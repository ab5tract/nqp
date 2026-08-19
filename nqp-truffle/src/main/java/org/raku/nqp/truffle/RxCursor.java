package org.raku.nqp.truffle;

/**
 * What the engine needs from the thing a grammar rule is matching against.
 *
 * <p>A regex in NQP is not a standalone matcher: it runs against a Cursor,
 * which carries the target, the position, the captures made so far, and the
 * means to call another rule. The engine is written against this interface
 * rather than the runtime's Cursor directly so the node set stays free of
 * the runtime, and so a plain implementation can drive it in tests where no
 * NQP is running.
 *
 * <h2>Captures are cursors</h2>
 *
 * NQP captures cursors, not spans: {@code !cursor_capture} is handed a
 * cursor, and that cursor lands in the match tree carrying its own captures.
 * Two things can be captured, and they arrive differently:
 *
 * <ul>
 *   <li>a subrule's result, which is already a cursor; and
 *   <li>a subcapture, which is a span of the target that no rule produced --
 *       the bytecode path builds a cursor for it with
 *       {@code !cursor_start_subcapture} and passes it before capturing.
 * </ul>
 *
 * So the engine hands over a span or a cursor, and the implementation does
 * whatever NQP requires to make it a capture. That keeps the engine working
 * in offsets, which is what it can do quickly, without it deciding anything
 * about how a match tree is built.
 *
 * <p>Everything here is a boundary as far as partial evaluation is
 * concerned: the implementations reach into NQP objects, which is exactly
 * the code PE should not see through. The match itself -- the part worth
 * specializing -- touches only the target string and an integer position.
 */
public interface RxCursor {

    int[] NO_BRANCHES = new int[0];

    /**
     * The branches of a named alternation, in the order to try them.
     *
     * <p>A named alt is longest-token-match: which branch wins is decided by
     * an NFA the grammar carries, not by the order they were written in. The
     * engine does not own that NFA -- it asks the cursor, which runs the very
     * `!alt` the bytecode path runs, so the two cannot disagree about the
     * winner and the highwater mark gets updated either way.
     *
     * @param branches how many branches there are; the answer indexes them.
     * @return the branches worth trying, best first; empty means none match.
     */
    int[] altOrder(String name, int pos, int branches);

    /** The string being matched. */
    String target();

    /** One past the last position a match may reach. */
    int eos();

    /**
     * Calls a named rule of the grammar at the given position.
     *
     * @return the cursor it produced, whether or not it matched; null when
     *         there is no such rule to call
     */
    Object callSubrule(String name, int pos);

    /**
     * The position a cursor reached, or {@link RxVmNode#NO_MATCH} when it
     * did not match. Kept separate from the call so the engine can hold on
     * to a cursor it may later capture without asking twice.
     */
    int reached(Object subCursor);

    /**
     * Captures a span of the target under a name, building whatever cursor
     * NQP wants to represent it.
     */
    void captureSpan(String name, int from, int to);

    /** Captures a cursor a rule produced, under a name. */
    void captureCursor(String name, Object subCursor);

    /** A cursor over a bare string, for tests and measurement. */
    final class OfString implements RxCursor {
        private final String target;

        public OfString(String target) { this.target = target; }

        @Override public String target() { return target; }

        @Override public int eos() { return target.length(); }

        @Override public Object callSubrule(String name, int pos) {
            throw new UnsupportedOperationException(
                "no subrules without a grammar: " + name);
        }

        @Override public int reached(Object subCursor) { return RxVmNode.NO_MATCH; }

        @Override public void captureSpan(String name, int from, int to) { }

        @Override public void captureCursor(String name, Object subCursor) { }

        /* No grammar, so no NFA and no alternation to order. */
        @Override public int[] altOrder(String name, int pos, int branches) { return NO_BRANCHES; }
    }
}
