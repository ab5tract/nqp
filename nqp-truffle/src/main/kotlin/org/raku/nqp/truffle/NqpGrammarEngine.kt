package org.raku.nqp.truffle

import com.oracle.truffle.api.CallTarget

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.GrammarEngine
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The grammar engine as the rest of NQP sees it -- the regex analog of
 * [NqpCodeEngine].
 *
 * This is the class `GrammarEngines` looks up by name, and the only one it
 * needs to know about: everything below here is free to change without the
 * boot-classpath side being able to name any of it. The name is load-bearing
 * -- it is a string in `GrammarEngine.kt` -- so it must not move package.
 *
 * Matchers are parsed in the process's one polyglot context ([NqpPolyglot]),
 * the same one the code engine's programs live in.
 */
class NqpGrammarEngine : GrammarEngine {

    /**
     * A compiled regex.
     *
     * The pass name rides alongside the call target rather than inside it:
     * matching answers a position, and what the cursor is then told is the
     * caller's business, exactly as it is on the bytecode path.
     */
    private data class Program(
        val target: CallTarget,
        val passName: String,
        val scan: Boolean,
        val resumable: Boolean,
    )

    override fun compile(encoded: String): Any {
        val d = RxWire.decode(encoded)
        /* The Source is named after the rule where it has a plain name, so
         * compilation traces and statistics say which rule they mean; a
         * computed name (the NUL-prefixed callback marker) or none at all
         * gets the generic label. */
        val name = if (d.passName.isEmpty() || d.passName.startsWith("\u0000")) "rx" else d.passName
        return Program(NqpPolyglot.compile(encoded, name), d.passName, d.scan, d.resumable)
    }

    override fun match(
        program: Any,
        tc: ThreadContext,
        cursor: SixModelObject,
        cursorClass: SixModelObject,
        target: String,
        from: Int,
        invocantFrom: Int,
        restart: Boolean,
        invocant: SixModelObject?,
        callback: SixModelObject?,
    ): SixModelObject {
        val p = program as Program
        val rx = NqpCursor(tc, cursor, cursorClass, target, callback)

        if (restart) {
            /* `!cursor_next`: the invocant is the previously PASSED cursor,
             * whose saved choice state answers "the next match". The state
             * is taken, resumed -- entering the loop failing, which pops the
             * most recent choice point -- and stored again if the new pass
             * still has choices left. No state means nothing left to offer.
             * The new cursor already carries clones of the old cstack and
             * bstack, courtesy of !cursor_start_all's restart branch. */
            val state = if (invocant == null) null else STATES.remove(invocant)
            if (state == null) {
                call(tc, cursor, "!cursor_fail")
                return cursor
            }
            rx.setCaptureBases(state.captureBaseC, state.captureBaseB)
            val stateOut = arrayOfNulls<RxVmNode.EngineState>(1)
            var end2 = p.target.call(target, 0, rx, stateOut, state) as Int
            var at2 = state.scanAt
            if (end2 < 0 && at2 >= 0) {
                /* The resumed choices are exhausted; the scan is the next
                 * one. Retry whole matches from the following positions,
                 * exactly what the bytecode path's scan marks resume to. */
                /* NFG: positions are grapheme indices, so scan by one grapheme.
                 * A CR LF pair is a single grapheme, so no fixup is needed. */
                val eos = rx.eos()
                at2 += 1
                while (at2 <= eos) {
                    end2 = p.target.call(target, at2, rx, stateOut) as Int
                    if (end2 >= 0) break
                    at2 += 1
                }
            }
            if (end2 >= 0 && at2 >= 0) {
                Ops.bindattr_i(cursor, cursorClass, "\$!from", at2.toLong(), tc)
                if (stateOut[0] == null) stateOut[0] = RxVmNode.EngineState.scanOnly(at2)
                else stateOut[0]?.scanAt = at2
            }
            finish(tc, cursor, cursorClass, rx, p, end2, stateOut[0])
            return cursor
        }

        var at = from
        var end: Int
        val stateOut = if (p.resumable) arrayOfNulls<RxVmNode.EngineState>(1) else null
        /* A scan retries the whole body one character further along until it
         * matches. It applies only when the invocant's $!from is -1, which is
         * a top-level parse; a subrule is called at a position and must match
         * there or not at all. Getting that backwards makes every subrule
         * silently search forward, which parses -- just not the language. */
        if (p.scan && invocantFrom == -1) {
            /* NFG: grapheme indices. A CR LF pair is one grapheme, so scanning
             * by one grapheme never offers a position inside it -- which is
             * exactly the atom-not-char stepping the old UTF-16 code emulated. */
            val eos = rx.eos()
            end = RxVmNode.NO_MATCH
            while (at <= eos) {
                end = p.target.call(target, at, rx, stateOut) as Int
                if (end >= 0) break
                at += 1
            }
            if (end < 0) at = from
        } else {
            end = p.target.call(target, at, rx, stateOut) as Int
        }

        /* Where the match began is the cursor's $!from, and the scan is what
         * moves it: !cursor_start_all set it to the position the rule was
         * called at, which is no longer where the match starts. */
        if (end >= 0 && at != from) {
            Ops.bindattr_i(cursor, cursorClass, "\$!from", at.toLong(), tc)
        }

        if (end >= 0 && p.scan && invocantFrom == -1 && stateOut != null) {
            /* A scanning rule can always offer later start positions, even
             * when the match itself left no choice points. */
            if (stateOut[0] == null) stateOut[0] = RxVmNode.EngineState.scanOnly(at)
            else stateOut[0]?.scanAt = at
        }
        finish(tc, cursor, cursorClass, rx, p, end, stateOut?.get(0))
        return cursor
    }

    /**
     * Ends a match the way the bytecode path does -- fail, or pass under
     * the rule's name -- and, for a resumable rule that still has choice
     * points, records the engine state under the passed cursor and marks
     * the cursor restartable so `!cursor_next` comes back here.
     */
    private fun finish(
        tc: ThreadContext,
        cursor: SixModelObject,
        cursorClass: SixModelObject,
        rx: NqpCursor,
        p: Program,
        end: Int,
        state: RxVmNode.EngineState?,
    ) {
        if (end < 0) {
            call(tc, cursor, "!cursor_fail")
            return
        }
        if (p.passName.isEmpty()) {
            call(tc, cursor, "!cursor_pass", end)
        } else if (p.passName.startsWith("\u0000cb:")) {
            /* A computed pass name -- the late-bound `regex ::($name)`
             * form. The NUL-prefixed marker carries the callback index of
             * the piece that answers the name, resolved now, at pass time,
             * the same moment the bytecode path evaluates it. */
            val name = rx.callbackName(p.passName.substring(4).toInt(), end)
            call(tc, cursor, "!cursor_pass", end, name)
        } else {
            call(tc, cursor, "!cursor_pass", end, p.passName)
        }
        if (p.resumable && state != null) {
            /* What !cursor_pass with :backtrack would have done: keep the
             * rule re-enterable. The pass above nulled the bstack, and the
             * restart branch of !cursor_start_all clones it, so it has to
             * be an (empty) array rather than null. */
            val bases = rx.captureBases()
            state.captureBaseC = bases[0]
            state.captureBaseB = bases[1]
            Ops.bindattr(cursor, cursorClass, "\$!restart",
                Ops.getattr(cursor, cursorClass, "\$!regexsub", tc), tc)
            val bstack = Ops.getattr(cursor, cursorClass, "\$!bstack", tc)
            if (bstack == null || Ops.isnull(bstack) != 0L ||
                Ops.isconcrete(bstack, tc) == 0L
            ) {
                Ops.bindattr(cursor, cursorClass, "\$!bstack",
                    Ops.create(Ops.bootintarray(tc), tc), tc)
            }
            STATES[cursor] = state
        }
    }

    companion object {
        /* The saved choice state of every resumable cursor that PASSED with
         * something left to offer; `!cursor_next` takes it back out. Weak
         * on the cursor so an abandoned match does not pin its state --
         * but an EngineState holds pending captures strongly, and a
         * WeakHashMap only expunges on access, so between eval-server runs
         * these entries pinned each finished run's whole universe. Cleared
         * at every run boundary like the other engine caches. */
        private val STATES =
            java.util.Collections.synchronizedMap(
                java.util.WeakHashMap<SixModelObject, RxVmNode.EngineState>())

        init {
            org.raku.nqp.dispatch.DispatchBootstrap.registerResettable { STATES.clear() }
        }

        private val INVOCANT =
            CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
        private val INVOCANT_INT = CallSiteDescriptor(
            byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT), null)
        private val INVOCANT_INT_STR = CallSiteDescriptor(
            byteArrayOf(
                CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT,
                CallSiteDescriptor.ARG_STR,
            ),
            null,
        )

        private fun call(tc: ThreadContext, cursor: SixModelObject, name: String) {
            val method = Ops.findmethod(cursor, name, tc)
            Ops.invokeDirect(tc, method, INVOCANT, arrayOf<Any?>(cursor))
        }

        private fun call(tc: ThreadContext, cursor: SixModelObject, name: String, arg: Int) {
            val method = Ops.findmethod(cursor, name, tc)
            Ops.invokeDirect(tc, method, INVOCANT_INT, arrayOf<Any?>(cursor, arg.toLong()))
        }

        private fun call(
            tc: ThreadContext,
            cursor: SixModelObject,
            name: String,
            pos: Int,
            arg: String,
        ) {
            val method = Ops.findmethod(cursor, name, tc)
            Ops.invokeDirect(
                tc, method, INVOCANT_INT_STR,
                arrayOf<Any?>(cursor, pos.toLong(), arg),
            )
        }
    }
}
