package org.raku.nqp.truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.oracle.truffle.api.Truffle;

/**
 * Smoke-checks the Bytecode DSL interpreter: the generated tiers execute,
 * locals and loops answer right, and a program survives the
 * serialize/deserialize round trip. Run via the {@code nqpcheck} Gradle
 * task, which stages the Truffle jars as modules the way a real run does.
 */
public final class NqpCheck {

    private static int bad = 0;

    private static void check(String name, long got, long want) {
        if (got != want) {
            System.out.println("not ok - " + name + ": got " + got + ", want " + want);
            bad++;
        } else {
            System.out.println("ok - " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("# Truffle runtime: " + Truffle.getRuntime().getName());
        try (Context ctx = Context.newBuilder(NqpLanguage.ID).build()) {
            Value add = ctx.eval(Source.create(NqpLanguage.ID, "code-test:add"));
            check("add", add.execute(20, 22).asLong(), 42);
            Value fib = ctx.eval(Source.create(NqpLanguage.ID, "code-test:fib"));
            check("fib", fib.execute(30).asLong(), 832040);
            // A warm loop, so the cached tier (and on Graal, compilation)
            // actually gets entered rather than everything staying uncached.
            long sum = 0;
            for (int i = 0; i < 200_000; i++) {
                sum += add.execute(i, 1).asLong();
            }
            check("warm add", sum, 200_000L * 199_999 / 2 + 200_000);
            Value serial = ctx.eval(Source.create(NqpLanguage.ID, "code-test:serial"));
            check("serialized fib", serial.execute(30).asLong(), 832040);

            // The wire road: the same fib arriving as an encoded program,
            // the way the QAST encoder sends one, run through the raw call
            // target the way the code engine runs one. No runtime objects
            // are involved, so only pure ops appear in the tree.
            String wire = wireFib();
            ctx.eval(Source.newBuilder(NqpLanguage.ID, wire, "wire").buildLiteral());
            Object got = NqpLanguage.PARSED.get(wire)
                .call(null, null, null, null, new Object[0]);
            check("wire fib", (Long) got, 832040);
        }
        if (bad > 0) {
            System.out.println("NQP-CODE CHECK FAILED: " + bad);
            System.exit(1);
        }
        System.out.println("nqp-code check passed");
    }

    /** fib(30) over locals and a loop, in the encoder's wire format. */
    private static String wireFib() {
        java.util.List<Integer> c = new java.util.ArrayList<>();
        java.util.List<String> pool = new java.util.ArrayList<>();
        // pool: 0="0" 1="1" 2="30"
        pool.add("0"); pool.add("1"); pool.add("30");
        c.add(NqpWire.VERSION);
        c.add(4);                                   // locals: a c i t
        for (int i = 0; i < 4; i++) c.add(NqpWire.T_INT);
        c.add(NqpWire.STMTS); c.add(5);
        locbind(c, 0, NqpWire.IVAL, 0);             // a := 0
        locbind(c, 1, NqpWire.IVAL, 1);             // c := 1
        locbind(c, 2, NqpWire.IVAL, 2);             // i := 30
        c.add(NqpWire.LOOP); c.add(0); c.add(0); c.add(NqpWire.T_INT);
        opcall2(c, NqpOps.OP_ISGT_I);               // while i > 0
        locget(c, 2);
        c.add(NqpWire.IVAL); c.add(0);
        c.add(NqpWire.STMTS); c.add(4);             // body
        c.add(NqpWire.LOCBIND); c.add(NqpWire.T_INT); c.add(3);
        opcall2(c, NqpOps.OP_ADD_I); locget(c, 0); locget(c, 1);
        c.add(NqpWire.LOCBIND); c.add(NqpWire.T_INT); c.add(0); locget(c, 1);
        c.add(NqpWire.LOCBIND); c.add(NqpWire.T_INT); c.add(1); locget(c, 3);
        c.add(NqpWire.LOCBIND); c.add(NqpWire.T_INT); c.add(2);
        opcall2(c, NqpOps.OP_SUB_I); locget(c, 2);
        c.add(NqpWire.IVAL); c.add(1);
        locget(c, 0);                               // value: a
        StringBuilder sb = new StringBuilder(NqpWire.MAGIC);
        sb.append(c.size());
        for (int v : c) sb.append(' ').append(v);
        sb.append(' ').append(pool.size());
        for (String p : pool) sb.append(' ').append(p.length()).append(':').append(p);
        return sb.toString();
    }

    private static void locbind(java.util.List<Integer> c, int idx, int valTag, int poolIdx) {
        c.add(NqpWire.LOCBIND); c.add(NqpWire.T_INT); c.add(idx);
        c.add(valTag); c.add(poolIdx);
    }

    private static void locget(java.util.List<Integer> c, int idx) {
        c.add(NqpWire.LOCGET); c.add(NqpWire.T_INT); c.add(idx);
    }

    private static void opcall2(java.util.List<Integer> c, int id) {
        c.add(NqpWire.OPCALL); c.add(id); c.add(2);
    }
}
