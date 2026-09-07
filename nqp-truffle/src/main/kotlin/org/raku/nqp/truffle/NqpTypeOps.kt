package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentLinkedQueue
import org.raku.nqp.dispatch.BindFailure
import org.raku.nqp.dispatch.DispatchBootstrap
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance
import org.raku.nqp.sixmodel.reprs.P6OpaqueREPRData

/**
 * jesp diamond 3: the type-check family as PE-visible operations.
 *
 * MoarVM's spesh folds `istype`, `isconcrete`, `decont`, `isnull`,
 * `assertparamcheck` and `create` to constants or field loads once a
 * guard has fixed the operand's type (optimize.c: `optimize_istype`,
 * `optimize_decont` to `sp_p6oget_o`, `sp_fastcreate`, ...). The engine
 * has no facts pass; what it has is the instruction. Each of these ops
 * carries a site that speculates on the STable of what it last saw --
 * resolved once, under a deoptimization, exactly as `NqpOps.AttrSite`
 * does for getattr -- and its fast path is then a pointer compare and a
 * field load in the caller's compiled code, where the generic road
 * crossed a `@TruffleBoundary` into a switch over boxed arguments.
 *
 * A site that misses re-speculates on the new operands a few times, then
 * pins itself to the generic road for good ([MAX_MISSES]). A site whose
 * answer was not a pure function of the STables (a type check the
 * metamodel answered, a generic return type, a container that does more
 * than read an attribute) pins itself at once.
 *
 * The type-check caches (`istype`, `p6typecheckrv`) trust
 * `STable.TypeCheckCache` to be stable once published, the assumption
 * spesh's `optimize_istype` makes when it folds to a constant.
 *
 * Kotlin on a PE-visible path: `@JvmField`s only, no `!!` on the fast
 * road; nqp-truffle compiles without the null assertions.
 */
object NqpTypeOps {

    /** Re-speculations a site allows before it pins to the generic road. */
    const val MAX_MISSES = 4

    private val SITES = ConcurrentLinkedQueue<Site>()

    init {
        /* The per-eval-server-run reset, as NqpOps.resetSites: between
         * runs, no program executing. */
        DispatchBootstrap.registerResettable(Runnable { for (s in SITES) s.reset() })
    }

    abstract class Site {
        /** Guard failures so far; at MAX_MISSES the site is generic for good. */
        @JvmField @field:CompilationFinal var misses: Int = 0

        init { SITES.add(this) }

        /** Back to unresolved, misses included. */
        abstract fun reset()

        /** Whether the site may still (re)speculate. */
        fun mayResolve(): Boolean = misses < MAX_MISSES

        /** Give the site up: generic from here on. */
        fun pin() { misses = MAX_MISSES }
    }

    /* ----- decont ----- */

    /**
     * Speculates on one STable: a non-container (the value is its own
     * decont), or a container whose fetch is a plain attribute read
     * (Raku's Scalar), read through the slot's field getter.
     */
    class DecontSite : Site() {
        @JvmField @field:CompilationFinal var st: STable? = null
        @JvmField @field:CompilationFinal var container: Boolean = false
        @JvmField @field:CompilationFinal var storage: Class<*>? = null
        @JvmField @field:CompilationFinal var getter: MethodHandle? = null

        override fun reset() {
            st = null; container = false; storage = null; getter = null; misses = 0
        }
    }

    /**
     * nqp::decont with a site. The slow road is Ops.decont itself (a
     * container spec's fetch may run user code: a Proxy).
     */
    @JvmStatic
    fun decont(site: DecontSite, o: Any?, tc: ThreadContext): Any? {
        if (o is SixModelObject) {
            var st = site.st
            if (st == null && site.mayResolve()) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                resolveDecont(site, o, tc)
                st = site.st
            }
            if (st != null) {
                if (NqpRaw.st(o) === st) {
                    if (!site.container || o is TypeObject) return o
                    if (o.javaClass === site.storage && (o as P6OpaqueBaseInstance).delegate == null) {
                        val getter = site.getter
                        if (getter != null) {
                            val v: SixModelObject? = try {
                                getter.invokeExact(o as SixModelObject) as SixModelObject?
                            } catch (t: Throwable) {
                                throw CompilerDirectives.shouldNotReachHere(t)
                            }
                            /* Null: not yet vivified; the accessor decides. */
                            if (v != null) return v
                        }
                    }
                }
                else miss(site)
            }
        }
        return decontSlow(o, tc)
    }

    @TruffleBoundary
    private fun resolveDecont(site: DecontSite, o: SixModelObject, tc: ThreadContext) {
        val st = o.st ?: run { site.pin(); return }
        val cs = st.ContainerSpec
        if (cs == null) {
            site.container = false
            site.st = st
            return
        }
        val fetch = cs.fetchAttribute(tc)
        val rd = st.REPRData
        if (fetch != null && rd is P6OpaqueREPRData) {
            val storage = rd.jvmClass
            if (storage != null) {
                val hint = st.REPR.hint_for(tc, st, fetch.classHandle, fetch.name)
                if (hint != STable.NO_HINT) {
                    val hs = NqpDispatch.fieldHandles(storage, hint.toInt())
                    if (hs != null) {
                        site.container = true
                        site.storage = storage
                        site.getter = hs[0]
                        site.st = st
                        return
                    }
                }
            }
        }
        site.pin()
    }

    @TruffleBoundary
    private fun decontSlow(o: Any?, tc: ThreadContext): Any? =
        Ops.decont(o as SixModelObject?, tc)

    /** A guard failed: forget the speculation; the next run re-resolves or pins. */
    private fun miss(site: Site) {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        val misses = site.misses + 1
        site.reset()
        site.misses = misses
        if (misses >= MAX_MISSES) site.pin()
    }

    /* ----- isnull ----- */

    /** nqp::isnull: a pointer compare against the VM null; no site, no boundary. */
    @JvmStatic
    fun isnull(o: Any?): Long =
        if (o is SixModelObject) Ops.isnull(o) else if (o == null) 1L else 0L

    /* ----- isconcrete ----- */

    class IsConcreteSite : Site() {
        @JvmField val decont = DecontSite()
        override fun reset() { misses = 0 }
    }

    /** nqp::isconcrete: null is not concrete; else the decont is not a type object. */
    @JvmStatic
    fun isconcrete(site: IsConcreteSite, o: Any?, tc: ThreadContext): Long {
        if (o !is SixModelObject) return 0L
        if (Ops.isnull(o) == 1L) return 0L
        val v = decont(site.decont, o, tc)
        return if (v == null || v is TypeObject) 0L else 1L
    }

    /* ----- istype ----- */

    /**
     * Speculates on the STables of the deconted value and the deconted
     * type, remembering the answer -- only when the type-check cache gave
     * it definitively (a hit, or a miss of a cache that is authoritative
     * and a type that needs no accepts_type).
     */
    class IsTypeSite : Site() {
        @JvmField val objDecont = DecontSite()
        @JvmField val typeDecont = DecontSite()
        @JvmField @field:CompilationFinal var objSt: STable? = null
        @JvmField @field:CompilationFinal var typeSt: STable? = null
        @JvmField @field:CompilationFinal var result: Long = 0

        override fun reset() { objSt = null; typeSt = null; result = 0; misses = 0 }
    }

    @JvmStatic
    fun istype(site: IsTypeSite, o: Any?, type: Any?, tc: ThreadContext): Long {
        val v = decont(site.objDecont, o, tc)
        val t = decont(site.typeDecont, type, tc)
        if (v is SixModelObject && t is SixModelObject) {
            var objSt = site.objSt
            if (objSt == null && site.mayResolve()) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                resolveIsType(site, v, t)
                objSt = site.objSt
            }
            if (objSt != null) {
                if (NqpRaw.st(v) === objSt && NqpRaw.st(t) === site.typeSt) return site.result
                miss(site)
            }
        }
        return istypeSlow(v, t, tc)
    }

    @TruffleBoundary
    private fun resolveIsType(site: IsTypeSite, v: SixModelObject, t: SixModelObject) {
        val vst = v.st
        val tst = t.st
        if (vst == null || tst == null || Ops.isnull(v) == 1L) { site.pin(); return }
        val cache = vst.TypeCheckCache ?: run { site.pin(); return }
        val mode = vst.ModeFlags and STable.TYPE_CHECK_CACHE_FLAG_MASK
        val needsAccept = (tst.ModeFlags and STable.TYPE_CHECK_NEEDS_ACCEPTS) != 0
        for (entry in cache) {
            if (entry === t) {
                site.objSt = vst; site.typeSt = tst; site.result = 1
                return
            }
        }
        if ((mode and STable.TYPE_CHECK_CACHE_THEN_METHOD) == 0 && !needsAccept) {
            site.objSt = vst; site.typeSt = tst; site.result = 0
            return
        }
        site.pin()
    }

    @TruffleBoundary
    private fun istypeSlow(v: Any?, t: Any?, tc: ThreadContext): Long =
        Ops.istype_nd(v as SixModelObject?, t as SixModelObject?, tc)

    /* ----- assertparamcheck ----- */

    /** nqp::assertparamcheck: the flag test inline, the failure behind a boundary. */
    @JvmStatic
    fun assertparamcheck(ok: Long, tc: ThreadContext): Any? {
        if (ok == 0L) failed(tc)
        return null
    }

    @TruffleBoundary
    private fun failed(tc: ThreadContext) {
        BindFailure.failed(tc)
    }

    /* ----- p6typecheckrv (Rakudo) ----- */

    /**
     * Speculates, for one routine, that a return value of this deconted
     * STable and concreteness was accepted -- the check is a pure function
     * of those once the routine's return type is known non-generic
     * (RakOps.p6typecheckrvCacheable). The op returns its value on
     * acceptance, so the fast path is the value itself.
     */
    class RvCheckSite : Site() {
        @JvmField val decont = DecontSite()
        @JvmField @field:CompilationFinal var routine: SixModelObject? = null
        @JvmField @field:CompilationFinal var bypass: SixModelObject? = null
        @JvmField @field:CompilationFinal var rvSt: STable? = null
        @JvmField @field:CompilationFinal var rvTypeObject: Boolean = false

        override fun reset() { routine = null; bypass = null; rvSt = null; rvTypeObject = false; misses = 0 }
    }

    @JvmStatic
    fun p6typecheckrv(site: RvCheckSite, rv: Any?, routine: Any?, bypass: Any?, tc: ThreadContext): Any? {
        val v = decont(site.decont, rv, tc)
        if (v is SixModelObject) {
            val cached = site.routine
            if (cached == null && site.mayResolve()) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                return resolveRvCheck(site, rv, v, routine, bypass, tc)
            }
            if (cached != null) {
                if (routine === cached && bypass === site.bypass && NqpRaw.st(v) === site.rvSt
                        && (v is TypeObject) == site.rvTypeObject)
                    return rv
                miss(site)
            }
        }
        return rvCheckSlow(rv, routine, bypass, tc)
    }

    /** Runs the check; if it accepted and may be cached, remembers it. */
    @TruffleBoundary
    private fun resolveRvCheck(site: RvCheckSite, rv: Any?, v: SixModelObject, routine: Any?,
                               bypass: Any?, tc: ThreadContext): Any? {
        val vst = v.st
        val r = rvCheckSlow(rv, routine, bypass, tc)
        if (vst != null && routine is SixModelObject && rvCacheable(routine, tc)) {
            site.rvSt = vst
            site.rvTypeObject = v is TypeObject
            site.bypass = bypass as SixModelObject?
            site.routine = routine
        }
        else site.pin()
        return r
    }

    @TruffleBoundary
    private fun rvCheckSlow(rv: Any?, routine: Any?, bypass: Any?, tc: ThreadContext): Any? =
        Rak.P6TYPECHECKRV.invokeExact(rv as SixModelObject?, routine as SixModelObject?,
            bypass as SixModelObject?, tc) as SixModelObject?

    @TruffleBoundary
    private fun rvCacheable(routine: SixModelObject, tc: ThreadContext): Boolean =
        (Rak.P6TYPECHECKRV_CACHEABLE.invokeExact(routine, tc) as Long) == 1L

    /** The Rakudo ops this file reaches, bound once (rakudo-runtime is not a compile-time dependency). */
    private object Rak {
        @JvmField val P6TYPECHECKRV: MethodHandle
        @JvmField val P6TYPECHECKRV_CACHEABLE: MethodHandle

        init {
            val c = Class.forName("org.raku.rakudo.RakOps")
            val l = MethodHandles.publicLookup()
            val smo = SixModelObject::class.java
            val tcc = ThreadContext::class.java
            P6TYPECHECKRV = l.findStatic(c, "p6typecheckrv", MethodType.methodType(smo, smo, smo, smo, tcc))
            P6TYPECHECKRV_CACHEABLE = l.findStatic(c, "p6typecheckrvCacheable",
                MethodType.methodType(java.lang.Long.TYPE, smo, tcc))
        }
    }

    /* ----- create ----- */

    /**
     * Speculates on the type's STable. A P6opaque allocates by cloning
     * its prototype instance: with the prototype a constant, the clone's
     * class is exact and the allocation is one Graal can see. Any other
     * REPR allocates through its (constant) REPR.
     */
    class CreateSite : Site() {
        @JvmField @field:CompilationFinal var st: STable? = null
        @JvmField @field:CompilationFinal var repr: REPR? = null
        @JvmField @field:CompilationFinal var proto: P6OpaqueBaseInstance? = null

        override fun reset() { st = null; repr = null; proto = null; misses = 0 }
    }

    @JvmStatic
    fun create(site: CreateSite, type: Any?, tc: ThreadContext): Any? {
        if (type is SixModelObject) {
            var st = site.st
            if (st == null && site.mayResolve()) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                resolveCreate(site, type)
                st = site.st
            }
            if (st != null) {
                if (NqpRaw.st(type) === st) {
                    val proto = site.proto
                    if (proto != null) {
                        val rd = st.REPRData
                        if (rd is P6OpaqueREPRData && rd.instance === proto) return proto.instClone()
                    }
                    else {
                        val repr = site.repr
                        if (repr != null) return repr.allocate(tc, st)
                    }
                }
                miss(site)
            }
        }
        return createSlow(type, tc)
    }

    @TruffleBoundary
    private fun resolveCreate(site: CreateSite, type: SixModelObject) {
        val st = type.st ?: run { site.pin(); return }
        val rd = st.REPRData
        if (rd is P6OpaqueREPRData) {
            val proto = rd.instance
            if (proto == null) { site.pin(); return }
            site.proto = proto
        }
        else site.repr = st.REPR
        site.st = st
    }

    @TruffleBoundary
    private fun createSlow(type: Any?, tc: ThreadContext): Any? =
        Ops.create(type as SixModelObject?, tc)
}
