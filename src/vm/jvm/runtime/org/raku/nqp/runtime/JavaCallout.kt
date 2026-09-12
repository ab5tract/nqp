package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.JavaObjectWrapper

/**
 * The Java-interop callout: one hand-written entry over a plan per Java
 * member, replacing the ASM-generated qb_N statics. Same convention as
 * before -- USE_BINDER: the raw argument list arrives, the callout opens a
 * CallFrame, checks arity, reads each positional through the binder's
 * reads, converts per parameter, calls the member, converts the result and
 * returns it through the frame.
 */
sealed class CalloutPlan(@JvmField val descriptor: String)

/** A fixed-arity member: method, constructor, field get/set, or a special.
 *  Positional argStart+i is read into Java argument i: 0 for an instance
 *  member (slot 0 is the invocant), 1 for a static, a constructor or a
 *  special (slot 0 is the type object and is skipped, as before). */
class MemberPlan(
    descriptor: String,
    @JvmField val arity: Int,
    @JvmField val argStart: Int,
    @JvmField val args: Array<ArgMarshal>,
    /** (Object[])Object over the marshalled Java values. */
    @JvmField val target: MethodHandle,
    @JvmField val ret: RetMarshal,
) : CalloutPlan(descriptor)

/** A callout that binds its own arguments (the Rakudo multi-dispatchers). */
abstract class VarArityPlan(descriptor: String) : CalloutPlan(descriptor) {
    abstract fun run(tc: ThreadContext, cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>)
}

/** The stand-in for a member whose handle could not be unreflected -- a
 *  public member of a class the lookup may not reach (a non-public or
 *  unexported declaring class). The plan exists so that one such member
 *  does not cost its whole class its interop: every other member keeps its
 *  road, and this one dies when it is actually called, naming itself. Any
 *  arity is accepted, so the failure is the access one and not a confusing
 *  arity mismatch. */
class UnusablePlan(descriptor: String, private val why: String) : VarArityPlan(descriptor) {
    override fun run(tc: ThreadContext, cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>) {
        throw ExceptionHandling.dieInternal(tc, "Java interop: cannot access " + descriptor + " (" + why + ")")
    }
}

/** How one Java parameter is read out of the Raku argument list. The cases
 *  are BootJavaInterop.marshalOut's, one class each. */
sealed class ArgMarshal {
    abstract fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any?

    /** long/int/short/byte/boolean from an int positional. */
    class LongArg(private val what: Class<*>) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val v = Ops.posparam_i(cf, csd, args, idx)
            return when (what) {
                java.lang.Long.TYPE -> v
                Integer.TYPE -> v.toInt()
                java.lang.Short.TYPE -> v.toShort()
                java.lang.Byte.TYPE -> v.toByte()
                else -> v != 0L    // boolean
            }
        }
    }

    /** double/float from a num positional. */
    class NumArg(private val what: Class<*>) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val v = Ops.posparam_n(cf, csd, args, idx)
            return if (what == java.lang.Float.TYPE) v.toFloat() else v
        }
    }

    /** String, or char as the first character. */
    class StrArg(private val asChar: Boolean) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val s = Ops.posparam_s(cf, csd, args, idx)
            return if (asChar) s!![0] else s
        }
    }

    /** A SixModelObject parameter: passed through. */
    object SmoArg : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? =
            Ops.posparam_o(cf, csd, args, idx)
    }

    /** ThreadContext / GlobalContext: the current one when null was passed. */
    class ContextArg(private val what: Class<*>) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val v = Ops.posparam_o(cf, csd, args, idx)
            if (v == null) return if (what == ThreadContext::class.java) tc else tc.gc
            return what.cast(BootJavaInterop.RuntimeSupport.unboxJava(v))
        }
    }

    /** A Java array (or, in Rakudo, a List/Map) built from a Raku list. */
    class ArrayArg(
        private val what: Class<*>,
        private val recurse: (SixModelObject, ThreadContext, Class<*>) -> Any?,
    ) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val v = Ops.posparam_o(cf, csd, args, idx)!!
            return what.cast(recurse(v, tc, what))
        }
    }

    /** Anything else: a wrapped Java object is unboxed, a Raku object goes
     *  as itself, and the cast to the parameter type fails at run time for
     *  a non-Java object where a Java one is needed (as before). */
    class ObjectArg(private val what: Class<*>) : ArgMarshal() {
        override fun read(cf: CallFrame, csd: CallSiteDescriptor, args: Array<Any?>, idx: Int, tc: ThreadContext): Any? {
            val v = Ops.posparam_o(cf, csd, args, idx)
            val d = Ops.decont(v, tc)
            val out: Any? = if (d is JavaObjectWrapper) BootJavaInterop.RuntimeSupport.unboxJava(d) else v
            return what.cast(out)
        }
    }
}

/** How a Java result goes back through the frame: BootJavaInterop.marshalIn's cases. */
sealed class RetMarshal {
    abstract fun write(v: Any?, cf: CallFrame, tc: ThreadContext)

    object VoidRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_o(null, cf)
    }

    object IntRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_i(
            when (v) {
                is Long -> v
                is Int -> v.toLong()
                is Short -> v.toLong()
                is Byte -> v.toLong()
                is Boolean -> if (v) 1L else 0L
                else -> throw IllegalStateException("interop: not an int result: $v")
            }, cf)
    }

    object NumRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_n(
            when (v) {
                is Double -> v
                is Float -> v.toDouble()
                else -> throw IllegalStateException("interop: not a num result: $v")
            }, cf)
    }

    object StrRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_s(v as String?, cf)
    }

    object CharRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_s((v as Char).toString(), cf)
    }

    object SmoRet : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) = Ops.return_o(v as SixModelObject?, cf)
    }

    class BoxRet(private val cache: BootJavaInterop.STableCache) : RetMarshal() {
        override fun write(v: Any?, cf: CallFrame, tc: ThreadContext) =
            Ops.return_o(BootJavaInterop.RuntimeSupport.boxJava(v, cache.getSTable()), cf)
    }
}

object JavaCallout {
    /** The handle AdaptorUnit binds a plan into: after insertArguments(0, plan)
     *  its type is (ThreadContext, CodeRef, CallSiteDescriptor, Object[])void,
     *  which is exactly what USE_BINDER invokeExacts. */
    @JvmField val INVOKE: MethodHandle = MethodHandles.lookup().findStatic(JavaCallout::class.java, "invoke",
        MethodType.methodType(Void.TYPE, CalloutPlan::class.java, ThreadContext::class.java, CodeRef::class.java,
            CallSiteDescriptor::class.java, Array<Any?>::class.java))

    @JvmStatic
    fun invoke(plan: CalloutPlan, tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            when (plan) {
                is MemberPlan -> {
                    val csd = Ops.checkarity(cf, csd0, args0, plan.arity, plan.arity)
                    val args = tc.flatArgs!!
                    val jargs = arrayOfNulls<Any>(plan.args.size)
                    for (i in plan.args.indices) jargs[i] = plan.args[i].read(cf, csd, args, plan.argStart + i, tc)
                    val result: Any? = plan.target.invokeExact(jargs)
                    plan.ret.write(result, cf, tc)
                }
                is VarArityPlan -> {
                    val csd = Ops.checkarity(cf, csd0, args0, 1, -1)
                    plan.run(tc, cf, csd, tc.flatArgs!!)
                }
            }
        }
        catch (t: Throwable) {
            /* leave() is deliberately outside the guarded region on the
             * success path (as the emitted adaptor's try range was), so a
             * throwing leave() cannot be followed by a second one. */
            cf.leave()
            if (t is ControlException) throw t
            throw ExceptionHandling.dieInternal(tc, t)
        }
        cf.leave()
    }
}
