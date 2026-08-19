package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

/**
 * The regex node set, one class per QAST::Regex rxtype. A pattern is a tree
 * of these, and that tree is what partial evaluation specializes: the
 * generic matcher below becomes machine code for one particular pattern,
 * which is the whole reason for the engine to exist.
 *
 * <p>The bytecode backend is a backtracking machine driven by an explicit
 * mark stack ({@code regex_mark}/{@code regex_commit}) and jumps. There is
 * no Truffle equivalent of a goto, so backtracking is expressed the other
 * way round: a matcher returns the position it reached, or {@link #NO_MATCH},
 * and an alternation simply tries its next child. The mark stack becomes the
 * Java stack, which is what lets partial evaluation see through it.
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
         * @return the position after the match, or {@link #NO_MATCH}.
         */
        public abstract int match(String target, int pos, int eos);
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

        @Override public int match(String target, int pos, int eos) {
            /* regionMatches answers false for a range past the end, so a
             * negated literal with no room left is a match -- which is the
             * assertion holding, not failing. */
            boolean hit = pos + text.length() <= eos
                && target.regionMatches(ignoreCase, pos, text, 0, text.length());
            if (hit == negate) return NO_MATCH;
            return zeroWidth ? pos : pos + text.length();
        }
    }

    /** rxtype concat: every child in turn, each starting where the last ended. */
    public static final class Concat extends Rx {
        @Children private final Rx[] parts;

        public Concat(Rx... parts) { this.parts = parts; }

        @Override @ExplodeLoop public int match(String target, int pos, int eos) {
            /* Unrolled by PE: the child count is fixed per pattern, so the
             * loop disappears and the children inline into one another. */
            for (Rx part : parts) {
                pos = part.match(target, pos, eos);
                if (pos == NO_MATCH) return NO_MATCH;
            }
            return pos;
        }
    }

    /**
     * rxtype alt: the first branch that matches wins, and a branch that
     * fails leaves the position untouched.
     *
     * <p>This is where the shape differs most from the bytecode engine: it
     * pushes a mark and jumps back on failure, where here a failed branch
     * has simply returned and the next one is tried with the original
     * position still in hand.
     */
    public static final class Alt extends Rx {
        @Children private final Rx[] branches;

        public Alt(Rx... branches) { this.branches = branches; }

        @Override @ExplodeLoop public int match(String target, int pos, int eos) {
            for (Rx branch : branches) {
                int r = branch.match(target, pos, eos);
                if (r != NO_MATCH) return r;
            }
            return NO_MATCH;
        }
    }

    /** rxtype quant: min..max repetitions, greedy or frugal. */
    public static final class Quant extends Rx {
        @Child private Rx body;
        @Child private Rx separator;
        @CompilationFinal private final int min;
        @CompilationFinal private final int max;   // -1 for unbounded
        @CompilationFinal private final boolean backtrackable;

        public Quant(Rx body, Rx separator, int min, int max, boolean backtrackable) {
            this.body = body;
            this.separator = separator;
            this.min = min;
            this.max = max;
            this.backtrackable = backtrackable;
        }

        @Override public int match(String target, int pos, int eos) {
            int count = 0;
            int at = pos;
            while (max < 0 || count < max) {
                int next = at;
                if (count > 0 && separator != null) {
                    next = separator.match(target, next, eos);
                    if (next == NO_MATCH) break;
                }
                int r = body.match(target, next, eos);
                if (r == NO_MATCH) break;
                /* A body that consumed nothing would spin forever; the
                 * bytecode engine guards this with its rep counter. */
                if (r == at && count >= min) break;
                at = r;
                count++;
            }
            return count >= min ? at : NO_MATCH;
        }
    }

    /** rxtype anchor: a zero-width assertion about the position itself. */
    public static final class Anchor extends Rx {
        public enum Kind { BOS, EOS, BOL, EOL, LWB, RWB }

        @CompilationFinal private final Kind kind;

        public Anchor(Kind kind) { this.kind = kind; }

        @Override public int match(String target, int pos, int eos) {
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
            return ok ? pos : NO_MATCH;
        }

        private static boolean isWord(String target, int pos) {
            char c = target.charAt(pos);
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    /** rxtype cclass: one character from a named class. */
    public static final class CClass extends Rx {
        public enum Kind { ANY, DIGIT, SPACE, WORD, NEWLINE, HSPACE, VSPACE }

        @CompilationFinal private final Kind kind;
        @CompilationFinal private final boolean negate;

        public CClass(Kind kind, boolean negate) {
            this.kind = kind;
            this.negate = negate;
        }

        @Override public int match(String target, int pos, int eos) {
            if (pos >= eos) return NO_MATCH;
            int cp = target.codePointAt(pos);
            boolean in = switch (kind) {
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
            if (in == negate) return NO_MATCH;
            return pos + width(target, pos);
        }
    }

    /** rxtype enumcharlist: one character drawn from a fixed set. */
    public static final class EnumCharList extends Rx {
        @CompilationFinal private final String chars;
        @CompilationFinal private final boolean negate;

        public EnumCharList(String chars, boolean negate) {
            this.chars = chars;
            this.negate = negate;
        }

        @Override public int match(String target, int pos, int eos) {
            if (pos >= eos) return NO_MATCH;
            int cp = target.codePointAt(pos);
            if ((chars.indexOf(cp) >= 0) == negate) return NO_MATCH;
            return pos + width(target, pos);
        }
    }

    /** rxtype charrange: one character within an inclusive range. */
    public static final class CharRange extends Rx {
        @CompilationFinal private final int lo;
        @CompilationFinal private final int hi;
        @CompilationFinal private final boolean negate;

        public CharRange(int lo, int hi, boolean negate) {
            this.lo = lo;
            this.hi = hi;
            this.negate = negate;
        }

        @Override public int match(String target, int pos, int eos) {
            if (pos >= eos) return NO_MATCH;
            int cp = target.codePointAt(pos);
            if ((cp >= lo && cp <= hi) == negate) return NO_MATCH;
            return pos + width(target, pos);
        }
    }

    /** rxtype scan: find the leftmost position the body matches from. */
    public static final class Scan extends Rx {
        @Child private Rx body;

        public Scan(Rx body) { this.body = body; }

        @Override public int match(String target, int pos, int eos) {
            for (int at = pos; at <= eos; at += width(target, at)) {
                int r = body.match(target, at, eos);
                if (r != NO_MATCH) return r;
            }
            return NO_MATCH;
        }
    }
}
