package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CompilationUnit;
import org.raku.nqp.runtime.ExceptionHandling;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.runtime.UnwindException;
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
        OP_P6DEFINITE = 106, OP_P6BINDATTRINVRES = 107,
        OP_CONTROL = 108, OP_LASTEXPAYLOAD = 109,
        OP_THROWPAYLOADLEX = 110, OP_THROWPAYLOADLEXCALLER = 111,
        OP_ASSERTPARAMCHECK = 112, OP_BINDCOMPLETE = 113,
        OP_P6TYPECHECKRV = 114, OP_P6DECONTRV_RT = 115,
        OP_GETATTR_I = 117, OP_GETATTR_N = 118, OP_GETATTR_S = 119,
        OP_BINDATTR_I = 121, OP_BINDATTR_N = 122, OP_BINDATTR_S = 123,
        OP_ATPOS_I = 124, OP_ATPOS_N = 125, OP_ATPOS_S = 126, OP_BINDPOS_I = 127,
        OP_BINDPOS_N = 128, OP_BINDPOS_S = 129, OP_ATKEY_I = 130, OP_ATKEY_N = 131,
        OP_ATKEY_S = 132, OP_BINDKEY_I = 133, OP_BINDKEY_N = 134, OP_BINDKEY_S = 135,
        OP_ISCONT_I = 136, OP_ISCONT_N = 137, OP_ISCONT_S = 138,
        OP_HLLLIST = 139, OP_HLLHASH = 140,
        OP_BOOTARRAY = 141, OP_BOOTINTARRAY = 142, OP_BOOTNUMARRAY = 143,
        OP_BOOTSTRARRAY = 144, OP_PUSH_I = 145, OP_PUSH_N = 146, OP_PUSH_S = 147,
        OP_HLLBOOL = 148, OP_ISTYPE_ND = 149, OP_WHO = 150, OP_GETPAYLOAD = 151,
        OP_ITERATOR = 152, OP_ITERVAL = 153, OP_ASSIGN = 154, OP_P6BINDASSERT = 155,
        OP_ITERKEY_S = 156, OP_SPLICE = 157, OP_HOW = 158,
        OP_GETATTRREF_I = 159, OP_GETATTRREF_N = 160, OP_GETATTRREF_S = 161,
        OP_ASSIGN_I = 162, OP_ASSIGN_U = 163, OP_ASSIGN_N = 164, OP_ASSIGN_S = 165;

    static final int OP_COUNT = 166;

    /* COERCE kinds, in encoder order. */
    static final int C_I2O = 0, C_N2O = 1, C_S2O = 2,
        C_O2I = 3, C_O2N = 4, C_O2S = 5,
        C_I2N = 6, C_N2I = 7, C_I2S = 8;

    @TruffleBoundary
    static Object run(int id, Object[] a, CompilationUnit cu, ThreadContext tc, CallFrame cf) {
        try {
            return run0(id, a, cu, tc, cf);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            // Any op that reaches user code (a sink, a decont through a
            // Proxy, a handler-running control) is a suspension point;
            // every OPCALL site is wrapped, so answer a token uniformly.
            return new NqpCont.Suspend(sse, NqpWire.T_OBJ);
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
            case OP_GETATTR_I: return Ops.getattr_i(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_GETATTR_N: return Ops.getattr_n(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_GETATTR_S: return Ops.getattr_s(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_BINDATTR_I: return Ops.bindattr_i(smo(a[0]), smo(a[1]), str(a[2]), lng(a[3]), tc);
            case OP_BINDATTR_N: return Ops.bindattr_n(smo(a[0]), smo(a[1]), str(a[2]), dbl(a[3]), tc);
            case OP_BINDATTR_S: return Ops.bindattr_s(smo(a[0]), smo(a[1]), str(a[2]), str(a[3]), tc);
            case OP_ATPOS_I: return Ops.atpos_i(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOS_N: return Ops.atpos_n(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOS_S: return Ops.atpos_s(smo(a[0]), lng(a[1]), tc);
            case OP_BINDPOS_I: return Ops.bindpos_i(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_BINDPOS_N: return Ops.bindpos_n(smo(a[0]), lng(a[1]), dbl(a[2]), tc);
            case OP_BINDPOS_S: return Ops.bindpos_s(smo(a[0]), lng(a[1]), str(a[2]), tc);
            case OP_ATKEY_I: return Ops.atkey_i(smo(a[0]), str(a[1]), tc);
            case OP_ATKEY_N: return Ops.atkey_n(smo(a[0]), str(a[1]), tc);
            case OP_ATKEY_S: return Ops.atkey_s(smo(a[0]), str(a[1]), tc);
            case OP_BINDKEY_I: return Ops.bindkey_i(smo(a[0]), str(a[1]), lng(a[2]), tc);
            case OP_BINDKEY_N: return Ops.bindkey_n(smo(a[0]), str(a[1]), dbl(a[2]), tc);
            case OP_BINDKEY_S: return Ops.bindkey_s(smo(a[0]), str(a[1]), str(a[2]), tc);
            case OP_HLLBOOL: return Ops.hllbool(lng(a[0]), tc);
            case OP_ITERKEY_S: return Ops.iterkey_s(smo(a[0]), tc);
            case OP_SPLICE: return Ops.splice(smo(a[0]), smo(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_HOW: return Ops.how(smo(a[0]), tc);
            case OP_GETATTRREF_I: return Ops.getattrref_i(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_GETATTRREF_N: return Ops.getattrref_n(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_GETATTRREF_S: return Ops.getattrref_s(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_ASSIGN_I: return Ops.assign_i(smo(a[0]), lng(a[1]), tc);
            case OP_ASSIGN_U: return Ops.assign_u(smo(a[0]), lng(a[1]), tc);
            case OP_ASSIGN_N: return Ops.assign_n(smo(a[0]), dbl(a[1]), tc);
            case OP_ASSIGN_S: return Ops.assign_s(smo(a[0]), str(a[1]), tc);
            case OP_ISTYPE_ND: return Ops.istype_nd(smo(a[0]), smo(a[1]), tc);
            case OP_WHO: return Ops.who(smo(a[0]), tc);
            case OP_GETPAYLOAD: return Ops.getpayload(smo(a[0]), tc);
            case OP_ITERATOR: return Ops.iter(smo(a[0]), tc);
            case OP_ITERVAL: return Ops.iterval(smo(a[0]), tc);
            case OP_ASSIGN: return Ops.assign(smo(a[0]), smo(a[1]), tc);
            case OP_P6BINDASSERT: {
                try {
                    return Rak.P6BINDASSERT.invoke(smo(a[0]), smo(a[1]), tc);
                } catch (Throwable t) { throw sneaky(t); }
            }
            // Resolve as Ops.hlllist/hllhash do: off the RUNNING frame's
            // compilation unit, not whichever unit handed the engine this
            // program. Taking it from `cu` can build a container of the
            // wrong HLL's type. (Found while bisecting the hash/list binder
            // bug; not that bug's cause, but wrong on its own terms.)
            case OP_HLLLIST: return Ops.hlllist(tc);
            case OP_BOOTARRAY: return Ops.bootarray(tc);
            case OP_BOOTINTARRAY: return Ops.bootintarray(tc);
            case OP_BOOTNUMARRAY: return Ops.bootnumarray(tc);
            case OP_BOOTSTRARRAY: return Ops.bootstrarray(tc);
            case OP_PUSH_I: return Ops.push_i(smo(a[0]), lng(a[1]), tc);
            case OP_PUSH_N: return Ops.push_n(smo(a[0]), dbl(a[1]), tc);
            case OP_PUSH_S: return Ops.push_s(smo(a[0]), str(a[1]), tc);
            case OP_HLLHASH: return Ops.hllhash(tc);
            case OP_ISCONT_I: return Ops.iscont_i(smo(a[0]));
            case OP_ISCONT_N: return Ops.iscont_n(smo(a[0]));
            case OP_ISCONT_S: return Ops.iscont_s(smo(a[0]));
            case OP_ASSERTPARAMCHECK:
                return Ops.assertparamcheck(lng(a[0]), tc);
            case OP_BINDCOMPLETE:
                return Ops.bindcomplete(tc);
            case OP_P6TYPECHECKRV: {
                try {
                    return Rak.P6TYPECHECKRV.invoke(smo(a[0]), smo(a[1]), smo(a[2]), tc);
                } catch (Throwable t) { throw sneaky(t); }
            }
            case OP_P6DECONTRV_RT: {
                try {
                    return Rak.P6DECONTRV_RT.invoke(smo(a[0]), smo(a[1]), lng(a[2]), tc);
                } catch (Throwable t) { throw sneaky(t); }
            }
            case OP_CONTROL: {
                // The bytecode path's control op: throw the category
                // dynamically; if a block handler resumes, the result is
                // waiting in the frame's return register.
                Ops.throwcatdyn_c(lng(a[0]), tc);
                return Ops.result_o(cf);
            }
            case OP_LASTEXPAYLOAD: return Ops.lastexpayload(tc);
            case OP_THROWPAYLOADLEX: {
                Ops._throwpayloadlex_c(lng(a[0]), smo(a[1]), tc);
                return Ops.result_o(cf);
            }
            case OP_THROWPAYLOADLEXCALLER: {
                Ops._throwpayloadlexcaller_c(lng(a[0]), smo(a[1]), tc);
                return Ops.result_o(cf);
            }
            default:
                throw new IllegalStateException("nqpp: unknown op id " + id);
        }
    }

    /* ----- unwind routing for the program's handler regions ----- */

    /**
     * The exception a catch arm received, as the runtime's unwind -- or a
     * rethrow when it is anything else (TryCatch does not filter by type;
     * the bytecode path's catch is typed to UnwindException, so everything
     * else must keep flying).
     */
    private static UnwindException unwindOf(Object ex) {
        if (ex instanceof NqpUnwind nu) return nu.unwind;
        if (ex instanceof RuntimeException re) throw re;
        throw sneaky((Throwable) ex);
    }

    /**
     * The unwind_check the emitted bytecode makes at a catch: an unwind
     * aimed at a different handler or a different unit keeps flying, and a
     * labeled unwind that landed here only by category overlap is
     * redirected outward the same way {@code Ops._rethrow_label} does.
     */
    private static UnwindException checkedUnwind(Object ex, int target, int outer,
                                                 CompilationUnit cu, ThreadContext tc) {
        UnwindException u = unwindOf(ex);
        if (u.unwindTarget != target || u.unwindCompUnit != cu) throw u;
        Ops._rethrow_label(u, outer, tc);
        return u;
    }

    @TruffleBoundary
    static long loopBodyUnwind(Object ex, int target, int outer,
                               CompilationUnit cu, ThreadContext tc) {
        UnwindException u = checkedUnwind(ex, target, outer, cu, tc);
        return (u.category & ExceptionHandling.EX_CAT_REDO) != 0 ? 1L : 0L;
    }

    @TruffleBoundary
    static void loopLastUnwind(Object ex, int target, int outer,
                               CompilationUnit cu, ThreadContext tc) {
        checkedUnwind(ex, target, outer, cu, tc);
    }

    /**
     * The handle/handlepayload catch: unwind_check (skipping the labeled
     * redirect when the dispatcher block cares about LABELED itself),
     * then the handler's result off the unwind.
     */
    @TruffleBoundary
    static Object handleUnwind(Object ex, int target, int outer, boolean cares,
                               CompilationUnit cu, ThreadContext tc) {
        if (System.getenv("NQP_EH_DEBUG") != null)
            System.err.println("handleUnwind ex=" + ex.getClass().getSimpleName()
                + " target=" + target
                + " uTarget=" + (ex instanceof NqpUnwind nu2 ? nu2.unwind.unwindTarget : -1)
                + " curFrame=" + (tc.curFrame == null ? "?" : tc.curFrame.codeRef.name));
        UnwindException u = unwindOf(ex);
        if (u.unwindTarget != target || u.unwindCompUnit != cu) throw u;
        if (!cares) Ops._rethrow_label(u, outer, tc);
        return u.result;
    }

    /**
     * The handle op's inner catch (the bytecode path's catch (Throwable)
     * around the protected region): control-protocol exceptions keep
     * flying raw; anything else becomes an nqp-level exception. Always
     * throws.
     */
    @TruffleBoundary
    static void hostErrToUnwind(Object ex, ThreadContext tc) {
        if (System.getenv("NQP_EH_DEBUG") != null)
            System.err.println("hostErrToUnwind ex=" + ex.getClass().getSimpleName()
                + " curFrame=" + (tc.curFrame == null ? "?" : tc.curFrame.codeRef.name));
        if (ex instanceof NqpHostError he) {
            if (System.getenv("NQP_EH_DEBUG") != null) {
                StringBuilder sb = new StringBuilder("hostErrToUnwind curFrame chain:");
                org.raku.nqp.runtime.CallFrame f = tc.curFrame;
                for (int i = 0; f != null && i < 6; i++, f = f.caller)
                    sb.append(" ").append(f.codeRef == null ? "?" : f.codeRef.name);
                System.err.println(sb);
            }
            throw ExceptionHandling.dieInternal(tc, he.original);
        }
        // NqpUnwind (headed for the enclosing unwind region) and anything
        // else pass through untouched.
        if (ex instanceof RuntimeException re) throw re;
        throw sneaky((Throwable) ex);
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

    /**
     * One dispatch instruction's constant: its callsite shape plus its
     * inline cache. Programs are shared process-wide (parsed call targets
     * live by source text), so this is exactly what an invokedynamic
     * instruction's DispatchCallSite is on the bytecode side -- and it is
     * registered for the per-eval-server-run reset for the same reason.
     *
     * The cache is not only the optimization tier: an uncached dispatch
     * RECORDS every time, and a recording is destroyed by a continuation
     * captured across it (record() pops it on the way out; the resumed
     * callback then holds captures of a dead recording -- "capture that
     * is not part of this dispatch" under race/hyper loads). Settled
     * sites replay their programs with no recording to destroy, which is
     * why the bytecode world tolerates gather-heavy code.
     */
    static final class EngineSite {
        final CallSiteDescriptor csd;
        final org.raku.nqp.dispatch.DispatchCallSite site;
        /* The folded replay prefix of the site's programs; see NqpDispatch. */
        final NqpDispatch.Cache cache;
        EngineSite(CallSiteDescriptor csd) {
            this.csd = csd;
            this.site = new org.raku.nqp.dispatch.DispatchCallSite(
                java.lang.invoke.MethodType.methodType(void.class));
            org.raku.nqp.dispatch.DispatchBootstrap.registerSite(this.site);
            this.cache = new NqpDispatch.Cache(this.site, csd);
        }
    }

    /** NQP_CODE_UNCACHED: every engine dispatch records afresh (debugging). */
    private static final boolean DISPATCH_UNCACHED = System.getenv("NQP_CODE_UNCACHED") != null;
    /** NQP_CODE_DISPATCH_OLD: the bytecode-side inline cache (MethodHandle
     *  chain behind a boundary) instead of the folded replay -- the A/B. */
    private static final boolean DISPATCH_OLD = System.getenv("NQP_CODE_DISPATCH_OLD") != null;

    /**
     * One dispatch instruction. The replay of the site's folded programs
     * is PE-visible; only a miss, a flattening shape, and the outcome's
     * invocation cross into the bytecode world.
     */
    static Object dispatch(int rtype, String name, EngineSite es, Object[] args,
                           ThreadContext tc, CallFrame cf, com.oracle.truffle.api.nodes.Node node) {
        try {
            if (DISPATCH_UNCACHED)
                NqpDispatch.dispatchUncached(name, es.csd, tc, args);
            else if (DISPATCH_OLD || es.csd.hasFlattening)
                NqpDispatch.dispatchFlattening(es.site, name, es.csd, tc, args);
            else if (!NqpDispatch.replay(es.cache, tc, args, node))
                NqpDispatch.miss(es.cache, name, tc, args);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            /* A continuation is being captured through this frame: hand a
             * suspend token to the program, which yields it; codeRun makes
             * the engine frame a ResumeStatus.Frame from there. */
            return new NqpCont.Suspend(sse, rtype);
        }
        return readResult(rtype, cf);
    }

    /**
     * The VMNull singleton, process-wide and immutable once made, cached
     * here as a compilation constant: Ops.createNull reaches it through a
     * synchronized getter and two `!!` checks, ~700 IR nodes per
     * nqp::null() -- 69% of an identity method's compiled code.
     */
    @CompilationFinal private static SixModelObject VMNULL;

    static SixModelObject nullConstant(ThreadContext tc) {
        SixModelObject n = VMNULL;
        if (n == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            n = Ops.createNull(tc);
            VMNULL = n;
        }
        return n;
    }

    /**
     * The exception an operation lets reach the interpreter loop. The
     * Bytecode DSL treats any exception that is not a Truffle exception as
     * an internal error: resolveThrowable in the generated interpreter
     * calls transferToInterpreterAndInvalidate BEFORE the root's
     * interceptInternalException gets to wrap it -- so every host
     * exception used as ordinary control flow (an UnwindException for a
     * return, next, last or a handled die) threw the compiled root away.
     * Compiler methods that return from inside loops did that hundreds of
     * times: "Deopt taken too many times", the root abandoned, the parse
     * driver among them. Every operation therefore converts here, at the
     * boundary, into the same carriers the interception produces; the
     * interception stays as the fallback for anything missed. The
     * runtime's own control exceptions (a continuation capture, a resume)
     * must keep flying raw, as before.
     */
    static RuntimeException carry(Throwable t) {
        if (t instanceof com.oracle.truffle.api.exception.AbstractTruffleException ate) return ate;
        if (t instanceof UnwindException u) return new NqpUnwind(u);
        if (t instanceof org.raku.nqp.runtime.ControlException) throw sneaky(t);
        if (t instanceof ThreadDeath) throw sneaky(t);
        return new NqpHostError(t);
    }

    /** The typed read of a call's result off the frame's return registers. */
    static Object readResult(int rtype, CallFrame cf) {
        /* The common case -- an object result read as an object -- is a
         * field read; the converting cases (a native boxed on the way out,
         * an object unboxed) go through Ops behind a boundary, whose
         * Kotlin checks would otherwise expand into every call site. */
        byte rt = cf.retType;
        switch (rtype) {
            case NqpWire.T_INT:
                if (rt == CallFrame.RET_INT) return cf.iRet;
                return resultSlow(rtype, cf);
            case NqpWire.T_NUM:
                if (rt == CallFrame.RET_NUM) return cf.nRet;
                return resultSlow(rtype, cf);
            case NqpWire.T_STR:
                if (rt == CallFrame.RET_STR) return cf.sRet;
                return resultSlow(rtype, cf);
            default:
                if (rt == CallFrame.RET_OBJ) return cf.oRet;
                return resultSlow(rtype, cf);
        }
    }

    @TruffleBoundary
    private static Object resultSlow(int rtype, CallFrame cf) {
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

    /*
     * Lexical access anchors at the PROGRAM'S OWN CallFrame, never
     * tc.curFrame: the emitted bytecode reads its lexicals through the cf
     * register, and tc.curFrame can be stale here -- a host exception
     * converted at a method boundary (the postlude's dieInternal) throws
     * its unwind from inside the catch arm, past that method's leave().
     * The bytecode world never notices; an engine anchored at curFrame
     * did, the moment a handler region resumed after such an unwind.
     */
    /**
     * One lexical-by-name instruction's cache: where the walk found the
     * name -- how many outers up, in which static frame, at which slot.
     * A block's static outer chain is fixed, so the answer is a constant
     * of the instruction once seen; the frame at that depth is checked
     * against the static code info, and a mismatch (the same program text
     * reached under another chain) takes the by-name walk. Filled once,
     * under transferToInterpreterAndInvalidate, so compiled code folds
     * depth and slot and the outer walk explodes.
     */
    static final class LexSite {
        @CompilationFinal org.raku.nqp.runtime.StaticCodeInfo sci;
        @CompilationFinal int depth;
        @CompilationFinal int idx;
    }

    static Object getlex(int type, String name, LexSite site, ThreadContext tc, CallFrame cf) {
        org.raku.nqp.runtime.StaticCodeInfo sci = site.sci;
        if (sci == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return getlexResolve(type, name, site, tc, cf);
        }
        CallFrame f = outerAt(cf, site.depth);
        if (f != null && f.codeRef.staticInfo == sci) {
            int i = site.idx;
            switch (type) {
                case NqpWire.T_INT: return f.iLex[i];
                case NqpWire.T_NUM: return f.nLex[i];
                case NqpWire.T_STR: return f.sLex[i];
                default: return lexO(f, i);
            }
        }
        return getlexWalk(type, name, tc, cf);
    }

    /*
     * The lexical slot reads and writes are field accesses written here in
     * Java rather than calls into Ops.kt: a method-expansion trace of a
     * 48-word accessor found 70% of its compiled IR under one
     * CallFrame.oLexOrVivify -- Kotlin's lateinit and `!!` checks, whose
     * failure paths (throwUninitializedPropertyAccessException,
     * checkNotNull, then sanitizeStackTrace and StackTraceElement
     * formatting) partial evaluation inlines in full, ~330 nodes per check.
     * Java reads the backing fields, which carry no such check.
     */

    /** CallFrame.oLexOrVivify, PE-sized: the array read here, the clone
     *  of a lazily vivified static behind a boundary. */
    static SixModelObject lexO(CallFrame f, int i) {
        SixModelObject[] oLex = f.oLex;
        if (oLex == null) return null;
        SixModelObject v = oLex[i];
        if (v != CallFrame.UNVIVIFIED) return v;
        return vivify(f, i);
    }

    @TruffleBoundary
    private static SixModelObject vivify(CallFrame f, int i) {
        return f.oLexOrVivify(i);
    }

    static Object bindlex(int type, String name, Object value, LexSite site, ThreadContext tc,
                          CallFrame cf) {
        org.raku.nqp.runtime.StaticCodeInfo sci = site.sci;
        if (sci == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveLex(type, name, site, cf);
            sci = site.sci;
        }
        if (sci != null) {
            CallFrame f = outerAt(cf, site.depth);
            if (f != null && f.codeRef.staticInfo == sci) {
                int i = site.idx;
                switch (type) {
                    case NqpWire.T_INT: { long v = lng(value); f.iLex[i] = v; return v; }
                    case NqpWire.T_NUM: { double v = dbl(value); f.nLex[i] = v; return v; }
                    case NqpWire.T_STR: { String v = str(value); f.sLex[i] = v; return v; }
                    default: { SixModelObject v = smo(value); f.oLex[i] = v; return v; }
                }
            }
        }
        return bindlexWalk(type, name, value, tc, cf);
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private static CallFrame outerAt(CallFrame cf, int depth) {
        CallFrame f = cf;
        for (int d = 0; d < depth && f != null; d++) f = f.outer;
        return f;
    }

    /** Fills the site from the by-name walk; false when the name is unbound. */
    @TruffleBoundary
    private static boolean resolveLex(int type, String name, LexSite site, CallFrame cf) {
        int depth = 0;
        for (CallFrame f = cf; f != null; f = f.outer, depth++) {
            org.raku.nqp.runtime.StaticCodeInfo sci = f.codeRef.staticInfo;
            int i = switch (type) {
                case NqpWire.T_INT -> sci.iTryGetLexicalIdx(name);
                case NqpWire.T_NUM -> sci.nTryGetLexicalIdx(name);
                case NqpWire.T_STR -> sci.sTryGetLexicalIdx(name);
                default -> sci.oTryGetLexicalIdx(name);
            };
            if (i != -1) {
                site.idx = i;
                site.depth = depth;
                site.sci = sci;
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    private static Object getlexResolve(int type, String name, LexSite site, ThreadContext tc,
                                        CallFrame cf) {
        resolveLex(type, name, site, cf);
        return getlexWalk(type, name, tc, cf);
    }

    @TruffleBoundary
    static Object getlexWalk(int type, String name, ThreadContext tc, CallFrame cf) {
        for (CallFrame f = cf; f != null; f = f.outer) {
            org.raku.nqp.runtime.StaticCodeInfo sci = f.codeRef.staticInfo;
            switch (type) {
                case NqpWire.T_INT: {
                    int i = sci.iTryGetLexicalIdx(name);
                    if (i != -1) return Ops.getlex_i(f, i);
                    break;
                }
                case NqpWire.T_NUM: {
                    int i = sci.nTryGetLexicalIdx(name);
                    if (i != -1) return Ops.getlex_n(f, i);
                    break;
                }
                case NqpWire.T_STR: {
                    int i = sci.sTryGetLexicalIdx(name);
                    if (i != -1) return Ops.getlex_s(f, i);
                    break;
                }
                default: {
                    int i = sci.oTryGetLexicalIdx(name);
                    if (i != -1) return Ops.getlex_o(f, i);
                    break;
                }
            }
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found");
    }

    @TruffleBoundary
    static Object bindlexWalk(int type, String name, Object value, ThreadContext tc, CallFrame cf) {
        for (CallFrame f = cf; f != null; f = f.outer) {
            org.raku.nqp.runtime.StaticCodeInfo sci = f.codeRef.staticInfo;
            switch (type) {
                case NqpWire.T_INT: {
                    int i = sci.iTryGetLexicalIdx(name);
                    if (i != -1) return Ops.bindlex_i(lng(value), f, i);
                    break;
                }
                case NqpWire.T_NUM: {
                    int i = sci.nTryGetLexicalIdx(name);
                    if (i != -1) return Ops.bindlex_n(dbl(value), f, i);
                    break;
                }
                case NqpWire.T_STR: {
                    int i = sci.sTryGetLexicalIdx(name);
                    if (i != -1) return Ops.bindlex_s(str(value), f, i);
                    break;
                }
                default: {
                    int i = sci.oTryGetLexicalIdx(name);
                    if (i != -1) return Ops.bindlex_o(smo(value), f, i);
                    break;
                }
            }
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found");
    }

    @TruffleBoundary
    static Object getlexouter(String name, ThreadContext tc, CallFrame cf) {
        for (CallFrame f = cf.outer; f != null; f = f.outer) {
            int i = f.codeRef.staticInfo.oTryGetLexicalIdx(name);
            if (i != -1) return Ops.getlex_o(f, i);
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found");
    }

    /**
     * One WVal instruction's cache: the resolved object, per GlobalContext
     * (an eval-server run has its own; a stale run's object must never
     * answer). Plain fields: a load and a compare replace the SC-handle
     * hash lookup Ops.wval does per execution. Written value-then-context
     * so a racing reader that sees the context sees the value.
     */
    static final class WvalSite {
        org.raku.nqp.runtime.GlobalContext gc;
        SixModelObject value;
    }

    static Object wval(String handle, int idx, WvalSite site, ThreadContext tc) {
        if (site.gc == tc.gc) return site.value;
        return wvalResolve(handle, idx, site, tc);
    }

    @TruffleBoundary
    private static Object wvalResolve(String handle, int idx, WvalSite site, ThreadContext tc) {
        SixModelObject v = Ops.wval(handle, idx, tc);
        site.value = v;
        site.gc = tc.gc;
        return v;
    }

    /**
     * One getattr/bindattr instruction's cache: the storage class and the
     * slot's field handles for the first object type seen, so the read or
     * write is a field access after PE (the generated accessor is not
     * called: its delegation branch is PE-recursive, see NqpDispatch). A
     * type with no plain-field road (a non-P6Opaque, a natively stored
     * slot, an unknown attribute) marks the site unusable and the runtime
     * op is taken; so does any other object type at the site.
     */
    static final class AttrSite {
        @CompilationFinal Class<?> storage;
        @CompilationFinal java.lang.invoke.MethodHandle getter;
        @CompilationFinal java.lang.invoke.MethodHandle setter;
        @CompilationFinal boolean resolved;
    }

    static Object getattr(AttrSite site, Object o, Object ch, String name, ThreadContext tc) {
        if (!site.resolved) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveAttr(site, o, ch, name, tc);
        }
        java.lang.invoke.MethodHandle getter = site.getter;
        if (getter != null && o != null && o.getClass() == site.storage
                && ((org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance) o).delegate == null) {
            SixModelObject v;
            try {
                v = (SixModelObject) getter.invokeExact((SixModelObject) o);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
            /* A null slot may still auto-vivify; the op decides. */
            if (v != null) return v;
        }
        return getattrSlow(o, ch, name, tc);
    }

    static Object bindattr(AttrSite site, Object o, Object ch, String name, Object value,
                           ThreadContext tc) {
        if (!site.resolved) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveAttr(site, o, ch, name, tc);
        }
        java.lang.invoke.MethodHandle setter = site.setter;
        if (setter != null && o != null && o.getClass() == site.storage
                && ((org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance) o).delegate == null) {
            SixModelObject obj = (SixModelObject) o;
            SixModelObject v = smo(value);
            try {
                setter.invokeExact(obj, v);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
            if (obj.sc != null) scwb(tc, obj);
            return v;
        }
        return bindattrSlow(o, ch, name, value, tc);
    }

    @TruffleBoundary
    private static void resolveAttr(AttrSite site, Object o, Object ch, String name, ThreadContext tc) {
        if (o instanceof SixModelObject obj && obj.st != null
                && obj.st.REPRData instanceof org.raku.nqp.sixmodel.reprs.P6OpaqueREPRData rd
                && rd.jvmClass != null) {
            SixModelObject chd = Ops.decont(smo(ch), tc);
            long hint = obj.st.REPR.hint_for(tc, obj.st, chd, name);
            if (hint != org.raku.nqp.sixmodel.STable.NO_HINT) {
                java.lang.invoke.MethodHandle[] hs = NqpDispatch.fieldHandles(rd.jvmClass, (int) hint);
                if (hs != null) {
                    site.storage = rd.jvmClass;
                    site.getter = hs[0];
                    site.setter = hs[1];
                }
            }
        }
        site.resolved = true;
    }

    @TruffleBoundary
    private static Object getattrSlow(Object o, Object ch, String name, ThreadContext tc) {
        return Ops.getattr(smo(o), smo(ch), name, tc);
    }

    @TruffleBoundary
    private static Object bindattrSlow(Object o, Object ch, String name, Object value, ThreadContext tc) {
        return Ops.bindattr(smo(o), smo(ch), name, smo(value), tc);
    }

    @TruffleBoundary
    private static void scwb(ThreadContext tc, SixModelObject obj) {
        Ops.scwbObject(tc, obj);
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
        /* Ops.return_* write the caller's registers (cf.caller); done here
         * as field writes for the reason lexO gives. */
        CallFrame caller = cf.caller;
        if (caller == null) { returnSlow(type, v, cf); return; }
        switch (type) {
            case NqpWire.T_INT -> { caller.iRet = lng(v); caller.retType = (byte) CallFrame.RET_INT; }
            case NqpWire.T_NUM -> { caller.nRet = dbl(v); caller.retType = (byte) CallFrame.RET_NUM; }
            case NqpWire.T_STR -> { caller.sRet = (String) v; caller.retType = (byte) CallFrame.RET_STR; }
            default -> { caller.oRet = (SixModelObject) v; caller.retType = (byte) CallFrame.RET_OBJ; }
        }
    }

    @TruffleBoundary
    private static void returnSlow(int type, Object v, CallFrame cf) {
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
        static final java.lang.invoke.MethodHandle P6BINDASSERT;
        static final java.lang.invoke.MethodHandle P6TYPECHECKRV;
        static final java.lang.invoke.MethodHandle P6DECONTRV_RT;
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
                P6BINDASSERT = l.findStatic(c, "p6bindassert",
                    java.lang.invoke.MethodType.methodType(SMO, SMO, SMO, TC));
                P6TYPECHECKRV = l.findStatic(c, "p6typecheckrv",
                    java.lang.invoke.MethodType.methodType(SMO, SMO, SMO, SMO, TC));
                P6DECONTRV_RT = l.findStatic(c, "p6decontrv_rt",
                    java.lang.invoke.MethodType.methodType(SMO, SMO, SMO, long.class, TC));
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
