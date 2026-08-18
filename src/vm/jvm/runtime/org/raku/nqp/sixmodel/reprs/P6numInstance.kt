package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class P6numInstance : SixModelObject() {
    @JvmField var value = 0.0

    override fun set_num(tc: ThreadContext, value: Double) {
        val bits = (st.REPRData as? org.raku.nqp.sixmodel.StorageSpec)?.bits?.toInt() ?: 64
        this.value = if (bits == 32) value.toFloat().toDouble() else value
    }

    override fun get_num(tc: ThreadContext): Double = value
}
