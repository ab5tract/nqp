package org.raku.nqp.sixmodel.reprs

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

class NativeCall : REPR() {
    /* The available native call argument types. */
    enum class ArgType {
        VOID,
        CHAR,
        SHORT,
        INT,
        LONG,
        LONGLONG,
        FLOAT,
        DOUBLE,
        ASCIISTR,
        UTF8STR,
        UTF16STR,
        CSTRUCT,
        CPPSTRUCT,
        CUNION,
        CARRAY,
        CALLBACK,
        CPOINTER,
        VMARRAY,
        UCHAR,
        USHORT,
        UINT,
        ULONG,
        ULONGLONG,
        CHAR_RW,
        SHORT_RW,
        INT_RW,
        LONG_RW,
        LONGLONG_RW,
        FLOAT_RW,
        DOUBLE_RW,
        UCHAR_RW,
        USHORT_RW,
        UINT_RW,
        ULONG_RW,
        ULONGLONG_RW,
        CPOINTER_RW,
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = null /* No REPR data needed. */
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = NativeCallInstance()
        obj.st = st
        obj.body = NativeCallBody()
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        val ss = StorageSpec()
        ss.inlineable = StorageSpec.INLINED
        ss.bits = 64
        return ss
    }

    override fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        cw.visitField(Opcodes.ACC_PUBLIC, prefix, Type.getType(NativeCallBody::class.java).descriptor, null, null)
    }

    override fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        val nativeCallType = Type.getType(NativeCallBody::class.java).descriptor
        val nativeCallIN = Type.getType(NativeCallBody::class.java).internalName
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_JVM_OBJ)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/runtime/ThreadContext", "native_j",
            Type.getType(Any::class.java).descriptor)
        mv.visitTypeInsn(Opcodes.CHECKCAST, nativeCallIN)
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, nativeCallType)
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_JVM_OBJ)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_type", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, prefix,
            Type.getType(NativeCallBody::class.java).descriptor)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "native_j",
            Type.getType(Any::class.java).descriptor)
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        /* Assume it'll be re-configured each time, so just allow it. */
    }

    // XXX This is a hack as it fails to check the REPR ID, but the JVM will
    // catch any screw-ups and keep us safe.
    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val nativeCallType = Type.getType(NativeCallBody::class.java).descriptor
        val nativeCallIN = Type.getType(NativeCallBody::class.java).internalName

        val getDesc = "(Lorg/raku/nqp/runtime/ThreadContext;J)Ljava/lang/Object;"
        val getMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_boxing_of", getDesc, null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, nativeCallType)
        getMeth.visitInsn(Opcodes.ARETURN)
        getMeth.visitMaxs(0, 0)

        val setDesc = "(Lorg/raku/nqp/runtime/ThreadContext;JLjava/lang/Object;)V"
        val setMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_boxing_of", setDesc, null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.ALOAD, 4)
        setMeth.visitTypeInsn(Opcodes.CHECKCAST, nativeCallIN)
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, nativeCallType)
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    // We don't depend on any details of the STable, so no description is needed
    override fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true
    override fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean = true

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        /* Assume it'll be re-configured each time, so just allow it. */
        val stub = NativeCallInstance()
        stub.st = st
        return stub
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        /* Assume it'll be re-configured each time, so just allow it. */
    }

    override fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
                                   prefix: String, obj: SixModelObject) {
        /* Assume it'll be re-configured each time, so just allow it. */
    }
}
