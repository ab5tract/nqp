package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject

abstract class MultiDimArrayInstanceBase : SixModelObject() {
    /* Assigned by the MultiDimArray REPR at allocation; the generated
     * subclasses read it raw. */
    lateinit var dimensions: LongArray

    override fun dimensions(tc: ThreadContext): LongArray {
        return dimensions
    }

    override fun set_dimensions(tc: ThreadContext, dims: LongArray) {
        val rd = this.st.REPRData as MultiDimArrayREPRData
        if (rd.numDimensions == dims.size) {
            System.arraycopy(dims, 0, this.dimensions, 0, dims.size)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, String.format(
                "Array type of %d dimensions cannot be initialized with %d dimensions",
                rd.numDimensions, dims.size))
        }
    }

    protected fun numSlots(): Int {
        var result = dimensions[0]
        for (i in 1 until dimensions.size)
            result *= dimensions[i]
        return result.toInt()
    }

    protected fun duplicateSetDimensions(tc: ThreadContext) {
        throw ExceptionHandling.dieInternal(tc,
            "MultiDimArray: can only set dimensions once")
    }

    protected fun indicesToFlatIndex(tc: ThreadContext, indices: LongArray): Int {
        if (indices.size == dimensions.size) {
            var multiplier = 1L
            var result = 0L
            for (i in dimensions.size - 1 downTo 0) {
                val dim_size = dimensions[i]
                val index = indices[i]
                if (index >= 0 && index < dim_size) {
                    result += index * multiplier
                    multiplier *= dim_size
                }
                else {
                    throw ExceptionHandling.dieInternal(tc, String.format(
                        "Index %d for dimension %d out of range (must be 0..%d)",
                        index, i + 1, dim_size - 1))
                }
            }
            return result.toInt()
        }
        else {
            throw ExceptionHandling.dieInternal(tc, String.format(
            "Cannot access %d dimension array with %d indices",
            dimensions.size, indices.size))
        }
    }

    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot push onto a fixed dimension array")
    }
    override fun push_native(tc: ThreadContext) {
        throw ExceptionHandling.dieInternal(tc, "Cannot push onto a fixed dimension array")
    }
    override fun pop_boxed(tc: ThreadContext): SixModelObject? {
        throw ExceptionHandling.dieInternal(tc, "Cannot pop a fixed dimension array")
    }
    override fun pop_native(tc: ThreadContext) {
        throw ExceptionHandling.dieInternal(tc, "Cannot pop a fixed dimension array")
    }
    override fun unshift_boxed(tc: ThreadContext, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot unshift onto a fixed dimension array")
    }
    override fun unshift_native(tc: ThreadContext) {
        throw ExceptionHandling.dieInternal(tc, "Cannot unshift onto a fixed dimension array")
    }
    override fun shift_boxed(tc: ThreadContext): SixModelObject? {
        throw ExceptionHandling.dieInternal(tc, "Cannot shift a fixed dimension array")
    }
    override fun shift_native(tc: ThreadContext) {
        throw ExceptionHandling.dieInternal(tc, "Cannot shift a fixed dimension array")
    }
    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject? {
        throw ExceptionHandling.dieInternal(tc, "Cannot slice a multidim array")
    }
    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot splice a fixed dimension array")
    }

    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        return this.at_pos_multidim_boxed(tc, longArrayOf(index))
    }
    override fun at_pos_native(tc: ThreadContext, index: Long) {
        this.at_pos_multidim_native(tc, longArrayOf(index))
    }
    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        this.bind_pos_multidim_boxed(tc, longArrayOf(index), value)
    }
    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        this.bind_pos_multidim_native(tc, longArrayOf(index))
    }
    override fun set_elems(tc: ThreadContext, count: Long) {
        this.set_dimensions(tc, longArrayOf(count))
    }
    override fun elems(tc: ThreadContext): Long {
        return this.dimensions(tc)[0]
    }

    abstract override fun clone(tc: ThreadContext): SixModelObject
    abstract fun serializeValues(tc: ThreadContext, writer: SerializationWriter)
    abstract fun deserializeValues(tc: ThreadContext, reader: SerializationReader)
}
