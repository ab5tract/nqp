package org.raku.nqp.truffle;

import java.util.List;

/**
 * The shape of a pattern, before it becomes a program.
 *
 * <p>Plain data, with no Truffle in it: both front ends -- the harness
 * parser and, in the real path, a walk over QAST::Regex -- build this, and
 * {@link RxProgram} turns it into something the engine runs. Keeping the
 * two apart is what lets the engine be driven by NQP's own grammar trees
 * without either side knowing about the other's syntax.
 *
 * <p>One node per QAST::Regex rxtype the engine covers so far. The ones a
 * grammar additionally needs -- dba, qastnode, dynquant, goal, conj -- have
 * no case here yet.
 */
public final class RxTree {

    private RxTree() { }

    public sealed interface Node
        permits Seq, Literal, One, Anchor, Alt, Quant, Sub, Capture, Scan { }

    /** rxtype concat. */
    public record Seq(List<Node> parts) implements Node { }

    /** rxtype literal. */
    public record Literal(String text, boolean negate, boolean zeroWidth,
                          boolean ignoreCase) implements Node { }

    /** rxtype cclass, enumcharlist and charrange: all one character. */
    public record One(RxProgram.CharPred pred) implements Node { }

    /** rxtype anchor. */
    public record Anchor(Kind kind) implements Node {
        public enum Kind { BOS, EOS, BOL, EOL, LWB, RWB }
    }

    /** rxtype alt. */
    public record Alt(List<Node> branches) implements Node { }

    /** rxtype quant. A max below zero is unbounded. */
    public record Quant(Node body, int min, int max, boolean greedy) implements Node { }

    /**
     * rxtype subrule. A capture name means the cursor this rule answers is
     * itself the capture -- which is what NQP puts in a match tree.
     */
    public record Sub(String name, boolean zeroWidth, boolean negate,
                      String capture) implements Node { }

    /** rxtype subcapture. */
    public record Capture(String name, Node body) implements Node { }

    /**
     * rxtype scan: try the body at each position from here on.
     *
     * <p>Every NQP regex is wrapped in one of these, so nothing real can be
     * encoded without it.
     */
    public record Scan(Node body) implements Node { }

    /* The character predicates the front ends build One nodes from. Written
     * as constants and small records so that each is a single class the
     * compiler sees as constant once the program is. */

    public static final RxProgram.CharPred ANY = cp -> true;
    public static final RxProgram.CharPred DIGIT = Character::isDigit;
    public static final RxProgram.CharPred SPACE = Character::isWhitespace;
    public static final RxProgram.CharPred WORD =
        cp -> Character.isLetterOrDigit(cp) || cp == '_';
    public static final RxProgram.CharPred NEWLINE = cp -> cp == '\n' || cp == '\r';
    public static final RxProgram.CharPred VSPACE =
        cp -> cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C
              || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    public static final RxProgram.CharPred HSPACE =
        cp -> cp == ' ' || cp == '\t'
              || (Character.isSpaceChar(cp) && cp != '\n' && cp != '\r');

    public static RxProgram.CharPred not(RxProgram.CharPred pred) {
        return cp -> !pred.holds(cp);
    }

    public static RxProgram.CharPred anyOf(String chars) {
        return cp -> chars.indexOf(cp) >= 0;
    }

    public static RxProgram.CharPred range(int lo, int hi) {
        return cp -> cp >= lo && cp <= hi;
    }

    public static RxProgram.CharPred either(RxProgram.CharPred a, RxProgram.CharPred b) {
        return cp -> a.holds(cp) || b.holds(cp);
    }
}
