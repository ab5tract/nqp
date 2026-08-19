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

    private RxParser(String src) { this.src = src; }

    

    @TruffleBoundary
    static RxTree.Node parse(String pattern) {
        RxParser p = new RxParser(pattern);
        RxTree.Node tree = p.alternation();
        if (p.at != pattern.length()) {
            throw new IllegalArgumentException(
                "unconsumed input at " + p.at + " of '" + pattern + "'");
        }
        return tree;
    }

    /** alternation := concat ('|' concat)* */
    private RxTree.Node alternation() {
        List<RxTree.Node> branches = new ArrayList<>();
        branches.add(concat());
        while (peek() == '|') {
            at++;
            branches.add(concat());
        }
        return branches.size() == 1 ? branches.get(0) : new RxTree.Alt(branches);
    }

    /** concat := quantified* */
    private RxTree.Node concat() {
        List<RxTree.Node> parts = new ArrayList<>();
        while (at < src.length() && peek() != '|' && peek() != ')') {
            parts.add(quantified());
        }
        if (parts.size() == 1) return parts.get(0);
        return new RxTree.Seq(parts);
    }

    /** quantified := atom ('*' | '+' | '?')? */
    private RxTree.Node quantified() {
        RxTree.Node atom = atom();
        char c = peek();
        if (c == '*' || c == '+' || c == '?') {
            at++;
            int min = c == '+' ? 1 : 0;
            int max = c == '?' ? 1 : -1;
            return new RxTree.Quant(atom, min, max, true);
        }
        return atom;
    }

    private RxTree.Node atom() {
        char c = src.charAt(at);
        switch (c) {
            case '(' -> {
                at++;
                RxTree.Node inner = alternation();
                expect(')');
                return inner;
            }
            case '[' -> {
                at++;
                return charClass();
            }
            case '.' -> {
                at++;
                return new RxTree.One(RxTree.ANY);
            }
            case '^' -> {
                at++;
                return new RxTree.Anchor(RxTree.Anchor.Kind.BOS);
            }
            case '$' -> {
                at++;
                return new RxTree.Anchor(RxTree.Anchor.Kind.EOS);
            }
            case '\\' -> {
                at++;
                return escape(src.charAt(at++));
            }
            default -> {
                at++;
                return new RxTree.Literal(String.valueOf(c), false, false, false);
            }
        }
    }

    private RxTree.Node escape(char c) {
        return switch (c) {
            case 'd' -> new RxTree.One(RxTree.DIGIT);
            case 'D' -> new RxTree.One(RxTree.not(RxTree.DIGIT));
            case 's' -> new RxTree.One(RxTree.SPACE);
            case 'S' -> new RxTree.One(RxTree.not(RxTree.SPACE));
            case 'w' -> new RxTree.One(RxTree.WORD);
            case 'W' -> new RxTree.One(RxTree.not(RxTree.WORD));
            case 'n' -> new RxTree.Literal("\n", false, false, false);
            case 't' -> new RxTree.Literal("\t", false, false, false);
            default -> new RxTree.Literal(String.valueOf(c), false, false, false);
        };
    }

    /** A bracketed set, with ranges and a leading '^' for negation. */
    private RxTree.Node charClass() {
        boolean negate = peek() == '^';
        if (negate) at++;
        StringBuilder singles = new StringBuilder();
        RxProgram.CharPred pred = null;
        while (at < src.length() && peek() != ']') {
            char lo = src.charAt(at++);
            if (peek() == '-' && at + 1 < src.length() && src.charAt(at + 1) != ']') {
                at++;
                char hi = src.charAt(at++);
                RxProgram.CharPred range = RxTree.range(lo, hi);
                pred = pred == null ? range : RxTree.either(pred, range);
            } else {
                singles.append(lo);
            }
        }
        expect(']');
        if (singles.length() > 0) {
            RxProgram.CharPred set = RxTree.anyOf(singles.toString());
            pred = pred == null ? set : RxTree.either(pred, set);
        }
        if (pred == null) pred = cp -> false;
        /* Negation applies to the set as a whole, which is why the parts are
         * combined first and only then inverted. */
        return new RxTree.One(negate ? RxTree.not(pred) : pred);
    }

    private char peek() { return at < src.length() ? src.charAt(at) : '\0'; }

    private void expect(char c) {
        if (at >= src.length() || src.charAt(at) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + at);
        }
        at++;
    }
}
