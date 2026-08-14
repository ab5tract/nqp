package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

open class VMArrayInstanceBase : SixModelObject() {
    override fun dimensions(tc: ThreadContext): LongArray {
        return longArrayOf(this.elems(tc))
    }

    override fun set_dimensions(tc: ThreadContext, dims: LongArray) {
        if (dims.size != 1)
            throw ExceptionHandling.dieInternal(tc, "A dynamic array can only have a single dimension")
        this.set_elems(tc, dims[0])
    }

    override fun at_pos_multidim_boxed(tc: ThreadContext, indices: LongArray): SixModelObject? {
        if (indices.size != 1)
            throw ExceptionHandling.dieInternal(tc, "A dynamic array can only be indexed with a single dimension")
        return this.at_pos_boxed(tc, indices[0])
    }

    override fun at_pos_multidim_native(tc: ThreadContext, indices: LongArray) {
        if (indices.size != 1)
            throw ExceptionHandling.dieInternal(tc, "A dynamic array can only be indexed with a single dimension")
        this.at_pos_native(tc, indices[0])
    }

    override fun bind_pos_multidim_boxed(tc: ThreadContext, indices: LongArray, value: SixModelObject?) {
        if (indices.size != 1)
            throw ExceptionHandling.dieInternal(tc, "A dynamic array can only be indexed with a single dimension")
        this.bind_pos_boxed(tc, indices[0], value)
    }

    override fun bind_pos_multidim_native(tc: ThreadContext, indices: LongArray) {
        if (indices.size != 1)
            throw ExceptionHandling.dieInternal(tc, "A dynamic array can only be indexed with a single dimension")
        this.bind_pos_native(tc, indices[0])
    }
}
