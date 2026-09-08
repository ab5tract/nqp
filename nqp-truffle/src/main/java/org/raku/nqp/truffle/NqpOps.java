package org.raku.nqp.truffle;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import org.raku.nqp.runtime.CallFrame;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.CodeRef;
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
        OP_ASSIGN_I = 162, OP_ASSIGN_U = 163, OP_ASSIGN_N = 164, OP_ASSIGN_S = 165,
        OP_EXCEPTION = 166, OP_GETEXTYPE = 167, OP_SETEXTYPE = 168, OP_SETPAYLOAD = 169,
        OP_GETMESSAGE = 170, OP_SETMESSAGE = 171, OP_NEWEXCEPTION = 172,
        OP_BACKTRACE = 173, OP_BACKTRACESTRINGS = 174, OP_ISFALSE = 175,
        OP_ISBIG_I = 176, OP_ATPOSREF_I = 177, OP_ATPOSREF_U = 178, OP_ISRWCONT = 179,
        OP_DIE_S = 180, OP_THROW = 181, OP_RETHROW = 182, OP_THROWEXTYPE = 183,
        OP_ISCONCRETE_ND = 184, OP_GETHLLSYM = 185,
        OP_BOX_I2 = 186, OP_BOX_N2 = 187, OP_BOX_S2 = 188, OP_ISNANORINF = 189,
        OP_WHERE = 190, OP_GETLEXCALLER = 191, OP_GETCOMP = 192,
        OP_ATPOSREF_N = 193, OP_ATPOSREF_S = 194, OP_ATPOS_U = 195, OP_BINDPOS_U = 196,
        OP_SLICE = 197, OP_DIMENSIONS = 198, OP_SHA1 = 199, OP_ISNULL_S = 200,
        OP_ISEQ_I_BIG = 201, OP_ISNE_I_BIG = 202, OP_ISLT_I_BIG = 203, OP_ISLE_I_BIG = 204,
        OP_ISGT_I_BIG = 205, OP_ISGE_I_BIG = 206, OP_DECONT_I = 207, OP_DECONT_N = 208,
        OP_DECONT_S = 209, OP_UNSHIFT_I = 210, OP_UNSHIFT_N = 211, OP_UNSHIFT_S = 212,
        OP_TOSTR_I_BIG = 213, OP_ADD_I_BIG = 214, OP_SUB_I_BIG = 215, OP_MUL_I_BIG = 216,
        OP_POP_I = 217, OP_POP_N = 218, OP_POP_S = 219, OP_SHIFT_I = 220, OP_SHIFT_N = 221,
        OP_SHIFT_S = 222, OP_OBJPRIMSPEC = 223, OP_X = 224, OP_CMP_I = 225,
        OP_NUMDIMENSIONS = 226, OP_RAND_N = 227, OP_ORDAT = 228, OP_ISCCLASS = 229,
        OP_FINDCCLASS = 230, OP_FINDNOTCCLASS = 231, OP_CTXLEXPAD = 232, OP_STAT = 233,
        OP_READFH = 234, OP_TIME = 235,
        OP_ATOMICADD_I = 236, OP_ATPOSND_I = 237, OP_ORDFIRST = 238, OP_CMP_N = 239,
        OP_CMP_S = 240, OP_CMP_I_BIG = 241, OP_DIV_I_BIG = 242, OP_REPLACE = 243,
        OP_SETWHO = 244, OP_FINDMETHOD = 245, OP_INF = 246, OP_NEGINF = 247, OP_NAN = 248,
        OP_RXMATCH = 249,
        OP_ATPOSND_O = 250, OP_ATPOSND_N = 251, OP_ATPOSND_S = 252, OP_OBJECTID = 253,
        OP_TRYFINDMETHOD = 254, OP_GETLEXRELCALLER = 255, OP_RINDEXFROM = 256,
        OP_ORDBASEAT = 257, OP_FLOOR_N = 258, OP_CEIL_N = 259,
        OP_RINDEXFROMEND = 260, OP_INDEXIC = 261, OP_INDEXIM = 262, OP_INDEXICIM = 263,
        OP_POW_I_BIG = 264, OP_CTXCALLERSKIPTHUNKS = 265, OP_MULTIDIMREF_I = 266,
        OP_MULTIDIMREF_U = 267, OP_MULTIDIMREF_N = 268, OP_MULTIDIMREF_S = 269,
        OP_ATPOS2D_O = 270, OP_ATPOS2D_I = 271, OP_ATPOS2D_N = 272, OP_ATPOS2D_S = 273,
        OP_ATPOS3D_O = 274, OP_ATPOS3D_I = 275, OP_ATPOS3D_N = 276, OP_ATPOS3D_S = 277,
        OP_BINDPOSND_O = 278, OP_BINDPOS2D_O = 279, OP_BINDPOS3D_O = 280,
        OP_CTX = 281, OP_CTXCALLER = 282, OP_CTXOUTERSKIPTHUNKS = 283, OP_REPRNAME = 284,
        OP_BITAND_I_BIG = 285, OP_NEG_I_BIG = 286, OP_GCD_I_BIG = 287, OP_FROMNUM_I_BIG = 288,
        OP_RAND_I_BIG = 289, OP_UNBOX_U = 290, OP_GETATTR_U = 291, OP_BINDHLLSYM = 292,
        OP_ISEQ_U = 293, OP_ISNE_U = 294, OP_ISLT_U = 295, OP_ISLE_U = 296,
        OP_ISGT_U = 297, OP_ISGE_U = 298, OP_CMP_U = 299, OP_MOD_N = 300,
        OP_RADIX_I = 301,
        OP_ATOMICSTORE_I = 302, OP_CAS = 303, OP_CLOSEFH = 304, OP_FILENOFH = 305,
        OP_DECODE = 306, OP_LOCK = 307, OP_UNLOCK = 308, OP_OPENDIR = 309,
        OP_NEXTFILEDIR = 310, OP_GETLEXRELDYN = 311,
        OP_BINDATTR_U = 312, OP_GETATTRREF_U = 313,
        OP_SQRT_N = 314, OP_LOG_N = 315, OP_EXP_N = 316, OP_SIN_N = 317, OP_ASIN_N = 318,
        OP_COS_N = 319, OP_ACOS_N = 320, OP_TAN_N = 321, OP_ATAN_N = 322, OP_SINH_N = 323,
        OP_COSH_N = 324, OP_TANH_N = 325, OP_ATAN2_N = 326, OP_CLOSEDIR = 327,
        OP_ATOMICLOAD_I = 328, OP_GETLEXREL = 329, OP_CAPTUREPOSARG = 330,
        OP_UNIPROPCODE = 331, OP_STRTOCODES = 332, OP_STAT_TIME = 333,
        OP_MOD_I_BIG = 334, OP_EXPMOD_I_BIG = 335, OP_ABS_I_BIG = 336, OP_BITSHIFTL_I_BIG = 337,
        OP_BITSHIFTR_I_BIG = 338, OP_BITOR_I_BIG = 339, OP_BITXOR_I_BIG = 340, OP_BITNEG_I_BIG = 341,
        OP_LCM_I_BIG = 342, OP_FROMI_I_BIG = 343, OP_ISPRIME_I_BIG = 344, OP_BASE_I_BIG = 345,
        OP_BOOL_I_BIG = 346, OP_TONUM_I_BIG = 347, OP_DIV_IN_BIG = 348, OP_GCD_I = 349,
        OP_LCM_I = 350, OP_COERCE_IS = 351, OP_COERCE_NS = 352, OP_COERCE_US = 353,
        OP_COERCE_IN = 354, OP_FLIP = 355, OP_TCLC = 356, OP_CODES = 357,
        OP_CAS_I = 358, OP_ATOMICINC_I = 359, OP_ATOMICDEC_I = 360, OP_BINDPOSND_I = 361,
        OP_BINDPOSND_N = 362, OP_BINDPOSND_S = 363, OP_BINDPOS2D_I = 364, OP_BINDPOS2D_N = 365,
        OP_BINDPOS2D_S = 366, OP_BINDPOS3D_I = 367, OP_BINDPOS3D_N = 368, OP_BINDPOS3D_S = 369,
        OP_ABS_N = 370,
        OP_FILEREADABLE = 371, OP_FILEWRITABLE = 372, OP_FILEEXECUTABLE = 373, OP_FILEISLINK = 374,
        OP_LSTAT = 375, OP_CHOWN = 376, OP_CHMOD = 377, OP_GETENVHASH = 378,
        // Delimited continuations (gather/take, lazy lists). Like the throw
        // :cont ops, each may suspend: continuationcontrol throws a
        // SaveStackException that the save-stack machinery captures across
        // engine frames, and the resumed value waits in the return register.
        OP_CONTINUATIONRESET = 379, OP_CONTINUATIONCONTROL = 380,
        OP_CONTINUATIONINVOKE = 381;

    static final int OP_COUNT = 382;

    /* COERCE kinds, in encoder order. */
    static final int C_I2O = 0, C_N2O = 1, C_S2O = 2,
        C_O2I = 3, C_O2N = 4, C_O2S = 5,
        C_I2N = 6, C_N2I = 7, C_I2S = 8,
        C_U2O = 9, C_U2N = 10, C_U2S = 11, C_O2U = 12,
        C_S2I = 13, C_N2S = 14, C_S2N = 15;

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
            case OP_GETATTR: return Ops.getattrIn(smo(a[0]), smo(a[1]), str(a[2]), tc, NqpRaw.hll(cu));
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
            case OP_EXCEPTION: return Ops.exception(tc);
            case OP_ISCONCRETE_ND: return Ops.isconcrete_nd(smo(a[0]), tc);
            case OP_BOX_I2: return Ops.box_i(lng(a[0]), smo(a[1]), tc);
            case OP_BOX_N2: return Ops.box_n(dbl(a[0]), smo(a[1]), tc);
            case OP_BOX_S2: return Ops.box_s(str(a[0]), smo(a[1]), tc);
            case OP_ISNANORINF: return Ops.isnanorinf(dbl(a[0]));
            case OP_WHERE: return Ops.where(smo(a[0]), tc);
            case OP_GETLEXCALLER: return Ops.getlexcaller(str(a[0]), tc);
            case OP_GETCOMP: return Ops.getcomp(str(a[0]), tc);
            case OP_ATPOSREF_N: return Ops.atposref_n(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOSREF_S: return Ops.atposref_s(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOS_U: return Ops.atpos_u(smo(a[0]), lng(a[1]), tc);
            case OP_BINDPOS_U: return Ops.bindpos_u(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_SLICE: return Ops.slice(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_DIMENSIONS: return Ops.dimensions(smo(a[0]), tc);
            case OP_SHA1: {
                try { return Ops.sha1(str(a[0])); }
                catch (Exception e) { throw sneaky(e); }
            }
            case OP_ISNULL_S: return Ops.isnull_s(str(a[0]));
            case OP_ISEQ_I_BIG: return Ops.iseq_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISNE_I_BIG: return Ops.isne_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISLT_I_BIG: return Ops.islt_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISLE_I_BIG: return Ops.isle_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISGT_I_BIG: return Ops.isgt_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISGE_I_BIG: return Ops.isge_I(smo(a[0]), smo(a[1]), tc);
            case OP_DECONT_I: return Ops.decont_i(smo(a[0]), tc);
            case OP_DECONT_N: return Ops.decont_n(smo(a[0]), tc);
            case OP_DECONT_S: return Ops.decont_s(smo(a[0]), tc);
            case OP_UNSHIFT_I: return Ops.unshift_i(smo(a[0]), lng(a[1]), tc);
            case OP_UNSHIFT_N: return Ops.unshift_n(smo(a[0]), dbl(a[1]), tc);
            case OP_UNSHIFT_S: return Ops.unshift_s(smo(a[0]), str(a[1]), tc);
            case OP_TOSTR_I_BIG: return Ops.tostr_I(smo(a[0]), tc);
            case OP_ADD_I_BIG: return Ops.add_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_SUB_I_BIG: return Ops.sub_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_MUL_I_BIG: return Ops.mul_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_POP_I: return Ops.pop_i(smo(a[0]), tc);
            case OP_POP_N: return Ops.pop_n(smo(a[0]), tc);
            case OP_POP_S: return Ops.pop_s(smo(a[0]), tc);
            case OP_SHIFT_I: return Ops.shift_i(smo(a[0]), tc);
            case OP_SHIFT_N: return Ops.shift_n(smo(a[0]), tc);
            case OP_SHIFT_S: return Ops.shift_s(smo(a[0]), tc);
            case OP_OBJPRIMSPEC: return Ops.objprimspec(smo(a[0]), tc);
            case OP_X: return Ops.x(str(a[0]), lng(a[1]), tc);
            case OP_CMP_I: return Ops.cmp_i(lng(a[0]), lng(a[1]));
            case OP_NUMDIMENSIONS: return Ops.numdimensions(smo(a[0]), tc);
            case OP_RAND_N: return Ops.rand_n(dbl(a[0]), tc);
            case OP_ORDAT: return Ops.ordat(str(a[0]), lng(a[1]));
            case OP_ISCCLASS: return Ops.iscclass(lng(a[0]), str(a[1]), lng(a[2]));
            case OP_FINDCCLASS: return Ops.findcclass(lng(a[0]), str(a[1]), lng(a[2]), lng(a[3]));
            case OP_FINDNOTCCLASS: return Ops.findnotcclass(lng(a[0]), str(a[1]), lng(a[2]), lng(a[3]));
            case OP_CTXLEXPAD: return Ops.ctxlexpad(smo(a[0]), tc);
            case OP_STAT: return Ops.stat(str(a[0]), lng(a[1]));
            case OP_READFH: return Ops.readfh(smo(a[0]), smo(a[1]), lng(a[2]), tc);
            case OP_TIME: return Ops.time();
            case OP_ATOMICADD_I: return Ops.atomicadd_i(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOSND_I: return Ops.atposnd_i(smo(a[0]), smo(a[1]), tc);
            case OP_ORDFIRST: return Ops.ordfirst(str(a[0]));
            case OP_CMP_N: return Ops.cmp_n(dbl(a[0]), dbl(a[1]));
            case OP_CMP_S: return Ops.cmp_s(str(a[0]), str(a[1]));
            case OP_CMP_I_BIG: return Ops.cmp_I(smo(a[0]), smo(a[1]), tc);
            case OP_DIV_I_BIG: return Ops.div_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_REPLACE: return Ops.replace(str(a[0]), lng(a[1]), lng(a[2]), str(a[3]));
            case OP_SETWHO: return Ops.setwho(smo(a[0]), smo(a[1]), tc);
            case OP_FINDMETHOD: return Ops.findmethod(smo(a[0]), str(a[1]), tc);
            case OP_INF: return Ops.inf();
            case OP_NEGINF: return Ops.neginf();
            case OP_NAN: return Ops.nan();
            case OP_RXMATCH: return org.raku.nqp.runtime.GrammarEngines.INSTANCE.rxmatch(
                str(a[0]), smo(a[1]), smo(a[2]), str(a[3]), lng(a[4]), lng(a[5]),
                lng(a[6]), smo(a[7]), smo(a[8]), tc);
            case OP_ATPOSND_O: return Ops.atposnd_o(smo(a[0]), smo(a[1]), tc);
            case OP_ATPOSND_N: return Ops.atposnd_n(smo(a[0]), smo(a[1]), tc);
            case OP_ATPOSND_S: return Ops.atposnd_s(smo(a[0]), smo(a[1]), tc);
            case OP_OBJECTID: return Ops.where(smo(a[0]), tc);
            case OP_TRYFINDMETHOD: return Ops.findmethodNonFatal(smo(a[0]), str(a[1]), tc);
            case OP_GETLEXRELCALLER: return Ops.getlexrelcaller(smo(a[0]), str(a[1]), tc);
            case OP_RINDEXFROM: return Ops.rindexfrom(str(a[0]), str(a[1]), lng(a[2]));
            case OP_ORDBASEAT: return Ops.ordbaseat(str(a[0]), lng(a[1]));
            case OP_FLOOR_N: return Math.floor(dbl(a[0]));
            case OP_CEIL_N: return Math.ceil(dbl(a[0]));
            case OP_RINDEXFROMEND: return Ops.rindexfromend(str(a[0]), str(a[1]));
            case OP_INDEXIC: return Ops.indexic(str(a[0]), str(a[1]), lng(a[2]));
            case OP_INDEXIM: return Ops.indexim(str(a[0]), str(a[1]), lng(a[2]));
            case OP_INDEXICIM: return Ops.indexicim(str(a[0]), str(a[1]), lng(a[2]));
            case OP_POW_I_BIG: return Ops.pow_I(smo(a[0]), smo(a[1]), smo(a[2]), smo(a[3]), tc);
            case OP_CTXCALLERSKIPTHUNKS: return Ops.ctxcallerskipthunks(smo(a[0]), tc);
            case OP_MULTIDIMREF_I: return Ops.multidimref_i(smo(a[0]), smo(a[1]), tc);
            case OP_MULTIDIMREF_U: return Ops.multidimref_u(smo(a[0]), smo(a[1]), tc);
            case OP_MULTIDIMREF_N: return Ops.multidimref_n(smo(a[0]), smo(a[1]), tc);
            case OP_MULTIDIMREF_S: return Ops.multidimref_s(smo(a[0]), smo(a[1]), tc);
            case OP_ATPOS2D_O: return Ops.atpos2d_o(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_ATPOS2D_I: return Ops.atpos2d_i(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_ATPOS2D_N: return Ops.atpos2d_n(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_ATPOS2D_S: return Ops.atpos2d_s(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_ATPOS3D_O: return Ops.atpos3d_o(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_ATPOS3D_I: return Ops.atpos3d_i(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_ATPOS3D_N: return Ops.atpos3d_n(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_ATPOS3D_S: return Ops.atpos3d_s(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_BINDPOSND_O: return Ops.bindposnd_o(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_BINDPOS2D_O: return Ops.bindpos2d_o(smo(a[0]), lng(a[1]), lng(a[2]), smo(a[3]), tc);
            case OP_BINDPOS3D_O: return Ops.bindpos3d_o(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), smo(a[4]), tc);
            case OP_CTX: return Ops.ctx_of(cf, tc);
            case OP_CTXCALLER: return Ops.ctxcaller(smo(a[0]), tc);
            case OP_CTXOUTERSKIPTHUNKS: return Ops.ctxouterskipthunks(smo(a[0]), tc);
            case OP_REPRNAME: return Ops.reprname(smo(a[0]), tc);
            case OP_BITAND_I_BIG: return Ops.bitand_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_NEG_I_BIG: return Ops.neg_I(smo(a[0]), smo(a[1]), tc);
            case OP_GCD_I_BIG: return Ops.gcd_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_FROMNUM_I_BIG: return Ops.fromnum_I(dbl(a[0]), smo(a[1]), tc);
            case OP_RAND_I_BIG: return Ops.rand_I(smo(a[0]), smo(a[1]), tc);
            case OP_UNBOX_U: return Ops.unbox_u(smo(a[0]), tc);
            case OP_GETATTR_U: return Ops.getattr_u(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_BINDHLLSYM: return Ops.bindhllsym(str(a[0]), str(a[1]), smo(a[2]), tc);
            case OP_ISEQ_U: return Ops.iseq_u(lng(a[0]), lng(a[1]));
            case OP_ISNE_U: return Ops.isne_u(lng(a[0]), lng(a[1]));
            case OP_ISLT_U: return Ops.islt_u(lng(a[0]), lng(a[1]));
            case OP_ISLE_U: return Ops.isle_u(lng(a[0]), lng(a[1]));
            case OP_ISGT_U: return Ops.isgt_u(lng(a[0]), lng(a[1]));
            case OP_ISGE_U: return Ops.isge_u(lng(a[0]), lng(a[1]));
            case OP_CMP_U: return Ops.cmp_u(lng(a[0]), lng(a[1]));
            case OP_MOD_N: return Ops.mod_n(dbl(a[0]), dbl(a[1]));
            case OP_RADIX_I: return Ops.radix_I(lng(a[0]), str(a[1]), lng(a[2]), lng(a[3]), smo(a[4]), tc);
            case OP_ATOMICSTORE_I: return Ops.atomicstore_i(smo(a[0]), lng(a[1]), tc);
            case OP_CAS: return Ops.cas(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_CLOSEFH: return Ops.closefh(smo(a[0]), tc);
            case OP_FILENOFH: return Ops.filenofh(smo(a[0]), tc);
            case OP_DECODE: return Ops.decode(smo(a[0]), str(a[1]), tc);
            case OP_LOCK: return Ops.lock(smo(a[0]), tc);
            case OP_UNLOCK: return Ops.unlock(smo(a[0]), tc);
            case OP_OPENDIR: return Ops.opendir(str(a[0]), tc);
            case OP_NEXTFILEDIR: return Ops.nextfiledir(smo(a[0]), tc);
            case OP_GETLEXRELDYN: return Ops.getlexreldyn(smo(a[0]), str(a[1]), tc);
            case OP_BINDATTR_U: return Ops.bindattr_u(smo(a[0]), smo(a[1]), str(a[2]), lng(a[3]), tc);
            case OP_GETATTRREF_U: return Ops.getattrref_u(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_SQRT_N: return Math.sqrt(dbl(a[0]));
            case OP_LOG_N: return Math.log(dbl(a[0]));
            case OP_EXP_N: return Math.exp(dbl(a[0]));
            case OP_SIN_N: return Math.sin(dbl(a[0]));
            case OP_ASIN_N: return Math.asin(dbl(a[0]));
            case OP_COS_N: return Math.cos(dbl(a[0]));
            case OP_ACOS_N: return Math.acos(dbl(a[0]));
            case OP_TAN_N: return Math.tan(dbl(a[0]));
            case OP_ATAN_N: return Math.atan(dbl(a[0]));
            case OP_SINH_N: return Math.sinh(dbl(a[0]));
            case OP_COSH_N: return Math.cosh(dbl(a[0]));
            case OP_TANH_N: return Math.tanh(dbl(a[0]));
            case OP_ATAN2_N: return Math.atan2(dbl(a[0]), dbl(a[1]));
            case OP_CLOSEDIR: return Ops.closedir(smo(a[0]), tc);
            case OP_ATOMICLOAD_I: return Ops.atomicload_i(smo(a[0]), tc);
            case OP_GETLEXREL: return Ops.getlexrel(smo(a[0]), str(a[1]), tc);
            case OP_CAPTUREPOSARG: return Ops.captureposarg(smo(a[0]), lng(a[1]), tc);
            case OP_UNIPROPCODE: return Ops.unipropcode(str(a[0]), tc);
            case OP_STRTOCODES: return Ops.strtocodes(str(a[0]), lng(a[1]), smo(a[2]), tc);
            case OP_STAT_TIME: return Ops.stat_time(str(a[0]), lng(a[1]));
            case OP_MOD_I_BIG: return Ops.mod_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_EXPMOD_I_BIG: return Ops.expmod_I(smo(a[0]), smo(a[1]), smo(a[2]), smo(a[3]), tc);
            case OP_ABS_I_BIG: return Ops.abs_I(smo(a[0]), smo(a[1]), tc);
            case OP_BITSHIFTL_I_BIG: return Ops.bitshiftl_I(smo(a[0]), lng(a[1]), smo(a[2]), tc);
            case OP_BITSHIFTR_I_BIG: return Ops.bitshiftr_I(smo(a[0]), lng(a[1]), smo(a[2]), tc);
            case OP_BITOR_I_BIG: return Ops.bitor_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_BITXOR_I_BIG: return Ops.bitxor_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_BITNEG_I_BIG: return Ops.bitneg_I(smo(a[0]), smo(a[1]), tc);
            case OP_LCM_I_BIG: return Ops.lcm_I(smo(a[0]), smo(a[1]), smo(a[2]), tc);
            case OP_FROMI_I_BIG: return Ops.fromI_I(smo(a[0]), smo(a[1]), tc);
            case OP_ISPRIME_I_BIG: return Ops.isprime_I(smo(a[0]), tc);
            case OP_BASE_I_BIG: return Ops.base_I(smo(a[0]), lng(a[1]), tc);
            case OP_BOOL_I_BIG: return Ops.bool_I(smo(a[0]), tc);
            case OP_TONUM_I_BIG: return Ops.tonum_I(smo(a[0]), tc);
            case OP_DIV_IN_BIG: return Ops.div_In(smo(a[0]), smo(a[1]), tc);
            case OP_GCD_I: return Ops.gcd_i(lng(a[0]), lng(a[1]));
            case OP_LCM_I: return Ops.lcm_i(lng(a[0]), lng(a[1]));
            case OP_COERCE_IS: return Ops.coerce_is(lng(a[0]), tc);
            case OP_COERCE_NS: return Ops.coerce_ns(dbl(a[0]), tc);
            case OP_COERCE_US: return Ops.coerce_us(lng(a[0]), tc);
            case OP_COERCE_IN: return Ops.coerce_in(lng(a[0]), tc);
            case OP_FLIP: return Ops.flip(str(a[0]));
            case OP_TCLC: return Ops.tclc(str(a[0]));
            case OP_CODES: return Ops.codes(str(a[0]));
            case OP_CAS_I: return Ops.cas_i(smo(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_ATOMICINC_I: return Ops.atomicinc_i(smo(a[0]), tc);
            case OP_ATOMICDEC_I: return Ops.atomicdec_i(smo(a[0]), tc);
            case OP_BINDPOSND_I: return Ops.bindposnd_i(smo(a[0]), smo(a[1]), lng(a[2]), tc);
            case OP_BINDPOSND_N: return Ops.bindposnd_n(smo(a[0]), smo(a[1]), dbl(a[2]), tc);
            case OP_BINDPOSND_S: return Ops.bindposnd_s(smo(a[0]), smo(a[1]), str(a[2]), tc);
            case OP_BINDPOS2D_I: return Ops.bindpos2d_i(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), tc);
            case OP_BINDPOS2D_N: return Ops.bindpos2d_n(smo(a[0]), lng(a[1]), lng(a[2]), dbl(a[3]), tc);
            case OP_BINDPOS2D_S: return Ops.bindpos2d_s(smo(a[0]), lng(a[1]), lng(a[2]), str(a[3]), tc);
            case OP_BINDPOS3D_I: return Ops.bindpos3d_i(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), lng(a[4]), tc);
            case OP_BINDPOS3D_N: return Ops.bindpos3d_n(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), dbl(a[4]), tc);
            case OP_BINDPOS3D_S: return Ops.bindpos3d_s(smo(a[0]), lng(a[1]), lng(a[2]), lng(a[3]), str(a[4]), tc);
            case OP_ABS_N: return Math.abs(dbl(a[0]));
            case OP_FILEREADABLE: return Ops.filereadable(str(a[0]), tc);
            case OP_FILEWRITABLE: return Ops.filewritable(str(a[0]), tc);
            case OP_FILEEXECUTABLE: return Ops.fileexecutable(str(a[0]), tc);
            case OP_FILEISLINK: return Ops.fileislink(str(a[0]), tc);
            case OP_LSTAT: return Ops.lstat(str(a[0]), lng(a[1]));
            case OP_CHOWN: return Ops.chown(str(a[0]), lng(a[1]), lng(a[2]), tc);
            case OP_CHMOD: return Ops.chmod(str(a[0]), lng(a[1]), tc);
            case OP_GETENVHASH: return Ops.getenvhash(tc);
            case OP_GETHLLSYM: return Ops.gethllsym(str(a[0]), str(a[1]), tc);
            case OP_GETEXTYPE: return Ops.getextype(smo(a[0]), tc);
            case OP_SETEXTYPE: return Ops.setextype(smo(a[0]), lng(a[1]), tc);
            case OP_SETPAYLOAD: return Ops.setpayload(smo(a[0]), smo(a[1]), tc);
            case OP_GETMESSAGE: return Ops.getmessage(smo(a[0]), tc);
            case OP_SETMESSAGE: return Ops.setmessage(smo(a[0]), str(a[1]), tc);
            case OP_NEWEXCEPTION: return Ops.newexception(tc);
            case OP_BACKTRACE: return Ops.backtrace(smo(a[0]), tc);
            case OP_BACKTRACESTRINGS: return Ops.backtracestrings(smo(a[0]), tc);
            case OP_ISFALSE: return Ops.isfalse(smo(a[0]), tc);
            case OP_ISBIG_I: return Ops.isbig_I(smo(a[0]), tc);
            case OP_ATPOSREF_I: return Ops.atposref_i(smo(a[0]), lng(a[1]), tc);
            case OP_ATPOSREF_U: return Ops.atposref_u(smo(a[0]), lng(a[1]), tc);
            case OP_ISRWCONT: return Ops.isrwcont(smo(a[0]), tc);
            // The bytecode path's :cont ops: throw, and if a handler
            // resumed, the result waits in the frame's return register --
            // the same shape OP_THROWPAYLOADLEX takes.
            case OP_DIE_S: {
                Ops.die_s_c(str(a[0]), tc);
                return Ops.result_s(cf);
            }
            case OP_THROW: {
                Ops._throw_c(smo(a[0]), tc);
                return Ops.result_o(cf);
            }
            case OP_RETHROW: {
                Ops.rethrow_c(smo(a[0]), tc);
                return Ops.result_o(cf);
            }
            case OP_THROWEXTYPE: {
                Ops.throwcatdyn_c(lng(a[0]), tc);
                return Ops.result_o(cf);
            }
            // Delimited continuations. continuationcontrol throws a
            // SaveStackException captured by the enclosing continuationreset;
            // each records its frame and, on resume, the value is in the
            // return register -- the same shape as the throw :cont ops.
            case OP_CONTINUATIONRESET: {
                // reset/invoke are @Throws(Throwable) in Ops.kt; a capture's
                // SaveStackException travels through sneaky() unchanged and is
                // caught by run()'s handler above (turned into a suspend token).
                try {
                    Ops.continuationreset(smo(a[0]), smo(a[1]), tc);
                } catch (Throwable t) { throw sneaky(t); }
                return Ops.result_o(cf);
            }
            case OP_CONTINUATIONCONTROL: {
                Ops.continuationcontrol(lng(a[0]), smo(a[1]), smo(a[2]), tc);
                return Ops.result_o(cf);
            }
            case OP_CONTINUATIONINVOKE: {
                try {
                    Ops.continuationinvoke(smo(a[0]), smo(a[1]), tc);
                } catch (Throwable t) { throw sneaky(t); }
                return Ops.result_o(cf);
            }
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
                                                 Object where, CompilationUnit cu, ThreadContext tc) {
        UnwindException u = unwindOf(ex);
        if (u.unwindTarget != target || u.unwindCompUnit != cu) throw u;
        // An unlabeled loop redirects any LABELED unwind outward; a labeled
        // loop instead keeps the ones whose payload is its own label. `where`
        // is null for an unlabeled loop, the label object for a labeled one --
        // the two arms of Compiler.nqp's unwind_check (_rethrow_label vs
        // _is_same_label).
        if (where == null) Ops._rethrow_label(u, outer, tc);
        else Ops._is_same_label(u, (SixModelObject) where, outer, tc);
        return u;
    }

    @TruffleBoundary
    static long loopBodyUnwind(Object ex, int target, int outer, Object where,
                               CompilationUnit cu, ThreadContext tc) {
        UnwindException u = checkedUnwind(ex, target, outer, where, cu, tc);
        return (u.category & ExceptionHandling.EX_CAT_REDO) != 0 ? 1L : 0L;
    }

    @TruffleBoundary
    static void loopLastUnwind(Object ex, int target, int outer, Object where,
                               CompilationUnit cu, ThreadContext tc) {
        checkedUnwind(ex, target, outer, where, cu, tc);
    }

    /**
     * The handle/handlepayload catch: unwind_check (skipping the labeled
     * redirect when the dispatcher block cares about LABELED itself),
     * then the handler's result off the unwind.
     */
    @TruffleBoundary
    static Object handleUnwind(Object ex, int target, int outer, boolean cares,
                               CompilationUnit cu, ThreadContext tc) {
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
        if (ex instanceof NqpHostError he) {
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
            // unsigned: a uint slot boxes/widens as 0..2^64-1, never as a negative
            case C_U2O: return Ops.box_u(lng(v), cu.hllConfig.intBoxType, tc);   // a uint boxes to Int; UInt is a subset (uintBoxType may be null during BEGIN)
            case C_U2N: { long u = lng(v); return (double) (u >>> 1) * 2.0 + (double) (u & 1L); }
            case C_U2S: return Long.toUnsignedString(lng(v));
            case C_O2U: return Ops.unbox_u(smo(v), tc);
            case C_S2I: return Ops.coerce_s2i(str(v));
            case C_N2S: return Ops.coerce_n2s(dbl(v));
            case C_S2N: return Ops.coerce_s2n(str(v));
            default:
                throw new IllegalStateException("nqpp: unknown coercion " + kind);
        }
    }

    /* ---- Per-eval-server-run reset of the resolution inline caches --------
     *
     * WvalSite/LexSite/AttrSite each cache a run-owned object: a resolved
     * WVal with its GlobalContext, a StaticCodeInfo, a generated P6Opaque
     * class. The parsed CallTargets that embed them are cached process-wide
     * by source (NqpLanguage.PARSED, kept so warm-up survives a run), so a
     * site last written by one run pins that whole run -- its GlobalContext,
     * and through it the serialization-context graph and the run's byte
     * class loader (~180MB) -- until some later run happens to re-execute the
     * same instruction. Distinct programs, which is the eval server's whole
     * point, touch distinct cold sites, so each program leaks its run: this
     * is the eval-server leak. (Repeating the SAME files hides it -- every
     * site is rewritten each round, so only the last run stays pinned, the
     * "healthy 2 GlobalContexts" the old leak-check saw.)
     *
     * Every site registers here and goes cold with the dispatch caches at
     * the start of each run (DispatchBootstrap.resetAll -> this resettable).
     * The identity checks these caches already make -- site.gc == tc.gc, the
     * sci compare, o.getClass() == site.storage -- mean clearing is pure
     * retention hygiene: a live run never matches a cleared entry, and
     * re-resolving a cold site is exactly what its first execution pays
     * anyway. The dispatch programs (EngineSite reset separately) and the
     * parsed CallTargets are left intact, so no warm-up is thrown away. This
     * restores the invariant CodeEngines already documents: a cached program
     * must resolve its run-owned objects afresh, not hold them across runs. */
    private static final java.util.Queue<WvalSite> WVAL_SITES =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Queue<LexSite> LEX_SITES =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Queue<AttrSite> ATTR_SITES =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    static {
        org.raku.nqp.dispatch.DispatchBootstrap.registerResettable(NqpOps::resetSites);
    }

    /** Returns every resolution inline cache to its built state; see above.
     *  Runs under RUN_LOCK, between runs, with no program executing -- so the
     *  writes race no reader even for the plain-field sites. */
    static void resetSites() {
        for (WvalSite s : WVAL_SITES) { s.value = null; s.gc = null; }
        for (LexSite s : LEX_SITES)   { s.sci = null; s.depth = 0; s.idx = 0; }
        for (AttrSite s : ATTR_SITES) {
            s.getter = null; s.setter = null; s.storage = null; s.resolved = false;
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


    /**
     * One dispatch instruction. The replay of the site's folded programs
     * is PE-visible; only a miss, a flattening shape, and the outcome's
     * invocation cross into the bytecode world.
     */
    static Object dispatch(int rtype, String name, EngineSite es, Object[] args,
                           ThreadContext tc, CallFrame cf, com.oracle.truffle.api.nodes.Node node,
                           CompilationUnit cu) {
        try {
            if (es.csd.hasFlattening)
                NqpDispatch.dispatchFlattening(es.site, name, es.csd, tc, args);
            else if (!NqpDispatch.replay(es.cache, tc, args, node, NqpRaw.hll(cu)))
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
    /** A registry-derived classlib op: the static method the bytecode path
     *  would invokestatic, resolved once into a spread MethodHandle. */
    static final class ClassLibSite {
        final String cls, meth, desc; final boolean tcArg; final int nargs;
        @CompilationFinal java.lang.invoke.MethodHandle mh;
        ClassLibSite(String cls, String meth, String desc, boolean tcArg, int nargs) {
            this.cls = cls; this.meth = meth; this.desc = desc; this.tcArg = tcArg; this.nargs = nargs;
        }
        java.lang.invoke.MethodHandle resolve() {
            java.lang.invoke.MethodHandle h = mh;
            if (h == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                try {
                    ClassLoader ld = NqpOps.class.getClassLoader();
                    // The registry stores the class as a JVM type descriptor
                    // (Lorg/raku/nqp/runtime/Ops;); Class.forName wants the
                    // binary name org.raku.nqp.runtime.Ops.
                    String bin = cls;
                    if (bin.startsWith("L") && bin.endsWith(";")) bin = bin.substring(1, bin.length() - 1);
                    bin = bin.replace('/', '.');
                    Class<?> c = Class.forName(bin, true, ld);
                    java.lang.invoke.MethodType mt = java.lang.invoke.MethodType.fromMethodDescriptorString(desc, ld);
                    h = java.lang.invoke.MethodHandles.lookup().findStatic(c, meth, mt);
                    int n = nargs + (tcArg ? 1 : 0);
                    h = h.asSpreader(Object[].class, n)
                         .asType(java.lang.invoke.MethodType.methodType(Object.class, Object[].class));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("nqpp: classlib op " + cls + "." + meth + desc + ": " + e, e);
                }
                mh = h;
            }
            return h;
        }
    }

    /** JESP_TRACE_CLASSLIB=meth: name, once per distinct current frame, the
     *  code running that classlib op (the caller's frame when frame-free). */
    private static final String TRACE_CLASSLIB = System.getenv("JESP_TRACE_CLASSLIB");
    private static final java.util.Set<String> tracedClasslib = new java.util.HashSet<>();

    @TruffleBoundary
    private static void traceClasslib(ClassLibSite site, ThreadContext tc, CallFrame cf) {
        CallFrame f = cf != null ? cf : tc.curFrame;
        String where = f == null ? "<no frame>" : f.codeRef.name
            + (cf == null ? " (frame-free callee, caller's frame)" : "");
        synchronized (tracedClasslib) {
            if (tracedClasslib.add(site.meth + "@" + where))
                System.err.println("classlib " + site.meth + " in " + where);
        }
    }

    static Object classlib(int rtype, ClassLibSite site, Object[] a, ThreadContext tc, CallFrame cf) {
        if (TRACE_CLASSLIB != null && TRACE_CLASSLIB.equals(site.meth)) traceClasslib(site, tc, cf);
        Object[] full = a;
        if (site.tcArg) {
            full = new Object[a.length + 1];
            System.arraycopy(a, 0, full, 0, a.length);
            full[a.length] = tc;
        }
        try {
            return site.resolve().invokeExact(full);
        } catch (org.raku.nqp.runtime.SaveStackException sse) {
            return new NqpCont.Suspend(sse, NqpWire.T_OBJ);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

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

    /* Natives inline; an object's truth may run its boolification. */
    static boolean truthy(int type, Object v, ThreadContext tc) {
        switch (type) {
            case NqpWire.T_INT: return lng(v) != 0;
            case NqpWire.T_NUM: return dbl(v) != 0.0;
            case NqpWire.T_STR: return Ops.istrue_s(str(v)) != 0;
            default: return truthyObj(v, tc);
        }
    }

    @TruffleBoundary
    private static boolean truthyObj(Object v, ThreadContext tc) {
        return Ops.istrue(smo(v), tc) != 0;
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
        LexSite() { LEX_SITES.add(this); }
    }

    /* An OUTER lexical read or bind from a block that has no frame of its
     * own (jesp: arguments in registers, part two). The walk a framed
     * callee would start at cf.outer starts at the frame the code ref
     * resolves its outer to -- the captured outer, else the outer block's
     * live or prior invocation, exactly as the CallFrame constructor
     * decides -- so a block whose only lexical traffic is with its outers
     * needs no CallFrame. The site's depth counts from that frame. */
    static Object getlexOuter(int type, String name, LexSite site, ThreadContext tc, CodeRef cr) {
        return getlex(type, name, site, tc, outerOf(tc, cr));
    }

    static Object bindlexOuter(int type, String name, Object v, LexSite site, ThreadContext tc, CodeRef cr) {
        return bindlex(type, name, v, site, tc, outerOf(tc, cr));
    }

    private static CallFrame outerOf(ThreadContext tc, CodeRef cr) {
        CallFrame o = cr.outer;
        if (o != null) return o;
        return outerOfSlow(tc, cr);
    }

    @TruffleBoundary
    private static CallFrame outerOfSlow(ThreadContext tc, CodeRef cr) {
        CallFrame o = CallFrame.outerFor(tc, cr);
        if (o == null)
            throw ExceptionHandling.dieInternal(tc, "No outer frame for a frame-free lexical read in "
                + (cr.name == null ? "<anon>" : cr.name));
        return o;
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

    /**
     * A native lexical reference (the lexicalref scope wanted as an
     * object): the declaring frame resolves through the same cached site
     * getlex uses, then the reference is allocated over that frame's slot
     * behind a boundary. The by-name walk is the same one the bytecode
     * path's getlexref_&lt;t&gt;(name) takes when nothing resolved statically,
     * anchored at the program's own frame rather than tc.curFrame.
     */
    static Object getlexref(int type, String name, int spec, LexSite site, ThreadContext tc,
                            CallFrame cf) {
        org.raku.nqp.runtime.StaticCodeInfo sci = site.sci;
        if (sci == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveLex(type, name, site, cf);
            return getlexrefWalk(type, name, spec, tc, cf);
        }
        CallFrame f = outerAt(cf, site.depth);
        if (f != null && f.codeRef.staticInfo == sci) {
            return lexrefAt(f, type, site.idx, spec, cf, tc);
        }
        return getlexrefWalk(type, name, spec, tc, cf);
    }

    @TruffleBoundary
    private static SixModelObject lexrefAt(CallFrame target, int type, int idx, int spec,
                                           CallFrame cur, ThreadContext tc) {
        return Ops.lexref_at(target, type, idx, spec, cur, tc);
    }

    @TruffleBoundary
    private static Object getlexrefWalk(int type, String name, int spec, ThreadContext tc,
                                        CallFrame cf) {
        for (CallFrame f = cf; f != null; f = f.outer) {
            org.raku.nqp.runtime.StaticCodeInfo sci = f.codeRef.staticInfo;
            int i = switch (type) {
                case NqpWire.T_INT -> sci.iTryGetLexicalIdx(name);
                case NqpWire.T_NUM -> sci.nTryGetLexicalIdx(name);
                case NqpWire.T_STR -> sci.sTryGetLexicalIdx(name);
                default -> -1;
            };
            if (i != -1) return Ops.lexref_at(f, type, i, spec, cf, tc);
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found");
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
        WvalSite() { WVAL_SITES.add(this); }
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
        AttrSite() { ATTR_SITES.add(this); }
    }

    static Object getattr(AttrSite site, Object o, Object ch, String name, ThreadContext tc, CompilationUnit cu) {
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
        return getattrSlow(o, ch, name, tc, cu);
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
    /* A native slot read in object context boxes with the BLOCK's language
     * (cu), never the current frame's: a frame-free callee entered across
     * languages has its caller's frame on tc. */
    private static Object getattrSlow(Object o, Object ch, String name, ThreadContext tc, CompilationUnit cu) {
        return Ops.getattrIn(smo(o), smo(ch), name, tc, NqpRaw.hll(cu));
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

    /** The common case -- no flattening, arity in range -- is a few field
     *  reads and writes, kept inlinable; a boundary call per entry was
     *  measurable. Only flattening and the failure go to the slow road.
     *  spesh drops the check outright once the callsite is known. */
    static CallSiteDescriptor checkarity(CallFrame cf, ThreadContext tc, CallSiteDescriptor csd,
                                         Object[] args, int required, int accepted) {
        if (!csd.hasFlattening) {
            int positionals = csd.numPositionals;
            if (positionals >= required && (positionals <= accepted || accepted == -1)) {
                /* No tc.flatArgs store: FlatArgs reads the frame's own array
                 * when the csd comes back unchanged, and a heap store here
                 * would make every argument array escape. A framed block
                 * keeps csd/args on its frame for a later bind error. */
                if (cf != null) { cf.csd = csd; cf.args = args; }
                return csd;
            }
        }
        return checkaritySlow(cf, tc, csd, args, required, accepted);
    }

    @TruffleBoundary
    private static CallSiteDescriptor checkaritySlow(CallFrame cf, ThreadContext tc, CallSiteDescriptor csd,
                                                     Object[] args, int required, int accepted) {
        if (cf != null)
            return Ops.checkarity(cf, csd, args, required, accepted);
        /* Frame-free: Ops.checkarity keeps csd/args on the frame (for a
         * later HLL error against the original arguments); a frame-free
         * block does no such custom binding, so only the flatten and the
         * arity check remain, against tc. */
        CallSiteDescriptor cs = csd;
        if (cs.hasFlattening) cs = cs.explodeFlattening(tc, args);
        else tc.flatArgs = args;
        int positionals = cs.numPositionals;
        if (positionals < required || (positionals > accepted && accepted != -1))
            throw ExceptionHandling.dieInternal(tc, "Too "
                + (positionals < required ? "few" : "many")
                + " positionals passed; expected " + required
                + (accepted != required && accepted != -1 ? " to " + accepted : "")
                + " argument" + (required == 1 && accepted == 1 ? "" : "s")
                + " but got " + positionals);
        return cs;
    }

    static Object[] flatArgs(ThreadContext tc) {
        return tc.flatArgs;
    }

    /* Parameter fetches by the declared type, the bytecode path's
     * posparam_<t>/namedparam_<t> (and their opt_ forms): a native
     * parameter unboxes on the way in and binds into the typed slot. */
    /* The common road -- a required parameter whose argument already has
     * the parameter's kind -- is one flag read and one array read, inline
     * for framed and frame-free blocks alike; optional parameters and every
     * conversion (decont, box, arity error) keep the runtime bodies behind
     * a boundary. The argument value is returned as it sits in the array:
     * a Long/Double/String/SixModelObject, which is what the operation's
     * Object result carries anyway. */
    static Object posparam(CallFrame cf, ThreadContext tc, CompilationUnit cu, Object csd,
                           Object[] args, int idx, boolean opt, int type) {
        CallSiteDescriptor cs = (CallSiteDescriptor) csd;
        if (!opt) {
            byte flag = cs.argFlags[idx];
            switch (type) {
                case NqpWire.T_INT: case NqpWire.T_UINT:
                    if (flag == CallSiteDescriptor.ARG_INT || flag == CallSiteDescriptor.ARG_UINT) return args[idx];
                    break;
                case NqpWire.T_NUM:
                    if (flag == CallSiteDescriptor.ARG_NUM) return args[idx];
                    break;
                case NqpWire.T_STR:
                    if (flag == CallSiteDescriptor.ARG_STR) return args[idx];
                    break;
                default:
                    if (flag == CallSiteDescriptor.ARG_OBJ) return args[idx];
                    break;
            }
        }
        return posparamSlow(cf, tc, cu, cs, args, idx, opt, type);
    }

    @TruffleBoundary
    private static Object posparamSlow(CallFrame cf, ThreadContext tc, CompilationUnit cu, CallSiteDescriptor cs,
                                       Object[] args, int idx, boolean opt, int type) {
        if (cf == null)
            return posparamFree(tc, cu, cs, args, idx, opt, type);
        switch (type) {
            case NqpWire.T_INT:
                return opt ? Ops.posparam_opt_i(cf, cs, args, idx) : Ops.posparam_i(cf, cs, args, idx);
            case NqpWire.T_NUM:
                return opt ? Ops.posparam_opt_n(cf, cs, args, idx) : Ops.posparam_n(cf, cs, args, idx);
            case NqpWire.T_STR:
                return opt ? Ops.posparam_opt_s(cf, cs, args, idx) : Ops.posparam_s(cf, cs, args, idx);
            case NqpWire.T_UINT:
                return opt ? Ops.posparam_opt_u(cf, cs, args, idx) : Ops.posparam_u(cf, cs, args, idx);
            default:
                return opt ? Ops.posparam_opt_o(cf, cs, args, idx) : Ops.posparam_o(cf, cs, args, idx);
        }
    }

    /**
     * The frame-free positional fetch: the same by-declared-type binding
     * Ops.posparam_&lt;t&gt; does, but reading the box types from the
     * CompilationUnit and the optional-existed flag from the ThreadContext
     * directly, so no CallFrame is needed. Only positional params reach
     * here (the encoder keeps named/slurpy blocks framed).
     */
    @TruffleBoundary
    private static Object posparamFree(ThreadContext tc, CompilationUnit cu, CallSiteDescriptor cs,
                                       Object[] args, int idx, boolean opt, int type) {
        if (opt) {
            if (idx >= cs.numPositionals) {
                tc.lastParameterExisted = 0;
                switch (type) {
                    case NqpWire.T_INT: case NqpWire.T_UINT: return 0L;
                    case NqpWire.T_NUM: return 0.0d;
                    default: return null;   // T_STR, T_OBJ
                }
            }
            tc.lastParameterExisted = 1;
        }
        byte flag = cs.argFlags[idx];
        Object raw = args[idx];
        var hll = cu.hllConfig;
        switch (type) {
            case NqpWire.T_INT: case NqpWire.T_UINT:
                switch (flag) {
                    case CallSiteDescriptor.ARG_INT: case CallSiteDescriptor.ARG_UINT:
                        return (long) raw;
                    case CallSiteDescriptor.ARG_OBJ:
                        return Ops.decont((SixModelObject) raw, tc).get_int(tc);
                    default:
                        throw ExceptionHandling.dieInternal(tc, "Expected native int argument");
                }
            case NqpWire.T_NUM:
                switch (flag) {
                    case CallSiteDescriptor.ARG_NUM: return (double) raw;
                    case CallSiteDescriptor.ARG_OBJ:
                        return Ops.decont((SixModelObject) raw, tc).get_num(tc);
                    default:
                        throw ExceptionHandling.dieInternal(tc, "Expected native num argument");
                }
            case NqpWire.T_STR:
                switch (flag) {
                    case CallSiteDescriptor.ARG_STR: return (String) raw;
                    case CallSiteDescriptor.ARG_OBJ:
                        return Ops.decont((SixModelObject) raw, tc).get_str(tc);
                    default:
                        throw ExceptionHandling.dieInternal(tc, "Expected native str argument");
                }
            default:   // T_OBJ
                switch (flag) {
                    case CallSiteDescriptor.ARG_OBJ: return (SixModelObject) raw;
                    case CallSiteDescriptor.ARG_INT: case CallSiteDescriptor.ARG_UINT:
                        return Ops.box_i((long) raw, hll.intBoxType, tc);
                    case CallSiteDescriptor.ARG_NUM:
                        return Ops.box_n((double) raw, hll.numBoxType, tc);
                    case CallSiteDescriptor.ARG_STR:
                        return Ops.box_s((String) raw, hll.strBoxType, tc);
                    default:
                        throw ExceptionHandling.dieInternal(tc, "Error in argument processing");
                }
        }
    }

    @TruffleBoundary
    static Object namedparam(CallFrame cf, Object csd, Object[] args, String name, boolean opt, int type) {
        CallSiteDescriptor cs = (CallSiteDescriptor) csd;
        switch (type) {
            case NqpWire.T_INT:
                return opt ? Ops.namedparam_opt_i(cf, cs, args, name) : Ops.namedparam_i(cf, cs, args, name);
            case NqpWire.T_NUM:
                return opt ? Ops.namedparam_opt_n(cf, cs, args, name) : Ops.namedparam_n(cf, cs, args, name);
            case NqpWire.T_STR:
                return opt ? Ops.namedparam_opt_s(cf, cs, args, name) : Ops.namedparam_s(cf, cs, args, name);
            case NqpWire.T_UINT:
                return opt ? Ops.namedparam_opt_u(cf, cs, args, name) : Ops.namedparam_u(cf, cs, args, name);
            default:
                return opt ? Ops.namedparam_opt_o(cf, cs, args, name) : Ops.namedparam_o(cf, cs, args, name);
        }
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

    /* Plain code: four field writes on the common road (every framed
     * block's return); only the caller-less case goes to the runtime. */
    static void storeReturnTyped(int type, Object v, CallFrame cf) {
        /* Ops.return_* write the caller's registers (cf.caller); done here
         * as field writes for the reason lexO gives. */
        CallFrame caller = cf.caller;
        if (caller == null) { returnSlow(type, v, cf); return; }
        storeReturnInto(type, v, caller);
    }

    /**
     * Writes a typed result into a frame's return registers. The frame-free
     * direct-entry road calls this with the caller frame, doing what a
     * framed callee's own StoreRet-into-cf.caller would have done, from the
     * program's return value instead.
     */
    static void storeReturnInto(int type, Object v, CallFrame target) {
        switch (type) {
            case NqpWire.T_INT -> { target.iRet = lng(v); target.retType = (byte) CallFrame.RET_INT; }
            case NqpWire.T_NUM -> { target.nRet = dbl(v); target.retType = (byte) CallFrame.RET_NUM; }
            case NqpWire.T_STR -> { target.sRet = (String) v; target.retType = (byte) CallFrame.RET_STR; }
            default -> { target.oRet = (SixModelObject) v; target.retType = (byte) CallFrame.RET_OBJ; }
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
    static void checkNoExtraNamed(CallFrame cf, ThreadContext tc, Object csdO, String[] allowed) {
        CallSiteDescriptor csd = (CallSiteDescriptor) csdO;
        String[] names = csd.names;
        if (names == null) return;
        outer:
        for (String n : names) {
            for (String a : allowed) if (a.equals(n)) continue outer;
            throw org.raku.nqp.runtime.ExceptionHandling.dieInternal(
                tc, "Unexpected named argument '" + n + "' passed");
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
        static final java.lang.invoke.MethodHandle P6ARGVMARRAY;
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
                P6ARGVMARRAY = l.findStatic(c, "p6argvmarray",
                    java.lang.invoke.MethodType.methodType(SMO, TC, CallSiteDescriptor.class,
                        Object[].class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /** nqp::curlexpad over the program's own frame, never tc.curFrame. */
    @TruffleBoundary
    static Object curlexpad(ThreadContext tc, CallFrame cf) {
        return Ops.ctx_of(cf, tc);
    }

    /** usecapture: the frame's own csd+args captured for a re-dispatch;
     *  the plain nqp op, so no reflective bridge is needed. */
    @TruffleBoundary
    static Object usecapture(ThreadContext tc, CallFrame cf) {
        return Ops.usecapture(tc, cf.csd, cf.args);
    }

    /** rakudo's p6argvmarray: the frame's raw arguments as a BOOTArray. */
    @TruffleBoundary
    static Object p6argvmarray(ThreadContext tc, CallFrame cf) {
        try { return Rak.P6ARGVMARRAY.invoke(tc, cf.csd, cf.args); }
        catch (Throwable t) { throw sneaky(t); }
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
