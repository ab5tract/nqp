package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/* Number native lexical reference. */
class NativeRefInstanceNumLex : NativeRefInstance() {
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
        lexicals!![idx] = value
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native string")
    }
}
