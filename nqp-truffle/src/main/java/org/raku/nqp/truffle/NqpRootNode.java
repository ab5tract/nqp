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

    @Operation
    public static final class AddI {
        @Specialization static long doLong(long a, long b) { return a + b; }
    }

    @Operation
    public static final class SubI {
        @Specialization static long doLong(long a, long b) { return a - b; }
    }

    @Operation
    public static final class MulI {
        @Specialization static long doLong(long a, long b) { return a * b; }
    }

    @Operation
    public static final class LtI {
        @Specialization static boolean doLong(long a, long b) { return a < b; }
    }

    @Operation
    public static final class GtI {
        @Specialization static boolean doLong(long a, long b) { return a > b; }
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
            return NqpOps.run(id, a, cu(f), tc(f), cf(f));
        }
    }

    /** A type coercion from the {@link NqpOps} kind table. */
    @Operation
    @ConstantOperand(type = int.class, name = "kind")
    public static final class Coerce {
        @Specialization
        static Object doCoerce(VirtualFrame f, int kind, Object v) {
            return NqpOps.coerce(kind, v, cu(f), tc(f));
        }
    }

    /** nqp truthiness, typed by the encoder; negate for until-loops. */
    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = int.class, name = "negate")
    public static final class Truthy {
        @Specialization
        static boolean doTruthy(VirtualFrame f, int type, int negate, Object v) {
            return NqpOps.truthy(type, v, tc(f)) == (negate == 0);
        }
    }

    /* ----- frame access ----- */

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexGet {
        @Specialization
        static Object doGet(VirtualFrame f, int type, String name) {
            return NqpOps.getlex(type, name, tc(f), cf(f));
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexBind {
        @Specialization
        static Object doBind(VirtualFrame f, int type, String name, Object v) {
            return NqpOps.bindlex(type, name, v, tc(f), cf(f));
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexOuterGet {
        @Specialization
        static Object doGet(VirtualFrame f, String name) {
            return NqpOps.getlexouter(name, tc(f), cf(f));
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
    public static final class WvalGet {
        @Specialization
        static Object doGet(VirtualFrame f, String handle, int idx) {
            return NqpOps.wval(handle, idx, tc(f));
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
    @Operation
    public static final class NullC {
        @Specialization
        static Object doNull(VirtualFrame f) {
            return org.raku.nqp.runtime.Ops.createNull(tc(f));
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
                                 @Variadic Object[] args) {
            return NqpOps.dispatch(rtype, name, (NqpOps.EngineSite) site, args, tc(f), cf(f));
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
            return NqpOps.checkarity((CallFrame) fa[ARG_CF], (CallSiteDescriptor) fa[ARG_CSD],
                (Object[]) fa[ARG_ARGS], required, accepted);
        }
    }

    /** The flattened argument array checkarity left on the thread context. */
    @Operation
    public static final class FlatArgs {
        @Specialization
        static Object doGet(VirtualFrame f) {
            return NqpOps.flatArgs(tc(f));
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "idx")
    @ConstantOperand(type = int.class, name = "opt")
    public static final class PosParam {
        @Specialization
        static Object doParam(VirtualFrame f, int idx, int opt, Object csd, Object args) {
            return NqpOps.posparam(cf(f), csd, (Object[]) args, idx, opt != 0);
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "opt")
    public static final class NamedParam {
        @Specialization
        static Object doParam(VirtualFrame f, String name, int opt, Object csd, Object args) {
            return NqpOps.namedparam(cf(f), csd, (Object[]) args, name, opt != 0);
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "from")
    public static final class PosSlurpy {
        @Specialization
        static Object doParam(VirtualFrame f, int from, Object csd, Object args) {
            return NqpOps.posslurpy(tc(f), cf(f), csd, (Object[]) args, from);
        }
    }

    @Operation
    public static final class NamedSlurpy {
        @Specialization
        static Object doParam(VirtualFrame f, Object csd, Object args) {
            return NqpOps.namedslurpy(tc(f), cf(f), csd, (Object[]) args);
        }
    }

    /** Whether the optional parameter just fetched was actually passed. */
    @Operation
    public static final class ParamExisted {
        @Specialization
        static boolean doGet(VirtualFrame f) {
            return NqpOps.lastParamExisted(tc(f));
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
        static long doRoute(VirtualFrame f, int target, int outer, Object ex) {
            return NqpOps.loopBodyUnwind(ex, target, outer, cu(f), tc(f));
        }
    }

    /** The catch arm of a loop's LAST region: unwind_check, then swallow. */
    @Operation
    @ConstantOperand(type = int.class, name = "target")
    @ConstantOperand(type = int.class, name = "outer")
    public static final class LoopLastUnwind {
        @Specialization
        static void doRoute(VirtualFrame f, int target, int outer, Object ex) {
            NqpOps.loopLastUnwind(ex, target, outer, cu(f), tc(f));
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
            return NqpOps.handleUnwind(ex, target, outer, cares != 0, cu(f), tc(f));
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
            NqpOps.hostErrToUnwind(ex, tc(f));
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
            NqpOps.checkNoExtraNamed(cf(f), csd, (String[]) allowed);
            return null;
        }
    }
}
