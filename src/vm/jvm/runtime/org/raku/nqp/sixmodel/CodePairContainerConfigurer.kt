package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

/**
 * A code_pair container uses a pair of methods (fetch/store) to provide the
 * container semantics.
 */
open class CodePairContainerConfigurer : ContainerConfigurer() {
    /* Sets this container spec in place for the specified STable. */
    override fun setContainerSpec(tc: ThreadContext, st: STable) {
        st.ContainerSpec = CodePairContainerSpec()
    }

    /* Configures the container spec with the specified info. */
    override fun configureContainerSpec(tc: ThreadContext, st: STable, config: SixModelObject) {
        val cs = st.ContainerSpec as CodePairContainerSpec
        val fetch = config.at_key_boxed(tc, "fetch")
        if (Ops.isnull(fetch) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "Container spec 'code_pair' must be configured with a fetch")
        val store = config.at_key_boxed(tc, "store")
        if (Ops.isnull(store) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "Container spec 'code_pair' must be configured with a store")
        cs.fetchCode = fetch
        cs.storeCode = store
    }
}
