package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

/**
 * The regex node set, one class per QAST::Regex rxtype. A pattern is a
 * graph of these, and that graph is what partial evaluation specializes:
 * the generic matcher below becomes machine code for one particular
 * pattern, which is the whole reason for the engine to exist.
 *
 * <h2>How backtracking is expressed</h2>
 *
 * The bytecode backend is a backtracking machine driven by an explicit mark
 * stack ({@code regex_mark}/{@code regex_commit}) and jumps: a quantifier
 * that took too much is rewound by popping back to a mark. There is no
 * Truffle equivalent of a goto, and simply returning "the position reached"
 * cannot express giving characters back -- it gets {@code a*a} wrong,
 * because {@code a*} takes everything and nothing makes it reconsider.
 *
 * <p>So each node knows what follows it, and asks. A quantifier tries a
 * repetition, asks what follows, and unwinds to fewer on failure; an
 * alternation lets each branch run into what follows the alternation.
 * Backtracking becomes ordinary returning and the mark stack becomes the
 * Java stack.
 *
 * <h2>Why the successor is a field</h2>
 *
 * An earlier cut passed the continuation as an argument, built as a lambda
 * per step. That is correct and slow: every node ends in a call to its
 * continuation, and a fresh lambda per step makes each of those sites
 * megamorphic, which is exactly what partial evaluation cannot fold. The
 * continuation stayed an allocated object behind a virtual call, costing
 * 2-3x against java.util.regex.
 *
 * <p>Here the successor is assigned once, when the pattern is built, and
 * marked compilation-final. Each site then has one target that PE folds
 * into straight-line code. It is a plain field rather than a {@code @Child}
 * because branches of an alternation share the node that follows them, and
 * a Truffle child may have only one parent.
 *
 * <h2>Known: this does not compile yet</h2>
 *
 * A quantifier repeats by recursion -- {@code rep} calls the body, whose
 * successor is {@link Again}, which calls {@code rep} again -- and the trip
 * count is unbounded. Partial evaluation cannot handle that: it tries to
 * inline the recursion and gives up with
 * {@code PermanentBailoutException: Too deep inlining}, so the whole
 * matcher root stays interpreted. Every measurement of this engine so far
 * is therefore of interpreted code, which is why it loses to
 * java.util.regex despite being the shape that ought to win.
 *
 * <p>Partial evaluation wants loops, not recursion. Making this compile
 * means repetition and backtracking driven by an explicit loop over an
 * explicit stack of choice points -- much closer in spirit to the bytecode
 * engine's mark stack than to the recursive matcher here -- or compiling
 * the pattern to an automaton, which is the road GraalVM's own TRegex
 * takes. The node set and its semantics carry over either way; what has to
 * change is how repetition is driven.
 *
 * <p>Positions are indices into the target's UTF-16 units, as everywhere
 * else in this backend; {@link #width} steps a whole codepoint, since
 * MoarVM counts positions in codepoints and a non-BMP character occupies
 * two units here.
 */
public abstract class RxNodes {

    /** No match. Distinct from 0, which is a match consuming nothing. */
    public static final int NO_MATCH = -1;

    public abstract static class Rx extends Node {
        /**
         * What follows this node, or null when nothing does. Shared between
         * the branches of an alternation, so not a {@code @Child}; assigned
         * once while the pattern is built, so PE treats it as a constant.
         */
        @CompilationFinal Rx succ;

        public abstract int match(RxState state, int pos);

        /** Hands off to whatever follows, or accepts. */
        protected final int cont(RxState state, int pos) {
            Rx s = succ;
            return s == null ? pos : s.match(state, pos);
        }
    }

    /** UTF-16 units occupied by the codepoint at pos. */
    static int width(String target, int pos) {
        return pos < target.length() ? Character.charCount(target.codePointAt(pos)) : 1;
    }

    /** rxtype literal: an exact run of text. */
    public static final class Literal extends Rx {
        @CompilationFinal private final String text;
        @CompilationFinal private final boolean negate;
        @CompilationFinal private final boolean zeroWidth;
        @CompilationFinal private final boolean ignoreCase;

        public Literal(String text, boolean negate, boolean zeroWidth, boolean ignoreCase) {
            this.text = text;
            this.negate = negate;
            this.zeroWidth = zeroWidth;
            this.ignoreCase = ignoreCase;
        }

        @Override public int match(RxState state, int pos) {
            /* regionMatches answers false for a range past the end, so a
             * negated literal with no room left is a match -- which is the
             * assertion holding, not failing. */
            boolean hit = pos + text.length() <= state.eos
                && state.target.regionMatches(ignoreCase, pos, text, 0, text.length());
            if (hit == negate) return NO_MATCH;
            return cont(state, zeroWidth ? pos : pos + text.length());
        }
    }

    /**
     * rxtype alt: the first branch that matches, and whose successor also
     * matches, wins.
     *
     * <p>Each branch runs into what follows the alternation rather than
     * returning to it, which is what makes this correct rather than merely
     * plausible: {@code (a|ab)c} against "abc" needs the second branch
     * precisely because the first leaves 'c' unmatched, and that is only
     * visible once the rest of the pattern has been tried.
     */
    public static final class Alt extends Rx {
        @Children private final Rx[] branches;

        public Alt(Rx... branches) { this.branches = branches; }

        Rx[] branches() { return branches; }

        /* Linking a branch that is a sequence answers its first element,
         * which is what has to be entered; the tree node it came from is
         * gone by then. */
        void setBranch(int i, Rx head) { branches[i] = head; }

        @Override @ExplodeLoop public int match(RxState state, int pos) {
            for (Rx branch : branches) {
                int r = branch.match(state, pos);
                if (r != NO_MATCH) return r;
            }
            return NO_MATCH;
        }
    }

    /**
     * rxtype quant: min..max repetitions, giving characters back as needed.
     *
     * <p>The repetition count lives in the match state rather than on the
     * Java stack, because the body runs into {@link Again}, which comes back
     * here without a parameter to carry it.
     */
    public static final class Quant extends Rx {
        @Child private Rx body;
        @Child private Rx separator;
        @Child private Again again;
        @CompilationFinal private final int min;
        @CompilationFinal private final int max;   // -1 for unbounded
        @CompilationFinal private final boolean greedy;
        @CompilationFinal private final int slot;

        public Quant(Rx body, Rx separator, int min, int max, boolean greedy, int slot) {
            this.body = body;
            this.separator = separator;
            this.min = min;
            this.max = max;
            this.greedy = greedy;
            this.slot = slot;
            this.again = new Again(this);
        }

        Rx body() { return body; }

        Rx separator() { return separator; }

        Again again() { return again; }

        void setBody(Rx head) { this.body = insert(head); }

        void setSeparator(Rx head) { this.separator = insert(head); }

        @Override public int match(RxState state, int pos) {
            state.setCount(slot, 0);
            return rep(state, pos);
        }

        /*
         * Greedy tries one more repetition before offering what follows,
         * frugal offers it first. That ordering is the entire difference
         * between the two, and unwinding this recursion is what "giving
         * characters back" means here.
         */
        int rep(RxState state, int pos) {
            int count = state.count(slot);
            if (!greedy && count >= min) {
                int done = cont(state, pos);
                if (done != NO_MATCH) return done;
            }
            if (max < 0 || count < max) {
                int at = pos;
                if (count > 0 && separator != null) {
                    at = separator.match(state, at);
                }
                if (at != NO_MATCH) {
                    /* A repetition that consumed nothing would recurse
                     * forever; the bytecode engine guards the same case with
                     * its rep counter. */
                    int wasLast = state.lastPos(slot);
                    state.setCount(slot, count + 1);
                    state.setLastPos(slot, at);
                    int r = body.match(state, at);
                    if (r != NO_MATCH) return r;
                    state.setCount(slot, count);
                    state.setLastPos(slot, wasLast);
                }
            }
            if (greedy && count >= min) {
                return cont(state, pos);
            }
            return NO_MATCH;
        }

        boolean consumedNothing(RxState state, int pos) {
            return pos == state.lastPos(slot);
        }
    }

    /** Where a quantifier's body runs to: back into the quantifier. */
    static final class Again extends Rx {
        @CompilationFinal private final Quant quant;

        Again(Quant quant) { this.quant = quant; }

        @Override public int match(RxState state, int pos) {
            if (quant.consumedNothing(state, pos)) return NO_MATCH;
            return quant.rep(state, pos);
        }
    }

    /** rxtype anchor: a zero-width assertion about the position itself. */
    public static final class Anchor extends Rx {
        public enum Kind { BOS, EOS, BOL, EOL, LWB, RWB }

        @CompilationFinal private final Kind kind;

        public Anchor(Kind kind) { this.kind = kind; }

        @Override public int match(RxState state, int pos) {
            String target = state.target;
            boolean ok = switch (kind) {
                case BOS -> pos == 0;
                case EOS -> pos == state.eos;
                case BOL -> pos == 0 || target.charAt(pos - 1) == '\n';
                case EOL -> pos == state.eos || target.charAt(pos) == '\n';
                case LWB -> pos < state.eos && isWord(target, pos)
                            && (pos == 0 || !isWord(target, pos - 1));
                case RWB -> pos > 0 && isWord(target, pos - 1)
                            && (pos == state.eos || !isWord(target, pos));
            };
            return ok ? cont(state, pos) : NO_MATCH;
        }

        private static boolean isWord(String target, int pos) {
            char c = target.charAt(pos);
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    /** One character, tested by whatever the subclass considers membership. */
    abstract static class OneChar extends Rx {
        @CompilationFinal final boolean negate;

        OneChar(boolean negate) { this.negate = negate; }

        abstract boolean holds(int codepoint);

        @Override public final int match(RxState state, int pos) {
            if (pos >= state.eos) return NO_MATCH;
            int cp = state.target.codePointAt(pos);
            if (holds(cp) == negate) return NO_MATCH;
            return cont(state, pos + width(state.target, pos));
        }
    }

    /** rxtype cclass: one character from a named class. */
    public static final class CClass extends OneChar {
        public enum Kind { ANY, DIGIT, SPACE, WORD, NEWLINE, HSPACE, VSPACE }

        @CompilationFinal private final Kind kind;

        public CClass(Kind kind, boolean negate) {
            super(negate);
            this.kind = kind;
        }

        @Override boolean holds(int cp) {
            return switch (kind) {
                case ANY -> true;
                case DIGIT -> Character.isDigit(cp);
                case SPACE -> Character.isWhitespace(cp);
                case WORD -> Character.isLetterOrDigit(cp) || cp == '_';
                case NEWLINE -> cp == '\n' || cp == '\r';
                case HSPACE -> cp == ' ' || cp == '\t'
                               || (Character.isSpaceChar(cp) && cp != '\n' && cp != '\r');
                case VSPACE -> cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C
                               || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
            };
        }
    }

    /** rxtype enumcharlist: one character drawn from a fixed set. */
    public static final class EnumCharList extends OneChar {
        @CompilationFinal private final String chars;

        public EnumCharList(String chars, boolean negate) {
            super(negate);
            this.chars = chars;
        }

        @Override boolean holds(int cp) { return chars.indexOf(cp) >= 0; }
    }

    /** rxtype charrange: one character within an inclusive range. */
    public static final class CharRange extends OneChar {
        @CompilationFinal private final int lo;
        @CompilationFinal private final int hi;

        public CharRange(int lo, int hi, boolean negate) {
            super(negate);
            this.lo = lo;
            this.hi = hi;
        }

        @Override boolean holds(int cp) { return cp >= lo && cp <= hi; }
    }

    /** rxtype scan: find the leftmost position the body matches from. */
    public static final class Scan extends Rx {
        @Child private Rx body;

        public Scan(Rx body) { this.body = body; }

        Rx body() { return body; }

        void setBody(Rx head) { this.body = insert(head); }

        @Override public int match(RxState state, int pos) {
            for (int at = pos; at <= state.eos; at += width(state.target, at)) {
                int r = body.match(state, at);
                if (r != NO_MATCH) return r;
            }
            return NO_MATCH;
        }
    }

    /**
     * rxtype subrule: hand off to another rule of the grammar.
     *
     * <p>This is the boundary of what partial evaluation can specialize. The
     * callee is NQP code -- compiled bytecode, not Truffle nodes -- so PE
     * inlines up to the call and stops. That is the whole reason the dispatch
     * half of this work has to come after code generation moves to Truffle:
     * until the callee is nodes too, there is nothing on the other side for
     * PE to fold into the caller.
     */
    public static final class Subrule extends Rx {
        @CompilationFinal private final String name;
        @CompilationFinal private final boolean zeroWidth;
        @CompilationFinal private final boolean negate;

        public Subrule(String name, boolean zeroWidth, boolean negate) {
            this.name = name;
            this.zeroWidth = zeroWidth;
            this.negate = negate;
        }

        @Override public int match(RxState state, int pos) {
            int r = call(state, pos);
            boolean matched = r != NO_MATCH;
            if (matched == negate) return NO_MATCH;
            return cont(state, negate || zeroWidth ? pos : r);
        }

        @TruffleBoundary
        private int call(RxState state, int pos) {
            return state.cursor.callSubrule(name, pos);
        }
    }

    /** rxtype subcapture: remembers where the body started. */
    public static final class SubCapture extends Rx {
        @Child private Rx body;
        @Child private CaptureEnd end;
        @CompilationFinal private final int slot;

        public SubCapture(String name, Rx body, int slot) {
            this.body = body;
            this.slot = slot;
            this.end = new CaptureEnd(name, slot);
        }

        Rx body() { return body; }

        CaptureEnd end() { return end; }

        void setBody(Rx head) { this.body = insert(head); }

        @Override public int match(RxState state, int pos) {
            state.setCaptureStart(slot, pos);
            return body.match(state, pos);
        }
    }

    /**
     * Where a capture's body runs to: records the span, but only once
     * everything after it has matched too, so a path that was tried and
     * abandoned leaves nothing behind. The bytecode engine has to unwind its
     * capture stack when a mark is backtracked to; running the rest of the
     * pattern first makes that bookkeeping unnecessary.
     */
    public static final class CaptureEnd extends Rx {
        @CompilationFinal private final String name;
        @CompilationFinal private final int slot;

        public CaptureEnd(String name, int slot) {
            this.name = name;
            this.slot = slot;
        }

        @Override public int match(RxState state, int pos) {
            int r = cont(state, pos);
            if (r != NO_MATCH) record(state, state.captureStart(slot), pos);
            return r;
        }

        @TruffleBoundary
        private void record(RxState state, int from, int to) {
            state.cursor.capture(name, from, to);
        }
    }
}
