package org.raku.nqp.truffle;

/**
 * What the engine needs from the thing a grammar rule is matching against.
 *
 * <p>A regex in NQP is not a standalone matcher: it runs against a Cursor,
 * which carries the target, the position, the captures made so far, and the
 * means to call another rule. The engine is written against this interface
 * rather than against the runtime's Cursor directly so that the node set
 * stays free of the runtime, and so it can be driven by a plain
 * implementation in tests and benchmarks where no NQP is running.
 *
 * <p>Everything here is a boundary as far as partial evaluation is
 * concerned: the implementations reach into NQP objects, which is exactly
 * the code PE should not try to see through. The match itself -- the part
 * worth specializing -- touches only the target string and an integer
 * position, and that is deliberate.
 */
public interface RxCursor {

    /** The string being matched. */
    String target();

    /** One past the last position a match may reach. */
    int eos();

    /**
     * Calls a named subrule at the given position.
     *
     * @return the position after the subrule's match, or
     *         {@link RxNodes#NO_MATCH}
     */
    int callSubrule(String name, int pos);

    /**
     * Records a capture spanning the given positions.
     *
     * <p>Called only when a match has succeeded, so an implementation need
     * not undo captures on backtracking: the engine backtracks by returning
     * from a node rather than by unwinding an explicit stack, and nothing is
     * recorded on a path that failed.
     */
    void capture(String name, int from, int to);

    /** A cursor over a bare string, for tests and measurement. */
    final class OfString implements RxCursor {
        private final String target;

        public OfString(String target) { this.target = target; }

        @Override public String target() { return target; }

        @Override public int eos() { return target.length(); }

        @Override public int callSubrule(String name, int pos) {
            throw new UnsupportedOperationException(
                "no subrules without a grammar: " + name);
        }

        @Override public void capture(String name, int from, int to) { }
    }
}
