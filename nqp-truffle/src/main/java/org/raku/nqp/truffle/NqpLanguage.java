package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * The one Truffle language of NQP: general code and regexes both live in
 * it. Code arrives already compiled -- the QAST backend encodes a block
 * into a program at compile time ({@link NqpWire}) and a regex into a
 * descriptor ({@code RxWire}) -- and parse only decodes and builds: a
 * Bytecode DSL root for a program, an {@code RxMatchRootNode} for a
 * descriptor. Nothing parses source text, except the harnesses' pattern
 * road ({@link RxCheck}, {@link RxBench}), which hands a bare pattern to
 * the regex parser.
 *
 * <p>Until 2026-09-11 the regex engine was a second registered language
 * ({@code RxLanguage}, id {@code nqp-rx}) with its own polyglot context,
 * so its matchers and the blocks calling them compiled in separate
 * Engines. One language in one context ({@link NqpPolyglot}) makes the
 * whole program one compilation world -- and is one language fewer for a
 * native image to carry.
 *
 * <p>{@code code-test:} sources are the {@link NqpCheck} harness's canned
 * programs.
 */
@TruffleLanguage.Registration(id = NqpLanguage.ID, name = "NQP", version = "0.1")
public final class NqpLanguage extends TruffleLanguage<NqpLanguage.Ctx> {

    public static final String ID = "nqp";

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
     * The call target for each source parsed, by source text -- programs
     * and matchers alike; their wire magics never collide. eval answers
     * a polyglot Value, and calling through one boxes everything, so the
     * embedders ({@link NqpCodeEngine}, {@code NqpGrammarEngine}, both
     * through {@link NqpPolyglot}) collect the bare target from here.
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
            return new ConstantRootNode(this, new Program(target)).getCallTarget();
        }
        if (source.startsWith("code-test:")) {
            CallTarget target = canned(source.substring("code-test:".length()));
            return new ConstantRootNode(this, new Program(target)).getCallTarget();
        }
        /* Everything else is a regex: a descriptor the backend flattened,
         * or a pattern for the harnesses (RxMatchRootNode tells them apart). */
        CallTarget match = RxMatchRootNode.create(this, source);
        PARSED.put(source, match);
        return new ConstantRootNode(this, new Matcher(match)).getCallTarget();
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

    /**
     * What eval hands back for a regex source: an executable the harnesses
     * call per target ({@code matcher.execute(input, pos)}). The engines
     * never go through it -- they take the bare target from PARSED.
     */
    @ExportLibrary(InteropLibrary.class)
    public static final class Matcher implements TruffleObject {
        private final CallTarget target;

        Matcher(CallTarget target) { this.target = target; }

        public CallTarget callTarget() { return target; }

        @ExportMessage boolean isExecutable() { return true; }

        @ExportMessage Object execute(Object[] args) throws ArityException, UnsupportedTypeException {
            if (args.length < 1 || args.length > 3) {
                throw ArityException.create(1, 3, args.length);
            }
            if (!(args[0] instanceof String s)) {
                throw UnsupportedTypeException.create(args, "target must be a string");
            }
            return switch (args.length) {
                case 1 -> target.call(s);
                case 2 -> target.call(s, args[1]);
                default -> target.call(s, args[1], args[2]);
            };
        }
    }
}
