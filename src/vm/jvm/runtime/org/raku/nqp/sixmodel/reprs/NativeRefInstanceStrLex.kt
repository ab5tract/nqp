package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/* String native lexical reference. */
class NativeRefInstanceStrLex : NativeRefInstance() {
    @JvmField var lexicals: Array<String?>? = null
    @JvmField var idx = 0

    override fun fetch_i(tc: ThreadContext): Long {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native integer")
    }

    override fun fetch_n(tc: ThreadContext): Double {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native number")
    }

    override fun fetch_s(tc: ThreadContext): String? {
        return lexicals!![idx]
    }

    override fun store_i(tc: ThreadContext, value: Long) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native integer")
    }

    override fun store_n(tc: ThreadContext, value: Double) {
        throw ExceptionHandling.dieInternal(tc,
            "This container does not reference a native number")
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        lexicals!![idx] = value
    }
}
