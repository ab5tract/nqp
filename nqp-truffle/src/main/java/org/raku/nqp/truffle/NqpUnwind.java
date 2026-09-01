package org.raku.nqp.truffle;

import com.oracle.truffle.api.exception.AbstractTruffleException;

import org.raku.nqp.runtime.UnwindException;

/**
 * The Truffle-visible carrier for the runtime's {@link UnwindException}.
 *
 * <p>The Bytecode DSL's TryCatch intercepts only Truffle exceptions; a
 * plain host exception is rethrown past every in-program handler (see
 * {@code resolveThrowable} in the generated interpreter). The runtime's
 * unwind machinery, on the other hand, must stay truffle-free -- the
 * bytecode world throws and catches {@code UnwindException} with no
 * engine on its classpath. So {@link NqpRootNode#interceptInternalException}
 * wraps an unwind in this carrier on the way into the dispatch loop, the
 * program's handler regions catch and route it, and
 * {@code CodeEngines.codeRun}'s boundary unwraps whatever escapes, so
 * bytecode frames above always see the naked host exception again.
 */
@SuppressWarnings("serial")
public final class NqpUnwind extends AbstractTruffleException {

    public final UnwindException unwind;

    public NqpUnwind(UnwindException unwind) {
        this.unwind = unwind;
    }
}
