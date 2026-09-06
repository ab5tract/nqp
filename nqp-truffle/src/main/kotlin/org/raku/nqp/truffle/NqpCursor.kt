package org.raku.nqp.truffle

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The engine's view of a real NQP Cursor.
 *
 * Every step here is the one the bytecode path takes, so the two agree about
 * what a match does to a cursor:
 *
 *  - a subrule is `$!pos := pos` followed by a method call, and the position
 *    it reached is `$!pos` on the cursor it answered, negative meaning no
 *    match;
 *  - a span becomes a capture through `!cursor_start_subcapture`,
 *    `!cursor_pass` and `!cursor_capture` -- a cursor is built for the span,
 *    passed at its end, and then captured, because a capture in a match tree
 *    is a cursor rather than a pair of offsets;
 *  - a rule's own result is captured directly, since it is a cursor already.
 *
 * @param cursorClass the class the cursor's attributes are DECLARED in --
 *   `$?CLASS` from `!cursor_start_all`, which is what the bytecode path uses.
 *   The cursor's own WHAT is a subclass for any real grammar, and looking an
 *   attribute up through it fails with "No such attribute".
 * @param callback the rule's own code for anything the engine cannot express
 *   -- a `{ ... }` or `<?{ ... }>` -- as a static code object, or null when
 *   the rule has none.
 */
class NqpCursor(
    private val tc: ThreadContext,
    private val cursor: SixModelObject,
    private val cursorClass: SixModelObject,
    private val target: String,
    private val callback: SixModelObject?,
) : RxCursor {

    private var callbackClosure: SixModelObject? = null

    /* Where the engine's own captures start on the cursor's stacks; -1
     * until the first capture records them. See truncateCaptures. */
    private var captureBaseC: Int = -1
    private var captureBaseB: Int = -1

    /* NFG: the target as grapheme atoms, built once. The engine indexes by
     * grapheme, so positions are indices into this and eos is its length. */
    private val atomArray: IntArray = NFGString.atomsOf(target)

    override fun target(): String = target

    override fun atoms(): IntArray = atomArray

    override fun eos(): Int = atomArray.size

    /**
     * Calls a rule of the grammar.
     *
     * **Partial evaluation stops here, and that is structural.** What Truffle
     * buys is specializing the interpreter loop against a program it can see
     * is constant, and it does that across calls too -- when the callee is
     * more Truffle nodes, PE inlines through and specializes the pair. The
     * callee here is an NQP `CodeRef`: bytecode the JAST backend emitted,
     * which PE cannot see into. Hence the `@TruffleBoundary` on the wrapper
     * in [RxVmNode]. A rule's specialized region therefore ends at every
     * `<foo>`, and a grammar is mostly `<foo>`, so the stretch PE gets to
     * work on is often a few character tests.
     *
     * The same fact is why replacing NQP's dispatch with Truffle has to
     * FOLLOW code generation rather than lead it. A `DirectCallNode` inline
     * cache pays off because PE inlines through it into the callee's nodes;
     * against a bytecode callee there is nothing to inline into, and it would
     * lose to the `invokedynamic` site with a guard chain that NQP already
     * has there.
     *
     * Distinct from that, and fixable without any of it: the bookkeeping
     * below. `findmethod` looks the rule up by name on every call and the
     * argument array is fresh each time, where the bytecode path resolves
     * both once at its call site.
     */
    override fun callSubrule(name: String, pos: Int, args: RxArgs?): Any? {
        Ops.bindattr_i(cursor, cursorClass, "\$!pos", pos.toLong(), tc)
        val method = Ops.findmethod(cursor, name, tc)
        if (args == null) {
            Ops.invokeDirect(tc, method, INVOCANT, arrayOf<Any?>(cursor))
        } else {
            /* The invocant first, then the arguments as the bytecode path
             * passes them: a str argument natively, an int argument as a
             * long, which is what the call site says they are. */
            val call = arrayOfNulls<Any?>(args.count() + 1)
            call[0] = cursor
            System.arraycopy(args.values, 0, call, 1, args.count())
            Ops.invokeDirect(tc, method, callSite(args), call)
        }
        return Ops.result_o(tc.curFrame!!)
    }

    override fun nextMatch(subCursor: Any?): Any? {
        if (subCursor !is SixModelObject) return null
        val next = Ops.findmethod(subCursor, "!cursor_next", tc)
        Ops.invokeDirect(tc, next, INVOCANT, arrayOf<Any?>(subCursor))
        return Ops.result_o(tc.curFrame!!)
    }

    /* The engine snapshots and restores these across a pass/resume pair,
     * so a resumed run truncates the (cloned) capture stacks correctly. */
    fun captureBases(): IntArray = intArrayOf(captureBaseC, captureBaseB)
    fun setCaptureBases(c: Int, b: Int) { captureBaseC = c; captureBaseB = b }

    override fun reached(subCursor: Any?): Int {
        if (subCursor !is SixModelObject) return RxVmNode.NO_MATCH
        val pos = Ops.getattr_i(subCursor, cursorClass, "\$!pos", tc)
        return if (pos < 0) RxVmNode.NO_MATCH else pos.toInt()
    }

    override fun charProp(property: String, pos: Int): Boolean {
        // NFG: pos is a grapheme index; test the property of the grapheme's base
        // codepoint (synthetics carry their base first).
        val atom = atomArray[pos]
        val base = if (atom >= 0) atom else org.raku.nqp.runtime.NFGSynthetics.baseOf(atom)
        return Ops.ischarprop(property, String(Character.toChars(base)), 0L) != 0L
    }

    /**
     * Runs one piece of the rule's own code, and says whether it held.
     *
     * The closure is taken HERE rather than in the rule's prologue, and that
     * is the whole trick. `rxmatch` is a static call, so no frame was pushed
     * for it: `tc.curFrame` is still the rule's own frame, which is exactly
     * the frame the code has to close over. Doing it at this moment also
     * means a rule whose callbacks never fire pays nothing -- where building
     * the closure up front would allocate once per call, and a grammar calls
     * its rules a great many times.
     *
     * The truth of the result is what `<?{ ... }>` turns on; a plain
     * `{ ... }` is run for its effect and the answer is ignored.
     */
    override fun callbackHolds(index: Int, pos: Int): Boolean =
        Ops.istrue(runCallback(index, pos), tc) != 0L

    /** A callback piece whose value is a subrule call's cursor. */
    override fun callbackCursor(index: Int, pos: Int): Any? = runCallback(index, pos)

    /** A computed pass name: the piece answers the string. */
    override fun callbackName(index: Int, pos: Int): String =
        Ops.unbox_s(runCallback(index, pos), tc) ?: ""

    /** A dynamic quantifier's bounds: the piece answers a two-int array. */
    override fun callbackBounds(index: Int, pos: Int): IntArray {
        val bounds = runCallback(index, pos)
        return intArrayOf(
            Ops.atpos_i(bounds, 0, tc).toInt(),
            Ops.atpos_i(bounds, 1, tc).toInt(),
        )
    }

    private fun runCallback(index: Int, pos: Int): SixModelObject? {
        if (TRACE) System.err.println("rx{ callback $index @ $pos")
        var closure = callbackClosure
        if (closure == null) {
            closure = Ops.takeclosure(callback, tc)
            callbackClosure = closure
        }
        Ops.invokeDirect(
            tc, closure, CALLBACK,
            arrayOf<Any?>(index.toLong(), cursor, cursorClass, pos.toLong()),
        )
        return Ops.result_o(tc.curFrame!!)
    }

    override fun captureSpan(name: String, from: Int, to: Int) {
        /* No rule produced this, so there is no cursor for it yet: build one
         * over the span and pass it, which is what makes it a capture NQP can
         * put in the match tree. */
        val start = Ops.findmethod(cursor, "!cursor_start_subcapture", tc)
        Ops.invokeDirect(tc, start, INVOCANT_INT, arrayOf<Any?>(cursor, from.toLong()))
        val sub = Ops.result_o(tc.curFrame!!)

        val pass = Ops.findmethod(sub, "!cursor_pass", tc)
        Ops.invokeDirect(tc, pass, INVOCANT_INT, arrayOf<Any?>(sub, to.toLong()))

        captureCursor(name, sub)
    }

    /**
     * Asks the grammar's NFA which branches of a named alternation to try.
     *
     * `!alt` is the bytecode path's own entry point, and it answers by
     * pushing four ints per branch onto the cursor's bstack -- mark, pos,
     * rep, capture height -- best LAST, because the bytecode engine reaches
     * them by popping. The marks it pushes are whatever was handed to it, so
     * passing the branch indices themselves makes the answer come back in the
     * engine's own terms.
     *
     * The bstack is left as it was found. The engine keeps its choice points
     * in its own stack and this rule is ratcheted anyway, so entries left
     * behind would be read later as backtracking that never happened.
     */
    override fun altOrder(name: String, pos: Int, branches: Int): IntArray {
        val bstack = Ops.getattr(cursor, cursorClass, "\$!bstack", tc)
        if (bstack == null || Ops.isnull(bstack) != 0L) return RxCursor.NO_BRANCHES
        val before = bstack.elems(tc).toInt()

        val marks = Ops.create(Ops.bootintarray(tc), tc)
        for (i in 0 until branches) {
            tc.nativeI = i.toLong()
            marks.push_native(tc)
        }

        /* No $!pos binding here: !alt takes the position as an argument and
         * the bytecode path does not touch the attribute either. Setting it
         * would leave a mid-match value behind for !cursor_capture to record. */
        val alt = Ops.findmethod(cursor, "!alt", tc)
        Ops.invokeDirect(
            tc, alt, INVOCANT_INT_STR_OBJ,
            arrayOf<Any?>(cursor, pos.toLong(), name, marks),
        )

        val after = bstack.elems(tc).toInt()
        val found = (after - before) / 4
        if (found <= 0) {
            bstack.set_elems(tc, before.toLong())
            return RxCursor.NO_BRANCHES
        }
        val order = IntArray(found)
        /* Reversed: !alt pushes the best branch last so that popping finds it
         * first, and the engine wants them best first. */
        for (i in 0 until found) {
            bstack.at_pos_native(tc, before + 4L * (found - 1 - i))
            order[i] = tc.nativeI.toInt()
        }
        bstack.set_elems(tc, before.toLong())
        return order
    }

    override fun captureCursor(name: String, subCursor: Any?) {
        /* The bases are read before the first capture lands, so truncation
         * knows where the engine's own contributions start. !cursor_capture
         * grows the cstack by one and the bstack by one four-int frame per
         * capture, and undoing a capture takes both back. */
        if (captureBaseC < 0) {
            /* A cursor that has captured nothing yet holds a TYPE OBJECT in
             * $!cstack (Cursor.nqp tests nqp::defined, not null), so the
             * guard here is concreteness, not nullness. */
            val cstack = Ops.getattr(cursor, cursorClass, "\$!cstack", tc)
            captureBaseC = if (cstack == null || Ops.isconcrete(cstack, tc) == 0L) 0
                           else cstack.elems(tc).toInt()
            val bstack = Ops.getattr(cursor, cursorClass, "\$!bstack", tc)
            captureBaseB = if (bstack == null || Ops.isconcrete(bstack, tc) == 0L) 0
                           else bstack.elems(tc).toInt()
        }
        val capture = Ops.findmethod(cursor, "!cursor_capture", tc)
        Ops.invokeDirect(
            tc, capture, INVOCANT_OBJ_STR,
            arrayOf<Any?>(cursor, subCursor, name),
        )
    }

    override fun truncateCaptures(entries: Int) {
        /* Only ever called after a sync, so the bases are set. Concreteness
         * guards for the same reason as above. */
        val cstack = Ops.getattr(cursor, cursorClass, "\$!cstack", tc)
        if (cstack != null && Ops.isconcrete(cstack, tc) != 0L) {
            cstack.set_elems(tc, (captureBaseC + entries).toLong())
        }
        val bstack = Ops.getattr(cursor, cursorClass, "\$!bstack", tc)
        if (bstack != null && Ops.isconcrete(bstack, tc) != 0L) {
            bstack.set_elems(tc, (captureBaseB + 4L * entries))
        }
    }

    companion object {
        private val TRACE: Boolean = System.getenv("NQP_RX_TRACE") != null

        private val INVOCANT =
            CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
        private val INVOCANT_INT = CallSiteDescriptor(
            byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT), null)
        private val INVOCANT_INT_STR_OBJ = CallSiteDescriptor(
            byteArrayOf(
                CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT,
                CallSiteDescriptor.ARG_STR, CallSiteDescriptor.ARG_OBJ,
            ),
            null,
        )
        private val INVOCANT_OBJ_STR = CallSiteDescriptor(
            byteArrayOf(
                CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ,
                CallSiteDescriptor.ARG_STR,
            ),
            null,
        )
        private val CALLBACK = CallSiteDescriptor(
            byteArrayOf(
                CallSiteDescriptor.ARG_INT, CallSiteDescriptor.ARG_OBJ,
                CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT,
            ),
            null,
        )

        /**
         * The call site for a set of literal arguments, derived once.
         *
         * There is one [RxArgs] per subrule call site in the program and its
         * kinds never change, so the descriptor is as constant as the call
         * is; building one per match would allocate on the hot path for
         * nothing.
         */
        private fun callSite(args: RxArgs): CallSiteDescriptor {
            (args.callSite as CallSiteDescriptor?)?.let { return it }
            val flags = ByteArray(args.count() + 1)
            flags[0] = CallSiteDescriptor.ARG_OBJ
            for (i in 0 until args.count()) {
                flags[i + 1] = if (args.kinds[i] == RxArgs.INT) CallSiteDescriptor.ARG_INT
                               else CallSiteDescriptor.ARG_STR
            }
            val built = CallSiteDescriptor(flags, null)
            args.callSite = built
            return built
        }
    }
}
