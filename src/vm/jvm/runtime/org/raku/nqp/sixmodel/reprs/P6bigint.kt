package org.raku.nqp.sixmodel.reprs

import java.math.BigInteger

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6bigint : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6bigintInstance()
        obj.st = st
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        val ss = StorageSpec()
        ss.inlineable = StorageSpec.INLINED
        ss.boxed_primitive = if (ss.is_unsigned.toInt() == 0) StorageSpec.BP_INT else StorageSpec.BP_UINT
        ss.bits = 64
        ss.can_box = StorageSpec.CAN_BOX_INT
        return ss
    }

    override fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        cw.visitField(Opcodes.ACC_PUBLIC, prefix, Type.getType(BigInteger::class.java).descriptor, null, null)
    }

    override fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        val bigIntegerType = Type.getType(BigInteger::class.java).descriptor
        val bigIntegerIN = Type.getType(BigInteger::class.java).internalName
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_JVM_OBJ)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/runtime/ThreadContext", "native_j",
            Type.getType(Any::class.java).descriptor)
        mv.visitTypeInsn(Opcodes.CHECKCAST, bigIntegerIN)
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, bigIntegerType)
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_JVM_OBJ)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, prefix,
            Type.getType(BigInteger::class.java).descriptor)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_j",
            Type.getType(Any::class.java).descriptor)
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitTypeInsn(Opcodes.NEW, "java/math/BigInteger")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SerializationReader", "readStr", "()Ljava/lang/String;")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/math/BigInteger", "<init>", "(Ljava/lang/String;)V")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "Ljava/math/BigInteger;")
    }

    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val bigIntegerType = Type.getType(BigInteger::class.java).descriptor
        val bigIntegerIN = Type.getType(BigInteger::class.java).internalName

        val getDesc = "(Lorg/raku/nqp/runtime/ThreadContext;)J"
        val getMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_int", getDesc, null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, bigIntegerType)
        getMeth.visitMethodInsn(Opcodes.INVOKEVIRTUAL, bigIntegerIN, "longValue", "()J")
        getMeth.visitInsn(Opcodes.LRETURN)
        getMeth.visitMaxs(0, 0)

        val setDesc = "(Lorg/raku/nqp/runtime/ThreadContext;J)V"
        val setMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_int", setDesc, null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.LLOAD, 2)
        setMeth.visitMethodInsn(Opcodes.INVOKESTATIC, bigIntegerIN, "valueOf",
            Type.getMethodDescriptor(Type.getType(BigInteger::class.java), Type.LONG_TYPE))
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, bigIntegerType)
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    // We don't depend on any details of the STable, so no description is needed
    override fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true
    override fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6bigintInstance()
        // "error: BigInteger(long) has private access in BigInteger"
        obj.value = BigInteger("0")
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6bigintInstance).value = BigInteger(reader.readStr())
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        /* Write out as String. */
        writer.writeStr((obj as P6bigintInstance).value.toString())
    }

    override fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
                                   prefix: String, obj: SixModelObject) {
        try {
            writer.writeStr(obj.javaClass.getField(prefix).get(obj).toString())
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
