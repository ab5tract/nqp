package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Array as JavaArray
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.HashMap

import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.JavaObjectWrapper

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

    /** Hiding arbitrary 6model objects under Object, for working with
      * untyped collection classes, etc. */
    open fun sixmodelToJavaObject(smo: SixModelObject): SixModelObject {
        return RuntimeSupport.boxJava(smo, getSTableForClass(Object::class.java))
    }

    open fun javaObjectToSixmodel(javaObj: SixModelObject): SixModelObject {
        return RuntimeSupport.unboxJava(javaObj) as SixModelObject
    }

    // begin gory details
    /** Constructs interop objects for a class.  Override this if you need something other than a hash. */
    protected open fun computeInterop(tc: ThreadContext, klass: Class<*>): SixModelObject {
        val plans = createPlans(klass)
        val adaptorUnit = AdaptorUnit(plans, klass.getName())
        adaptorUnit.initializeCompilationUnit(tc)

        val hash = gc.BOOTHash!!.st.REPR.allocate(tc, gc.BOOTHash!!.st)

        val names = HashMap<String, SixModelObject?>()

        for (i in 0 until plans.size) {
            val desc = plans[i].descriptor
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

    /** One plan per public method, field (get, and set unless final),
     *  constructor, plus the three specials. Rakudo overrides to add its
     *  multi-dispatchers. */
    protected open fun createPlans(target: Class<*>): MutableList<CalloutPlan> {
        val plans = ArrayList<CalloutPlan>()
        for (m in target.getMethods()) {
            try { plans.add(methodPlan(m)) }
            catch (e: ReflectiveOperationException) {
                plans.add(unusablePlan("method/" + m.getName() + "/" + jvmDescriptor(m), e))
            }
        }
        for (f in target.getFields()) {
            try { plans.add(fieldGetPlan(f)) }
            catch (e: ReflectiveOperationException) {
                plans.add(unusablePlan("field/get_" + f.getName() + "/" + f.getType().descriptorString(), e))
            }
            if (!Modifier.isFinal(f.getModifiers())) {
                try { plans.add(fieldSetPlan(f)) }
                catch (e: ReflectiveOperationException) {
                    plans.add(unusablePlan("field/set_" + f.getName() + "/" + f.getType().descriptorString(), e))
                }
            }
        }
        for (c in target.getConstructors()) {
            try { plans.add(constructorPlan(c)) }
            catch (e: ReflectiveOperationException) {
                plans.add(unusablePlan("constructor/new/" + jvmDescriptor(c), e))
            }
        }
        plans.addAll(specialPlans(target))
        return plans
    }

    /** A member whose handle the lookup refused (`unreflect` throws
     *  IllegalAccessException for a public member of a class this module
     *  may not reach) gets a plan that dies at call time naming it, instead
     *  of failing the interop of every other member of its class. */
    protected fun unusablePlan(descriptor: String, e: ReflectiveOperationException): CalloutPlan =
        UnusablePlan(descriptor, e.toString())

    private val lookup = MethodHandles.lookup()
    private val SPREAD = MethodType.methodType(Any::class.java, Array<Any?>::class.java)

    /** A member handle spread over an Object[] of its arguments, typed (Object[])Object. */
    protected fun spread(mh: MethodHandle): MethodHandle =
        mh.asType(mh.type().generic()).asSpreader(Array<Any?>::class.java, mh.type().parameterCount()).asType(SPREAD)

    /** Descriptor strings are keys the Raku side uses verbatim
     *  ("method/valueOf/(Z)Ljava/lang/String;" in the test), so they stay
     *  byte-identical to what ASM's Type.getMethodDescriptor produced;
     *  Class.descriptorString() (JDK 12+) yields the same text. */
    protected fun jvmDescriptor(m: Method): String =
        m.getParameterTypes().joinToString("", "(", ")") { it.descriptorString() } + m.getReturnType().descriptorString()

    protected fun jvmDescriptor(c: Constructor<*>): String =
        c.getParameterTypes().joinToString("", "(", ")") { it.descriptorString() } + "V"

    protected open fun methodPlan(m: Method): MemberPlan {
        val isStatic = Modifier.isStatic(m.getModifiers())
        val ptypes = m.getParameterTypes()
        val args = ArrayList<ArgMarshal>()
        /* Slot 0 is the invocant even for a static (the type object): the
         * arity counts it; an instance method marshals it, a static skips it. */
        if (!isStatic) args.add(argMarshalFor(m.getDeclaringClass()))
        for (p in ptypes) args.add(argMarshalFor(p))
        return MemberPlan("method/" + m.getName() + "/" + jvmDescriptor(m), ptypes.size + 1, if (isStatic) 1 else 0,
            args.toTypedArray(), spread(lookup.unreflect(m)), retMarshalFor(m.getReturnType()))
    }

    protected open fun fieldGetPlan(f: Field): MemberPlan {
        val isStatic = Modifier.isStatic(f.getModifiers())
        val args = if (isStatic) emptyList<ArgMarshal>() else listOf(argMarshalFor(f.getDeclaringClass()))
        return MemberPlan("field/get_" + f.getName() + "/" + f.getType().descriptorString(), 1, if (isStatic) 1 else 0,
            args.toTypedArray(), spread(lookup.unreflectGetter(f)), retMarshalFor(f.getType()))
    }

    protected open fun fieldSetPlan(f: Field): MemberPlan {
        val isStatic = Modifier.isStatic(f.getModifiers())
        val args = (if (isStatic) emptyList<ArgMarshal>() else listOf(argMarshalFor(f.getDeclaringClass()))) +
            argMarshalFor(f.getType())
        return MemberPlan("field/set_" + f.getName() + "/" + f.getType().descriptorString(), 2, if (isStatic) 1 else 0,
            args.toTypedArray(), spread(lookup.unreflectSetter(f)), RetMarshal.VoidRet)
    }

    protected open fun constructorPlan(k: Constructor<*>): MemberPlan {
        val ptypes = k.getParameterTypes()
        return MemberPlan("constructor/new/" + jvmDescriptor(k), ptypes.size + 1, 1,
            ptypes.map { argMarshalFor(it) }.toTypedArray(), spread(lookup.unreflectConstructor(k)),
            retMarshalFor(k.getDeclaringClass()))
    }

    protected open fun specialPlans(target: Class<*>): List<CalloutPlan> {
        val box = lookup.findStatic(BootJavaInterop::class.java, "special_box",
            MethodType.methodType(Any::class.java, Any::class.java))
        val unbox = lookup.findStatic(BootJavaInterop::class.java, "special_unbox",
            MethodType.methodType(Any::class.java, Class::class.java, Any::class.java))
        val isinst = lookup.findStatic(BootJavaInterop::class.java, "special_isinst",
            MethodType.methodType(java.lang.Boolean.TYPE, Class::class.java, Any::class.java))
        return listOf(
            MemberPlan("/box/", 2, 1, arrayOf(argMarshalFor(target)), spread(box),
                RetMarshal.BoxRet(STableCache(Any::class.java))),
            /* The old /unbox/ marshalled its result in as the target type,
             * so a String target came back as a str, not a wrapper. */
            MemberPlan("/unbox/", 2, 1, arrayOf(ArgMarshal.ObjectArg(Any::class.java)),
                spread(MethodHandles.insertArguments(unbox, 0, target)), retMarshalFor(target)),
            MemberPlan("/isinst/", 2, 1, arrayOf(ArgMarshal.ObjectArg(Any::class.java)),
                spread(MethodHandles.insertArguments(isinst, 0, target)), RetMarshal.IntRet),
        )
    }

    /** marshalOut's cases, as data. */
    protected open fun argMarshalFor(what: Class<*>): ArgMarshal = when {
        what == java.lang.Long.TYPE || what == Integer.TYPE || what == java.lang.Short.TYPE
            || what == java.lang.Byte.TYPE || what == java.lang.Boolean.TYPE -> ArgMarshal.LongArg(what)
        what == java.lang.Double.TYPE || what == java.lang.Float.TYPE -> ArgMarshal.NumArg(what)
        what == String::class.java -> ArgMarshal.StrArg(false)
        what == Character.TYPE -> ArgMarshal.StrArg(true)
        what == SixModelObject::class.java -> ArgMarshal.SmoArg
        what == ThreadContext::class.java || what == GlobalContext::class.java -> ArgMarshal.ContextArg(what)
        what.componentType != null -> ArgMarshal.ArrayArg(what) { smo, tc, cls -> marshalOutRecursive(smo, tc, cls) }
        else -> ArgMarshal.ObjectArg(what)
    }

    /** marshalIn's cases, as data. */
    protected open fun retMarshalFor(what: Class<*>): RetMarshal = when {
        what == Void.TYPE -> RetMarshal.VoidRet
        what == Integer.TYPE || what == java.lang.Short.TYPE || what == java.lang.Byte.TYPE
            || what == java.lang.Boolean.TYPE || what == java.lang.Long.TYPE -> RetMarshal.IntRet
        what == java.lang.Double.TYPE || what == java.lang.Float.TYPE -> RetMarshal.NumRet
        what == String::class.java -> RetMarshal.StrRet
        what == Character.TYPE -> RetMarshal.CharRet
        what == SixModelObject::class.java -> RetMarshal.SmoRet
        /* The old marshalIn's commonSTable arm needs no case of its own:
         * STableCache defers to getSTableForClass, which answers
         * commonSTable when one is set. */
        else -> RetMarshal.BoxRet(STableCache(what))
    }

    companion object {
        /* The three specials' bodies, reached through method handles from
         * specialPlans: what the emitted /box/, /unbox/ and /isinst/ did
         * inline. */
        @JvmStatic fun special_box(o: Any?): Any? = o
        @JvmStatic fun special_unbox(target: Class<*>, o: Any?): Any? = target.cast(o)
        @JvmStatic fun special_isinst(target: Class<*>, o: Any?): Boolean = target.isInstance(o)

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
         * RakudoJavaInterop declares its own static marshalOutRecursive,
         * which under Java's rules *hides* this one — and hiding a final
         * static is a compile error. The Java original was a plain
         * (non-final) public static. */
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
    }

    /** No user-servicable parts inside.  Public for the sake of the callout plans only. */
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
}
