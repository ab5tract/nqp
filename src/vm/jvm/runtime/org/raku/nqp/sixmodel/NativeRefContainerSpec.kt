package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.reprs.NativeRefInstance
import org.raku.nqp.sixmodel.reprs.NativeRefREPRData

open class NativeRefContainerSpec : ContainerSpec() {
    /* Fetches a value out of a container. Used for decontainerization. */
    override fun fetch(tc: ThreadContext, cont: SixModelObject): SixModelObject {
        val rd = cont.st.REPRData as NativeRefREPRData
        var hll = cont.st.hllOwner
        if (hll == null)
            hll = tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig
        return when (rd.primitive_type) {
            StorageSpec.BP_INT, StorageSpec.BP_UINT ->
                Ops.box_i(fetch_i(tc, cont), hll.intBoxType, tc)
            StorageSpec.BP_NUM ->
                Ops.box_n(fetch_n(tc, cont), hll.numBoxType, tc)
            StorageSpec.BP_STR ->
                Ops.box_s(fetch_s(tc, cont), hll.strBoxType, tc)
            else -> throw ExceptionHandling.dieInternal(tc,
                "Unknown native reference primitive type")
        }
    }

    override fun fetch_i(tc: ThreadContext, cont: SixModelObject): Long =
        (cont as NativeRefInstance).fetch_i(tc)

    override fun fetch_n(tc: ThreadContext, cont: SixModelObject): Double =
        (cont as NativeRefInstance).fetch_n(tc)

    override fun fetch_s(tc: ThreadContext, cont: SixModelObject): String? =
        (cont as NativeRefInstance).fetch_s(tc)

    /* Stores a value in a container. Used for assignment. */
    override fun store(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject) {
        val rd = cont.st.REPRData as NativeRefREPRData
        when (rd.primitive_type) {
            StorageSpec.BP_INT, StorageSpec.BP_UINT -> store_i(tc, cont, obj.get_int(tc))
            StorageSpec.BP_NUM -> store_n(tc, cont, obj.get_num(tc))
            StorageSpec.BP_STR -> store_s(tc, cont, obj.get_str(tc))
            else -> throw ExceptionHandling.dieInternal(tc,
                "Unknown native reference primitive type")
        }
    }

    override fun store_i(tc: ThreadContext, cont: SixModelObject, value: Long) {
        (cont as NativeRefInstance).store_i(tc, value)
    }

    override fun store_n(tc: ThreadContext, cont: SixModelObject, value: Double) {
        (cont as NativeRefInstance).store_n(tc, value)
    }

    override fun store_s(tc: ThreadContext, cont: SixModelObject, value: String?) {
        (cont as NativeRefInstance).store_s(tc, value)
    }

    /* Stores a value in a container, without any checking of it (this
     * assumes an optimizer or something else already did it). Used for
     * assignment. */
    override fun storeUnchecked(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject) {
        store(tc, cont, obj)
    }

    /* Name of this container specification. */
    override fun name(): String = "native_ref"

    /* Serializes the container data, if any. */
    override fun serialize(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        /* Nothing to do. */
    }

    /* Deserializes the container data, if any. */
    override fun deserialize(tc: ThreadContext, st: STable, reader: SerializationReader) {
        /* Nothing to do. */
    }
}
