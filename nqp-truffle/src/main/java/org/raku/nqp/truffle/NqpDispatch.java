package org.raku.nqp.truffle;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;

import org.raku.nqp.dispatch.ArgKind;
import org.raku.nqp.dispatch.BindFailureException;
import org.raku.nqp.dispatch.BindReturnException;
import org.raku.nqp.dispatch.DispatchRecord;
import org.raku.nqp.dispatch.Captures;
import org.raku.nqp.dispatch.Dispatch;
import org.raku.nqp.dispatch.DispatchCallSite;
import org.raku.nqp.dispatch.DispatchCompiler;
import org.raku.nqp.dispatch.DispatchProgram;
import org.raku.nqp.dispatch.DispatchValue;
import org.raku.nqp.dispatch.Guard;
import org.raku.nqp.dispatch.Outcome;
import org.raku.nqp.dispatch.Syscall;
import org.raku.nqp.dispatch.ValueSource;
import org.raku.nqp.runtime.ArgsExpectation;
import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CodeRef;
import org.raku.nqp.runtime.ControlException;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.HLLConfig;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.sixmodel.STable;
import org.raku.nqp.sixmodel.SixModelObject;
import org.raku.nqp.sixmodel.TypeObject;
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance;
import org.raku.nqp.sixmodel.reprs.P6OpaqueDelegateInstance;
import org.raku.nqp.sixmodel.reprs.P6OpaqueREPRData;

/**
 * The Truffle side of a dispatch instruction's inline cache.
 *
 * <p>A settled dispatch site on the bytecode side replays its recorded
 * {@link DispatchProgram}s through a MethodHandle guard chain
 * ({@link DispatchCompiler}); from an engine-run block that chain sat
 * behind a {@code @TruffleBoundary}, so partial evaluation saw one opaque
 * call where MoarVM's specializer sees the guards. Here the same programs
 * are kept per instruction as a compilation-final array, and their guards
 * and outcomes are evaluated by plain Java that PE folds: a constant
 * program's guard list explodes into straight-line type/identity tests
 * against the argument array, and the outcome into a register store or a
 * call.
 *
 * <p>The value sources a program reads are folded once, when the program
 * is admitted. An attribute read (the decont of a Scalar argument, a Code
 * object's {@code $!do}) is only ever recorded behind a type guard on the
 * object it reads from -- the same contract MoarVM's tracked attributes
 * impose -- so the guarded type's storage class and the attribute's slot
 * hint are both known here, and the read replays as an exact-class cast
 * plus the generated accessor's switch on a constant: a field load after
 * PE, where the interpreted road looked the name up in a hash per dispatch.
 * A source whose object type is not fixed by a guard crosses a boundary
 * into the runtime's generic accessor instead.
 *
 * <p>Nothing about recording changes. A miss takes {@link Dispatch#fallback},
 * exactly the tail a compiled chain falls into -- it tries the programs the
 * cache does not cover and records afresh, installing at the site -- and
 * then the cache is rebuilt from the site's program list. Every rebuild, and
 * the per-eval-server-run reset, replaces the array under a fresh
 * {@link Assumption}, so compiled code that folded the old array is
 * invalidated rather than left replaying a stale prefix. The cacheable prefix
 * is what {@code DispatchCompiler.compilable} admits: initial dispatches
 * whose recorded shape is this instruction's shape; resuming programs walk
 * the live callstack and stay on the interpreted road.
 */
final class NqpDispatch {
    private NqpDispatch() {}

    /** How many programs an instruction folds in; the chain's MAX_COMPILED twin. */
    static final int MAX_CACHED = 8;

    static final Program[] NONE = new Program[0];

    static final int K_VALUE = 0, K_SYSCALL = 1, K_INVOKE_MAPPED = 2, K_INVOKE = 3,
        K_INVOKE_RESUMABLE = 4;

    /* ----- value sources, folded ----- */

    /** A value source as PE-visible code; see the class comment. */
    abstract static class Src {
        abstract Object eval(ThreadContext tc, Object[] args);
    }

    static final class ArgSrc extends Src {
        final int index;
        ArgSrc(int index) { this.index = index; }
        @Override Object eval(ThreadContext tc, Object[] args) { return args[index]; }
    }

    static final class LitSrc extends Src {
        final Object value;
        LitSrc(Object value) { this.value = value; }
        @Override Object eval(ThreadContext tc, Object[] args) { return value; }
    }

    static final class HowSrc extends Src {
        final Src from;
        HowSrc(Src from) { this.from = from; }
        @Override Object eval(ThreadContext tc, Object[] args) {
            Object v = from.eval(tc, args);
            if (!(v instanceof SixModelObject smo) || smo.st == null) return null;
            return smo.st.HOW;
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
    static final class AttrSrc extends Src {
        final Src from;
        final Class<?> storage;
        final MethodHandle getter;
        final SixModelObject classHandle;
        final String name;
        final ArgKind kind;
        AttrSrc(Src from, Class<?> storage, MethodHandle getter, SixModelObject classHandle,
                String name, ArgKind kind) {
            this.from = from;
            this.storage = storage;
            this.getter = getter;
            this.classHandle = classHandle;
            this.name = name;
            this.kind = kind;
        }
        @Override Object eval(ThreadContext tc, Object[] args) {
            Object o = from.eval(tc, args);
            Object target = o instanceof P6OpaqueDelegateInstance d ? d.delegate : o;
            if (target != null && target.getClass() == storage
                    && ((P6OpaqueBaseInstance) target).delegate == null) {
                SixModelObject v;
                try {
                    v = (SixModelObject) getter.invokeExact((SixModelObject) target);
                } catch (Throwable t) {
                    throw CompilerDirectives.shouldNotReachHere(t);
                }
                /* Null: not yet vivified (or genuinely null); the accessor
                 * decides which and vivifies. */
                if (v != null) return v;
            }
            return slow(tc, o);
        }
        @TruffleBoundary
        private Object slow(ThreadContext tc, Object o) {
            if (STATS) {
                count(slowEvals);
                Object target = o instanceof P6OpaqueDelegateInstance d ? d.delegate : o;
                String key = "slow attr " + name + " of "
                    + (o == null ? "null" : o.getClass().getName()) + "/"
                    + (target == null ? "null" : target.getClass().getName())
                    + " vs " + storage.getName()
                    + (target instanceof P6OpaqueBaseInstance b && b.delegate != null ? " (delegating)" : "");
                if (seenSlow.add(key)) System.err.println("dispatch " + key);
            }
            if (o == null)
                throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(tc,
                    "Dispatch program read an attribute of a null value");
            return ValueSource.Companion.readAttribute(tc, (SixModelObject) o, classHandle, name, kind);
        }
    }

    /** An unbox of an object whose storage class is known, likewise. */
    static final class UnboxSrc extends Src {
        final Src from;
        final Class<?> storage;
        final ArgKind kind;
        UnboxSrc(Src from, Class<?> storage, ArgKind kind) {
            this.from = from;
            this.storage = storage;
            this.kind = kind;
        }
        @Override Object eval(ThreadContext tc, Object[] args) {
            Object o = from.eval(tc, args);
            Object target = o instanceof P6OpaqueDelegateInstance d ? d.delegate : o;
            if (target != null && target.getClass() == storage) {
                SixModelObject smo = (SixModelObject) CompilerDirectives.castExact(target, storage);
                switch (kind) {
                    case INT: case UINT: return smo.get_int(tc);
                    case NUM: return smo.get_num(tc);
                    default: return smo.get_str(tc);
                }
            }
            return slow(tc, o);
        }
        @TruffleBoundary
        private Object slow(ThreadContext tc, Object o) {
            if (STATS) {
                count(slowEvals);
                String key = "slow unbox " + kind + " of " + (o == null ? "null" : o.getClass().getName())
                    + " vs " + storage.getName();
                if (seenSlow.add(key)) System.err.println("dispatch " + key);
            }
            return ValueSource.Companion.unbox(tc, (SixModelObject) o, kind);
        }
    }

    /** Everything else: the runtime's generic evaluator, across a boundary. */
    static final class SlowSrc extends Src {
        final ValueSource source;
        SlowSrc(ValueSource source) { this.source = source; }
        @Override @TruffleBoundary
        Object eval(ThreadContext tc, Object[] args) {
            if (STATS) {
                count(slowEvals);
                String key = "slow source " + source;
                if (seenSlow.add(key)) System.err.println("dispatch " + key);
            }
            return DispatchCompiler.evalRaw(source, tc, args);
        }
    }

    /* ----- guards, folded ----- */

    abstract static class Chk {
        final Src on;
        Chk(Src on) { this.on = on; }
        abstract boolean test(ThreadContext tc, Object[] args);
    }

    static final class TypeChk extends Chk {
        final STable type;
        TypeChk(Src on, STable type) { super(on); this.type = type; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            Object v = on.eval(tc, args);
            return v instanceof SixModelObject smo && smo.st == type;
        }
    }

    static final class ConcreteChk extends Chk {
        final boolean concrete;
        ConcreteChk(Src on, boolean concrete) { super(on); this.concrete = concrete; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            Object v = on.eval(tc, args);
            return (v != null && !(v instanceof TypeObject)) == concrete;
        }
    }

    static final class IdChk extends Chk {
        final Object expected;
        IdChk(Src on, Object expected) { super(on); this.expected = expected; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            return on.eval(tc, args) == expected;
        }
    }

    static final class EqChk extends Chk {
        final Object expected;
        EqChk(Src on, Object expected) { super(on); this.expected = expected; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            return Objects.equals(on.eval(tc, args), expected);
        }
    }

    static final class NotIdChk extends Chk {
        final Object rejected;
        NotIdChk(Src on, Object rejected) { super(on); this.rejected = rejected; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            return on.eval(tc, args) != rejected;
        }
    }

    static final class HllChk extends Chk {
        final HLLConfig hll;
        HllChk(Src on, HLLConfig hll) { super(on); this.hll = hll; }
        @Override boolean test(ThreadContext tc, Object[] args) {
            Object v = on.eval(tc, args);
            return v instanceof SixModelObject smo && smo.st != null && smo.st.hllOwner == hll;
        }
    }

    /**
     * One installed program with its guards and outcome folded, so the
     * per-dispatch work is only the tests themselves. Every field is
     * final: the object is a constant of the compiled code, and finals fold.
     */
    static final class Program {
        final DispatchProgram program;
        @CompilationFinal(dimensions = 1) final Chk[] guards;
        final int kind;
        final Src source;
        final ArgKind valueKind;
        final Syscall syscall;
        @CompilationFinal(dimensions = 1) final Src[] plan;
        final CallSiteDescriptor descriptor;
        final Src callee;
        final SixModelObject calleeLiteral;
        @CompilationFinal(dimensions = 1) final int[] map;

        /**
         * The call node for a literal engine-bodied callee, adopted under
         * the dispatch instruction's node the first time the program
         * replays there, so Truffle's inliner sees the call. Replaced when
         * the instruction's node changes (the uncached-to-cached tier
         * transition), since a call node under a dead parent inlines
         * nowhere. Null for any other callee, and until the callee's
         * program has run once and registered its target.
         */
        @CompilationFinal DirectCallNode callNode;
        /** How many times the call node was re-adopted under a changed
         *  instruction node; past a few, the node in hand is used as is. */
        @CompilationFinal int readopts;

        /**
         * Folds the program. The guards are walked in recorded order, and
         * each type guard fixes the storage class of what it guards for the
         * sources built after it -- which is what lets an attribute read of
         * that object resolve its slot here.
         */
        Program(DispatchProgram p, CallSiteDescriptor csd, ThreadContext tc) {
            program = p;
            Folder f = new Folder(csd, tc);
            List<Guard> gs = p.getGuards();
            Chk[] guards = new Chk[gs.size()];
            for (int i = 0; i < guards.length; i++) guards[i] = f.fold(gs.get(i));
            this.guards = guards;

            int kind;
            Src source = null;
            ArgKind valueKind = null;
            Syscall syscall = null;
            Src[] plan = null;
            CallSiteDescriptor descriptor = null;
            Src callee = null;
            SixModelObject calleeLiteral = null;
            int[] map = null;
            Outcome o = p.getOutcome();
            if (o instanceof Outcome.Value v) {
                kind = K_VALUE;
                source = f.fold(v.getSource());
                valueKind = f.kindOf(v.getSource());
            }
            else if (o instanceof Outcome.InvokeSyscall sc) {
                kind = K_SYSCALL;
                syscall = sc.getSyscall();
                plan = f.fold(sc.getArgs().getSources());
                descriptor = sc.getArgs().getDescriptor();
            }
            else {
                Outcome.InvokeCode ic = (Outcome.InvokeCode) o;
                List<ValueSource> sources = ic.getArgs().getSources();
                descriptor = ic.getArgs().getDescriptor();
                ValueSource c = ic.getCallee();
                if (p.getResumptions().isEmpty() && p.getBindControl() == null) {
                    int[] m = c instanceof ValueSource.Literal ? argMap(sources) : null;
                    if (m != null) {
                        kind = K_INVOKE_MAPPED;
                        calleeLiteral = (SixModelObject) ((ValueSource.Literal) c).getValue();
                        map = m;
                    }
                    else {
                        kind = K_INVOKE;
                        callee = f.fold(c);
                        plan = f.fold(sources);
                    }
                }
                else {
                    kind = K_INVOKE_RESUMABLE;
                    callee = f.fold(c);
                    plan = f.fold(sources);
                }
            }
            this.kind = kind;
            this.source = source;
            this.valueKind = valueKind;
            this.syscall = syscall;
            this.plan = plan;
            this.descriptor = descriptor;
            this.callee = callee;
            this.calleeLiteral = calleeLiteral;
            this.map = map;
            if (STATS) dump(p, kind, guards);
        }

        @TruffleBoundary
        private static void dump(DispatchProgram p, int kind, Chk[] guards) {
            StringBuilder sb = new StringBuilder("dispatch fold: kind=").append(kind).append(" guards=[");
            for (Chk c : guards) sb.append(c.getClass().getSimpleName()).append('(')
                .append(c.on.getClass().getSimpleName()).append(") ");
            sb.append("] outcome=").append(p.getOutcome());
            System.err.println(sb);
        }

        /** An arguments-only plan as bare indices, or null. */
        private static int[] argMap(List<ValueSource> plan) {
            int[] map = new int[plan.size()];
            for (int i = 0; i < map.length; i++) {
                if (!(plan.get(i) instanceof ValueSource.Arg a)) return null;
                map[i] = a.getIndex();
            }
            return map;
        }
    }

    /** Builds the folded sources and guards of one program, in order. */
    static final class Folder {
        final CallSiteDescriptor csd;
        final ThreadContext tc;
        /** Sources whose type a guard has fixed so far, structurally keyed. */
        final Map<ValueSource, STable> known = new HashMap<>();

        Folder(CallSiteDescriptor csd, ThreadContext tc) {
            this.csd = csd;
            this.tc = tc;
        }

        Chk fold(Guard g) {
            Src on = fold(g.getOn());
            if (g instanceof Guard.OfType t) {
                known.put(g.getOn(), t.getType());
                return new TypeChk(on, t.getType());
            }
            if (g instanceof Guard.Concreteness c) return new ConcreteChk(on, c.getConcrete());
            if (g instanceof Guard.Literal l) {
                DispatchValue e = l.getExpected();
                if (e.getKind() == ArgKind.OBJ) {
                    /* An identity guard fixes the object, hence its type. */
                    if (e.getValue() instanceof SixModelObject smo && smo.st != null)
                        known.put(g.getOn(), smo.st);
                    return new IdChk(on, e.getValue());
                }
                return new EqChk(on, e.getValue());
            }
            if (g instanceof Guard.NotLiteralObj n) return new NotIdChk(on, n.getRejected());
            if (g instanceof Guard.OfHll h) return new HllChk(on, h.getHll());
            throw new IllegalStateException("unfoldable dispatch guard " + g);
        }

        Src[] fold(List<ValueSource> sources) {
            Src[] out = new Src[sources.size()];
            for (int i = 0; i < out.length; i++) out[i] = fold(sources.get(i));
            return out;
        }

        Src fold(ValueSource s) {
            if (s instanceof ValueSource.Arg a) return new ArgSrc(a.getIndex());
            if (s instanceof ValueSource.Literal l) return new LitSrc(l.getValue());
            if (s instanceof ValueSource.How h) return new HowSrc(fold(h.getFrom()));
            if (s instanceof ValueSource.Attribute at && at.getKind() == ArgKind.OBJ) {
                Class<?> storage = storageOf(at.getFrom());
                if (storage != null) {
                    STable st = known.get(at.getFrom());
                    long hint = st.REPR.hint_for(tc, st, at.getClassHandle(), at.getName());
                    MethodHandle getter = hint == STable.NO_HINT ? null : getterFor(st, storage, (int) hint);
                    if (getter != null)
                        return new AttrSrc(fold(at.getFrom()), storage, getter,
                            at.getClassHandle(), at.getName(), at.getKind());
                }
            }
            if (s instanceof ValueSource.Unbox u) {
                Class<?> storage = storageOf(u.getFrom());
                if (storage != null && u.getKind() != ArgKind.OBJ)
                    return new UnboxSrc(fold(u.getFrom()), storage, u.getKind());
            }
            if (STATS) dumpSlow(s, known);
            return new SlowSrc(s);
        }

        @TruffleBoundary
        private static void dumpSlow(ValueSource s, Map<ValueSource, STable> known) {
            System.err.println("dispatch slow source: " + s + " known=" + known.keySet());
        }

        /**
         * A (SixModelObject)SixModelObject getter for the slot's field, or
         * null when the slot is not a reference field.
         */
        private static MethodHandle getterFor(STable st, Class<?> storage, int slot) {
            MethodHandle[] hs = fieldHandles(storage, slot);
            return hs == null ? null : hs[0];
        }

        /** The exact storage class of a source a type guard has fixed. */
        private Class<?> storageOf(ValueSource from) {
            STable st = known.get(from);
            if (st == null) return null;
            if (!(st.REPRData instanceof P6OpaqueREPRData rd)) return null;
            return rd.jvmClass;
        }

        /** The kind a source produces, which the callsite shape fixes. */
        ArgKind kindOf(ValueSource s) {
            if (s instanceof ValueSource.Arg a)
                return ArgKind.Companion.ofFlag(csd.argFlags[a.getIndex()]);
            if (s instanceof ValueSource.Literal l) return l.getKind();
            if (s instanceof ValueSource.Attribute at) return at.getKind();
            if (s instanceof ValueSource.Unbox u) return u.getKind();
            return ArgKind.OBJ;
        }
    }

    /**
     * The cache itself: the site's replayable prefix plus the assumption
     * compiled code folds it under. Owned by an {@link NqpOps.EngineSite}.
     */
    static final class Cache {
        final DispatchCallSite site;
        final CallSiteDescriptor csd;
        @CompilationFinal(dimensions = 1) Program[] programs = NONE;
        @CompilationFinal Assumption stable = Truffle.getRuntime().createAssumption("dispatch site");
        /** Misses since the last refold; see REFOLD_AFTER. */
        int missesSinceFold;

        Cache(DispatchCallSite site, CallSiteDescriptor csd) {
            this.site = site;
            this.csd = csd;
            /* The per-run reset clears the bytecode-side site; the folded
             * prefix has to go with it, or compiled code keeps replaying
             * programs that guard on a finished run's types. */
            site.onReset = this::reset;
        }

        /** Rebuilds the prefix from the site's programs after a miss. */
        synchronized void refresh(ThreadContext tc) {
            DispatchProgram[] all = site.programs;
            int n = 0;
            while (n < all.length && n < MAX_CACHED && cacheable(all[n])) n++;
            Program[] current = programs;
            if (current.length == n) {
                boolean same = true;
                for (int i = 0; i < n; i++)
                    if (current[i].program != all[i]) { same = false; break; }
                if (same) { missesSinceFold = 0; return; }
            }
            Program[] fresh = new Program[n];
            for (int i = 0; i < n; i++)
                fresh[i] = i < current.length && current[i].program == all[i]
                    ? current[i] : new Program(all[i], csd, tc);
            publish(fresh);
        }

        synchronized void reset() {
            if (programs.length != 0) publish(NONE);
        }

        /* New array and new assumption first, then the old assumption goes:
         * code that folded the old array deoptimizes and re-reads. A
         * thread still in the old code between the two steps replays the
         * old programs, which remain valid programs. */
        private void publish(Program[] fresh) {
            missesSinceFold = 0;
            Assumption old = stable;
            programs = fresh;
            stable = Truffle.getRuntime().createAssumption("dispatch site");
            old.invalidate();
        }

        private boolean cacheable(DispatchProgram p) {
            return !p.isResuming() && Captures.INSTANCE.sameShape(p.getDescriptor(), csd);
        }
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
    static MethodHandle[] fieldHandles(Class<?> storage, int slot) {
        try {
            java.lang.reflect.Field f = storage.getField("field_" + slot);
            if (f.getType() != SixModelObject.class) return null;
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return new MethodHandle[] {
                lookup.unreflectGetter(f)
                    .asType(MethodType.methodType(SixModelObject.class, SixModelObject.class)),
                lookup.unreflectSetter(f)
                    .asType(MethodType.methodType(void.class, SixModelObject.class, SixModelObject.class)),
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /* ----- counters (NQP_DISPATCH_STATS=1 prints them at exit) ----- */

    static final java.util.Set<String> seenSlow = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> noTargetBy =
        new java.util.concurrent.ConcurrentHashMap<>();

    static final boolean STATS = System.getenv("NQP_DISPATCH_STATS") != null;
    static final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong(),
        misses = new java.util.concurrent.atomic.AtomicLong(),
        slowEvals = new java.util.concurrent.atomic.AtomicLong(),
        invokes = new java.util.concurrent.atomic.AtomicLong(),
        directs = new java.util.concurrent.atomic.AtomicLong(),
        noTarget = new java.util.concurrent.atomic.AtomicLong(),
        badExpectation = new java.util.concurrent.atomic.AtomicLong(),
        notCodeRef = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong[] hitsByKind = {
        new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(),
        new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(),
        new java.util.concurrent.atomic.AtomicLong() };
    static {
        if (STATS) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("dispatch stats: hits=" + hits + " misses=" + misses
                + " slowEvals=" + slowEvals + " invokes=" + invokes + " directs=" + directs
                + " noTarget=" + noTarget + " badExpectation=" + badExpectation + " notCodeRef=" + notCodeRef
                + " byKind[value,syscall,mapped,invoke,resumable]=" + java.util.Arrays.toString(hitsByKind));
            noTargetBy.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get())).limit(10)
                .forEach(e -> System.err.println("  noTarget " + e.getValue() + " " + e.getKey()));
        }));
    }

    @TruffleBoundary
    private static void count(java.util.concurrent.atomic.AtomicLong c) { c.incrementAndGet(); }

    /* ----- the fast path ----- */

    /**
     * Tries the folded programs in order; true when one applied and its
     * outcome has run. The array read folds to a constant under the
     * assumption, so the loop explodes into the programs' tests.
     */
    @ExplodeLoop
    static boolean replay(Cache cache, ThreadContext tc, Object[] args, Node node) {
        if (!cache.stable.isValid()) CompilerDirectives.transferToInterpreterAndInvalidate();
        Program[] programs = cache.programs;
        for (int i = 0; i < programs.length; i++) {
            Program p = programs[i];
            if (matches(p, tc, args)) {
                if (STATS) { count(hits); count(hitsByKind[p.kind]); }
                realize(p, cache.site, tc, args, node);
                return true;
            }
        }
        return false;
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
    static final int REFOLD_AFTER = 16;

    /** The chain's tail: uncached programs, then a recording; then refold. */
    @TruffleBoundary
    static void miss(Cache cache, String name, ThreadContext tc, Object[] args) {
        if (STATS) count(misses);
        Dispatch.fallback(cache.site, name, cache.csd, cache.programs.length, tc, args);
        if (cache.programs.length == 0 || ++cache.missesSinceFold >= REFOLD_AFTER)
            cache.refresh(tc);
    }

    @ExplodeLoop
    private static boolean matches(Program p, ThreadContext tc, Object[] args) {
        Chk[] guards = p.guards;
        for (int i = 0; i < guards.length; i++)
            if (!guards[i].test(tc, args)) return false;
        return true;
    }

    /* ----- outcomes ----- */

    private static void realize(Program p, DispatchCallSite site, ThreadContext tc,
                                Object[] args, Node node) {
        /* A literal callee with an engine body: through the adopted call
         * node, so the callee inlines into this root. */
        if (p.calleeLiteral instanceof CodeRef cr && (p.kind == K_INVOKE_MAPPED
                || (p.kind == K_INVOKE_RESUMABLE && p.callee instanceof LitSrc))
                && cr.staticInfo.argsExpectation == ArgsExpectation.USE_BINDER) {
            DirectCallNode cn = p.callNode;
            /* Adopt only once a target exists (a bytecode-bodied callee
             * never has one; one not yet run has none yet): the check is
             * a volatile load, the adoption a deoptimization -- doing the
             * latter on every replay of a target-less callee was a deopt
             * cycle. Re-adopt when the instruction's node changed, a few
             * times at most. */
            if (cn == null) {
                if (cr.staticInfo.engineTarget != null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cn = adoptCallNode(p, cr, node);
                }
            }
            else if (cn.getParent() != node && p.readopts < 4) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                p.readopts++;
                cn = adoptCallNode(p, cr, node);
            }
            if (cn != null) {
                Object[] out = p.kind == K_INVOKE_MAPPED ? mapArgs(p.map, args) : evalPlan(p.plan, tc, args);
                if (p.kind == K_INVOKE_MAPPED) {
                    enterDirect(tc, cr, cn, p.descriptor, out);
                } else {
                    DispatchRecord record = pushRecord(tc, p.program, args, site);
                    try {
                        tc.pendingDispatch = record;
                        try {
                            enterDirect(tc, cr, cn, p.descriptor, out);
                        }
                        catch (BindFailureException failure) {
                            if (failure.getRecord() != record) throw failure;
                            resumeAfterBindFailure(tc, record, failure.getFlag());
                        }
                        finally {
                            tc.pendingDispatch = null;
                        }
                    }
                    finally {
                        popRecord(tc);
                    }
                }
                return;
            }
        }
        switch (p.kind) {
            case K_VALUE: {
                CallFrame frame = tc.curFrame;
                Object v = p.source.eval(tc, args);
                switch (p.valueKind) {
                    case OBJ:
                        frame.oRet = (SixModelObject) v;
                        frame.retType = (byte) CallFrame.RET_OBJ;
                        break;
                    case INT:
                        frame.iRet = (Long) v;
                        frame.retType = (byte) CallFrame.RET_INT;
                        break;
                    case UINT:
                        frame.iRet = (Long) v;
                        frame.retType = (byte) CallFrame.RET_UINT;
                        break;
                    case NUM:
                        frame.nRet = (Double) v;
                        frame.retType = (byte) CallFrame.RET_NUM;
                        break;
                    default:
                        frame.sRet = (String) v;
                        frame.retType = (byte) CallFrame.RET_STR;
                        break;
                }
                break;
            }
            case K_SYSCALL:
                syscall(p, tc, evalPlan(p.plan, tc, args));
                break;
            case K_INVOKE_MAPPED:
                invoke(tc, p.calleeLiteral, p.descriptor, mapArgs(p.map, args));
                break;
            case K_INVOKE:
                invoke(tc, (SixModelObject) p.callee.eval(tc, args), p.descriptor,
                    evalPlan(p.plan, tc, args));
                break;
            default:
                invokeResumable(p, site, tc, args);
                break;
        }
    }

    @ExplodeLoop
    private static Object[] mapArgs(int[] map, Object[] args) {
        Object[] out = new Object[map.length];
        for (int i = 0; i < map.length; i++) out[i] = args[map[i]];
        return out;
    }

    @ExplodeLoop
    private static Object[] evalPlan(Src[] plan, ThreadContext tc, Object[] args) {
        Object[] out = new Object[plan.length];
        for (int i = 0; i < plan.length; i++) out[i] = plan[i].eval(tc, args);
        return out;
    }

    @TruffleBoundary
    private static void syscall(Program p, ThreadContext tc, Object[] out) {
        Dispatch.setFrameResult(tc.curFrame, p.syscall.call(tc, p.descriptor, out));
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
     * inlinable direct call is a DirectCallNode's job, later.
     */
    @TruffleBoundary
    private static void invoke(ThreadContext tc, SixModelObject callee,
                               CallSiteDescriptor descriptor, Object[] out) {
        if (STATS) count(invokes);
        if (callee instanceof CodeRef cr) {
            Object target = cr.staticInfo.engineTarget;
            if (target != null && cr.staticInfo.argsExpectation == ArgsExpectation.USE_BINDER) {
                if (STATS) count(directs);
                enterEngine(tc, cr, (CallTarget) target, descriptor, out);
                return;
            }
            if (STATS) countRefused(cr, target == null);
        }
        else if (STATS) count(notCodeRef);
        invokeBoundary(tc, callee, descriptor, out);
    }

    /* Diagnostics stay behind boundaries too: getSimpleName's reflection
     * road inlined under PE was itself a "too deep inlining" bailout. */
    @TruffleBoundary
    private static void countRefused(CodeRef cr, boolean noTargetCase) {
        count(noTargetCase ? noTarget : badExpectation);
        String key = (cr.name == null || cr.name.isEmpty() ? "<anon>" : cr.name)
            + " " + cr.staticInfo.compUnit.getClass().getSimpleName();
        noTargetBy.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    @TruffleBoundary
    private static void invokeBoundary(ThreadContext tc, SixModelObject callee,
                                       CallSiteDescriptor descriptor, Object[] out) {
        Ops.invokeDirect(tc, callee, descriptor, out);
    }

    /**
     * A program that set up resumptions or bind control: the record must be
     * live while the callee runs, so that the callee can resume this
     * dispatch or fail its bind back into it. DispatchCompiler.invokeResumable,
     * over the folded plan, with the record's bookkeeping PE-visible.
     */
    private static void invokeResumable(Program p, DispatchCallSite site, ThreadContext tc,
                                        Object[] args) {
        SixModelObject callee = (SixModelObject) p.callee.eval(tc, args);
        Object[] out = evalPlan(p.plan, tc, args);
        invokeResumableBoundary(p, site, tc, args, callee, out);
    }

    @TruffleBoundary
    private static void invokeResumableBoundary(Program p, DispatchCallSite site, ThreadContext tc,
                                                Object[] args, SixModelObject callee, Object[] out) {
        /* The record list is an ArrayList: its add/remove stay behind
         * boundaries, because PE inlines the JDK's bounds-check slow paths
         * (Preconditions -> Formatter -> Locale) until Graal bails out of
         * the whole compilation with "too deep inlining" -- seen, and the
         * root then runs interpreted for good. */
        DispatchRecord record = pushRecord(tc, p.program, args, site);
        try {
            tc.pendingDispatch = record;
            try {
                invoke(tc, callee, p.descriptor, out);
            }
            catch (BindFailureException failure) {
                if (failure.getRecord() != record) throw failure;
                resumeAfterBindFailure(tc, record, failure.getFlag());
            }
            finally {
                tc.pendingDispatch = null;
            }
        }
        finally {
            popRecord(tc);
        }
    }

    @TruffleBoundary
    private static DispatchRecord pushRecord(ThreadContext tc, DispatchProgram program,
                                             Object[] args, DispatchCallSite site) {
        DispatchRecord record = new DispatchRecord(tc, null, program.getDescriptor(), args,
            tc.curFrame, site);
        record.setProgram(program);
        record.endRecording();
        tc.dispatchRecords.add(record);
        return record;
    }

    @TruffleBoundary
    private static void popRecord(ThreadContext tc) {
        List<DispatchRecord> records = tc.dispatchRecords;
        records.remove(records.size() - 1);
    }

    @TruffleBoundary
    private static void resumeAfterBindFailure(ThreadContext tc, DispatchRecord record, long flag) {
        Dispatch.resumeAfterBindFailure(tc, record, flag);
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
    /** The NqpRootNode behind an engine CallTarget, or null. */
    private static NqpRootNode engineRootOf(Object target) {
        return target instanceof com.oracle.truffle.api.RootCallTarget rct
            && rct.getRootNode() instanceof NqpRootNode r ? r : null;
    }

    @TruffleBoundary
    private static RuntimeException frameFreeSuspend(CodeRef cr) {
        return new IllegalStateException(
            "continuation captured through a frame-free block ("
            + (cr.name == null || cr.name.isEmpty() ? "<anon>" : cr.name) + ")");
    }

    /**
     * Delivers a frame-free callee's return value into the caller's
     * registers, doing what a framed callee's StoreRet-into-cf.caller would
     * have done -- the caller then reads it with readResult exactly as
     * before. tc.curFrame is unchanged (the callee never had a frame), so
     * it is the caller.
     */
    private static void frameFreeResult(ThreadContext tc, int resultType, Object r) {
        CallFrame caller = tc.curFrame != null ? tc.curFrame : tc.dummyCaller;
        NqpOps.storeReturnInto(resultType, r, caller);
    }

    private static void enterEngine(ThreadContext tc, CodeRef cr, CallTarget target,
                                    CallSiteDescriptor csd, Object[] args) {
        NqpRootNode ffRoot = engineRootOf(target);
        if (ffRoot != null && !ffRoot.needsFrame) {
            /* No CallFrame: the block proved frame-free. Its own StoreRet
             * (cf==null) only passes the value through, so the program's
             * return value is the block value; deliver it to the caller. */
            Object r;
            try {
                r = target.call(cr.staticInfo.compUnit, tc, null, csd, args);
            }
            catch (org.raku.nqp.runtime.SaveStackException sse) { throw frameFreeSuspend(cr); }
            catch (NqpUnwind u) { throw u.unwind; }
            catch (NqpHostError h) { throw dieInternal(tc, h.original); }
            catch (ControlException ce) { throw ce; }
            catch (Throwable t) { throw dieInternal(tc, t); }
            if (r instanceof ContinuationResult) throw frameFreeSuspend(cr);
            frameFreeResult(tc, ffRoot.resultType, r);
            return;
        }
        CallFrame callerFrame = tc.curFrame;
        try {
            /* Frame construction walks the caller chain for an outer and
             * may auto-close; leave() may run an exit handler through
             * invokeDirect. Neither is PE-sized: both stay boundaries, and
             * only the program call itself is in the compiled code. */
            CallFrame cf = newFrame(tc, cr);
            try {
                NqpCodeEngine.runProgram(target, cr.staticInfo.compUnit, tc, cf, csd, args);
            }
            catch (ControlException ce) {
                leave(cf);
                throw ce;
            }
            catch (Throwable t) {
                throw dieInternal(tc, t);
            }
            leave(cf);
        }
        catch (BindReturnException r) {
            CallFrame caller = callerFrame != null ? callerFrame : tc.dummyCaller;
            caller.oRet = r.getValue();
            caller.retType = (byte) CallFrame.RET_OBJ;
        }
        catch (ControlException e) {
            throw e;
        }
        catch (Throwable e) {
            /* As Ops.invokeDirect: dieInternal unwinds to a handler itself;
             * what it returns is not thrown. */
            dieInternal(tc, e);
        }
    }

    /** Adopts a call node for the callee's engine target, or null if the
     *  callee has no registered target yet (it has not run once). */
    @TruffleBoundary
    private static DirectCallNode adoptCallNode(Program p, CodeRef cr, Node node) {
        Object target = cr.staticInfo.engineTarget;
        if (!(target instanceof CallTarget ct)
                || cr.staticInfo.argsExpectation != ArgsExpectation.USE_BINDER)
            return null;
        DirectCallNode cn = node.insert(DirectCallNode.create(ct));
        p.callNode = cn;
        return cn;
    }

    /**
     * The direct entry through an adopted call node: the frame the stub's
     * prelude builds (behind a boundary), the call itself in compiled code
     * so the callee can inline, and the engine's own postlude for the
     * result -- a suspend token joins the resume chain, an unwind leaves
     * the frame and flies on as the Truffle carrier it already is.
     */
    private static void enterDirect(ThreadContext tc, CodeRef cr, DirectCallNode cn,
                                    CallSiteDescriptor csd, Object[] args) {
        /* A frame-free callee runs with cf==null: no CallFrame is built and
         * none is left, so partial evaluation scalar-replaces the callee's
         * VirtualFrame across this inlined call -- the whole point of the
         * port. root.needsFrame is constant for this call node, so the
         * branch folds. */
        NqpRootNode root = engineRootOf(cn.getCallTarget());
        boolean framed = root == null || root.needsFrame;
        CallFrame cf = framed ? newFrame(tc, cr) : null;
        Object r;
        try {
            r = cn.call(cr.staticInfo.compUnit, tc, cf, csd, args);
        }
        catch (NqpUnwind u) {
            if (cf != null) leave(cf);
            throw u;
        }
        catch (NqpHostError h) {
            throw dieInternal(tc, h.original);
        }
        catch (org.raku.nqp.runtime.ControlException ce) {
            if (cf != null) leave(cf);
            throw ce;
        }
        catch (Throwable t) {
            throw dieInternal(tc, t);
        }
        if (r instanceof ContinuationResult) {
            if (cf == null) throw frameFreeSuspend(cr);
            throw NqpCodeEngine.suspendFrame((ContinuationResult) r, cf);
        }
        if (cf != null) leave(cf);
        else frameFreeResult(tc, root.resultType, r);
    }

    @TruffleBoundary
    private static CallFrame newFrame(ThreadContext tc, CodeRef cr) {
        return new CallFrame(tc, cr);
    }

    @TruffleBoundary
    private static void leave(CallFrame cf) {
        cf.leave();
    }

    @TruffleBoundary
    private static RuntimeException dieInternal(ThreadContext tc, Throwable t) {
        return org.raku.nqp.runtime.ExceptionHandling.dieInternal(tc, t);
    }

    /* ----- the roads that stay on the bytecode side ----- */

    @TruffleBoundary
    static void dispatchFlattening(DispatchCallSite site, String name, CallSiteDescriptor csd,
                                   ThreadContext tc, Object[] args) {
        Dispatch.dispatchWithDescriptor(site, name, csd, tc, args);
    }

    @TruffleBoundary
    static void dispatchUncached(String name, CallSiteDescriptor csd, ThreadContext tc,
                                 Object[] args) {
        Dispatch.dispatchUncached(tc, name, csd, args);
    }
}
