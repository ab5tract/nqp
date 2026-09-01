package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CodeEngine;
import org.raku.nqp.runtime.CompilationUnit;
import org.raku.nqp.runtime.ThreadContext;

/**
 * The code engine as the rest of NQP sees it — the general-code analog of
 * {@link TruffleGrammarEngine}, looked up by name from {@code CodeEngines}
 * in nqp-runtime; the class name is load-bearing and must not move
 * package. One polyglot context for the process, created on first use,
 * shared shape with the grammar engine's for the same reasons (a root
 * node outside a context is never queued for compilation).
 */
public final class NqpCodeEngine implements CodeEngine {

    @Override
    public Object compile(String encoded) {
        Holder.CONTEXT.eval(Source.newBuilder(NqpLanguage.ID, encoded, "nqp-code").buildLiteral());
        CallTarget target = NqpLanguage.PARSED.get(encoded);
        if (target == null)
            throw new IllegalStateException("the language parsed no program for: "
                + encoded.substring(0, Math.min(encoded.length(), 60)));
        return target;
    }

    @Override
    public void run(Object program, CompilationUnit cu, ThreadContext tc, CallFrame cf,
                    CallSiteDescriptor csd, Object[] args) {
        try {
            // The program stores its own return value, typed; see StoreRet.
            ((CallTarget) program).call(cu, tc, cf, csd, args);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            /* A continuation is being captured through this frame, and an
             * engine frame has no resume machinery yet: replaying it would
             * silently resume with this block's work missing. Refuse loudly;
             * the encoder's answer is to keep such shapes on bytecode. */
            throw new IllegalStateException(
                "continuation captured through an engine-run block ("
                + (cf.codeRef == null ? "<anon>" : cf.codeRef.name)
                + "); this block shape must stay on the bytecode path", sse);
        }
    }

    private static final class Holder {
        static final Context CONTEXT = Context.newBuilder(NqpLanguage.ID)
            .allowExperimentalOptions(true)
            .build();

        static {
            String runtime = Truffle.getRuntime().getName();
            if (!runtime.contains("GraalVM")) {
                System.err.println(
                    "nqp: Truffle runtime is '" + runtime + "', not an optimizing one;"
                    + " engine-run code will be slow."
                    + " Check --module-path and --add-modules on the runner.");
            }
        }
    }
}
