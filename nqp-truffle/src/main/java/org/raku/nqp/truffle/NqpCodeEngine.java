package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

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
        Object r;
        try {
            // The program stores its own return value, typed; see StoreRet.
            r = ((CallTarget) program).call(cu, tc, cf, csd, args);
        } catch (NqpUnwind wrapped) {
            /* An unwind no handler region in the program claimed: hand the
             * naked host exception back to the bytecode frames above, which
             * catch UnwindException by type (see NqpUnwind). */
            throw wrapped.unwind;
        } catch (NqpHostError wrapped) {
            /* Same boundary rule for a wrapped host throwable no handle
             * region claimed. */
            throw hostForm(wrapped.original);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            /* Every dispatch and handler-running op site yields a suspend
             * token instead of letting the capture cross raw; a raw one
             * here means a site outside that protocol, and joining the
             * chain without this frame would silently drop its work. */
            throw new IllegalStateException(
                "continuation captured at a non-suspendable site in an engine-run block ("
                + (cf.codeRef == null ? "<anon>" : cf.codeRef.name) + ")", sse);
        }
        if (r instanceof ContinuationResult cr) {
            /* The program yielded a suspend token: a continuation is being
             * captured through this frame. Join the ResumeStatus chain the
             * way a bytecode save-site does; the resume handle re-enters
             * the program through ContinuationResult.continueWith. */
            NqpCont.Suspend token = (NqpCont.Suspend) cr.getResult();
            throw token.sse.pushFrame(0, RESUME,
                new Object[] { cr, token.rtype }, cf);
        }
    }

    private static RuntimeException hostForm(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }

    /** Signature (ResumeStatus.Frame)void, per the resume contract. */
    private static final MethodHandle RESUME;
    static {
        try {
            RESUME = MethodHandles.lookup().findStatic(NqpCodeEngine.class, "resumeEngine",
                MethodType.methodType(void.class, org.raku.nqp.runtime.ResumeStatus.Frame.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Re-enters a suspended engine frame: resume the deeper frames first
     * (the bytecode saver contract), read the suspended call's result off
     * this frame's return registers by the site's static type -- or carry
     * an exception the deeper frames threw -- and hand it through the
     * yield with continueWith. What follows mirrors the emitted wrapper
     * method around codeRun: another yield re-suspends, an unwind or host
     * error unwraps at the boundary, a control exception leaves the frame
     * on the way out, and a normal completion leaves it too (the return
     * value is already in the registers, stored by StoreRet).
     */
    static void resumeEngine(org.raku.nqp.runtime.ResumeStatus.Frame frame) throws Throwable {
        ContinuationResult cr = (ContinuationResult) frame.saveSpace[0];
        int rtype = (Integer) frame.saveSpace[1];
        ThreadContext tc = frame.tc;
        CallFrame cf = frame.callFrame;
        Object inject;
        try {
            frame.resumeNextSave();
            inject = NqpOps.readResult(rtype, cf);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            throw sse;   // resumeNextSave already re-saved this frame
        } catch (Throwable t) {
            /* Deliver the exception through the yield so the program's
             * handler regions see it as the suspended call's own throw. */
            inject = new NqpCont.Rethrow(t);
        }
        Object r;
        try {
            r = cr.continueWith(inject);
        } catch (NqpUnwind wrapped) {
            cf.leave();
            throw wrapped.unwind;
        } catch (NqpHostError wrapped) {
            throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(tc, wrapped.original);
        } catch (org.raku.nqp.runtime.ControlException ce) {
            cf.leave();
            throw ce;
        } catch (Throwable t) {
            throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(tc, t);
        }
        if (r instanceof ContinuationResult cr2) {
            NqpCont.Suspend token = (NqpCont.Suspend) cr2.getResult();
            throw token.sse.pushFrame(0, RESUME,
                new Object[] { cr2, token.rtype }, cf);
        }
        cf.leave();
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
