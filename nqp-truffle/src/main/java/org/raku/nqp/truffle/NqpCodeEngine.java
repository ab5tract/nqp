package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CodeEngine;
import org.raku.nqp.runtime.CompilationUnit;
import org.raku.nqp.runtime.ThreadContext;

/**
 * The code engine as the rest of NQP sees it — the general-code analog of
 * {@code NqpGrammarEngine}, looked up by name from {@code CodeEngines}
 * in nqp-runtime; the class name is load-bearing and must not move
 * package. Programs are parsed in the process's one polyglot context
 * ({@link NqpPolyglot}), the same one the grammar engine's matchers live in.
 */
public final class NqpCodeEngine implements CodeEngine {

    @Override
    public Object compile(String encoded, String name) {
        return NqpPolyglot.compile(encoded, name);
    }

    @Override
    public void run(Object program, CompilationUnit cu, ThreadContext tc, CallFrame cf,
                    CallSiteDescriptor csd, Object[] args) {
        runProgram((CallTarget) program, cu, tc, cf, csd, args);
    }

    /**
     * Runs one program inside the frame the caller built -- what the
     * emitted stub's body does. Static so that an engine-side dispatch
     * entering a callee directly (NqpDispatch) takes exactly this road.
     */
    static void runProgram(CallTarget program, CompilationUnit cu, ThreadContext tc, CallFrame cf,
                           CallSiteDescriptor csd, Object[] args) {
        Object r;
        if (program instanceof com.oracle.truffle.api.RootCallTarget rct
                && rct.getRootNode() instanceof NqpRootNode root && root.blockName == null) {
            root.blockName = cf.codeRef == null ? "" : cf.codeRef.name;
            /* The one point a CodeRef meets its program root: the wire's
             * exit-handler invariant is checked here, once, rather than on
             * every frame-free entry. Before the overrides, so forcing a
             * block framed cannot mask an encoder disagreement. */
            NqpFrameFree.checkExitHandler(root, cf.codeRef);
            // The frame-free overrides (NQP_FRAMEFREE*), now that the name is known.
            NqpFrameFree.apply(root, root.blockName);
        }
        try {
            // The program stores its own return value, typed; see StoreRet.
            r = program.call(cu, tc, cf, csd, args, cf.codeRef);
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
            throw nonSuspendable(cf, sse);
        }
        if (r instanceof ContinuationResult cr) {
            /* The program yielded a suspend token: a continuation is being
             * captured through this frame. Join the ResumeStatus chain the
             * way a bytecode save-site does; the resume handle re-enters
             * the program through ContinuationResult.continueWith. */
            throw suspend(cr, cf);
        }
    }

    /* This method is reached from PE-visible code (NqpDispatch's direct
     * entry), so its exceptional tails -- a string concatenation, the
     * save-stack machinery -- stay behind boundaries. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static RuntimeException nonSuspendable(CallFrame cf,
                                                   org.raku.nqp.runtime.SaveStackException sse) {
        /* NQP_CONT_TRACE: who escaped, and what had already saved below it.
         * SaveStackException.toString lists the frames the capture has
         * joined so far, so the topmost name there is the frame under the
         * one that failed to yield -- the pair locates the unwrapped site. */
        if (System.getenv("NQP_CONT_TRACE") != null) {
            org.raku.nqp.runtime.StaticCodeInfo sci =
                cf.codeRef == null ? null : cf.codeRef.staticInfo;
            System.err.println("nqp cont: non-suspendable escape in '"
                + (cf.codeRef == null ? "<null>" : cf.codeRef.name) + "' uid="
                + (sci == null ? "?" : sci.uniqueId) + " at "
                + (sci == null ? "?" : sci.sourceFile + ":" + sci.sourceLine)
                + "; saved so far: " + sse);
        }
        return new IllegalStateException(
            "continuation captured at a non-suspendable site in an engine-run block ("
            + (cf.codeRef == null ? "<anon>" : cf.codeRef.name) + ")", sse);
    }

    /** For NqpDispatch's direct entry: the same join of the resume chain. */
    static RuntimeException suspendFrame(ContinuationResult cr, CallFrame cf) {
        return suspend(cr, cf);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static RuntimeException suspend(ContinuationResult cr, CallFrame cf) {
        NqpCont.Suspend token = (NqpCont.Suspend) cr.getResult();
        return token.sse.pushFrame(0, RESUME,
            new Object[] { cr, token.rtype, token.finish }, cf);
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
        /* A fused op's tail, if the suspended site had one; see
         * NqpCont.Suspend.finish. */
        @SuppressWarnings("unchecked")
        java.util.function.Function<Object, Object> finish =
            (java.util.function.Function<Object, Object>) frame.saveSpace[2];
        ThreadContext tc = frame.tc;
        CallFrame cf = frame.callFrame;
        /* A continuation can resume on another thread, and the suspended
         * program's frame still carries the ORIGINAL invocation's thread
         * context in its arguments -- every op it runs after resume would
         * act on the old thread's tc (dispatch records pushed onto the
         * wrong list, curFrame written across threads: the race/hyper
         * corruption). The bytecode resume road reloads tc from the
         * resume status for exactly this reason ("restored separately
         * since we can change threads"); do the same for the engine
         * frame's arguments before re-entering it. */
        cr.getFrame().getArguments()[NqpRootNode.ARG_TC] = tc;
        Object inject;
        boolean finishing = false;
        try {
            frame.resumeNextSave();
            /* Without a finisher the suspended call's value IS the site's
             * result and is read by the site's static type; with one, the
             * value is the INNER call's (always an object) and the op's
             * tail turns it into the op's result. The flag keeps the tail
             * out of the save-stack catch: this frame is not re-saved once
             * resumeNextSave has returned, so a throw from the tail -- a
             * failed type check, or a capture the tail itself provoked --
             * must go through the yield as the op's own throw (Rethrow),
             * never as a save that would drop this frame's work. */
            if (finish == null) {
                inject = NqpOps.readResult(rtype, cf);
            }
            else {
                Object inner = NqpOps.readResult(NqpWire.T_OBJ, cf);
                finishing = true;
                inject = finish.apply(inner);
            }
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            /* A capture the FINISHER provoked is not this frame's save:
             * nothing re-saved it, so it goes through the yield with the
             * other throws (the program's handler regions see it as the
             * op's throw, and an unhandled one reports as a
             * non-suspendable site rather than silently losing work). */
            if (finishing) {
                inject = new NqpCont.Rethrow(sse);
            }
            else {
                /* resumeNextSave already re-saved this frame. Leave it
                 * too: a re-suspending bytecode frame leaves through its
                 * postlude, and skipping this made tc.curFrame point at a
                 * frame packed away in a continuation --
                 * Dispatch.descriptorFor reads tc.curFrame, so race/hyper
                 * runs then resolved callsite descriptors against the
                 * wrong unit. leaveSuspended, not leave: the save is not
                 * this frame's exit, so its LEAVE/KEEP/UNDO stay owed to
                 * the real one. */
                cf.leaveSuspended();
                throw sse;
            }
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
            /* A capture deeper in that reached us raw is a save, not this
             * frame's exit; anything else really leaves it. */
            cf.leaveThrough(ce);
            throw ce;
        } catch (Throwable t) {
            throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(tc, t);
        }
        if (r instanceof ContinuationResult cr2) {
            NqpCont.Suspend token = (NqpCont.Suspend) cr2.getResult();
            /* Same discipline as the catch above: the frame is saved in
             * the continuation, so leave it before the save propagates --
             * suspended, not exited, so no exit handler here either. */
            cf.leaveSuspended();
            throw token.sse.pushFrame(0, RESUME,
                new Object[] { cr2, token.rtype, token.finish }, cf);
        }
        cf.leave();
    }
}
