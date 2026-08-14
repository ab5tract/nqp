package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class VMIterInstance : SixModelObject() {
    companion object {
        /**
         * Possible modes.
         */
        const val MODE_ARRAY: Byte = 1
        const val MODE_ARRAY_INT: Byte = 2
        const val MODE_ARRAY_NUM: Byte = 3
        const val MODE_ARRAY_STR: Byte = 4
        const val MODE_HASH: Byte = 5
    }

    /**
     * Target of the iteration.
     */
    lateinit var target: SixModelObject

    /**
     * State for array iteration.
     */
    @JvmField var idx: Long = 0
    @JvmField var limit: Long = 0

    /**
     * State for hash iteration.
     */
    @JvmField var hashKeyIter: Iterator<String>? = null
    @JvmField var curKey: String? = null
    @JvmField var curValue: SixModelObject? = null
    private var beforeStart = true
    private var afterEnd = false

    /**
     * Iteration mode.
     */
    @JvmField var iterMode: Byte = 0

    /**
     * Iterators work like things you can shift from. This is mostly because
     * Parrot did it that way, and we have a load of code that expect them to
     * work in this kind of way.
     */
    override fun shift_boxed(tc: ThreadContext): SixModelObject? {
        when (iterMode) {
            MODE_ARRAY -> {
                idx++
                if (idx >= limit)
                    throw ExceptionHandling.dieInternal(tc, "Iteration past end of iterator")
                return target.at_pos_boxed(tc, idx)
            }
            MODE_ARRAY_INT -> {
                idx++
                if (idx >= limit)
                    throw ExceptionHandling.dieInternal(tc, "Iteration past end of iterator")
                target.at_pos_native(tc, idx)
                return Ops.box_i(tc.native_i, tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig!!.intBoxType, tc)
            }
            MODE_ARRAY_NUM -> {
                idx++
                if (idx >= limit)
                    throw ExceptionHandling.dieInternal(tc, "Iteration past end of iterator")
                target.at_pos_native(tc, idx)
                return Ops.box_n(tc.native_n, tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig!!.numBoxType, tc)
            }
            MODE_ARRAY_STR -> {
                idx++
                if (idx >= limit)
                    throw ExceptionHandling.dieInternal(tc, "Iteration past end of iterator")
                target.at_pos_native(tc, idx)
                return Ops.box_s(tc.native_s, tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig!!.strBoxType, tc)
            }
            MODE_HASH -> {
                if (!hashKeyIter!!.hasNext()) {
                    afterEnd = true
                    throw ExceptionHandling.dieInternal(tc, "Iteration past end of iterator")
                }
                curKey = hashKeyIter!!.next()
                curValue = target.at_key_boxed(tc, curKey)
                beforeStart = false
                return this
            }
            else ->
                throw ExceptionHandling.dieInternal(tc, "Unknown iteration mode")
        }
    }

    /**
     * Not part of the 6model API, just keeps some code in here.
     */
    fun boolify(): Boolean {
        when (iterMode) {
            MODE_ARRAY, MODE_ARRAY_INT, MODE_ARRAY_NUM, MODE_ARRAY_STR ->
                return idx + 1 < limit
            MODE_HASH ->
                return hashKeyIter!!.hasNext()
            else ->
                throw RuntimeException("Unknown iteration mode")
        }
    }

    fun key_s(tc: ThreadContext): String? {
        when (iterMode) {
            MODE_ARRAY ->
                return idx.toString()
            MODE_HASH -> {
                if (beforeStart || afterEnd)
                    throw ExceptionHandling.dieInternal(tc, "You have not advanced to the first item of the hash iterator, or have gone past the end")
                return curKey
            }
            else ->
                throw ExceptionHandling.dieInternal(tc, "Unknown iteration mode")
        }
    }

    fun `val`(tc: ThreadContext): SixModelObject? {
        when (iterMode) {
            MODE_ARRAY ->
                return target.at_pos_boxed(tc, idx)
            MODE_HASH -> {
                if (beforeStart || afterEnd)
                    throw ExceptionHandling.dieInternal(tc, "You have not advanced to the first item of the hash iterator, or have gone past the end")
                return curValue
            }
            else ->
                throw ExceptionHandling.dieInternal(tc, "Unknown iteration mode")
        }
    }
}
