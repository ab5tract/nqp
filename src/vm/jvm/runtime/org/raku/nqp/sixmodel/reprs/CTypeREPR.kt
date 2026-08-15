package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

/**
 * Shared machinery for the three C aggregate representations: the field
 * offsets, size and alignment of the type, worked out the way a C compiler
 * would. Struct and union differ only in whether members follow one another
 * or all start at the front, so one implementation covers all three.
 */
abstract class CTypeREPR(
    private val kind: String,
    private val isUnion: Boolean,
    private val requireAttributes: Boolean,
) : REPR() {
    protected abstract fun newREPRData(): CTypeREPRData
    protected abstract fun newInstance(): CTypeInstance

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun compose(tc: ThreadContext, st: STable, repr_info: SixModelObject) {
        val attr_info = repr_info.at_key_boxed(tc, "attribute")!!
        val repr_data = newREPRData()

        val mroLength = attr_info.elems(tc)
        val attrInfos = ArrayList<CAttrInfo>()
        for (i in mroLength - 1 downTo 0) {
            val entry = attr_info.at_pos_boxed(tc, i)!!
            val attrs = entry.at_pos_boxed(tc, 1)!!
            val parents = entry.at_pos_boxed(tc, 2)!!.elems(tc)

            if (parents <= 1) {
                val numAttrs = attrs.elems(tc)
                for (j in 0 until numAttrs) {
                    val attrHash = attrs.at_pos_boxed(tc, j)!!
                    val info = CAttrInfo()
                    info.name = attrHash.at_key_boxed(tc, "name")!!.get_str(tc)
                    info.type = attrHash.at_key_boxed(tc, "type")
                    info.inlined = attrHash.at_key_boxed(tc, "inlined")!!.get_int(tc).toShort()
                    val spec = info.type!!.st.REPR.get_storage_spec(tc, info.type!!.st)
                    info.bits = spec.bits
                    repr_data.fieldTypes.put(info.name!!, info)

                    if (info.type == null) {
                        ExceptionHandling.dieInternal(tc, "$kind representation requires the types of all attributes to be specified")
                    }

                    attrInfos.add(info)
                }
            }
            else {
                ExceptionHandling.dieInternal(tc, "$kind representation does not support multiple inheritance")
            }
        }

        st.REPRData = repr_data
        computeLayout(tc, st, attrInfos)
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        /* TODO: Die if someone tries to allocate one of these before it's
         * been composed. */
        val obj = newInstance()
        val repr_data = st.REPRData as CTypeREPRData
        obj.st = st
        obj.storage = NativeSupport.allocate(repr_data.size)
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject? {
        /* This REPR can't be serialized. */
        ExceptionHandling.dieInternal(tc, "Can't deserialize_stub a $kind object.")

        return null
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        ExceptionHandling.dieInternal(tc, "Can't deserialize_finish a $kind object.")
    }

    private fun computeLayout(tc: ThreadContext, st: STable, attrs: List<CAttrInfo>) {
        val repr_data = st.REPRData as CTypeREPRData

        if (requireAttributes && attrs.isEmpty()) {
            ExceptionHandling.dieInternal(tc,
                "Class " + Ops.typeName(st.WHAT, tc) + " has no attributes, which is illegal with the $kind representation.")
        }

        var offset = 0L
        var size = 0L
        var alignment = 1L

        for (info in attrs) {
            measure(tc, info)

            if (info.alignment > alignment)
                alignment = info.alignment

            if (isUnion) {
                info.offset = 0
                if (info.size > size)
                    size = info.size
            }
            else {
                offset = roundUp(offset, info.alignment)
                info.offset = offset
                offset += info.size
                size = offset
            }
        }

        repr_data.attrs = attrs
        repr_data.alignment = alignment
        repr_data.size = roundUp(size, alignment)
    }

    private fun roundUp(value: Long, alignment: Long): Long =
        if (alignment <= 1) value else (value + alignment - 1) / alignment * alignment

    /* Works out one attribute's C type, size and alignment, mirroring the
     * Java field type the ASM generators used to emit for it. */
    private fun measure(tc: ThreadContext, info: CAttrInfo) {
        val repr = info.type!!.st.REPR
        val spec = repr.get_storage_spec(tc, info.type!!.st)
        info.bits = spec.bits

        if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_INT) {
            info.argType = when (spec.bits.toInt()) {
                8 -> ArgType.CHAR
                16 -> ArgType.SHORT
                32 -> ArgType.INT
                64 -> ArgType.LONG
                else -> {
                    ExceptionHandling.dieInternal(tc, "$kind representation only handles 8, 16, 32 and 64 bit ints")
                    return
                }
            }
            scalar(info, spec.bits / 8L)
        }
        else if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_UINT) {
            info.argType = when (spec.bits.toInt()) {
                8 -> ArgType.UCHAR
                16 -> ArgType.USHORT
                32 -> ArgType.UINT
                64 -> ArgType.ULONG
                else -> {
                    ExceptionHandling.dieInternal(tc, "$kind representation only handles 8, 16, 32 and 64 bit uints")
                    return
                }
            }
            scalar(info, spec.bits / 8L)
        }
        else if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_NUM) {
            info.argType = when (spec.bits.toInt()) {
                32 -> ArgType.FLOAT
                64 -> ArgType.DOUBLE
                else -> {
                    ExceptionHandling.dieInternal(tc, "$kind representation only handles 32 and 64 bit nums")
                    return
                }
            }
            scalar(info, spec.bits / 8L)
        }
        else if ((spec.can_box.toInt() and StorageSpec.CAN_BOX_STR.toInt()) != 0) {
            info.argType = ArgType.UTF8STR
            pointer(info)
        }
        else if (repr is CArray) {
            info.argType = ArgType.CARRAY
            pointer(info)
        }
        else if (repr is CPointer) {
            info.argType = ArgType.CPOINTER
            pointer(info)
        }
        else if (repr is CUnion) {
            info.argType = ArgType.CUNION
            aggregate(tc, info)
        }
        else if (repr is CPPStruct) {
            info.argType = ArgType.CPPSTRUCT
            aggregate(tc, info)
        }
        else if (repr is CStruct) {
            info.argType = ArgType.CSTRUCT
            aggregate(tc, info)
        }
        else {
            ExceptionHandling.dieInternal(tc, "$kind representation only handles int, num, CArray, CPointer, CStruct, CPPStruct and CUnion")
        }
    }

    private fun scalar(info: CAttrInfo, bytes: Long) {
        info.size = bytes
        info.alignment = bytes
    }

    private fun pointer(info: CAttrInfo) {
        info.size = NativeSupport.POINTER_SIZE.toLong()
        info.alignment = info.size
    }

    /* Nested structs and unions inline by default; an attribute the type
     * declared as a reference is just a pointer, which is also all we can do
     * for a type that hasn't been composed yet. */
    private fun aggregate(tc: ThreadContext, info: CAttrInfo) {
        if (info.inlined.toInt() == 0) {
            pointer(info)
            return
        }

        val nested = info.type!!.st.REPRData as? CTypeREPRData
        if (nested == null || !nested.composed) {
            /* A type we don't know the size of can't be laid out inside
             * another one, and inlining a type into itself has no finite
             * layout at all. Worded as MoarVM words it, which is what
             * rakudo's t/04-nativecall/23-incomplete-types.t looks for. */
            ExceptionHandling.dieInternal(tc,
                "$kind: can't inline a ${nestedKind(info)} attribute before its type's definition")
            return
        }

        info.size = nested.size
        info.alignment = nested.alignment
    }

    private fun nestedKind(info: CAttrInfo): String = when (info.argType) {
        ArgType.CSTRUCT -> "CStruct"
        ArgType.CPPSTRUCT -> "CPPStruct"
        ArgType.CUNION -> "CUnion"
        else -> "CArray"
    }
}
