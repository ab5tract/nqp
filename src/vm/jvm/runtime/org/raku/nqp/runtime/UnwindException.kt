package org.raku.nqp.runtime

import org.raku.nqp.sixmodel.SixModelObject

class UnwindException : ControlException() {
    companion object {
        private const val serialVersionUID = -2452898396745530180L
    }

    /* What we're unwinding to. */
    @JvmField var unwindTarget = 0L

    /* The compilation unit holding the unwind target. */
    @JvmField var unwindCompUnit: CompilationUnit? = null

    /* The category, if we're a simple handler. */
    @JvmField var category = 0L

    /* If there was a block handler, this is the result the block
     * produced.
     */
    @JvmField var result: SixModelObject? = null

    /* When we transform a VMException into an UnwindException, we need
     * to keep the payload, for example when it is about a loop label. */
    @JvmField var payload: SixModelObject? = null
}
