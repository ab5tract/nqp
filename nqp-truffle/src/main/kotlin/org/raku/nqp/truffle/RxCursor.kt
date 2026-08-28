package org.raku.nqp.truffle

/**
 * What the engine needs from the thing a grammar rule is matching against.
 *
 * A regex in NQP is not a standalone matcher: it runs against a Cursor, which
 * carries the target, the position, the captures made so far, and the means
 * to call another rule. The engine is written against this interface rather
 * than the runtime's Cursor directly so the node set stays free of the
 * runtime, and so a plain implementation can drive it in tests where no NQP
 * is running.
 *
 * ## Captures are cursors
 *
 * NQP captures cursors, not spans: `!cursor_capture` is handed a cursor, and
 * that cursor lands in the match tree carrying its own captures. Two things
 * can be captured, and they arrive differently:
 *
 *  - a subrule's result, which is already a cursor; and
 *  - a subcapture, which is a span of the target that no rule produced -- the
 *    bytecode path builds a cursor for it with `!cursor_start_subcapture` and
 *    passes it before capturing.
 *
 * So the engine hands over a span or a cursor, and the implementation does
 * whatever NQP requires to make it a capture. That keeps the engine working
 * in offsets, which is what it can do quickly, without it deciding anything
 * about how a match tree is built.
 *
 * Everything here is a boundary as far as partial evaluation is concerned:
 * the implementations reach into NQP objects, which is exactly the code PE
 * should not see through. The match itself -- the part worth specializing --
 * touches only the target string and an integer position.
 */
interface RxCursor {

    /**
     * The branches of a named alternation, in the order to try them.
     *
     * A named alt is longest-token-match: which branch wins is decided by an
     * NFA the grammar carries, not by the order they were written in. The
     * engine does not own that NFA -- it asks the cursor, which runs the very
     * `!alt` the bytecode path runs, so the two cannot disagree about the
     * winner and the highwater mark gets updated either way.
     *
     * @param branches how many branches there are; the answer indexes them.
     * @return the branches worth trying, best first; empty means none match.
     */
    fun altOrder(name: String, pos: Int, branches: Int): IntArray

    /** The string being matched. */
    fun target(): String

    /** One past the last position a match may reach. */
    fun eos(): Int

    /**
     * Calls a named rule of the grammar at the given position.
     *
     * @param args the call's literal arguments, or null when it has none. A
     *   grammar's error-reporting rules -- `<.panic('...')>`,
     *   `<.FAILGOAL(...)>` -- are nearly all called with some, so a call that
     *   could not carry them would keep a great many rules on the bytecode
     *   path.
     * @return the cursor it produced, whether or not it matched; null when
     *   there is no such rule to call.
     */
    fun callSubrule(name: String, pos: Int, args: RxArgs?): Any?

    /**
     * The position a cursor reached, or [RxVmNode.NO_MATCH] when it did not
     * match. Kept separate from the call so the engine can hold on to a
     * cursor it may later capture without asking twice.
     */
    fun reached(subCursor: Any?): Int

    /**
     * Whether the character at [pos] has a Unicode property.
     *
     * Asked of the cursor rather than answered here for the same reason a
     * subrule is: the property tables are the runtime's, and the engine is
     * written so that it can be driven without one.
     */
    fun charProp(property: String, pos: Int): Boolean

    /**
     * Runs one piece of the rule's own code and says whether it held.
     *
     * A `{ ... }` or `<?{ ... }>` in a grammar is arbitrary NQP compiled into
     * the matcher's frame, where it can read the rule's lexicals. The engine
     * has no frame, so rather than move the code it comes back for it: the
     * rule hands over a block, and this is the call into it.
     *
     * @param index which piece; the descriptor carries only the number.
     * @param pos where the match has got to, which the code is entitled to
     *   see on the cursor.
     */
    fun callbackHolds(index: Int, pos: Int): Boolean

    /**
     * Runs one piece of the rule's own code whose value is a subrule call's
     * cursor -- an invocation the descriptor could not carry directly (a
     * lexical rule, computed arguments) travels as a callback piece instead
     * of a [callSubrule] name. Same channel as [callbackHolds]; the answer
     * is the subcursor rather than a truth.
     */
    fun callbackCursor(index: Int, pos: Int): Any?

    /**
     * Captures a span of the target under a name, building whatever cursor
     * NQP wants to represent it.
     */
    fun captureSpan(name: String, from: Int, to: Int)

    /** Captures a cursor a rule produced, under a name. */
    fun captureCursor(name: String, subCursor: Any?)

    /**
     * Runs the callback piece holding a dynamic quantifier's bounds
     * expression (`x ** {$n}`) and answers (min, max), -1 meaning
     * unbounded. Same channel as [callbackHolds].
     */
    fun callbackBounds(index: Int, pos: Int): IntArray

    /**
     * Takes back captures, keeping only the first [entries] the engine made.
     *
     * The engine holds captures pending and hands them over at the end --
     * except that a rule's own code (`{ ... }`) is entitled to see the
     * captures made so far through `$/`, so the engine syncs pending
     * captures to the cursor before running a callback. A later backtrack
     * past a synced capture then has to undo the cursor's record of it,
     * which the bytecode path does through its bstack marks and the engine
     * does with this.
     */
    fun truncateCaptures(entries: Int)

    companion object {
        @JvmField
        val NO_BRANCHES = IntArray(0)
    }

    /** A cursor over a bare string, for tests and measurement. */
    class OfString(private val target: String) : RxCursor {

        override fun target(): String = target

        override fun eos(): Int = target.length

        override fun callSubrule(name: String, pos: Int, args: RxArgs?): Any =
            throw UnsupportedOperationException("no subrules without a grammar: $name")

        override fun reached(subCursor: Any?): Int = RxVmNode.NO_MATCH

        override fun charProp(property: String, pos: Int): Boolean =
            throw UnsupportedOperationException(
                "no Unicode properties without a runtime: $property")

        override fun callbackHolds(index: Int, pos: Int): Boolean =
            throw UnsupportedOperationException("no rule code without a grammar: callback $index")

        override fun callbackCursor(index: Int, pos: Int): Any? =
            throw UnsupportedOperationException("no rule code without a grammar: callback $index")

        override fun captureSpan(name: String, from: Int, to: Int) { }

        override fun captureCursor(name: String, subCursor: Any?) { }

        override fun callbackBounds(index: Int, pos: Int): IntArray =
            throw UnsupportedOperationException("no rule code without a grammar: callback $index")

        override fun truncateCaptures(entries: Int) { }

        /* No grammar, so no NFA and no alternation to order. */
        override fun altOrder(name: String, pos: Int, branches: Int): IntArray = NO_BRANCHES
    }
}
