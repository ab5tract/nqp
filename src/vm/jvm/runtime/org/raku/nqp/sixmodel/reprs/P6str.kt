package org.raku.nqp.sixmodel.reprs

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6str : REPR() {
    private companion object {
        val ss = StorageSpec().apply {
            inlineable = StorageSpec.INLINED
            boxed_primitive = StorageSpec.BP_STR
            can_box = StorageSpec.CAN_BOX_STR
        }
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6strInstance()
        obj.st = st
        obj.value = ""
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec = ss

    override fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        cw.visitField(Opcodes.ACC_PUBLIC, prefix, "Ljava/lang/String;", null, null)
    }

    override fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_STR)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/runtime/ThreadContext", "native_s", "Ljava/lang/String;")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "Ljava/lang/String;")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_STR)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "Ljava/lang/String;")
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_s", "Ljava/lang/String;")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SerializationReader", "readStr", "()Ljava/lang/String;")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "Ljava/lang/String;")
    }

    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val getMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_str",
            "(Lorg/raku/nqp/runtime/ThreadContext;)Ljava/lang/String;", null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "Ljava/lang/String;")
        getMeth.visitInsn(Opcodes.ARETURN)
        getMeth.visitMaxs(0, 0)

        val setMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_str",
            "(Lorg/raku/nqp/runtime/ThreadContext;Ljava/lang/String;)V", null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.ALOAD, 2)
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "Ljava/lang/String;")
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    // We don't depend on any details of the STable, so no description is needed
    override fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true
    override fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6strInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6strInstance).value = reader.readStr()
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        writer.writeStr((obj as P6strInstance).value)
    }

    override fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
                                   prefix: String, obj: SixModelObject) {
        try {
            writer.writeStr(obj.javaClass.getField(prefix).get(obj) as String?)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
