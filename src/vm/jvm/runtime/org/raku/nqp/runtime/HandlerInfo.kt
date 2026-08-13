package org.raku.nqp.runtime

import org.raku.nqp.sixmodel.reprs.VMExceptionInstance

/* Describes an exception handler currently being processed. */
class HandlerInfo(
    @JvmField var exObj: VMExceptionInstance?,
    @JvmField var handlerInfo: LongArray,
)
