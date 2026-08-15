package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class VMIter : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = VMIterInstance()
        obj.st = st
        return obj
    }

    override fun get_value_storage_spec(tc: ThreadContext, st: STable): StorageSpec =
        StorageSpec()

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "VMIter does not participate in serialization")
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc, "VMIter does not participate in serialization")
    }
}
