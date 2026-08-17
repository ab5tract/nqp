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
    @JvmField var childObjs: Array<SixModelObject?>? = null
    @JvmField var managed = false
    @JvmField var allocated: Long = 0
    @JvmField var elems: Long = 0
    /* C strings we allocated for elements bound into the array; it holds only
     * their addresses, so we have to keep them reachable ourselves. */
    private val pinned = HashMap<Long, MemorySegment>()

    override fun at_pos_native(tc: ThreadContext, index: Long) {
        val reprData = st.REPRData as CArrayREPRData

        if (managed && index >= elems) {
            if (reprData.elemKind == ElemKind.INTEGER) {
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = 0
            }
            else if (reprData.elemKind == ElemKind.NUMERIC) {
                tc.nativeType = ThreadContext.NATIVE_NUM
                tc.nativeN = 0.0
            }
            else {
                ExceptionHandling.dieInternal(tc, "CArray can only at_pos_native with ints and nums.")
            }
            return
        }

        val offset = index * reprData.elemBytes
        if (reprData.elemKind == ElemKind.INTEGER) {
            tc.nativeType = ThreadContext.NATIVE_INT
            when (reprData.elemSize.toInt()) {
                8 -> tc.nativeI = storage!!.get(ValueLayout.JAVA_BYTE, offset).toLong()
                16 -> tc.nativeI = storage!!.get(ValueLayout.JAVA_SHORT, offset).toLong()
                32 -> tc.nativeI = storage!!.get(ValueLayout.JAVA_INT, offset).toLong()
                64 -> tc.nativeI = storage!!.get(ValueLayout.JAVA_LONG, offset)
            }
        }
        else if (reprData.elemKind == ElemKind.NUMERIC) {
            tc.nativeType = ThreadContext.NATIVE_NUM
            when (reprData.elemSize.toInt()) {
                32 -> tc.nativeN = storage!!.get(ValueLayout.JAVA_FLOAT, offset).toDouble()
                64 -> tc.nativeN = storage!!.get(ValueLayout.JAVA_DOUBLE, offset)
            }
        }
        else {
            ExceptionHandling.dieInternal(tc, "CArray can only at_pos_native with ints and nums.")
        }
    }

    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        val reprData = st.REPRData as CArrayREPRData
        val intidx = index.toInt()

        /* TODO: Die if this is a NUMERIC/INTEGER CArray. */

        if (managed && index >= elems)
            return reprData.elemType
        else if (index >= allocated)
            expand(tc, index + 1)

        if (Ops.isnull(childObjs!![intidx]) == 0L) {
            return childObjs!![intidx]
        }
        else {
            val obj = makeObject(tc, storage!!.get(ValueLayout.ADDRESS, index * reprData.elemBytes))
            childObjs!![intidx] = obj
            return obj
        }
    }

    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        val reprData = st.REPRData as CArrayREPRData

        if (index >= allocated) {
            expand(tc, index + 1)
        }

        val offset = index * reprData.elemBytes
        if (reprData.elemKind == ElemKind.INTEGER) {
            tc.nativeType = ThreadContext.NATIVE_INT
            when (reprData.elemSize.toInt()) {
                8 -> storage!!.set(ValueLayout.JAVA_BYTE, offset, tc.nativeI.toByte())
                16 -> storage!!.set(ValueLayout.JAVA_SHORT, offset, tc.nativeI.toShort())
                32 -> storage!!.set(ValueLayout.JAVA_INT, offset, tc.nativeI.toInt())
                64 -> storage!!.set(ValueLayout.JAVA_LONG, offset, tc.nativeI)
            }
        }
        else if (reprData.elemKind == ElemKind.NUMERIC) {
            tc.nativeType = ThreadContext.NATIVE_NUM
            when (reprData.elemSize.toInt()) {
                32 -> storage!!.set(ValueLayout.JAVA_FLOAT, offset, tc.nativeN.toFloat())
                64 -> storage!!.set(ValueLayout.JAVA_DOUBLE, offset, tc.nativeN)
            }
        }
        else {
            ExceptionHandling.dieInternal(tc, "CArray can only bind_pos_native with ints and nums.")
        }
    }

    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        val reprData = st.REPRData as CArrayREPRData
        val intidx = index.toInt()
        val deconted = Ops.decont(value, tc)

        /* TODO: Die if this is a NUMERIC/INTEGER CArray. */
        if (index >= allocated)
            expand(tc, index + 1)

        var ptr: MemorySegment? = null
        if (Ops.isconcrete(deconted, tc) != 0L) {
            when (reprData.elemKind) {
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
        else if (reprData.elemKind == ElemKind.STRING) {
            pinned.remove(index)
        }

        childObjs!![intidx] = deconted
        storage!!.set(ValueLayout.ADDRESS, index * reprData.elemBytes, NativeSupport.orNull(ptr))
    }

    private fun expand(tc: ThreadContext, newSize: Long) {
        val reprData = st.REPRData as CArrayREPRData

        if (managed) {
            val newStorage = NativeSupport.allocate(newSize * reprData.elemBytes)
            val oldStorage = storage
            if (oldStorage != null)
                MemorySegment.copy(oldStorage, 0L, newStorage, 0L, allocated * reprData.elemBytes)
            storage = newStorage
        }

        val complex = reprData.elemKind == ElemKind.CARRAY
            || reprData.elemKind == ElemKind.CPOINTER
            || reprData.elemKind == ElemKind.CSTRUCT
            || reprData.elemKind == ElemKind.CPPSTRUCT
            || reprData.elemKind == ElemKind.CUNION
            || reprData.elemKind == ElemKind.STRING

        if (complex) {
            val existing = childObjs
            childObjs = if (existing == null)
                arrayOfNulls(newSize.toInt())
            else
                Arrays.copyOf(existing, newSize.toInt())
        }

        elems = newSize
        allocated = newSize
    }

    override fun elems(tc: ThreadContext): Long {
        return elems
    }

    private fun makeObject(tc: ThreadContext, raw: MemorySegment?): SixModelObject? {
        val reprData = st.REPRData as CArrayREPRData
        val ptr = NativeSupport.unbounded(raw)

        if (ptr == null)
            return reprData.elemType

        when (reprData.elemKind) {
            ElemKind.STRING ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.UTF8STR, reprData.elemType, NativeSupport.fromCString(ptr))
            ElemKind.CARRAY ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CARRAY, reprData.elemType, ptr)
            ElemKind.CPOINTER ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPOINTER, reprData.elemType, ptr)
            ElemKind.CSTRUCT ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CSTRUCT, reprData.elemType, ptr)
            ElemKind.CPPSTRUCT ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPPSTRUCT, reprData.elemType, ptr)
            ElemKind.CUNION ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CUNION, reprData.elemType, ptr)
            else ->
                ExceptionHandling.dieInternal(tc, "CArray can only makeObject strings, arrays, structs and pointers")
        }

        /* And a dummy return statement to placate Java's flow analysis. */
        return null
    }

    override fun refresh(tc: ThreadContext) {
        val reprData = st.REPRData as CArrayREPRData

        // No need to refresh if we don't have any cached children.
        if (childObjs == null) return

        if (reprData.elemKind == ElemKind.CARRAY
         || reprData.elemKind == ElemKind.CSTRUCT
         || reprData.elemKind == ElemKind.CPPSTRUCT
         || reprData.elemKind == ElemKind.CUNION) {
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
        val children = childObjs!!
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
        val children = childObjs!!
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
