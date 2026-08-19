package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/**
 * Runs a {@link RxProgram} against a target.
 *
 * <p>One loop, and a stack of choice points. A {@code SPLIT} pushes the arm
 * it did not take together with the position to resume at; a failure pops
 * the most recent one and carries on from there. That is the same discipline
 * as the bytecode backend's mark stack -- and unlike the recursive matcher
 * that preceded it, partial evaluation can compile it, because there is a
 * loop here rather than an unbounded recursion for PE to inline into.
 *
 * <p>The program is compilation-final, so PE specializes this loop to one
 * particular program: the dispatch on opcode folds away and what remains is
 * code for that pattern alone. A matcher for a grammar that only exists at
 * run time, which is the whole point.
 */
public final class RxVmNode extends Node {

    /** No match. */
    public static final int NO_MATCH = -1;

    private static final int CHOICE_WIDTH = 3;   // pc, pos, capture-log height
    private static final int UNDO_WIDTH = 2;     // register, previous value
    private static final int[] EMPTY = new int[0];
    private static final String[] NO_NAMES = new String[0];

    @CompilationFinal private final RxProgram program;

    public RxVmNode(RxProgram program) {
        this.program = program;
    }

    public int match(RxCursor cursor, int startPos) {
        return run(cursor, cursor.target(), cursor.eos(), startPos);
    }

    /*
     * Deliberately not @ExplodeLoop. Exploding along the program's control
     * flow is the usual move for a bytecode interpreter, but it only
     * terminates for an acyclic program, and a quantifier compiles to a jump
     * back -- MERGE_EXPLODE bails with "too many loop explosion iterations".
     * Left as an ordinary loop, partial evaluation still specializes it to
     * the program, since the code array is compilation-final; it just keeps
     * a loop rather than unrolling one.
     */
    private int run(RxCursor cursor, String target, int eos, int startPos) {
        final int[] code = program.code;
        final Object[] pool = program.pool;

        /* Sized to what this program can actually use, and skipped
         * altogether when it uses none: a scan enters here once per
         * position, so an array allocated per match is one per character of
         * the target. */
        int[] regs = program.registers == 0 ? EMPTY : new int[program.registers];
        int[] choices = new int[program.choiceDepth * CHOICE_WIDTH];
        int choiceTop = 0;
        int[] undo = program.captures == 0 ? EMPTY : new int[program.captures * UNDO_WIDTH];
        int undoTop = 0;
        /* Captures are recorded as they are passed, and taken back when a
         * choice point before them is resumed; that is what the undo log is
         * for. The bytecode engine unwinds its capture stack for the same
         * reason. */
        int[] capStart = program.captures == 0 ? EMPTY : new int[program.registers];
        String[] capName = program.captures == 0 ? NO_NAMES : new String[program.registers];
        int[] capEnd = program.captures == 0 ? EMPTY : new int[program.registers];

        int pc = 0;
        int pos = startPos;

        while (true) {
            boolean failed = false;
            switch (code[pc]) {
                case RxProgram.MATCH -> {
                    if (capName.length != 0) flush(cursor, capName, capStart, capEnd);
                    return pos;
                }
                case RxProgram.CHAR -> {
                    String text = (String) pool[code[pc + 1]];
                    int flags = code[pc + 2];
                    boolean ignoreCase = (flags & RxProgram.F_IGNORECASE) != 0;
                    boolean hit = pos + text.length() <= eos
                        && target.regionMatches(ignoreCase, pos, text, 0, text.length());
                    if (hit == ((flags & RxProgram.F_NEGATE) != 0)) {
                        failed = true;
                    } else {
                        if ((flags & RxProgram.F_ZEROWIDTH) == 0) pos += text.length();
                        pc += 3;
                    }
                }
                case RxProgram.ONE -> {
                    if (pos >= eos) {
                        failed = true;
                    } else {
                        RxProgram.CharPred pred = (RxProgram.CharPred) pool[code[pc + 1]];
                        int cp = target.codePointAt(pos);
                        if (!pred.holds(cp)) {
                            failed = true;
                        } else {
                            pos += Character.charCount(cp);
                            pc += 2;
                        }
                    }
                }
                case RxProgram.ANCHOR -> {
                    if (!anchorHolds(code[pc + 1], target, pos, eos)) {
                        failed = true;
                    } else {
                        pc += 2;
                    }
                }
                case RxProgram.SPLIT -> {
                    if (choiceTop + CHOICE_WIDTH > choices.length) {
                        choices = grow(choices);
                    }
                    choices[choiceTop] = code[pc + 2];
                    choices[choiceTop + 1] = pos;
                    choices[choiceTop + 2] = undoTop;
                    choiceTop += CHOICE_WIDTH;
                    pc = code[pc + 1];
                }
                case RxProgram.JMP -> pc = code[pc + 1];
                case RxProgram.MARK -> {
                    regs[code[pc + 1]] = pos;
                    pc += 2;
                }
                case RxProgram.EMPTY_CHECK -> {
                    /* A repetition that consumed nothing would spin here. */
                    if (pos == regs[code[pc + 1]]) {
                        failed = true;
                    } else {
                        pc += 2;
                    }
                }
                case RxProgram.CAP_START -> {
                    int r = code[pc + 1];
                    if (undoTop + UNDO_WIDTH > undo.length) undo = grow(undo);
                    undo[undoTop] = r;
                    undo[undoTop + 1] = capStart[r];
                    undoTop += UNDO_WIDTH;
                    capStart[r] = pos;
                    pc += 2;
                }
                case RxProgram.CAP_END -> {
                    int r = code[pc + 1];
                    capEnd[r] = pos;
                    capName[r] = (String) pool[code[pc + 2]];
                    pc += 3;
                }
                case RxProgram.SUB -> {
                    String name = (String) pool[code[pc + 1]];
                    int flags = code[pc + 2];
                    int r = callSubrule(cursor, name, pos);
                    boolean matched = r != NO_MATCH;
                    if (matched == ((flags & RxProgram.F_NEGATE) != 0)) {
                        failed = true;
                    } else {
                        if (matched && (flags & RxProgram.F_ZEROWIDTH) == 0) pos = r;
                        pc += 3;
                    }
                }
                default -> throw new IllegalStateException("bad opcode " + code[pc]);
            }

            if (failed) {
                if (choiceTop == 0) return NO_MATCH;
                choiceTop -= CHOICE_WIDTH;
                pc = choices[choiceTop];
                pos = choices[choiceTop + 1];
                int wantUndo = choices[choiceTop + 2];
                while (undoTop > wantUndo) {
                    undoTop -= UNDO_WIDTH;
                    capStart[undo[undoTop]] = undo[undoTop + 1];
                }
            }
        }
    }

    private static boolean anchorHolds(int kind, String target, int pos, int eos) {
        return switch (RxTree.Anchor.Kind.values()[kind]) {
            case BOS -> pos == 0;
            case EOS -> pos == eos;
            case BOL -> pos == 0 || target.charAt(pos - 1) == '\n';
            case EOL -> pos == eos || target.charAt(pos) == '\n';
            case LWB -> pos < eos && isWord(target, pos)
                        && (pos == 0 || !isWord(target, pos - 1));
            case RWB -> pos > 0 && isWord(target, pos - 1)
                        && (pos == eos || !isWord(target, pos));
        };
    }

    private static boolean isWord(String target, int pos) {
        char c = target.charAt(pos);
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int[] grow(int[] array) {
        int[] bigger = new int[array.length * 2];
        System.arraycopy(array, 0, bigger, 0, array.length);
        return bigger;
    }

    /* The two ways out of the engine and into NQP: a subrule is compiled
     * bytecode, and a capture lands on the cursor. Both are boundaries. */

    @TruffleBoundary
    private static int callSubrule(RxCursor cursor, String name, int pos) {
        return cursor.callSubrule(name, pos);
    }

    @TruffleBoundary
    private static void flush(RxCursor cursor, String[] names, int[] starts, int[] ends) {
        for (int r = 0; r < names.length; r++) {
            if (names[r] != null) cursor.capture(names[r], starts[r], ends[r]);
        }
    }
}
