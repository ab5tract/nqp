package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/* Number native lexical reference. */
class NativeRefInstanceNumLex : NativeRefInstance() {
    /* 32 when the referenced lexical is a num32, else 0; see the IntLex
     * counterpart. A num32 store rounds to float precision. */
    @JvmField var sizeSpec = 0

    @JvmField var lexicals: DoubleArray? = null
    @JvmField var idx = 0

    override fun fetch_i(tc: ThreadContext): Long {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native integer")
    }

    override fun fetch_n(tc: ThreadContext): Double {
        return lexicals!![idx]
    }

    override fun fetch_s(tc: ThreadContext): String? {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native string")
    }

    override fun store_i(tc: ThreadContext, value: Long) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native integer")
    }

    override fun store_n(tc: ThreadContext, value: Double) {
        lexicals!![idx] = if (sizeSpec == 32) value.toFloat().toDouble() else value
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native string")
    }
}
