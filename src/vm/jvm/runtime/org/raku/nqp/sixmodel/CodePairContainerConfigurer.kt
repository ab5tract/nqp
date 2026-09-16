package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

/**
 * A code_pair container uses a pair of methods (fetch/store) to provide the
 * container semantics.
 */
open class CodePairContainerConfigurer : ContainerConfigurer() {
    override fun newContainerSpec(tc: ThreadContext, st: STable): ContainerSpec = CodePairContainerSpec()

    override fun configureContainerSpec(tc: ThreadContext, cs: ContainerSpec, config: SixModelObject) {
        cs as CodePairContainerSpec
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
