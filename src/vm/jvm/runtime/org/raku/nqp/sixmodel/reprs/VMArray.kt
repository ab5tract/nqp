package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class VMArray : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj: SixModelObject
        if (st.REPRData == null) {
            obj = VMArrayInstance()
        }
        else {
            val ss = (st.REPRData as VMArrayREPRData).ss!!
            obj = when (ss.boxed_primitive) {
                StorageSpec.BP_INT, StorageSpec.BP_UINT -> when (ss.bits.toInt()) {
                    64 -> VMArrayInstance_i()
                    8 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i8() else VMArrayInstance_u8()
                    16 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i16() else VMArrayInstance_u16()
                    32 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i32() else VMArrayInstance_u32()
                    else -> VMArrayInstance_i()
                }
                StorageSpec.BP_NUM -> VMArrayInstance_n()
                StorageSpec.BP_STR -> VMArrayInstance_s()
                else -> throw ExceptionHandling.dieInternal(tc, "Invalid REPR data for VMArray in allocate")
            }
        }
        obj.st = st
        return obj
    }

    override fun compose(tc: ThreadContext, st: STable, repr_info: SixModelObject) {
        val arrayInfo = repr_info.at_key_boxed(tc, "array")
        if (Ops.isnull(arrayInfo) == 0L) {
            val type = arrayInfo.at_key_boxed(tc, "type")
            val ss = type.st.REPR.get_storage_spec(tc, type.st)
            when (ss.boxed_primitive) {
                StorageSpec.BP_INT, StorageSpec.BP_UINT, StorageSpec.BP_NUM, StorageSpec.BP_STR -> {
                    val reprData = VMArrayREPRData()
                    reprData.type = type
                    reprData.ss = ss
                    st.REPRData = reprData
                }
                else ->
                    if (ss.inlineable != StorageSpec.REFERENCE)
                        throw ExceptionHandling.dieInternal(tc, "VMArray can only store native int/num/str or reference types")
            }
        }
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj: SixModelObject
        if (st.REPRData == null) {
            // Either a real VMArray or REPRData not yet known.
            obj = when (st.REPR.subtype_name) {
                "VMArray" -> VMArrayInstance()
                "VMArray_i8" -> VMArrayInstance_i8()
                "VMArray_u8" -> VMArrayInstance_u8()
                "VMArray_i16" -> VMArrayInstance_i16()
                "VMArray_u16" -> VMArrayInstance_u16()
                "VMArray_i32" -> VMArrayInstance_i32()
                "VMArray_u32" -> VMArrayInstance_u32()
                "VMArray_i" -> VMArrayInstance_i()
                "VMArray_n" -> VMArrayInstance_n()
                "VMArray_s" -> VMArrayInstance_s()
                else -> throw ExceptionHandling.dieInternal(tc, "Invalid REPR name for VMArray")
            }
        }
        else {
            val ss = (st.REPRData as VMArrayREPRData).ss!!
            obj = when (ss.boxed_primitive) {
                StorageSpec.BP_INT, StorageSpec.BP_UINT -> when (ss.bits.toInt()) {
                    8 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i8() else VMArrayInstance_u8()
                    16 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i16() else VMArrayInstance_u16()
                    32 -> if (ss.is_unsigned.toInt() == 0) VMArrayInstance_i32() else VMArrayInstance_u32()
                    else -> VMArrayInstance_i()
                }
                StorageSpec.BP_NUM -> VMArrayInstance_n()
                StorageSpec.BP_STR -> VMArrayInstance_s()
                else -> throw ExceptionHandling.dieInternal(tc, "Invalid REPR data for VMArray in deserialize_stub")
            }
        }
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val elems = reader.readInt32()
        obj.set_elems(tc, elems.toLong())
        if (st.REPRData == null) {
            for (i in 0 until elems)
                obj.bind_pos_boxed(tc, i.toLong(), reader.readRef())
        }
        else {
            val boxPrim = (st.REPRData as VMArrayREPRData).ss!!.boxed_primitive
            for (i in 0 until elems.toLong()) {
                when (boxPrim) {
                    StorageSpec.BP_INT, StorageSpec.BP_UINT -> tc.native_i = reader.readLong()
                    StorageSpec.BP_NUM -> tc.native_n = reader.readDouble()
                    StorageSpec.BP_STR -> tc.native_s = reader.readStr()
                    else -> throw ExceptionHandling.dieInternal(tc, "Invalid REPR data for VMArray in deserialize_finish")
                }
                obj.bind_pos_native(tc, i)
            }
        }
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val elems = obj.elems(tc).toInt()
        writer.writeInt32(elems)
        if (obj.st.REPRData == null) {
            for (i in 0 until elems.toLong())
                writer.writeRef(obj.at_pos_boxed(tc, i))
        }
        else {
            val boxPrim = (obj.st.REPRData as VMArrayREPRData).ss!!.boxed_primitive
            for (i in 0 until elems.toLong()) {
                obj.at_pos_native(tc, i)
                when (boxPrim) {
                    StorageSpec.BP_INT, StorageSpec.BP_UINT -> writer.writeInt(tc.native_i)
                    StorageSpec.BP_NUM -> writer.writeNum(tc.native_n)
                    StorageSpec.BP_STR -> writer.writeStr(tc.native_s)
                    else -> throw ExceptionHandling.dieInternal(tc, "Invalid REPR data for VMArray in serialize")
                }
            }
        }
    }

    override fun get_value_storage_spec(tc: ThreadContext, st: STable): StorageSpec? =
        if (st.REPRData == null) StorageSpec.BOXED else (st.REPRData as VMArrayREPRData).ss

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        writer.writeRef(if (st.REPRData == null) null else (st.REPRData as VMArrayREPRData).type)
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        if (reader.version >= 7) {
            val type = reader.readRef()
            if (Ops.isnull(type) == 0L) {
                val reprData = VMArrayREPRData()
                reprData.type = type
                reprData.ss = type!!.st.REPR.get_storage_spec(tc, type.st)
                st.REPRData = reprData
            }
        }
    }
}
