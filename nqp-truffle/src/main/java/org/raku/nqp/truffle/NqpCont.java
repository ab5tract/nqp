package org.raku.nqp.truffle;

import org.raku.nqp.runtime.SaveStackException;

/**
 * The continuation-suspension protocol's carriers. A dispatch site that
 * sees a {@link SaveStackException} cannot let it cross the engine frame
 * raw -- the Bytecode DSL frame has no Java stack to save -- so the op
 * answers a {@link Suspend} token instead, the program yields it, and
 * {@code CodeEngines.codeRun} turns the yield into a
 * {@code ResumeStatus.Frame} whose resume handle re-enters the program
 * with {@code ContinuationResult.continueWith}. A {@link Rethrow} is how
 * the resume handle injects an exception that arrived while deeper
 * frames were resuming: the value comes back through the yield and the
 * program rethrows it at the suspension point, so handler regions see
 * it exactly as if the original call had thrown it.
 */
final class NqpCont {

    static final class Suspend {
        final SaveStackException sse;
        final int rtype;
        /**
         * The suspended op's tail, applied on resume to the inner call's
         * (object) value; null when the inner value IS the op's result.
         *
         * A FUSED op (isconcrete = decont + concreteness; istype = decont
         * + type check; p6typecheckrv = a where call + the pass/fail
         * decision) computes from a value user code produced, and the
         * Java frames between the op and that call cannot be saved -- so
         * without this the op's result WOULD be the inner value ("say
         * f()" of a routine whose return subset takes answered the where
         * block's True instead of 5). The finisher is the part of the op
         * after the inner call, as a function of that call's value; see
         * NqpTypeOps.SuspendedIn for where the finishers are made.
         */
        final java.util.function.Function<Object, Object> finish;
        Suspend(SaveStackException sse, int rtype) {
            this(sse, rtype, null);
        }
        Suspend(SaveStackException sse, int rtype,
                java.util.function.Function<Object, Object> finish) {
            this.sse = sse;
            this.rtype = rtype;
            this.finish = finish;
        }
    }

    static final class Rethrow {
        final Throwable t;
        Rethrow(Throwable t) {
            this.t = t;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    static RuntimeException sneaky(Throwable t) {
        return NqpCont.<RuntimeException>sneakyThrow(t);
    }

    private NqpCont() { }
}
