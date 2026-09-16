package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ThreadContext

/**
 * A native_ref container spec is for use with the NativeRef REPR, and makes
 * the references decontainerizable and assignable.
 */
open class NativeRefContainerConfigurer : ContainerConfigurer() {
    override fun newContainerSpec(tc: ThreadContext, st: STable): ContainerSpec = NativeRefContainerSpec()

    override fun configureContainerSpec(tc: ThreadContext, cs: ContainerSpec, config: SixModelObject) {
        /* Nothing to configure here. */
    }
}
