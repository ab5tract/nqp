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

    /**
     * A cursor that reports a branch order chosen by the test rather than by
     * a grammar's NFA. The engine must take the branches in the order it is
     * given, which is the whole of what makes a named alt longest-token
     * rather than first-wins.
     */
    private record Ordered(String target, int[] order) implements RxCursor {
        @Override public String target() { return target; }
        @Override public int eos() { return target.length(); }
        @Override public Object callSubrule(String name, int pos) { return null; }
        @Override public int reached(Object subCursor) { return RxVmNode.NO_MATCH; }
        @Override public void captureSpan(String name, int from, int to) { }
        @Override public void captureCursor(String name, Object subCursor) { }
        @Override public int[] altOrder(String name, int pos, int branches) { return order; }
    }

    private static int checkLtm() {
        /* A RATCHETED alt named "x" of (literal "ab", literal "abc"), then
         * literal "c", against "abc".
         *
         * Ratcheted is what makes this a real test of ordering: the alt
         * commits to whichever branch it takes, so the order decides the
         * answer rather than merely the route to it. Taking "ab" first
         * leaves "c" to match and reaches 3; taking "abc" first consumes the
         * lot, commits, and the trailing "c" has nothing left -- the match
         * fails outright. Without the commit both orders reach 3 by
         * backtracking, and the test would pass whatever the engine did. */
        int[] code = {
            RxDescriptor.SEQ, 2,
            RxDescriptor.ALT_LTM, 0, 1, 2,
                RxDescriptor.LITERAL, 1, 0,
                RxDescriptor.LITERAL, 2, 0,
            RxDescriptor.LITERAL, 3, 0,
        };
        Object[] pool = { "x", "ab", "abc", "c" };
        RxProgram program = RxDescriptor.compile(code, pool);

        int bad = 0;
        record Case(String name, int[] order, int want) { }
        for (Case c : new Case[] {
                new Case("ltm takes the branch offered first", new int[] { 0, 1 }, 3),
                new Case("ltm order changes the answer", new int[] { 1, 0 }, -1),
                new Case("ltm with no branch matching", new int[] { }, -1),
        }) {
            int got = new RxVmNode(program).match(new Ordered("abc", c.order()), 0);
            boolean ok = got == c.want();
            if (!ok) bad++;
            System.out.printf("%-5s %-32s got %3d want %3d%n",
                ok ? "ok" : "NOK", c.name(), got, c.want());
        }
        return bad;
    }

    public static void main(String[] args) {
        int bad = checkLtm();
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
                    RxDescriptor.QUANT, 0, -1, 1, 0,
                        RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                },
                new Object[] { }, "a123", 4),

            /* concat(enum "{" ZEROWIDTH, literal "{") -- the look must not
             * consume, so the literal after it still finds the brace. This is
             * the shape of <?[{]> <pblock>, where treating the look as
             * consuming called pblock one character late and it reported a
             * missing block at a position where the block plainly was. */
            new Case("zero-width look does not consume",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.ENUM, 0, 2,
                    RxDescriptor.LITERAL, 1, 0,
                },
                new Object[] { "{", "{" }, "{ 1 }", 1),

            /* The same, negated, at the end of the string: there is no
             * character to be the wrong one, so it holds. */
            new Case("negated zero-width holds at eos",
                new int[] { RxDescriptor.ENUM, 0, 3 },
                new Object[] { "x" }, "", 0),

            /* concat(quant 1..* greedy(cclass d), cclass d) -- a GREEDY
             * quantifier hands a digit back so the trailing \d can match. */
            new Case("greedy gives back",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.QUANT, 1, -1, 1, 0,
                        RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                    RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                },
                new Object[] { }, "123", 3),

            /* The same thing RATCHETED, which is what every quantifier in an
             * NQP token is: it keeps all three digits, leaves nothing for the
             * trailing \d, and the whole match fails rather than giving one
             * back. Reading a ratcheted quantifier as an ordinary greedy one
             * is what made the engine accept input the bytecode path
             * rejects. */
            new Case("ratchet keeps what it took",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.QUANT, 1, -1, 1, 1,
                        RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                    RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                },
                new Object[] { }, "123", -1),

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
                    RxDescriptor.QUANT, 1, -1, 1, 0,
                        RxDescriptor.RANGE, 'a', 'z', 0,
                },
                new Object[] { }, "abc1", 3),

            /* a negated enum: anything but a comma */
            new Case("negated enum",
                new int[] { RxDescriptor.ENUM, 0, 1 },
                new Object[] { "," }, "x,", 1),

            /* scan: the body only matches partway in, which is the shape
             * every NQP regex has around it */
            new Case("scan finds it later",
                new int[] {
                    RxDescriptor.SCAN,
                    RxDescriptor.LITERAL, 0, 0,
                },
                new Object[] { "cd" }, "abcd", 4),

            /* scan that never matches has to fail rather than run off */
            new Case("scan finds nothing",
                new int[] {
                    RxDescriptor.SCAN,
                    RxDescriptor.LITERAL, 0, 0,
                },
                new Object[] { "zz" }, "abcd", -1),

            /* a subcapture round-trips and does not disturb the match; the
             * capture itself lands on the cursor, which OfString discards */
            new Case("subcapture",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.CAPTURE, 0,
                        RxDescriptor.QUANT, 1, -1, 1, 0,
                            RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                    RxDescriptor.LITERAL, 1, 0,
                },
                new Object[] { "num", "x" }, "42x", 3),

            /* a capture on a path that is abandoned must not be kept: the
             * first branch captures and then fails on the literal */
            new Case("capture on a dead path",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.ALT, 2,
                        RxDescriptor.CAPTURE, 0,
                            RxDescriptor.LITERAL, 1, 0,
                        RxDescriptor.LITERAL, 2, 0,
                    RxDescriptor.LITERAL, 3, 0,
                },
                new Object[] { "n", "ab", "a", "c" }, "ac", 2),

            /* anchor at end after a greedy run, which only matches once the
             * quantifier has given characters back */
            new Case("greedy then eos",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.QUANT, 0, -1, 1, 0,
                        RxDescriptor.CCLASS, RxDescriptor.CC_ANY, 0,
                    RxDescriptor.ANCHOR, 1,
                },
                new Object[] { }, "abc", 3),
        };
    }
}
