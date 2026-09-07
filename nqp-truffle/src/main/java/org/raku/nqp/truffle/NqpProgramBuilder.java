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
        // For objects that really is a Java null (aconst_null), not the
        // VMNull singleton NullC now stands for.
        for (int i = 0; i < nlocals; i++) {
            b.beginStoreLocal(locals[i]);
            switch (program.localType(i)) {
                case NqpWire.T_INT -> b.emitLoadConstant(0L);
                case NqpWire.T_NUM -> b.emitLoadConstant(0.0d);
                default -> b.emitLoadNull();
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
                    if (emit) b.emitLoadNull();
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
            case NqpWire.JNULL:
                if (emit) b.emitLoadNull();
                return at + 1;
            case NqpWire.CURLEXPAD:
                if (emit) b.emitCurLexpad();
                return at + 1;
            case NqpWire.P6ARGVMARRAY:
                if (emit) b.emitP6ArgVmArray();
                return at + 1;
            case NqpWire.USECAPTURE:
                if (emit) b.emitUseCapture();
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
                if (emit) b.emitWvalGet(pool[code[at + 1]], code[at + 2], new NqpOps.WvalSite());
                return at + 3;
            case NqpWire.LEXGET:
                if (emit) b.emitLexGet(code[at + 1], pool[code[at + 2]], new NqpOps.LexSite());
                return at + 3;
            case NqpWire.LEXBIND: {
                int type = code[at + 1];
                String name = pool[code[at + 2]];
                if (emit) b.beginLexBind(type, name, new NqpOps.LexSite());
                at = walk(at + 3, emit);
                if (emit) b.endLexBind();
                return at;
            }
            case NqpWire.LEXREF:
                if (emit) b.emitLexRef(code[at + 1], pool[code[at + 2]], code[at + 3],
                    new NqpOps.LexSite());
                return at + 4;
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
                    if (emit) b.emitLoadNull();
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
                if (emit) b.emitLoadNull();
                if (emit) b.endBlock();
                return at;
            }
            case NqpWire.LOOP: {
                int until = code[at + 1];
                int repeat = code[at + 2];
                int hasNext = code[at + 3];
                int condType = code[at + 4];
                int condAt = at + 5;
                int bodyAt = walk(condAt, false);
                int nextAt = walk(bodyAt, false);
                // The whole region ends after the "next" expr if it is present.
                int endAt = hasNext != 0 ? walk(nextAt, false) : nextAt;
                if (repeat != 0 && emit) {
                    // Run the body once ahead: repeat_while == body; while.
                    // (repeat + a next-expr is refused by the encoder.)
                    beginSink();
                    walk(bodyAt, true);
                    endSink();
                }
                if (emit) {
                    b.beginBlock();
                    b.beginWhile();
                    walkCond(condAt, condType, until, true);
                    beginSink();
                    walk(bodyAt, true);
                    // The "next" expr runs after the body, before the re-test.
                    if (hasNext != 0) walk(nextAt, true);
                    endSink();
                    b.endWhile();
                    b.emitLoadNull();
                    b.endBlock();
                }
                return endAt;
            }
            case NqpWire.LOOPH: {
                // A loop with last/next/redo handlers: the bytecode shape
                // (Compiler.nqp's while/until emission) reconstructed from
                // structured operations. Backward jumps aren't a DSL
                // feature, so REDO is a flag-driven inner loop instead of
                // a branch to a label; the observable order of events --
                // curHandler delimiting, unwind_check, category routing --
                // matches the emitted bytecode exactly.
                int until = code[at + 1];
                int repeat = code[at + 2];
                int hasNext = code[at + 3];
                int hasLabel = code[at + 4];
                int labelLocalIdx = code[at + 5];
                int condType = code[at + 6];
                int lastId = code[at + 7];
                int nrId = code[at + 8];
                int outerIdx = code[at + 9];
                int labelAt = at + 10;
                int condAt = hasLabel != 0 ? walk(labelAt, false) : labelAt;
                int bodyAt = walk(condAt, false);
                int nextAt = walk(bodyAt, false);
                int endAt = hasNext != 0 ? walk(nextAt, false) : nextAt;
                if (!emit) return endAt;

                // A labeled loop keeps its label object in a block local, read
                // by the unwind arms as the `where` for _is_same_label; an
                // unlabeled loop passes null (-> _rethrow_label).
                BytecodeLocal labelLocal = hasLabel != 0 ? locals[labelLocalIdx] : null;
                BytecodeLocal redoL = b.createLocal();
                b.beginBlock();
                if (hasLabel != 0) {
                    b.beginStoreLocal(labelLocal);
                    walk(labelAt, true);
                    b.endStoreLocal();
                }
                b.beginTryCatch();
                {   // try: the loop itself, cond and all, under lastId.
                    b.beginBlock();
                    b.emitSetCurHandler(lastId);
                    // repeat_: run the body once ahead of the first cond test,
                    // inside these same regions (Compiler.nqp's goto redo_lbl).
                    if (repeat != 0) {
                        emitLoophBody(redoL, bodyAt, nextAt, hasNext != 0, nrId, lastId, labelLocal);
                    }
                    b.beginWhile();
                    walkCond(condAt, condType, until, true);
                    emitLoophBody(redoL, bodyAt, nextAt, hasNext != 0, nrId, lastId, labelLocal);
                    b.endWhile();
                    b.emitSetCurHandler(outerIdx);
                    b.endBlock();
                }
                {   // catch: a LAST aimed here ends the loop quietly.
                    b.beginBlock();
                    b.emitSetCurHandler(outerIdx);
                    b.beginLoopLastUnwind(lastId, outerIdx);
                    if (labelLocal != null) b.emitLoadLocal(labelLocal); else b.emitLoadNull();
                    b.emitLoadException();
                    b.endLoopLastUnwind();
                    b.endBlock();
                }
                b.endTryCatch();
                b.emitLoadNull();
                b.endBlock();
                return endAt;
            }
            case NqpWire.HANDLE: {
                // The handle op's nesting, reconstructed: an inner TryCatch
                // makes host throwables into nqp exceptions FROM INSIDE the
                // outer region (so a CATCH in this very frame can take
                // them), the outer one runs unwind_check and takes the
                // handler's result, honoring cf.exitAfterUnwind with an
                // early typed return. The dispatcher closure was bound to
                // its lexical by ordinary tags just before this node.
                int hid = code[at + 1];
                int outerIdx = code[at + 2];
                int cares = code[at + 3];
                int childAt = at + 4;
                if (!emit) return walk(childAt, false);

                BytecodeLocal resL = b.createLocal();
                b.beginBlock();
                b.beginTryCatch();
                {   // try: protected code under hid, host errors converted.
                    b.beginBlock();
                    b.emitSetCurHandler(hid);
                    b.beginTryCatch();
                    {
                        b.beginStoreLocal(resL);
                        walk(childAt, true);
                        b.endStoreLocal();
                    }
                    {
                        b.beginHostErrToUnwind();
                        b.emitLoadException();
                        b.endHostErrToUnwind();
                    }
                    b.endTryCatch();
                    b.emitSetCurHandler(outerIdx);
                    b.endBlock();
                }
                {   // catch: unwind check, result, exit-after-unwind.
                    b.beginBlock();
                    b.emitSetCurHandler(outerIdx);
                    b.beginStoreLocal(resL);
                    b.beginHandleUnwind(hid, outerIdx, cares);
                    b.emitLoadException();
                    b.endHandleUnwind();
                    b.endStoreLocal();
                    b.beginIfThen();
                    b.emitExitAfterUnwind();
                    b.beginReturn();
                    b.beginStoreRet(NqpWire.T_OBJ);
                    b.emitLoadLocal(resL);
                    b.endStoreRet();
                    b.endReturn();
                    b.endIfThen();
                    b.endBlock();
                }
                b.endTryCatch();
                b.emitLoadLocal(resL);
                b.endBlock();
                return walk(childAt, false);
            }
            case NqpWire.HANDLEPAYLOAD: {
                // The throwpayloadlex catcher: no host-error conversion, no
                // exit check -- the catch runs unwind_check, discards the
                // unwind, and evaluates the handler expression here (it
                // reads nqp::lastexpayload, published by invokeHandler).
                int hid = code[at + 1];
                int outerIdx = code[at + 2];
                int protAt = at + 3;
                int handlerAt = walk(protAt, false);
                if (!emit) return walk(handlerAt, false);

                BytecodeLocal resL = b.createLocal();
                b.beginBlock();
                b.beginTryCatch();
                {
                    b.beginBlock();
                    b.emitSetCurHandler(hid);
                    b.beginStoreLocal(resL);
                    walk(protAt, true);
                    b.endStoreLocal();
                    b.emitSetCurHandler(outerIdx);
                    b.endBlock();
                }
                {
                    b.beginBlock();
                    b.emitSetCurHandler(outerIdx);
                    beginSink();
                    b.beginHandleUnwind(hid, outerIdx, 0);
                    b.emitLoadException();
                    b.endHandleUnwind();
                    endSink();
                    b.beginStoreLocal(resL);
                    walk(handlerAt, true);
                    b.endStoreLocal();
                    b.endBlock();
                }
                b.endTryCatch();
                b.emitLoadLocal(resL);
                b.endBlock();
                return walk(handlerAt, false);
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
                        // A flat named arg (`|%h`) sets the named bit but
                        // carries no name string -- explodeFlattening reads
                        // the hash keys. Only a non-flat named arg names a
                        // slot, matching the encoder and the bytecode path.
                        if ((flag & 8) == 0) names.add(pool[code[at++]]);
                    }
                    if ((flag & 8) != 0) csFlag |= CallSiteDescriptor.ARG_FLAT;
                    flags[i] = csFlag;
                }
                CallSiteDescriptor csd = new CallSiteDescriptor(flags,
                    names.isEmpty() ? null : names.toArray(new String[0]));
                // Every dispatch is a potential continuation suspension
                // point: the result rides a local so a suspend token can
                // be yielded and the resumed value take its place.
                BytecodeLocal dres = emit ? b.createLocal() : null;
                if (emit) {
                    b.beginBlock();
                    b.beginStoreLocal(dres);
                    // The constant is the instruction's inline cache as
                    // well as its shape; see NqpOps.EngineSite.
                    b.beginDispatchOp(rtype, name, new NqpOps.EngineSite(csd));
                }
                for (int i = 0; i < nargs; i++) at = walk(at, emit);
                if (emit) {
                    b.endDispatchOp();
                    b.endStoreLocal();
                    emitSuspendCheck(dres);
                    b.emitLoadLocal(dres);
                    b.endBlock();
                }
                return at;
            }
            case NqpWire.OPCALL: {
                int id = code[at + 1];
                int nargs = code[at + 2];
                if (id < 0 || id >= NqpOps.OP_COUNT)
                    throw new IllegalStateException("nqpp: op id out of range: " + id);
                // Every table op can in principle reach user code (a sink,
                // a Proxy FETCH, a handler); all sites carry the suspension
                // tail, and the token check speculates to false in compiled
                // code.
                BytecodeLocal ores = emit ? b.createLocal() : null;
                if (emit) {
                    b.beginBlock();
                    b.beginStoreLocal(ores);
                }
                // The ops with a per-instruction site: getattr/bindattr
                // (NqpOps.AttrSite) and the type-check family (NqpTypeOps,
                // jesp diamond 3). Chosen here, at load: no wire format or
                // setting recompile is involved. Same suspension wrapper as
                // any table op.
                Op op = dedicatedOp(id, nargs);
                if (emit) beginOp(op, id);
                at += 3;
                for (int i = 0; i < nargs; i++) at = walk(at, emit);
                if (emit) endOp(op);
                if (emit) {
                    b.endStoreLocal();
                    emitSuspendCheck(ores);
                    b.emitLoadLocal(ores);
                    b.endBlock();
                }
                return at;
            }
            case NqpWire.CLASSLIB: {
                int rtype = code[at + 1];
                String cls = pool[code[at + 2]];
                String meth = pool[code[at + 3]];
                String desc = pool[code[at + 4]];
                boolean tcArg = code[at + 5] != 0;
                int nargs = code[at + 6];
                at += 7 + nargs;   // the arg types are informational here
                BytecodeLocal ores = emit ? b.createLocal() : null;
                if (emit) {
                    b.beginBlock();
                    b.beginStoreLocal(ores);
                }
                if (emit) b.beginClassLibOp(rtype, new NqpOps.ClassLibSite(cls, meth, desc, tcArg, nargs));
                for (int i = 0; i < nargs; i++) at = walk(at, emit);
                if (emit) b.endClassLibOp();
                if (emit) {
                    b.endStoreLocal();
                    emitSuspendCheck(ores);
                    b.emitLoadLocal(ores);
                    b.endBlock();
                }
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
            default: {
                // Name the neighbourhood: a bad tag is an encoder layout
                // bug, and the words around it are what locates it.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < code.length; i++)
                    sb.append(i == at ? " [" : " ").append(code[i]).append(i == at ? "]" : "");
                throw new IllegalStateException("nqpp: unknown tag " + tag + " at " + at
                    + " of " + code.length + " words; program:" + sb
                    + "; pool=" + java.util.Arrays.toString(pool));
            }
        }
    }

    /* ----- table ops with a dedicated operation ----- */

    private enum Op { RUN, GETATTR, BINDATTR, DECONT, ISNULL, ISCONCRETE, ISTYPE, ASSERTPARAMCHECK, P6TYPECHECKRV, CREATE,
                      INT_BIN, INT_UN, NUM_BIN, NUM_CMP, NUM_NEG }

    /** Which operation a table op becomes; RUN is the generic road. */
    private static Op dedicatedOp(int id, int nargs) {
        switch (NqpNativeOps.kindOf(id, nargs)) {
            case NqpNativeOps.INT_BIN: return Op.INT_BIN;
            case NqpNativeOps.INT_UN: return Op.INT_UN;
            case NqpNativeOps.NUM_BIN: return Op.NUM_BIN;
            case NqpNativeOps.NUM_CMP: return Op.NUM_CMP;
            case NqpNativeOps.NUM_NEG: return Op.NUM_NEG;
            default: break;
        }
        if (id == NqpOps.OP_GETATTR && nargs == 3) return Op.GETATTR;
        if (id == NqpOps.OP_BINDATTR && nargs == 4) return Op.BINDATTR;
        if (id == NqpOps.OP_DECONT && nargs == 1) return Op.DECONT;
        if (id == NqpOps.OP_ISNULL && nargs == 1) return Op.ISNULL;
        if (id == NqpOps.OP_ISCONCRETE && nargs == 1) return Op.ISCONCRETE;
        if (id == NqpOps.OP_ISTYPE && nargs == 2) return Op.ISTYPE;
        if (id == NqpOps.OP_ASSERTPARAMCHECK && nargs == 1) return Op.ASSERTPARAMCHECK;
        if (id == NqpOps.OP_P6TYPECHECKRV && nargs == 3) return Op.P6TYPECHECKRV;
        if (id == NqpOps.OP_CREATE && nargs == 1) return Op.CREATE;
        return Op.RUN;
    }

    private void beginOp(Op op, int id) {
        switch (op) {
            case RUN -> b.beginRunOp(id);
            case GETATTR -> b.beginGetAttrOp(new NqpOps.AttrSite());
            case BINDATTR -> b.beginBindAttrOp(new NqpOps.AttrSite());
            case DECONT -> b.beginDecontOp(new NqpTypeOps.DecontSite());
            case ISNULL -> b.beginIsNullOp();
            case ISCONCRETE -> b.beginIsConcreteOp(new NqpTypeOps.IsConcreteSite());
            case ISTYPE -> b.beginIsTypeOp(new NqpTypeOps.IsTypeSite());
            case ASSERTPARAMCHECK -> b.beginAssertParamCheckOp();
            case P6TYPECHECKRV -> b.beginP6TypeCheckRvOp(new NqpTypeOps.RvCheckSite());
            case CREATE -> b.beginCreateOp(new NqpTypeOps.CreateSite());
            case INT_BIN -> b.beginIntBinOp(id);
            case INT_UN -> b.beginIntUnOp(id);
            case NUM_BIN -> b.beginNumBinOp(id);
            case NUM_CMP -> b.beginNumCmpOp(id);
            case NUM_NEG -> b.beginNumNegOp();
        }
    }

    private void endOp(Op op) {
        switch (op) {
            case RUN -> b.endRunOp();
            case GETATTR -> b.endGetAttrOp();
            case BINDATTR -> b.endBindAttrOp();
            case DECONT -> b.endDecontOp();
            case ISNULL -> b.endIsNullOp();
            case ISCONCRETE -> b.endIsConcreteOp();
            case ISTYPE -> b.endIsTypeOp();
            case ASSERTPARAMCHECK -> b.endAssertParamCheckOp();
            case P6TYPECHECKRV -> b.endP6TypeCheckRvOp();
            case CREATE -> b.endCreateOp();
            case INT_BIN -> b.endIntBinOp();
            case INT_UN -> b.endIntUnOp();
            case NUM_BIN -> b.endNumBinOp();
            case NUM_CMP -> b.endNumCmpOp();
            case NUM_NEG -> b.endNumNegOp();
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
            if (kind == 2 || kind == 3 || kind == 4) named = pool[code[at++]];
            if (kind == 2) namedAllowed.add(named);
            // Kind 4 is a discard `%_` named slurpy: it suppresses the
            // extra-named rejection exactly as a real slurpy does, but binds
            // nothing and needs no CallFrame -- the whole point of the
            // frame-free path for methods.
            if (kind == 3 || kind == 4) namedSlurpy = true;
            int hasDefault = code[at++];
            if (type != NqpWire.T_OBJ && kind != 0 && kind != 2)
                throw new IllegalStateException("nqpp: a slurpy parameter is always an object");

            if (kind == 4) {
                // No bind, no fetch; hasDefault is 0 and there are no tasks.
            } else {
                if (emit) beginBindTarget(scope, target,
                        type == NqpWire.T_UINT ? NqpWire.T_INT : type);
                if (hasDefault != 0) {
                    // v = fetched-if-existed else default; the existed flag is
                    // read before anything can clobber it.
                    if (emit) {
                        b.beginBlock();
                        b.beginStoreLocal(tmp);
                    }
                    emitFetch(kind, named, posIdx, 1, csdL, argsL, emit, type);
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
                    emitFetch(kind, named, posIdx, 0, csdL, argsL, emit, type);
                }
                if (emit) endBindTarget(scope);
                if (kind == 0) posIdx++;
            }
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
            b.emitLoadNull();
            b.endBlock();
        }
        return at;
    }

    private void emitFetch(int kind, String named, int posIdx, int opt,
                           BytecodeLocal csdL, BytecodeLocal argsL, boolean emit, int type) {
        if (!emit) return;
        switch (kind) {
            case 0 -> b.beginPosParam(posIdx, opt, type);
            case 1 -> b.beginPosSlurpy(posIdx);
            case 2 -> b.beginNamedParam(named, opt, type);
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

    private void beginBindTarget(int scope, int target, int type) {
        if (scope == 0) {
            beginSink();
            b.beginLexBind(type, pool[target], new NqpOps.LexSite());
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

    /**
     * The suspension tail of a call site: a suspend token in the local is
     * yielded (codeRun turns the yield into a ResumeStatus.Frame), and
     * whatever comes back through the resumed yield -- the call's real
     * result, or an injected exception rethrown by UnpackResumed --
     * replaces it.
     */
    private void emitSuspendCheck(BytecodeLocal t) {
        b.beginIfThen();
        b.beginIsSuspend();
        b.emitLoadLocal(t);
        b.endIsSuspend();
        b.beginStoreLocal(t);
        b.beginUnpackResumed();
        b.beginYield();
        b.emitLoadLocal(t);
        b.endYield();
        b.endUnpackResumed();
        b.endStoreLocal();
        b.endIfThen();
    }

    /**
     * The body of a handled loop (W_LOOPH): the run-once-with-redo block under
     * nrId, followed by the optional "next" expr under lastId. Emitted once
     * per iteration by the outer while, and once more ahead of the first cond
     * test for a repeat_ loop -- so the two call sites duplicate the body, as
     * the nohandler W_LOOP builder duplicates its body for repeat. redoL is a
     * shared scratch flag, reset to 1 at the start of each emission.
     */
    private void emitLoophBody(BytecodeLocal redoL, int bodyAt, int nextAt,
                              boolean hasNext, int nrId, int lastId,
                              BytecodeLocal labelLocal) {
        b.beginBlock();
        b.beginStoreLocal(redoL);
        b.emitLoadConstant(1L);
        b.endStoreLocal();
        b.beginWhile();
        b.beginNonZero();
        b.emitLoadLocal(redoL);
        b.endNonZero();
        {
            b.beginBlock();
            b.beginStoreLocal(redoL);
            b.emitLoadConstant(0L);
            b.endStoreLocal();
            b.beginTryCatch();
            {
                b.beginBlock();
                b.emitSetCurHandler(nrId);
                beginSink();
                walk(bodyAt, true);
                endSink();
                b.emitSetCurHandler(lastId);
                b.endBlock();
            }
            {   // catch: route NEXT/REDO, rethrow the rest.
                b.beginBlock();
                b.emitSetCurHandler(lastId);
                b.beginStoreLocal(redoL);
                b.beginLoopBodyUnwind(nrId, lastId);
                if (labelLocal != null) b.emitLoadLocal(labelLocal); else b.emitLoadNull();
                b.emitLoadException();
                b.endLoopBodyUnwind();
                b.endStoreLocal();
                b.endBlock();
            }
            b.endTryCatch();
            b.endBlock();
        }
        b.endWhile();
        // The "next" expr, under lastId: after the body's redo loop drains
        // (normal completion or a NEXT unwind routed to redo=0), before the
        // outer while re-tests the condition.
        if (hasNext) {
            beginSink();
            walk(nextAt, true);
            endSink();
        }
        b.endBlock();
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
        b.emitLoadNull();
        b.endStoreLocal();
    }
}
