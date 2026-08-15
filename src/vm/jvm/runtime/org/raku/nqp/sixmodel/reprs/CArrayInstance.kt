package org.raku.nqp.sixmodel.reprs

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

import java.util.Arrays
import java.util.HashMap

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeCallOps
import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.SixModelObject

import org.raku.nqp.sixmodel.reprs.CArrayREPRData.ElemKind

class CArrayInstance : SixModelObject(), Refreshable {
    @JvmField var storage: MemorySegment? = null
    @JvmField var child_objs: Array<SixModelObject?>? = null
    @JvmField var managed = false
    @JvmField var allocated: Long = 0
    @JvmField var elems: Long = 0
    /* C strings we allocated for elements bound into the array; it holds only
     * their addresses, so we have to keep them reachable ourselves. */
    private val pinned = HashMap<Long, MemorySegment>()

    override fun at_pos_native(tc: ThreadContext, index: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (managed && index >= elems) {
            if (repr_data.elemKind == ElemKind.INTEGER) {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = 0
            }
            else if (repr_data.elemKind == ElemKind.NUMERIC) {
                tc.native_type = ThreadContext.NATIVE_NUM
                tc.native_n = 0.0
            }
            else {
                ExceptionHandling.dieInternal(tc, "CArray can only at_pos_native with ints and nums.")
            }
            return
        }

        val offset = index * repr_data.elemBytes
        if (repr_data.elemKind == ElemKind.INTEGER) {
            tc.native_type = ThreadContext.NATIVE_INT
            when (repr_data.elemSize.toInt()) {
                8 -> tc.native_i = storage!!.get(ValueLayout.JAVA_BYTE, offset).toLong()
                16 -> tc.native_i = storage!!.get(ValueLayout.JAVA_SHORT, offset).toLong()
                32 -> tc.native_i = storage!!.get(ValueLayout.JAVA_INT, offset).toLong()
                64 -> tc.native_i = storage!!.get(ValueLayout.JAVA_LONG, offset)
            }
        }
        else if (repr_data.elemKind == ElemKind.NUMERIC) {
            tc.native_type = ThreadContext.NATIVE_NUM
            when (repr_data.elemSize.toInt()) {
                32 -> tc.native_n = storage!!.get(ValueLayout.JAVA_FLOAT, offset).toDouble()
                64 -> tc.native_n = storage!!.get(ValueLayout.JAVA_DOUBLE, offset)
            }
        }
        else {
            ExceptionHandling.dieInternal(tc, "CArray can only at_pos_native with ints and nums.")
        }
    }

    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        val repr_data = st.REPRData as CArrayREPRData
        val intidx = index.toInt()

        /* TODO: Die if this is a NUMERIC/INTEGER CArray. */

        if (managed && index >= elems)
            return repr_data.elemType
        else if (index >= allocated)
            expand(tc, index + 1)

        if (Ops.isnull(child_objs!![intidx]) == 0L) {
            return child_objs!![intidx]
        }
        else {
            val obj = makeObject(tc, storage!!.get(ValueLayout.ADDRESS, index * repr_data.elemBytes))
            child_objs!![intidx] = obj
            return obj
        }
    }

    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (index >= allocated) {
            expand(tc, index + 1)
        }

        val offset = index * repr_data.elemBytes
        if (repr_data.elemKind == ElemKind.INTEGER) {
            tc.native_type = ThreadContext.NATIVE_INT
            when (repr_data.elemSize.toInt()) {
                8 -> storage!!.set(ValueLayout.JAVA_BYTE, offset, tc.native_i.toByte())
                16 -> storage!!.set(ValueLayout.JAVA_SHORT, offset, tc.native_i.toShort())
                32 -> storage!!.set(ValueLayout.JAVA_INT, offset, tc.native_i.toInt())
                64 -> storage!!.set(ValueLayout.JAVA_LONG, offset, tc.native_i)
            }
        }
        else if (repr_data.elemKind == ElemKind.NUMERIC) {
            tc.native_type = ThreadContext.NATIVE_NUM
            when (repr_data.elemSize.toInt()) {
                32 -> storage!!.set(ValueLayout.JAVA_FLOAT, offset, tc.native_n.toFloat())
                64 -> storage!!.set(ValueLayout.JAVA_DOUBLE, offset, tc.native_n)
            }
        }
        else {
            ExceptionHandling.dieInternal(tc, "CArray can only bind_pos_native with ints and nums.")
        }
    }

    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        val repr_data = st.REPRData as CArrayREPRData
        val intidx = index.toInt()
        val deconted = Ops.decont(value, tc)

        /* TODO: Die if this is a NUMERIC/INTEGER CArray. */
        if (index >= allocated)
            expand(tc, index + 1)

        var ptr: MemorySegment? = null
        if (Ops.isconcrete(deconted, tc) != 0L) {
            when (repr_data.elemKind) {
                ElemKind.STRING -> {
                    /* TODO: Handle encodings. */
                    ptr = NativeSupport.toCString(deconted!!.get_str(tc))
                    pinned.put(index, ptr)
                }
                ElemKind.CARRAY ->
                    ptr = (deconted as CArrayInstance).storage
                ElemKind.CSTRUCT, ElemKind.CPPSTRUCT, ElemKind.CUNION ->
                    ptr = (deconted as CTypeInstance).storage
                ElemKind.CPOINTER ->
                    ptr = (deconted as CPointerInstance).pointer
                else ->
                    ExceptionHandling.dieInternal(tc, "CArray.bind_pos_boxed reached its default case. This should never happen.")
            }
        }
        else if (repr_data.elemKind == ElemKind.STRING) {
            pinned.remove(index)
        }

        child_objs!![intidx] = deconted
        storage!!.set(ValueLayout.ADDRESS, index * repr_data.elemBytes, NativeSupport.orNull(ptr))
    }

    private fun expand(tc: ThreadContext, new_size: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (managed) {
            val new_storage = NativeSupport.allocate(new_size * repr_data.elemBytes)
            val old_storage = storage
            if (old_storage != null)
                MemorySegment.copy(old_storage, 0L, new_storage, 0L, allocated * repr_data.elemBytes)
            storage = new_storage
        }

        val complex = repr_data.elemKind == ElemKind.CARRAY
            || repr_data.elemKind == ElemKind.CPOINTER
            || repr_data.elemKind == ElemKind.CSTRUCT
            || repr_data.elemKind == ElemKind.CPPSTRUCT
            || repr_data.elemKind == ElemKind.CUNION
            || repr_data.elemKind == ElemKind.STRING

        if (complex) {
            val existing = child_objs
            child_objs = if (existing == null)
                arrayOfNulls(new_size.toInt())
            else
                Arrays.copyOf(existing, new_size.toInt())
        }

        elems = new_size
        allocated = new_size
    }

    override fun elems(tc: ThreadContext): Long {
        return elems
    }

    private fun makeObject(tc: ThreadContext, raw: MemorySegment?): SixModelObject? {
        val repr_data = st.REPRData as CArrayREPRData
        val ptr = NativeSupport.unbounded(raw)

        if (ptr == null)
            return repr_data.elemType

        when (repr_data.elemKind) {
            ElemKind.STRING ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.UTF8STR, repr_data.elemType, NativeSupport.fromCString(ptr))
            ElemKind.CARRAY ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CARRAY, repr_data.elemType, ptr)
            ElemKind.CPOINTER ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPOINTER, repr_data.elemType, ptr)
            ElemKind.CSTRUCT ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CSTRUCT, repr_data.elemType, ptr)
            ElemKind.CPPSTRUCT ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPPSTRUCT, repr_data.elemType, ptr)
            ElemKind.CUNION ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CUNION, repr_data.elemType, ptr)
            else ->
                ExceptionHandling.dieInternal(tc, "CArray can only makeObject strings, arrays, structs and pointers")
        }

        /* And a dummy return statement to placate Java's flow analysis. */
        return null
    }

    override fun refresh(tc: ThreadContext) {
        val repr_data = st.REPRData as CArrayREPRData

        // No need to refresh if we don't have any cached children.
        if (child_objs == null) return

        if (repr_data.elemKind == ElemKind.CARRAY
         || repr_data.elemKind == ElemKind.CSTRUCT
         || repr_data.elemKind == ElemKind.CPPSTRUCT
         || repr_data.elemKind == ElemKind.CUNION) {
            refreshComplex(tc)
        }
        else {
            refreshSimple(tc)
        }
    }

    /**
     * Refresh logic for CArray of complex (CArray or CStruct) types.
     */
    private fun refreshComplex(tc: ThreadContext) {
        val children = child_objs!!
        for (i in children.indices) {
            val child = children[i]

            // No cache for this element? Go to next.
            if (Ops.isnull(child) == 1L) continue

            /* Invalidate cache and recursively refresh child too. Future
             * versions here should only invalidate the cache if C memory has
             * a different pointer than the cached object.
             */
            children[i] = null
            NativeCallOps.refresh(child, tc)
        }
    }

    /**
     * Refresh logic for CArray of simple (CPointer) types.
     */
    private fun refreshSimple(tc: ThreadContext) {
        val children = child_objs!!
        for (i in children.indices) {
            val child = children[i]

            // No cache for this element? Go to next.
            if (Ops.isnull(child) == 1L) continue

            /* Invalidate cache. Future versions here should only invalidate
             * the cache if C memory has a different pointer than the cached
             * object.
             */
            children[i] = null
        }
    }
}
