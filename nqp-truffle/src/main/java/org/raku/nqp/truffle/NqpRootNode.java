package org.raku.nqp.truffle;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The Bytecode DSL root node general code runs on — the interpreter half of
 * the jast2bc-to-Truffle migration (docs/jvm-truffle-migration.md in
 * rakudo). The processor generates {@code NqpRootNodeGen} from this spec:
 * cached and uncached tiers, OSR, and a serializable bytecode form, which
 * is what answers both per-code-object node memory for the setting and the
 * precompiled-jar story.
 *
 * <p>This is deliberately a skeleton. The operation set grows in the order
 * the encoder's coverage survey says pays (the Phase 1 op census in the
 * migration doc is the starting priority list); what is here now exists to
 * prove the generated interpreter round-trips in this build — the DSL
 * processor runs, both tiers execute, and long arithmetic unboxes.
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

    protected NqpRootNode(NqpLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
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
}
