package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/**
 * A pattern compiled to a flat program for [RxVmNode] to run.
 *
 * The node tree could not be partially evaluated: a quantifier that repeats
 * by recursion has an unbounded trip count, and PE inlines until it bails
 * with "Too deep inlining". Partial evaluation wants a loop, so the pattern
 * becomes a program and repetition becomes a jump.
 *
 * What that buys is the Futamura projection in its usual form: the code array
 * is compilation-final, so PE specializes the interpreter loop to *this*
 * program -- the generic matcher disappears and what is left is machine code
 * for one pattern, including one that only came into existence at run time.
 *
 * Alternation and repetition are both [SPLIT]: try one branch, and on failure
 * resume at the other with the position restored. That is the same
 * choice-point discipline as the bytecode backend's mark stack, only in a
 * form a compiler can see through.
 */
class RxProgram private constructor(
    /* @JvmField, not a Kotlin property: Truffle reads the FIELD to decide
     * what is compilation-final, and a private backing field behind a getter
     * is not the same thing. */
    @field:CompilationFinal(dimensions = 1) @JvmField val code: IntArray,
    @field:CompilationFinal(dimensions = 1) @JvmField val pool: Array<Any?>,
    @JvmField val registers: Int,
    @JvmField val captures: Int,
    splits: Int,
) {
    /** How many choice points the program can have live at once. */
    @JvmField
    val choiceDepth: Int =
        /* A split inside a repetition can stack up one choice point per
         * repetition, so the depth is not statically known; start with room
         * for a few per split and grow from there. */
        maxOf(8, splits * 4)

    /** One character's worth of membership test, kept out of the code array. */
    fun interface CharPred {
        fun holds(codepoint: Int): Boolean
    }

    companion object {
        /* Instructions are an opcode followed by its operands, all in one int
         * array so the whole program is a single compilation-final constant.
         * `const` so that `when` over them compiles to a switch. */
        const val MATCH = 0        // accept
        const val CHAR = 1         // literal(idx), flags
        const val ONE = 2          // predicate(idx)
        const val ANCHOR = 3       // kind
        const val SPLIT = 4        // preferred pc, alternative pc
        const val JMP = 5          // pc
        const val SUB = 6          // name(idx), flags, capture(idx+1 or 0), args(idx+1 or 0)
        const val MARK = 7         // register
        const val EMPTY_CHECK = 8  // register -- fail if nothing consumed
        const val CAP_START = 9    // register
        const val CAP_END = 10     // register, name(idx)
        const val ADVANCE = 11     // step one codepoint, or fail at the end
        const val CUT_MARK = 18    // register -- remember the choice-point height
        const val CUT = 19         // register -- drop every choice point made since

        /* name(idx), count, then one pc per branch. The order to try them in
         * is asked of the cursor at match time, so it is not in the code. */
        const val ALT_LTM = 20

        /* predicate(idx), negate -- tests without moving. Its own opcode
         * rather than a flag on the rest because the end of the string is a
         * special case: a NEGATED zero-width class succeeds there, having
         * nothing to exclude, where a consuming one has to fail. */
        const val ONE_ZW = 21

        /* property(idx), flags -- one character against a Unicode property.
         * The test is the runtime's and is reached through the cursor, so
         * this is a boundary in the same way a subrule is. */
        const val UNIPROP = 22

        /* index, flags -- back into the rule's own frame for a piece of NQP
         * the engine cannot express. A boundary by nature: the far side is
         * compiled bytecode holding the rule's lexicals. */
        const val QASTNODE = 23

        /*
         * The character classes worth their own opcode. ONE reaches a
         * predicate through an interface call, and across patterns that call
         * site sees every predicate there is -- megamorphic, so partial
         * evaluation cannot fold it. These carry the test in the opcode
         * instead, where it becomes a constant once the program is.
         */
        const val DIGIT = 12       // negate
        const val WORD = 13        // negate
        const val SPACE = 14       // negate
        const val ANY = 15         // (no operands)
        const val RANGE1 = 16      // lo, hi, negate
        const val CHAR1 = 17       // codepoint, negate

        /* (no operands) -- a newline, where a CR also consumes a directly
         * following LF. The strings this backend matches are not NFG, so a
         * "\r\n" is two characters; a consuming newline class must take
         * both or every position after it is off by one on CRLF input. The
         * bytecode engine does the same through Ops.checkcrlf. Negated and
         * zero-width newline classes stay ordinary predicates: nothing is
         * consumed, so there is no pair to keep whole. */
        const val NL = 24

        /* CHAR flags. */
        const val F_NEGATE = 1
        const val F_ZEROWIDTH = 2
        const val F_IGNORECASE = 4

        /** Compiles a pattern tree. Runs once, when the pattern is first seen. */
        @JvmStatic
        @TruffleBoundary
        fun compile(tree: RxTree.Node): RxProgram {
            val b = Builder()
            b.emit(tree)
            b.op(MATCH)
            return RxProgram(b.codeArray(), b.poolArray(), b.registers, b.captures, b.splits)
        }
    }

    private class Builder {
        private val code = ArrayList<Int>()
        private val pool = ArrayList<Any?>()
        var registers = 0
        var captures = 0
        var splits = 0

        fun op(opcode: Int, vararg operands: Int): Int {
            val at = code.size
            if (opcode == SPLIT) splits++
            code.add(opcode)
            for (o in operands) code.add(o)
            return at
        }

        fun constant(value: Any?): Int {
            pool.add(value)
            return pool.size - 1
        }

        fun reg(): Int = registers++

        fun patch(at: Int, value: Int) {
            code[at] = value
        }

        fun here(): Int = code.size

        fun codeArray(): IntArray = IntArray(code.size) { code[it] }

        fun poolArray(): Array<Any?> = pool.toTypedArray()

        fun emit(node: RxTree.Node) {
            when (node) {
                is RxTree.Seq -> for (part in node.parts) emit(part)

                is RxTree.Literal -> {
                    val flags = (if (node.negate) F_NEGATE else 0) or
                        (if (node.zeroWidth) F_ZEROWIDTH else 0) or
                        (if (node.ignoreCase) F_IGNORECASE else 0)
                    /* Most literals in a grammar are one character; comparing
                     * a codepoint beats a region match against a one-char
                     * string. */
                    if (flags == 0 && node.text.codePointCount(0, node.text.length) == 1) {
                        op(CHAR1, node.text.codePointAt(0), 0)
                    } else {
                        op(CHAR, constant(node.text), flags)
                    }
                }

                is RxTree.One -> emitOne(node)

                is RxTree.Anchor -> op(ANCHOR, node.kind.ordinal)

                is RxTree.Alt -> emitAlt(node.branches, 0)

                is RxTree.AltLtm -> emitAltLtm(node)

                is RxTree.Quant -> emitQuant(node)

                is RxTree.Sub -> {
                    /* Zero means the result is not captured; otherwise the
                     * pool index of the name, biased so zero can mean "none". */
                    val capture = if (node.capture == null) 0 else constant(node.capture) + 1
                    /* Biased the same way, for the same reason: a call with no
                     * arguments is by far the common one and says so with a 0. */
                    val args = if (node.args == null) 0 else constant(node.args) + 1
                    op(
                        SUB, constant(node.name),
                        (if (node.negate) F_NEGATE else 0) or
                            (if (node.zeroWidth) F_ZEROWIDTH else 0),
                        capture, args,
                    )
                    if (node.capture != null) captures++
                }

                is RxTree.QastNode -> op(
                    QASTNODE, node.index,
                    (if (node.negate) F_NEGATE else 0) or
                        (if (node.zeroWidth) F_ZEROWIDTH else 0),
                )

                is RxTree.UniProp -> op(
                    UNIPROP, constant(node.property),
                    (if (node.negate) F_NEGATE else 0) or
                        (if (node.zeroWidth) F_ZEROWIDTH else 0),
                )

                is RxTree.Scan -> {
                    /*
                     *   L0: SPLIT L1, L2      try matching where we are
                     *   L1: <body> ...
                     *   L2: ADVANCE           nothing here; step one and retry
                     *       JMP L0
                     * The split's alternative arm is what a failure inside the
                     * body resumes at, with the position it had on entry,
                     * which is exactly what scanning needs.
                     */
                    val loop = here()
                    val split = op(SPLIT, 0, 0)
                    patch(split + 1, here())
                    emit(node.body)
                    val done = op(JMP, 0)
                    patch(split + 2, here())
                    op(ADVANCE)
                    op(JMP, loop)
                    patch(done + 1, here())
                }

                is RxTree.Capture -> {
                    val r = reg()
                    captures++
                    op(CAP_START, r)
                    emit(node.body)
                    op(CAP_END, r, constant(node.name))
                }
            }
        }

        private fun emitOne(node: RxTree.One) {
            val pred = node.pred
            if (node.zeroWidth) {
                /* Negation stays inside the opcode: the predicate itself
                 * carries it, and the end-of-string rule needs to know. */
                if (pred is RxTree.Negated) op(ONE_ZW, constant(pred.of), 1)
                else op(ONE_ZW, constant(pred), 0)
                return
            }
            /* A predicate the opcode set knows becomes that opcode; the rest
             * still go through the interface. Compared by IDENTITY, which is
             * why RxTree holds them as single constants. */
            when {
                pred === RxTree.ANY -> op(ANY)
                pred === RxTree.DIGIT -> op(DIGIT, 0)
                pred === RxTree.WORD -> op(WORD, 0)
                pred === RxTree.SPACE -> op(SPACE, 0)
                pred === RxTree.NEWLINE -> op(NL)
                pred is RxTree.Negated -> {
                    val of = pred.of
                    when {
                        of === RxTree.DIGIT -> op(DIGIT, 1)
                        of === RxTree.WORD -> op(WORD, 1)
                        of === RxTree.SPACE -> op(SPACE, 1)
                        of is RxTree.Range -> op(RANGE1, of.lo, of.hi, 1)
                        else -> op(ONE, constant(pred))
                    }
                }
                pred is RxTree.Range -> op(RANGE1, pred.lo, pred.hi, 0)
                else -> op(ONE, constant(pred))
            }
        }

        /*
         * a|b|c becomes SPLIT a, (SPLIT b, c): each branch jumps clear of the
         * rest once it has matched, and a failure inside one resumes at the
         * next.
         */
        fun emitAlt(branches: List<RxTree.Node>, i: Int) {
            if (i == branches.size - 1) {
                emit(branches[i])
                return
            }
            val split = op(SPLIT, 0, 0)
            patch(split + 1, here())
            emit(branches[i])
            val jump = op(JMP, 0)
            patch(split + 2, here())
            emitAlt(branches, i + 1)
            patch(jump + 1, here())
        }

        /*
         * A longest-token alternation. The branches are laid out one after
         * another, each ending in a jump to the common exit, and the header
         * holds a slot per branch which is patched to where that branch
         * starts. Which slot to take first is decided at match time by the
         * grammar's NFA, so the code says only where each branch is.
         */
        fun emitAltLtm(alt: RxTree.AltLtm) {
            val cut = if (alt.ratchet) reg() else -1
            if (cut >= 0) op(CUT_MARK, cut)

            val n = alt.branches.size
            val header = op(ALT_LTM, constant(alt.name), n)
            repeat(n) { code.add(0) }
            /* Every branch can be a choice point until one of them wins. */
            splits += n

            val jumps = ArrayList<Int>()
            for (i in 0 until n) {
                patch(header + 3 + i, here())
                emit(alt.branches[i])
                jumps.add(op(JMP, 0))
            }
            val out = here()
            for (jump in jumps) patch(jump + 1, out)

            if (cut >= 0) op(CUT, cut)
        }

        fun emitQuant(quant: RxTree.Quant) {
            /*
             * A ratcheted quantifier keeps what it took. NQP's `token` and
             * `rule` make every quantifier in them ratcheted, so this is the
             * common case rather than an exotic one: `\d+` in a token takes
             * all the digits and never gives one back to help what follows
             * match. Expressed here by remembering the choice-point height
             * before the loop and cutting back to it after -- the choice
             * points the loop made are simply gone, so a later failure
             * backtracks past the whole quantifier instead of into it.
             */
            val cut = if (quant.ratchet) reg() else -1
            if (cut >= 0) op(CUT_MARK, cut)
            emitQuantBody(quant)
            if (cut >= 0) op(CUT, cut)
        }

        /*
         * Greedy x* is
         *      L1: SPLIT L2, L3
         *      L2: MARK r ; <x> ; EMPTY_CHECK r ; JMP L1
         *      L3:
         * and frugal swaps the split's arms, which is the whole difference.
         * The mark and check are what stop a body that can match nothing from
         * looping forever, the same job the bytecode engine gives its rep
         * counter.
         */
        fun emitQuantBody(quant: RxTree.Quant) {
            if (quant.separator != null) {
                emitQuantSeparated(quant)
                return
            }
            repeat(quant.min) { emit(quant.body) }
            if (quant.max < 0) {
                val r = reg()
                val loop = here()
                val split = op(SPLIT, 0, 0)
                val body = here()
                op(MARK, r)
                emit(quant.body)
                op(EMPTY_CHECK, r)
                op(JMP, loop)
                val out = here()
                patch(split + 1, if (quant.greedy) body else out)
                patch(split + 2, if (quant.greedy) out else body)
                return
            }
            /* A bounded repetition is just that many optional copies. */
            val splits = ArrayList<Int>()
            for (i in quant.min until quant.max) {
                val split = op(SPLIT, 0, 0)
                splits.add(split)
                patch(split + (if (quant.greedy) 1 else 2), here())
                emit(quant.body)
            }
            val out = here()
            for (split in splits) patch(split + (if (quant.greedy) 2 else 1), out)
        }

        /*
         * `body % sep` is body (sep body)*, and the shape of that is the whole
         * of what makes it different: the separator belongs to the LOOP, not
         * to a repetition. The choice point sits before the separator, so when
         * the body after it fails the position resumed at is the end of the
         * last body -- which is why `a+ % ','` stops before a trailing comma
         * rather than eating it.
         *
         * That is exactly where QAST::Compiler's greedy arm puts its mark:
         * after the body, before the separator, loop.
         */
        fun emitQuantSeparated(quant: RxTree.Quant) {
            val sep = quant.separator!!
            val min = maxOf(quant.min, 0)
            /* min 0 max 0 is a quantifier that wants nothing at all, and the
             * backend emits no instructions for it. */
            if (min == 0 && quant.max == 0) return

            /* The arms of every optional copy, all leaving at the same exit. */
            val leaving = ArrayList<Int>()
            if (min == 0) {
                val split = op(SPLIT, 0, 0)
                leaving.add(split)
                patch(split + (if (quant.greedy) 1 else 2), here())
            }
            emit(quant.body)
            for (i in 2..min) {
                emit(sep)
                emit(quant.body)
            }

            if (quant.max < 0) {
                val r = reg()
                val loop = here()
                val split = op(SPLIT, 0, 0)
                val again = here()
                op(MARK, r)
                emit(sep)
                emit(quant.body)
                /* A separator and a body that between them consumed nothing
                 * would loop forever, the same way a bare body would. */
                op(EMPTY_CHECK, r)
                op(JMP, loop)
                val out = here()
                patch(split + 1, if (quant.greedy) again else out)
                patch(split + 2, if (quant.greedy) out else again)
                for (s in leaving) patch(s + (if (quant.greedy) 2 else 1), out)
                return
            }

            for (i in maxOf(min, 1) until quant.max) {
                val split = op(SPLIT, 0, 0)
                leaving.add(split)
                patch(split + (if (quant.greedy) 1 else 2), here())
                emit(sep)
                emit(quant.body)
            }
            val out = here()
            for (s in leaving) patch(s + (if (quant.greedy) 2 else 1), out)
        }
    }
}
