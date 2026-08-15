package org.raku.nqp.sixmodel.reprs

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class CPointer : REPR() {
    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = null /* No REPR data needed. */
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = CPointerInstance()
        obj.st = st
        return obj
    }

    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val getDesc = "(Lorg/raku/nqp/runtime/ThreadContext;)J"
        val getMeth: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_int", getDesc, null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "J")
        getMeth.visitInsn(Opcodes.LRETURN)
        getMeth.visitMaxs(0, 0)

        val setDesc = "(Lorg/raku/nqp/runtime/ThreadContext;J)V"
        val setMeth: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_int", setDesc, null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.LLOAD, 2)
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "J")
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject? {
        /* This REPR can't be serialized. */
        ExceptionHandling.dieInternal(tc, "Can't deserialize_stub a CPointer object.")

        return null
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        ExceptionHandling.dieInternal(tc, "Can't deserialize_finish a CPointer object.")
    }
}
