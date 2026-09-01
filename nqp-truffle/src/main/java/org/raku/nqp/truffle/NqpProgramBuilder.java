package org.raku.nqp.truffle;

import com.oracle.truffle.api.bytecode.BytecodeLocal;

import org.raku.nqp.runtime.CallSiteDescriptor;

/**
 * Translates a decoded {@link NqpWire} program into Bytecode DSL builder
 * calls. One instance per translation; the walk is a straight recursive
 * descent over the prefix-encoded tree.
 *
 * <p>Two mechanical points worth stating:
 *
 * <ul>
 * <li>Positions where the DSL requires a void child (non-final block
 *   statements are fine, but loop bodies and discarded values must not
 *   leave a value) get wrapped in a store to a scratch local — the
 *   cheapest universally-correct discard.</li>
 * <li>{@code repeat} loops re-walk the body subtree once ahead of the
 *   loop rather than growing a control-flow feature: the walker can walk
 *   any subtree twice because the wire form is just ints.</li>
 * </ul>
 */
final class NqpProgramBuilder {

    private final NqpRootNodeGen.Builder b;
    private final int[] code;
    private final String[] pool;
    private BytecodeLocal[] locals;
    private BytecodeLocal sink;
    private BytecodeLocal tmp;

    private final NqpWire.Program program;

    private NqpProgramBuilder(NqpRootNodeGen.Builder b, NqpWire.Program p) {
        this.b = b;
        this.code = p.code();
        this.pool = p.pool();
        this.program = p;
    }

    /** Builds the one root node for a program. */
    static void build(NqpRootNodeGen.Builder b, NqpWire.Program p) {
        new NqpProgramBuilder(b, p).root(p.nlocals());
    }

    private void root(int nlocals) {
        b.beginRoot();
        locals = new BytecodeLocal[nlocals];
        for (int i = 0; i < nlocals; i++) locals[i] = b.createLocal();
        sink = b.createLocal();
        tmp = b.createLocal();
        // JVM method locals start zeroed; engine locals match, so a QAST
        // local read before its first bind answers what bytecode answers.
        for (int i = 0; i < nlocals; i++) {
            b.beginStoreLocal(locals[i]);
            switch (program.localType(i)) {
                case NqpWire.T_INT -> b.emitLoadConstant(0L);
                case NqpWire.T_NUM -> b.emitLoadConstant(0.0d);
                default -> b.emitNullC();
            }
            b.endStoreLocal();
        }
        b.beginReturn();
        b.beginStoreRet(program.resultType());
        walk(program.treeStart(), true);
        b.endStoreRet();
        b.endReturn();
        b.endRoot();
    }

    /**
     * Walks the subtree at {@code at}; emits builder calls when
     * {@code emit}, measures silently otherwise. Answers the position
     * after the subtree. When emitting, exactly one value is left unless
     * the node is inherently void (the caller sinks where it must).
     */
    private int walk(int at, boolean emit) {
        int tag = code[at];
        switch (tag) {
            case NqpWire.STMTS: {
                int n = code[at + 1];
                at += 2;
                if (n == 0) {
                    if (emit) b.emitNullC();
                    return at;
                }
                if (emit && n > 1) b.beginBlock();
                for (int i = 0; i < n; i++) {
                    boolean last = i == n - 1;
                    if (emit && !last) beginSink();
                    at = walk(at, emit);
                    if (emit && !last) endSink();
                }
                if (emit && n > 1) b.endBlock();
                return at;
            }
            case NqpWire.NULLC:
                if (emit) b.emitNullC();
                return at + 1;
            case NqpWire.IVAL:
                if (emit) b.emitLoadConstant(Long.parseLong(pool[code[at + 1]]));
                return at + 2;
            case NqpWire.NVAL:
                if (emit) b.emitLoadConstant(Double.parseDouble(pool[code[at + 1]]));
                return at + 2;
            case NqpWire.SVAL:
                if (emit) b.emitLoadConstant(pool[code[at + 1]]);
                return at + 2;
            case NqpWire.WVAL:
                if (emit) b.emitWvalGet(pool[code[at + 1]], code[at + 2]);
                return at + 3;
            case NqpWire.LEXGET:
                if (emit) b.emitLexGet(code[at + 1], pool[code[at + 2]]);
                return at + 3;
            case NqpWire.LEXBIND: {
                int type = code[at + 1];
                String name = pool[code[at + 2]];
                if (emit) b.beginLexBind(type, name);
                at = walk(at + 3, emit);
                if (emit) b.endLexBind();
                return at;
            }
            case NqpWire.LOCGET:
                if (emit) b.emitLoadLocal(locals[code[at + 2]]);
                return at + 3;
            case NqpWire.LOCBIND: {
                BytecodeLocal local = locals[code[at + 2]];
                // A bind is an expression whose value is the bound value;
                // store it, then load it back.
                if (emit) b.beginBlock();
                if (emit) b.beginStoreLocal(local);
                at = walk(at + 3, emit);
                if (emit) b.endStoreLocal();
                if (emit) b.emitLoadLocal(local);
                if (emit) b.endBlock();
                return at;
            }
            case NqpWire.IFV: {
                int condType = code[at + 1];
                int negate = code[at + 2];
                int hasElse = code[at + 3];
                if (emit) b.beginConditional();
                at = walkCond(at + 4, condType, negate, emit);
                at = walk(at, emit);
                if (hasElse != 0) {
                    at = walk(at, emit);
                } else {
                    if (emit) b.emitNullC();
                }
                if (emit) b.endConditional();
                return at;
            }
            case NqpWire.IFS: {
                int condType = code[at + 1];
                int negate = code[at + 2];
                int hasElse = code[at + 3];
                if (emit) b.beginBlock();
                if (emit) b.beginIfThenElse();
                at = walkCond(at + 4, condType, negate, emit);
                if (emit) beginSink();
                at = walk(at, emit);
                if (emit) endSink();
                if (hasElse != 0) {
                    if (emit) beginSink();
                    at = walk(at, emit);
                    if (emit) endSink();
                } else {
                    if (emit) emitNothing();
                }
                if (emit) b.endIfThenElse();
                if (emit) b.emitNullC();
                if (emit) b.endBlock();
                return at;
            }
            case NqpWire.LOOP: {
                int until = code[at + 1];
                int repeat = code[at + 2];
                int condType = code[at + 3];
                int condAt = at + 4;
                int bodyAt = walk(condAt, false);
                if (repeat != 0 && emit) {
                    // Run the body once ahead: repeat_while == body; while.
                    beginSink();
                    walk(bodyAt, true);
                    endSink();
                }
                if (emit) b.beginBlock();
                if (emit) b.beginWhile();
                walkCond(condAt, condType, until, emit);
                if (emit) beginSink();
                at = walk(bodyAt, emit);
                if (emit) endSink();
                if (emit) b.endWhile();
                if (emit) b.emitNullC();
                if (emit) b.endBlock();
                return at;
            }
            case NqpWire.DISPATCH: {
                int rtype = code[at + 1];
                String name = pool[code[at + 2]];
                int nargs = code[at + 3];
                at += 4;
                byte[] flags = new byte[nargs];
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                for (int i = 0; i < nargs; i++) {
                    int flag = code[at++];
                    int type = flag & 3;
                    byte csFlag = switch (type) {
                        case NqpWire.T_STR -> CallSiteDescriptor.ARG_STR;
                        case NqpWire.T_INT -> CallSiteDescriptor.ARG_INT;
                        case NqpWire.T_NUM -> CallSiteDescriptor.ARG_NUM;
                        default -> CallSiteDescriptor.ARG_OBJ;
                    };
                    if ((flag & 4) != 0) {
                        csFlag |= CallSiteDescriptor.ARG_NAMED;
                        names.add(pool[code[at++]]);
                    }
                    if ((flag & 8) != 0) csFlag |= CallSiteDescriptor.ARG_FLAT;
                    flags[i] = csFlag;
                }
                CallSiteDescriptor csd = new CallSiteDescriptor(flags,
                    names.isEmpty() ? null : names.toArray(new String[0]));
                if (emit) b.beginDispatchOp(rtype, name, csd);
                for (int i = 0; i < nargs; i++) at = walk(at, emit);
                if (emit) b.endDispatchOp();
                return at;
            }
            case NqpWire.OPCALL: {
                int id = code[at + 1];
                int nargs = code[at + 2];
                if (id < 0 || id >= NqpOps.OP_COUNT)
                    throw new IllegalStateException("nqpp: op id out of range: " + id);
                if (emit) b.beginRunOp(id);
                at += 3;
                for (int i = 0; i < nargs; i++) at = walk(at, emit);
                if (emit) b.endRunOp();
                return at;
            }
            case NqpWire.COERCE: {
                int kind = code[at + 1];
                if (emit) b.beginCoerce(kind);
                at = walk(at + 2, emit);
                if (emit) b.endCoerce();
                return at;
            }
            case NqpWire.PARAMS:
                return params(at, emit);
            case NqpWire.GETLEXOUTER:
                if (emit) b.emitLexOuterGet(pool[code[at + 1]]);
                return at + 2;
            case NqpWire.CODEREF:
                if (emit) b.emitCodeRefGet(code[at + 1]);
                return at + 2;
            default:
                throw new IllegalStateException("nqpp: unknown tag " + tag + " at " + at);
        }
    }

    /** A condition child, wrapped in typed truthiness (negated for until). */
    private int walkCond(int at, int condType, int negate, boolean emit) {
        if (emit) b.beginTruthy(condType, negate);
        at = walk(at, emit);
        if (emit) b.endTruthy();
        return at;
    }

    /**
     * The parameter prologue: arity check, then each parameter fetched and
     * bound the way the emitted bytecode prologue would. Void; leaves the
     * live csd and args in two dedicated locals while it runs.
     */
    private int params(int at, boolean emit) {
        int required = code[at + 1];
        int accepted = code[at + 2];
        int n = code[at + 3];
        at += 4;
        BytecodeLocal csdL = null;
        BytecodeLocal argsL = null;
        if (emit) {
            csdL = b.createLocal();
            argsL = b.createLocal();
            b.beginBlock();
            b.beginStoreLocal(csdL);
            b.emitCheckArity(required, accepted);
            b.endStoreLocal();
            b.beginStoreLocal(argsL);
            b.emitFlatArgs();
            b.endStoreLocal();
        }
        int posIdx = 0;
        boolean namedSlurpy = false;
        java.util.ArrayList<String> namedAllowed = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            int kind = code[at];
            int type = code[at + 1];
            int scope = code[at + 2];
            int target = code[at + 3];
            at += 4;
            String named = null;
            if (kind == 2 || kind == 3) named = pool[code[at++]];
            if (kind == 2) namedAllowed.add(named);
            if (kind == 3) namedSlurpy = true;
            int hasDefault = code[at++];
            if (type != NqpWire.T_OBJ)
                throw new IllegalStateException("nqpp: typed parameters not yet encoded");

            if (emit) beginBindTarget(scope, target);
            if (hasDefault != 0) {
                // v = fetched-if-existed else default; the existed flag is
                // read before anything can clobber it.
                if (emit) {
                    b.beginBlock();
                    b.beginStoreLocal(tmp);
                }
                emitFetch(kind, named, posIdx, 1, csdL, argsL, emit);
                if (emit) {
                    b.endStoreLocal();
                    b.beginConditional();
                    b.emitParamExisted();
                    b.emitLoadLocal(tmp);
                }
                at = walk(at, emit);
                if (emit) {
                    b.endConditional();
                    b.endBlock();
                }
            } else {
                emitFetch(kind, named, posIdx, 0, csdL, argsL, emit);
            }
            if (emit) endBindTarget(scope);
            if (kind == 0) posIdx++;
            // Param tasks: whatever the declaration carried as children
            // (the bytecode path's emit_param_tasks), run after the bind.
            int ntasks = code[at++];
            for (int t = 0; t < ntasks; t++) {
                if (emit) beginSink();
                at = walk(at, emit);
                if (emit) endSink();
            }
        }
        if (emit && !namedSlurpy) {
            // The invoker's expectation check would have rejected extra
            // named arguments before the call; re-make it here.
            beginSink();
            b.beginCheckNamedAllowed(namedAllowed.toArray(new String[0]));
            b.emitLoadLocal(csdL);
            b.endCheckNamedAllowed();
            endSink();
        }
        if (emit) {
            b.emitNullC();
            b.endBlock();
        }
        return at;
    }

    private void emitFetch(int kind, String named, int posIdx, int opt,
                           BytecodeLocal csdL, BytecodeLocal argsL, boolean emit) {
        if (!emit) return;
        switch (kind) {
            case 0 -> b.beginPosParam(posIdx, opt);
            case 1 -> b.beginPosSlurpy(posIdx);
            case 2 -> b.beginNamedParam(named, opt);
            case 3 -> b.beginNamedSlurpy();
            default -> throw new IllegalStateException("nqpp: bad param kind " + kind);
        }
        b.emitLoadLocal(csdL);
        b.emitLoadLocal(argsL);
        switch (kind) {
            case 0 -> b.endPosParam();
            case 1 -> b.endPosSlurpy();
            case 2 -> b.endNamedParam();
            case 3 -> b.endNamedSlurpy();
        }
    }

    private void beginBindTarget(int scope, int target) {
        if (scope == 0) {
            beginSink();
            b.beginLexBind(NqpWire.T_OBJ, pool[target]);
        } else {
            b.beginStoreLocal(locals[target]);
        }
    }

    private void endBindTarget(int scope) {
        if (scope == 0) {
            b.endLexBind();
            endSink();
        } else {
            b.endStoreLocal();
        }
    }

    /** Discards the value the wrapped child leaves. */
    private void beginSink() {
        b.beginStoreLocal(sink);
    }

    private void endSink() {
        b.endStoreLocal();
    }

    /** A void filler for an empty else branch. */
    private void emitNothing() {
        b.beginStoreLocal(sink);
        b.emitNullC();
        b.endStoreLocal();
    }
}
