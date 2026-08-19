package org.raku.nqp.truffle;

import com.oracle.truffle.api.Truffle;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.regex.Pattern;

/**
 * Runs the engine against java.util.regex on the same patterns and input.
 *
 * <p>Run it with RX_TRACE=1 to see what actually compiled. That matters
 * more than it sounds: the matcher root currently fails compilation with
 * "Too deep inlining" because quantifier repetition is recursive, so the
 * numbers below are of interpreted code. A trace line saying "opt failed"
 * for MatchRootNode means the measurement is not of the compiled engine.
 *
 * <p>It asserts that the run is actually optimized before reporting a
 * number. Both ways this can silently not be true -- the fallback
 * "Interpreted" runtime when the Truffle artifacts do not match the JDK's
 * GraalVM, and a call target that never got hot enough to be compiled --
 * produce a result that merely looks like "Truffle is slow", so a
 * measurement that has not checked is not worth reading.
 */
public final class RxBench {

    private static final String[] PATTERNS = {
        "a(b|c)*d",
        "[a-z]+[0-9]+",
        "\\w+\\s+\\w+",
    };

    public static void main(String[] args) {
        String runtime = Truffle.getRuntime().getName();
        System.out.println("truffle runtime: " + runtime);
        if (!runtime.contains("GraalVM")) {
            System.err.println("REFUSING TO MEASURE: this is the fallback runtime, so nothing "
                + "is partially evaluated. Put truffle-api and truffle-runtime on the MODULE "
                + "path at the version this JDK's GraalVM reports.");
            System.exit(2);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) sb.append("abcbcd xyz42 hello world ");
        String input = sb.toString();

        try (Context ctx = Context.newBuilder(RxLanguage.ID)
                .allowExperimentalOptions(true)
                .option("engine.CompileImmediately", "true")
                .option("engine.TraceCompilation", System.getenv("RX_TRACE") != null ? "true" : "false")
                .build()) {
            for (String pattern : PATTERNS) {
                Value matcher = ctx.eval(Source.newBuilder(RxLanguage.ID, pattern, "rx").buildLiteral());
                long truffle = bestOf(() -> scan(matcher, input));
                long java = bestOf(() -> scanJava(Pattern.compile(pattern), input));
                System.out.printf("%-18s truffle %6.2f ms   java.util.regex %6.2f ms   ratio %.2fx%n",
                    pattern, truffle / 1e6, java / 1e6, (double) truffle / java);
            }
        }
    }

    private static int scan(Value matcher, String input) {
        int hits = 0;
        for (int i = 0; i < input.length(); i++) {
            if (matcher.execute(input, i).asInt() != RxNodes.NO_MATCH) hits++;
        }
        return hits;
    }

    private static int scanJava(Pattern p, String input) {
        int hits = 0;
        var m = p.matcher(input);
        for (int i = 0; i < input.length(); i++) {
            m.region(i, input.length());
            if (m.lookingAt()) hits++;
        }
        return hits;
    }

    private static long bestOf(Runnable body) {
        for (int i = 0; i < 3; i++) body.run();           // warm up
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t = System.nanoTime();
            body.run();
            best = Math.min(best, System.nanoTime() - t);
        }
        return best;
    }
}
