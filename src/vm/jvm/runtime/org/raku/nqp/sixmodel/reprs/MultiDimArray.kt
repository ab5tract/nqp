package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.Inlining
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class MultiDimArray : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val rd = st.REPRData as MultiDimArrayREPRData?
        if (rd != null) {
            val ss = rd.ss
            val obj: MultiDimArrayInstanceBase = if (ss != null) {
                when (ss.boxedPrimitive) {
                    BoxedPrimitive.INT -> when (ss.bits.toInt()) {
                        64 -> MultiDimArrayInstance_i()
                        8 -> MultiDimArrayInstance_i8()
                        16 -> MultiDimArrayInstance_i16()
                        32 -> MultiDimArrayInstance_i32()
                        else -> MultiDimArrayInstance_i()
                    }
                    BoxedPrimitive.UINT -> when (ss.bits.toInt()) {
                        64 -> MultiDimArrayInstance_i()
                        8 -> MultiDimArrayInstance_u8()
                        16 -> MultiDimArrayInstance_u16()
                        32 -> MultiDimArrayInstance_u32()
                        else -> MultiDimArrayInstance_i()
                    }
                    BoxedPrimitive.NUM -> MultiDimArrayInstance_n()
                    BoxedPrimitive.STR -> MultiDimArrayInstance_s()
                    else -> MultiDimArrayInstance()
                }
            }
            else {
                MultiDimArrayInstance()
            }
            obj.dimensions = LongArray(rd.numDimensions)
            obj.st = st
            return obj
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "Cannot allocate a multi-dim array type before it is composed")
        }
    }

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        val arrayInfo = reprInfo.at_key_boxed(tc, "array")
        if (Ops.isnull(arrayInfo) == 0L) {
            val reprData = MultiDimArrayREPRData()
            val dims = arrayInfo!!.at_key_boxed(tc, "dimensions")
            if (Ops.isnull(dims) == 1L)
                throw ExceptionHandling.dieInternal(tc,
                    "MultiDimArray REPR must be composed with a number of dimensions")
            val dimensions = dims!!.get_int(tc).toInt()
            if (dimensions < 1)
                throw ExceptionHandling.dieInternal(tc,
                    "MultiDimArray REPR must be composed with at least 1 dimension")
            reprData.numDimensions = dimensions
            val type = arrayInfo.at_key_boxed(tc, "type")
            val ss = if (Ops.isnull(type) == 0L) type!!.st.REPR.get_storage_spec(tc, type.st) else null
            when (ss?.boxedPrimitive ?: Inlining.REFERENCE) {
                BoxedPrimitive.INT, BoxedPrimitive.UINT, BoxedPrimitive.NUM, BoxedPrimitive.STR -> {
                    reprData.type = type
                    reprData.ss = ss
                }
                else ->
                    if (ss != null && ss.inlining != Inlining.REFERENCE)
                        throw ExceptionHandling.dieInternal(tc, "MultiDimArray can only store native int/num/str or reference types")
            }
            st.REPRData = reprData
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "MultiDimArray REPR must be composed with array information")
        }
    }

    override fun get_value_storage_spec(tc: ThreadContext, st: STable): StorageSpec? =
        if (st.REPRData == null) StorageSpec.BOXED else (st.REPRData as MultiDimArrayREPRData).ss

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val rd = st.REPRData as MultiDimArrayREPRData?
        var obj: MultiDimArrayInstanceBase? = null
        val ss = rd?.ss
        if (ss != null) {
            obj = when (ss.boxedPrimitive) {
                BoxedPrimitive.INT -> when (ss.bits.toInt()) {
                    64 -> MultiDimArrayInstance_i()
                    8 -> MultiDimArrayInstance_i8()
                    16 -> MultiDimArrayInstance_i16()
                    32 -> MultiDimArrayInstance_i32()
                    else -> MultiDimArrayInstance_i()
                }
                BoxedPrimitive.UINT -> when (ss.bits.toInt()) {
                    64 -> MultiDimArrayInstance_i()
                    8 -> MultiDimArrayInstance_u8()
                    16 -> MultiDimArrayInstance_u16()
                    32 -> MultiDimArrayInstance_u32()
                    else -> MultiDimArrayInstance_i()
                }
                BoxedPrimitive.NUM -> MultiDimArrayInstance_n()
                BoxedPrimitive.STR -> MultiDimArrayInstance_s()
                else -> null
            }
        }
        if (Ops.isnull(obj) == 1L)
            obj = MultiDimArrayInstance()
        obj!!.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val mda = obj as MultiDimArrayInstanceBase
        val rd = st.REPRData as MultiDimArrayREPRData
        val dimensions = LongArray(rd.numDimensions)
        for (i in dimensions.indices)
            dimensions[i] = reader.readLong()
        mda.dimensions = dimensions
        mda.deserializeValues(tc, reader)
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val mda = obj as MultiDimArrayInstanceBase
        for (i in mda.dimensions.indices)
            writer.writeInt(mda.dimensions[i])
        mda.serializeValues(tc, writer)
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        val rd = st.REPRData as MultiDimArrayREPRData?
        if (rd != null) {
            writer.writeInt(rd.numDimensions.toLong())
            writer.writeRef(rd.type)
        }
        else {
            writer.writeInt(0)
        }
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        val dims = reader.readLong().toInt()
        if (dims > 0) {
            val reprData = MultiDimArrayREPRData()
            reprData.numDimensions = dims
            val type = reader.readRef()
            if (Ops.isnull(type) == 0L) {
                reprData.type = type
                reprData.ss = type!!.st.REPR.get_storage_spec(tc, type.st)
            }
            st.REPRData = reprData
        }
    }
}
