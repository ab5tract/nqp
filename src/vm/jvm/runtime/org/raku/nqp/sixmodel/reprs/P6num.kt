package org.raku.nqp.sixmodel.reprs

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6num : REPR() {
    companion object {
        /* Possible C types we can handle. */
        const val P6NUM_C_TYPE_FLOAT: Byte = -1
        const val P6NUM_C_TYPE_DOUBLE: Byte = -2
        const val P6NUM_C_TYPE_LONGDOUBLE: Byte = -3
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        val ss = StorageSpec()
        ss.inlineable = StorageSpec.INLINED
        ss.boxed_primitive = StorageSpec.BP_NUM
        ss.bits = 64
        ss.can_box = StorageSpec.CAN_BOX_NUM
        st.REPRData = ss
        return st.WHAT
    }

    override fun compose(tc: ThreadContext, st: STable, repr_info: SixModelObject) {
        val floatInfo = repr_info.at_key_boxed(tc, "float")
        if (Ops.isnull(floatInfo) == 0L) {
            val bits = floatInfo!!.at_key_boxed(tc, "bits")
            if (Ops.isnull(bits) == 0L) {
                val bitwidth = bits!!.get_int(tc).toShort()
                val ss = st.REPRData as StorageSpec
                ss.bits = when (bitwidth.toInt()) {
                    P6NUM_C_TYPE_FLOAT.toInt() -> java.lang.Float.SIZE.toShort()
                    P6NUM_C_TYPE_DOUBLE.toInt() -> java.lang.Double.SIZE.toShort()
                    /* There is no LongDouble in Java */
                    P6NUM_C_TYPE_LONGDOUBLE.toInt() -> java.lang.Double.SIZE.toShort()
                    else -> bitwidth
                }
            }
        }
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6numInstance()
        obj.st = st
        obj.value = Double.NaN
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec =
        st.REPRData as StorageSpec

    override fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        cw.visitField(Opcodes.ACC_PUBLIC, prefix, "D", null, null)
    }

    override fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_NUM)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/runtime/ThreadContext", "native_n", "D")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "D")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_NUM)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "D")
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_n", "D")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SerializationReader", "readDouble", "()D")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "D")
    }

    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val getMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_num",
            "(Lorg/raku/nqp/runtime/ThreadContext;)D", null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "D")
        getMeth.visitInsn(Opcodes.DRETURN)
        getMeth.visitMaxs(0, 0)

        val setMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_num",
            "(Lorg/raku/nqp/runtime/ThreadContext;D)V", null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.DLOAD, 2)
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "D")
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    // We don't depend on any details of the STable, so no description is needed
    override fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true
    override fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6numInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6numInstance).value = reader.readDouble()
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        writer.writeNum((obj as P6numInstance).value)
    }

    override fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
                                   prefix: String, obj: SixModelObject) {
        try {
            writer.writeNum(obj.javaClass.getField(prefix).get(obj) as Double)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        writer.writeInt((st.REPRData as StorageSpec).bits.toLong())
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        val ss = StorageSpec()
        ss.inlineable = StorageSpec.INLINED
        ss.boxed_primitive = StorageSpec.BP_NUM
        ss.bits = if (reader.version >= 7) reader.readLong().toShort() else 64
        ss.can_box = StorageSpec.CAN_BOX_NUM
        st.REPRData = ss
    }
}
