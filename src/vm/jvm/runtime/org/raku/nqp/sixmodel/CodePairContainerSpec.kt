package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

/**
 * A code_pair container uses a pair of methods (fetch/store) to provide the
 * container semantics.
 */
open class CodePairContainerSpec : ContainerSpec() {
    @JvmField var fetchCode: SixModelObject? = null
    @JvmField var storeCode: SixModelObject? = null

    /* Fetches a value out of a container. Used for decontainerization. */
    override fun fetch(tc: ThreadContext, cont: SixModelObject): SixModelObject? {
        Ops.invokeDirect(tc, fetchCode, Ops.invocantCallSite, arrayOf<Any>(cont))
        return Ops.result_o(tc.curFrame)
    }

    override fun fetch_i(tc: ThreadContext, cont: SixModelObject): Long {
        Ops.invokeDirect(tc, fetchCode, Ops.invocantCallSite, arrayOf<Any>(cont))
        return Ops.result_i(tc.curFrame)
    }

    override fun fetch_n(tc: ThreadContext, cont: SixModelObject): Double {
        Ops.invokeDirect(tc, fetchCode, Ops.invocantCallSite, arrayOf<Any>(cont))
        return Ops.result_n(tc.curFrame)
    }

    override fun fetch_s(tc: ThreadContext, cont: SixModelObject): String? {
        Ops.invokeDirect(tc, fetchCode, Ops.invocantCallSite, arrayOf<Any>(cont))
        return Ops.result_s(tc.curFrame)
    }

    /* Stores a value in a container. Used for assignment. */
    override fun store(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject) {
        Ops.invokeDirect(tc, storeCode, Ops.storeCallSite, arrayOf<Any>(cont, obj))
    }

    override fun store_i(tc: ThreadContext, cont: SixModelObject, value: Long) {
        Ops.invokeDirect(tc, storeCode, Ops.storeCallSite_i, arrayOf<Any>(cont, value))
    }

    override fun store_n(tc: ThreadContext, cont: SixModelObject, value: Double) {
        Ops.invokeDirect(tc, storeCode, Ops.storeCallSite_n, arrayOf<Any>(cont, value))
    }

    override fun store_s(tc: ThreadContext, cont: SixModelObject, value: String?) {
        Ops.invokeDirect(tc, storeCode, Ops.storeCallSite_s, arrayOf<Any?>(cont, value))
    }

    /* Stores a value in a container, without any checking of it (this
     * assumes an optimizer or something else already did it). Used for
     * assignment. */
    override fun storeUnchecked(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject) {
        store(tc, cont, obj)
    }

    /* Name of this container specification. */
    override fun name(): String = "code_pair"

    /* Serializes the container data, if any. */
    override fun serialize(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        writer.writeRef(fetchCode)
        writer.writeRef(storeCode)
    }

    /* Deserializes the container data, if any. */
    override fun deserialize(tc: ThreadContext, st: STable, reader: SerializationReader) {
        fetchCode = reader.readRef()
        storeCode = reader.readRef()
    }
}
