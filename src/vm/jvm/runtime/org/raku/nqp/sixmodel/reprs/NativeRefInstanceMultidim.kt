package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/* Native attribute reference. */
class NativeRefInstanceMultidim : NativeRefInstance() {
    @JvmField var obj: SixModelObject? = null
    @JvmField var indices: LongArray? = null

    override fun fetch_i(tc: ThreadContext): Long {
        obj!!.at_pos_multidim_native(tc, indices!!)
        if (tc.native_type == ThreadContext.NATIVE_INT)
            return tc.native_i
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native int")
    }

    override fun fetch_n(tc: ThreadContext): Double {
        obj!!.at_pos_multidim_native(tc, indices!!)
        if (tc.native_type == ThreadContext.NATIVE_NUM)
            return tc.native_n
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native number")
    }

    override fun fetch_s(tc: ThreadContext): String? {
        obj!!.at_pos_multidim_native(tc, indices!!)
        if (tc.native_type == ThreadContext.NATIVE_STR)
            return tc.native_s
        else
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native string")
    }

    override fun store_i(tc: ThreadContext, value: Long) {
        tc.native_i = value
        obj!!.bind_pos_multidim_native(tc, indices!!)
        if (tc.native_type != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native int")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }

    override fun store_n(tc: ThreadContext, value: Double) {
        tc.native_n = value
        obj!!.bind_pos_multidim_native(tc, indices!!)
        if (tc.native_type != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native number")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }

    override fun store_s(tc: ThreadContext, value: String?) {
        tc.native_s = value
        obj!!.bind_pos_multidim_native(tc, indices!!)
        if (tc.native_type != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc,
                "This container does not reference a native string")
        if (obj!!.sc != null)
            Ops.scwbObject(tc, obj)
    }
}
