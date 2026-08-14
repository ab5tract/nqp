package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/* Integer native lexical reference. */
class NativeRefInstanceIntLex : NativeRefInstance() {
    @JvmField var lexicals: LongArray? = null
    @JvmField var idx = 0

    override fun fetch_i(tc: ThreadContext): Long {
        return lexicals!![idx]
    }

    override fun fetch_n(tc: ThreadContext): Double {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native number")
    }

    override fun fetch_s(tc: ThreadContext): String? {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native string")
    }

    override fun store_i(tc: ThreadContext, value: Long) {
        lexicals!![idx] = value
    }

    override fun store_n(tc: ThreadContext, value: Double) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native number")
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native string")
    }
}
