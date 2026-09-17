package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.SaveStackException
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.BoolificationSpec
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.VMIterInstance
import org.raku.nqp.truffle.NqpTypeOps.Site
import org.raku.nqp.truffle.NqpTypeOps.DecontSite
import org.raku.nqp.truffle.NqpTypeOps.DEBUG
import org.raku.nqp.truffle.NqpTypeOps.debug
import org.raku.nqp.truffle.NqpTypeOps.decont
import org.raku.nqp.truffle.NqpTypeOps.miss
import org.raku.nqp.truffle.NqpTypeOps.republished
import org.raku.nqp.truffle.NqpTypeOps.suspendedIn
import org.raku.nqp.truffle.NqpTypeOps.truthy

/**
 * The batch 1 sites of milestone 8 Phase B -- iscont, istrue/isfalse (and
 * the Truthy object arm), findmethod/tryfindmethod/can -- moved out of
 * NqpTypeOps.kt when that file passed the spec's thousand-line split point
 * (section 4; 6.1 item 5). The Site base, the registry, DecontSite and the
 * shared helpers stay in NqpTypeOps; batch 2's sites go in a third file.
 * Every body here is the batch 1 text, unchanged.
 */
object NqpSiteOps {

    /* ----- iscont ----- */

    /**
     * nqp::iscont with a site: whether a type's objects are containers is a
     * fact of its state (the container spec), so the answer folds to a
     * constant under one STable compare and the state's assumption.
     * setcontspec publishes a new state, which invalidates the fold.
     */
    class IsContSite : Site() {
        @JvmField @field:CompilationFinal var st: STable? = null
        @JvmField @field:CompilationFinal var result: Long = 0
        override fun clear() { st = null; result = 0 }
    }

    /** nqp::iscont: null is not a container; else the folded fact, else the runtime. */
    @JvmStatic
    fun iscont(site: IsContSite, o: Any?): Long {
        if (NqpCensus.ON) NqpCensus.call(site.stats)
        if (o !is SixModelObject || Ops.isnull(o) == 1L) return 0L
        var st = site.st
        if (st == null && site.mayResolve()) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            resolveIsCont(site, o)
            st = site.st
        }
        if (st != null) {
            if (!site.valid()) republished(site)
            else if (NqpRaw.st(o) === st) return site.result
            else miss(site)
        }
        if (NqpCensus.ON) NqpCensus.slow(site.stats, if (site.mayResolve()) "generic" else "pinned")
        /* no boundary on purpose: Ops.iscont is two field reads, cheaper
         * inline than a boundary crossing (unlike the siblings' slow roads,
         * which call into the runtime proper). */
        return Ops.iscont(o)
    }

    @TruffleBoundary
    private fun resolveIsCont(site: IsContSite, o: SixModelObject) {
        if (!o.stInitialized) { site.pin(); return }
        val st = o.st
        val s = st.state
        /* st last: a reader that sees st non-null must see the fold it
         * licenses (the file's convention, see resolveDecont/resolveCreate);
         * this orders plain stores, it is not a fence -- the milestone-level
         * item is recorded in the ledger. */
        site.result = if (s.containerSpec == null) 0L else 1L
        site.state = s
        site.st = st
        if (DEBUG) debug("iscont site resolved " + site.result + " on " + st.debugName)
    }

    /* ----- istrue / isfalse ----- */

    /**
     * nqp::istrue with a site: the boolification mode is a fact of the
     * deconted value's state, so under one STable compare the answer is one
     * REPR read chosen by a compilation-final mode. Mode 0 (call a method)
     * is user code and is not folded: the site pins and the census counts
     * the road as `method`. Only BIGINT (mode 6) stays on the runtime road
     * besides it: its read goes through the bigint cache behind a boundary.
     */
    class IsTrueSite : Site() {
        @JvmField val decont = DecontSite()
        @JvmField @field:CompilationFinal var st: STable? = null
        @JvmField @field:CompilationFinal var mode: Int = -1
        /** Why the site pinned, for the census; null while it may resolve. */
        @JvmField var pinReason: String? = null
        override fun clear() { st = null; mode = -1; pinReason = null }
    }

    /**
     * The constant a native-typed condition carries: the DSL refuses null
     * ("Constant operands do not permit null values"), never cast. That arm
     * of Truthy reads no site, so the builder passes this rather than
     * allocate a site nothing would use.
     */
    @JvmField val NO_SITE: Any = Any()

    /** nqp::istrue (negate == 0) or nqp::isfalse (negate != 0) of a value. */
    @JvmStatic
    fun istrue(site: IsTrueSite, o: Any?, negate: Int, tc: ThreadContext): Long {
        if (NqpCensus.ON) NqpCensus.call(site.stats)
        val v = try {
            decont(site.decont, o, tc)
        } catch (sse: SaveStackException) {
            /* A Proxy FETCH captured; finish by re-running on the fetched
             * value, which is no longer a container (see istype). */
            throw suspendedIn(sse, java.util.function.Function { fetched -> istrue(site, fetched, negate, tc) })
        }
        /* a mode-0 boolification is user code: its capture must resume
         * through this op's negate, as the decont's does. The resumed
         * value is the boolify METHOD's result -- an object, read as one
         * (NqpOps.suspendToken's finisher overload) -- so the lost tail is
         * Ops.istrue OF that object (Ops.istrue's own mode-0 road ends in
         * `istrue(result_o(...))`), and then the negate. */
        val truth = try {
            truth(site, v, tc)
        } catch (sse: SaveStackException) {
            throw suspendedIn(sse, java.util.function.Function { t ->
                val tt = truthy(t, tc); if (negate == 0) tt else 1L - tt })
        }
        return if (negate == 0) truth else 1L - truth
    }

    /** The truth of an already-deconted value: the fold, else the runtime. */
    private fun truth(site: IsTrueSite, v: Any?, tc: ThreadContext): Long {
        if (v !is SixModelObject || Ops.isnull(v) == 1L) return 0L
        var st = site.st
        if (st == null && site.mayResolve()) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            resolveIsTrue(site, v)
            st = site.st
        }
        if (st != null) {
            if (!site.valid()) republished(site)
            else if (NqpRaw.st(v) === st) return truthByMode(site.mode, v, tc)
            else miss(site)
        }
        if (NqpCensus.ON) NqpCensus.slow(site.stats, site.pinReason ?: if (site.mayResolve()) "generic" else "pinned")
        return istrueSlow(v, tc)
    }

    /** The folded modes, as Ops.istrue answers them; `mode` is compilation-final. */
    private fun truthByMode(mode: Int, o: SixModelObject, tc: ThreadContext): Long = when (mode) {
        BoolificationSpec.MODE_NOT_TYPE_OBJECT -> if (o is TypeObject) 0L else 1L
        BoolificationSpec.MODE_UNBOX_INT -> if (o is TypeObject || o.get_int(tc) == 0L) 0L else 1L
        BoolificationSpec.MODE_UNBOX_NUM -> if (o is TypeObject || o.get_num(tc) == 0.0) 0L else 1L
        BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY ->
            if (o is TypeObject) 0L else { val s = o.get_str(tc); if (s == null || s.isEmpty()) 0L else 1L }
        BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY_OR_ZERO ->
            if (o is TypeObject) 0L else { val s = o.get_str(tc); if (s == null || s.isEmpty() || s == "0") 0L else 1L }
        BoolificationSpec.MODE_HAS_ELEMS -> if (o.elems(tc) == 0L) 0L else 1L
        /* Under the STable compare the cast cannot fail: the folded
         * type's REPR is the iterator REPR. */
        BoolificationSpec.MODE_ITER -> if ((o as VMIterInstance).boolify()) 1L else 0L
        else -> istrueSlow(o, tc)
    }

    @TruffleBoundary
    private fun resolveIsTrue(site: IsTrueSite, o: SixModelObject) {
        if (!o.stInitialized) { site.pin(); return }
        val st = o.st
        val s = st.state
        val bs = s.boolificationSpec
        val mode = if (bs == null) BoolificationSpec.MODE_NOT_TYPE_OBJECT else bs.Mode
        when (mode) {
            BoolificationSpec.MODE_NOT_TYPE_OBJECT, BoolificationSpec.MODE_UNBOX_INT,
            BoolificationSpec.MODE_UNBOX_NUM, BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY,
            BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY_OR_ZERO, BoolificationSpec.MODE_HAS_ELEMS,
            BoolificationSpec.MODE_ITER -> {
                /* st last (see resolveIsCont). */
                site.mode = mode
                site.state = s
                site.st = st
                if (DEBUG) debug("istrue site resolved mode " + mode + " on " + st.debugName)
            }
            else -> {
                site.pinReason = if (mode == BoolificationSpec.MODE_CALL_METHOD) "method" else "mode" + mode
                site.pin()
                if (DEBUG) debug("istrue pin " + site.pinReason + ": " + st.debugName)
            }
        }
    }

    @TruffleBoundary
    private fun istrueSlow(v: Any?, tc: ThreadContext): Long =
        Ops.istrue(v as SixModelObject?, tc)

    /* ----- findmethod / tryfindmethod / can ----- */

    const val FIND_FATAL = 0
    const val FIND_TRY = 1
    const val FIND_CAN = 2

    /**
     * The method a name resolves to on a type is a fact of its state when
     * the state's method cache answers it (the Phase B rule: a site folds
     * only a fact some state it holds published; the HOW walk is not one).
     * A cache HIT is such a fact whatever the cache's authority -- the
     * runtime returns a hit before it ever consults the flag, see
     * Ops.findmethodNonFatal -- so a hit folds under any cache. A cache
     * MISS only means "no such method" when the cache is AUTHORITATIVE, so
     * a miss folds to null under an authoritative cache and pins under an
     * advisory one (counted as `advisory`); a state with no method cache at
     * all pins the same way, under its own key (`nocache`), so the census
     * says which of the two a site met. What is folded is then held under one STable compare, the
     * name's identity and the state's assumption. Three ops share it:
     * findmethod (fatal on null, through the runtime's error road),
     * tryfindmethod (null on null) and can (0/1).
     */
    class FindMethodSite : Site() {
        @JvmField val decont = DecontSite()
        @JvmField @field:CompilationFinal var st: STable? = null
        @JvmField @field:CompilationFinal var name: String? = null
        @JvmField @field:CompilationFinal var found: SixModelObject? = null
        @JvmField var pinReason: String? = null
        override fun clear() { st = null; name = null; found = null; pinReason = null }
    }

    @JvmStatic
    fun findmethod(site: FindMethodSite, o: Any?, name: Any?, kind: Int, tc: ThreadContext): Any? {
        if (NqpCensus.ON) NqpCensus.call(site.stats)
        val v = try {
            decont(site.decont, o, tc)
        } catch (sse: SaveStackException) {
            throw suspendedIn(sse, java.util.function.Function { fetched -> findmethod(site, fetched, name, kind, tc) })
        }
        if (v is SixModelObject && name is String && Ops.isnull(v) == 0L) {
            var st = site.st
            if (st == null && site.mayResolve()) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                resolveFindMethod(site, v, name)
                st = site.st
            }
            if (st != null) {
                if (!site.valid()) republished(site)
                else if (NqpRaw.st(v) === st && (name === site.name || name == site.name)) {
                    val found = site.found
                    return when (kind) {
                        FIND_CAN -> if (found == null) 0L else 1L
                        FIND_TRY -> found
                        else -> found ?: findmethodSlow(v, name, kind, tc)   /* the runtime raises the error */
                    }
                }
                else miss(site)
            }
        }
        if (NqpCensus.ON) NqpCensus.slow(site.stats, site.pinReason ?: if (site.mayResolve()) "generic" else "pinned")
        return findmethodSlow(v, name, kind, tc)
    }

    @TruffleBoundary
    private fun resolveFindMethod(site: FindMethodSite, v: SixModelObject, name: String) {
        if (!v.stInitialized) { site.pin(); return }
        val st = v.st
        val s = st.state
        val cache = s.methodCache
        if (cache == null) {
            site.pinReason = "nocache"; site.pin()
            if (DEBUG) debug("findmethod pin nocache (no method cache): " + st.debugName + "." + name)
            return
        }
        val raw = cache.get(name)
        val hit = if (raw == null || Ops.isnull(raw) == 1L) null else raw
        /* A miss is only "no such method" when the cache is authoritative;
         * under an advisory one the HOW may still find the method. */
        if (hit == null && !s.methodCacheAuthoritative) {
            site.pinReason = "advisory"; site.pin()
            if (DEBUG) debug("findmethod pin advisory (miss under an advisory cache): " + st.debugName + "." + name)
            return
        }
        /* st last (see resolveIsCont). */
        site.name = name
        site.found = hit
        site.state = s
        site.st = st
        if (DEBUG) debug("findmethod site resolved " + st.debugName + "." + name + " found=" + (hit != null))
    }

    /**
     * The runtime road: the same three entries the table and classlib roads
     * call. `NqpOps.str` is private to NqpOps, so the str coercion is
     * written out here (ruling: the brief's named fallback).
     */
    @TruffleBoundary
    private fun findmethodSlow(v: Any?, name: Any?, kind: Int, tc: ThreadContext): Any? {
        val o = v as SixModelObject?
        /* Ops.can/findmethodNonFatal/findmethod all take a non-null String,
         * so a null name has no runtime road to take: it is a builder bug,
         * named rather than passed on as the string "null". */
        val n: String = if (name is String) name
            else name?.toString() ?: throw IllegalArgumentException("findmethod: null name")
        return when (kind) {
            FIND_CAN -> Ops.can(o, n, tc)
            FIND_TRY -> Ops.findmethodNonFatal(o, n, tc)
            else -> Ops.findmethod(o, n, tc)
        }
    }

}
