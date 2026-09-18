package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject

/** A reference no serialization context names; caught at the top of
 *  persist/realise, never escapes. */
class Unpersistable(what: String) : RuntimeException(what)

/**
 * DispatchProgram <-> PProgram. persist() returns null for a program
 * with a reference that has no SC address (C0: 1.1 % of them; the
 * classes are listed in the findings); realise() returns null when a
 * persisted reference does not resolve in this process (an SC not
 * loaded yet, an index past the table, an empty root slot), and the
 * site then records as it always did. Both directions are total
 * functions of their input: no state, no caching.
 */
object DispatchSlotCodec {
    /* ----- to the persisted form ----- */

    /** [stamps], when given, collects the stamp of every SC a persisted
     *  reference names (DispatchSlot.stamps); a program that turns out
     *  unpersistable may have added to it, so the caller keeps a map per
     *  program and merges on success. */
    fun persist(p: DispatchProgram, stamps: MutableMap<String, Int>? = null): PProgram? =
        try { program(p, stamps) } catch (_: Unpersistable) { null }

    private fun program(p: DispatchProgram, stamps: MutableMap<String, Int>?): PProgram = PProgram(
        descriptor(p.descriptor), p.guards.map { guard(it, stamps) }, outcome(p.outcome, stamps),
        p.resumptions.map { PResumption(it.dispatcher.id, shape(it.initArgs, stamps)) },
        p.resumeKind,
        p.resumeLevels.map { l ->
            PLevel(l.dispatcher.id, descriptor(l.initDescriptor), l.guards.map { guard(it, stamps) },
                l.newState?.let { source(it, stamps) }, l.requireNoFurther) },
        p.bindControl?.let { PBind(it.failureFlag, it.successFlag, it.onSuccessToo) },
        p.bindFailureProgram?.let { program(it, stamps) })

    private fun descriptor(d: CallSiteDescriptor) = PDescriptor(d.argFlags.copyOf(), d.names?.toList())

    private fun typeName(obj: SixModelObject): String =
        if (obj.stInitialized) obj.st.debugName ?: "?" else "?"

    /* The context validates an index by reading the slot back; -1 means not
     * in the root set. */
    private fun objectIndex(sc: SerializationContext, obj: SixModelObject): Int = sc.getObjectIndex(obj)
    private fun codeIndex(sc: SerializationContext, obj: SixModelObject): Int = sc.getCodeIndex(obj)

    private fun stamped(sc: SerializationContext, r: PRef, stamps: MutableMap<String, Int>?): PRef {
        stamps?.put(sc.handle, sc.stamp)
        return r
    }

    fun ref(obj: SixModelObject?, stamps: MutableMap<String, Int>? = null): PRef? {
        if (obj == null) return null
        val sc = obj.sc ?: throw Unpersistable("object of ${typeName(obj)} in no SC")
        val oi = objectIndex(sc, obj)
        if (oi >= 0) return stamped(sc, PRef(sc.handle, oi, PRef.OBJ), stamps)
        val ci = codeIndex(sc, obj)
        if (ci >= 0) return stamped(sc, PRef(sc.handle, ci, PRef.CODE), stamps)
        throw Unpersistable("object of ${typeName(obj)} not in the root set of ${sc.handle}")
    }

    fun ref(st: STable?, stamps: MutableMap<String, Int>? = null): PRef? {
        if (st == null) return null
        val sc = st.sc ?: throw Unpersistable("STable ${st.debugName} in no SC")
        val i = sc.getSTableIndex(st)
        if (i >= 0) return stamped(sc, PRef(sc.handle, i, PRef.STABLE), stamps)
        throw Unpersistable("STable ${st.debugName} not in the root set of ${sc.handle}")
    }

    private fun literal(kind: ArgKind, value: Any?, stamps: MutableMap<String, Int>?): PLiteral = when (kind) {
        ArgKind.OBJ -> PLiteral(kind, ref(value as SixModelObject?, stamps), 0, 0.0, null)
        ArgKind.INT, ArgKind.UINT -> PLiteral(kind, null, (value as Number).toLong(), 0.0, null)
        ArgKind.NUM -> PLiteral(kind, null, 0, (value as Number).toDouble(), null)
        ArgKind.STR -> PLiteral(kind, null, 0, 0.0, value as String?)
    }

    private fun source(s: ValueSource, stamps: MutableMap<String, Int>?): PSource = when (s) {
        is ValueSource.Arg -> PArg(s.index)
        is ValueSource.ResumeInitArg -> PResumeInitArg(s.level, s.index)
        is ValueSource.Literal -> literal(s.kind, s.value, stamps)
        is ValueSource.Attribute -> PAttribute(source(s.from, stamps), ref(s.classHandle, stamps), s.name, s.kind)
        is ValueSource.How -> PHow(source(s.from, stamps))
        is ValueSource.Unbox -> PUnbox(source(s.from, stamps), s.kind)
        is ValueSource.Lookup -> PLookup(source(s.table, stamps), source(s.key, stamps))
        is ValueSource.ResumeState -> PResumeState(s.level)
    }

    private fun guard(g: Guard, stamps: MutableMap<String, Int>?): PGuard = when (g) {
        is Guard.OfType -> PGuardType(source(g.on, stamps), ref(g.type, stamps))
        is Guard.Concreteness -> PGuardConcreteness(source(g.on, stamps), g.concrete)
        is Guard.Literal -> PGuardLiteral(source(g.on, stamps), literal(g.expected.kind, g.expected.value, stamps))
        is Guard.NotLiteralObj -> PGuardNotLiteralObj(source(g.on, stamps), ref(g.rejected, stamps))
        is Guard.OfHll -> PGuardHll(source(g.on, stamps), g.hll?.name, g.hll?.compilerSide ?: false)
    }

    private fun shape(c: CaptureShape, stamps: MutableMap<String, Int>?) =
        PShape(c.sources.map { source(it, stamps) }, descriptor(c.descriptor))

    private fun outcome(o: Outcome, stamps: MutableMap<String, Int>?): POutcome = when (o) {
        is Outcome.Value -> POutcomeValue(source(o.source, stamps))
        is Outcome.InvokeCode -> POutcomeInvoke(source(o.callee, stamps), shape(o.args, stamps))
        is Outcome.InvokeSyscall -> POutcomeSyscall(o.syscall.name, shape(o.args, stamps))
    }

    /* ----- from the persisted form ----- */

    /** [onDrop], when given, is handed the reason a program did not realise
     *  (NQP_DISPATCH_PERSIST_TRACE reads it); the program is dropped either way. */
    fun realise(tc: ThreadContext, p: PProgram, onDrop: ((String) -> Unit)? = null): DispatchProgram? =
        try { program(tc, p) } catch (e: Unpersistable) { onDrop?.invoke(e.message ?: "?"); null }

    private fun program(tc: ThreadContext, p: PProgram): DispatchProgram {
        val out = DispatchProgram(descriptor(p.descriptor), p.guards.map { guard(tc, it) }, outcome(tc, p.outcome),
            p.resumptions.map { ResumptionSpec(dispatcher(tc, it.dispatcher), shape(tc, it.initArgs)) },
            p.resumeKind,
            p.resumeLevels.map { l ->
                ResumptionLevel(dispatcher(tc, l.dispatcher), descriptor(l.initDescriptor),
                    l.guards.map { guard(tc, it) }, l.newState?.let { source(tc, it) }, l.requireNoFurther) },
            p.bindControl?.let { BindControl(it.failureFlag, it.successFlag, it.onSuccessToo) })
        p.bindFailure?.let { out.bindFailureProgram = program(tc, it) }
        return out
    }

    private fun descriptor(d: PDescriptor) = CallSiteDescriptor(d.flags.copyOf(), d.names?.toTypedArray())

    private fun sc(tc: ThreadContext, handle: String): SerializationContext =
        tc.gc.scs[handle] ?: throw Unpersistable("no SC $handle")

    private fun obj(tc: ThreadContext, r: PRef?): SixModelObject? {
        if (r == null) return null
        val sc = sc(tc, r.handle)
        val o: SixModelObject? = when (r.kind) {
            PRef.OBJ -> if (r.index in 0 until sc.objectCount()) sc.getObject(r.index) else null
            PRef.CODE -> if (r.index in 0 until sc.coderefCount()) sc.getCodeRef(r.index) else null
            else -> null
        }
        return o ?: throw Unpersistable("${r.handle}:${r.index} (kind ${r.kind}) is empty")
    }

    private fun stable(tc: ThreadContext, r: PRef?): STable? {
        if (r == null) return null
        val sc = sc(tc, r.handle)
        if (r.kind != PRef.STABLE || r.index !in 0 until sc.stableCount())
            throw Unpersistable("${r.handle}:${r.index} is not an STable slot")
        return sc.getSTable(r.index) ?: throw Unpersistable("${r.handle}:${r.index} STable is empty")
    }

    private fun dispatcher(tc: ThreadContext, id: String): Dispatcher =
        tc.gc.dispatchers.findOrNull(id) ?: throw Unpersistable("no dispatcher $id")

    private fun literal(tc: ThreadContext, l: PLiteral): Any? = when (l.kind) {
        ArgKind.OBJ -> obj(tc, l.obj)
        ArgKind.INT, ArgKind.UINT -> l.i
        ArgKind.NUM -> l.n
        ArgKind.STR -> l.s
    }

    private fun source(tc: ThreadContext, s: PSource): ValueSource = when (s) {
        is PArg -> ValueSource.Arg(s.index)
        is PResumeInitArg -> ValueSource.ResumeInitArg(s.level, s.index)
        is PLiteral -> ValueSource.Literal(s.kind, literal(tc, s))
        is PAttribute -> ValueSource.Attribute(source(tc, s.from), obj(tc, s.classHandle), s.name, s.kind)
        is PHow -> ValueSource.How(source(tc, s.from))
        is PUnbox -> ValueSource.Unbox(source(tc, s.from), s.kind)
        is PLookup -> ValueSource.Lookup(source(tc, s.table), source(tc, s.key))
        is PResumeState -> ValueSource.ResumeState(s.level)
    }

    private fun guard(tc: ThreadContext, g: PGuard): Guard = when (g) {
        is PGuardType -> Guard.OfType(source(tc, g.on), stable(tc, g.type))
        is PGuardConcreteness -> Guard.Concreteness(source(tc, g.on), g.concrete)
        is PGuardLiteral -> Guard.Literal(source(tc, g.on), DispatchValue(g.expected.kind, literal(tc, g.expected)))
        is PGuardNotLiteralObj -> Guard.NotLiteralObj(source(tc, g.on), obj(tc, g.rejected))
        /* findHLLConfig, not getHLLConfigFor: the guard compares by identity,
         * so a config minted here -- or taken from whichever registry happens
         * to be current -- would be a guard that can never match. */
        is PGuardHll -> Guard.OfHll(source(tc, g.on), g.hll?.let {
            tc.gc.findHLLConfig(it, g.compilerSide)
                ?: throw Unpersistable("no HLL config $it (compilerSide=${g.compilerSide})") })
    }

    private fun shape(tc: ThreadContext, s: PShape) = CaptureShape(s.sources.map { source(tc, it) }, descriptor(s.descriptor))

    private fun outcome(tc: ThreadContext, o: POutcome): Outcome = when (o) {
        is POutcomeValue -> Outcome.Value(source(tc, o.source))
        is POutcomeInvoke -> Outcome.InvokeCode(source(tc, o.callee), shape(tc, o.args))
        is POutcomeSyscall -> Outcome.InvokeSyscall(
            try { Syscalls.find(tc, o.syscall) } catch (_: Exception) { throw Unpersistable("no syscall ${o.syscall}") },
            shape(tc, o.args))
    }
}
