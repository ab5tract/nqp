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
        OP_ORD = 83, OP_NULL_S = 84, OP_ISTRUE_S = 85,
        OP_GETLEXDYN = 86, OP_BINDLEXDYN = 87, OP_FORCEOUTERCTX = 88,
        OP_CAN = 89, OP_ISINVOKABLE = 90, OP_SETELEMS = 91, OP_EXISTSPOS = 92,
        OP_CLONE_ND = 93, OP_SETCODEOBJ = 94, OP_GETCURHLLSYM = 95,
        OP_TAKECLOSURE = 96, OP_GETCODEOBJ = 97, OP_CURCODE = 98,
        OP_P6CAPTURELEX = 100, OP_P6SINK = 101, OP_P6STORE = 102,
        OP_P6BOX_I = 103, OP_P6BOX_N = 104, OP_P6BOX_S = 105,
        OP_P6DEFINITE = 106, OP_P6BINDATTRINVRES = 107;

    static final int OP_COUNT = 108;

    /* COERCE kinds, in encoder order. */
    static final int C_I2O = 0, C_N2O = 1, C_S2O = 2,
        C_O2I = 3, C_O2N = 4, C_O2S = 5,
        C_I2N = 6, C_N2I = 7, C_I2S = 8;

    @TruffleBoundary
    static Object run(int id, Object[] a, CompilationUnit cu, ThreadContext tc, CallFrame cf) {
        try {
            return run0(id, a, cu, tc, cf);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage() + " (op id " + id + ")", e);
        }
    }

    private static Object run0(int id, Object[] a, CompilationUnit cu, ThreadContext tc, CallFrame cf) {
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
            case OP_ISEQ_S: return b(ns(a[0]).equals(ns(a[1])));
            case OP_ISNE_S: return b(!ns(a[0]).equals(ns(a[1])));
            case OP_ISLT_S: return b(ns(a[0]).compareTo(ns(a[1])) < 0);
            case OP_ISLE_S: return b(ns(a[0]).compareTo(ns(a[1])) <= 0);
            case OP_ISGT_S: return b(ns(a[0]).compareTo(ns(a[1])) > 0);
            case OP_ISGE_S: return b(ns(a[0]).compareTo(ns(a[1])) >= 0);
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
            case OP_NULL_S: return null;   // the null str, which isnull_s sees
            case OP_ISTRUE_S: return Ops.istrue_s(str(a[0]));
            case OP_GETLEXDYN: return Ops.getlexdyn(str(a[0]), tc);
            case OP_BINDLEXDYN: return Ops.bindlexdyn(str(a[0]), smo(a[1]), tc);
            case OP_FORCEOUTERCTX: return Ops.forceouterctx(smo(a[0]), smo(a[1]), tc);
            case OP_CAN: return Ops.can(smo(a[0]), str(a[1]), tc);
            case OP_ISINVOKABLE: return Ops.isinvokable(smo(a[0]), tc);
            case OP_SETELEMS: return Ops.setelems(smo(a[0]), lng(a[1]), tc);
            case OP_EXISTSPOS: return Ops.existspos(smo(a[0]), lng(a[1]), tc);
            case OP_CLONE_ND: return Ops.clone_nd(smo(a[0]), tc);
            case OP_SETCODEOBJ: return Ops.setcodeobj(smo(a[0]), smo(a[1]), tc);
            case OP_GETCURHLLSYM: return Ops.getcurhllsym(str(a[0]), tc);
            case OP_TAKECLOSURE: return Ops.takeclosure(smo(a[0]), tc);
            case OP_GETCODEOBJ: return Ops.getcodeobj(smo(a[0]), tc);
            case OP_CURCODE: return Ops.curcode(tc);
            case OP_P6CAPTURELEX: return rak(Rak.P6CAPTURELEX, a[0], tc);
            case OP_P6SINK: return rak(Rak.P6SINK, a[0], tc);
            case OP_P6STORE: return rak2(Rak.P6STORE, a[0], a[1], tc);
            case OP_P6BOX_I: return rakRaw(Rak.P6BOX_I, lng(a[0]), tc);
            case OP_P6BOX_N: return rakRaw(Rak.P6BOX_N, dbl(a[0]), tc);
            case OP_P6BOX_S: return rakRaw(Rak.P6BOX_S, str(a[0]), tc);
            case OP_P6DEFINITE: return rak(Rak.P6DEFINITE, a[0], tc);
            case OP_P6BINDATTRINVRES: {
                try {
                    return Rak.P6BINDATTRINVRES.invoke(smo(a[0]), smo(a[1]), str(a[2]), smo(a[3]), tc);
                } catch (Throwable t) { throw sneaky(t); }
            }
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
    static Object dispatch(int rtype, String name, CallSiteDescriptor csd, Object[] args,
                           ThreadContext tc, CallFrame cf) {
        org.raku.nqp.dispatch.Dispatch.dispatchUncached(tc, name, csd, args);
        switch (rtype) {
            case NqpWire.T_INT: return Ops.result_i(cf);
            case NqpWire.T_NUM: return Ops.result_n(cf);
            case NqpWire.T_STR: return Ops.result_s(cf);
            default: return Ops.result_o(cf);
        }
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

    /* ----- the typed return-register store the program ends with ----- */

    @TruffleBoundary
    static void storeReturnTyped(int type, Object v, CallFrame cf) {
        switch (type) {
            case NqpWire.T_INT -> Ops.return_i(lng(v), cf);
            case NqpWire.T_NUM -> Ops.return_n(dbl(v), cf);
            case NqpWire.T_STR -> Ops.return_s((String) v, cf);
            default -> Ops.return_o((SixModelObject) v, cf);
        }
    }

    /**
     * The named-argument rejection the invoker's expectation check would
     * have done: a block with no named slurpy accepts exactly its
     * declared named parameters.
     */
    @TruffleBoundary
    static void checkNoExtraNamed(CallFrame cf, Object csdO, String[] allowed) {
        CallSiteDescriptor csd = (CallSiteDescriptor) csdO;
        String[] names = csd.names;
        if (names == null) return;
        outer:
        for (String n : names) {
            for (String a : allowed) if (a.equals(n)) continue outer;
            throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(
                cf.tc, "Unexpected named argument '" + n + "' passed");
        }
    }

    /* ----- the rakudo runtime, reached by reflection: nqp-truffle cannot
     * link against rakudo's jar at build time, but at run time RakOps is
     * on the boot classpath like the rest of the runtime. Loaded on
     * first use; an nqp-only process never touches it. ----- */

    private static final class Rak {
        static final java.lang.invoke.MethodHandle P6CAPTURELEX;
        static final java.lang.invoke.MethodHandle P6SINK;
        static final java.lang.invoke.MethodHandle P6STORE;
        static final java.lang.invoke.MethodHandle P6BOX_I;
        static final java.lang.invoke.MethodHandle P6BOX_N;
        static final java.lang.invoke.MethodHandle P6BOX_S;
        static final java.lang.invoke.MethodHandle P6DEFINITE;
        static final java.lang.invoke.MethodHandle P6BINDATTRINVRES;
        static {
            try {
                Class<?> c = Class.forName("org.raku.rakudo.RakOps");
                var l = java.lang.invoke.MethodHandles.publicLookup();
                Class<?> SMO = SixModelObject.class;
                Class<?> TC = ThreadContext.class;
                java.lang.invoke.MethodType mt;
                mt = java.lang.invoke.MethodType.methodType(SMO, SMO, TC);
                P6CAPTURELEX = l.findStatic(c, "p6capturelex", mt);
                P6SINK = l.findStatic(c, "p6sink", mt);
                P6DEFINITE = l.findStatic(c, "p6definite", mt);
                P6STORE = l.findStatic(c, "p6store",
                    java.lang.invoke.MethodType.methodType(SMO, SMO, SMO, TC));
                P6BOX_I = l.findStatic(c, "p6box_i",
                    java.lang.invoke.MethodType.methodType(SMO, long.class, TC));
                P6BOX_N = l.findStatic(c, "p6box_n",
                    java.lang.invoke.MethodType.methodType(SMO, double.class, TC));
                P6BOX_S = l.findStatic(c, "p6box_s",
                    java.lang.invoke.MethodType.methodType(SMO, String.class, TC));
                P6BINDATTRINVRES = l.findStatic(c, "p6bindattrinvres",
                    java.lang.invoke.MethodType.methodType(SMO, SMO, SMO, String.class, SMO, TC));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static Object rak(java.lang.invoke.MethodHandle h, Object a, ThreadContext tc) {
        try { return h.invoke(smo(a), tc); } catch (Throwable t) { throw sneaky(t); }
    }

    private static Object rak2(java.lang.invoke.MethodHandle h, Object a, Object b2, ThreadContext tc) {
        try { return h.invoke(smo(a), smo(b2), tc); } catch (Throwable t) { throw sneaky(t); }
    }

    private static Object rakRaw(java.lang.invoke.MethodHandle h, Object a, ThreadContext tc) {
        try { return h.invoke(a, tc); } catch (Throwable t) { throw sneaky(t); }
    }

    /** Rethrows anything unwrapped -- control exceptions must pass. */
    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException r) throw r;
        if (t instanceof Error e) throw e;
        throw new RuntimeException(t);
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
        if (v == null) return null;   // the null str travels as absence
        if (v instanceof String s) return s;
        throw new IllegalStateException("nqpp: expected str, got " + kind(v));
    }

    /** Null-safe: the null str compares as the empty string. */
    private static String ns(Object v) {
        String s = str(v);
        return s == null ? "" : s;
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
