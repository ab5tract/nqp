package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

/**
 * The regex node set, one class per QAST::Regex rxtype. A pattern is a tree
 * of these, and that tree is what partial evaluation specializes: the
 * generic matcher below becomes machine code for one particular pattern,
 * which is the whole reason for the engine to exist.
 *
 * <h2>Why continuation passing</h2>
 *
 * The bytecode backend is a backtracking machine driven by an explicit mark
 * stack ({@code regex_mark}/{@code regex_commit}) and jumps: a quantifier
 * that took too much is rewound by popping back to a mark. There is no
 * Truffle equivalent of a goto, and the obvious alternative -- have each
 * node return the position it reached -- cannot express giving characters
 * back. It gets {@code a*a} wrong, because {@code a*} takes everything and
 * nothing can make it reconsider.
 *
 * <p>So a node is handed the rest of the pattern as a continuation. A
 * quantifier tries a repetition, asks the continuation, and on failure drops
 * back to fewer; an alternation asks the continuation inside each branch.
 * Backtracking becomes ordinary returning, and the mark stack becomes the
 * Java stack -- which is the form partial evaluation can see through, since
 * the continuation at each site is a constant.
 *
 * <p>Positions are indices into the target's UTF-16 units, as everywhere
 * else in this backend; {@link #width} steps a whole codepoint, since
 * MoarVM counts positions in codepoints and a non-BMP character occupies
 * two units here.
 */
public abstract class RxNodes {

    /** No match. Distinct from 0, which is a match consuming nothing. */
    public static final int NO_MATCH = -1;

    /** The rest of the pattern, asked whether it matches from here. */
    public interface RxCont {
        int run(RxCursor cursor, String target, int pos, int eos);
    }

    /** The continuation at the end of a pattern: whatever reached here matched. */
    public static final RxCont ACCEPT = (cursor, target, pos, eos) -> pos;

    public abstract static class Rx extends Node {
        /**
         * The target and eos travel alongside the cursor rather than being
         * read from it per node: they are loop-invariant for a whole match,
         * and passing them keeps the hot path free of interface calls that
         * partial evaluation would have to reason about. Only the nodes that
         * genuinely need the cursor -- subrules and captures -- touch it.
         *
         * @param k what must also match, starting where this node ends
         * @return the position the whole rest of the pattern reached, or
         *         {@link #NO_MATCH}
         */
        public abstract int match(RxCursor cursor, String target, int pos, int eos, RxCont k);

        /** Matches this node alone, with nothing required after it. */
        public final int matchAlone(RxCursor cursor, String target, int pos, int eos) {
            return match(cursor, target, pos, eos, ACCEPT);
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

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            /* regionMatches answers false for a range past the end, so a
             * negated literal with no room left is a match -- which is the
             * assertion holding, not failing. */
            boolean hit = pos + text.length() <= eos
                && target.regionMatches(ignoreCase, pos, text, 0, text.length());
            if (hit == negate) return NO_MATCH;
            return k.run(cursor, target, zeroWidth ? pos : pos + text.length(), eos);
        }
    }

    /** rxtype concat: every child in turn, each continuing into the next. */
    public static final class Concat extends Rx {
        @Children private final Rx[] parts;

        public Concat(Rx... parts) { this.parts = parts; }

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            return step(0, cursor, target, pos, eos, k);
        }

        /*
         * The child index is constant at each site once PE unrolls the
         * chain, so the continuations below fold into direct control flow
         * rather than remaining allocated objects.
         */
        private int step(int i, RxCursor cursor, String target, int pos, int eos, RxCont k) {
            if (i == parts.length) return k.run(cursor, target, pos, eos);
            return parts[i].match(cursor, target, pos, eos,
                (c, t, p, e) -> step(i + 1, c, t, p, e, k));
        }
    }

    /**
     * rxtype alt: the first branch that matches, and whose continuation also
     * matches, wins.
     *
     * <p>Asking the continuation inside the branch is what makes this
     * correct rather than merely plausible: {@code (a|ab)c} against "abc"
     * needs the second branch precisely because the first leaves 'c'
     * unmatched, which is only visible once the rest of the pattern has been
     * tried.
     */
    public static final class Alt extends Rx {
        @Children private final Rx[] branches;

        public Alt(Rx... branches) { this.branches = branches; }

        @Override @ExplodeLoop
        public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            for (Rx branch : branches) {
                int r = branch.match(cursor, target, pos, eos, k);
                if (r != NO_MATCH) return r;
            }
            return NO_MATCH;
        }
    }

    /** rxtype quant: min..max repetitions, giving characters back as needed. */
    public static final class Quant extends Rx {
        @Child private Rx body;
        @Child private Rx separator;
        @CompilationFinal private final int min;
        @CompilationFinal private final int max;   // -1 for unbounded
        @CompilationFinal private final boolean greedy;

        public Quant(Rx body, Rx separator, int min, int max, boolean greedy) {
            this.body = body;
            this.separator = separator;
            this.min = min;
            this.max = max;
            this.greedy = greedy;
        }

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            return rep(0, cursor, target, pos, eos, k);
        }

        /*
         * Greedy tries one more repetition before offering the continuation,
         * frugal offers it first. That ordering is the entire difference
         * between the two, and unwinding this recursion is what "giving
         * characters back" means here.
         */
        private int rep(int count, RxCursor cursor, String target, int pos, int eos, RxCont k) {
            if (!greedy && count >= min) {
                int done = k.run(cursor, target, pos, eos);
                if (done != NO_MATCH) return done;
            }
            if (max < 0 || count < max) {
                int deeper = one(count, cursor, target, pos, eos, k);
                if (deeper != NO_MATCH) return deeper;
            }
            if (greedy && count >= min) {
                return k.run(cursor, target, pos, eos);
            }
            return NO_MATCH;
        }

        private int one(int count, RxCursor cursor, String target, int pos, int eos, RxCont k) {
            int at = pos;
            if (count > 0 && separator != null) {
                int sep = separator.matchAlone(cursor, target, at, eos);
                if (sep == NO_MATCH) return NO_MATCH;
                at = sep;
            }
            final int from = at;
            /* A repetition that consumed nothing would recurse forever; the
             * bytecode engine guards the same case with its rep counter. */
            return body.match(cursor, target, at, eos, (c, t, p, e) ->
                p == from ? NO_MATCH : rep(count + 1, c, t, p, e, k));
        }
    }

    /** rxtype anchor: a zero-width assertion about the position itself. */
    public static final class Anchor extends Rx {
        public enum Kind { BOS, EOS, BOL, EOL, LWB, RWB }

        @CompilationFinal private final Kind kind;

        public Anchor(Kind kind) { this.kind = kind; }

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            boolean ok = switch (kind) {
                case BOS -> pos == 0;
                case EOS -> pos == eos;
                case BOL -> pos == 0 || target.charAt(pos - 1) == '\n';
                case EOL -> pos == eos || target.charAt(pos) == '\n';
                case LWB -> pos < eos && isWord(target, pos)
                            && (pos == 0 || !isWord(target, pos - 1));
                case RWB -> pos > 0 && isWord(target, pos - 1)
                            && (pos == eos || !isWord(target, pos));
            };
            return ok ? k.run(cursor, target, pos, eos) : NO_MATCH;
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

        @Override public final int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            if (pos >= eos) return NO_MATCH;
            int cp = target.codePointAt(pos);
            if (holds(cp) == negate) return NO_MATCH;
            return k.run(cursor, target, pos + width(target, pos), eos);
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

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            for (int at = pos; at <= eos; at += width(target, at)) {
                int r = body.match(cursor, target, at, eos, k);
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

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            int r = call(cursor, pos);
            boolean matched = r != NO_MATCH;
            if (matched == negate) return NO_MATCH;
            return k.run(cursor, target, negate || zeroWidth ? pos : r, eos);
        }

        @TruffleBoundary
        private int call(RxCursor cursor, int pos) {
            return cursor.callSubrule(name, pos);
        }
    }

    /**
     * rxtype subcapture: match the body and record what it spanned.
     *
     * <p>Recorded only once the continuation has accepted too, so a path
     * that was tried and abandoned leaves nothing behind. The bytecode
     * engine has to unwind its capture stack when a mark is backtracked to;
     * returning from a node makes that bookkeeping unnecessary.
     */
    public static final class SubCapture extends Rx {
        @Child private Rx body;
        @CompilationFinal private final String name;

        public SubCapture(String name, Rx body) {
            this.name = name;
            this.body = body;
        }

        @Override public int match(RxCursor cursor, String target, int pos, int eos, RxCont k) {
            final int from = pos;
            return body.match(cursor, target, pos, eos, (c, t, p, e) -> {
                int rest = k.run(c, t, p, e);
                if (rest != NO_MATCH) record(c, from, p);
                return rest;
            });
        }

        @TruffleBoundary
        private void record(RxCursor cursor, int from, int to) {
            cursor.capture(name, from, to);
        }
    }
}
