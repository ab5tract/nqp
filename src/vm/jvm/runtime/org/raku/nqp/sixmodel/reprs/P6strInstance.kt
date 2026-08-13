package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class P6strInstance : SixModelObject() {
    @JvmField var value: String? = null

    override fun set_str(tc: ThreadContext, value: String?) {
        this.value = value
    }

    override fun get_str(tc: ThreadContext): String? = value
}
