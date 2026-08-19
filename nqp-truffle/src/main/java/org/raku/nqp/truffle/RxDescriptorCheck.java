package org.raku.nqp.truffle;

/**
 * Checks that a descriptor of the shape the NQP side emits decodes into an
 * engine that answers the same as the harness parser does.
 *
 * <p>The two front ends have to agree, because the plan is for the parser
 * to be the test fixture and QAST::Regex to be the real input. A difference
 * between them would otherwise only show up once a grammar was already
 * running on the engine.
 */
public final class RxDescriptorCheck {

    private record Case(String name, int[] code, Object[] pool, String input, int want) { }

    public static void main(String[] args) {
        int bad = 0;
        for (Case c : cases()) {
            RxProgram program = RxDescriptor.compile(c.code(), c.pool());
            int got = new RxVmNode(program).match(new RxCursor.OfString(c.input()), 0);
            boolean ok = got == c.want();
            if (!ok) bad++;
            System.out.printf("%-5s %-22s on %-10s got %3d want %3d%n",
                ok ? "ok" : "NOK", c.name(), "'" + c.input() + "'", got, c.want());
        }
        System.out.println(bad == 0 ? "descriptor decodes correctly"
                                    : bad + " descriptor cases wrong");
        if (bad != 0) System.exit(1);
    }

    private static Case[] cases() {
        return new Case[] {
            /* concat(literal "ab") */
            new Case("literal",
                new int[] { RxDescriptor.SEQ, 1, RxDescriptor.LITERAL, 0, 0 },
                new Object[] { "ab" }, "abc", 2),

            /* concat(cclass w, quant 0..* greedy(cclass d)) */
            new Case("word then digits*",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.CCLASS, RxDescriptor.CC_WORD, 0,
                    RxDescriptor.QUANT, 0, -1, 1,
                        RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                },
                new Object[] { }, "a123", 4),

            /* alt(literal "ab", literal "a") then literal "c" -- needs the
             * second branch, so it also checks backtracking survives the
             * round trip. */
            new Case("alt backtracks",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.ALT, 2,
                        RxDescriptor.LITERAL, 0, 0,
                        RxDescriptor.LITERAL, 1, 0,
                    RxDescriptor.LITERAL, 2, 0,
                },
                new Object[] { "ab", "a", "c" }, "ac", 2),

            /* range a-z, one or more */
            new Case("range +",
                new int[] {
                    RxDescriptor.QUANT, 1, -1, 1,
                        RxDescriptor.RANGE, 'a', 'z', 0,
                },
                new Object[] { }, "abc1", 3),

            /* a negated enum: anything but a comma */
            new Case("negated enum",
                new int[] { RxDescriptor.ENUM, 0, 1 },
                new Object[] { "," }, "x,", 1),

            /* anchor at end after a greedy run, which only matches once the
             * quantifier has given characters back */
            new Case("greedy then eos",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.QUANT, 0, -1, 1,
                        RxDescriptor.CCLASS, RxDescriptor.CC_ANY, 0,
                    RxDescriptor.ANCHOR, 1,
                },
                new Object[] { }, "abc", 3),
        };
    }
}
