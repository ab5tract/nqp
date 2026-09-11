package org.raku.nqp.sixmodel.reprs

import java.lang.foreign.MemorySegment

import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class CPointerInstance : SixModelObject() {
    @JvmField var pointer: MemorySegment? = null

    override fun set_int(tc: ThreadContext, value: Long) {
        /* NOTE: an address with the top bit set reads as null here, as it did
         * through JNA's Pointer.createConstant guard. */
        this.pointer = if (value > 0) NativeSupport.pointer(value) else null
    }

    override fun get_int(tc: ThreadContext): Long = NativeSupport.address(pointer)
}
