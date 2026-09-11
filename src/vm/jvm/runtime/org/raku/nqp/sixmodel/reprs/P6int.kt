package org.raku.nqp.sixmodel.reprs

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.Inlining
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

class P6int : REPR() {
    companion object {
        /* Possible C types we can handle. */
        const val P6INT_C_TYPE_CHAR: Byte = -1
        const val P6INT_C_TYPE_SHORT: Byte = -2
        const val P6INT_C_TYPE_INT: Byte = -3
        const val P6INT_C_TYPE_LONG: Byte = -4
        const val P6INT_C_TYPE_LONGLONG: Byte = -5
        const val P6INT_C_TYPE_SIZE_T: Byte = -6
        const val P6INT_C_TYPE_BOOL: Byte = -7

        /** Truncate a value to a sized-int storage width, sign- or
         * zero-extending back to a long, the way MoarVM's sized registers
         * store. Full-width and unsized specs pass through. */
        @JvmStatic
        fun sizedValue(ss: StorageSpec?, value: Long): Long {
            val bits = ss?.bits?.toInt() ?: return value
            if (bits <= 0 || bits >= 64) return value
            return if (ss.isUnsigned) value and ((1L shl bits) - 1)
                   else (value shl (64 - bits)) shr (64 - bits)
        }
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        st.REPRData = StorageSpec.integer(64)
        return st.WHAT
    }

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        val integerInfo = reprInfo.at_key_boxed(tc, "integer")
        if (Ops.isnull(integerInfo) == 0L) {
            val bits = integerInfo!!.at_key_boxed(tc, "bits")
            if (Ops.isnull(bits) == 0L) {
                val bitwidth = bits!!.get_int(tc).toShort()
                val width = when (bitwidth.toInt()) {
                    P6INT_C_TYPE_CHAR.toInt() -> java.lang.Byte.SIZE.toShort()
                    P6INT_C_TYPE_SHORT.toInt() -> java.lang.Short.SIZE.toShort()
                    P6INT_C_TYPE_INT.toInt() -> Integer.SIZE.toShort()
                    /* C_LONG_SIZE is in bytes, not bits. */
                    P6INT_C_TYPE_LONG.toInt() -> (8 * NativeSupport.C_LONG_SIZE).toShort()
                    /* There is no LongLong in Java */
                    P6INT_C_TYPE_LONGLONG.toInt() -> java.lang.Long.SIZE.toShort()
                    P6INT_C_TYPE_SIZE_T.toInt() -> (8 * NativeSupport.SIZE_T_SIZE).toShort()
                    /* Let's just hope that a bool is 1 byte in size, always. */
                    P6INT_C_TYPE_BOOL.toInt() -> java.lang.Byte.SIZE.toShort()
                    else -> bitwidth
                }
                st.REPRData = (st.REPRData as StorageSpec).copy(bits = width)
            }
            val unsigned = integerInfo.at_key_boxed(tc, "unsigned")
            if (Ops.isnull(unsigned) == 0L) {
                val ss = st.REPRData as StorageSpec
                st.REPRData = StorageSpec.integer(ss.bits, unsigned!!.get_int(tc) != 0L)
            }
        }
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6intInstance()
        obj.st = st
        return obj
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec =
        st.REPRData as StorageSpec

    override fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        cw.visitField(Opcodes.ACC_PUBLIC, prefix, "J", null, null)
    }

    override fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_INT)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "nativeType", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/runtime/ThreadContext", "nativeI", "J")
        /* A sized int stores truncated to its width, as MoarVM's sized
         * registers do; reads then see the wrapped value. */
        val ss = st.REPRData as? StorageSpec
        val bits = ss?.bits?.toInt() ?: 64
        if (bits in 1..63) {
            if (ss!!.isUnsigned) {
                mv.visitLdcInsn((1L shl bits) - 1)
                mv.visitInsn(Opcodes.LAND)
            }
            else {
                mv.visitIntInsn(Opcodes.BIPUSH, 64 - bits)
                mv.visitInsn(Opcodes.LSHL)
                mv.visitIntInsn(Opcodes.BIPUSH, 64 - bits)
                mv.visitInsn(Opcodes.LSHR)
            }
        }
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "J")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.DUP)
        mv.visitInsn(Opcodes.ICONST_0 + ThreadContext.NATIVE_INT)
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "nativeType", "I")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "J")
        mv.visitFieldInsn(Opcodes.PUTFIELD, "org/raku/nqp/runtime/ThreadContext", "nativeI", "J")
        mv.visitInsn(Opcodes.RETURN)
    }

    override fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SerializationReader", "readLong", "()J")
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "J")
    }

    override fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        val getDesc = "(Lorg/raku/nqp/runtime/ThreadContext;)J"
        val getMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_int", getDesc, null, null)
        getMeth.visitVarInsn(Opcodes.ALOAD, 0)
        getMeth.visitFieldInsn(Opcodes.GETFIELD, className, prefix, "J")
        getMeth.visitInsn(Opcodes.LRETURN)
        getMeth.visitMaxs(0, 0)

        val setDesc = "(Lorg/raku/nqp/runtime/ThreadContext;J)V"
        val setMeth = cw.visitMethod(Opcodes.ACC_PUBLIC, "set_int", setDesc, null, null)
        setMeth.visitVarInsn(Opcodes.ALOAD, 0)
        setMeth.visitVarInsn(Opcodes.LLOAD, 2)
        setMeth.visitFieldInsn(Opcodes.PUTFIELD, className, prefix, "J")
        setMeth.visitInsn(Opcodes.RETURN)
        setMeth.visitMaxs(0, 0)
    }

    // We don't depend on any details of the STable, so no description is needed
    /* The generated storage code masks sized ints on store, so the width
     * and signedness are part of the storage-class cache signature; without
     * them one cached class would serve every int width. */
    override fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean {
        val ss = st.REPRData as? StorageSpec
        out.append(ss?.bits ?: 64).append(if (ss?.isUnsigned == true) 'u' else 's')
        return true
    }
    override fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean =
        inline_description(tc, st, out)

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val obj = P6intInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        (obj as P6intInstance).value = reader.readLong()
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        writer.writeInt((obj as P6intInstance).value)
    }

    override fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
                                   prefix: String, obj: SixModelObject) {
        try {
            writer.writeInt(obj.javaClass.getField(prefix).get(obj) as Long)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        writer.writeInt((st.REPRData as StorageSpec).bits.toLong())
        writer.writeInt(if ((st.REPRData as StorageSpec).isUnsigned) 1L else 0L)
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        val bits = if (reader.version >= 7) reader.readLong().toShort() else 64
        val unsigned = reader.version >= 8 && reader.readLong() != 0L
        st.REPRData = StorageSpec.integer(bits, unsigned)
    }
}
