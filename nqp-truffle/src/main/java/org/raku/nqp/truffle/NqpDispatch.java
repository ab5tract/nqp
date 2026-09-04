package org.raku.nqp.truffle;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CallTarget;

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
     * guards: the exact cast lets PE devirtualize the generated accessor,
     * and the constant hint folds its switch to the field.
     */
    static final class AttrSrc extends Src {
        final Src from;
        final Class<?> storage;
        final long hint;
        final SixModelObject classHandle;
        final String name;
        final ArgKind kind;
        AttrSrc(Src from, Class<?> storage, long hint, SixModelObject classHandle,
                String name, ArgKind kind) {
            this.from = from;
            this.storage = storage;
            this.hint = hint;
            this.classHandle = classHandle;
            this.name = name;
            this.kind = kind;
        }
        /* The class check is a speculation, not a proof: an object whose
         * type a mixin changed keeps its original storage class and
         * delegates (P6OpaqueDelegateInstance), so its class is not its
         * new type's. Those, and a null, take the generic accessor. */
        @Override Object eval(ThreadContext tc, Object[] args) {
            Object o = from.eval(tc, args);
            /* A deserialized object is a delegate wrapper around its real
             * storage (so is one a mixin retyped); look through one. */
            Object target = o instanceof P6OpaqueDelegateInstance d ? d.delegate : o;
            if (target != null && target.getClass() == storage) {
                SixModelObject smo = (SixModelObject) CompilerDirectives.castExact(target, storage);
                switch (kind) {
                    case OBJ:
                        return smo.get_attribute_boxed(tc, classHandle, name, hint);
                    case INT: case UINT:
                        smo.get_attribute_native(tc, classHandle, name, hint);
                        return tc.nativeI;
                    case NUM:
                        smo.get_attribute_native(tc, classHandle, name, hint);
                        return tc.nativeN;
                    default:
                        smo.get_attribute_native(tc, classHandle, name, hint);
                        return tc.nativeS;
                }
            }
            return slow(tc, o);
        }
        @TruffleBoundary
        private Object slow(ThreadContext tc, Object o) {
            if (STATS) {
                count(slowEvals);
                String key = (o == null ? "null" : o.getClass().getName()) + " vs " + storage.getName() + " " + name;
                if (seenSlow.add(key)) System.err.println("dispatch slow attr: " + key);
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
            if (STATS) count(slowEvals);
            return ValueSource.Companion.unbox(tc, (SixModelObject) o, kind);
        }
    }

    /** Everything else: the runtime's generic evaluator, across a boundary. */
    static final class SlowSrc extends Src {
        final ValueSource source;
        SlowSrc(ValueSource source) { this.source = source; }
        @Override @TruffleBoundary
        Object eval(ThreadContext tc, Object[] args) {
            if (STATS) count(slowEvals);
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
            if (s instanceof ValueSource.Attribute at) {
                Class<?> storage = storageOf(at.getFrom());
                if (storage != null) {
                    STable st = known.get(at.getFrom());
                    long hint = st.REPR.hint_for(tc, st, at.getClassHandle(), at.getName());
                    if (hint != STable.NO_HINT)
                        return new AttrSrc(fold(at.getFrom()), storage, hint,
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
                if (same) return;
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
            Assumption old = stable;
            programs = fresh;
            stable = Truffle.getRuntime().createAssumption("dispatch site");
            old.invalidate();
        }

        private boolean cacheable(DispatchProgram p) {
            return !p.isResuming() && Captures.INSTANCE.sameShape(p.getDescriptor(), csd);
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
    static boolean replay(Cache cache, ThreadContext tc, Object[] args) {
        if (!cache.stable.isValid()) CompilerDirectives.transferToInterpreterAndInvalidate();
        Program[] programs = cache.programs;
        for (int i = 0; i < programs.length; i++) {
            Program p = programs[i];
            if (matches(p, tc, args)) {
                if (STATS) { count(hits); count(hitsByKind[p.kind]); }
                realize(p, cache.site, tc, args);
                return true;
            }
        }
        return false;
    }

    /** The chain's tail: uncached programs, then a recording; then refold. */
    @TruffleBoundary
    static void miss(Cache cache, String name, ThreadContext tc, Object[] args) {
        if (STATS) count(misses);
        Dispatch.fallback(cache.site, name, cache.csd, cache.programs.length, tc, args);
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
                                Object[] args) {
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
     */
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
            if (STATS) {
                count(target == null ? noTarget : badExpectation);
                String key = (cr.name == null || cr.name.isEmpty() ? "<anon>" : cr.name)
                    + " " + cr.staticInfo.compUnit.getClass().getSimpleName();
                noTargetBy.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            }
        }
        else if (STATS) count(notCodeRef);
        invokeBoundary(tc, callee, descriptor, out);
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
        DispatchRecord record = newRecord(tc, p.program, args, site);
        List<DispatchRecord> records = tc.dispatchRecords;
        records.add(record);
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
            records.remove(records.size() - 1);
        }
    }

    @TruffleBoundary
    private static DispatchRecord newRecord(ThreadContext tc, DispatchProgram program,
                                            Object[] args, DispatchCallSite site) {
        DispatchRecord record = new DispatchRecord(tc, null, program.getDescriptor(), args,
            tc.curFrame, site);
        record.setProgram(program);
        record.endRecording();
        return record;
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
    private static void enterEngine(ThreadContext tc, CodeRef cr, CallTarget target,
                                    CallSiteDescriptor csd, Object[] args) {
        CallFrame callerFrame = tc.curFrame;
        try {
            CallFrame cf = new CallFrame(tc, cr);
            try {
                NqpCodeEngine.runProgram(target, cr.staticInfo.compUnit, tc, cf, csd, args);
            }
            catch (ControlException ce) {
                cf.leave();
                throw ce;
            }
            catch (Throwable t) {
                throw dieInternal(tc, t);
            }
            cf.leave();
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
