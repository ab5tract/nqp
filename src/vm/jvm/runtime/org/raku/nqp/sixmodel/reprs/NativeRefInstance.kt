package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/* Base for instances of native references. */
abstract class NativeRefInstance : SixModelObject() {
    abstract fun fetch_i(tc: ThreadContext): Long
    abstract fun fetch_n(tc: ThreadContext): Double
    abstract fun fetch_s(tc: ThreadContext): String?
    abstract fun store_i(tc: ThreadContext, value: Long)
    abstract fun store_n(tc: ThreadContext, value: Double)
    abstract fun store_s(tc: ThreadContext, value: String?)
}
