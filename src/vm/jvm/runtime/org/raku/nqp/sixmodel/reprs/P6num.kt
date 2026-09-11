package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6num : REPR() {
    companion object {
        /* Possible C types we can handle. */
        const val P6NUM_C_TYPE_FLOAT: Byte = -1
        const val P6NUM_C_TYPE_DOUBLE: Byte = -2
        const val P6NUM_C_TYPE_LONGDOUBLE: Byte = -3

        /** Round a value to a sized-num storage width, the way MoarVM's
         * num32 registers store. Full-width and unsized specs pass through. */
        @JvmStatic
        fun sizedValue(ss: StorageSpec?, value: Double): Double =
            if (ss?.bits?.toInt() == 32) value.toFloat().toDouble() else value
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        st.REPRData = StorageSpec.number(64)
        return st.WHAT
    }

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        val floatInfo = reprInfo.at_key_boxed(tc, "float")
        if (Ops.isnull(floatInfo) == 0L) {
            val bits = floatInfo!!.at_key_boxed(tc, "bits")
            if (Ops.isnull(bits) == 0L) {
                val bitwidth = bits!!.get_int(tc).toShort()
                val width = when (bitwidth.toInt()) {
                    P6NUM_C_TYPE_FLOAT.toInt() -> java.lang.Float.SIZE.toShort()
                    P6NUM_C_TYPE_DOUBLE.toInt() -> java.lang.Double.SIZE.toShort()
                    /* There is no LongDouble in Java */
                    P6NUM_C_TYPE_LONGDOUBLE.toInt() -> java.lang.Double.SIZE.toShort()
                    else -> bitwidth
                }
                st.REPRData = (st.REPRData as StorageSpec).copy(bits = width)
            }
        }
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6numInstance()
        obj.st = st
        obj.value = Double.NaN
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec =
        st.REPRData as StorageSpec

    override fun inlinedKind(): SlotKind = SlotKind.NUM

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6numInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6numInstance).value = reader.readDouble()
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        writer.writeNum((obj as P6numInstance).value)
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        writer.writeInt((st.REPRData as StorageSpec).bits.toLong())
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        st.REPRData = StorageSpec.number(
            if (reader.version >= 7) reader.readLong().toShort() else 64)
    }
}
