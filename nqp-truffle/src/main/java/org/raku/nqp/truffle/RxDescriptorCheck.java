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
        @Override public Object callSubrule(String name, int pos, RxArgs args) { return null; }
        @Override public int reached(Object subCursor) { return RxVmNode.NO_MATCH; }
        @Override public void captureSpan(String name, int from, int to) { }
        @Override public void captureCursor(String name, Object subCursor) { }
        @Override public int[] altOrder(String name, int pos, int branches) { return order; }
        @Override public boolean charProp(String property, int pos) { return false; }
        @Override public boolean callbackHolds(int index, int pos) { return true; }
    }

    /**
     * A cursor whose Unicode properties are decided by the test rather than
     * by the runtime, so the opcode can be checked where no NQP is running.
     * "Alpha" holds for a letter, and nothing else holds at all.
     */
    private record Props(String target) implements RxCursor {
        @Override public String target() { return target; }
        @Override public int eos() { return target.length(); }
        @Override public Object callSubrule(String name, int pos, RxArgs args) { return null; }
        @Override public int reached(Object subCursor) { return RxVmNode.NO_MATCH; }
        @Override public void captureSpan(String name, int from, int to) { }
        @Override public void captureCursor(String name, Object subCursor) { }
        @Override public int[] altOrder(String name, int pos, int branches) { return NO_BRANCHES; }
        @Override public boolean charProp(String property, int pos) {
            return property.equals("Alpha") && Character.isLetter(target.charAt(pos));
        }
        @Override public boolean callbackHolds(int index, int pos) { return true; }
    }

    /**
     * `<:Alpha>+` consumes letters and stops, and a negated one is its
     * complement. The end of the string fails either way: there is no
     * character there to carry a property, which is what QAST::Compiler's
     * own bounds check decides before it ever asks.
     */
    private static int checkUniProp() {
        int bad = 0;
        record Case(String name, int[] code, String input, int want) { }
        for (Case c : new Case[] {
                new Case("uniprop takes letters",
                    new int[] { RxDescriptor.QUANT, 1, -1, 1, 0, 0,
                                    RxDescriptor.UNIPROP, 0, 0 },
                    "abc1", 3),
                new Case("negated uniprop",
                    new int[] { RxDescriptor.QUANT, 1, -1, 1, 0, 0,
                                    RxDescriptor.UNIPROP, 0, 1 },
                    "12a", 2),
                new Case("uniprop at eos",
                    new int[] { RxDescriptor.UNIPROP, 0, 0 }, "", -1),
        }) {
            RxProgram program = RxDescriptor.compile(c.code(), new Object[] { "Alpha" });
            int got = new RxVmNode(program).match(new Props(c.input()), 0);
            boolean ok = got == c.want();
            if (!ok) bad++;
            System.out.printf("%-5s %-32s on %-8s got %3d want %3d%n",
                ok ? "ok" : "NOK", c.name(), "'" + c.input() + "'", got, c.want());
        }
        return bad;
    }

    /**
     * A separator sits BETWEEN repetitions and is never trailing, so
     * `\d+ % ','` matches "1,2" out of "1,2," and stops before the last
     * comma. Reading the separator as part of a repetition would eat it,
     * and then whatever the rule expects after the list would not be there.
     */
    private static int checkSeparated() {
        int bad = 0;
        /* QUANT min max greedy ratchet separated, body \d, separator ',' */
        int[] greedy = {
            RxDescriptor.QUANT, 1, -1, 1, 0, 1,
                RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                RxDescriptor.LITERAL, 0, 0,
        };
        /* The same thing RATCHETED, which is what a token makes of it. */
        int[] ratcheted = {
            RxDescriptor.QUANT, 1, -1, 1, 1, 1,
                RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                RxDescriptor.LITERAL, 0, 0,
        };
        /* Zero or more, so an empty target still matches, at 0. */
        int[] optional = {
            RxDescriptor.QUANT, 0, -1, 1, 0, 1,
                RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
                RxDescriptor.LITERAL, 0, 0,
        };
        record Case(String name, int[] code, String input, int want) { }
        for (Case c : new Case[] {
                new Case("separated list", greedy, "1,2,3", 5),
                new Case("no trailing separator", greedy, "1,2,", 3),
                new Case("one item needs no separator", greedy, "1x", 1),
                new Case("separator must be there", greedy, "1 2", 1),
                new Case("ratcheted, still no trailing", ratcheted, "1,2,", 3),
                new Case("zero or more matches nothing", optional, "x", 0),
        }) {
            RxProgram program = RxDescriptor.compile(c.code(), new Object[] { "," });
            int got = new RxVmNode(program).match(new RxCursor.OfString(c.input()), 0);
            boolean ok = got == c.want();
            if (!ok) bad++;
            System.out.printf("%-5s %-32s on %-8s got %3d want %3d%n",
                ok ? "ok" : "NOK", c.name(), "'" + c.input() + "'", got, c.want());
        }
        return bad;
    }

    /**
     * A cursor whose rule code answers whatever the test says, and records
     * that it was asked.
     */
    private static final class Code implements RxCursor {
        private final String target;
        private final boolean answer;
        final StringBuilder ran = new StringBuilder();

        Code(String target, boolean answer) { this.target = target; this.answer = answer; }

        @Override public String target() { return target; }
        @Override public int eos() { return target.length(); }
        @Override public Object callSubrule(String name, int pos, RxArgs args) { return null; }
        @Override public int reached(Object subCursor) { return RxVmNode.NO_MATCH; }
        @Override public void captureSpan(String name, int from, int to) { }
        @Override public void captureCursor(String name, Object subCursor) { }
        @Override public int[] altOrder(String name, int pos, int branches) { return NO_BRANCHES; }
        @Override public boolean charProp(String property, int pos) { return false; }
        @Override public boolean callbackHolds(int index, int pos) {
            ran.append(index).append('@').append(pos).append(' ');
            return answer;
        }
    }

    /**
     * `<?{ ... }>` decides the match by its answer; a plain `{ ... }` runs
     * and is not allowed to. Neither moves the position -- code in a grammar
     * looks at where the match is, it does not consume any of it.
     */
    private static int checkCallback() {
        int bad = 0;
        /* \d, then the code, then \d again: if the code consumed anything
         * the second digit would not be there. */
        int[] assertion = {
            RxDescriptor.SEQ, 3,
            RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
            RxDescriptor.QASTNODE, 0, 2,     /* zerowidth */
            RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
        };
        int[] negated = {
            RxDescriptor.QASTNODE, 0, 3,     /* zerowidth + negate */
        };
        int[] effect = {
            RxDescriptor.SEQ, 2,
            RxDescriptor.QASTNODE, 7, 0,     /* a plain { ... } */
            RxDescriptor.CCLASS, RxDescriptor.CC_DIGIT, 0,
        };
        record Case(String name, int[] code, boolean answer, String input, int want, String ran) { }
        for (Case c : new Case[] {
                new Case("assertion holds", assertion, true, "12", 2, "0@1 "),
                new Case("assertion fails the match", assertion, false, "12", -1, "0@1 "),
                new Case("negated assertion", negated, false, "x", 0, "0@0 "),
                new Case("negated assertion the other way", negated, true, "x", -1, "0@0 "),
                new Case("plain code cannot fail the match", effect, false, "5", 1, "7@0 "),
        }) {
            RxProgram program = RxDescriptor.compile(c.code(), new Object[] { });
            Code cursor = new Code(c.input(), c.answer());
            int got = new RxVmNode(program).match(cursor, 0);
            boolean ok = got == c.want() && cursor.ran.toString().equals(c.ran());
            if (!ok) bad++;
            System.out.printf("%-5s %-32s on %-6s got %3d want %3d  ran %s%n",
                ok ? "ok" : "NOK", c.name(), "'" + c.input() + "'", got, c.want(), cursor.ran);
        }
        return bad;
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

    /**
     * A cursor that answers every subrule call with a fixed width and records
     * how it was called, so that the arguments a descriptor carried can be
     * checked against what arrived.
     */
    private static final class Calls implements RxCursor {
        private final String target;
        private final int width;
        final StringBuilder seen = new StringBuilder();
        private int at;

        Calls(String target, int width) { this.target = target; this.width = width; }

        @Override public String target() { return target; }
        @Override public int eos() { return target.length(); }
        @Override public Object callSubrule(String name, int pos, RxArgs args) {
            seen.append(name).append('(');
            if (args != null) {
                for (int i = 0; i < args.count(); i++) {
                    if (i > 0) seen.append(',');
                    seen.append(args.kinds().charAt(i)).append(':').append(args.values[i]);
                }
            }
            seen.append(')');
            at = Math.min(pos + width, target.length());
            return this;
        }
        @Override public int reached(Object subCursor) { return at; }
        @Override public void captureSpan(String name, int from, int to) { }
        @Override public void captureCursor(String name, Object subCursor) { }
        @Override public int[] altOrder(String name, int pos, int branches) { return NO_BRANCHES; }
        @Override public boolean charProp(String property, int pos) { return false; }
        @Override public boolean callbackHolds(int index, int pos) { return true; }
    }

    /**
     * A subrule call carries the arguments the grammar wrote, in order and
     * with their kinds. Getting this wrong would not fail: the rule would be
     * called with the wrong arguments, and something like
     * `<.FAILGOAL(')', 'argument list')>` would report the wrong thing at the
     * wrong place.
     */
    private static int checkSubruleArgs() {
        int[] code = {
            RxDescriptor.SUB, 0, 0, 0, 2,
                RxDescriptor.ARG_STR, 1,
                RxDescriptor.ARG_INT, 2,
        };
        Object[] pool = { "FAILGOAL", ")", "42" };
        RxProgram program = RxDescriptor.compile(code, pool);

        Calls cursor = new Calls("abc", 2);
        int got = new RxVmNode(program).match(cursor, 0);
        String want = "FAILGOAL(S:),I:42)";
        boolean ok = got == 2 && cursor.seen.toString().equals(want);
        System.out.printf("%-5s %-32s got %s%n",
            ok ? "ok" : "NOK", "subrule carries its arguments", cursor.seen);
        return ok ? 0 : 1;
    }

    public static void main(String[] args) {
        int bad = checkLtm() + checkSubruleArgs() + checkUniProp() + checkSeparated()
                + checkCallback();
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
                    RxDescriptor.QUANT, 0, -1, 1, 0, 0,
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
                    RxDescriptor.QUANT, 1, -1, 1, 0, 0,
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
                    RxDescriptor.QUANT, 1, -1, 1, 1, 0,
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
                    RxDescriptor.QUANT, 1, -1, 1, 0, 0,
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
                        RxDescriptor.QUANT, 1, -1, 1, 0, 0,
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

            /* The two constant assertions. A `pass` anchor holds wherever it
             * is and consumes nothing; a `fail` anchor kills the branch it is
             * in, and here the alternation's second branch is what answers. */
            new Case("pass anchor holds",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.ANCHOR, 6,
                    RxDescriptor.LITERAL, 0, 0,
                },
                new Object[] { "ab" }, "abc", 2),

            new Case("fail anchor kills its branch",
                new int[] {
                    RxDescriptor.ALT, 2,
                        RxDescriptor.SEQ, 2,
                            RxDescriptor.LITERAL, 0, 0,
                            RxDescriptor.ANCHOR, 7,
                        RxDescriptor.LITERAL, 1, 0,
                },
                new Object[] { "ab", "a" }, "abc", 1),

            /* anchor at end after a greedy run, which only matches once the
             * quantifier has given characters back */
            new Case("greedy then eos",
                new int[] {
                    RxDescriptor.SEQ, 2,
                    RxDescriptor.QUANT, 0, -1, 1, 0, 0,
                        RxDescriptor.CCLASS, RxDescriptor.CC_ANY, 0,
                    RxDescriptor.ANCHOR, 1,
                },
                new Object[] { }, "abc", 3),
        };
    }
}
