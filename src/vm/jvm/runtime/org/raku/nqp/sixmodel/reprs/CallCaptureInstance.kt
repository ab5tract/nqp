package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class CallCaptureInstance : SixModelObject() {
    @JvmField var descriptor: CallSiteDescriptor? = null
    @JvmField var args: Array<Any?>? = null

    override fun clone(tc: ThreadContext): SixModelObject {
        val clone = CallCaptureInstance()
        clone.st = this.st
        clone.descriptor = this.descriptor
        clone.args = this.args!!.clone()
        return clone
    }
}
