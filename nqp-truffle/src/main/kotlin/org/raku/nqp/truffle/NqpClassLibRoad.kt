package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.raku.nqp.runtime.SaveStackException
import org.raku.nqp.runtime.ThreadContext

/**
 * The typed classlib road (milestone 8, Phase B, batch 2, spec 6.2).
 *
 * A registry-derived classlib op used to reach the runtime through one
 * variadic node: an Object[] from the DSL, a second Object[] to append the
 * thread context, a handle adapted with asSpreader and asType to
 * (Object[])Object -- every INT/STR result boxed by the adapter -- and one
 * megamorphic invokeExact for every classlib op in the process, behind a
 * boundary. That road's own leaf was 18.3 % of a CORE.c compile.
 *
 * Here every CLASSLIB instruction of arity 0-4 gets a [TypedSite]: the
 * registry row, the result type and ONE handle, resolved once and adapted
 * once to the uniform type of its arity and flavour. The Object flavour is
 * `(Object, ..., ThreadContext)Object`: the context is a real argument (an
 * op without `:tc` has it dropped by the adapter), and a capture answers
 * the suspend token typed by the op's result, as the variadic road did.
 * The long flavour is `(Object, ...)long` for context-free INT/UINT ops of
 * arity 1-3 only (plan Ruling 1): without a context an op cannot reach
 * guest code, so it cannot capture, and a long cannot carry a token.
 *
 * What the default road does NOT do is de-megamorphise the JVM call site.
 * Behind the default `@TruffleBoundary` ([obj0]-[obj4], [long1]-[long3])
 * the `invokeExact` inside each `callN` is still ONE JVM call site shared
 * by every classlib instruction of that arity and flavour -- eight sites
 * for the process instead of one, not one per instruction. Per-instruction
 * folding of the `@CompilationFinal` handle happens only under
 * `NQP_CLASSLIB_INLINE`, where the constant [TypedSite] and its handle are
 * PE-visible and Graal can inline the target. What the default road removes
 * is the two `Object[]` allocations and the spreading/boxing adapter.
 *
 * The declared residual: operands stay boxed (the operand stack is Object
 * for a mixed signature). Arity 5-6 (twenty registrations) and
 * `NQP_SITES_OFF=classlib` keep the variadic node. `NQP_CLASSLIB_INLINE=1`
 * calls the compilation-final handle without the boundary (the
 * milestone 7 knob, same meaning on this road).
 *
 * `JESP_TRACE_CLASSLIB` reaches the Object flavour only: the long flavour
 * has no [ThreadContext] to name a frame from, so [callLong1]-[callLong3]
 * never trace and a traced op that is a context-free INT/UINT of arity 1-3
 * (69 registrations) prints nothing.
 */
object NqpClassLibRoad {
    const val MAX_ARITY = 4

    /** One CLASSLIB instruction: the registry row plus its handle. */
    class TypedSite(@JvmField val cls: String, @JvmField val meth: String, @JvmField val desc: String,
                    @JvmField val tcArg: Boolean, @JvmField val nargs: Int, @JvmField val rtype: Int,
                    @JvmField val isLong: Boolean) {
        /** The census name, `Ops.meth`, as the variadic road's site prints. */
        @JvmField val name: String = cls.substringAfterLast('/').removeSuffix(";") + "." + meth
        /** The token's register type: a uint site reads from the int register. */
        @JvmField val tokenType: Int = if (rtype == NqpWire.T_UINT) NqpWire.T_INT else rtype
        @JvmField @field:CompilationFinal var mh: MethodHandle? = null

        fun handle(): MethodHandle {
            val h = mh
            if (h != null) return h
            CompilerDirectives.transferToInterpreterAndInvalidate()
            val r = resolve(this)
            mh = r
            return r
        }
    }

    /** The site for one instruction, or null when it stays on the variadic node. */
    @JvmStatic
    fun site(cls: String, meth: String, desc: String, tcArg: Boolean, nargs: Int, rtype: Int): TypedSite? {
        if (nargs > MAX_ARITY) return null
        val isLong = !tcArg && (rtype == NqpWire.T_INT || rtype == NqpWire.T_UINT) && nargs in 1..3
        return TypedSite(cls, meth, desc, tcArg, nargs, rtype, isLong)
    }

    @TruffleBoundary
    private fun resolve(s: TypedSite): MethodHandle {
        val ld = NqpOps::class.java.classLoader
        // The registry stores the class as a JVM type descriptor
        // (Lorg/raku/nqp/runtime/Ops;); Class.forName wants org.raku.nqp.runtime.Ops.
        var bin = s.cls
        if (bin.startsWith("L") && bin.endsWith(";")) bin = bin.substring(1, bin.length - 1)
        bin = bin.replace('/', '.')
        val h0 = try {
            val c = Class.forName(bin, true, ld)
            val mt = MethodType.fromMethodDescriptorString(s.desc, ld)
            MethodHandles.lookup().findStatic(c, s.meth, mt)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("nqpp: classlib op ${s.cls}.${s.meth}${s.desc}: $e", e)
        }
        val obj = Any::class.java
        val params = ArrayList<Class<*>>(s.nargs + 1)
        repeat(s.nargs) { params.add(obj) }
        if (s.isLong) return h0.asType(MethodType.methodType(java.lang.Long.TYPE, params))
        val h = if (s.tcArg) h0 else MethodHandles.dropArguments(h0, s.nargs, ThreadContext::class.java)
        params.add(ThreadContext::class.java)
        return h.asType(MethodType.methodType(obj, params))
    }

    /* ----- JESP_TRACE_CLASSLIB=meth: name the frame running that op, once per frame ----- */

    @JvmField val TRACE: String? = System.getenv("JESP_TRACE_CLASSLIB")
    private val traced = HashSet<String>()

    @TruffleBoundary
    private fun trace(s: TypedSite, tc: ThreadContext) {
        val f = tc.curFrame
        val where = if (f == null) "<no frame>" else f.codeRef.name + " (current frame; a frame-free callee names its caller)"
        synchronized(traced) {
            if (traced.add(s.meth + "@" + where)) System.err.println("classlib " + s.meth + " in " + where)
        }
    }

    /* ----- the Object flavour: a token on a capture ----- */

    @JvmStatic fun call0(s: TypedSite, tc: ThreadContext): Any? {
        if (TRACE != null && TRACE == s.meth) trace(s, tc)
        return try { s.handle().invokeExact(tc) as Any? }
               catch (sse: SaveStackException) { NqpOps.suspendToken(sse, s.tokenType) }
    }
    @JvmStatic fun call1(s: TypedSite, a0: Any?, tc: ThreadContext): Any? {
        if (TRACE != null && TRACE == s.meth) trace(s, tc)
        return try { s.handle().invokeExact(a0, tc) as Any? }
               catch (sse: SaveStackException) { NqpOps.suspendToken(sse, s.tokenType) }
    }
    @JvmStatic fun call2(s: TypedSite, a0: Any?, a1: Any?, tc: ThreadContext): Any? {
        if (TRACE != null && TRACE == s.meth) trace(s, tc)
        return try { s.handle().invokeExact(a0, a1, tc) as Any? }
               catch (sse: SaveStackException) { NqpOps.suspendToken(sse, s.tokenType) }
    }
    @JvmStatic fun call3(s: TypedSite, a0: Any?, a1: Any?, a2: Any?, tc: ThreadContext): Any? {
        if (TRACE != null && TRACE == s.meth) trace(s, tc)
        return try { s.handle().invokeExact(a0, a1, a2, tc) as Any? }
               catch (sse: SaveStackException) { NqpOps.suspendToken(sse, s.tokenType) }
    }
    @JvmStatic fun call4(s: TypedSite, a0: Any?, a1: Any?, a2: Any?, a3: Any?, tc: ThreadContext): Any? {
        if (TRACE != null && TRACE == s.meth) trace(s, tc)
        return try { s.handle().invokeExact(a0, a1, a2, a3, tc) as Any? }
               catch (sse: SaveStackException) { NqpOps.suspendToken(sse, s.tokenType) }
    }

    @JvmStatic @TruffleBoundary fun obj0(s: TypedSite, tc: ThreadContext): Any? = call0(s, tc)
    @JvmStatic @TruffleBoundary fun obj1(s: TypedSite, a0: Any?, tc: ThreadContext): Any? = call1(s, a0, tc)
    @JvmStatic @TruffleBoundary fun obj2(s: TypedSite, a0: Any?, a1: Any?, tc: ThreadContext): Any? = call2(s, a0, a1, tc)
    @JvmStatic @TruffleBoundary fun obj3(s: TypedSite, a0: Any?, a1: Any?, a2: Any?, tc: ThreadContext): Any? = call3(s, a0, a1, a2, tc)
    @JvmStatic @TruffleBoundary fun obj4(s: TypedSite, a0: Any?, a1: Any?, a2: Any?, a3: Any?, tc: ThreadContext): Any? = call4(s, a0, a1, a2, a3, tc)

    /* ----- the long flavour: context-free INT/UINT ops, arity 1-3 (Ruling 1) ----- */

    @JvmStatic fun callLong1(s: TypedSite, a0: Any?): Long = s.handle().invokeExact(a0) as Long
    @JvmStatic fun callLong2(s: TypedSite, a0: Any?, a1: Any?): Long = s.handle().invokeExact(a0, a1) as Long
    @JvmStatic fun callLong3(s: TypedSite, a0: Any?, a1: Any?, a2: Any?): Long = s.handle().invokeExact(a0, a1, a2) as Long

    @JvmStatic @TruffleBoundary fun long1(s: TypedSite, a0: Any?): Long = callLong1(s, a0)
    @JvmStatic @TruffleBoundary fun long2(s: TypedSite, a0: Any?, a1: Any?): Long = callLong2(s, a0, a1)
    @JvmStatic @TruffleBoundary fun long3(s: TypedSite, a0: Any?, a1: Any?, a2: Any?): Long = callLong3(s, a0, a1, a2)
}
