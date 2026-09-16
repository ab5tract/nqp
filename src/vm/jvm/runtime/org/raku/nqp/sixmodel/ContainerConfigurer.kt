package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ThreadContext

/**
 * A container configurer knows how to build a certain type of container
 * spec and configure it.
 */
abstract class ContainerConfigurer {
    /**
     * A fresh, unconfigured container spec for the type. The CALLER publishes
     * it (Ops.setcontspec, SerializationReader) once it is complete: a spec
     * is never reachable through a state before it is filled.
     */
    abstract fun newContainerSpec(tc: ThreadContext, st: STable): ContainerSpec

    /** Fills the spec from its configuration hash. */
    abstract fun configureContainerSpec(tc: ThreadContext, cs: ContainerSpec, config: SixModelObject)
}
