package org.raku.nqp.truffle;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * The Truffle-visible carrier for a host exception that is NOT part of
 * the runtime's control-flow protocol -- an NPE out of an op, say.
 *
 * <p>The bytecode path's `handle` op wraps its protected region in a
 * {@code catch (Throwable)} that funnels anything that is not a
 * ControlException into {@code ExceptionHandling.dieInternal}, making it
 * an nqp-level exception a CATCH block can take. For an engine handler
 * region to do the same, the throwable must reach the program's exception
 * table at all, so {@link NqpRootNode#interceptInternalException} wraps it
 * here. A program with no handle region never catches it, and
 * {@code CodeEngines.codeRun} unwraps it at the boundary, so bytecode
 * frames above see the original throwable.
 */
@SuppressWarnings("serial")
public final class NqpHostError extends AbstractTruffleException {

    public final Throwable original;

    public NqpHostError(Throwable original) {
        this.original = original;
    }
}
