package org.raku.nqp.truffle;

import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CompilationUnit;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.runtime.UnwindException;

/**
 * The Bytecode DSL root node general code runs on — the interpreter half of
 * the jast2bc-to-Truffle migration (docs/jvm-truffle-migration.md in
 * rakudo). The processor generates {@code NqpRootNodeGen} from this spec:
 * cached and uncached tiers, OSR, and a serializable bytecode form, which
 * is what answers both per-code-object node memory for the setting and the
 * precompiled-jar story.
 *
 * <p>A program's frame arguments are, in order: the CompilationUnit, the
 * ThreadContext, the CallFrame the emitted prologue built, the
 * CallSiteDescriptor, and the argument array — everything the emitted
 * bytecode body had. {@code LoadArgument} built-ins reach them; the
 * constants live in the bytecode constant pool via {@link ConstantOperand}.
 *
 * <p>Semantics live in {@link NqpOps}: every operation here is a thin
 * frame-argument unpack around a boundary call into the existing runtime.
 * Correctness first — the ops worth partial evaluation get dedicated,
 * specializing operations as measurement says they earn it (the long
 * arithmetic below is the first of those).
 *
 * <p>The rx engine avoids the DSL on purpose (see nqp-truffle's
 * build.gradle.kts) because its one node specializes by hand. This node is
 * the opposite case: a large op set where the generated dispatch loop,
 * quickening, and boxing elimination are the whole point — which is why it
 * is Java, like everything annotation-processed here.
 */
@GenerateBytecode(
    languageClass = NqpLanguage.class,
    enableUncachedInterpreter = true,
    enableSerialization = true,
    enableYield = true,
    boxingEliminationTypes = { long.class })
public abstract class NqpRootNode extends RootNode implements BytecodeRootNode {

    static final int ARG_CU = 0;
    static final int ARG_TC = 1;
    static final int ARG_CF = 2;
    static final int ARG_CSD = 3;
    static final int ARG_ARGS = 4;

    protected NqpRootNode(NqpLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /** The wire program's length in words, set at parse; names the root in
     *  compilation traces and decides whether it may compile at all. */
    int programSize;

    /** The block's name, learned at its first run (the wire carries none). */
    volatile String blockName;

    /** The block's static result type (wire T_OBJ/INT/NUM/STR); set at
     *  parse. For a frame-free block the direct-entry road reads this to
     *  deposit the program's return value into the caller's registers,
     *  where a framed block's own StoreRet would have. */
    int resultType;

    /** False when the block runs with no CallFrame (the encoder proved it
     *  frame-free: no lexicals, no dispatch, no nested block, no
     *  frame-reading op, positional local-scope params only). Set at parse
     *  from the wire header; default (framed) is the safe value. */
    boolean needsFrame = true;
    /** No op in the block reads the current language: a frame-free entry
     *  may cross languages (the wire's bit 1; jesp diamond 7). */
    boolean hllFree = false;

    @Override
    public String getName() {
        String n = blockName;
        return (n == null || n.isEmpty() ? "<anon>" : n) + "[" + programSize + "]";
    }

    /** What TraceCompilation prints for the call target. */
    @Override
    public String toString() {
        return getName();
    }

    /**
     * NQP_CODE_MAX_COMPILE: programs longer than this many wire words are
     * never handed to the compiler. A TraceCompilation of the CORE.c
     * compile found 114 roots (of 1650 compilations) failing with "code
     * installation failed: code is too large" after a mean 6.4s of
     * compiler time each -- 733s of the 1880s the run compiled at all --
     * and such a root runs interpreted afterwards regardless, so refusing
     * up front costs it nothing and frees the compiler for the hot roots
     * queued behind it. The encoder-side counterpart (leave such blocks to
     * bytecode) is the durable fix; this is the runtime's guard.
     */
    static final int MAX_COMPILE_SIZE;
    static {
        String v = System.getenv("NQP_CODE_MAX_COMPILE");
        MAX_COMPILE_SIZE = v == null ? Integer.MAX_VALUE : Integer.parseInt(v);
    }

    @Override
    protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier,
                                            boolean lastTier) {
        return programSize <= MAX_COMPILE_SIZE;
    }

    private static ThreadContext tc(VirtualFrame f) {
        return (ThreadContext) f.getArguments()[ARG_TC];
    }

    private static CallFrame cf(VirtualFrame f) {
        return (CallFrame) f.getArguments()[ARG_CF];
    }

    private static CompilationUnit cu(VirtualFrame f) {
        return (CompilationUnit) f.getArguments()[ARG_CU];
    }

    /**
     * A host {@link UnwindException} would be rethrown past every
     * in-program TryCatch (the dispatch loop intercepts only Truffle
     * exceptions), so wrap it in {@link NqpUnwind} here. Programs with no
     * handler regions never catch the wrapper; it propagates out and
     * {@code CodeEngines.codeRun} unwraps it at the engine boundary.
     */
    @Override
    public Throwable interceptInternalException(Throwable t, VirtualFrame frame,
                                                BytecodeNode bytecodeNode, int bci) {
        if (t instanceof UnwindException u) return new NqpUnwind(u);
        // Other control-flow exceptions (SaveStackException, ResumeException,
        // thread death) must keep flying untouched; anything else becomes
        // visible to handle regions, which dieInternal it the way the
        // bytecode path's catch (Throwable) does.
        if (t instanceof org.raku.nqp.runtime.ControlException) return t;
        return new NqpHostError(t);
    }

    // NQP's int is 64-bit throughout; these mirror nqp::add_i and friends.

    /* The native int/num table ops, by op id (a constant, so the switch in
     * NqpNativeOps folds to the one instruction); jesp diamond 3. */

    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class IntBinOp {
        @Specialization static long doLong(int kind, long a, long b) { return NqpNativeOps.intBin(kind, a, b); }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class IntUnOp {
        @Specialization static long doLong(int kind, long a) { return NqpNativeOps.intUn(kind, a); }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class NumBinOp {
        @Specialization static double doNum(int kind, double a, double b) { return NqpNativeOps.numBin(kind, a, b); }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class NumCmpOp {
        @Specialization static long doNum(int kind, double a, double b) { return NqpNativeOps.numCmp(kind, a, b); }
    }

    @Operation
    public static final class NumNegOp {
        @Specialization static double doNum(double a) { return -a; }
    }

    /** nqp truth over a native int: nonzero is true. */
    @Operation
    public static final class NonZero {
        @Specialization static boolean doLong(long a) { return a != 0; }
    }

    /* ----- the generic table; semantics in NqpOps ----- */

    /** One op from the {@link NqpOps} table, all operands boxed. */
    @Operation
    @ConstantOperand(type = int.class, name = "id")
    public static final class RunOp {
        @Specialization
        static Object doOp(VirtualFrame f, int id, @Variadic Object[] a) {
            try {
                return NqpOps.run(id, a, cu(f), tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** A classlib op straight from the registry the bytecode path compiles
     *  to an invokestatic: the site resolves the static method once. */
    @Operation
    @ConstantOperand(type = int.class, name = "rtype")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class ClassLibOp {
        @Specialization
        static Object doCall(VirtualFrame f, int rtype, Object site, @Variadic Object[] a) {
            try {
                return NqpOps.classlib(rtype, (NqpOps.ClassLibSite) site, a, tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** A type coercion from the {@link NqpOps} kind table. */
    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class Coerce {
        @Specialization
        static Object doCoerce(VirtualFrame f, int kind, Object v) {
            try {
                return NqpOps.coerce(kind, v, cu(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** nqp truthiness, typed by the encoder; negate for until-loops. */
    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = int.class, name = "negate")
    public static final class Truthy {
        @Specialization
        static boolean doTruthy(VirtualFrame f, int type, int negate, Object v) {
            try {
                return NqpOps.truthy(type, v, tc(f)) == (negate == 0);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /* ----- frame access ----- */

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class LexGet {
        @Specialization
        static Object doGet(VirtualFrame f, int type, String name, Object site) {
            try {
                return NqpOps.getlex(type, name, (NqpOps.LexSite) site, tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class LexBind {
        @Specialization
        static Object doBind(VirtualFrame f, int type, String name, Object site, Object v) {
            try {
                return NqpOps.bindlex(type, name, v, (NqpOps.LexSite) site, tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "spec")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class LexRef {
        @Specialization
        static Object doRef(VirtualFrame f, int type, String name, int spec, Object site) {
            try {
                return NqpOps.getlexref(type, name, spec, (NqpOps.LexSite) site, tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class CurLexpad {
        @Specialization
        static Object doCtx(VirtualFrame f) {
            try {
                return NqpOps.curlexpad(tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class UseCapture {
        @Specialization
        static Object doUse(VirtualFrame f) {
            try {
                return NqpOps.usecapture(tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class P6ArgVmArray {
        @Specialization
        static Object doArgs(VirtualFrame f) {
            try {
                return NqpOps.p6argvmarray(tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexOuterGet {
        @Specialization
        static Object doGet(VirtualFrame f, String name) {
            try {
                return NqpOps.getlexouter(name, tc(f), cf(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /* ----- the continuation-suspension protocol (see NqpCont) ----- */

    /** Whether a call answered a suspend token instead of a result. */
    @Operation
    public static final class IsSuspend {
        @Specialization
        static boolean doCheck(Object v) {
            return v instanceof NqpCont.Suspend;
        }
    }

    /**
     * The value a resumed yield produced: a real result passes through,
     * an injected exception is rethrown at the suspension point so the
     * program's handler regions see it as the call's own throw.
     */
    @Operation
    public static final class UnpackResumed {
        @Specialization
        static Object doUnpack(Object v) {
            if (v instanceof NqpCont.Rethrow r) throw NqpCont.sneaky(r.t);
            return v;
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "handle")
    @ConstantOperand(type = int.class, name = "idx")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class WvalGet {
        @Specialization
        static Object doGet(VirtualFrame f, String handle, int idx, Object site) {
            try {
                return NqpOps.wval(handle, idx, (NqpOps.WvalSite) site, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** nqp::getattr with a per-instruction slot cache; see NqpOps.AttrSite. */
    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class GetAttrOp {
        @Specialization
        static Object doGet(VirtualFrame f, Object site, Object obj, Object ch, Object name) {
            try {
                return NqpOps.getattr((NqpOps.AttrSite) site, obj, ch, (String) name, tc(f), cu(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** nqp::bindattr, likewise. */
    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class BindAttrOp {
        @Specialization
        static Object doBind(VirtualFrame f, Object site, Object obj, Object ch, Object name,
                             Object value) {
            try {
                return NqpOps.bindattr((NqpOps.AttrSite) site, obj, ch, (String) name, value, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /**
     * The null SMO constant — the VMNull singleton, exactly what the
     * bytecode path's {@code Ops.createNull} answers for nqp::null().
     * A Java null is NOT the same thing observably: bindattr stores it
     * as never-initialized (attrinited answers 0), and isnull goes by
     * identity. Positions where the bytecode path really has a Java
     * null (fresh object locals, valueless else branches) use the
     * builder's LoadNull instead.
     */
    /* ----- the type-check family with per-instruction sites (jesp diamond 3;
     * see NqpTypeOps). The builder emits these for the table ops of the same
     * name; the generic RunOp road remains for everything else. ----- */

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class DecontOp {
        @Specialization
        static Object doDecont(VirtualFrame f, Object site, Object o) {
            try {
                return NqpTypeOps.decont((NqpTypeOps.DecontSite) site, o, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class IsNullOp {
        @Specialization
        static long doIsNull(Object o) {
            return NqpTypeOps.isnull(o);
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class IsConcreteOp {
        @Specialization
        static long doIsConcrete(VirtualFrame f, Object site, Object o) {
            try {
                return NqpTypeOps.isconcrete((NqpTypeOps.IsConcreteSite) site, o, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class IsTypeOp {
        @Specialization
        static long doIsType(VirtualFrame f, Object site, Object o, Object type) {
            try {
                return NqpTypeOps.istype((NqpTypeOps.IsTypeSite) site, o, type, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class HllizeOp {
        @Specialization
        static Object doHllize(VirtualFrame f, Object site, Object o) {
            try {
                return NqpTypeOps.hllize((NqpTypeOps.HllizeSite) site, o, cu(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class AssertParamCheckOp {
        @Specialization
        static Object doLong(VirtualFrame f, long ok) {
            try {
                return NqpTypeOps.assertparamcheck(ok, cf(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
        @Specialization
        static Object doObject(VirtualFrame f, Object ok) {
            try {
                return NqpTypeOps.assertparamcheck(((Number) ok).longValue(), cf(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class P6TypeCheckRvOp {
        @Specialization
        static Object doCheck(VirtualFrame f, Object site, Object rv, Object routine, Object bypass) {
            try {
                return NqpTypeOps.p6typecheckrv((NqpTypeOps.RvCheckSite) site, rv, routine, bypass, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    public static final class CreateOp {
        @Specialization
        static Object doCreate(VirtualFrame f, Object site, Object type) {
            try {
                return NqpTypeOps.create((NqpTypeOps.CreateSite) site, type, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** add_I/sub_I/mul_I with a site (jesp diamond 4); kind is the op id. */
    @Operation
    @ConstantOperand(type = Object.class, name = "site")
    @ConstantOperand(type = int.class, name = "kind")
    public static final class BigIntArithOp {
        @Specialization
        static Object doArith(VirtualFrame f, Object site, int kind, Object a, Object b, Object type) {
            try {
                return NqpTypeOps.bigintArith((NqpTypeOps.BigIntSite) site, kind, a, b, type, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class NullC {
        @Specialization
        static Object doNull(VirtualFrame f) {
            try {
                return NqpOps.nullConstant(tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** A code ref of this unit by block id -- what a BVal compiles to. */
    @Operation
    @ConstantOperand(type = int.class, name = "qbid")
    public static final class CodeRefGet {
        @Specialization
        static Object doGet(VirtualFrame f, int qbid) {
            return cu(f).lookupCodeRef(qbid);
        }
    }

    /* ----- calls ----- */

    /**
     * One dispatch through the newdisp machinery — lang-call,
     * lang-meth-call, and friends — uncached: full semantics, no
     * per-callsite program yet. The per-site guard caching is the
     * optimization tier this migration eventually buys; correctness
     * comes from taking exactly the road the emitted bytecode takes.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "rtype")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = Object.class, name = "site")
    public static final class DispatchOp {
        @Specialization
        static Object doDispatch(VirtualFrame f, int rtype, String name, Object site,
                                 @Variadic Object[] args,
                                 @com.oracle.truffle.api.dsl.Bind com.oracle.truffle.api.nodes.Node node) {
            try {
                return NqpOps.dispatch(rtype, name, (NqpOps.EngineSite) site, args, tc(f), cf(f), node, cu(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /* ----- parameter binding, mirroring the emitted prologue ----- */

    /** Arity check; answers the (possibly exploded) csd. */
    @Operation
    @ConstantOperand(type = int.class, name = "required")
    @ConstantOperand(type = int.class, name = "accepted")
    public static final class CheckArity {
        @Specialization
        static Object doCheck(VirtualFrame f, int required, int accepted) {
            Object[] fa = f.getArguments();
            try {
                return NqpOps.checkarity((CallFrame) fa[ARG_CF], (ThreadContext) fa[ARG_TC],
                (CallSiteDescriptor) fa[ARG_CSD], (Object[]) fa[ARG_ARGS], required, accepted);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** The argument array the parameter fetches read. When the arity check
     *  answered the callsite it was given, nothing flattened and the array
     *  is the frame's own argument -- read from the frame, never parked on
     *  the thread context: a heap store is what kept every argument array
     *  alive through escape analysis after the callee was inlined (jesp:
     *  spesh keeps arguments in registers). Only an exploded callsite has
     *  a new array, left on the thread context by the slow road. */
    @Operation
    public static final class FlatArgs {
        @Specialization
        static Object doGet(VirtualFrame f, Object csd) {
            Object[] fa = f.getArguments();
            if (csd == fa[ARG_CSD]) return fa[ARG_ARGS];
            try {
                return NqpOps.flatArgs((ThreadContext) fa[ARG_TC]);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "idx")
    @ConstantOperand(type = int.class, name = "opt")
    @ConstantOperand(type = int.class, name = "type")
    public static final class PosParam {
        @Specialization
        static Object doParam(VirtualFrame f, int idx, int opt, int type, Object csd, Object args) {
            try {
                return NqpOps.posparam(cf(f), tc(f), cu(f), csd, (Object[]) args, idx, opt != 0, type);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "opt")
    @ConstantOperand(type = int.class, name = "type")
    public static final class NamedParam {
        @Specialization
        static Object doParam(VirtualFrame f, String name, int opt, int type, Object csd, Object args) {
            try {
                return NqpOps.namedparam(cf(f), csd, (Object[]) args, name, opt != 0, type);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "from")
    public static final class PosSlurpy {
        @Specialization
        static Object doParam(VirtualFrame f, int from, Object csd, Object args) {
            try {
                return NqpOps.posslurpy(tc(f), cf(f), csd, (Object[]) args, from);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    @Operation
    public static final class NamedSlurpy {
        @Specialization
        static Object doParam(VirtualFrame f, Object csd, Object args) {
            try {
                return NqpOps.namedslurpy(tc(f), cf(f), csd, (Object[]) args);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** Whether the optional parameter just fetched was actually passed. */
    @Operation
    public static final class ParamExisted {
        @Specialization
        static boolean doGet(VirtualFrame f) {
            try {
                return NqpOps.lastParamExisted(tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /**
     * The typed return-register store, inside the program because only
     * the encoder knows the block's static result type -- a null str and
     * a null obj are indistinguishable as values. Passes the value
     * through so a harness run without a CallFrame still answers.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "type")
    public static final class StoreRet {
        @Specialization
        static Object doStore(VirtualFrame f, int type, Object v) {
            CallFrame cf = (CallFrame) f.getArguments()[ARG_CF];
            if (cf != null) NqpOps.storeReturnTyped(type, v, cf);
            return v;
        }
    }

    /* ----- unwind handling, mirroring the emitted handler regions ----- */

    /**
     * The dynamic handler cursor: what {@code delimit_handler} emits as a
     * {@code putfield curHandler} pair around every protected region.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "id")
    public static final class SetCurHandler {
        @Specialization
        static void doSet(VirtualFrame f, int id) {
            cf(f).curHandler = id;
        }
    }

    /**
     * The catch arm of a loop body's NEXT|REDO region: the unwind_check
     * (rethrow anything not aimed at this handler in this unit, redirect
     * labeled unwinds outward), then answers 1 for REDO and 0 for NEXT.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "target")
    @ConstantOperand(type = int.class, name = "outer")
    public static final class LoopBodyUnwind {
        @Specialization
        static long doRoute(VirtualFrame f, int target, int outer, Object where, Object ex) {
            try {
                return NqpOps.loopBodyUnwind(ex, target, outer, where, cu(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** The catch arm of a loop's LAST region: unwind_check, then swallow. */
    @Operation
    @ConstantOperand(type = int.class, name = "target")
    @ConstantOperand(type = int.class, name = "outer")
    public static final class LoopLastUnwind {
        @Specialization
        static void doRoute(VirtualFrame f, int target, int outer, Object where, Object ex) {
            try {
                NqpOps.loopLastUnwind(ex, target, outer, where, cu(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /**
     * The catch arm of a handle/handlepayload region: unwind_check
     * (cares != 0 skips the labeled redirect, as :handler_cares does),
     * then the result the block handler left on the unwind.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "target")
    @ConstantOperand(type = int.class, name = "outer")
    @ConstantOperand(type = int.class, name = "cares")
    public static final class HandleUnwind {
        @Specialization
        static Object doRoute(VirtualFrame f, int target, int outer, int cares, Object ex) {
            try {
                return NqpOps.handleUnwind(ex, target, outer, cares != 0, cu(f), tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /**
     * The handle op's inner catch: a host throwable that is not part of
     * the control protocol becomes an nqp exception via dieInternal,
     * thrown from here so the enclosing unwind region can take it.
     * Never returns normally.
     */
    @Operation
    public static final class HostErrToUnwind {
        @Specialization
        static void doConvert(VirtualFrame f, Object ex) {
            try {
                NqpOps.hostErrToUnwind(ex, tc(f));
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
        }
    }

    /** cf.exitAfterUnwind: a handler asked this whole frame to leave. */
    @Operation
    public static final class ExitAfterUnwind {
        @Specialization
        static boolean doGet(VirtualFrame f) {
            return cf(f).exitAfterUnwind;
        }
    }

    /**
     * The named-argument rejection the invoker's args-expectation check
     * does for compiled blocks; an engine block routes around that check
     * and so re-makes it here, against its declared named parameters.
     */
    @Operation
    @ConstantOperand(type = Object.class, name = "allowed")
    public static final class CheckNamedAllowed {
        @Specialization
        static Object doCheck(VirtualFrame f, Object allowed, Object csd) {
            try {
                NqpOps.checkNoExtraNamed(cf(f), tc(f), csd, (String[]) allowed);
            } catch (Throwable t) {
                throw NqpOps.carry(t);
            }
            return null;
        }
    }
}
