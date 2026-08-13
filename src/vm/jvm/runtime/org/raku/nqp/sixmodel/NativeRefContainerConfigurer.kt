package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ThreadContext

/**
 * A native_ref container spec is for use with the NativeRef REPR, and makes
 * the references decontainerizable and assignable.
 */
open class NativeRefContainerConfigurer : ContainerConfigurer() {
    /* Sets this container spec in place for the specified STable. */
    override fun setContainerSpec(tc: ThreadContext, st: STable) {
        st.ContainerSpec = NativeRefContainerSpec()
    }

    /* Configures the container spec with the specified info. */
    override fun configureContainerSpec(tc: ThreadContext, st: STable, config: SixModelObject) {
        /* Nothing to configure here. */
    }
}
