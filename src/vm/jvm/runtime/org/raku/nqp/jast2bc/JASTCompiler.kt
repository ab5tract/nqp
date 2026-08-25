package org.raku.nqp.jast2bc

import java.io.File
import java.io.FileOutputStream

import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

import java.util.zip.CRC32
import java.util.zip.ZipEntry

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.runtime.Ops

import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4CompressorWithLength

@Suppress("DEPRECATION")
class JASTCompiler private constructor(jastNodes: SixModelObject, tc: ThreadContext) {
    companion object {
        @JvmStatic
        fun buildClass(jast: SixModelObject, jastNodes: SixModelObject, split: Boolean, tc: ThreadContext): JavaClass {
            try {
                val compiler = JASTCompiler(jastNodes, tc)
                val c = compiler.compileJast(jast, jastNodes, split, tc)
                return c
            }
            catch (e: Exception) {
                if (!split && isMethodTooLarge(e))
                    return buildClass(jast, jastNodes, true, tc)
                /* "Class too large: <hash>" says nothing about how far over
                 * the 65535-entry constant pool the unit went, which is the
                 * one number that tells you whether trimming is plausible. */
                if (e is org.objectweb.asm.ClassTooLargeException)
                    throw RuntimeException(
                        "Class too large: constant pool has "
                            + e.getConstantPoolCount() + " entries, limit is 65535", e)
                throw RuntimeException(e)
            }
        }

        /* ASM 4 signals an oversized method with a RuntimeException carrying
         * this message; ASM 5+ throws the typed MethodTooLargeException (with a
         * different message), which is matched by name here so that this code
         * compiles against either ASM. Without this, the autosplitting method
         * writer is unreachable under newer ASM and >64KB methods are a hard
         * build failure. */
        private fun isMethodTooLarge(e: Exception): Boolean {
            return "Method code too large!" == e.message
                || "org.objectweb.asm.MethodTooLargeException" == e.javaClass.getName()
        }

        /* This used to be fastestInstance(), which selects lz4's
         * sun.misc.Unsafe fast path. Switched to the pure-Java safeInstance()
         * to future-proof against the deprecation-for-removal of Unsafe
         * (JEP 498: JDK 24+ warns on every process start, and later releases
         * will refuse outright). */
        private val lz4 =
            LZ4CompressorWithLength(LZ4Factory.safeInstance().highCompressor(8))
        // The lower the compression level, the faster the serialization, at the
        // expense of deserialization and disk space; the higher the compression
        // level, the lower the disk space, at the expense of both serialization
        // and deserialization. 8 is a sweet spot of sorts.

        @JvmStatic
        fun writeClass(jast: SixModelObject, jastNodes: SixModelObject, filename: String, tc: ThreadContext) {
            val c = buildClass(jast, jastNodes, false, tc)
            try {
                val fos = FileOutputStream(filename)
                if (c.serialized == null) {
                    // we're writing a plain java class
                    fos.write(c.bytes)
                    fos.close()
                } else {
                    // writing a jar
                    val mf = Manifest()
                    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0")
                    if (c.hasMain)
                        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, c.name)
                    val jos = JarOutputStream(fos, mf)

                    val jec = JarEntry(c.name + ".class")
                    jec.setComment(c.name!!.replace(File.pathSeparatorChar, '.'))
                    jos.putNextEntry(jec)
                    jos.write(c.bytes)
                    jos.closeEntry()

                    val digest = lz4.compress(c.serialized)
                    val cipher = CRC32()
                    cipher.update(digest, 0, digest.size)

                    val jes = JarEntry(c.name + ".serialized.lz4")
                    jes.setMethod(ZipEntry.STORED)
                    jes.setSize(digest.size.toLong())
                    jes.setCompressedSize(digest.size.toLong())
                    jes.setCrc(cipher.getValue())
                    jos.putNextEntry(jes)
                    jos.write(digest)
                    jos.closeEntry()

                    /* Nested units (EVALs run while this unit compiled)
                     * whose code refs the serialization points into ride
                     * along, to be loaded back by jvmclaimnested. */
                    for (nestedName in c.nestedClassNames) {
                        val nestedBytes = tc.gc.inMemoryUnitBytes[nestedName]
                            ?: throw RuntimeException(
                                "No retained classfile for nested unit " + nestedName)
                        val jen = JarEntry(nestedName + ".class")
                        jos.putNextEntry(jen)
                        jos.write(nestedBytes)
                        jos.closeEntry()
                    }

                    jos.close()
                }
            }
            catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        private var setup = false
        private fun setup(jastNodes: SixModelObject, tc: ThreadContext) {
            setup = true

            JastClass.setup(jastNodes.at_key_boxed(tc, "JAST::Class")!!, tc)
            JastField.setup(jastNodes.at_key_boxed(tc, "JAST::Field")!!, tc)
            JastMethod.setup(jastNodes.at_key_boxed(tc, "JAST::Method")!!, tc)
        }

        @JvmStatic
        fun processType(typeName: String): Type {
            /* The JAST type language uses first-character-significant names
             * ("Long", "Double", "Byte", "[Byte", ...) alongside real JVM
             * descriptors. ASM 4's Type.getType happens to accept the friendly
             * names because it only inspects the leading character; newer ASM
             * keeps the given string as the descriptor verbatim, producing
             * corrupt descriptors like ([Byte[Ljava/lang/String;)V. Map the
             * type language explicitly so either ASM works. */
            if (typeName == "Long")
                return Type.LONG_TYPE
            return when (typeName[0]) {
                'V' -> Type.VOID_TYPE
                'Z' -> Type.BOOLEAN_TYPE
                'C' -> Type.CHAR_TYPE
                'B' -> Type.BYTE_TYPE
                'S' -> Type.SHORT_TYPE
                'I' -> Type.INT_TYPE
                'F' -> Type.FLOAT_TYPE
                'J' -> Type.LONG_TYPE
                'D' -> Type.DOUBLE_TYPE
                '[' -> Type.getType('[' + processType(typeName.substring(1)).getDescriptor())
                else -> Type.getType(typeName)
            }
        }
    }

    private val jastLabel: SixModelObject?
    private val jastInstruction: SixModelObject?
    private val jastIndy: SixModelObject?
    private val jastInstructionList: SixModelObject?
    private val jastPushI: SixModelObject?
    private val jastPushN: SixModelObject?
    private val jastPushS: SixModelObject?
    private val jastPushC: SixModelObject?
    private val jastPushIdx: SixModelObject?
    private val jastTryCatch: SixModelObject?
    private val jastAnnotation: SixModelObject?

    init {
        if (!setup) setup(jastNodes, tc)

        jastLabel = jastNodes.at_key_boxed(tc, "JAST::Label")
        jastInstruction = jastNodes.at_key_boxed(tc, "JAST::Instruction")
        jastIndy = jastNodes.at_key_boxed(tc, "JAST::InvokeDynamic")
        jastInstructionList = jastNodes.at_key_boxed(tc, "JAST::InstructionList")
        jastPushI = jastNodes.at_key_boxed(tc, "JAST::PushIVal")
        jastPushN = jastNodes.at_key_boxed(tc, "JAST::PushNVal")
        jastPushS = jastNodes.at_key_boxed(tc, "JAST::PushSVal")
        jastPushC = jastNodes.at_key_boxed(tc, "JAST::PushCVal")
        jastPushIdx = jastNodes.at_key_boxed(tc, "JAST::PushIndex")
        jastTryCatch = jastNodes.at_key_boxed(tc, "JAST::TryCatch")
        jastAnnotation = jastNodes.at_key_boxed(tc, "JAST::Annotation")
    }

    @Throws(Exception::class)
    private fun compileJast(jast: SixModelObject, jastNodes: SixModelObject, split: Boolean, tc: ThreadContext): JavaClass {

        val jastClassObj = jastNodes.at_key_boxed(tc, "JAST::Class")
        val jastField = jastNodes.at_key_boxed(tc, "JAST::Field")
        val jastMethod = jastNodes.at_key_boxed(tc, "JAST::Method")

        val jastClass = JastClass(jast, jastClassObj!!, tc)
        val c = JavaClass()

        c.name = jastClass.className
        c.serialized = jastClass.serialized
        c.nestedClassNames = jastClass.nestedClasses

        val className = jastClass.className!!.replace('.', '/')
        val superName = jastClass.superName!!.replace('.', '/')

        indyCount = 0
        indyByBsm.clear()
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, className, null,
                superName, null)
        cw.visitSource(jastClass.filename, null)

        var iter = Ops.iter(jastClass.fields, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val field = JastField(iter.shift_boxed(tc)!!, jastField!!, tc)

            cw.visitField(
                    if (field.isStatic)
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
                    else
                        Opcodes.ACC_PUBLIC,
                    field.name, field.type!!.getDescriptor(), null, null)
        }

        iter = Ops.iter(jastClass.methods, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val method = JastMethod(iter.shift_boxed(tc)!!, jastMethod!!, tc)
            compileMethod(c, method, cw, className, split, tc)
        }

        // Add empty constructor.
        val constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                superName, "<init>", "()V")
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        cw.visitEnd()
        c.bytes = cw.toByteArray()

        /* HotSpot gives a class one resolved-references array and indexes it
         * with a 16-bit field. It holds one entry per invokedynamic
         * INSTRUCTION -- sites sharing a constant pool entry still take one
         * each -- plus one per string, class, method-handle, method-type and
         * dynamic constant. Past 65535 the index wraps and the entries alias:
         * a string constant reads back as another site's CallSite, and a
         * bootstrap slot reads back as something that is not a MethodHandle,
         * which the VM reports as "classfile must supply a valid BSM" or dies
         * on (observed on JDK 25.0.3 and 25.0.4: aliased callsites under JIT,
         * SIGSEGV under -Xint). Nothing downstream can detect it, so refuse to
         * emit such a class. The QAST compiler keeps its indy sites under
         * $INDY_SITE_BUDGET so this is a backstop, not the usual limiter. */
        val constantRefs = countResolvedReferences(c.bytes!!)
        val resolvedRefs = constantRefs + indyCount
        if (resolvedRefs > 65535)
            throw RuntimeException("Class " + className + " needs " + resolvedRefs
                + " resolved references (" + indyCount + " invokedynamic instructions plus "
                + constantRefs + " string/class/handle constants), over the JVM's"
                + " per-class limit of 65535 (by bootstrap method: "
                + indyByBsm.entries.sortedByDescending { it.value }
                    .joinToString(", ") { it.key + "=" + it.value }
                + ")")

        return c
    }

    @Throws(Exception::class)
    private fun compileMethod(jcout: JavaClass, method: JastMethod, c: ClassWriter, className: String, split: Boolean, tc: ThreadContext) {
        val desc = Type.getMethodDescriptor(method.returns, *method.arguments.toTypedArray())
        val modifiers = if (method.isStatic) Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC else Opcodes.ACC_PUBLIC
        val m: MethodVisitor = if (split) AutosplitMethodWriter(c, className, modifiers, method.name, desc, null, null)
            else c.visitMethod(modifiers, method.name, desc, null, null)

        if (method.name == "main") jcout.hasMain = true

        if ((method.crCuid != null && method.crCuid != "") || method.crOuter >= -1) {
            val crAnnType = Type.getType("Lorg/raku/nqp/runtime/CodeRefAnnotation;")
            val av = m.visitAnnotation(crAnnType.getDescriptor(), true)
            av.visit("name", method.crName)
            if (method.crCuid != null && !method.crCuid!!.isEmpty()) av.visit("cuid", method.crCuid)
            if (method.crOuter >= 0) av.visit("outerQbid", method.crOuter)

            var avLex: org.objectweb.asm.AnnotationVisitor
            if (method.crOlex.size > 0) {
                avLex = av.visitArray("oLexicalNames")
                for (i in 0 until method.crOlex.size)
                    avLex.visit(null, method.crOlex[i])
                avLex.visitEnd()
            }
            if (method.crIlex.size > 0) {
                avLex = av.visitArray("iLexicalNames")
                for (i in 0 until method.crIlex.size)
                    avLex.visit(null, method.crIlex[i])
                avLex.visitEnd()
            }
            if (method.crNlex.size > 0) {
                avLex = av.visitArray("nLexicalNames")
                for (i in 0 until method.crNlex.size)
                    avLex.visit(null, method.crNlex[i])
                avLex.visitEnd()
            }
            if (method.crSlex.size > 0) {
                avLex = av.visitArray("sLexicalNames")
                for (i in 0 until method.crSlex.size)
                    avLex.visit(null, method.crSlex[i])
                avLex.visitEnd()
            }

            if (method.crHandlers.size != 1 || method.crHandlers[0] != 0L) av.visit("handlers", method.crHandlers)
            if (method.hasExitHandler) av.visit("hasExitHandler", method.hasExitHandler)
            if (method.argsExpectation > 0) av.visit("argsExpectation", method.argsExpectation)
            if (method.isThunk) av.visit("isThunk", method.isThunk)
            method.crFile?.let {
                av.visit("sourceFile", it)
                av.visit("sourceLine", method.crLine)
                /* Stored as a delta: it is constant per #line-directive
                 * section, so the constant pool interns a handful of values
                 * rather than one integer per method (which overflowed the
                 * setting's 64K pool), and zero -- every non-directive file
                 * -- costs nothing at all. */
                val delta = method.crRawLine - method.crLine
                if (delta != 0) av.visit("sourceLineDelta", delta)
                /* Intra-body directive sections; rare, so the extra pool
                 * entries stay negligible (files intern, ints are small). */
                method.crSectionRaw?.let { raws ->
                    av.visit("sourceSectionRaw", raws)
                    av.visit("sourceSectionLine", method.crSectionLine)
                    val avSect = av.visitArray("sourceSectionFile")
                    for (f in method.crSectionFile!!)
                        avSect.visit(null, f)
                    avSect.visitEnd()
                }
            }
            av.visitEnd()
        }

        if (!method.isStatic)
            method.locals.put("this", VariableDef(0, "L$className;", method.beginAll, method.endAll))

        m.visitCode()
        m.visitLabel(method.beginAll)

        val iter = Ops.iter(method.instructions, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val insn = iter.shift_boxed(tc)
            compileInstruction(insn!!, method, m, tc)
        }

        m.visitLabel(method.endAll)
        var i = 0
        for (e in method.locals.entries) {
            val def = e.value
            m.visitLocalVariable("__local_" + i++, def.type, null, def.start, def.end, def.index)
        }

        for (e in method.labels.entries) {
            if (!e.value.defined)
                throw Exception(e.key + " used but not defined in " + method.name)
        }

        try {
            m.visitMaxs(0, 0)
        }
        catch (e: Exception) {
            throw Exception("Bytecode assembly failed for method '" +
                method.crName + "' (" + method.name + ") in " + className, e)
        }
        m.visitEnd()
    }

    @Throws(Exception::class)
    private fun compileInstruction(insn: SixModelObject, method: JastMethod, m: MethodVisitor, tc: ThreadContext) {
        if (Ops.istype(insn, jastLabel, tc) != 0L) {
            val labelName = Ops.getattr_s(insn, jastLabel, "\$!name", 0, tc)
            if (!method.labels.containsKey(labelName))
                method.labels.put(labelName, LabelInfo())
            val inf = method.labels[labelName]!!
            if (inf.defined) throw RuntimeException(labelName + " defined twice in " + method.name)
            inf.defined = true
            m.visitLabel(inf.label)
        }
        else if (Ops.istype(insn, jastPushI, tc) != 0L) {
            val value = Ops.getattr_i(insn, jastPushI, "\$!value", 0, tc)
            if (value == 0L) {
                m.visitInsn(Opcodes.LCONST_0)
            } else if (value == 1L) {
                m.visitInsn(Opcodes.LCONST_1)
            } else {
                m.visitLdcInsn(value)
            }
        }
        else if (Ops.istype(insn, jastPushN, tc) != 0L) {
            val value = Ops.getattr_n(insn, jastPushN, "\$!value", 0, tc)
            m.visitLdcInsn(value)
        }
        else if (Ops.istype(insn, jastPushS, tc) != 0L) {
            val value = Ops.getattr_s(insn, jastPushS, "\$!value", 0, tc)
            /* A constant-pool Utf8 entry's length is a u2 in bytes; a bigger
             * string literal is pushed in pieces and concatenated. */
            if (value != null && value.length > 16000 &&
                    value.toByteArray(Charsets.UTF_8).size > 60000) {
                if (System.getenv("NQP_DISPATCH_DEBUG") != null)
                    System.err.println("[big-sval] " + value.length + " chars, starts: '" +
                        value.substring(0, 80).replace('\n', ' ') + "'")
                var i = 0
                var first = true
                while (i < value.length) {
                    val end = minOf(i + 16000, value.length)
                    m.visitLdcInsn(value.substring(i, end))
                    if (!first)
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                            "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                    first = false
                    i = end
                }
            }
            else
                m.visitLdcInsn(value)
        }
        else if (Ops.istype(insn, jastPushC, tc) != 0L) {
            val value = Type.getType(Ops.getattr_s(insn, jastPushC, "\$!value", 0, tc))
            m.visitLdcInsn(value)
        }
        else if (Ops.istype(insn, jastPushIdx, tc) != 0L) {
            val value = Ops.getattr_i(insn, jastPushIdx, "\$!value", 0, tc).toInt()
            if (value >= 0 && value <= 5) {
                m.visitInsn(Opcodes.ICONST_0 + value)
            }
            else if (value >= java.lang.Byte.MIN_VALUE && value <= java.lang.Byte.MAX_VALUE) {
                m.visitIntInsn(Opcodes.BIPUSH, value)
            }
            else if (value >= java.lang.Short.MIN_VALUE && value <= java.lang.Short.MAX_VALUE) {
                m.visitIntInsn(Opcodes.SIPUSH, value)
            }
            // bandaid for rakudo: reduce number of constant pool entries
            else if (value > java.lang.Short.MAX_VALUE) {
                var valueRemain = value - java.lang.Short.MAX_VALUE
                m.visitIntInsn(Opcodes.SIPUSH, java.lang.Short.MAX_VALUE.toInt())
                while (valueRemain > java.lang.Short.MAX_VALUE) {
                    valueRemain = valueRemain - java.lang.Short.MAX_VALUE
                    m.visitIntInsn(Opcodes.SIPUSH, java.lang.Short.MAX_VALUE.toInt())
                    m.visitInsn(Opcodes.IADD)
                }
                if (valueRemain <= 5) {
                    m.visitInsn(Opcodes.ICONST_0 + valueRemain)
                }
                else if (valueRemain <= java.lang.Byte.MAX_VALUE) {
                    m.visitIntInsn(Opcodes.BIPUSH, valueRemain)
                }
                else {
                    m.visitIntInsn(Opcodes.SIPUSH, valueRemain)
                }
                m.visitInsn(Opcodes.IADD)
            }
            else {
                m.visitLdcInsn(value)
            }
        }
        else if (Ops.istype(insn, jastTryCatch, tc) != 0L) {
            val start = Label()
            val afterCatch = Label()
            val end = Label()
            val handler = Label()
            var typeName = Ops.getattr_s(insn, jastTryCatch, "\$!type", 2, tc)
            if (typeName != null) {
                typeName = typeName.substring(1, typeName.length - 1)
            }

            m.visitLabel(start)

            compileInstruction(Ops.getattr(insn, jastTryCatch, "\$!try", 0, tc)!!, method, m, tc)

            m.visitJumpInsn(Opcodes.GOTO, afterCatch)
            m.visitLabel(end)
            m.visitLabel(handler)
            m.visitTryCatchBlock(start, end, handler, typeName)

            compileInstruction(Ops.getattr(insn, jastTryCatch, "\$!catch", 1, tc)!!, method, m, tc)

            m.visitLabel(afterCatch)
        }
        else if (Ops.istype(insn, jastAnnotation, tc) != 0L) {
            val line = Ops.getattr_i(insn, jastAnnotation, "\$!line", 0, tc).toInt()
            val l = Label()
            m.visitLabel(l)
            m.visitLineNumber(line, l)
        }
        else if (Ops.istype(insn, jastInstruction, tc) != 0L) {
            emitInstruction(insn, method, m, tc)
        }
        else if (Ops.istype(insn, jastIndy, tc) != 0L) {
            emitInvokeDynamic(insn, m, tc)
        }
        else if (Ops.istype(insn, jastInstructionList, tc) != 0L) {
            val iter = Ops.iter(Ops.getattr(insn, jastInstructionList, "@!instructions", 0, tc), tc)
            while (Ops.istrue(iter, tc) != 0L) {
                compileInstruction(iter.shift_boxed(tc)!!, method, m, tc)
            }
        }
        else {
            throw Exception("Unknown JAST::Node in @!instructions: " + Ops.typeName(insn, tc))
        }
    }

    @Throws(Exception::class)
    private fun emitInstruction(insn: SixModelObject, method: JastMethod, m: MethodVisitor, tc: ThreadContext) {
        val instruction = Ops.getattr_i(insn, jastInstruction, "\$!op", 0, tc).toInt()
        val args = Ops.getattr(insn, jastInstruction, "@!args", 1, tc)

        // Go by instruction.
        when (instruction) {
            0x00, // nop
            0x01, //aconst_null
            0x02, // iconst_m1
            0x03, // iconst_0
            0x04, // iconst_1
            0x05, // iconst_2
            0x06, // iconst_3
            0x07, // iconst_4
            0x08, // iconst_5
            0x09, // lconst_0
            0x0a, // lconst_1
            0x0b, // fconst_0
            0x0c, // fconst_1
            0x0d, // fconst_2
            0x0e, // dconst_0
            0x0f -> // dconst_1
                m.visitInsn(instruction)
            0x12 -> { // ldc
                // XXX: only supporting String constants currently
                val constant = Ops.atpos(args, 0, tc)!!.get_str(tc)
                m.visitLdcInsn(constant)
            }
            0x15, // iload
            0x16, // lload
            0x17, // fload
            0x18, // dload
            0x19 -> { // aload
                val name = Ops.atpos(args, 0, tc)!!.get_str(tc)
                if (method.locals.containsKey(name))
                    m.visitVarInsn(instruction, method.locals[name]!!.index)
                else
                    throw Exception("Undeclared local variable: $name")
            }
            0x1a -> // iload_0
                m.visitVarInsn(Opcodes.ILOAD, 0)
            0x1b -> // iload_1
                m.visitVarInsn(Opcodes.ILOAD, 1)
            0x1c -> // iload_2
                m.visitVarInsn(Opcodes.ILOAD, 2)
            0x1d -> // iload_3
                m.visitVarInsn(Opcodes.ILOAD, 3)
            0x1e -> // lload_0
                m.visitVarInsn(Opcodes.LLOAD, 0)
            0x1f -> // lload_1
                m.visitVarInsn(Opcodes.LLOAD, 1)
            0x20 -> // lload_2
                m.visitVarInsn(Opcodes.LLOAD, 2)
            0x21 -> // lload_3
                m.visitVarInsn(Opcodes.LLOAD, 3)
            0x22 -> // fload_0
                m.visitVarInsn(Opcodes.FLOAD, 0)
            0x23 -> // fload_1
                m.visitVarInsn(Opcodes.FLOAD, 1)
            0x24 -> // fload_2
                m.visitVarInsn(Opcodes.FLOAD, 2)
            0x25 -> // fload_3
                m.visitVarInsn(Opcodes.FLOAD, 3)
            0x26 -> // dload_0
                m.visitVarInsn(Opcodes.DLOAD, 0)
            0x27 -> // dload_1
                m.visitVarInsn(Opcodes.DLOAD, 1)
            0x28 -> // dload_2
                m.visitVarInsn(Opcodes.DLOAD, 2)
            0x29 -> // dload_3
                m.visitVarInsn(Opcodes.DLOAD, 3)
            0x2a -> // aload_0
                m.visitVarInsn(Opcodes.ALOAD, 0)
            0x2b -> // aload_1
                m.visitVarInsn(Opcodes.ALOAD, 1)
            0x2c -> // aload_2
                m.visitVarInsn(Opcodes.ALOAD, 2)
            0x2d -> // aload_3
                m.visitVarInsn(Opcodes.ALOAD, 3)
            0x2e, // iaload
            0x2f, // laload
            0x30, // faload
            0x31, // daload
            0x32, // aaload
            0x33, // baload
            0x34, // caload
            0x35 -> // saload
                m.visitInsn(instruction)
            0x36, // istore
            0x37, // lstore
            0x38, // fstore
            0x39, // dstore
            0x3a -> { // astore
                val name = Ops.atpos(args, 0, tc)!!.get_str(tc)
                if (method.locals.containsKey(name))
                    m.visitVarInsn(instruction, method.locals[name]!!.index)
                else
                    throw Exception("Undeclared local variable: $name")
            }
            0x3b -> // istore_0
                m.visitVarInsn(Opcodes.ISTORE, 0)
            0x3c -> // istore_1
                m.visitVarInsn(Opcodes.ISTORE, 1)
            0x3d -> // istore_2
                m.visitVarInsn(Opcodes.ISTORE, 2)
            0x3e -> // istore_3
                m.visitVarInsn(Opcodes.ISTORE, 3)
            0x3f -> // lstore_0
                m.visitVarInsn(Opcodes.LSTORE, 0)
            0x40 -> // lstore_1
                m.visitVarInsn(Opcodes.LSTORE, 1)
            0x41 -> // lstore_2
                m.visitVarInsn(Opcodes.LSTORE, 2)
            0x42 -> // lstore_3
                m.visitVarInsn(Opcodes.LSTORE, 3)
            0x43 -> // fstore_0
                m.visitVarInsn(Opcodes.FSTORE, 0)
            0x44 -> // fstore_1
                m.visitVarInsn(Opcodes.FSTORE, 1)
            0x45 -> // fstore_2
                m.visitVarInsn(Opcodes.FSTORE, 2)
            0x46 -> // fstore_3
                m.visitVarInsn(Opcodes.FSTORE, 3)
            0x47 -> // dstore_0
                m.visitVarInsn(Opcodes.DSTORE, 0)
            0x48 -> // dstore_1
                m.visitVarInsn(Opcodes.DSTORE, 1)
            0x49 -> // dstore_2
                m.visitVarInsn(Opcodes.DSTORE, 2)
            0x4a -> // dstore_3
                m.visitVarInsn(Opcodes.DSTORE, 3)
            /* NOTE: the four astore_N cases below emit DSTORE in the Java
             * original (a latent copy-paste bug on paths the QAST compiler
             * never emits); faithfully preserved. */
            0x4b -> // astore_0
                m.visitVarInsn(Opcodes.DSTORE, 0)
            0x4c -> // astore_1
                m.visitVarInsn(Opcodes.DSTORE, 1)
            0x4d -> // astore_2
                m.visitVarInsn(Opcodes.DSTORE, 2)
            0x4e -> // astore_3
                m.visitVarInsn(Opcodes.DSTORE, 3)
            0x4f, // iastore
            0x50, // lastore
            0x51, // fastore
            0x52, // dastore
            0x53, // aastore
            0x54, // bastore
            0x55, // castore
            0x56, // sastore
            0x57, // pop
            0x58, // pop2
            0x59, // dup
            0x5a, // dup_x1
            0x5b, // dup_x2
            0x5c, // dup2
            0x5d, // dup2_x1
            0x5e, // dup2_x2
            0x5f, // swap
            0x60, // iadd
            0x61, // ladd
            0x62, // fadd
            0x63, // dadd
            0x64, // isub
            0x65, // lsub
            0x66, // fsub
            0x67, // dsub
            0x68, // imul
            0x69, // lmul
            0x6a, // fmul
            0x6b, // dmul
            0x6c, // idiv
            0x6d, // ldiv
            0x6e, // fdiv
            0x6f, // ddiv
            0x70, // irem
            0x71, // lrem
            0x72, // frem
            0x73, // drem
            0x74, // ineg
            0x75, // lneg
            0x76, // fneg
            0x77, // dneg
            0x78, // ishl
            0x79, // lshl
            0x7a, // ishr
            0x7b, // lshr
            0x7c, // iushr
            0x7d, // lushr
            0x7e, // iand
            0x7f, // land
            0x80, // ior
            0x81, // lor
            0x82, // ixor
            0x83, // lxor
            0x85, // i2l
            0x86, // i2f
            0x87, // i2d
            0x88, // l2i
            0x89, // l2f
            0x8a, // l2d
            0x8b, // f2i
            0x8c, // f2l
            0x8d, // f2d
            0x8e, // d2i
            0x8f, // d2l
            0x90, // d2f
            0x91, // i2b
            0x92, // i2c
            0x93, // i2s
            0x94, // lcmp
            0x95, // fcmpl
            0x96, // fcmpg
            0x97, // dcmpl
            0x98 -> // dcmpg
                m.visitInsn(instruction)
            0x99, // ifeq
            0x9a, // ifne
            0x9b, // iflt
            0x9c, // ifge
            0x9d, // ifgt
            0x9e, // ifle
            0x9f, // if_icmpeq
            0xa0, // if_icmpne
            0xa1, // if_icmplt
            0xa2, // if_icmpge
            0xa3, // if_icmpgt
            0xa4, // if_icmple
            0xa5, // if_acmpeq
            0xa6, // if_acmpne
            0xa7 -> // goto
                emitBranchInstruction(method, m, Ops.getattr_s(Ops.atpos(args, 0, tc), jastLabel, "\$!name", 0, tc), instruction)
            0xaa -> // tableswitch
                emitTableSwitchInstruction(method, m, args!!, tc)
            0xac, // ireturn
            0xad, // lreturn
            0xae, // freturn
            0xaf, // dreturn
            0xb0, // areturn
            0xb1 -> // return
                m.visitInsn(instruction)
            0xb2, // getstatic
            0xb3, // putstatic
            0xb4, // getfield
            0xb5 -> // putfield
                emitFieldAccess(m, args!!, instruction, tc)
            0xb6, // invokevirtual
            0xb7, // invokespecial
            0xb8 -> // invokestatic
                emitCall(m, args!!, instruction, tc)
            0xba ->
                throw Exception("Encountered invokedynamic in emitInstruction. This should never happen.")
            0xbb, // new
            0xc0, // checkcast
            0xc1 -> { // instanceof
                val t = processType(Ops.atpos(args, 0, tc)!!.get_str(tc)!!)
                m.visitTypeInsn(instruction, t.getInternalName())
            }
            0xbc -> { // newarray
                val name = Ops.atpos(args, 0, tc)!!.get_str(tc)
                val type: Int
                if (name == "Integer")
                    type = Opcodes.T_INT
                else if (name == "Long")
                    type = Opcodes.T_LONG
                else if (name == "Double")
                    type = Opcodes.T_DOUBLE
                else if (name == "Boolean")
                    type = Opcodes.T_BOOLEAN
                else if (name == "J" || name == "Long")
                    type = Opcodes.T_LONG
                else if (name == "Byte")
                    type = Opcodes.T_BYTE
                else
                    throw RuntimeException("Unknown native array type")
                m.visitIntInsn(Opcodes.NEWARRAY, type)
            }
            0xbd -> // anewarray
                m.visitTypeInsn(Opcodes.ANEWARRAY, processType(Ops.atpos(args, 0, tc)!!.get_str(tc)!!).getInternalName())
            0xbe -> // arraylength
                m.visitInsn(Opcodes.ARRAYLENGTH)
            0xbf -> // athrow
                m.visitInsn(Opcodes.ATHROW)
            0xc6, // ifnull
            0xc7, // ifnonnull
            0xc8 -> // goto_w
                emitBranchInstruction(method, m, Ops.getattr_s(Ops.atpos(args, 0, tc), jastLabel, "\$!name", 0, tc), instruction)
            else ->
                throw Exception("Unrecognized instruction #$instruction")
        }
    }

    private fun emitBranchInstruction(method: JastMethod, m: MethodVisitor, label: String?, icode: Int) {
        if (!method.labels.containsKey(label))
            method.labels.put(label, LabelInfo())
        m.visitJumpInsn(icode, method.labels[label]!!.label)
    }

    private fun emitTableSwitchInstruction(method: JastMethod, m: MethodVisitor, args: SixModelObject, tc: ThreadContext) {
        val numKeys = args.elems(tc).toInt()
        val labels = arrayOfNulls<Label>(numKeys - 1)
        var defaultLabel: Label? = null

        for (i in 0 until numKeys) {
            val key = Ops.getattr_s(Ops.atpos(args, i.toLong(), tc), jastLabel, "\$!name", 0, tc)
            if (!method.labels.containsKey(key)) {
                method.labels.put(key, LabelInfo())
            }
            if (i == 0) {
                defaultLabel = method.labels[key]!!.label
            } else {
                labels[i - 1] = method.labels[key]!!.label
            }
        }
        @Suppress("UNCHECKED_CAST")
        m.visitTableSwitchInsn(0, labels.size - 1, defaultLabel, *(labels as Array<Label>))
    }

    private fun emitFieldAccess(m: MethodVisitor, args: SixModelObject, accessType: Int, tc: ThreadContext) {
        val classType = processType(Ops.atpos(args, 0, tc)!!.get_str(tc)!!)
        val fieldName = Ops.atpos(args, 1, tc)!!.get_str(tc)
        val fieldType = processType(Ops.atpos(args, 2, tc)!!.get_str(tc)!!)
        m.visitFieldInsn(accessType, classType.getInternalName(), fieldName, fieldType.getDescriptor())
    }

    private fun emitCall(m: MethodVisitor, args: SixModelObject, callType: Int, tc: ThreadContext) {
        val argLen = args.elems(tc).toInt()
        val targetType = processType(Ops.atpos(args, 0, tc)!!.get_str(tc)!!)
        val methodName = Ops.atpos(args, 1, tc)!!.get_str(tc)
        val returnType = processType(Ops.atpos(args, 2, tc)!!.get_str(tc)!!)
        val argumentTypes = arrayOfNulls<Type>(argLen - 3)
        for (i in 3 until argLen)
            argumentTypes[i - 3] = processType(Ops.atpos(args, i.toLong(), tc)!!.get_str(tc)!!)
        @Suppress("UNCHECKED_CAST")
        m.visitMethodInsn(callType, targetType.getInternalName(), methodName,
                Type.getMethodDescriptor(returnType, *(argumentTypes as Array<Type>)))
    }

    /* Counts the constant pool entries that take a resolved-references slot:
     * Class, String, MethodHandle, MethodType and Dynamic. Reads the emitted
     * bytes rather than asking ASM, which does not expose its pool. */
    private fun countResolvedReferences(bytes: ByteArray): Int {
        fun u1(at: Int) = bytes[at].toInt() and 0xff
        fun u2(at: Int) = (u1(at) shl 8) or u1(at + 1)
        val count = u2(8)
        var at = 10
        var refs = 0
        var i = 1
        while (i < count) {
            when (val tag = u1(at)) {
                1 -> at += 3 + u2(at + 1)                       /* Utf8 */
                7, 8, 16, 19, 20 -> { at += 3; if (tag != 19 && tag != 20) refs++ }
                15 -> { at += 4; refs++ }                        /* MethodHandle */
                17 -> { at += 5; refs++ }                        /* Dynamic */
                18 -> at += 5                                    /* InvokeDynamic: counted per instruction */
                3, 4, 9, 10, 11, 12 -> at += 5
                5, 6 -> { at += 9; i++ }                         /* Long/Double take two slots */
                else -> throw RuntimeException("Unknown constant pool tag $tag")
            }
            i++
        }
        return refs
    }

    /* Per-class invokedynamic instruction count; see the limit check after
     * class assembly. */
    private var indyCount = 0

    /* Which bootstrap methods the indy sites go to, so an over-limit class can
     * say where its call sites actually come from. */
    private val indyByBsm = HashMap<String, Int>()


    private fun emitInvokeDynamic(insn: SixModelObject, m: MethodVisitor, tc: ThreadContext) {
        indyCount++
        val name = Ops.getattr(insn, jastIndy, "\$!name", 0, tc)!!.get_str(tc)
        val argTypesSmo = Ops.getattr(insn, jastIndy, "@!arg_types", 1, tc)
        val retType = processType(Ops.getattr(insn, jastIndy, "\$!ret_type", 2, tc)!!.get_str(tc)!!)
        val bsmType = Ops.getattr(insn, jastIndy, "\$!bsm_type", 3, tc)!!.get_str(tc)
        val bsmName = Ops.getattr(insn, jastIndy, "\$!bsm_name", 4, tc)!!.get_str(tc)
        indyByBsm.merge(bsmName ?: "?", 1, Int::plus)
        val extraArgsSmo = Ops.getattr(insn, jastIndy, "@!extra_args", 5, tc)

        val numArgs = argTypesSmo!!.elems(tc).toInt()
        val argTypes = arrayOfNulls<Type>(numArgs)
        for (i in 0 until numArgs) {
            argTypes[i] = processType(Ops.atpos(argTypesSmo, i.toLong(), tc)!!.get_str(tc)!!)
        }

        val numExtraArgs = extraArgsSmo!!.elems(tc).toInt()
        var bsmMT = MethodType.methodType(CallSite::class.java, MethodHandles.Lookup::class.java,
                java.lang.String::class.java, MethodType::class.java)

        val extraArgs = arrayOfNulls<Any>(numExtraArgs)
        for (i in 0 until numExtraArgs) {
            val extra = Ops.atpos(extraArgsSmo, i.toLong(), tc)
            if (Ops.istype(extra, jastPushI, tc) != 0L) {
                extraArgs[i] = Ops.getattr_i(extra, jastPushI, "\$!value", 0, tc)
                bsmMT = bsmMT.appendParameterTypes(java.lang.Long.TYPE)
            }
            else if (Ops.istype(extra, jastPushN, tc) != 0L) {
                extraArgs[i] = Ops.getattr_n(extra, jastPushN, "\$!value", 0, tc)
                bsmMT = bsmMT.appendParameterTypes(java.lang.Double.TYPE)
            }
            else if (Ops.istype(extra, jastPushS, tc) != 0L) {
                extraArgs[i] = Ops.getattr_s(extra, jastPushS, "\$!value", 0, tc)
                bsmMT = bsmMT.appendParameterTypes(String::class.java)
            }
            else if (Ops.istype(extra, jastPushIdx, tc) != 0L) {
                extraArgs[i] = Ops.getattr_i(extra, jastPushIdx, "\$!value", 0, tc).toInt()
                bsmMT = bsmMT.appendParameterTypes(Integer.TYPE)
            }
            else {
                throw RuntimeException("Unrecognized extra argument for invokedynamic")
            }
        }

        val bsmHandle = Handle(Opcodes.H_INVOKESTATIC, bsmType, bsmName, bsmMT.toMethodDescriptorString())
        m.visitInvokeDynamicInsn(name, Type.getMethodDescriptor(retType, *(argTypes as Array<Type>)), bsmHandle, *extraArgs)
    }

    class LabelInfo {
        @JvmField val label = Label()
        @JvmField var defined = false
    }

    class VariableDef(
        @JvmField val index: Int,
        @JvmField val type: String,
        @JvmField val start: Label,
        @JvmField val end: Label,
    )
}
