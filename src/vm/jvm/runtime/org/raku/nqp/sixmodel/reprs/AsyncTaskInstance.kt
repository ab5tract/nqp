package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class AsyncTaskInstance : SixModelObject() {

    @JvmField var queue: SixModelObject? = null
    @JvmField var schedulee: SixModelObject? = null

    /* Object that can perform I/O operations; will be checked for its
     * capabilities by interface by ops and then invoked. */
    @JvmField var handle: Any? = null

    /* Sequence number for incremental ops */
    @JvmField var seq = 0L
}
