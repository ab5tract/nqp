package org.raku.nqp.sixmodel.reprs

import java.lang.foreign.MemorySegment

import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class CStrInstance : SixModelObject() {
    @JvmField var cstr: MemorySegment? = null

    override fun set_str(tc: ThreadContext, value: String?) {
        /* TODO: Handle encodings. */
        this.cstr = NativeSupport.toCString(value)
    }

    override fun get_str(tc: ThreadContext): String = cstr!!.getString(0)
}
