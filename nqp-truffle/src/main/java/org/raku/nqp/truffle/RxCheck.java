package org.raku.nqp.truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks the engine against java.util.regex on the same patterns and
 * inputs. A speed number means nothing until this passes, and the cases
 * that need backtracking are the ones a naive matcher gets wrong while
 * still looking fast.
 */
public final class RxCheck {

    private record Case(String pattern, String input) { }

    private static final List<Case> CASES = List.of(
        new Case("abc", "abc"),
        new Case("abc", "xabc"),
        new Case("a(b|c)*d", "abcbcd"),
        new Case("a(b|c)*d", "ad"),
        new Case("[a-z]+[0-9]+", "abc123"),
        new Case("\\w+\\s+\\w+", "hello world"),
        new Case("\\d*", "123"),
        new Case("a?b", "b"),
        /* These need a quantifier to give characters back. A matcher that
         * takes the longest run and never reconsiders fails all of them. */
        new Case("a*a", "aaa"),
        new Case("[a-z]+c", "abc"),
        new Case("\\w+d", "abcd"),
        new Case("a*ab", "aaab"),
        new Case(".*b", "abcb")
    );

    public static void main(String[] args) {
        int bad = 0;
        try (Context ctx = Context.newBuilder(NqpLanguage.ID).build()) {
            for (Case c : CASES) {
                Value matcher = ctx.eval(
                    Source.newBuilder(NqpLanguage.ID, c.pattern(), "rx").buildLiteral());
                int got = matcher.execute(c.input(), 0).asInt();

                var m = Pattern.compile(c.pattern()).matcher(c.input());
                int want = m.lookingAt() ? m.end() : RxVmNode.NO_MATCH;

                boolean ok = got == want;
                if (!ok) bad++;
                System.out.printf("%-5s %-14s on %-10s got %3d want %3d%n",
                    ok ? "ok" : "NOK", c.pattern(), "'" + c.input() + "'", got, want);
            }
        }
        System.out.println(bad == 0 ? "all correct" : bad + " of " + CASES.size() + " wrong");
        if (bad != 0) System.exit(1);
    }
}
