package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The Truffle language the regex engine lives in.
 *
 * <p>It has to be a registered language rather than loose RootNodes: a
 * RootNode built with a null language and called outside a polyglot context
 * runs correctly and is <em>never queued for compilation</em>, so partial
 * evaluation -- the entire point -- silently does not happen. The failure
 * looks like "Truffle is no faster", which is why the harness asserts on
 * the runtime name and on compilation actually being traced.
 */
@TruffleLanguage.Registration(id = RxLanguage.ID, name = "NQP Regex", version = "0.1")
public final class RxLanguage extends TruffleLanguage<RxLanguage.Ctx> {

    public static final String ID = "nqp-rx";

    public static final class Ctx { }

    @Override protected Ctx createContext(Env env) { return new Ctx(); }

    /**
     * Parsing a source yields the matcher for it. The tree built here is the
     * pattern, so each distinct pattern gets its own call target and its own
     * compiled code -- a parser for a grammar that only exists at runtime,
     * which is the thing a compile-time approach cannot reach.
     */
    @Override protected CallTarget parse(ParsingRequest request) {
        RxParser.Built built = RxParser.parse(request.getSource().getCharacters().toString());
        Matcher matcher = new Matcher(
            new MatchRootNode(this, built.entry(), built.slots()).getCallTarget());
        return new ConstantRootNode(this, matcher).getCallTarget();
    }

    /** Runs one pattern against one target; the unit partial evaluation compiles. */
    public static final class MatchRootNode extends RootNode {
        @Child private RxNodes.Rx entry;
        private final int slots;

        public MatchRootNode(TruffleLanguage<?> language, RxNodes.Rx entry, int slots) {
            super(language);
            this.entry = entry;
            this.slots = slots;
        }

        @Override public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            String target = (String) args[0];
            int pos = args.length > 1 ? (Integer) args[1] : 0;
            RxCursor cursor = args.length > 2 ? (RxCursor) args[2] : new RxCursor.OfString(target);
            return entry.match(new RxState(cursor, slots), pos);
        }
    }

    /** What eval hands back: an executable the embedder calls per target. */
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

    static final class ConstantRootNode extends RootNode {
        private final Object value;

        ConstantRootNode(TruffleLanguage<?> language, Object value) {
            super(language);
            this.value = value;
        }

        @Override public Object execute(VirtualFrame frame) { return value; }
    }
}
