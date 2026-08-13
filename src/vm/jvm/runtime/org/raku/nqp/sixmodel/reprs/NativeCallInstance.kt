package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class NativeCallInstance : SixModelObject() {
    /* Body held at a level of indirection to support inlining that into a
     * P6opaque; this is about the best we can do on the JVM, which doesn't
     * support interior pointers of complex value types. */
    @JvmField var body: NativeCallBody? = null

    override fun get_int(tc: ThreadContext): Long {
        val body = this.body
        return if (body == null || body.entry_point == null) 0 else 1
    }
}
