package org.raku.nqp.truffle;

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
            return NqpOps.getlex(type, name, tc(f));
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "type")
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexBind {
        @Specialization
        static Object doBind(VirtualFrame f, int type, String name, Object v) {
            return NqpOps.bindlex(type, name, v, tc(f));
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class LexOuterGet {
        @Specialization
        static Object doGet(VirtualFrame f, String name) {
            return NqpOps.getlexouter(name, tc(f));
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

    /** The null SMO constant. */
    @Operation
    public static final class NullC {
        @Specialization static Object doNull() { return null; }
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
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = Object.class, name = "csd")
    public static final class DispatchOp {
        @Specialization
        static Object doDispatch(VirtualFrame f, String name, Object csd,
                                 @Variadic Object[] args) {
            return NqpOps.dispatch(name, (CallSiteDescriptor) csd, args, tc(f), cf(f));
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
}
