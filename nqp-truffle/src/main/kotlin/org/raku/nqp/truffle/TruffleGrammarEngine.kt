package org.raku.nqp.truffle

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.Truffle

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.GrammarEngine
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The engine as the rest of NQP sees it.
 *
 * This is the class `GrammarEngines` looks up by name, and the only one it
 * needs to know about: everything below here is free to change without the
 * boot-classpath side being able to name any of it. The name is load-bearing
 * -- it is a string in `GrammarEngine.kt` -- so it must not move package.
 *
 * One polyglot context is created for the process and never closed. It has to
 * be a context rather than loose RootNodes -- outside one, a root node runs
 * correctly and is never queued for compilation, so the partial evaluation
 * this whole design exists for silently does not happen.
 */
class TruffleGrammarEngine : GrammarEngine {

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
    )

    override fun compile(encoded: String): Any {
        /* The eval is what makes the language parse the descriptor; its
         * result is a polyglot Value, and calling through one would box every
         * argument of every match, so the call target is collected from where
         * parse left it instead. */
        Holder.CONTEXT.eval(Source.newBuilder(RxLanguage.ID, encoded, "rx").buildLiteral())
        val target = RxLanguage.PARSED[encoded]
            ?: throw IllegalStateException("the language parsed no matcher for: $encoded")
        val d = RxWire.decode(encoded)
        return Program(target, d.passName, d.scan)
    }

    override fun match(
        program: Any,
        tc: ThreadContext,
        cursor: SixModelObject,
        cursorClass: SixModelObject,
        target: String,
        from: Int,
        invocantFrom: Int,
        callback: SixModelObject?,
    ): SixModelObject {
        val p = program as Program
        val rx = NqpCursor(tc, cursor, cursorClass, target, callback)

        var at = from
        var end: Int
        /* A scan retries the whole body one character further along until it
         * matches. It applies only when the invocant's $!from is -1, which is
         * a top-level parse; a subrule is called at a position and must match
         * there or not at all. Getting that backwards makes every subrule
         * silently search forward, which parses -- just not the language. */
        if (p.scan && invocantFrom == -1) {
            val eos = target.length
            end = RxVmNode.NO_MATCH
            while (at <= eos) {
                end = p.target.call(target, at, rx) as Int
                if (end >= 0) break
                at++
            }
            if (end < 0) at = from
        } else {
            end = p.target.call(target, at, rx) as Int
        }

        /* Where the match began is the cursor's $!from, and the scan is what
         * moves it: !cursor_start_all set it to the position the rule was
         * called at, which is no longer where the match starts. */
        if (end >= 0 && at != from) {
            Ops.bindattr_i(cursor, cursorClass, "\$!from", at.toLong(), tc)
        }

        /* The bytecode path ends the same two ways, and a cursor that was
         * neither passed nor failed is not a usable match result. The name is
         * what makes !cursor_pass reduce; without it a rule would match the
         * right span and build nothing. */
        if (end < 0) {
            call(tc, cursor, "!cursor_fail")
        } else if (p.passName.isEmpty()) {
            call(tc, cursor, "!cursor_pass", end)
        } else {
            call(tc, cursor, "!cursor_pass", end, p.passName)
        }
        return cursor
    }

    /**
     * Built on first use rather than in the constructor: the runtime looks
     * the engine up while deciding whether there is one at all, and starting
     * a polyglot context is far too much to do just to answer that.
     */
    private object Holder {
        val CONTEXT: Context = Context.newBuilder(RxLanguage.ID)
            .allowExperimentalOptions(true)
            .build()

        init {
            val runtime = Truffle.getRuntime().name
            if (!runtime.contains("GraalVM")) {
                /* The fallback interpreter does no partial evaluation, so the
                 * engine would be a slower bytecode path with extra steps.
                 * Saying so beats measuring it later and blaming Truffle. */
                System.err.println(
                    "nqp: Truffle runtime is '$runtime', not an optimizing one;" +
                        " grammar matching will be slow." +
                        " Check --module-path and --add-modules on the runner.",
                )
            }
        }
    }

    companion object {
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
