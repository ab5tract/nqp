package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * The Truffle language general NQP/Raku code runs in — the target of the
 * jast2bc-to-Truffle migration (docs/jvm-truffle-migration.md in rakudo).
 * The grammar engine's {@link RxLanguage} stays its own language: its parse
 * builds matchers, and the two coverage stories grow independently.
 *
 * <p>Like the regex engine, code arrives here already compiled: the QAST
 * backend encodes a code object into a program at compile time, and this
 * language only decodes and runs it. Nothing parses source text.
 *
 * <p>What parse accepts today is only the {@code code-test:} scaffolding the
 * skeleton harness ({@link NqpCheck}) drives; the encoder's wire form joins
 * it when Phase 2 puts real code on this road.
 */
@TruffleLanguage.Registration(id = NqpLanguage.ID, name = "NQP Code", version = "0.1")
public final class NqpLanguage extends TruffleLanguage<NqpLanguage.Ctx> {

    public static final String ID = "nqp-code";

    public static final class Ctx { }

    @Override protected Ctx createContext(Env env) { return new Ctx(); }

    /**
     * Programs compile lazily, on whichever thread first runs a block --
     * with precompiled modules (Phase 4) that is routinely a worker
     * thread, and the default single-threaded policy would refuse the
     * second thread's eval. The language holds no mutable context state
     * (Ctx is empty, PARSED is concurrent, and the DSL serializes its
     * own specialization updates), so shared multi-threaded access is
     * sound -- and matcher call targets have always been shared across
     * threads on the rx side.
     */
    @Override protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    /**
     * The program call target for each source parsed, by source text —
     * the same raw-CallTarget handoff the rx engine uses: eval answers a
     * polyglot Value, and calling through one boxes everything, so the
     * embedder ({@link NqpCodeEngine}) collects the target from here.
     */
    static final java.util.concurrent.ConcurrentHashMap<String, CallTarget> PARSED =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override protected CallTarget parse(ParsingRequest request) {
        String source = request.getSource().getCharacters().toString();
        if (NqpWire.isProgram(source)) {
            NqpWire.Program p = NqpWire.decode(source);
            BytecodeRootNodes<NqpRootNode> nodes = NqpRootNodeGen.create(
                this, BytecodeConfig.DEFAULT, b -> NqpProgramBuilder.build(b, p));
            NqpRootNode root = nodes.getNode(0);
            root.programSize = p.code().length;
            root.resultType = p.resultType();
            root.needsFrame = p.needsFrame();
            root.hllFree = p.hllFree();
            CallTarget target = root.getCallTarget();
            PARSED.put(source, target);
            return new RxLanguage.ConstantRootNode(this, new Program(target)).getCallTarget();
        }
        if (!source.startsWith("code-test:")) {
            throw new IllegalArgumentException(
                "nqp-code runs encoded programs (nqpp ...) and code-test: harness sources");
        }
        CallTarget target = canned(source.substring("code-test:".length()));
        return new RxLanguage.ConstantRootNode(this, new Program(target)).getCallTarget();
    }

    /**
     * Canned programs, exercising the generated interpreter's basics:
     * constants, arguments, locals, custom operations, branches and loops.
     * Scaffolding for {@link NqpCheck}; deleted when real programs arrive.
     */
    private CallTarget canned(String which) {
        BytecodeParser<NqpRootNodeGen.Builder> parser = switch (which) {
            case "add" -> NqpLanguage::buildAdd;
            case "fib", "serial" -> NqpLanguage::buildFib;
            default -> throw new IllegalArgumentException("no canned program " + which);
        };
        BytecodeRootNodes<NqpRootNode> nodes =
            NqpRootNodeGen.create(this, BytecodeConfig.DEFAULT, parser);
        if (which.equals("serial")) {
            nodes = roundTrip(nodes);
        }
        return nodes.getNode(0).getCallTarget();
    }

    /**
     * Serializes a program and reads it back, which is the whole of the
     * precompilation story in miniature: what travels in a jar in Phase 4
     * is exactly this byte stream. Constants are longs today; the encoder's
     * wire format decides the real tag set when it lands.
     */
    private BytecodeRootNodes<NqpRootNode> roundTrip(BytecodeRootNodes<NqpRootNode> nodes) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            nodes.serialize(new java.io.DataOutputStream(bytes), (ctx, out, obj) -> {
                if (obj instanceof Long l) {
                    out.writeLong(l);
                } else {
                    throw new java.io.IOException("unserializable constant: " + obj);
                }
            });
            var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            return NqpRootNodeGen.deserialize(this, BytecodeConfig.DEFAULT,
                () -> in, (ctx, input) -> input.readLong());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** arg0 + arg1 */
    private static void buildAdd(NqpRootNodeGen.Builder b) {
        b.beginRoot();
        b.beginReturn();
        b.beginIntBinOp(NqpOps.OP_ADD_I);
        b.emitLoadArgument(0);
        b.emitLoadArgument(1);
        b.endIntBinOp();
        b.endReturn();
        b.endRoot();
    }

    /** Iterative fib(arg0): locals and a while loop. */
    private static void buildFib(NqpRootNodeGen.Builder b) {
        b.beginRoot();
        var a = b.createLocal();
        var c = b.createLocal();
        var i = b.createLocal();
        var t = b.createLocal();

        b.beginStoreLocal(a);
        b.emitLoadConstant(0L);
        b.endStoreLocal();
        b.beginStoreLocal(c);
        b.emitLoadConstant(1L);
        b.endStoreLocal();
        b.beginStoreLocal(i);
        b.emitLoadArgument(0);
        b.endStoreLocal();

        b.beginWhile();
        b.beginNonZero(); b.beginIntBinOp(NqpOps.OP_ISGT_I);
        b.emitLoadLocal(i);
        b.emitLoadConstant(0L);
        b.endIntBinOp(); b.endNonZero();
        b.beginBlock();
        b.beginStoreLocal(t);
        b.beginIntBinOp(NqpOps.OP_ADD_I);
        b.emitLoadLocal(a);
        b.emitLoadLocal(c);
        b.endIntBinOp();
        b.endStoreLocal();
        b.beginStoreLocal(a);
        b.emitLoadLocal(c);
        b.endStoreLocal();
        b.beginStoreLocal(c);
        b.emitLoadLocal(t);
        b.endStoreLocal();
        b.beginStoreLocal(i);
        b.beginIntBinOp(NqpOps.OP_SUB_I);
        b.emitLoadLocal(i);
        b.emitLoadConstant(1L);
        b.endIntBinOp();
        b.endStoreLocal();
        b.endBlock();
        b.endWhile();

        b.beginReturn();
        b.emitLoadLocal(a);
        b.endReturn();
        b.endRoot();
    }

    /**
     * What eval hands back: an executable over the program's call target.
     * Polyglot hands numbers over as whatever fits, so arguments are
     * widened to long here, once, at the boundary.
     */
    @ExportLibrary(InteropLibrary.class)
    public static final class Program implements TruffleObject {
        private final CallTarget target;

        Program(CallTarget target) { this.target = target; }

        public CallTarget callTarget() { return target; }

        @ExportMessage boolean isExecutable() { return true; }

        @ExportMessage Object execute(Object[] args) throws ArityException {
            Object[] widened = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                widened[i] = args[i] instanceof Number n ? n.longValue() : args[i];
            }
            return target.call(widened);
        }
    }
}
