package org.raku.nqp.sixmodel.reprs

import com.sun.jna.Memory
import com.sun.jna.Native

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class CStrInstance : SixModelObject() {
    @JvmField var cstr: Memory? = null

    override fun set_str(tc: ThreadContext, value: String?) {
        /* TODO: Handle encodings. */
        val bytes = Native.toByteArray(value)
        val cstr = Memory(bytes.size.toLong())
        cstr.write(0, bytes, 0, bytes.size)
        this.cstr = cstr
    }

    override fun get_str(tc: ThreadContext): String =
        cstr!!.getString(0, Native.getDefaultStringEncoding())
}
