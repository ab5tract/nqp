package org.raku.nqp.truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.oracle.truffle.api.Truffle;

/**
 * Smoke-checks the Bytecode DSL interpreter: the generated tiers execute,
 * locals and loops answer right, and a program survives the
 * serialize/deserialize round trip. Run via the {@code qtcheck} Gradle
 * task, which stages the Truffle jars as modules the way a real run does.
 */
public final class QtCheck {

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
        try (Context ctx = Context.newBuilder(QtLanguage.ID).build()) {
            Value add = ctx.eval(Source.create(QtLanguage.ID, "qt-test:add"));
            check("add", add.execute(20, 22).asLong(), 42);
            Value fib = ctx.eval(Source.create(QtLanguage.ID, "qt-test:fib"));
            check("fib", fib.execute(30).asLong(), 832040);
            // A warm loop, so the cached tier (and on Graal, compilation)
            // actually gets entered rather than everything staying uncached.
            long sum = 0;
            for (int i = 0; i < 200_000; i++) {
                sum += add.execute(i, 1).asLong();
            }
            check("warm add", sum, 200_000L * 199_999 / 2 + 200_000);
            Value serial = ctx.eval(Source.create(QtLanguage.ID, "qt-test:serial"));
            check("serialized fib", serial.execute(30).asLong(), 832040);
        }
        if (bad > 0) {
            System.out.println("QT CHECK FAILED: " + bad);
            System.exit(1);
        }
        System.out.println("qt check passed");
    }
}
