package org.raku.nqp.sixmodel.reprs

import java.util.Arrays

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeCallOps
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.SixModelObject

import org.raku.nqp.sixmodel.reprs.CArrayREPRData.ElemKind

class CArrayInstance : SixModelObject(), Refreshable {
    @JvmField var storage: Pointer? = null
    @JvmField var child_objs: Array<SixModelObject?>? = null
    @JvmField var managed = false
    @JvmField var allocated: Long = 0
    @JvmField var elems: Long = 0

    override fun at_pos_native(tc: ThreadContext, index: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (managed && index >= elems) {
            if (repr_data.elem_kind == ElemKind.INTEGER) {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = 0
            }
            else if (repr_data.elem_kind == ElemKind.NUMERIC) {
                tc.native_type = ThreadContext.NATIVE_NUM
                tc.native_n = 0.0
            }
            else {
                ExceptionHandling.dieInternal(tc, "CArray can only at_pos_native with ints and nums.")
            }
            return
        }

        if (repr_data.elem_kind == ElemKind.INTEGER) {
            tc.native_type = ThreadContext.NATIVE_INT
            when (repr_data.elem_size.toInt()) {
                8 -> tc.native_i = storage!!.getByte(index * repr_data.jna_size).toLong()
                16 -> tc.native_i = storage!!.getShort(index * repr_data.jna_size).toLong()
                32 -> tc.native_i = storage!!.getInt(index * repr_data.jna_size).toLong()
                64 -> tc.native_i = storage!!.getLong(index * repr_data.jna_size)
            }
        }
        else if (repr_data.elem_kind == ElemKind.NUMERIC) {
            tc.native_type = ThreadContext.NATIVE_NUM
            when (repr_data.elem_size.toInt()) {
                32 -> tc.native_n = storage!!.getFloat(index * repr_data.jna_size).toDouble()
                64 -> tc.native_n = storage!!.getDouble(index * repr_data.jna_size)
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
            return repr_data.elem_type
        else if (index >= allocated)
            expand(tc, index + 1)

        if (Ops.isnull(child_objs!![intidx]) == 0L) {
            return child_objs!![intidx]
        }
        else {
            val obj = makeObject(tc, storage!!.getPointer(index * repr_data.jna_size))
            child_objs!![intidx] = obj
            return obj
        }
    }

    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (index >= allocated) {
            expand(tc, index + 1)
        }

        if (repr_data.elem_kind == ElemKind.INTEGER) {
            tc.native_type = ThreadContext.NATIVE_INT
            when (repr_data.elem_size.toInt()) {
                8 -> storage!!.setByte(index * repr_data.jna_size, tc.native_i.toByte())
                16 -> storage!!.setShort(index * repr_data.jna_size, tc.native_i.toShort())
                32 -> storage!!.setInt(index * repr_data.jna_size, tc.native_i.toInt())
                64 -> storage!!.setLong(index * repr_data.jna_size, tc.native_i)
            }
        }
        else if (repr_data.elem_kind == ElemKind.NUMERIC) {
            tc.native_type = ThreadContext.NATIVE_NUM
            when (repr_data.elem_size.toInt()) {
                32 -> storage!!.setFloat(index * repr_data.jna_size, tc.native_n.toFloat())
                64 -> storage!!.setDouble(index * repr_data.jna_size, tc.native_n)
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

        var ptr: Pointer? = null
        if (Ops.isconcrete(deconted, tc) != 0L) {
            when (repr_data.elem_kind) {
                ElemKind.STRING -> {
                    val bytes = Native.toByteArray(deconted!!.get_str(tc))
                    val mem = Memory(bytes.size.toLong())
                    mem.write(0, bytes, 0, bytes.size)
                    ptr = mem
                }
                ElemKind.CARRAY ->
                    ptr = (deconted as CArrayInstance).storage
                ElemKind.CSTRUCT ->
                    ptr = (deconted as CStructInstance).storage!!.getPointer()
                ElemKind.CPPSTRUCT ->
                    ptr = (deconted as CPPStructInstance).storage!!.getPointer()
                ElemKind.CUNION ->
                    ptr = (deconted as CUnionInstance).storage!!.getPointer()
                ElemKind.CPOINTER ->
                    ptr = (deconted as CPointerInstance).pointer
                else ->
                    ExceptionHandling.dieInternal(tc, "CArray.bind_pos_boxed reached its default case. This should never happen.")
            }
        }

        child_objs!![intidx] = deconted
        storage!!.setPointer(index * repr_data.jna_size, ptr)
    }

    private fun expand(tc: ThreadContext, new_size: Long) {
        val repr_data = st.REPRData as CArrayREPRData

        if (managed) {
            val new_storage = Memory(new_size * repr_data.jna_size)
            new_storage.clear()
            val old_storage = storage
            if (old_storage != null) {
                old_storage as Memory
                new_storage.write(0, old_storage.getByteArray(0, old_storage.size().toInt()), 0, old_storage.size().toInt())
            }
            storage = new_storage
        }

        val complex = repr_data.elem_kind == ElemKind.CARRAY
            || repr_data.elem_kind == ElemKind.CPOINTER
            || repr_data.elem_kind == ElemKind.CSTRUCT
            || repr_data.elem_kind == ElemKind.CPPSTRUCT
            || repr_data.elem_kind == ElemKind.CUNION
            || repr_data.elem_kind == ElemKind.STRING

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

    private fun makeObject(tc: ThreadContext, ptr: Pointer?): SixModelObject? {
        val repr_data = st.REPRData as CArrayREPRData

        if (ptr == null)
            return repr_data.elem_type

        when (repr_data.elem_kind) {
            ElemKind.STRING ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.UTF8STR, repr_data.elem_type, ptr.getString(0))
            ElemKind.CARRAY ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CARRAY, repr_data.elem_type, ptr)
            ElemKind.CPOINTER ->
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPOINTER, repr_data.elem_type, ptr)
            ElemKind.CSTRUCT -> {
                val structClass = (repr_data.elem_type!!.st.REPRData as CStructREPRData).structureClass!!
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CSTRUCT, repr_data.elem_type, Structure.newInstance(structClass.asSubclass(Structure::class.java), ptr))
            }
            ElemKind.CPPSTRUCT -> {
                val structClass = (repr_data.elem_type!!.st.REPRData as CPPStructREPRData).structureClass!!
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CPPSTRUCT, repr_data.elem_type, Structure.newInstance(structClass.asSubclass(Structure::class.java), ptr))
            }
            ElemKind.CUNION -> {
                val structClass = (repr_data.elem_type!!.st.REPRData as CUnionREPRData).structureClass!!
                return NativeCallOps.toNQPType(tc, NativeCall.ArgType.CUNION, repr_data.elem_type, Union.newInstance(structClass.asSubclass(Union::class.java), ptr))
            }
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

        if (repr_data.elem_kind == ElemKind.CARRAY
         || repr_data.elem_kind == ElemKind.CSTRUCT
         || repr_data.elem_kind == ElemKind.CPPSTRUCT
         || repr_data.elem_kind == ElemKind.CUNION) {
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
