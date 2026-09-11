package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/* Integer native lexical reference. */
class NativeRefInstanceIntLex : NativeRefInstance() {
    @JvmField var lexicals: LongArray? = null
    @JvmField var idx = 0

    /* Width of the referenced lexical's declared type, when sized: the low
     * byte is the bit width, +256 marks unsigned; 0 means full width. Set by
     * Ops.sizedref from compile-time knowledge, since the long slot itself
     * carries no size. A sized store truncates the way MoarVM's sized
     * registers do. */
    @JvmField var sizeSpec = 0

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
        lexicals!![idx] = if (sizeSpec == 0) value else {
            val bits = sizeSpec and 0xFF
            if (sizeSpec >= 256) value and ((1L shl bits) - 1)
            else (value shl (64 - bits)) shr (64 - bits)
        }
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
