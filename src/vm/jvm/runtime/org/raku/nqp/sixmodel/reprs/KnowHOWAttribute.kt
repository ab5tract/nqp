package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class KnowHOWAttribute : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = KnowHOWAttributeInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = KnowHOWAttributeInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val data = obj as KnowHOWAttributeInstance
        data.name = reader.readStr()
        data.type = tc.gc.KnowHOW // Not serialized yet
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val data = obj as KnowHOWAttributeInstance
        writer.writeStr(data.name)
    }
}
