package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class VMHash : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = VMHashInstance()
        obj.st = st
        obj.storage = HashMap()
        return obj
    }

    override fun get_value_storage_spec(tc: ThreadContext, st: STable): StorageSpec =
        StorageSpec()

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = VMHashInstance()
        obj.st = st
        obj.storage = HashMap()
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val storage = (obj as VMHashInstance).storage
        val elems = reader.readInt32()
        for (i in 0 until elems) {
            val key = reader.readStr()
            val value = reader.readRef()
            storage[key!!] = value
        }
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val storage = (obj as VMHashInstance).storage

        /* Write out element count. */
        writer.writeInt32(storage.size)

        /* Write elements, as key,value,key,value etc. */
        for (key in storage.keys) {
            writer.writeStr(key)
            writer.writeRef(storage[key])
        }
    }
}
