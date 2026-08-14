package org.raku.nqp.sixmodel.reprs

import com.sun.jna.Structure

import org.raku.nqp.jast2bc.BytecodeVersion
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class CPPStruct : REPR() {
    companion object {
        private var typeId: Long = 0
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun compose(tc: ThreadContext, st: STable, repr_info: SixModelObject) {
        val attr_info = repr_info.at_key_boxed(tc, "attribute")!!
        val repr_data = CPPStructREPRData()

        val mroLength = attr_info.elems(tc)
        val attrInfos = ArrayList<CPPStructREPRData.AttrInfo>()
        for (i in mroLength - 1 downTo 0) {
            val entry = attr_info.at_pos_boxed(tc, i)!!
            val attrs = entry.at_pos_boxed(tc, 1)!!
            val parents = entry.at_pos_boxed(tc, 2)!!.elems(tc)

            if (parents <= 1) {
                val numAttrs = attrs.elems(tc)
                for (j in 0 until numAttrs) {
                    val attrHash = attrs.at_pos_boxed(tc, j)!!
                    val info = CPPStructREPRData.AttrInfo()
                    info.name = attrHash.at_key_boxed(tc, "name")!!.get_str(tc)
                    info.type = attrHash.at_key_boxed(tc, "type")
                    info.inlined = attrHash.at_key_boxed(tc, "inlined")!!.get_int(tc).toShort()
                    val spec = info.type!!.st.REPR.get_storage_spec(tc, info.type!!.st)!!
                    info.bits = spec.bits
                    repr_data.fieldTypes.put(info.name!!, info)

                    if (info.type == null) {
                        ExceptionHandling.dieInternal(tc, "CPPStruct representation requires the types of all attributes to be specified")
                    }

                    attrInfos.add(info)
                }
            }
            else {
                ExceptionHandling.dieInternal(tc, "CPPStruct representation does not support multiple inheritance")
            }
        }

        /* XXX: We could generate the structure class lazily the first time we
         * allocate an object, rather than upfront. Not sure if that's
         * necessary though. */
        st.REPRData = repr_data
        generateStructClass(tc, st, attrInfos)
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        /* TODO: Die if someone tries to allocate a CPPStruct before it's been
         * composed. */
        val obj = CPPStructInstance()
        val repr_data = st.REPRData as CPPStructREPRData
        obj.st = st
        try {
            @Suppress("DEPRECATION")
            obj.storage = repr_data.structureClass!!.newInstance() as Structure
        }
        catch (e: ReflectiveOperationException) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject? {
        /* This REPR can't be serialized. */
        ExceptionHandling.dieInternal(tc, "Can't deserialize_stub a CPPStruct object.")

        return null
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        ExceptionHandling.dieInternal(tc, "Can't deserialize_finish a CPPStruct object.")
    }

    @Suppress("DEPRECATION")
    private fun generateStructClass(tc: ThreadContext, st: STable, fields: List<CPPStructREPRData.AttrInfo>) {
        val reprData = st.REPRData as CPPStructREPRData
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        val className = "__CPPStruct__" + typeId++

        val attributes = fields.size

        // public $className extends com.sun.jna.Structure implements com.sun.jna.Structure.ByReference { ... }
        cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null, "com/sun/jna/Structure", null)

        //     private static List<String> fieldOrder;
        var fv = cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "fieldOrder", "Ljava/util/List;",
                "Ljava.util.List<Ljava.lang.String;>;", null)
        fv.visitEnd()

        for (info in fields) {
            val type = typeDescriptor(tc, info)
            /* Indirect referenced structs need to be handled as pointers here, since the default of
             * structs and unions is to inline. */
            if (info.inlined.toInt() == 0
            && (info.argType == ArgType.CSTRUCT || info.argType == ArgType.CPPSTRUCT || info.argType == ArgType.CUNION))
                fv = cw.visitField(Opcodes.ACC_PUBLIC, info.name, "Lcom/sun/jna/Pointer;", null, null)
            else
                fv = cw.visitField(Opcodes.ACC_PUBLIC, info.name, type, null, null)
            fv.visitEnd()
        }

        //     static { fieldOrder = new ArrayList(); fieldOrder.add(field1); ... }
        val staticVisitor = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        staticVisitor.visitCode()
        staticVisitor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList") // Construct new object.
        staticVisitor.visitInsn(Opcodes.DUP)
        staticVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V") // Invoke the constructor.
        staticVisitor.visitFieldInsn(Opcodes.PUTSTATIC, className, "fieldOrder", "Ljava/util/List;")

        for (i in 0 until attributes) {
            staticVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, "fieldOrder", "Ljava/util/List;")
            staticVisitor.visitLdcInsn(fields[i].name)
            staticVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z")
            staticVisitor.visitInsn(Opcodes.POP)
        }

        staticVisitor.visitInsn(Opcodes.RETURN)
        staticVisitor.visitMaxs(2, 0)
        staticVisitor.visitEnd()

        // public List<String> getFieldOrder() { return fieldOrder; }
        val gfoVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "getFieldOrder", "()Ljava/util/List;", "()Ljava/util/List<Ljava/lang/String;>;", null)
        gfoVisitor.visitCode()
        gfoVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, "fieldOrder", "Ljava/util/List;")
        gfoVisitor.visitInsn(Opcodes.ARETURN)
        gfoVisitor.visitMaxs(1, 1)
        gfoVisitor.visitEnd()

        // Add nullary constructor calling superclass.
        val constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/sun/jna/Structure", "<init>", "()V")
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        cw.visitEnd()
        val compiled = cw.toByteArray()
        reprData.structureClass = tc.gc.byteClassLoader.defineClass(className, compiled)
    }

    private fun typeDescriptor(tc: ThreadContext, info: CPPStructREPRData.AttrInfo): String? {
        val repr = info.type!!.st.REPR
        val spec = repr.get_storage_spec(tc, info.type!!.st)!!
        info.bits = spec.bits
        if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_INT) {
            when (spec.bits.toInt()) {
                8 -> {
                    info.argType = ArgType.CHAR
                    return "B"
                }
                16 -> {
                    info.argType = ArgType.SHORT
                    return "S"
                }
                32 -> {
                    info.argType = ArgType.INT
                    return "I"
                }
                64 -> {
                    info.argType = ArgType.LONG
                    return "J"
                }
                else -> {
                    ExceptionHandling.dieInternal(tc, "CPPStruct representation only handles 8, 16, 32 and 64 bit ints")
                    return null
                }
            }
        }
        else if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_UINT) {
            when (spec.bits.toInt()) {
                8 -> {
                    info.argType = ArgType.UCHAR
                    return "B"
                }
                16 -> {
                    info.argType = ArgType.USHORT
                    return "S"
                }
                32 -> {
                    info.argType = ArgType.UINT
                    return "I"
                }
                64 -> {
                    info.argType = ArgType.ULONG
                    return "J"
                }
                else -> {
                    ExceptionHandling.dieInternal(tc, "CPPStruct representation only handles 8, 16, 32 and 64 bit uints")
                    return null
                }
            }
        }
        else if (spec.inlineable == StorageSpec.INLINED && spec.boxed_primitive == StorageSpec.BP_NUM) {
            when (spec.bits.toInt()) {
                32 -> {
                    info.argType = ArgType.FLOAT
                    return "F"
                }
                64 -> {
                    info.argType = ArgType.DOUBLE
                    return "D"
                }
                else -> {
                    ExceptionHandling.dieInternal(tc, "CPPStruct representation only handles 32 and 64 bit nums")
                    return null
                }
            }
        }
        else if ((spec.can_box.toInt() and StorageSpec.CAN_BOX_STR.toInt()) != 0) {
            info.argType = ArgType.UTF8STR
            return "Ljava/lang/String;"
        }
        else if (repr is CArray) {
            info.argType = ArgType.CARRAY
            return "Lcom/sun/jna/Pointer;"
        }
        else if (repr is CPointer) {
            info.argType = ArgType.CPOINTER
            return "Lcom/sun/jna/Pointer;"
        }
        else if (repr is CUnion) {
            info.argType = ArgType.CUNION
            val c = (info.type!!.st.REPRData as CUnionREPRData).structureClass
            return Type.getDescriptor(c)
        }
        else if (repr is CStruct) {
            info.argType = ArgType.CSTRUCT
            val c = (info.type!!.st.REPRData as CStructREPRData).structureClass
            return Type.getDescriptor(c)
        }
        else if (repr is CPPStruct) {
            info.argType = ArgType.CPPSTRUCT
            val c = (info.type!!.st.REPRData as CPPStructREPRData).structureClass

            /* When we hit a struct in an attribute that is not composed yet, we most likely
             * have hit a struct of our own kind. */
            if (c == null)
                return "L__CPPStruct__$typeId;"

            return Type.getDescriptor(c)
        }
        else {
            ExceptionHandling.dieInternal(tc, "CPPStruct representation only handles int, num, CArray, CPointer, CStruct, CPPStruct and CUnion")
            return null
        }
    }
}
