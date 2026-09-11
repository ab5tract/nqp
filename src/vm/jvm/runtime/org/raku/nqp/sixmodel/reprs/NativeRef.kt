package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class NativeRef : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val rd = st.REPRData as NativeRefREPRData?
            ?: throw ExceptionHandling.dieInternal(tc,
                "Cannot allocate NativeRef type that is not yet composed")
        val obj: SixModelObject = when (rd.refKind) {
            RefKind.LEXICAL -> when (rd.primitiveType) {
                BoxedPrimitive.INT, BoxedPrimitive.UINT -> NativeRefInstanceIntLex()
                BoxedPrimitive.NUM -> NativeRefInstanceNumLex()
                BoxedPrimitive.STR -> NativeRefInstanceStrLex()
                else -> throw ExceptionHandling.dieInternal(tc,
                    "Unknown primtive type in native ref allocation")
            }
            RefKind.ATTRIBUTE -> NativeRefInstanceAttribute()
            RefKind.POSITIONAL -> NativeRefInstancePositional()
            RefKind.MULTIDIM -> NativeRefInstanceMultidim()
            else -> throw ExceptionHandling.dieInternal(tc,
                "Unknown reference kind in native ref allocation")
        }
        obj.st = st
        return obj
    }

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        val info = reprInfo.at_key_boxed(tc, "nativeref")
        if (Ops.isnull(info) == 0L) {
            val type = info!!.at_key_boxed(tc, "type")
            val prim = type!!.st.REPR.get_storage_spec(tc, type.st).boxedPrimitive
            if (prim != BoxedPrimitive.NONE) {
                val refkind = info.at_key_boxed(tc, "refkind")
                if (Ops.isnull(refkind) == 0L) {
                    val kind = RefKind.named(refkind!!.get_str(tc))
                        ?: throw ExceptionHandling.dieInternal(tc,
                            "NativeRef: invalid refkind in compose")
                    st.REPRData = NativeRefREPRData(prim, kind)
                }
                else {
                    throw ExceptionHandling.dieInternal(tc,
                        "NativeRef: missing refkind in compose")
                }
            }
            else {
                throw ExceptionHandling.dieInternal(tc,
                    "NativeRef: non-native type supplied in compose")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "NativeRef: missing nativeref protocol in compose")
        }
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "Cannot deserialize a native reference")
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc, "Cannot deserialize a native reference")
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        val rd = st.REPRData as NativeRefREPRData?
        if (rd != null) {
            writer.writeInt32(rd.primitiveType.spec)
            writer.writeInt32(rd.refKind.spec)
        }
        else {
            writer.writeInt32(0)
            writer.writeInt32(0)
        }
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        val primitive = BoxedPrimitive.ofSpec(reader.readInt32())
        val kind = RefKind.ofSpec(reader.readInt32())
        st.REPRData = NativeRefREPRData(primitive, kind)
    }
}
