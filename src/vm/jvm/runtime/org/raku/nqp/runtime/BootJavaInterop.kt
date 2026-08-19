package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Array as JavaArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.ArrayList
import java.util.HashMap

import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.JavaObjectWrapper

import org.raku.nqp.jast2bc.BytecodeVersion
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Factory for Java object interop wrappers.  This class is designed to be
 * subclassed by HLLs.  Not shareable between [GlobalContext]s.  Interop
 * factories should generally be treated as singletons, because constructed
 * wrappers and types cannot be shared between them.
 */
@Suppress("DEPRECATION")
open class BootJavaInterop(gc: GlobalContext) {

    /**
     * Set this to a non-null value to use the same STable for every class.
     */
    @JvmField protected var commonSTable: STable? = null

    /** The global context that this interop factory is used for. */
    @JvmField protected var gc: GlobalContext = gc

    /** If we need to load stuff from a JAR, the class loader for doing so. */
    @JvmField protected var jarClassLoaders: HashMap<String, URLClassLoader> = HashMap()

    private class InteropInfo {
        @JvmField var forClass: Class<*>? = null
        @JvmField var interop: SixModelObject? = null
        @JvmField var stable: STable? = null // not used if commonSTable != null
    }

    private val cache = object : ClassValue<InteropInfo>() {
        override fun computeValue(cl: Class<*>): InteropInfo {
            val tc = this@BootJavaInterop.gc.getCurrentThreadContext()!!
            val r = InteropInfo()
            r.forClass = cl
            r.interop = computeInterop(tc, cl)
            r.stable = computeSTable(tc, cl, r.interop!!)
            return r
        }
    }

    /**
     * Override this to define per-class STables.
     */
    protected open fun computeSTable(tc: ThreadContext, klass: Class<*>, interop: SixModelObject): STable? {
        return interop.at_key_boxed(tc, "/TYPE/")!!.st
    }

    /**
     * Get STable for class, computing if necessary.  You probably want to
     * override [computeSTable] instead of this.
     */
    open fun getSTableForClass(c: Class<*>): STable? {
        return commonSTable ?: cache.get(c).stable
    }

    /** Get interop table for a class. */
    open fun getInteropForClass(clazz: Class<*>): SixModelObject? {
        return cache.get(clazz).interop
    }

    /** Main entry point for OO-ish callouts. */
    open fun typeForName(name: String): SixModelObject? {
        val classLoader = ClassLoader.getSystemClassLoader()
        val clazz = try {
            classLoader.loadClass(name)
        }
        catch (cnfException: ClassNotFoundException) {
            throw ExceptionHandling.dieInternal(gc.getCurrentThreadContext()!!, cnfException)
        }
        return getSTableForClass(clazz)!!.WHAT
    }

    /** Convenience methods for NQP coding. */
    open fun currentGC(): GlobalContext {
        return gc
    }

    open fun currentTC(): ThreadContext? {
        return gc.getCurrentThreadContext()
    }

    protected open fun unboxClass(to: SixModelObject): Class<*> {
        if (to is JavaObjectWrapper) {
            val o = to.theObject
            if (o is Class<*>) return o
        }
        try {
            return Class.forName(Ops.unbox_s(to, gc.getCurrentThreadContext()!!))
        } catch (e: ClassNotFoundException) {
            throw ExceptionHandling.dieInternal(gc.getCurrentThreadContext()!!, e)
        }
    }

    /**
     * Entry point for callouts.
     */
    open fun getInterop(to: SixModelObject): SixModelObject? {
        return getInteropForClass(unboxClass(to))
    }

    /** Entry point for callback setup. */
    open fun implementClass(description: SixModelObject): SixModelObject {
        val tc = gc.getCurrentThreadContext()!!
        // unpack the list-of-lists
        val rows = arrayOfNulls<Array<SixModelObject?>>(description.elems(tc).toInt())
        for (i in rows.indices) {
            val rawRow = description.at_pos_boxed(tc, i.toLong())!!
            val row = arrayOfNulls<SixModelObject>(rawRow.elems(tc).toInt())
            rows[i] = row
            for (j in row.indices)
                row[j] = rawRow.at_pos_boxed(tc, j.toLong())
        }

        var rptr = 0
        val cc = ClassContext()
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        val className = "org/raku/nqp/generatedclass/" + description.hashCode()
        cc.className = className
        cc.cv = cw

        var superclass = "java/lang/Object"
        val ifaces = ArrayList<String>()

        if (matchName(tc, rows, rptr, "extends"))
            superclass = Ops.unbox_s(rows[rptr++]!![1], tc)!!.replace('.', '/')

        while (matchName(tc, rows, rptr, "implements"))
            ifaces.add(Ops.unbox_s(rows[rptr++]!![1], tc)!!.replace('.', '/'))

        cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null,
                superclass, ifaces.toTypedArray())
        cw.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC, "constants", "[Ljava/lang/Object;", null, null).visitEnd()

        // TODO if needed: source, outer class, (annotation | attribute)*

        // TODO if needed: constructors, fields, inner classes
        while (rptr < rows.size) {
            if (matchName(tc, rows, rptr, "instance_method")) {
                rptr = methodCallin(tc, cc, rows, rptr, false)
            } else if (matchName(tc, rows, rptr, "static_method")) {
                rptr = methodCallin(tc, cc, rows, rptr, true)
            } else {
                throw RuntimeException("confused at index $rptr")
            }
        }

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        finishClass(cc)
        return RuntimeSupport.boxJava(cc.constructed, getSTableForClass(Class::class.java))
    }

    /** Hiding arbitrary 6model objects under Object, for working with
      * untyped collection classes, etc. */
    open fun sixmodelToJavaObject(smo: SixModelObject): SixModelObject {
        return RuntimeSupport.boxJava(smo, getSTableForClass(Object::class.java))
    }
    open fun javaObjectToSixmodel(javaObj: SixModelObject): SixModelObject {
        return RuntimeSupport.unboxJava(javaObj) as SixModelObject
    }

    protected open fun finishClass(cc: ClassContext) {
        cc.cv!!.visitEnd()

        val bits = cc.cv!!.toByteArray()
        if (System.getenv("NQP_DEBUG_DUMP_CLASSFILES") != null) {
            try {
                java.nio.file.Files.write(
                    java.io.File(cc.className!!.replace('/', '_') + ".class").toPath(), bits)
            } catch (e: java.io.IOException) {
            }
        }
        // XXX: The condition here can probably cut down a few more
        // allocations if we check if the target's class loader isn't in the
        // chain of loaders above gc.byteClassLoader.
        val loader = if (cc.target == null)
            gc.byteClassLoader
        else
            ByteClassLoader(javaClass.getClassLoader())
        cc.constructed = loader.defineClass(cc.className!!.replace('/', '.'), bits)
        try {
            cc.constructed!!.getField("constants").set(null, cc.constants.toTypedArray())
        } catch (roe: ReflectiveOperationException) {
            throw RuntimeException(roe)
        }
    }

    /** Helper method for parsing descriptions in [implementClass]. */
    protected open fun matchName(tc: ThreadContext, rows: Array<Array<SixModelObject?>?>, rptr: Int, name: String): Boolean {
        return rptr < rows.size && rows[rptr]!!.size > 0 && name == Ops.unbox_s(rows[rptr]!![0], tc)
    }

    // begin gory details
    /** Constructs interop objects for a class.  Override this if you need something other than a hash. */
    protected open fun computeInterop(tc: ThreadContext, klass: Class<*>): SixModelObject {
        val adaptor = createAdaptor(klass)

        val adaptorUnit: CompilationUnit
        try {
            adaptorUnit = adaptor.constructed!!.newInstance() as CompilationUnit
        } catch (roe: ReflectiveOperationException) {
            throw RuntimeException(roe)
        }
        adaptorUnit.initializeCompilationUnit(tc)

        val hash = gc.BOOTHash!!.st.REPR.allocate(tc, gc.BOOTHash!!.st)

        val names = HashMap<String, SixModelObject?>()

        for (i in 0 until adaptor.descriptors.size) {
            val desc = adaptor.descriptors[i]
            val cr: SixModelObject? = adaptorUnit.lookupCodeRef(i)

            val s1 = desc.indexOf('/')
            val s2 = desc.indexOf('/', s1 + 1)

            val shorten = desc.substring(s1 + 1, s2)
            names.put(shorten, if (names.containsKey(shorten)) null else cr)
            names.put(desc, cr)
        }

        val it = names.entries.iterator()
        while (it.hasNext()) {
            val ent = it.next()
            if (Ops.isnull(ent.value) == 0L)
                hash.bind_key_boxed(tc, ent.key, ent.value)
            else
                it.remove()
        }

        val protoSt = gc.BOOTJava!!.st
        val freshType = protoSt.REPR.type_object_for(tc, computeHOW(tc, klass.getName()))
        freshType.st.MethodCache = names
        freshType.st.ModeFlags = freshType.st.ModeFlags or STable.METHOD_CACHE_AUTHORITATIVE

        hash.bind_key_boxed(tc, "/TYPE/", freshType)

        return hash
    }

    /** Produces a meta-object for a Java type. Override this to have something
      * other than the BOOTJava one. */
    protected open fun computeHOW(tc: ThreadContext, name: String): SixModelObject? {
        return gc.BOOTJava!!.st.HOW
    }

    /** Handles class construction for adaptors. */
    protected open fun createAdaptor(target: Class<*>): ClassContext {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        val className = "org/raku/nqp/generatedadaptor/" + target.getName().replace('.', '/')
        cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null, TYPE_CU.getInternalName(), null)

        cw.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC, "constants", "[Ljava/lang/Object;", null, null).visitEnd()

        val cc = ClassContext()
        cc.cv = cw
        cc.className = className
        cc.target = target

        for (m in target.getMethods()) createAdaptorMethod(cc, m)
        for (f in target.getFields()) createAdaptorField(cc, f)
        for (c in target.getConstructors()) createAdaptorConstructor(cc, c)
        createAdaptorSpecials(cc)
        compunitMethods(cc)

        finishClass(cc)
        return cc
    }

    protected open fun compunitMethods(c: ClassContext) {
        val cw = c.cv!!
        var mv: MethodVisitor
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCallSites", "()[Lorg/raku/nqp/runtime/CallSiteDescriptor;", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hllName", "()Ljava/lang/String;", null, null)
        mv.visitCode()
        mv.visitLdcInsn("")
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/raku/nqp/runtime/CompilationUnit", "<init>", "()V")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Override this to customize the calling convention for method adaptors. */
    protected open fun createAdaptorMethod(c: ClassContext, tobind: Method) {
        val ptype = tobind.getParameterTypes()
        val isStatic = Modifier.isStatic(tobind.getModifiers())

        val desc = Type.getMethodDescriptor(tobind)
        val cc = startCallout(c, ptype.size + 1, "method/" + tobind.getName() + "/" + desc)

        var parix = 1
        preMarshalIn(cc, tobind.getReturnType(), 0)
        if (!isStatic) marshalOut(cc, tobind.getDeclaringClass(), 0)
        for (pt in ptype) marshalOut(cc, pt, parix++)
        cc.mv!!.visitMethodInsn(if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL, Type.getInternalName(tobind.getDeclaringClass()), tobind.getName(), desc)
        marshalIn(cc, tobind.getReturnType(), 0)

        endCallout(cc)
    }

    /** Override this to customize the calling convention for field adaptors. */
    protected open fun createAdaptorField(c: ClassContext, f: Field) {
        val isStatic = Modifier.isStatic(f.getModifiers())
        var cc: MethodContext

        cc = startCallout(c, 1, "field/get_" + f.getName() + "/" + Type.getDescriptor(f.getType()))
        preMarshalIn(cc, f.getType(), 0)
        if (!isStatic) marshalOut(cc, f.getDeclaringClass(), 0)
        cc.mv!!.visitFieldInsn(if (isStatic) Opcodes.GETSTATIC else Opcodes.GETFIELD, Type.getInternalName(f.getDeclaringClass()), f.getName(), Type.getDescriptor(f.getType()))
        marshalIn(cc, f.getType(), 0)
        endCallout(cc)

        if (!Modifier.isFinal(f.getModifiers())) {
            cc = startCallout(c, 2, "field/set_" + f.getName() + "/" + Type.getDescriptor(f.getType()))
            preMarshalIn(cc, Void.TYPE, 0)
            if (!isStatic) marshalOut(cc, f.getDeclaringClass(), 0)
            marshalOut(cc, f.getType(), 1)
            cc.mv!!.visitFieldInsn(if (isStatic) Opcodes.PUTSTATIC else Opcodes.PUTFIELD, Type.getInternalName(f.getDeclaringClass()), f.getName(), Type.getDescriptor(f.getType()))
            marshalIn(cc, Void.TYPE, 0)
            endCallout(cc)
        }
    }

    /** Override this to customize the calling convention for constructor adaptors. */
    protected open fun createAdaptorConstructor(c: ClassContext, k: Constructor<*>) {
        val ptypes = k.getParameterTypes()
        val desc = Type.getConstructorDescriptor(k)
        val cc = startCallout(c, ptypes.size + 1, "constructor/new/$desc")
        var parix = 1
        preMarshalIn(cc, k.getDeclaringClass(), 0)
        cc.mv!!.visitTypeInsn(Opcodes.NEW, Type.getInternalName(k.getDeclaringClass()))
        cc.mv!!.visitInsn(Opcodes.DUP)
        for (p in ptypes) marshalOut(cc, p, parix++)
        cc.mv!!.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(k.getDeclaringClass()), "<init>", desc)
        marshalIn(cc, k.getDeclaringClass(), 0)
        endCallout(cc)
    }

    /** Override this to add or customize special adaptors not tied to specific fields. */
    protected open fun createAdaptorSpecials(c: ClassContext) {
        // odds and ends like early bound array stuff, isinst, nondefault marshalling...
        var cc = startCallout(c, 2, "/box/")
        preMarshalIn(cc, Object::class.java, 0)
        marshalOut(cc, c.target!!, 1)
        // implicit widening conversion to Object
        marshalIn(cc, Object::class.java, 0)
        endCallout(cc)

        cc = startCallout(c, 2, "/unbox/")
        preMarshalIn(cc, c.target!!, 0)
        marshalOut(cc, Object::class.java, 1)
        cc.mv!!.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(c.target))
        marshalIn(cc, c.target!!, 0)
        endCallout(cc)

        cc = startCallout(c, 2, "/isinst/")
        preMarshalIn(cc, java.lang.Boolean.TYPE, 0)
        marshalOut(cc, Object::class.java, 1)
        cc.mv!!.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(c.target))
        marshalIn(cc, java.lang.Boolean.TYPE, 0)
        endCallout(cc)
    }

    // [ "instance_method", "name", "descriptor", sub () {} ]
    /** Override this to customize generation of callin methods. */
    protected open fun methodCallin(tc: ThreadContext, c: ClassContext, rows: Array<Array<SixModelObject?>?>, rptrIn: Int, isStatic: Boolean): Int {
        var rptr = rptrIn
        val row = rows[rptr++]!!
        if (row.size != 4) throw ExceptionHandling.dieInternal(tc, "instance_method requires 3 arguments")
        val name = Ops.unbox_s(row[1], tc)
        val desc = Type.getMethodType(Ops.unbox_s(row[2], tc))
        val mc = startCallin(c, if (isStatic) Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC else Opcodes.ACC_PUBLIC, name!!, desc)
        val mv = mc.mv!!

        val ret = desc.getReturnType()
        val parm = desc.getArgumentTypes()

        val cbArgs = arrayOfNulls<Class<*>>(parm.size + (if (isStatic) 0 else 1))
        var aidx = 0
        if (!isStatic) cbArgs[aidx++] = Object::class.java // XXX we can't properly marshal here because the class doesn't exist yet!
        for (p in parm) cbArgs[aidx++] = typeToClass(p)

        setupCallback(mc, row[3], null, cbArgs)

        var lidx = 0
        for (i in cbArgs.indices) {
            val arg = cbArgs[i]!!
            val ty = Type.getType(arg)
            preMarshalIn(mc, arg, i)
            mv.visitVarInsn(ty.getOpcode(Opcodes.ILOAD), lidx)
            lidx += ty.getSize()
            marshalIn(mc, arg, i)
        }

        fireCallback(mc)

        marshalOut(mc, typeToClass(ret), 0)
        mc.mv!!.visitInsn(ret.getOpcode(Opcodes.IRETURN))

        endCallin(mc)
        return rptr
    }

    /**
     * Attempt to resolve a type name in a callin signature to a type for
     * marshalling.  This is icky factoring, we should either marshal by type
     * name or pass actual types when building callins.  The former option
     * makes subclass-sensitive marshalling tricky and the latter prevents
     * recursively referencing callin classes, though.
     */
    protected open fun typeToClass(t: Type): Class<*> {
        return when (t.getSort()) {
            Type.ARRAY, Type.OBJECT ->
                try {
                    Class.forName(t.getClassName()) // TODO: classloader selection
                } catch (e: ClassNotFoundException) {
                    throw RuntimeException(e)
                }
            Type.BOOLEAN -> java.lang.Boolean.TYPE
            Type.BYTE -> java.lang.Byte.TYPE
            Type.CHAR -> Character.TYPE
            Type.DOUBLE -> java.lang.Double.TYPE
            Type.FLOAT -> java.lang.Float.TYPE
            Type.INT -> Integer.TYPE
            Type.LONG -> java.lang.Long.TYPE
            Type.SHORT -> java.lang.Short.TYPE
            Type.VOID -> Void.TYPE
            else -> throw RuntimeException("impossible type in typeToClass")
        }
    }

    /**
     * The primitive a given Java type marshals as. Override this to
     * customize marshalling. You will probably only need to change this if
     * you want to make char or boolean come in as objects.
     */
    protected open fun storageForType(what: Class<*>): BoxedPrimitive {
        return if (what == String::class.java || what == Character.TYPE)
            BoxedPrimitive.STR
        else if (what == java.lang.Float.TYPE || what == java.lang.Double.TYPE)
            BoxedPrimitive.NUM
        else if (what != Void.TYPE && what.isPrimitive())
            BoxedPrimitive.INT
        else
            BoxedPrimitive.NONE
    }

    /** Generates "early" code for a marshal-in, such as `new` opcodes.  Override this to customize marshalling. */
    protected open fun preMarshalIn(c: MethodContext, what: Class<*>, ix: Int) {
        preEmitPutToNQP(c, ix, storageForType(what))
    }

    /** Generates "late" code for a marshal-in.  Override this to customize marshalling. */
    protected open fun marshalIn(c: MethodContext, what: Class<*>, ix: Int) {
        if (what == Void.TYPE) {
            c.mv!!.visitInsn(Opcodes.ACONST_NULL)
        }
        else if (what == Integer.TYPE || what == java.lang.Short.TYPE || what == java.lang.Byte.TYPE || what == java.lang.Boolean.TYPE) {
            c.mv!!.visitInsn(Opcodes.I2L)
        }
        else if (what == java.lang.Long.TYPE || what == java.lang.Double.TYPE || what == String::class.java || what == SixModelObject::class.java) {
            // already in needed form
        }
        else if (what == java.lang.Float.TYPE) {
            c.mv!!.visitInsn(Opcodes.F2D)
        }
        else if (what == Character.TYPE) {
            c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(C)Ljava/lang/String;")
        }
        else {
            val commonSTable = this.commonSTable
            if (commonSTable != null) {
                emitConst(c, commonSTable, STable::class.java)
            } else {
                emitConst(c, STableCache(what), STableCache::class.java)
                c.mv!!.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(STableCache::class.java), "getSTable", "()Lorg/raku/nqp/sixmodel/STable;")
            }
            c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/BootJavaInterop\$RuntimeSupport", "boxJava", Type.getMethodDescriptor(TYPE_SMO, TYPE_OBJ, TYPE_ST))
        }

        emitPutToNQP(c, ix, storageForType(what))
    }

    /**
     * Generates code for a marshal-out (NQP to Java).
     */
    protected open fun marshalOut(c: MethodContext, what: Class<*>, ix: Int) {
        emitGetFromNQP(c, ix, storageForType(what))
        val mv = c.mv!!

        if (what == Void.TYPE) {
            mv.visitInsn(Opcodes.POP)
        }
        else if (what == java.lang.Long.TYPE || what == java.lang.Double.TYPE || what == String::class.java || what == SixModelObject::class.java) {
            // already in needed form
        }
        else if (what == Integer.TYPE || what == java.lang.Short.TYPE || what == java.lang.Byte.TYPE || what == java.lang.Boolean.TYPE) {
            mv.visitInsn(Opcodes.L2I)
            if (what == java.lang.Short.TYPE) mv.visitInsn(Opcodes.I2S)
            else if (what == java.lang.Byte.TYPE) mv.visitInsn(Opcodes.I2B)
            else if (what == java.lang.Boolean.TYPE) {
                val f = Label()
                val e = Label() // ugh, but this is what javac does for != 0
                mv.visitJumpInsn(Opcodes.IFEQ, f)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitJumpInsn(Opcodes.GOTO, e)
                mv.visitLabel(f)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitLabel(e)
            }
        }
        else if (what == java.lang.Float.TYPE)
            mv.visitInsn(Opcodes.D2F)
        else if (what == Character.TYPE) {
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C")
        }
        else if (what == GlobalContext::class.java) {
            val provided = Label()
            val done = Label()
            mv.visitInsn(Opcodes.DUP)
            mv.visitJumpInsn(Opcodes.IFNONNULL, provided)
            mv.visitInsn(Opcodes.POP)
            mv.visitVarInsn(Opcodes.ALOAD, c.tcLoc)
            mv.visitFieldInsn(Opcodes.GETFIELD, TYPE_TC.getInternalName(), "gc", "Lorg/raku/nqp/runtime/GlobalContext;")
            mv.visitJumpInsn(Opcodes.GOTO, done)
            mv.visitLabel(provided)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/BootJavaInterop\$RuntimeSupport", "unboxJava", Type.getMethodDescriptor(TYPE_OBJ, TYPE_SMO))
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(what))
            mv.visitLabel(done)
        }
        else if (what == ThreadContext::class.java) {
            val provided = Label()
            val done = Label()
            mv.visitInsn(Opcodes.DUP)
            mv.visitJumpInsn(Opcodes.IFNONNULL, provided)
            mv.visitInsn(Opcodes.POP)
            mv.visitVarInsn(Opcodes.ALOAD, c.tcLoc)
            mv.visitJumpInsn(Opcodes.GOTO, done)
            mv.visitLabel(provided)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/BootJavaInterop\$RuntimeSupport", "unboxJava", Type.getMethodDescriptor(TYPE_OBJ, TYPE_SMO))
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(what))
            mv.visitLabel(done)
        }
        // array cases
        else if (what.componentType != null) {
            mv.visitVarInsn(Opcodes.ALOAD, c.tcLoc)
            mv.visitLdcInsn(Type.getType(what))
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/BootJavaInterop", "marshalOutRecursive",
                Type.getMethodDescriptor(Type.getType(Array<Any>::class.java), TYPE_SMO, TYPE_TC, Type.getType(Class::class.java)))
            /* The helper's static return type is too wide for the callee's
             * parameter; without the cast the verifier rejects the adaptor
             * ("Object not assignable to [Ljava/lang/Object;"). */
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(what))
        }
        else {
            val isntWrapped = Label()
            mv.visitInsn(Opcodes.DUP)
            mv.visitVarInsn(Opcodes.ALOAD, c.tcLoc)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "decont", Type.getMethodDescriptor(TYPE_SMO, TYPE_SMO, TYPE_TC))
            mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getType(JavaObjectWrapper::class.java).getInternalName())
            mv.visitJumpInsn(Opcodes.IFEQ, isntWrapped)
            mv.visitVarInsn(Opcodes.ALOAD, c.tcLoc)
            // XXX: the secondary decont is a bit awkward, but storing to the stack doesn't seem to work out
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "decont", Type.getMethodDescriptor(TYPE_SMO, TYPE_SMO, TYPE_TC))
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/BootJavaInterop\$RuntimeSupport", "unboxJava", Type.getMethodDescriptor(TYPE_OBJ, TYPE_SMO))
            mv.visitLabel(isntWrapped)
            /* Cast after the join: with the cast only on the wrapped arm,
             * the verifier merges the two paths to Object and rejects any
             * use of the value at its marshalled type. The not-wrapped arm
             * failing the cast at run time is the correct outcome for a
             * non-Java object where a Java one is needed. */
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(what))
        }
    }

    companion object {
        @JvmStatic
        @Throws(Throwable::class)
        fun castObjectToClass(obj: Any, klass: Class<*>): Any? {
            var retVal: Any? = null

            if (klass.isAssignableFrom(obj.javaClass)) {
                retVal = obj
            }
            else if (klass == java.lang.Boolean::class.java && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long) != 0L
            }
            else if (klass == java.lang.Byte::class.java && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toByte()
            }
            else if (klass == java.lang.Short::class.java && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toShort()
            }
            else if (klass == java.lang.Integer::class.java && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toInt()
            }
            else if (klass == java.lang.Long::class.java && obj.javaClass == java.lang.Long::class.java) {
                retVal = obj as Long
            }
            else if (klass == java.lang.Float::class.java && obj.javaClass == java.lang.Double::class.java) {
                retVal = (obj as Double).toFloat()
            }
            else if (klass == java.lang.Double::class.java && obj.javaClass == java.lang.Double::class.java) {
                retVal = obj as Double
            }
            else if (klass == java.lang.Character::class.java && obj.javaClass == String::class.java) {
                retVal = (obj as String)[0]
            }
            else if (klass == String::class.java && obj.javaClass == String::class.java) {
                retVal = obj as String
            }
            else if (klass == java.lang.Boolean.TYPE && obj.javaClass == java.lang.Long::class.java) {
                retVal = if ((obj as Long) == 0L) java.lang.Boolean.FALSE else java.lang.Boolean.TRUE
            }
            else if (klass == java.lang.Byte.TYPE && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toByte()
            }
            else if (klass == java.lang.Short.TYPE && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toShort()
            }
            else if (klass == Integer.TYPE && obj.javaClass == java.lang.Long::class.java) {
                retVal = (obj as Long).toInt()
            }
            else if (klass == java.lang.Long.TYPE && obj.javaClass == java.lang.Long::class.java) {
                retVal = obj
            }
            else if (klass == Character.TYPE && obj.javaClass == String::class.java) {
                retVal = (obj as String)[0]
            }
            else if (klass == java.lang.Float.TYPE && obj.javaClass == java.lang.Double::class.java) {
                retVal = (obj as Double).toFloat()
            }
            else if (klass == java.lang.Double.TYPE && obj.javaClass == java.lang.Double::class.java) {
                retVal = obj
            }

            return retVal
        }

        /* `open` so the @JvmStatic bridge is emitted non-final: rakudo's
         * RakudoJavaInterop declares its own static marshalOutRecursive
         * (invoked by name from its emitted adaptors), which under Java's
         * rules *hides* this one — and hiding a final static is a
         * compile error. The Java original was a plain (non-final)
         * public static. */
        @JvmStatic
        @Throws(Throwable::class)
        open fun marshalOutRecursive(`in`: SixModelObject, tc: ThreadContext, what: Class<*>?): Any? {
            var out: Any? = null
            val size = Ops.elems(`in`, tc).toInt()
            if (what != null) {
                val arr = JavaArray.newInstance(what.componentType, size)
                out = arr
                for (i in 0 until size) {
                    `in`.at_pos_native(tc, i.toLong())
                    var value: Any?
                    if (tc.nativeType == ThreadContext.NATIVE_NUM) {
                        value = tc.nativeN
                    }
                    else if (tc.nativeType == ThreadContext.NATIVE_STR) {
                        value = tc.nativeS
                    }
                    else if (tc.nativeType == ThreadContext.NATIVE_INT) {
                        value = tc.nativeI
                    }
                    else {
                        val cur = Ops.atpos(`in`, i.toLong(), tc)
                        if (cur is JavaObjectWrapper) {
                            value = RuntimeSupport.unboxJava(cur)
                        } // XXX: probably cases missing here
                        else {
                            value = marshalOutRecursive(cur!!, tc, what.componentType)
                        }
                    }
                    value = castObjectToClass(value!!, what.componentType)
                    JavaArray.set(arr, i, value)
                }
            }
            else {
                // we've hopefully been called from a subclass, so we rely on them for casting and just unbox
                for (i in 0 until size) {
                    `in`.at_pos_native(tc, i.toLong())
                    if (tc.nativeType == ThreadContext.NATIVE_NUM) {
                        if (out == null)
                            out = arrayOfNulls<Double>(size)
                        @Suppress("UNCHECKED_CAST")
                        (out as Array<Double?>)[i] = tc.nativeN
                    }
                    else if (tc.nativeType == ThreadContext.NATIVE_STR) {
                        if (out == null)
                            out = arrayOfNulls<String>(size)
                        @Suppress("UNCHECKED_CAST")
                        (out as Array<String?>)[i] = tc.nativeS
                    }
                    else if (tc.nativeType == ThreadContext.NATIVE_INT) {
                        if (out == null)
                            out = arrayOfNulls<Long>(size)
                        @Suppress("UNCHECKED_CAST")
                        (out as Array<Long?>)[i] = tc.nativeI
                    }
                    else {
                        val cur = Ops.atpos(`in`, i.toLong(), tc)
                        if (cur is JavaObjectWrapper) {
                            @Suppress("UNCHECKED_CAST")
                            (out as Array<Any?>)[i] = RuntimeSupport.unboxJava(cur)
                        } // XXX: probably cases missing here?
                        else {
                            @Suppress("UNCHECKED_CAST")
                            (out as Array<Any?>)[i] = marshalOutRecursive(cur!!, tc, null)
                        }
                    }
                }
            }
            return out
        }

        /** Maps BP_XXX constants to o, i, n, s flags. */
        /* Indexed by BoxedPrimitive.spec, which is why the order is
         * object, int, num, str. */
        @JvmField protected val TYPE_CHAR = charArrayOf('o', 'i', 'n', 's')
        /** Maps BP_XXX constants to ARG_XXX constants. */
        @JvmField protected val TYPE_argflag = byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT, CallSiteDescriptor.ARG_NUM, CallSiteDescriptor.ARG_STR)
        /** Maps BP_XXX constants to type names. */
        @JvmField protected val TYPES = arrayOf(Type.getType(SixModelObject::class.java), Type.LONG_TYPE, Type.DOUBLE_TYPE, Type.getType(String::class.java))
        /** Name of arrays of Object. */
        @JvmField protected val TYPE_AOBJ: Type = Type.getType(Array<Any>::class.java)
        /** Type name of [CallFrame]. */
        @JvmField protected val TYPE_CF: Type = Type.getType(CallFrame::class.java)
        /** Type name of [CodeRef]. */
        @JvmField protected val TYPE_CR: Type = Type.getType(CodeRef::class.java)
        /** Type name of [CallSiteDescriptor]. */
        @JvmField protected val TYPE_CSD: Type = Type.getType(CallSiteDescriptor::class.java)
        /** Type name of [CompilationUnit]. */
        @JvmField protected val TYPE_CU: Type = Type.getType(CompilationUnit::class.java)
        /** Type name of [Object]. */
        @JvmField protected val TYPE_OBJ: Type = Type.getType(Object::class.java)
        /** Type name of [Ops]. */
        @JvmField protected val TYPE_OPS: Type = Type.getType(Ops::class.java)
        /** Type name of [SixModelObject]. */
        @JvmField protected val TYPE_SMO: Type = Type.getType(SixModelObject::class.java)
        /** Type name of [STable]. */
        @JvmField protected val TYPE_ST: Type = Type.getType(STable::class.java)
        /** Type name of [ThreadContext]. */
        @JvmField protected val TYPE_TC: Type = Type.getType(ThreadContext::class.java)
    }

    /** Stores working information while building a class. */
    protected open class ClassContext {
        /** The ASM class writer. */
        @JvmField var cv: ClassWriter? = null
        /** The new class' internal name. */
        @JvmField var className: String? = null
        /** The incomplete list of constants, used by [BootJavaInterop.emitConst]. */
        @JvmField var constants: MutableList<Any> = ArrayList()
        /** The referenced class (for adaptors only). */
        @JvmField var target: Class<*>? = null
        /** The newly minted class. */
        @JvmField var constructed: Class<*>? = null
        /** Adaptor names, in the same order as the qb_NNN indexes, for adaptors only. */
        @JvmField var descriptors: MutableList<String> = ArrayList()
        /** The next qb_NNN index to use. */
        @JvmField var nextCallout: Int = 0
    }

    /** Start an adaptor method and generate standard prologue. */
    protected open fun startCallout(cc: ClassContext, arity: Int, desc: String): MethodContext {
        val mc = MethodContext()
        mc.cc = cc
        val mv = cc.cv!!.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "qb_" + (cc.nextCallout++),
                Type.getMethodDescriptor(Type.VOID_TYPE, TYPE_CU, TYPE_TC, TYPE_CR, TYPE_CSD, TYPE_AOBJ),
                null, null)
        mc.mv = mv
        val av = mv.visitAnnotation("Lorg/raku/nqp/runtime/CodeRefAnnotation;", true)
        av.visit("name", "callout " + cc.target!!.getName() + " " + desc)
        av.visitEnd()
        mv.visitCode()
        cc.descriptors.add(desc)

        mc.argsLoc = 4
        mc.csdLoc = 3
        mc.cfLoc = 5
        mc.tcLoc = 1

        mv.visitTypeInsn(Opcodes.NEW, "org/raku/nqp/runtime/CallFrame")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 1) // tc
        mv.visitVarInsn(Opcodes.ALOAD, 2) // cr
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/raku/nqp/runtime/CallFrame", "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, TYPE_TC, TYPE_CR))
        mv.visitVarInsn(Opcodes.ASTORE, 5) // cf;

        mc.tryStart = Label()
        mv.visitLabel(mc.tryStart)

        mv.visitVarInsn(Opcodes.ALOAD, 5) // cf
        mv.visitVarInsn(Opcodes.ALOAD, 3) // csd
        mv.visitVarInsn(Opcodes.ALOAD, 4) // args
        emitInteger(mc, arity)
        emitInteger(mc, arity)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "checkarity", Type.getMethodDescriptor(TYPE_CSD, TYPE_CF, TYPE_CSD, TYPE_AOBJ, Type.INT_TYPE, Type.INT_TYPE))
        mv.visitVarInsn(Opcodes.ASTORE, 3) // csd
        mv.visitVarInsn(Opcodes.ALOAD, 1) // tc
        mv.visitFieldInsn(Opcodes.GETFIELD, TYPE_TC.getInternalName(), "flatArgs", TYPE_AOBJ.getDescriptor())
        mv.visitVarInsn(Opcodes.ASTORE, 4) // args

        return mc
    }

    /** Generate adaptor epilogue and end the method. */
    protected open fun endCallout(c: MethodContext) {
        val mv = c.mv!!
        val endTry = Label()
        val handler = Label()
        val notcontrol = Label()

        mv.visitLabel(endTry)
        mv.visitVarInsn(Opcodes.ALOAD, 5) //cf
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TYPE_CF.getInternalName(), "leave", "()V")
        mv.visitInsn(Opcodes.RETURN)

        mv.visitLabel(handler)
        mv.visitInsn(Opcodes.DUP)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/raku/nqp/runtime/ControlException")
        mv.visitJumpInsn(Opcodes.IFEQ, notcontrol)
        mv.visitVarInsn(Opcodes.ALOAD, 5) //cf
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TYPE_CF.getInternalName(), "leave", "()V")
        mv.visitInsn(Opcodes.ATHROW)

        mv.visitLabel(notcontrol)
        mv.visitVarInsn(Opcodes.ALOAD, 1) // tc
        mv.visitInsn(Opcodes.SWAP)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/ExceptionHandling", "dieInternal",
                Type.getMethodDescriptor(Type.getType(RuntimeException::class.java), TYPE_TC, Type.getType(Throwable::class.java)))
        mv.visitInsn(Opcodes.ATHROW)

        c.mv!!.visitTryCatchBlock(c.tryStart, endTry, handler, null)
        c.mv!!.visitMaxs(0, 0)
        c.mv!!.visitEnd()
    }

    /** Generate callin prologue. */
    protected open fun startCallin(cc: ClassContext, modifiers: Int, name: String, desc: Type): MethodContext {
        val mc = MethodContext()
        mc.cc = cc
        val mv = cc.cv!!.visitMethod(modifiers, name, desc.getDescriptor(), null, null)
        mc.mv = mv
        mc.callback = true

        mv.visitCode()
        emitConst(mc, gc, GlobalContext::class.java)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/runtime/GlobalContext", "getCurrentThreadContext", "()Lorg/raku/nqp/runtime/ThreadContext;")
        mc.tcLoc = desc.getArgumentsAndReturnSizes() shr 2
        if ((modifiers and Opcodes.ACC_STATIC) != 0) mc.tcLoc--
        mc.argsLoc = mc.tcLoc + 1
        mc.cfLoc = mc.tcLoc + 2
        mv.visitVarInsn(Opcodes.ASTORE, mc.tcLoc)

        mv.visitVarInsn(Opcodes.ALOAD, mc.tcLoc)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TYPE_TC.getInternalName(), "resultFrame", Type.getMethodDescriptor(TYPE_CF))
        mv.visitVarInsn(Opcodes.ASTORE, mc.cfLoc)

        mc.tryStart = Label()
        mv.visitLabel(mc.tryStart)
        return mc
    }

    /** Generate callin epilogue. */
    protected open fun endCallin(mc: MethodContext) {
        val end = Label()
        val mv = mc.mv!!
        mv.visitLabel(end)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;")
        mv.visitInsn(Opcodes.ATHROW)
        mv.visitTryCatchBlock(mc.tryStart, end, end, "org/raku/nqp/runtime/JavaCallinException")
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Constructs a CallSiteDescriptor and argument array for a callback and begins the invokeDirect call. */
    protected open fun setupCallback(mc: MethodContext, invokee: SixModelObject?, invokeeKey: Method?, args: Array<Class<*>?>) {
        val csdFlags = ByteArray(args.size)
        for (i in args.indices)
            csdFlags[i] = TYPE_argflag[storageForType(args[i]!!).spec]
        val csd = CallSiteDescriptor(csdFlags, null)

        val mv = mc.mv!!
        mv.visitVarInsn(Opcodes.ALOAD, mc.tcLoc)
        if (Ops.isnull(invokee) == 0L) {
            emitConst(mc, invokee!!, SixModelObject::class.java)
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, mc.cc!!.className, "methodMap", "Ljava/util/Map;")
            emitConst(mc, invokeeKey!! as Any, Any::class.java)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;")
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(SixModelObject::class.java))
        }
        emitConst(mc, csd, CallSiteDescriptor::class.java)
        emitInteger(mc, args.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        mv.visitVarInsn(Opcodes.ASTORE, mc.argsLoc)
    }

    /** Finishes the invokeDirect call for a callback. */
    protected open fun fireCallback(mc: MethodContext) {
        val mv = mc.mv!!
        mv.visitVarInsn(Opcodes.ALOAD, mc.argsLoc)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "invokeDirect",
                Type.getMethodDescriptor(Type.VOID_TYPE, TYPE_TC, TYPE_SMO, TYPE_CSD, TYPE_AOBJ))
    }

    /** Working information for a method under construction. */
    protected open class MethodContext {
        /** The owning incomplete class. */
        @JvmField var cc: ClassContext? = null
        /** The ASM method writer. */
        @JvmField var mv: MethodVisitor? = null
        /** True if this is a callin. */
        @JvmField var callback: Boolean = false
        /** Local variable index of the current [CallFrame]. */
        @JvmField var cfLoc: Int = 0
        /** Local variable index of the argument list being constructed or read. */
        @JvmField var argsLoc: Int = 0
        /** Local variable index of the [CallSiteDescriptor] being read. */
        @JvmField var csdLoc: Int = 0
        /** Local variable index of the current [ThreadContext]. */
        @JvmField var tcLoc: Int = 0
        /** Temporary used for whole-method exception catching. */
        @JvmField var tryStart: Label? = null
    }

    /** Emits code to a working method to push an integer constant. */
    protected open fun emitInteger(c: MethodContext, i: Int) {
        if (i >= -1 && i <= 5) c.mv!!.visitInsn(Opcodes.ICONST_0 + i)
        else if (i == i.toByte().toInt()) c.mv!!.visitIntInsn(Opcodes.BIPUSH, i)
        else if (i == i.toShort().toInt()) c.mv!!.visitIntInsn(Opcodes.SIPUSH, i)
        else c.mv!!.visitLdcInsn(i)
    }

    /** Emits code to a working method to push an object constant. */
    protected open fun <T : Any> emitConst(c: MethodContext, k: T, cls: Class<T>) {
        val ks = c.cc!!.constants
        val kix = ks.size
        ks.add(k)
        c.mv!!.visitFieldInsn(Opcodes.GETSTATIC, c.cc!!.className, "constants", "[Ljava/lang/Object;")
        emitInteger(c, kix)
        c.mv!!.visitInsn(Opcodes.AALOAD)
        if (cls != Object::class.java) c.mv!!.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(cls))
    }

    /** Emits code to a working method to get a value from an argument list or return value. */
    protected open fun emitGetFromNQP(c: MethodContext, index: Int, type: BoxedPrimitive) {
        if (c.callback) {
            // return value
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.cfLoc)
            c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "result_" + TYPE_CHAR[type.spec], Type.getMethodDescriptor(TYPES[type.spec], TYPE_CF))
        } else {
            // an argument
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.cfLoc)
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.csdLoc)
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.argsLoc)
            emitInteger(c, index)
            c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "posparam_" + TYPE_CHAR[type.spec], Type.getMethodDescriptor(TYPES[type.spec], TYPE_CF, TYPE_CSD, TYPE_AOBJ, Type.INT_TYPE))
        }
    }

    /** Emits "early" code to a working method to push a value to a return value or argument list constructor. */
    protected open fun preEmitPutToNQP(c: MethodContext, index: Int, type: BoxedPrimitive) {
        if (c.callback) {
            // an argument
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.argsLoc)
            emitInteger(c, index)
        }
    }

    /** Emits "late" code to a working method to push a value to a return value or argument list constructor. */
    protected open fun emitPutToNQP(c: MethodContext, index: Int, type: BoxedPrimitive) {
        if (c.callback) {
            // an argument
            if (type == BoxedPrimitive.INT) {
                c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")
            } else if (type == BoxedPrimitive.NUM) {
                c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double")
            }
            c.mv!!.visitInsn(Opcodes.AASTORE)
        } else {
            c.mv!!.visitVarInsn(Opcodes.ALOAD, c.cfLoc)
            c.mv!!.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "return_" + TYPE_CHAR[type.spec], Type.getMethodDescriptor(Type.VOID_TYPE, TYPES[type.spec], TYPE_CF))
        }
    }

    /** No user-servicable parts inside.  Public for the sake of generated code only. */
    open class RuntimeSupport {
        companion object {
            @JvmStatic
            fun boxJava(o: Any?, st: STable?): SixModelObject {
                val jow = JavaObjectWrapper()
                jow.st = st!!
                jow.theObject = o
                return jow
            }

            @JvmStatic
            fun unboxJava(smo: SixModelObject?): Any? {
                return (smo as JavaObjectWrapper).theObject
            }
        }
    }

    /** A non-invalidating inline cache for lazily turning a [Class] into a [STable]. */
    open inner class STableCache(private val what: Class<*>) {
        @Volatile private var localCache: STable? = null

        fun getSTable(): STable? {
            val fetch = localCache
            if (fetch != null) return fetch
            val computed = getSTableForClass(what)
            localCache = computed
            return computed
        }
    }

    private val proxyClasses = object : ClassValue<MethodHandle>() {
        override fun computeValue(iface: Class<*>): MethodHandle {
            val cls = computeProxyClass(iface)
            try {
                return MethodHandles.publicLookup().findConstructor(cls, MethodType.methodType(Void.TYPE, java.util.Map::class.java))
            } catch (roe: ReflectiveOperationException) {
                throw RuntimeException(roe)
            }
        }
    }

    /** Produce an interface instance using a cached class, in the style of (but not using) [java.lang.reflect.Proxy]. */
    open fun proxy(ifaceSmo: SixModelObject, methods: SixModelObject): SixModelObject {
        val iface = unboxClass(ifaceSmo)
        val tc = gc.getCurrentThreadContext()!!
        val methodImpl = proxyGetMethods(tc, iface, methods)
        val proxy: Any?
        try {
            proxy = proxyClasses.get(iface).invoke(methodImpl)
        } catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }

        return RuntimeSupport.boxJava(proxy, getSTableForClass(iface))
    }

    /** Override this to customize [proxy] method extraction. */
    protected open fun proxyGetMethods(tc: ThreadContext, iface: Class<*>, methods: SixModelObject): Map<Method, SixModelObject?> {
        // here in BOOTland we can't tell the difference between a coderef and a hash, so require a hash
        val ms = iface.getMethods()
        val ret = HashMap<Method, SixModelObject?>()
        for (m in ms) {
            if (m.getDeclaringClass() != iface) continue // don't care about hashCode and equals

            val s = m.getName()
            val l = s + "/" + Type.getMethodDescriptor(m)

            if (methods.exists_key(tc, l) != 0L)
                ret.put(m, methods.at_key_boxed(tc, l))
            else if (methods.exists_key(tc, s) != 0L)
                ret.put(m, methods.at_key_boxed(tc, s))
            else if (!Modifier.isAbstract(iface.getModifiers()) || Modifier.isAbstract(m.getModifiers()))
                throw ExceptionHandling.dieInternal(tc, "method hash has no definition for $l")
        }
        return ret
    }

    /** Override this to customize generation of proxy classes. */
    protected open fun computeProxyClass(iface: Class<*>): Class<*> {
        val cc = ClassContext()
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        val className = "org/raku/nqp/generatedproxy/" + Type.getInternalName(iface)
        cc.className = className
        cc.cv = cw

        val superclass: String
        if (Modifier.isInterface(iface.getModifiers())) {
            cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null,
                    "java/lang/Object", arrayOf(Type.getInternalName(iface)))
            superclass = "java/lang/Object"
        }
        else {
            superclass = Type.getInternalName(iface)
            cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null,
                    superclass, arrayOf())
        }
        cw.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC, "constants", "[Ljava/lang/Object;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE, "methodMap", "Ljava/util/Map;", null, null).visitEnd()

        for (m in iface.getMethods()) {
            if (m.getDeclaringClass() != iface) continue // no hashCode, equals
            createProxyMethod(cc, m)
        }

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/Map;)V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitFieldInsn(Opcodes.PUTFIELD, className, "methodMap", "Ljava/util/Map;")
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        finishClass(cc)
        return cc.constructed!!
    }

    /** Override this to customize proxy callins. */
    protected open fun createProxyMethod(cc: ClassContext, m: Method) {
        val mc = startCallin(cc, Opcodes.ACC_PUBLIC, m.getName(), Type.getType(m))
        val mv = mc.mv!!

        val cret = m.getReturnType()
        val cparm = m.getParameterTypes()

        @Suppress("UNCHECKED_CAST")
        setupCallback(mc, null, m, cparm as Array<Class<*>?>)

        var lidx = 1 // skip self
        for (i in cparm.indices) {
            val arg = cparm[i]!!
            val ty = Type.getType(arg)
            preMarshalIn(mc, arg, i)
            mv.visitVarInsn(ty.getOpcode(Opcodes.ILOAD), lidx)
            lidx += ty.getSize()
            marshalIn(mc, arg, i)
        }

        fireCallback(mc)

        marshalOut(mc, cret, 0)
        mc.mv!!.visitInsn(Type.getType(cret).getOpcode(Opcodes.IRETURN))

        endCallin(mc)
    }
}
