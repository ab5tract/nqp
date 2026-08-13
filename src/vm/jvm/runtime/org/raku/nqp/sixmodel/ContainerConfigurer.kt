package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ThreadContext

/**
 * A container configurer knows how to attach a certain type of container
 * to an STable and configure it.
 */
abstract class ContainerConfigurer {
    /* Sets this container spec in place for the specified STable. */
    abstract fun setContainerSpec(tc: ThreadContext, st: STable)

    /* Configures the container spec with the specified info. */
    abstract fun configureContainerSpec(tc: ThreadContext, st: STable, config: SixModelObject)
}
