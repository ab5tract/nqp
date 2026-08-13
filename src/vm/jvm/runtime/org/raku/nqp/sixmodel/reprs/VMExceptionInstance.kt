package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.sixmodel.SixModelObject

class VMExceptionInstance : SixModelObject() {
    @JvmField var message: String? = null
    @JvmField var payload: SixModelObject? = null
    @JvmField var category = ExceptionHandling.EX_CAT_CATCH.toLong()
    @JvmField var resumable = false
    @JvmField var origin: CallFrame? = null
    @JvmField var nativeTrace: Array<StackTraceElement>? = null
}
