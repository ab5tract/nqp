package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.Boxable
import org.raku.nqp.sixmodel.BoxedPrimitive
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
        return st.WHAT
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
        val meth = Ops.findmethodNonFatal(st.WHAT, "of", tc)
        if (Ops.isnull(meth) == 1L)
            ExceptionHandling.dieInternal(tc, "CArray representation expects an 'of' method, specifying the element type")
        Ops.invokeDirect(tc, meth, CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null), arrayOf<Any?>(st.WHAT))
        val elemType = Ops.decont(Ops.result_o(tc.resultFrame()), tc)
        if (Ops.isnull(elemType) == 1L)
            ExceptionHandling.dieInternal(tc, "CArray representation expects a non-null return value from the 'of' method, specifying the element type")

        val ss = elemType!!.st.REPR.get_storage_spec(tc, elemType.st)
        val pointerBytes = NativeSupport.POINTER_SIZE.toLong()
        val kind: ElemKind
        val bytes: Long

        if (ss.boxedPrimitive == BoxedPrimitive.INT || ss.boxedPrimitive == BoxedPrimitive.UINT) {
            if (ss.bits.toInt() !in intArrayOf(8, 16, 32, 64))
                ExceptionHandling.dieInternal(tc, "CArray can only handle 8, 16, 32 and 64 bit ints.")
            kind = ElemKind.INTEGER
            bytes = ss.bits / 8L
        }
        else if (ss.boxedPrimitive == BoxedPrimitive.NUM) {
            if (ss.bits.toInt() !in intArrayOf(32, 64))
                ExceptionHandling.dieInternal(tc, "CArray can only handle 32 and 64 bit floats.")
            kind = ElemKind.NUMERIC
            bytes = ss.bits / 8L
        }
        else {
            kind = when {
                Boxable.STR in ss.canBox    -> ElemKind.STRING
                elemType.st.REPR is CPointer   -> ElemKind.CPOINTER
                elemType.st.REPR is CArray     -> ElemKind.CARRAY
                elemType.st.REPR is CStruct    -> ElemKind.CSTRUCT
                elemType.st.REPR is CPPStruct  -> ElemKind.CPPSTRUCT
                elemType.st.REPR is CUnion     -> ElemKind.CUNION
                /* TODO: Remaining cases. */
                else -> throw ExceptionHandling.dieInternal(tc,
                    "CArray only handles ints, nums, strings, CArrays, CPointers, CStructs, CPPStructs and CUnions so far.")
            }
            bytes = pointerBytes
        }

        st.REPRData = CArrayREPRData(elemType, kind, ss.bits, bytes)
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
