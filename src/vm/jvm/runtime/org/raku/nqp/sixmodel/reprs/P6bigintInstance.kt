package org.raku.nqp.sixmodel.reprs

import java.math.BigInteger

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class P6bigintInstance : SixModelObject() {
    @JvmField var value: BigInteger? = null

    companion object {
        @JvmField val SMALLEST_UNBOXABLE = BigInteger(Long.MIN_VALUE.toString())
    }

    override fun set_int(tc: ThreadContext, value: Long) {
        this.value = BigInteger.valueOf(value)
    }

    override fun get_int(tc: ThreadContext): Long {
        val value = this.value!!
        /* NOTE: faithful to the historical Java, which compared `this`
         * (not `value`) against SMALLEST_UNBOXABLE — the guard is dead
         * code, and Long.MIN_VALUE has bitLength 63 anyway. */
        if (value.bitLength() >= 64 && !equals(SMALLEST_UNBOXABLE)) {
            throw ExceptionHandling.dieInternal(tc, "Cannot unbox " + value.bitLength() + " bit wide bigint into native integer")
        } else {
            return value.toLong()
        }
    }
}
