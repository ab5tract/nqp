package org.raku.nqp.truffle

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.raku.nqp.dispatch.ArgKind
import org.raku.nqp.dispatch.BindFailure
import org.raku.nqp.dispatch.BindFailureException
import org.raku.nqp.dispatch.BindReturnException
import org.raku.nqp.dispatch.Captures
import org.raku.nqp.dispatch.Dispatch
import org.raku.nqp.dispatch.DispatchCallSite
import org.raku.nqp.dispatch.DispatchCompiler
import org.raku.nqp.dispatch.DispatchProgram
import org.raku.nqp.dispatch.DispatchRecord
import org.raku.nqp.dispatch.Guard
import org.raku.nqp.dispatch.Outcome
import org.raku.nqp.dispatch.Syscall
import org.raku.nqp.dispatch.ValueSource
import org.raku.nqp.runtime.ArgsExpectation
import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeEngines
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.HLLConfig
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.SaveStackException
import org.raku.nqp.runtime.StaticCodeInfo
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance
import org.raku.nqp.sixmodel.reprs.P6OpaqueDelegateInstance
import org.raku.nqp.sixmodel.reprs.P6OpaqueREPRData

/**
 * The Truffle side of a dispatch instruction's inline cache.
 *
 * A settled dispatch site on the bytecode side replays its recorded
 * [DispatchProgram]s through a MethodHandle guard chain ([DispatchCompiler]);
 * from an engine-run block that chain sat behind a `@TruffleBoundary`, so
 * partial evaluation saw one opaque call where MoarVM's specializer sees the
 * guards. Here the same programs are kept per instruction as a
 * compilation-final array, and their guards and outcomes are evaluated by
 * plain code that PE folds: a constant program's guard list explodes into
 * straight-line type/identity tests against the argument array, and the
 * outcome into a register store or a call.
 *
 * The value sources a program reads are folded once, when the program is
 * admitted. An attribute read (the decont of a Scalar argument, a Code
 * object's `$!do`) is only ever recorded behind a type guard on the object it
 * reads from -- the same contract MoarVM's tracked attributes impose -- so
 * the guarded type's storage class and the attribute's slot hint are both
 * known here, and the read replays as an exact-class cast plus the generated
 * accessor's switch on a constant: a field load after PE, where the
 * interpreted road looked the name up in a hash per dispatch. A source whose
 * object type is not fixed by a guard crosses a boundary into the runtime's
 * generic accessor instead.
 *
 * Nothing about recording changes. A miss takes [Dispatch.fallback], exactly
 * the tail a compiled chain falls into -- it tries the programs the cache
 * does not cover and records afresh, installing at the site -- and then the
 * cache is rebuilt from the site's program list. Every rebuild, and the
 * per-eval-server-run reset, replaces the array under a fresh [Assumption],
 * so compiled code that folded the old array is invalidated rather than left
 * replaying a stale prefix. The cacheable prefix is what
 * `DispatchCompiler.compilable` admits: initial dispatches whose recorded
 * shape is this instruction's shape; resuming programs walk the live
 * callstack and stay on the interpreted road.
 *
 * Kotlin, on a PE-visible path: every field read here is a `@JvmField`, no
 * `!!` or `lateinit` sits on the fast road (their failure paths inline in
 * full), and nqp-truffle compiles with the parameter/call/receiver null
 * assertions off, as nqp-runtime does.
 */
object NqpDispatch {

    /** How many programs an instruction folds in; the chain's MAX_COMPILED twin. */
    const val MAX_CACHED = 8

    @JvmField val NONE: Array<Program> = emptyArray()

    const val K_VALUE = 0
    const val K_SYSCALL = 1
    const val K_INVOKE_MAPPED = 2
    const val K_INVOKE = 3
    const val K_INVOKE_RESUMABLE = 4

    /* ----- value sources, folded ----- */

    /** A value source as PE-visible code; see the class comment. */
    abstract class Src {
        abstract fun eval(tc: ThreadContext, args: Array<Any?>): Any?
    }

    class ArgSrc(@JvmField val index: Int) : Src() {
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? = args[index]
    }

    class LitSrc(@JvmField val value: Any?) : Src() {
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? = value
    }

    class HowSrc(@JvmField val from: Src) : Src() {
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? {
            val v = from.eval(tc, args)
            if (v !is SixModelObject) return null
            val st = NqpRaw.st(v) ?: return null
            return st.HOW
        }
    }

    /**
     * An attribute read whose object's storage class is known from the
     * guards. The generated accessor is NOT called: its delegation branch
     * calls the same accessor on the delegate, which PE follows as
     * recursion until Graal bails out of the whole compilation ("too deep
     * inlining", seen). Instead the slot's field is read through a
     * constant MethodHandle getter, which PE folds to the field load. The
     * class check is a speculation, not a proof: an object whose type a
     * mixin changed keeps its original storage class and delegates, so
     * its class is not its new type's; a delegating instance of the right
     * class, a deserialized wrapper's delegate, and a null all take the
     * generic accessor.
     */
    class AttrSrc(
        @JvmField val from: Src,
        @JvmField val storage: Class<*>,
        @JvmField val getter: MethodHandle,
        @JvmField val classHandle: SixModelObject?,
        @JvmField val name: String,
        @JvmField val kind: ArgKind,
    ) : Src() {
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? {
            val o = from.eval(tc, args)
            val target = if (o is P6OpaqueDelegateInstance) o.delegate else o
            if (target != null && target.javaClass === storage
                    && (target as P6OpaqueBaseInstance).delegate == null) {
                val v: SixModelObject? = try {
                    getter.invokeExact(target as SixModelObject) as SixModelObject?
                } catch (t: Throwable) {
                    throw CompilerDirectives.shouldNotReachHere(t)
                }
                /* Null: not yet vivified (or genuinely null); the accessor
                 * decides which and vivifies. */
                if (v != null) return v
            }
            return slow(tc, o)
        }

        @TruffleBoundary
        private fun slow(tc: ThreadContext, o: Any?): Any? {
            if (STATS) {
                count(slowEvals)
                val target = if (o is P6OpaqueDelegateInstance) o.delegate else o
                val key = "slow attr " + name + " of " +
                    (if (o == null) "null" else o.javaClass.name) + "/" +
                    (if (target == null) "null" else target.javaClass.name) +
                    " vs " + storage.name +
                    (if (target is P6OpaqueBaseInstance && target.delegate != null) " (delegating)" else "")
                if (seenSlow.add(key)) System.err.println("dispatch $key")
            }
            if (o == null)
                throw ExceptionHandling.dieInternal(tc,
                    "Dispatch program read an attribute of a null value")
            return ValueSource.readAttribute(tc, o as SixModelObject, classHandle, name, kind)
        }
    }

    /** An unbox of an object whose storage class is known, likewise. */
    class UnboxSrc(
        @JvmField val from: Src,
        @JvmField val storage: Class<*>,
        @JvmField val kind: ArgKind,
    ) : Src() {
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? {
            val o = from.eval(tc, args)
            val target = if (o is P6OpaqueDelegateInstance) o.delegate else o
            if (target != null && target.javaClass === storage) {
                val smo = CompilerDirectives.castExact(target, storage) as SixModelObject
                return when (kind) {
                    ArgKind.INT, ArgKind.UINT -> smo.get_int(tc)
                    ArgKind.NUM -> smo.get_num(tc)
                    else -> smo.get_str(tc)
                }
            }
            return slow(tc, o)
        }

        @TruffleBoundary
        private fun slow(tc: ThreadContext, o: Any?): Any? {
            if (STATS) {
                count(slowEvals)
                val key = "slow unbox " + kind + " of " + (if (o == null) "null" else o.javaClass.name) +
                    " vs " + storage.name
                if (seenSlow.add(key)) System.err.println("dispatch $key")
            }
            return ValueSource.unbox(tc, o as SixModelObject?, kind)
        }
    }

    /** Everything else: the runtime's generic evaluator, across a boundary. */
    class SlowSrc(@JvmField val source: ValueSource) : Src() {
        @TruffleBoundary
        override fun eval(tc: ThreadContext, args: Array<Any?>): Any? {
            if (STATS) {
                count(slowEvals)
                val key = "slow source $source"
                if (seenSlow.add(key)) System.err.println("dispatch $key")
            }
            return DispatchCompiler.evalRaw(source, tc, args)
        }
    }

    /* ----- guards, folded ----- */

    abstract class Chk(@JvmField val on: Src) {
        abstract fun test(tc: ThreadContext, args: Array<Any?>): Boolean
    }

    class TypeChk(on: Src, @JvmField val type: STable?) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean {
            val v = on.eval(tc, args)
            return v is SixModelObject && NqpRaw.st(v) === type
        }
    }

    class ConcreteChk(on: Src, @JvmField val concrete: Boolean) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean {
            val v = on.eval(tc, args)
            return (v != null && v !is TypeObject) == concrete
        }
    }

    class IdChk(on: Src, @JvmField val expected: Any?) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean =
            on.eval(tc, args) === expected
    }

    class EqChk(on: Src, @JvmField val expected: Any?) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean =
            Objects.equals(on.eval(tc, args), expected)
    }

    class NotIdChk(on: Src, @JvmField val rejected: Any?) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean =
            on.eval(tc, args) !== rejected
    }

    class HllChk(on: Src, @JvmField val hll: HLLConfig?) : Chk(on) {
        override fun test(tc: ThreadContext, args: Array<Any?>): Boolean {
            val v = on.eval(tc, args)
            if (v !is SixModelObject) return false
            val st = NqpRaw.st(v) ?: return false
            return st.hllOwner === hll
        }
    }

    /**
     * One installed program with its guards and outcome folded, so the
     * per-dispatch work is only the tests themselves. Every field is
     * final: the object is a constant of the compiled code, and finals fold.
     */
    class Program(p: DispatchProgram, csd: CallSiteDescriptor, tc: ThreadContext) {
        @JvmField val program: DispatchProgram = p
        @JvmField @field:CompilationFinal(dimensions = 1) val guards: Array<Chk>
        @JvmField val kind: Int
        @JvmField val source: Src?
        @JvmField val valueKind: ArgKind?
        @JvmField val syscall: Syscall?
        @JvmField @field:CompilationFinal(dimensions = 1) val plan: Array<Src>?
        @JvmField val descriptor: CallSiteDescriptor?
        @JvmField val callee: Src?
        /** The callee when the program names it: a mapped or a resumable invoke of a literal. */
        @JvmField val calleeLiteral: SixModelObject?
        @JvmField @field:CompilationFinal(dimensions = 1) val map: IntArray?

        /**
         * The call node for a literal engine-bodied callee, adopted under
         * the dispatch instruction's node the first time the program
         * replays there, so Truffle's inliner sees the call. Replaced when
         * the instruction's node changes (the uncached-to-cached tier
         * transition), since a call node under a dead parent inlines
         * nowhere. Null for any other callee, and until the callee's
         * program has run once and registered its target.
         */
        @JvmField @field:CompilationFinal var callNode: DirectCallNode? = null
        /** How many times the call node was re-adopted under a changed
         *  instruction node; past a few, the node in hand is used as is. */
        @JvmField @field:CompilationFinal var readopts: Int = 0

        /**
         * Folds the program. The guards are walked in recorded order, and
         * each type guard fixes the storage class of what it guards for the
         * sources built after it -- which is what lets an attribute read of
         * that object resolve its slot here.
         */
        init {
            val f = Folder(csd, tc)
            val gs = p.guards
            guards = Array(gs.size) { i -> f.fold(gs[i]) }

            var kind: Int
            var source: Src? = null
            var valueKind: ArgKind? = null
            var syscall: Syscall? = null
            var plan: Array<Src>? = null
            var descriptor: CallSiteDescriptor? = null
            var callee: Src? = null
            var calleeLiteral: SixModelObject? = null
            var map: IntArray? = null
            when (val o = p.outcome) {
                is Outcome.Value -> {
                    kind = K_VALUE
                    source = f.fold(o.source)
                    valueKind = f.kindOf(o.source)
                }
                is Outcome.InvokeSyscall -> {
                    kind = K_SYSCALL
                    syscall = o.syscall
                    plan = f.fold(o.args.sources)
                    descriptor = o.args.descriptor
                }
                is Outcome.InvokeCode -> {
                    val sources = o.args.sources
                    descriptor = o.args.descriptor
                    val c = o.callee
                    if (c is ValueSource.Literal)
                        calleeLiteral = c.value as SixModelObject?
                    if (p.resumptions.isEmpty() && p.bindControl == null) {
                        val m = if (c is ValueSource.Literal) argMap(sources) else null
                        if (m != null) {
                            kind = K_INVOKE_MAPPED
                            map = m
                        }
                        else {
                            kind = K_INVOKE
                            calleeLiteral = null
                            callee = f.fold(c)
                            plan = f.fold(sources)
                        }
                    }
                    else {
                        kind = K_INVOKE_RESUMABLE
                        callee = f.fold(c)
                        plan = f.fold(sources)
                    }
                }
            }
            this.kind = kind
            this.source = source
            this.valueKind = valueKind
            this.syscall = syscall
            this.plan = plan
            this.descriptor = descriptor
            this.callee = callee
            this.calleeLiteral = calleeLiteral
            this.map = map
            if (STATS) dump(p, kind, guards)
        }

        companion object {
            @TruffleBoundary
            private fun dump(p: DispatchProgram, kind: Int, guards: Array<Chk>) {
                val sb = StringBuilder("dispatch fold: kind=").append(kind).append(" guards=[")
                for (c in guards) sb.append(c.javaClass.simpleName).append('(')
                    .append(c.on.javaClass.simpleName).append(") ")
                sb.append("] outcome=").append(p.outcome)
                System.err.println(sb)
            }

            /** An arguments-only plan as bare indices, or null. */
            private fun argMap(plan: List<ValueSource>): IntArray? {
                val map = IntArray(plan.size)
                for (i in map.indices) {
                    val a = plan[i] as? ValueSource.Arg ?: return null
                    map[i] = a.index
                }
                return map
            }
        }
    }

    /** Builds the folded sources and guards of one program, in order. */
    class Folder(private val csd: CallSiteDescriptor, private val tc: ThreadContext) {
        /** Sources whose type a guard has fixed so far, structurally keyed. */
        private val known = HashMap<ValueSource, STable>()

        fun fold(g: Guard): Chk {
            val on = fold(g.on)
            when (g) {
                is Guard.OfType -> {
                    val t = g.type
                    if (t != null) known[g.on] = t
                    return TypeChk(on, t)
                }
                is Guard.Concreteness -> return ConcreteChk(on, g.concrete)
                is Guard.Literal -> {
                    val e = g.expected
                    if (e.kind == ArgKind.OBJ) {
                        /* An identity guard fixes the object, hence its type. */
                        val v = e.value
                        if (v is SixModelObject) {
                            val st = v.st
                            if (st != null) known[g.on] = st
                        }
                        return IdChk(on, e.value)
                    }
                    return EqChk(on, e.value)
                }
                is Guard.NotLiteralObj -> return NotIdChk(on, g.rejected)
                is Guard.OfHll -> return HllChk(on, g.hll)
                else -> throw IllegalStateException("unfoldable dispatch guard $g")
            }
        }

        fun fold(sources: List<ValueSource>): Array<Src> = Array(sources.size) { i -> fold(sources[i]) }

        fun fold(s: ValueSource): Src {
            if (s is ValueSource.Arg) return ArgSrc(s.index)
            if (s is ValueSource.Literal) return LitSrc(s.value)
            if (s is ValueSource.How) return HowSrc(fold(s.from))
            if (s is ValueSource.Attribute && s.kind == ArgKind.OBJ) {
                val storage = storageOf(s.from)
                if (storage != null) {
                    val st = known[s.from]!!
                    val hint = st.REPR.hint_for(tc, st, s.classHandle, s.name)
                    val getter = if (hint == STable.NO_HINT) null else getterFor(storage, hint.toInt())
                    if (getter != null)
                        return AttrSrc(fold(s.from), storage, getter, s.classHandle, s.name, s.kind)
                }
            }
            if (s is ValueSource.Unbox) {
                val storage = storageOf(s.from)
                if (storage != null && s.kind != ArgKind.OBJ)
                    return UnboxSrc(fold(s.from), storage, s.kind)
            }
            if (STATS) dumpSlow(s, known)
            return SlowSrc(s)
        }

        /** The exact storage class of a source a type guard has fixed. */
        private fun storageOf(from: ValueSource): Class<*>? {
            val st = known[from] ?: return null
            val rd = st.REPRData as? P6OpaqueREPRData ?: return null
            return rd.jvmClass
        }

        /** The kind a source produces, which the callsite shape fixes. */
        fun kindOf(s: ValueSource): ArgKind = when (s) {
            is ValueSource.Arg -> ArgKind.ofFlag(csd.argFlags[s.index])
            is ValueSource.Literal -> s.kind
            is ValueSource.Attribute -> s.kind
            is ValueSource.Unbox -> s.kind
            else -> ArgKind.OBJ
        }

        companion object {
            @TruffleBoundary
            private fun dumpSlow(s: ValueSource, known: Map<ValueSource, STable>) {
                System.err.println("dispatch slow source: " + s + " known=" + known.keys)
            }

            /**
             * A (SixModelObject)SixModelObject getter for the slot's field, or
             * null when the slot is not a reference field.
             */
            private fun getterFor(storage: Class<*>, slot: Int): MethodHandle? {
                val hs = fieldHandles(storage, slot)
                return hs?.get(0)
            }
        }
    }

    /**
     * The cache itself: the site's replayable prefix plus the assumption
     * compiled code folds it under. Owned by an [NqpOps.EngineSite].
     */
    class Cache(@JvmField val site: DispatchCallSite, @JvmField val csd: CallSiteDescriptor) {
        @JvmField @field:CompilationFinal(dimensions = 1) var programs: Array<Program> = NONE
        @JvmField @field:CompilationFinal var stable: Assumption =
            Truffle.getRuntime().createAssumption("dispatch site")
        /** Misses since the last refold; see REFOLD_AFTER. */
        @JvmField var missesSinceFold: Int = 0

        init {
            /* The per-run reset clears the bytecode-side site; the folded
             * prefix has to go with it, or compiled code keeps replaying
             * programs that guard on a finished run's types. */
            site.onReset = Runnable { reset() }
        }

        /** Rebuilds the prefix from the site's programs after a miss. */
        @Synchronized
        fun refresh(tc: ThreadContext) {
            val all = site.programs
            var n = 0
            while (n < all.size && n < MAX_CACHED && cacheable(all[n])) n++
            val current = programs
            if (current.size == n) {
                var same = true
                for (i in 0 until n)
                    if (current[i].program !== all[i]) { same = false; break }
                if (same) { missesSinceFold = 0; return }
            }
            val fresh = Array(n) { i ->
                if (i < current.size && current[i].program === all[i]) current[i]
                else Program(all[i], csd, tc)
            }
            publish(fresh)
        }

        @Synchronized
        fun reset() {
            if (programs.isNotEmpty()) publish(NONE)
        }

        /* New array and new assumption first, then the old assumption goes:
         * code that folded the old array deoptimizes and re-reads. A
         * thread still in the old code between the two steps replays the
         * old programs, which remain valid programs. */
        private fun publish(fresh: Array<Program>) {
            missesSinceFold = 0
            val old = stable
            programs = fresh
            stable = Truffle.getRuntime().createAssumption("dispatch site")
            old.invalidate()
        }

        private fun cacheable(p: DispatchProgram): Boolean =
            !p.isResuming && Captures.sameShape(p.descriptor, csd)
    }

    /**
     * The slot's field of a P6Opaque storage class as a getter
     * (SixModelObject)SixModelObject and a setter
     * (SixModelObject,SixModelObject)void, or null when the slot is not a
     * reference field. Auto-vivification (every `$` attribute of a Raku
     * class has a container prototype) only matters when the field is
     * null -- the accessor clones the prototype in and stores it -- so a
     * caller reads the field and sends a null to the accessor, and a
     * vivified attribute, the steady state, is a plain load.
     */
    @JvmStatic
    fun fieldHandles(storage: Class<*>, slot: Int): Array<MethodHandle>? {
        try {
            val f = storage.getField("field_$slot")
            if (f.type != SixModelObject::class.java) return null
            val lookup = MethodHandles.lookup()
            return arrayOf(
                lookup.unreflectGetter(f)
                    .asType(MethodType.methodType(SixModelObject::class.java, SixModelObject::class.java)),
                lookup.unreflectSetter(f)
                    .asType(MethodType.methodType(Void.TYPE, SixModelObject::class.java, SixModelObject::class.java)),
            )
        } catch (e: ReflectiveOperationException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
    }

    /* ----- counters (NQP_DISPATCH_STATS=1 prints them at exit) ----- */

    @JvmField val seenSlow: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @JvmField val noTargetBy = ConcurrentHashMap<String, AtomicLong>()

    @JvmField val STATS: Boolean = System.getenv("NQP_DISPATCH_STATS") != null
    @JvmField val hits = AtomicLong()
    @JvmField val misses = AtomicLong()
    @JvmField val slowEvals = AtomicLong()
    @JvmField val invokes = AtomicLong()
    @JvmField val directs = AtomicLong()
    @JvmField val noTarget = AtomicLong()
    @JvmField val badExpectation = AtomicLong()
    @JvmField val notCodeRef = AtomicLong()
    @JvmField val hitsByKind: Array<AtomicLong> =
        arrayOf(AtomicLong(), AtomicLong(), AtomicLong(), AtomicLong(), AtomicLong())

    init {
        if (STATS) Runtime.getRuntime().addShutdownHook(Thread {
            System.err.println("dispatch stats: hits=" + hits + " misses=" + misses +
                " slowEvals=" + slowEvals + " invokes=" + invokes + " directs=" + directs +
                " noTarget=" + noTarget + " badExpectation=" + badExpectation + " notCodeRef=" + notCodeRef +
                " byKind[value,syscall,mapped,invoke,resumable]=" + hitsByKind.contentToString())
            noTargetBy.entries.sortedByDescending { it.value.get() }.take(10)
                .forEach { System.err.println("  noTarget " + it.value + " " + it.key) }
        })
    }

    @TruffleBoundary
    private fun count(c: AtomicLong) { c.incrementAndGet() }

    /* ----- the fast path ----- */

    /**
     * Tries the folded programs in order; true when one applied and its
     * outcome has run. The array read folds to a constant under the
     * assumption, so the loop explodes into the programs' tests.
     */
    @JvmStatic
    @ExplodeLoop
    fun replay(cache: Cache, tc: ThreadContext, args: Array<Any?>, node: Node, callerHll: HLLConfig?): Boolean {
        if (!cache.stable.isValid) CompilerDirectives.transferToInterpreterAndInvalidate()
        val programs = cache.programs
        for (i in programs.indices) {
            val p = programs[i]
            if (matches(p, tc, args)) {
                if (STATS) { count(hits); count(hitsByKind[p.kind]) }
                realize(p, cache.site, tc, args, node, callerHll)
                return true
            }
        }
        return false
    }

    /**
     * How many misses a folded site tolerates before it refolds. A refold
     * republishes the array under a fresh Assumption, which invalidates
     * every compiled root that folded the site; on the CORE.c compile that
     * was ~290 invalidations against 38 on the old road, as sites grew
     * their program lists one recording at a time under compiled code.
     * A miss is correct regardless -- Dispatch.fallback tries every
     * program the site holds -- so a stale prefix costs only the
     * interpreted replay of the programs it lacks, and a site refolds
     * once it has shown it needs to. The first fold is immediate: a
     * monomorphic site must not run its whole life through the fallback.
     */
    const val REFOLD_AFTER = 16

    /** The chain's tail: uncached programs, then a recording; then refold. */
    @JvmStatic
    @TruffleBoundary
    fun miss(cache: Cache, name: String, tc: ThreadContext, args: Array<Any?>) {
        if (STATS) count(misses)
        Dispatch.fallback(cache.site, name, cache.csd, cache.programs.size, tc, args)
        if (cache.programs.isEmpty() || ++cache.missesSinceFold >= REFOLD_AFTER)
            cache.refresh(tc)
    }

    @ExplodeLoop
    private fun matches(p: Program, tc: ThreadContext, args: Array<Any?>): Boolean {
        val guards = p.guards
        for (i in guards.indices)
            if (!guards[i].test(tc, args)) return false
        return true
    }

    /* ----- outcomes ----- */

    private fun realize(p: Program, site: DispatchCallSite, tc: ThreadContext,
                        args: Array<Any?>, node: Node, callerHll: HLLConfig?) {
        /* A literal callee with an engine body: through the adopted call
         * node, so the callee inlines into this root. The resumable kind
         * too -- a multi's candidate is one -- with the dispatch carried
         * lazily by the callee's frame. */
        val lit = p.calleeLiteral
        if (lit is CodeRef && (p.kind == K_INVOKE_MAPPED || p.kind == K_INVOKE_RESUMABLE)
                && NqpRaw.staticInfo(lit).argsExpectation == ArgsExpectation.USE_BINDER) {
            var cn = p.callNode
            /* Adopt only once a target exists (a bytecode-bodied callee
             * never has one; one not yet run has none yet): the check is
             * a volatile load, the adoption a deoptimization -- doing the
             * latter on every replay of a target-less callee was a deopt
             * cycle. Re-adopt when the instruction's node changed, a few
             * times at most. */
            if (cn == null) {
                if (hasTarget(NqpRaw.staticInfo(lit))) {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    cn = adoptCallNode(p, lit, node)
                }
            }
            else if (cn.parent !== node && p.readopts < 4) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                p.readopts++
                cn = adoptCallNode(p, lit, node)
            }
            if (cn != null) {
                if (p.kind == K_INVOKE_MAPPED) {
                    enterMappedDirect(tc, lit, cn, p.descriptor, mapArgs(p.map!!, args), callerHll)
                } else {
                    enterResumableDirect(p, site, tc, args, lit, cn, evalPlan(p.plan!!, tc, args), callerHll)
                }
                return
            }
        }
        when (p.kind) {
            K_VALUE -> {
                val frame = tc.curFrame ?: tc.dummyCaller
                val v = p.source!!.eval(tc, args)
                when (p.valueKind) {
                    ArgKind.OBJ -> {
                        frame.oRet = v as SixModelObject?
                        frame.retType = CallFrame.RET_OBJ.toByte()
                    }
                    ArgKind.INT -> {
                        frame.iRet = v as Long
                        frame.retType = CallFrame.RET_INT.toByte()
                    }
                    ArgKind.UINT -> {
                        frame.iRet = v as Long
                        frame.retType = CallFrame.RET_UINT.toByte()
                    }
                    ArgKind.NUM -> {
                        frame.nRet = v as Double
                        frame.retType = CallFrame.RET_NUM.toByte()
                    }
                    else -> {
                        frame.sRet = v as String?
                        frame.retType = CallFrame.RET_STR.toByte()
                    }
                }
            }
            K_SYSCALL -> syscall(p, tc, evalPlan(p.plan!!, tc, args))
            K_INVOKE_MAPPED -> invoke(tc, p.calleeLiteral, p.descriptor, mapArgs(p.map!!, args))
            K_INVOKE -> invoke(tc, p.callee!!.eval(tc, args) as SixModelObject?, p.descriptor,
                evalPlan(p.plan!!, tc, args))
            else -> invokeResumable(p, site, tc, args)
        }
    }

    @ExplodeLoop
    private fun mapArgs(map: IntArray, args: Array<Any?>): Array<Any?> {
        val out = arrayOfNulls<Any?>(map.size)
        for (i in map.indices) out[i] = args[map[i]]
        return out
    }

    @ExplodeLoop
    private fun evalPlan(plan: Array<Src>, tc: ThreadContext, args: Array<Any?>): Array<Any?> {
        val out = arrayOfNulls<Any?>(plan.size)
        for (i in plan.indices) out[i] = plan[i].eval(tc, args)
        return out
    }

    @TruffleBoundary
    private fun syscall(p: Program, tc: ThreadContext, out: Array<Any?>) {
        Dispatch.setFrameResult(tc.curFrame!!, p.syscall!!.call(tc, p.descriptor!!, out))
    }

    /**
     * The call itself. A program with no resumptions and no bind control
     * needs no dispatch record at all (see DispatchCompiler.invokeMapped).
     *
     * A boundary, deliberately: a method-expansion trace of a compiler
     * root found ~3000 IR nodes per dispatch site, almost all of it this
     * road (enterEngine and runProgram, with their exception tails)
     * inlined once per folded program -- for a call that ends in an
     * indirect CallTarget.call PE cannot see through anyway. Only the
     * guard tests earn their place in the caller's compiled code; the
     * inlinable direct call is a DirectCallNode's job, above.
     */
    @TruffleBoundary
    private fun invoke(tc: ThreadContext, callee: SixModelObject?,
                       descriptor: CallSiteDescriptor?, out: Array<Any?>) {
        if (STATS) count(invokes)
        if (callee is CodeRef) {
            val target = CodeEngines.materialize(callee.staticInfo)
            if (target != null && callee.staticInfo.argsExpectation == ArgsExpectation.USE_BINDER) {
                if (STATS) count(directs)
                enterEngine(tc, callee, target as CallTarget, descriptor, out)
                return
            }
            if (STATS) countRefused(callee, target == null)
        }
        else if (STATS) count(notCodeRef)
        invokeBoundary(tc, callee, descriptor, out)
    }

    /* Diagnostics stay behind boundaries too: getSimpleName's reflection
     * road inlined under PE was itself a "too deep inlining" bailout. */
    @TruffleBoundary
    private fun countRefused(cr: CodeRef, noTargetCase: Boolean) {
        count(if (noTargetCase) noTarget else badExpectation)
        val name = cr.name
        val key = (if (name == null || name.isEmpty()) "<anon>" else name) +
            " " + cr.staticInfo.compUnit.javaClass.simpleName
        noTargetBy.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    @TruffleBoundary
    private fun invokeBoundary(tc: ThreadContext, callee: SixModelObject?,
                               descriptor: CallSiteDescriptor?, out: Array<Any?>) {
        Ops.invokeDirect(tc, callee, descriptor!!, out)
    }

    /**
     * A program that set up resumptions or bind control, with a callee the
     * program computes: the dispatch has to be findable while the callee
     * runs, so that the callee can resume it or fail its bind back into it.
     * DispatchCompiler.invokeResumable, over the folded plan.
     */
    private fun invokeResumable(p: Program, site: DispatchCallSite, tc: ThreadContext,
                                args: Array<Any?>) {
        val callee = p.callee!!.eval(tc, args) as SixModelObject?
        val out = evalPlan(p.plan!!, tc, args)
        invokeResumableBoundary(p, site, tc, args, callee, out)
    }

    @TruffleBoundary
    private fun invokeResumableBoundary(p: Program, site: DispatchCallSite, tc: ThreadContext,
                                        args: Array<Any?>, callee: SixModelObject?, out: Array<Any?>) {
        leavePending(p, site, tc, args)
        try {
            invoke(tc, callee, p.descriptor, out)
        }
        catch (failure: BindFailureException) {
            if (!owns(failure, p, args)) throw failure
            resumeAfterBindFailure(tc, failure.record, failure.flag)
        }
        finally {
            clearPending(tc)
        }
    }

    /**
     * The dispatch, for the frame the callee is about to build: not a
     * record but the three things one is made of, which the frame carries
     * and turns into a DispatchRecord only if a bind failure or a
     * resumption search asks (ThreadContext.pendingProgram). MoarVM's
     * sp_resumption: a resumable call allocates nothing for being one.
     */
    private fun leavePending(p: Program, site: DispatchCallSite, tc: ThreadContext, args: Array<Any?>) {
        tc.pendingProgram = p.program
        tc.pendingArgs = args
        tc.pendingSite = site
    }

    /** The frame took them, or nothing did (a frame-free callee): either way, gone. */
    private fun clearPending(tc: ThreadContext) {
        tc.pendingProgram = null
        tc.pendingArgs = null
        tc.pendingSite = null
    }

    /**
     * Is this bind failure ours to resume? The failing frame materialized
     * its record from what leavePending left, so it names our program and
     * our argument array; a deeper dispatch's failure names its own.
     */
    private fun owns(failure: BindFailureException, p: Program, args: Array<Any?>): Boolean {
        val failed = failure.record
        return failed.args === args && failed.program === p.program
    }

    @TruffleBoundary
    private fun resumeAfterBindFailure(tc: ThreadContext, record: DispatchRecord, flag: Long) {
        Dispatch.resumeAfterBindFailure(tc, record, flag)
    }

    /**
     * The direct road for a resumable program's literal engine callee: the
     * same adopted call node a mapped invoke uses, with the dispatch left
     * for the callee's frame to carry. A frame-free callee has no frame to
     * carry it and cannot resume or bind-fail into it; nothing is left for
     * it, so no nested frame claims the dispatch by mistake.
     */
    private fun enterResumableDirect(p: Program, site: DispatchCallSite, tc: ThreadContext,
                                     args: Array<Any?>, cr: CodeRef, cn: DirectCallNode,
                                     out: Array<Any?>, callerHll: HLLConfig?) {
        val root = engineRootOf(cn.callTarget)
        val framed = root == null || root.needsFrame || !frameFreeEntryOk(root, cr, callerHll)
        if (framed) leavePending(p, site, tc, args)
        try {
            enterDirect(tc, cr, cn, p.descriptor, out, callerHll)
        }
        catch (failure: BindFailureException) {
            if (!owns(failure, p, args)) throw failure
            resumeAfterBindFailure(tc, failure.record, failure.flag)
        }
        catch (failure: NqpFrameFreeBindFailure) {
            frameFreeBindFailed(p, site, tc, args, cr, out)
        }
        finally {
            if (framed) clearPending(tc)
        }
    }

    /**
     * A frame-free callee's bind check failed (jesp diamond 5): it had no
     * frame to find its dispatch on, so this road -- which is that
     * dispatch -- resumes it with the failure flag if the program asked
     * for bind failures as resumptions, else reports to the language's
     * bind_error handler with the callee, callsite and arguments it has.
     */
    @TruffleBoundary
    private fun frameFreeBindFailed(p: Program, site: DispatchCallSite, tc: ThreadContext,
                                    args: Array<Any?>, cr: CodeRef, out: Array<Any?>) {
        val program = p.program
        val control = program.bindControl
        if (control != null) {
            val record = DispatchRecord(tc, null, program.descriptor, args, tc.curFrame, site)
            record.program = program
            record.endRecording()
            Dispatch.resumeAfterBindFailure(tc, record, control.failureFlag)
        }
        else reportFrameFree(tc, cr, p.descriptor, out)
    }

    /** The bind_error road for a frame-free callee; a produced value is the call's result. */
    @TruffleBoundary
    private fun reportFrameFree(tc: ThreadContext, cr: CodeRef, csd: CallSiteDescriptor?, args: Array<Any?>) {
        val produced = BindFailure.reportFrameFree(tc, cr, csd, args)
        val caller = tc.curFrame ?: tc.dummyCaller
        caller.oRet = produced
        caller.retType = CallFrame.RET_OBJ.toByte()
    }

    /** The mapped road's direct entry, owning a frame-free callee's bind failure. */
    private fun enterMappedDirect(tc: ThreadContext, cr: CodeRef, cn: DirectCallNode,
                                  csd: CallSiteDescriptor?, out: Array<Any?>, callerHll: HLLConfig?) {
        try {
            enterDirect(tc, cr, cn, csd, out, callerHll)
        }
        catch (failure: NqpFrameFreeBindFailure) {
            reportFrameFree(tc, cr, csd, out)
        }
    }

    /**
     * spesh's inlining rule, at the call site: a callee runs frame-free
     * only in its caller's language. Every "current HLL" the runtime reads
     * (hllbool, hllize, the box types getattr uses for a native slot, ...)
     * comes from tc.curFrame's compilation unit, which for a frame-free
     * callee is the caller's; same language, same answer. A Raku accessor
     * entered from NQP dispatcher code built NQP's Bool -- null -- until
     * this. Both units are constants of the call node, so PE folds it.
     */
    private fun sameHll(cr: CodeRef, callerHll: HLLConfig?): Boolean =
        callerHll != null && NqpRaw.hll(NqpRaw.staticInfo(cr).compUnit) === callerHll

    /** JESP_HLLFREE=0 keeps every frame-free entry in its caller's language
     *  (diamond 5 behaviour); JESP_HLLFREE_TRACE=1 names, once each, the
     *  callees entered frame-free in another language (or from a unit whose
     *  language is not yet known) and what the caller's language was. */
    private val HLLFREE_ON: Boolean = System.getenv("JESP_HLLFREE") != "0"
    private val HLLFREE_TRACE: Boolean = System.getenv("JESP_HLLFREE_TRACE") != null
    private val hllFreeTraced = java.util.HashSet<String>()

    /** Whether a frame-free entry into `root` is allowed from a caller in
     *  `callerHll`: always in the same language; across languages (or from
     *  a caller whose language is unknown) only for a block the encoder
     *  marked as reading no current language (jesp diamond 7). */
    private fun frameFreeEntryOk(root: NqpRootNode, cr: CodeRef, callerHll: HLLConfig?): Boolean {
        if (sameHll(cr, callerHll)) return true
        /* Only a DIFFERENT language crosses. Two cases that are not one:
         * an unknown caller language (the caller's unit has no config yet:
         * still loading, binding its setting), and two configs of the SAME
         * language -- a bootstrap holds the compiler's `nqp` config and the
         * compilee's `nqp` config as distinct objects, and diamond 5 framed
         * every call between them. Letting those through as "cross-language"
         * ran the World's own methods frame-free with a mismatched config
         * during module setup and bound stage2's NQPHLL to stage1's setting. */
        if (callerHll == null) return false
        val calleeHll = NqpRaw.hll(NqpRaw.staticInfo(cr).compUnit) ?: return false
        val cn = calleeHll.name
        val rn = callerHll.name
        if (cn === rn || (cn != null && cn == rn)) return false
        if (!root.hllFree || !HLLFREE_ON) return false
        if (HLLFREE_TRACE) traceHllFree(cr, callerHll)
        return true
    }

    @TruffleBoundary
    private fun traceHllFree(cr: CodeRef, callerHll: HLLConfig?) {
        val key = (cr.name ?: "<anon>") + "@" + (callerHll?.name ?: "null")
        synchronized(hllFreeTraced) {
            if (hllFreeTraced.add(key))
                System.err.println("hllfree> " + (cr.name ?: "<anon>") + " ("
                    + (NqpRaw.hll(NqpRaw.staticInfo(cr).compUnit)?.name ?: "null")
                    + ") entered frame-free from caller language " + (callerHll?.name ?: "null"))
        }
    }

    /** The language of the frame we are in, for the invoke road (boundary code). */
    private fun currentHll(tc: ThreadContext): HLLConfig? {
        val cf = tc.curFrame ?: return null
        val cr = cf.codeRef ?: return null
        return NqpRaw.hll(NqpRaw.staticInfo(cr).compUnit)
    }

    /** The NqpRootNode behind an engine CallTarget, or null. */
    private fun engineRootOf(target: Any?): NqpRootNode? {
        if (target !is RootCallTarget) return null
        val root = target.rootNode
        return if (root is NqpRootNode) root else null
    }

    @TruffleBoundary
    private fun frameFreeSuspend(cr: CodeRef): RuntimeException {
        val name = cr.name
        return IllegalStateException(
            "continuation captured through a frame-free block (" +
            (if (name == null || name.isEmpty()) "<anon>" else name) + ")")
    }

    /**
     * Delivers a frame-free callee's return value into the caller's
     * registers, doing what a framed callee's StoreRet-into-cf.caller would
     * have done -- the caller then reads it with readResult exactly as
     * before. tc.curFrame is unchanged (the callee never had a frame), so
     * it is the caller.
     */
    private fun frameFreeResult(tc: ThreadContext, resultType: Int, r: Any?) {
        val caller = tc.curFrame ?: tc.dummyCaller
        NqpOps.storeReturnInto(resultType, r, caller)
    }

    /**
     * Enters an engine-bodied callee without its stub: the frame the stub's
     * prelude builds, the program run the way CodeEngines.codeRun runs it,
     * and the stub's postlude and Ops.invokeDirect's catches around it, in
     * that order and with their exact behaviour -- a control exception
     * leaves the frame and propagates; anything else goes to dieInternal
     * (which does not leave the frame, as the emitted catch arm does not);
     * a bind-return from a junction autothread lands its value as the
     * call's result in the caller's registers.
     */
    private fun enterEngine(tc: ThreadContext, cr: CodeRef, target: CallTarget,
                            csd: CallSiteDescriptor?, args: Array<Any?>) {
        val ffRoot = engineRootOf(target)
        if (ffRoot != null && !ffRoot.needsFrame && frameFreeEntryOk(ffRoot, cr, currentHll(tc))) {
            /* No CallFrame: the block proved frame-free. Its own StoreRet
             * (cf==null) only passes the value through, so the program's
             * return value is the block value; deliver it to the caller. */
            val r: Any?
            try {
                r = target.call(cr.staticInfo.compUnit, tc, null, csd, args, cr)
            }
            catch (sse: SaveStackException) { throw frameFreeSuspend(cr) }
            catch (u: NqpUnwind) { throw u.unwind }
            catch (h: NqpHostError) { throw dieInternal(tc, h.original) }
            catch (failure: NqpFrameFreeBindFailure) { reportFrameFree(tc, cr, csd, args); return }
            catch (ce: ControlException) { throw ce }
            catch (t: Throwable) { throw dieInternal(tc, t) }
            if (r is ContinuationResult) throw frameFreeSuspend(cr)
            frameFreeResult(tc, ffRoot.resultType, r)
            return
        }
        val callerFrame = tc.curFrame
        try {
            /* Frame construction walks the caller chain for an outer and
             * may auto-close; leave() may run an exit handler through
             * invokeDirect. Neither is PE-sized: both stay boundaries, and
             * only the program call itself is in the compiled code. */
            val cf = newFrame(tc, cr)
            try {
                NqpCodeEngine.runProgram(target, cr.staticInfo.compUnit, tc, cf, csd, args)
            }
            catch (ce: ControlException) {
                leave(cf)
                throw ce
            }
            catch (t: Throwable) {
                throw dieInternal(tc, t)
            }
            leave(cf)
        }
        catch (r: BindReturnException) {
            val caller = callerFrame ?: tc.dummyCaller
            caller.oRet = r.value
            caller.retType = CallFrame.RET_OBJ.toByte()
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            /* As Ops.invokeDirect: dieInternal unwinds to a handler itself;
             * what it returns is not thrown. */
            dieInternal(tc, e)
        }
    }

    /** A target exists or can be made from the unit (artifact road): the
     *  volatile read stays PE-visible, the compile goes behind a boundary. */
    private fun hasTarget(sci: StaticCodeInfo): Boolean =
        sci.engineTarget != null || (sci.programIndex >= 0 && materializeBoundary(sci) != null)

    @TruffleBoundary
    private fun materializeBoundary(sci: StaticCodeInfo): Any? = CodeEngines.materialize(sci)

    /** Adopts a call node for the callee's engine target, or null if the
     *  callee has no registered target yet (it has not run once). */
    @TruffleBoundary
    private fun adoptCallNode(p: Program, cr: CodeRef, node: Node): DirectCallNode? {
        val target = CodeEngines.materialize(cr.staticInfo)
        if (target !is CallTarget || cr.staticInfo.argsExpectation != ArgsExpectation.USE_BINDER)
            return null
        val cn = node.insert(DirectCallNode.create(target))
        p.callNode = cn
        return cn
    }

    /**
     * The direct entry through an adopted call node: the frame the stub's
     * prelude builds (behind a boundary), the call itself in compiled code
     * so the callee can inline, and the engine's own postlude for the
     * result -- a suspend token joins the resume chain, an unwind leaves
     * the frame and flies on as the Truffle carrier it already is.
     */
    private fun enterDirect(tc: ThreadContext, cr: CodeRef, cn: DirectCallNode,
                            csd: CallSiteDescriptor?, args: Array<Any?>, callerHll: HLLConfig?) {
        /* A frame-free callee runs with cf==null: no CallFrame is built and
         * none is left, so partial evaluation scalar-replaces the callee's
         * VirtualFrame across this inlined call -- the whole point of the
         * port. root.needsFrame is constant for this call node, so the
         * branch folds. */
        val root = engineRootOf(cn.callTarget)
        val framed = root == null || root.needsFrame || !frameFreeEntryOk(root, cr, callerHll)
        val cf = if (framed) newFrame(tc, cr) else null
        val r: Any?
        try {
            r = cn.call(NqpRaw.staticInfo(cr).compUnit, tc, cf, csd, args, cr)
        }
        catch (u: NqpUnwind) {
            if (cf != null) leave(cf)
            throw u
        }
        catch (h: NqpHostError) {
            throw dieInternal(tc, h.original)
        }
        catch (br: BindReturnException) {
            /* As enterEngine: a bind_error handler stood in for the call
             * (a Junction autothread); its value is the call's result. */
            if (cf != null) leave(cf)
            val caller = tc.curFrame ?: tc.dummyCaller
            caller.oRet = br.value
            caller.retType = CallFrame.RET_OBJ.toByte()
            return
        }
        catch (ce: ControlException) {
            if (cf != null) leave(cf)
            throw ce
        }
        catch (t: Throwable) {
            throw dieInternal(tc, t)
        }
        if (r is ContinuationResult) {
            if (cf == null) throw frameFreeSuspend(cr)
            throw NqpCodeEngine.suspendFrame(r, cf)
        }
        if (cf != null) leave(cf)
        else frameFreeResult(tc, root!!.resultType, r)
    }

    @TruffleBoundary
    private fun newFrame(tc: ThreadContext, cr: CodeRef): CallFrame = CallFrame(tc, cr)

    @TruffleBoundary
    private fun leave(cf: CallFrame) { cf.leave() }

    @TruffleBoundary
    private fun dieInternal(tc: ThreadContext, t: Throwable): RuntimeException =
        ExceptionHandling.dieInternal(tc, t)

    /* ----- the roads that stay on the bytecode side ----- */

    @JvmStatic
    @TruffleBoundary
    fun dispatchFlattening(site: DispatchCallSite, name: String, csd: CallSiteDescriptor,
                           tc: ThreadContext, args: Array<Any?>) {
        Dispatch.dispatchWithDescriptor(site, name, csd, tc, args)
    }
}
