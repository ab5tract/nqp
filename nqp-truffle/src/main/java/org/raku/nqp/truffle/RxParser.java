package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a node tree from a pattern written in a small subset of regex
 * syntax.
 *
 * <p>This exists so the engine can be exercised and measured on its own.
 * The real path does not parse text at all: NQP has already parsed the
 * grammar and holds a QAST::Regex tree, and the backend will walk that tree
 * into the same nodes. Keeping the node set free of any syntax lets the two
 * front ends share one engine.
 *
 * <p>Everything here runs once, when a pattern is first seen, so it is
 * behind a boundary rather than something partial evaluation should look
 * into.
 */
final class RxParser {

    private final String src;
    private int at;
    /* Slots for the per-match state: each quantifier and each capture gets
     * one when it is built, so the state can be flat arrays. */
    private int slots;

    private RxParser(String src) { this.src = src; }

    /** A built pattern: where to enter it, and how much state it needs. */
    record Built(RxNodes.Rx entry, int slots) { }

    @TruffleBoundary
    static Built parse(String pattern) {
        RxParser p = new RxParser(pattern);
        RxNodes.Rx tree = p.alternation();
        if (p.at != pattern.length()) {
            throw new IllegalArgumentException(
                "unconsumed input at " + p.at + " of '" + pattern + "'");
        }
        /* Nothing follows the whole pattern, so it accepts where it ends. */
        return new Built(RxLink.link(tree, null), p.slots);
    }

    /** alternation := concat ('|' concat)* */
    private RxNodes.Rx alternation() {
        List<RxNodes.Rx> branches = new ArrayList<>();
        branches.add(concat());
        while (peek() == '|') {
            at++;
            branches.add(concat());
        }
        return branches.size() == 1
            ? branches.get(0)
            : new RxNodes.Alt(branches.toArray(new RxNodes.Rx[0]));
    }

    /** concat := quantified* */
    private RxNodes.Rx concat() {
        List<RxNodes.Rx> parts = new ArrayList<>();
        while (at < src.length() && peek() != '|' && peek() != ')') {
            parts.add(quantified());
        }
        if (parts.size() == 1) return parts.get(0);
        return new RxLink.Seq(parts.toArray(new RxNodes.Rx[0]));
    }

    /** quantified := atom ('*' | '+' | '?')? */
    private RxNodes.Rx quantified() {
        RxNodes.Rx atom = atom();
        char c = peek();
        if (c == '*' || c == '+' || c == '?') {
            at++;
            int min = c == '+' ? 1 : 0;
            int max = c == '?' ? 1 : -1;
            return new RxNodes.Quant(atom, null, min, max, true, slots++);
        }
        return atom;
    }

    private RxNodes.Rx atom() {
        char c = src.charAt(at);
        switch (c) {
            case '(' -> {
                at++;
                RxNodes.Rx inner = alternation();
                expect(')');
                return inner;
            }
            case '[' -> {
                at++;
                return charClass();
            }
            case '.' -> {
                at++;
                return new RxNodes.CClass(RxNodes.CClass.Kind.ANY, false);
            }
            case '^' -> {
                at++;
                return new RxNodes.Anchor(RxNodes.Anchor.Kind.BOS);
            }
            case '$' -> {
                at++;
                return new RxNodes.Anchor(RxNodes.Anchor.Kind.EOS);
            }
            case '\\' -> {
                at++;
                return escape(src.charAt(at++));
            }
            default -> {
                at++;
                return new RxNodes.Literal(String.valueOf(c), false, false, false);
            }
        }
    }

    private RxNodes.Rx escape(char c) {
        return switch (c) {
            case 'd' -> new RxNodes.CClass(RxNodes.CClass.Kind.DIGIT, false);
            case 'D' -> new RxNodes.CClass(RxNodes.CClass.Kind.DIGIT, true);
            case 's' -> new RxNodes.CClass(RxNodes.CClass.Kind.SPACE, false);
            case 'S' -> new RxNodes.CClass(RxNodes.CClass.Kind.SPACE, true);
            case 'w' -> new RxNodes.CClass(RxNodes.CClass.Kind.WORD, false);
            case 'W' -> new RxNodes.CClass(RxNodes.CClass.Kind.WORD, true);
            case 'n' -> new RxNodes.Literal("\n", false, false, false);
            case 't' -> new RxNodes.Literal("\t", false, false, false);
            default -> new RxNodes.Literal(String.valueOf(c), false, false, false);
        };
    }

    /** A bracketed set, with ranges and a leading '^' for negation. */
    private RxNodes.Rx charClass() {
        boolean negate = peek() == '^';
        if (negate) at++;
        StringBuilder singles = new StringBuilder();
        List<RxNodes.Rx> ranges = new ArrayList<>();
        while (at < src.length() && peek() != ']') {
            char lo = src.charAt(at++);
            if (peek() == '-' && at + 1 < src.length() && src.charAt(at + 1) != ']') {
                at++;
                char hi = src.charAt(at++);
                ranges.add(new RxNodes.CharRange(lo, hi, negate));
            } else {
                singles.append(lo);
            }
        }
        expect(']');
        if (ranges.isEmpty()) {
            return new RxNodes.EnumCharList(singles.toString(), negate);
        }
        if (singles.length() > 0) {
            ranges.add(new RxNodes.EnumCharList(singles.toString(), negate));
        }
        /* A negated set has to fail if ANY part matches, which is what a
         * concatenation of zero-width checks would say; only the positive
         * case is a plain alternation. The negated case with ranges is left
         * out rather than quietly answering the wrong thing. */
        if (negate) {
            throw new IllegalArgumentException("negated ranges are NYI in the harness parser");
        }
        return new RxNodes.Alt(ranges.toArray(new RxNodes.Rx[0]));
    }

    private char peek() { return at < src.length() ? src.charAt(at) : '\0'; }

    private void expect(char c) {
        if (at >= src.length() || src.charAt(at) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + at);
        }
        at++;
    }
}
