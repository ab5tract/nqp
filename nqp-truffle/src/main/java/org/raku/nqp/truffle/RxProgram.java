package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;

/**
 * A pattern compiled to a flat program for {@link RxVmNode} to run.
 *
 * <p>The node tree could not be partially evaluated: a quantifier that
 * repeats by recursion has an unbounded trip count, and PE inlines until it
 * bails with "Too deep inlining". Partial evaluation wants a loop, so the
 * pattern becomes a program and repetition becomes a jump.
 *
 * <p>What that buys is the Futamura projection in its usual form: the code
 * array is compilation-final, so PE specializes the interpreter loop to
 * <em>this</em> program -- the generic matcher disappears and what is left
 * is machine code for one pattern, including one that only came into
 * existence at run time.
 *
 * <p>Alternation and repetition are both {@link #SPLIT}: try one branch,
 * and on failure resume at the other with the position restored. That is
 * the same choice-point discipline as the bytecode backend's mark stack,
 * only in a form a compiler can see through.
 */
public final class RxProgram {

    /* Instructions are an opcode followed by its operands, all in one int
     * array so the whole program is a single compilation-final constant. */
    public static final int MATCH = 0;      // accept
    public static final int CHAR = 1;       // literal(idx), flags
    public static final int ONE = 2;        // predicate(idx)
    public static final int ANCHOR = 3;     // kind
    public static final int SPLIT = 4;      // preferred pc, alternative pc
    public static final int JMP = 5;        // pc
    public static final int SUB = 6;        // name(idx), flags, capture(idx+1 or 0)
    public static final int MARK = 7;       // register
    public static final int EMPTY_CHECK = 8; // register -- fail if nothing consumed
    public static final int CAP_START = 9;  // register
    public static final int CAP_END = 10;   // register, name(idx)
    public static final int ADVANCE = 11;   // step one codepoint, or fail at the end
    public static final int CUT_MARK = 18;  // register -- remember the choice-point height
    public static final int CUT = 19;       // register -- drop every choice point made since
    /* name(idx), count, then one pc per branch. The order to try them in is
     * asked of the cursor at match time, so it is not in the code. */
    public static final int ALT_LTM = 20;
    /* predicate(idx), negate -- tests without moving. Its own opcode rather
     * than a flag on the rest because the end of the string is a special
     * case: a NEGATED zero-width class succeeds there, having nothing to
     * exclude, where a consuming one has to fail. */
    public static final int ONE_ZW = 21;
    /*
     * The character classes worth their own opcode. ONE reaches a predicate
     * through an interface call, and across patterns that call site sees
     * every predicate there is -- megamorphic, so partial evaluation cannot
     * fold it. These carry the test in the opcode instead, where it becomes
     * a constant once the program is.
     */
    public static final int DIGIT = 12;     // negate
    public static final int WORD = 13;      // negate
    public static final int SPACE = 14;     // negate
    public static final int ANY = 15;       // (no operands)
    public static final int RANGE1 = 16;    // lo, hi, negate
    public static final int CHAR1 = 17;     // codepoint, negate

    /* CHAR flags. */
    public static final int F_NEGATE = 1;
    public static final int F_ZEROWIDTH = 2;
    public static final int F_IGNORECASE = 4;

    @CompilationFinal(dimensions = 1) final int[] code;
    @CompilationFinal(dimensions = 1) final Object[] pool;
    final int registers;
    final int captures;
    /** How many choice points the program can have live at once. */
    final int choiceDepth;

    private RxProgram(int[] code, Object[] pool, int registers, int captures, int splits) {
        this.code = code;
        this.pool = pool;
        this.registers = registers;
        this.captures = captures;
        /* A split inside a repetition can stack up one choice point per
         * repetition, so the depth is not statically known; start with room
         * for a few per split and grow from there. */
        this.choiceDepth = Math.max(8, splits * 4);
    }

    /** One character's worth of membership test, kept out of the code array. */
    public interface CharPred {
        boolean holds(int codepoint);
    }

    /** Compiles a pattern tree. Runs once, when the pattern is first seen. */
    @TruffleBoundary
    public static RxProgram compile(RxTree.Node tree) {
        Builder b = new Builder();
        b.emit(tree);
        b.op(MATCH);
        return new RxProgram(b.codeArray(), b.pool.toArray(), b.registers,
            b.captures, b.splits);
    }

    private static final class Builder {
        private final List<Integer> code = new ArrayList<>();
        private final List<Object> pool = new ArrayList<>();
        private int registers;
        private int captures;
        private int splits;

        int op(int opcode, int... operands) {
            int at = code.size();
            if (opcode == SPLIT) splits++;
            code.add(opcode);
            for (int o : operands) code.add(o);
            return at;
        }

        int constant(Object value) {
            pool.add(value);
            return pool.size() - 1;
        }

        int reg() { return registers++; }

        void patch(int at, int value) { code.set(at, value); }

        int here() { return code.size(); }

        int[] codeArray() {
            int[] out = new int[code.size()];
            for (int i = 0; i < out.length; i++) out[i] = code.get(i);
            return out;
        }

        void emit(RxTree.Node node) {
            if (node instanceof RxTree.Seq seq) {
                for (RxTree.Node part : seq.parts()) emit(part);
                return;
            }
            if (node instanceof RxTree.Literal lit) {
                int flags = (lit.negate() ? F_NEGATE : 0)
                          | (lit.zeroWidth() ? F_ZEROWIDTH : 0)
                          | (lit.ignoreCase() ? F_IGNORECASE : 0);
                /* Most literals in a grammar are one character; comparing a
                 * codepoint beats a region match against a one-char string. */
                if (flags == 0 && lit.text().codePointCount(0, lit.text().length()) == 1) {
                    op(CHAR1, lit.text().codePointAt(0), 0);
                } else {
                    op(CHAR, constant(lit.text()), flags);
                }
                return;
            }
            if (node instanceof RxTree.One one) {
                RxProgram.CharPred pred = one.pred();
                if (one.zeroWidth()) {
                    /* Negation stays inside the opcode: the predicate itself
                     * carries it, and the end-of-string rule needs to know. */
                    if (pred instanceof RxTree.Negated n) op(ONE_ZW, constant(n.of()), 1);
                    else op(ONE_ZW, constant(pred), 0);
                    return;
                }
                /* A predicate the opcode set knows becomes that opcode; the
                 * rest still go through the interface. */
                if (pred == RxTree.ANY) op(ANY);
                else if (pred == RxTree.DIGIT) op(DIGIT, 0);
                else if (pred == RxTree.WORD) op(WORD, 0);
                else if (pred == RxTree.SPACE) op(SPACE, 0);
                else if (pred instanceof RxTree.Negated n) {
                    if (n.of() == RxTree.DIGIT) op(DIGIT, 1);
                    else if (n.of() == RxTree.WORD) op(WORD, 1);
                    else if (n.of() == RxTree.SPACE) op(SPACE, 1);
                    else if (n.of() instanceof RxTree.Range r) op(RANGE1, r.lo(), r.hi(), 1);
                    else op(ONE, constant(pred));
                }
                else if (pred instanceof RxTree.Range r) op(RANGE1, r.lo(), r.hi(), 0);
                else op(ONE, constant(pred));
                return;
            }
            if (node instanceof RxTree.Anchor anchor) {
                op(ANCHOR, anchor.kind().ordinal());
                return;
            }
            if (node instanceof RxTree.Alt alt) {
                emitAlt(alt.branches(), 0);
                return;
            }
            if (node instanceof RxTree.AltLtm alt) {
                emitAltLtm(alt);
                return;
            }
            if (node instanceof RxTree.Quant quant) {
                emitQuant(quant);
                return;
            }
            if (node instanceof RxTree.Sub sub) {
                /* Zero means the result is not captured; otherwise the pool
                 * index of the name, biased so zero can mean "none". */
                int capture = sub.capture() == null ? 0 : constant(sub.capture()) + 1;
                op(SUB, constant(sub.name()),
                    (sub.negate() ? F_NEGATE : 0) | (sub.zeroWidth() ? F_ZEROWIDTH : 0),
                    capture);
                if (sub.capture() != null) captures++;
                return;
            }
            if (node instanceof RxTree.Scan scan) {
                /*
                 *   L0: SPLIT L1, L2      try matching where we are
                 *   L1: <body> ...
                 *   L2: ADVANCE           nothing here; step one and retry
                 *       JMP L0
                 * The split's alternative arm is what a failure inside the
                 * body resumes at, with the position it had on entry, which
                 * is exactly what scanning needs.
                 */
                int loop = here();
                int split = op(SPLIT, 0, 0);
                patch(split + 1, here());
                emit(scan.body());
                int done = op(JMP, 0);
                patch(split + 2, here());
                op(ADVANCE);
                op(JMP, loop);
                patch(done + 1, here());
                return;
            }
            if (node instanceof RxTree.Capture capture) {
                int r = reg();
                captures++;
                op(CAP_START, r);
                emit(capture.body());
                op(CAP_END, r, constant(capture.name()));
                return;
            }
            throw new IllegalArgumentException("no rule for " + node.getClass());
        }

        /*
         * a|b|c becomes SPLIT a, (SPLIT b, c): each branch jumps clear of the
         * rest once it has matched, and a failure inside one resumes at the
         * next.
         */
        void emitAlt(List<RxTree.Node> branches, int i) {
            if (i == branches.size() - 1) {
                emit(branches.get(i));
                return;
            }
            int split = op(SPLIT, 0, 0);
            patch(split + 1, here());
            emit(branches.get(i));
            int jump = op(JMP, 0);
            patch(split + 2, here());
            emitAlt(branches, i + 1);
            patch(jump + 1, here());
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
        /*
         * A longest-token alternation. The branches are laid out one after
         * another, each ending in a jump to the common exit, and the header
         * holds a slot per branch which is patched to where that branch
         * starts. Which slot to take first is decided at match time by the
         * grammar's NFA, so the code says only where each branch is.
         */
        void emitAltLtm(RxTree.AltLtm alt) {
            int cut = alt.ratchet() ? reg() : -1;
            if (cut >= 0) op(CUT_MARK, cut);

            int n = alt.branches().size();
            int header = op(ALT_LTM, constant(alt.name()), n);
            for (int i = 0; i < n; i++) code.add(0);
            /* Every branch can be a choice point until one of them wins. */
            splits += n;

            List<Integer> jumps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                patch(header + 3 + i, here());
                emit(alt.branches().get(i));
                jumps.add(op(JMP, 0));
            }
            int out = here();
            for (int jump : jumps) patch(jump + 1, out);

            if (cut >= 0) op(CUT, cut);
        }

        void emitQuant(RxTree.Quant quant) {
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
            int cut = quant.ratchet() ? reg() : -1;
            if (cut >= 0) op(CUT_MARK, cut);
            emitQuantBody(quant);
            if (cut >= 0) op(CUT, cut);
        }

        void emitQuantBody(RxTree.Quant quant) {
            for (int i = 0; i < quant.min(); i++) emit(quant.body());
            if (quant.max() < 0) {
                int r = reg();
                int loop = here();
                int split = op(SPLIT, 0, 0);
                int body = here();
                op(MARK, r);
                emit(quant.body());
                op(EMPTY_CHECK, r);
                op(JMP, loop);
                int out = here();
                patch(split + 1, quant.greedy() ? body : out);
                patch(split + 2, quant.greedy() ? out : body);
                return;
            }
            /* A bounded repetition is just that many optional copies. */
            List<Integer> splits = new ArrayList<>();
            for (int i = quant.min(); i < quant.max(); i++) {
                int split = op(SPLIT, 0, 0);
                splits.add(split);
                patch(split + (quant.greedy() ? 1 : 2), here());
                emit(quant.body());
            }
            int out = here();
            for (int split : splits) {
                patch(split + (quant.greedy() ? 2 : 1), out);
            }
        }
    }
}
