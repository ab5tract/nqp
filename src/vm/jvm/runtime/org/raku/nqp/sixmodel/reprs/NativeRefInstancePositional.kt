package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/* Native attribute reference. */
class NativeRefInstancePositional : NativeRefInstance() {
    @JvmField var obj: SixModelObject? = null
    @JvmField var idx: Long = 0

    override fun fetch_i(tc: ThreadContext): Long {
        obj!!.at_pos_native(tc, idx)
        if (tc.nativeType == ThreadContext.NATIVE_INT)
            return tc.nativeI
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native int")
    }

    override fun fetch_n(tc: ThreadContext): Double {
        obj!!.at_pos_native(tc, idx)
        if (tc.nativeType == ThreadContext.NATIVE_NUM)
            return tc.nativeN
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native number")
    }

    override fun fetch_s(tc: ThreadContext): String? {
        obj!!.at_pos_native(tc, idx)
        if (tc.nativeType == ThreadContext.NATIVE_STR)
            return tc.nativeS
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native string")
    }

    override fun store_i(tc: ThreadContext, value: Long) {
        tc.nativeI = value
        obj!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native int")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }

    override fun store_n(tc: ThreadContext, value: Double) {
        tc.nativeN = value
        obj!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native number")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        tc.nativeS = value
        obj!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native string")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }
}
