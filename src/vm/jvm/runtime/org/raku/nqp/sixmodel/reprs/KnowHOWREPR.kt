package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class KnowHOWREPR : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = KnowHOWREPRInstance()
        obj.st = st
        obj.name = "<anon>"
        obj.attributes = ArrayList()
        obj.methods = HashMap()
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = KnowHOWREPRInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val body = obj as KnowHOWREPRInstance
        body.name = reader.readStr()

        val attributes = ArrayList<SixModelObject>()
        val attrs = reader.readRef()
        val elems = attrs.elems(tc)
        for (i in 0 until elems)
            attributes.add(attrs.at_pos_boxed(tc, i))
        body.attributes = attributes

        body.methods = (reader.readRef() as VMHashInstance).storage
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val kh = obj as KnowHOWREPRInstance
        writer.writeStr(kh.name)
        writer.writeList(kh.attributes!!)
        writer.writeHash(kh.methods!!)
    }
}
