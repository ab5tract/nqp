package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6str : REPR() {
    private companion object {
        val ss = StorageSpec.string()
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6strInstance()
        obj.st = st
        obj.value = ""
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec = ss

    override fun inlinedKind(): SlotKind = SlotKind.STR

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6strInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6strInstance).value = reader.readStr()
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        writer.writeStr((obj as P6strInstance).value)
    }
}
