package org.raku.nqp.sixmodel.reprs

import java.math.BigInteger

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6bigint : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6bigintInstance()
        obj.st = st
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        return StorageSpec.integer(64)
    }

    override fun inlinedKind(): SlotKind = SlotKind.BIGINT

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6bigintInstance()
        // "error: BigInteger(long) has private access in BigInteger"
        obj.value = BigInteger("0")
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6bigintInstance).value = BigInteger(reader.readStr())
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        /* Write out as String. */
        writer.writeStr((obj as P6bigintInstance).value.toString())
    }

    companion object {
        /* The unsigned reading of a native long: 0..2^64-1. */
        @JvmStatic
        fun unsignedValueOf(v: Long): BigInteger =
            if (v >= 0L) BigInteger.valueOf(v) else BigInteger.valueOf(v).add(BigInteger.ONE.shiftLeft(64))

        /** The unsigned reading of a flattened bigint: 64 bits is a value
         * an unsigned native holds, so only wider than that is refused. */
        @JvmStatic
        fun uncheckedLongValue(value: java.math.BigInteger): Long {
            if (value.bitLength() > 64)
                throw RuntimeException("Cannot unbox " + value.bitLength() +
                    " bit wide bigint into native integer")
            return value.toLong()
        }

        /** The signed reading, refusing what a signed native cannot hold,
         * the same way the standalone instance does. */
        @JvmStatic
        fun checkedLongValue(value: java.math.BigInteger): Long {
            if (value.bitLength() >= 64 && value != P6bigintInstance.SMALLEST_UNBOXABLE)
                throw RuntimeException("Cannot unbox " + value.bitLength() +
                    " bit wide bigint into native integer")
            return value.toLong()
        }
    }
}
