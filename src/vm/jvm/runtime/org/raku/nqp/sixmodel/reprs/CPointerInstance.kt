package org.raku.nqp.sixmodel.reprs

import com.sun.jna.Pointer

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class CPointerInstance : SixModelObject() {
    @JvmField var pointer: Pointer? = null

    override fun set_int(tc: ThreadContext, value: Long) {
        this.pointer = if (value > 0) Pointer.createConstant(value) else null
    }

    override fun get_int(tc: ThreadContext): Long {
        val pointer = this.pointer
        return if (pointer == null) 0 else Pointer.nativeValue(pointer)
    }
}
