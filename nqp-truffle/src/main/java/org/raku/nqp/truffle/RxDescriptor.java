package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a QAST::Regex tree that the JVM backend has flattened for us.
 *
 * <p>The backend already holds the grammar as QAST::Regex by the time it
 * emits code, so nothing needs re-parsing: it walks that tree once, at
 * compile time, into the flat form below, and the engine turns it back into
 * an {@link RxTree} and then a program. A flat int array plus a pool of
 * strings is chosen because it is what NQP can build and hand across
 * cheaply -- no Java objects constructed from the NQP side, and no NQP
 * types leaking into the engine.
 *
 * <p>Each node is a tag followed by its operands, children inline in the
 * order they are named. The encoding mirrors the rxtypes one for one, so a
 * reader can check it against {@code QAST::Regex} without a translation
 * table.
 */
public final class RxDescriptor {

    /* Tags. Children follow inline, so a node's size depends on its kind. */
    public static final int SEQ = 1;      // count, children...
    public static final int ALT = 2;      // count, children...
    public static final int LITERAL = 3;  // pool(text), flags
    public static final int CCLASS = 4;   // kind, negate
    public static final int ENUM = 5;     // pool(chars), negate
    public static final int RANGE = 6;    // lo, hi, negate
    public static final int ANCHOR = 7;   // kind
    public static final int QUANT = 8;    // min, max, greedy, child
    public static final int SUB = 9;      // pool(name), flags
    public static final int CAPTURE = 10; // pool(name), child

    /* CCLASS kinds, in the order the engine's predicates are listed. */
    public static final int CC_ANY = 0;
    public static final int CC_DIGIT = 1;
    public static final int CC_SPACE = 2;
    public static final int CC_WORD = 3;
    public static final int CC_NEWLINE = 4;
    public static final int CC_HSPACE = 5;
    public static final int CC_VSPACE = 6;

    private final int[] code;
    private final Object[] pool;
    private int at;

    private RxDescriptor(int[] code, Object[] pool) {
        this.code = code;
        this.pool = pool;
    }

    /** Turns a flattened QAST::Regex tree into what the engine compiles. */
    @TruffleBoundary
    public static RxTree.Node decode(int[] code, Object[] pool) {
        RxDescriptor d = new RxDescriptor(code, pool);
        RxTree.Node tree = d.node();
        if (d.at != code.length) {
            throw new IllegalArgumentException(
                "descriptor has " + (code.length - d.at) + " ints left over");
        }
        return tree;
    }

    /** Compiles a flattened tree straight to a program. */
    @TruffleBoundary
    public static RxProgram compile(int[] code, Object[] pool) {
        return RxProgram.compile(decode(code, pool));
    }

    private RxTree.Node node() {
        int tag = code[at++];
        switch (tag) {
            case SEQ -> {
                return new RxTree.Seq(children());
            }
            case ALT -> {
                return new RxTree.Alt(children());
            }
            case LITERAL -> {
                String text = (String) pool[code[at++]];
                int flags = code[at++];
                return new RxTree.Literal(text,
                    (flags & RxProgram.F_NEGATE) != 0,
                    (flags & RxProgram.F_ZEROWIDTH) != 0,
                    (flags & RxProgram.F_IGNORECASE) != 0);
            }
            case CCLASS -> {
                RxProgram.CharPred pred = predicate(code[at++]);
                return new RxTree.One(code[at++] != 0 ? RxTree.not(pred) : pred);
            }
            case ENUM -> {
                RxProgram.CharPred pred = RxTree.anyOf((String) pool[code[at++]]);
                return new RxTree.One(code[at++] != 0 ? RxTree.not(pred) : pred);
            }
            case RANGE -> {
                RxProgram.CharPred pred = RxTree.range(code[at++], code[at++]);
                return new RxTree.One(code[at++] != 0 ? RxTree.not(pred) : pred);
            }
            case ANCHOR -> {
                return new RxTree.Anchor(RxTree.Anchor.Kind.values()[code[at++]]);
            }
            case QUANT -> {
                int min = code[at++];
                int max = code[at++];
                boolean greedy = code[at++] != 0;
                return new RxTree.Quant(node(), min, max, greedy);
            }
            case SUB -> {
                String name = (String) pool[code[at++]];
                int flags = code[at++];
                return new RxTree.Sub(name,
                    (flags & RxProgram.F_ZEROWIDTH) != 0,
                    (flags & RxProgram.F_NEGATE) != 0);
            }
            case CAPTURE -> {
                String name = (String) pool[code[at++]];
                return new RxTree.Capture(name, node());
            }
            default -> throw new IllegalArgumentException("unknown descriptor tag " + tag);
        }
    }

    private List<RxTree.Node> children() {
        int count = code[at++];
        List<RxTree.Node> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(node());
        return out;
    }

    private static RxProgram.CharPred predicate(int kind) {
        return switch (kind) {
            case CC_ANY -> RxTree.ANY;
            case CC_DIGIT -> RxTree.DIGIT;
            case CC_SPACE -> RxTree.SPACE;
            case CC_WORD -> RxTree.WORD;
            case CC_NEWLINE -> RxTree.NEWLINE;
            case CC_HSPACE -> RxTree.HSPACE;
            case CC_VSPACE -> RxTree.VSPACE;
            default -> throw new IllegalArgumentException("unknown cclass kind " + kind);
        };
    }
}
