package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.locks.ReentrantLock

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class ReentrantMutex : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = ReentrantMutexInstance()
        obj.st = st
        obj.lock = ReentrantLock()
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject =
        allocate(tc, st)

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        /* Already did it all in deserialize_stub. */
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        /* Nothing to do, we just re-create the lock on deserialization. */
    }
}
