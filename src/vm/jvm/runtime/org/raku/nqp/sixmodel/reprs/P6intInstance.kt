package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class P6intInstance : SixModelObject() {
    @JvmField var value = 0L

    override fun set_int(tc: ThreadContext, value: Long) {
        this.value = P6int.sizedValue(st.REPRData as? org.raku.nqp.sixmodel.StorageSpec, value)
    }

    override fun get_int(tc: ThreadContext): Long = value

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val clone = super.clone() as P6intInstance
            clone.sc = null
            return clone
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }
}
