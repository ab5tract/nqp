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
        Suspend(SaveStackException sse, int rtype) {
            this.sse = sse;
            this.rtype = rtype;
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
