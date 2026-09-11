package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.Inlining
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class NativeCall : REPR() {
    /* The available native call argument types. */
    enum class ArgType {
        VOID,
        CHAR,
        SHORT,
        INT,
        LONG,
        LONGLONG,
        FLOAT,
        DOUBLE,
        ASCIISTR,
        UTF8STR,
        UTF16STR,
        CSTRUCT,
        CPPSTRUCT,
        CUNION,
        CARRAY,
        CALLBACK,
        CPOINTER,
        VMARRAY,
        UCHAR,
        USHORT,
        UINT,
        ULONG,
        ULONGLONG,
        CHAR_RW,
        SHORT_RW,
        INT_RW,
        LONG_RW,
        LONGLONG_RW,
        FLOAT_RW,
        DOUBLE_RW,
        UCHAR_RW,
        USHORT_RW,
        UINT_RW,
        ULONG_RW,
        ULONGLONG_RW,
        CPOINTER_RW,
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = null /* No REPR data needed. */
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = NativeCallInstance()
        obj.st = st
        obj.body = NativeCallBody()
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        return StorageSpec(inlining = Inlining.INLINED, bits = 64)
    }

    override fun inlinedKind(): SlotKind = SlotKind.NCBODY

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        /* Assume it'll be re-configured each time, so just allow it. */
        val stub = NativeCallInstance()
        stub.st = st
        return stub
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        /* Assume it'll be re-configured each time, so just allow it. */
    }
}
