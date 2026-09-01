package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CompilationUnit;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.sixmodel.SixModelObject;

/**
 * What the code interpreter's operations actually do: thin calls into the
 * existing runtime. Correctness first -- everything here crosses a
 * {@link TruffleBoundary} and boxes; the ops worth partial evaluation get
 * dedicated operations in {@link NqpRootNode} as measurement says they
 * earn it, and this table is the semantic reference they must match.
 *
 * The op ids are one half of a contract whose other half is
 * {@code QAST::TruffleEncoder} (nqp's src/vm/jvm/QAST/TruffleEncoder.nqp):
 * the encoder's op table lists the same names in the same order. An op
 * added on one side only fails loudly at program-compile time.
 */
final class NqpOps {

    /* Op ids, in encoder order. */
    static final int OP_SAY = 0, OP_PRINT = 1,
        OP_ADD_I = 2, OP_SUB_I = 3, OP_MUL_I = 4, OP_DIV_I = 5, OP_MOD_I = 6,
        OP_NEG_I = 7, OP_ABS_I = 8,
        OP_BITAND_I = 9, OP_BITOR_I = 10, OP_BITXOR_I = 11,
        OP_BITSHIFTL_I = 12, OP_BITSHIFTR_I = 13, OP_BITNEG_I = 14,
        OP_NOT_I = 15,
        OP_ISEQ_I = 16, OP_ISNE_I = 17, OP_ISLT_I = 18, OP_ISLE_I = 19,
        OP_ISGT_I = 20, OP_ISGE_I = 21,
        OP_ADD_N = 22, OP_SUB_N = 23, OP_MUL_N = 24, OP_DIV_N = 25, OP_NEG_N = 26,
        OP_ISEQ_N = 27, OP_ISNE_N = 28, OP_ISLT_N = 29, OP_ISLE_N = 30,
        OP_ISGT_N = 31, OP_ISGE_N = 32,
        OP_CONCAT = 33, OP_CHARS = 34, OP_UC = 35, OP_LC = 36,
        OP_SUBSTR2 = 37, OP_SUBSTR3 = 38, OP_INDEX = 39, OP_INDEXFROM = 40,
        OP_EQAT = 41, OP_CHR = 42, OP_JOIN = 43, OP_SPLIT = 44,
        OP_ISEQ_S = 45, OP_ISNE_S = 46, OP_ISLT_S = 47, OP_ISLE_S = 48,
        OP_ISGT_S = 49, OP_ISGE_S = 50,
        OP_DECONT = 51, OP_ISNULL = 52, OP_ISCONCRETE = 53, OP_ISTRUE = 54,
        OP_ISTYPE = 55, OP_EQADDR = 56, OP_WHAT = 57, OP_CREATE = 58, OP_CLONE = 59,
        OP_ELEMS = 60, OP_PUSH = 61, OP_POP = 62, OP_SHIFT = 63, OP_UNSHIFT = 64,
        OP_ATPOS = 65, OP_BINDPOS = 66, OP_ATKEY = 67, OP_BINDKEY = 68,
        OP_EXISTSKEY = 69, OP_DELETEKEY = 70,
        OP_ISCONT = 71, OP_HLLIZE = 72, OP_ISLIST = 73, OP_ISHASH = 74,
        OP_UNBOX_I = 75, OP_UNBOX_N = 76, OP_UNBOX_S = 77,
        OP_BOX_I = 78, OP_BOX_N = 79, OP_BOX_S = 80,
        OP_GETATTR = 81, OP_BINDATTR = 82,
        OP_ORD = 83, OP_NULL_S = 84, OP_ISTRUE_S = 85;

    static final int OP_COUNT = 86;

    /* COERCE kinds, in encoder order. */
    static final int C_I2O = 0, C_N2O = 1, C_S2O = 2,
        C_O2I = 3, C_O2N = 4, C_O2S = 5,
        C_I2N = 6, C_N2I = 7, C_I2S = 8;

    @TruffleBoundary
    static Object run(int id, Object[] a, CompilationUnit cu, ThreadContext tc, CallFrame cf) {
        switch (id) {
            case OP_SAY: return Ops.say(str(a[0]), tc);
            case OP_PRINT: return Ops.print(str(a[0]), tc);
            case OP_ADD_I: return lng(a[0]) + lng(a[1]);
            case OP_SUB_I: return lng(a[0]) - lng(a[1]);
            case OP_MUL_I: return lng(a[0]) * lng(a[1]);
            case OP_DIV_I: return Ops.div_i(lng(a[0]), lng(a[1]), tc);
            case OP_MOD_I: return lng(a[0]) % lng(a[1]);   // the bytecode path is a bare lrem
            case OP_NEG_I: return -lng(a[0]);
            case OP_ABS_I: return Math.abs(lng(a[0]));
            case OP_BITAND_I: return lng(a[0]) & lng(a[1]);
            case OP_BITOR_I: return lng(a[0]) | lng(a[1]);
            case OP_BITXOR_I: return lng(a[0]) ^ lng(a[1]);
            case OP_BITSHIFTL_I: return lng(a[0]) << lng(a[1]);
            case OP_BITSHIFTR_I: return lng(a[0]) >> lng(a[1]);
            case OP_BITNEG_I: return ~lng(a[0]);
            case OP_NOT_I: return lng(a[0]) == 0 ? 1L : 0L;
            case OP_ISEQ_I: return b(lng(a[0]) == lng(a[1]));
            case OP_ISNE_I: return b(lng(a[0]) != lng(a[1]));
            case OP_ISLT_I: return b(lng(a[0]) < lng(a[1]));
            case OP_ISLE_I: return b(lng(a[0]) <= lng(a[1]));
            case OP_ISGT_I: return b(lng(a[0]) > lng(a[1]));
            case OP_ISGE_I: return b(lng(a[0]) >= lng(a[1]));
            case OP_ADD_N: return dbl(a[0]) + dbl(a[1]);
            case OP_SUB_N: return dbl(a[0]) - dbl(a[1]);
            case OP_MUL_N: return dbl(a[0]) * dbl(a[1]);
            case OP_DIV_N: return dbl(a[0]) / dbl(a[1]);
            case OP_NEG_N: return -dbl(a[0]);
            case OP_ISEQ_N: return b(dbl(a[0]) == dbl(a[1]));
            case OP_ISNE_N: return b(dbl(a[0]) != dbl(a[1]));
            case OP_ISLT_N: return b(dbl(a[0]) < dbl(a[1]));
            case OP_ISLE_N: return b(dbl(a[0]) <= dbl(a[1]));
            case OP_ISGT_N: return b(dbl(a[0]) > dbl(a[1]));
            case OP_ISGE_N: return b(dbl(a[0]) >= dbl(a[1]));
            case OP_CONCAT: return Ops.concat(str(a[0]), str(a[1]));
            case OP_CHARS: return Ops.chars(str(a[0]));
            case OP_UC: return Ops.uc(str(a[0]));
            case OP_LC: return Ops.lc(str(a[0]));
            case OP_SUBSTR2: return Ops.substr2(str(a[0]), lng(a[1]));
            case OP_SUBSTR3: return Ops.substr3(str(a[0]), lng(a[1]), lng(a[2]));
            case OP_INDEX: return Ops.indexfrom(str(a[0]), str(a[1]), 0L);
            case OP_INDEXFROM: return Ops.indexfrom(str(a[0]), str(a[1]), lng(a[2]));
            case OP_EQAT: return Ops.eqat(str(a[0]), str(a[1]), lng(a[2]));
            case OP_CHR: return Ops.chr(lng(a[0]), tc);
            case OP_JOIN: return Ops.join(str(a[0]), smo(a[1]), tc);
            case OP_SPLIT: return Ops.split(str(a[0]), str(a[1]), tc);
            case OP_ISEQ_S: return b(str(a[0]).equals(str(a[1])));
            case OP_ISNE_S: return b(!str(a[0]).equals(str(a[1])));
            case OP_ISLT_S: return b(str(a[0]).compareTo(str(a[1])) < 0);
            case OP_ISLE_S: return b(str(a[0]).compareTo(str(a[1])) <= 0);
            case OP_ISGT_S: return b(str(a[0]).compareTo(str(a[1])) > 0);
            case OP_ISGE_S: return b(str(a[0]).compareTo(str(a[1])) >= 0);
            case OP_DECONT: return Ops.decont(smo(a[0]), tc);
            case OP_ISNULL: return Ops.isnull(smo(a[0]));
            case OP_ISCONCRETE: return Ops.isconcrete(smo(a[0]), tc);
            case OP_ISTRUE: return Ops.istrue(smo(a[0]), tc);
            case OP_ISTYPE: return Ops.istype(smo(a[0]), smo(a[1]), tc);
            case OP_EQADDR: return Ops.eqaddr(smo(a[0]), smo(a[1]));
            case OP_WHAT: return Ops.what(smo(a[0]), tc);
            case OP_CREATE: return Ops.create(smo(a[0]), tc);
            case OP_CLONE: return Ops.clone(smo(a[0]), tc);
            case OP_ELEMS: return Ops.elems(smo(a[0]), tc);
            case OP_PUSH: return Ops.push(smo(a[0]), smo(a[1]), tc);
            case OP_POP: return Ops.pop(smo(a[0]), tc);
            case OP_SHIFT: return Ops.shift(smo(a[0]), tc);
            case OP_UNSHIFT: return Ops.unshift(smo(a[0]), smo(a[1]), tc);
            case OP_ATPOS: return Ops.atpos(smo(a[0]), lng(a[1]), tc);
            case OP_BINDPOS: return Ops.bindpos(smo(a[0]), lng(a[1]), smo(a[2]), tc);
            case OP_ATKEY: return Ops.atkey(smo(a[0]), str(a[1]), tc);
            case OP_BINDKEY: return Ops.bindkey(smo(a[0]), str(a[1]), smo(a[2]), tc);
            case OP_EXISTSKEY: return Ops.existskey(smo(a[0]), str(a[1]), tc);
            case OP_DELETEKEY: return Ops.deletekey(smo(a[0]), str(a[1]), tc);
            case OP_ISCONT: return Ops.iscont(smo(a[0]));
            case OP_HLLIZE: return Ops.hllize(smo(a[0]), tc);
            case OP_ISLIST: return Ops.islist(smo(a[0]), tc);
            case OP_ISHASH: return Ops.ishash(smo(a[0]), tc);
            case OP_UNBOX_I: return Ops.unbox_i(smo(a[0]), tc);
            case OP_UNBOX_N: return Ops.unbox_n(smo(a[0]), tc);
            case OP_UNBOX_S: return Ops.unbox_s(smo(a[0]), tc);
            case OP_BOX_I: return Ops.box_i(lng(a[0]), cu.hllConfig.intBoxType, tc);
            case OP_BOX_N: return Ops.box_n(dbl(a[0]), cu.hllConfig.numBoxType, tc);
            case OP_BOX_S: return Ops.box_s(str(a[0]), cu.hllConfig.strBoxType, tc);
            case OP_GETATTR: return Ops.getattr(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_BINDATTR: return Ops.bindattr(smo(a[0]), smo(a[1]), str(a[2]), smo(a[3]), tc);
            case OP_ORD: return Ops.ordfirst(str(a[0]));
            case OP_NULL_S: return "";
            case OP_ISTRUE_S: return Ops.istrue_s(str(a[0]));
            default:
                throw new IllegalStateException("nqpp: unknown op id " + id);
        }
    }

    @TruffleBoundary
    static Object coerce(int kind, Object v, CompilationUnit cu, ThreadContext tc) {
        switch (kind) {
            case C_I2O: return Ops.box_i(lng(v), cu.hllConfig.intBoxType, tc);
            case C_N2O: return Ops.box_n(dbl(v), cu.hllConfig.numBoxType, tc);
            case C_S2O: return Ops.box_s(str(v), cu.hllConfig.strBoxType, tc);
            case C_O2I: return Ops.smart_intify(smo(v), tc);
            case C_O2N: return Ops.smart_numify(smo(v), tc);
            case C_O2S: return Ops.smart_stringify(smo(v), tc);
            case C_I2N: return (double) lng(v);
            case C_N2I: return (long) dbl(v);
            case C_I2S: return Long.toString(lng(v));
            default:
                throw new IllegalStateException("nqpp: unknown coercion " + kind);
        }
    }

    @TruffleBoundary
    static Object dispatch(String name, CallSiteDescriptor csd, Object[] args,
                           ThreadContext tc, CallFrame cf) {
        org.raku.nqp.dispatch.Dispatch.dispatchUncached(tc, name, csd, args);
        return Ops.result_o(cf);
    }

    @TruffleBoundary
    static boolean truthy(int type, Object v, ThreadContext tc) {
        switch (type) {
            case NqpWire.T_INT: return lng(v) != 0;
            case NqpWire.T_NUM: return dbl(v) != 0.0;
            case NqpWire.T_STR: return Ops.istrue_s(str(v)) != 0;
            default: return Ops.istrue(smo(v), tc) != 0;
        }
    }

    @TruffleBoundary
    static Object getlex(int type, String name, ThreadContext tc) {
        switch (type) {
            case NqpWire.T_INT: return Ops.getlex_i(name, tc);
            case NqpWire.T_NUM: return Ops.getlex_n(name, tc);
            case NqpWire.T_STR: return Ops.getlex_s(name, tc);
            default: return Ops.getlex(name, tc);
        }
    }

    @TruffleBoundary
    static Object bindlex(int type, String name, Object value, ThreadContext tc) {
        switch (type) {
            case NqpWire.T_INT: return Ops.bindlex_i(name, lng(value), tc);
            case NqpWire.T_NUM: return Ops.bindlex_n(name, dbl(value), tc);
            case NqpWire.T_STR: return Ops.bindlex_s(name, str(value), tc);
            default: return Ops.bindlex(name, smo(value), tc);
        }
    }

    @TruffleBoundary
    static Object getlexouter(String name, ThreadContext tc) {
        return Ops.getlexouter(name, tc);
    }

    @TruffleBoundary
    static Object wval(String handle, int idx, ThreadContext tc) {
        return Ops.wval(handle, idx, tc);
    }

    /* ----- parameter binding, mirroring the emitted prologue ----- */

    @TruffleBoundary
    static CallSiteDescriptor checkarity(CallFrame cf, CallSiteDescriptor csd, Object[] args,
                                         int required, int accepted) {
        return Ops.checkarity(cf, csd, args, required, accepted);
    }

    @TruffleBoundary
    static Object[] flatArgs(ThreadContext tc) {
        return tc.flatArgs;
    }

    @TruffleBoundary
    static Object posparam(CallFrame cf, Object csd, Object[] args, int idx, boolean opt) {
        CallSiteDescriptor cs = (CallSiteDescriptor) csd;
        return opt ? Ops.posparam_opt_o(cf, cs, args, idx) : Ops.posparam_o(cf, cs, args, idx);
    }

    @TruffleBoundary
    static Object namedparam(CallFrame cf, Object csd, Object[] args, String name, boolean opt) {
        CallSiteDescriptor cs = (CallSiteDescriptor) csd;
        return opt ? Ops.namedparam_opt_o(cf, cs, args, name) : Ops.namedparam_o(cf, cs, args, name);
    }

    @TruffleBoundary
    static Object posslurpy(ThreadContext tc, CallFrame cf, Object csd, Object[] args, int from) {
        return Ops.posslurpy(tc, cf, (CallSiteDescriptor) csd, args, from);
    }

    @TruffleBoundary
    static Object namedslurpy(ThreadContext tc, CallFrame cf, Object csd, Object[] args) {
        return Ops.namedslurpy(tc, cf, (CallSiteDescriptor) csd, args);
    }

    static boolean lastParamExisted(ThreadContext tc) {
        return tc.lastParameterExisted != 0;
    }

    /* ----- the typed return-register store run() finishes with ----- */

    @TruffleBoundary
    static void storeReturn(Object v, CallFrame cf) {
        if (v == null || v instanceof SixModelObject) Ops.return_o((SixModelObject) v, cf);
        else if (v instanceof Long l) Ops.return_i(l, cf);
        else if (v instanceof Double d) Ops.return_n(d, cf);
        else if (v instanceof String s) Ops.return_s(s, cf);
        else throw new IllegalStateException("nqpp: unreturnable value " + v.getClass());
    }

    /* ----- unwrap helpers; a miss is an encoder type bug, said loudly ----- */

    private static long lng(Object v) {
        if (v instanceof Long l) return l;
        throw new IllegalStateException("nqpp: expected int, got " + kind(v));
    }

    private static double dbl(Object v) {
        if (v instanceof Double d) return d;
        throw new IllegalStateException("nqpp: expected num, got " + kind(v));
    }

    private static String str(Object v) {
        if (v instanceof String s) return s;
        throw new IllegalStateException("nqpp: expected str, got " + kind(v));
    }

    private static SixModelObject smo(Object v) {
        if (v == null) return null;
        if (v instanceof SixModelObject o) return o;
        throw new IllegalStateException("nqpp: expected obj, got " + kind(v));
    }

    private static long b(boolean v) { return v ? 1L : 0L; }

    private static String kind(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private NqpOps() { }
}
