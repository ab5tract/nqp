package org.raku.nqp.sixmodel.reprs

import com.sun.jna.Native

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.CArrayREPRData.ElemKind

class CArray : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = null /* No REPR data yet. */
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = CArrayInstance()
        obj.managed = true
        if (st.REPRData == null)
            fillREPRData(tc, st)
        obj.st = st
        return obj
    }

    private fun fillREPRData(tc: ThreadContext, st: STable) {
        val data = CArrayREPRData()
        val meth = Ops.findmethodNonFatal(st.WHAT, "of", tc)
        if (Ops.isnull(meth) == 1L)
            ExceptionHandling.dieInternal(tc, "CArray representation expects an 'of' method, specifying the element type")
        Ops.invokeDirect(tc, meth, CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null), arrayOf<Any?>(st.WHAT!!))
        data.elem_type = Ops.decont(Ops.result_o(tc.resultFrame()), tc)
        if (Ops.isnull(data.elem_type) == 1L)
            ExceptionHandling.dieInternal(tc, "CArray representation expects a non-null return value from the 'of' method, specifying the element type")
        val elemType = data.elem_type!!
        val ss = elemType.st.REPR.get_storage_spec(tc, elemType.st)
        data.elem_size = ss.bits
        if (ss.boxed_primitive == StorageSpec.BP_INT || ss.boxed_primitive == StorageSpec.BP_UINT) {
            when (ss.bits.toInt()) {
                8 -> data.jna_size = Native.getNativeSize(Byte::class.javaObjectType)
                16 -> data.jna_size = Native.getNativeSize(Short::class.javaObjectType)
                32 -> data.jna_size = Native.getNativeSize(Integer::class.java)
                64 -> data.jna_size = Native.getNativeSize(Long::class.javaObjectType)
                else -> ExceptionHandling.dieInternal(tc, "CArray can only handle 8, 16, 32 and 64 bit ints.")
            }
            data.elem_kind = ElemKind.INTEGER
        }
        else if (ss.boxed_primitive == StorageSpec.BP_NUM) {
            when (ss.bits.toInt()) {
                32 -> data.jna_size = Native.getNativeSize(Float::class.javaObjectType)
                64 -> data.jna_size = Native.getNativeSize(Double::class.javaObjectType)
                else -> ExceptionHandling.dieInternal(tc, "CArray can only handle 32 and 64 bit floats.")
            }
            data.elem_kind = ElemKind.NUMERIC
        }
        else if ((ss.can_box.toInt() and StorageSpec.CAN_BOX_STR.toInt()) != 0) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.STRING
        }
        else if (elemType.st.REPR is CPointer) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.CPOINTER
        }
        else if (elemType.st.REPR is CArray) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.CARRAY
        }
        else if (elemType.st.REPR is CStruct) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.CSTRUCT
        }
        else if (elemType.st.REPR is CPPStruct) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.CPPSTRUCT
        }
        else if (elemType.st.REPR is CUnion) {
            data.jna_size = Native.POINTER_SIZE
            data.elem_kind = ElemKind.CUNION
        }
        else {
            /* TODO: Remaining cases. */
            ExceptionHandling.dieInternal(tc, "CArray only handles ints, nums, strings, CArrays, CPointers, CStructs, CPPStructs and CUnions so far.")
        }
        st.REPRData = data
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject? {
        /* This REPR can't be serialized. */
        ExceptionHandling.dieInternal(tc, "Can't deserialize_stub a CArray object.")

        return null
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        ExceptionHandling.dieInternal(tc, "Can't deserialize_finish a CArray object.")
    }
}
