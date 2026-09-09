package org.raku.nqp.truffle;

/**
 * The wire format an encoded block program travels in -- the general-code
 * analog of {@link RxWire}, produced by {@code QAST::TruffleEncoder}
 * (src/vm/jvm/QAST/TruffleEncoder.nqp in nqp) and decoded here. The two
 * files are one contract: a tag added on either side without the other is
 * a decode failure, which is the intended failure mode -- loud, at
 * compile time of the program, never a wrong answer at run time.
 *
 * <pre>
 *   nqpp &lt;nints&gt; &lt;int&gt;... &lt;npool&gt; (&lt;len&gt;:&lt;chars&gt;)...
 * </pre>
 *
 * The int stream is [version, resultType, nlocals, needsFrame,
 * localType*nlocals, tree...]; the
 * tree is a prefix walk. Local types let the builder default-initialize
 * every engine local (0, 0e0, null) the way JVM method locals are -- a
 * QAST local may legitimately be read before its first bind. All 64-bit
 * and floating values ride in the string pool and are parsed at decode,
 * so the int stream stays 32-bit clean.
 *
 * Tree tags. Types are 0=obj 1=int(long) 2=num(double) 3=str.
 *
 * <pre>
 *  1 STMTS n child*            value of the last child; the rest run void
 *  2 NULLC                     the null SMO
 *  3 IVAL p                    long constant, pool decimal
 *  4 NVAL p                    double constant, pool decimal
 *  5 SVAL p                    string constant
 *  6 WVAL pHandle scIdx        Ops.wval
 *  7 LEXGET type pName         nearest declaration, by name
 *  8 LEXBIND type pName child
 *  9 LOCGET type idx
 * 10 LOCBIND type idx child
 * 11 IFV condType negate hasElse cond then [else]  value; no else = null
 * 12 IFS condType negate hasElse cond then [else]  statement, value null
 * 13 LOOP until repeat hasNext condType cond body [next]  value null
 *    hasNext=1 adds a 3rd operand (C-style loop incr / NEXT-expr), run in
 *    void after the body, before the cond re-test.
 * 14 DISPATCH rtype pName nargs (flag [pName])* child*
 *    flag bits: 0-1 arg type (obj/str used), 2 named, 3 flat
 * 15 OPCALL opId nargs child*  the NqpOps table
 * 16 COERCE kind child         kinds in NqpOps
 * 17 PARAMS required accepted n
 *    (kind type scope target [pName] hasDefault [default] ntasks task*)*
 *    kind 0 pos, 1 pos-slurpy, 2 named, 3 named-slurpy; scope 0 lex (target
 *    is a pool name), 1 local (target is a local index); optionality is
 *    hasDefault. Emitted only as the first child of the root STMTS.
 *    A header with n == 0 and accepted == -1 is the custom_args shape:
 *    the block binds its own arguments through the runtime Binder
 *    (P6BINDSIG/P6TRYBINDSIG) rather than declared params, so the reader
 *    skips the arity and extra-named checks on it. No other block shape
 *    produces this header, since accepted == -1 otherwise requires a
 *    positional slurpy, which is itself a param record (n &gt;= 1).
 * 18 GETLEXOUTER pName
 * 19 CODEREF qbid               cu.lookupCodeRef, the BVal road
 * 20 LOOPH until repeat hasNext hasLabel labelLocal condType lastId nrId
 *      outerIdx [labelExpr] cond body [next]
 *    repeat=1 runs the body once ahead of the first cond test, inside the
 *    same last/next/redo regions (a repeat_while/repeat_until with handlers).
 *    hasLabel=1 adds a labelExpr region whose value the builder binds into
 *    block local labelLocal at loop entry; the unwind arms read it as the
 *    `where` for _is_same_label (unlabeled loops pass null -> _rethrow_label).
 *    a while/until loop WITH last/next/redo handlers: the encoder
 *    registered lastId (LAST) and nrId (NEXT|REDO) rows in the block's
 *    handler table; the builder emits the same delimited TryCatch shape
 *    the bytecode path does (curHandler=lastId around cond+loop, nrId
 *    around the body; body catch routes NEXT/REDO, loop catch swallows
 *    LAST; outerIdx restored after). Value null, like LOOP. hasNext=1 adds
 *    a 3rd operand run in void under lastId after the body (and after a NEXT
 *    unwind), before the cond re-test.
 * 21 JNULL                      a Java null: what aconst_null answers
 *    (fresh object locals, valueless else branches) -- NOT the VMNull
 *    singleton NULLC stands for.
 * 22 HANDLE hid outerIdx cares protected
 *    the nqp handle op's regions: the encoder registered an EX_BLOCK
 *    row (hid) whose dispatcher closure it bound to a lexical with
 *    ordinary tags just before this node; the builder nests the
 *    bytecode shape -- an inner TryCatch turning host Throwables into
 *    nqp exceptions (dieInternal), an outer one running unwind_check
 *    (cares skips the labeled redirect), taking u.result, and honoring
 *    cf.exitAfterUnwind with an early typed return.
 * 23 HANDLEPAYLOAD hid outerIdx protected handlerExpr
 *    the throwpayloadlex catcher: an EX_UNWIND_OBJECT row; the catch
 *    arm runs unwind_check then evaluates handlerExpr in this frame
 *    (it reads nqp::lastexpayload, published by invokeHandler).
 * 24 LEXREF type pName spec     a native lexical reference (the
 *    lexicalref scope wanted as an object): the declaring frame is found
 *    the way LEXGET finds it, the reference is allocated over its slot.
 *    spec is the declared width of a sized int/num lexical (Ops.sizedref's
 *    encoding), 0 for full width.
 * 25 CURLEXPAD                  nqp::curlexpad over the program's own frame
 * 26 P6ARGVMARRAY               rakudo's p6argvmarray: the frame's raw
 *    arguments (cf.csd, cf.args) as a BOOTArray
 * </pre>
 */
public final class NqpWire {

    /** Marks a source as an encoded block program. */
    public static final String MAGIC = "nqpp ";

    public static final int VERSION = 2;

    public static final int STMTS = 1;
    public static final int NULLC = 2;
    public static final int IVAL = 3;
    public static final int NVAL = 4;
    public static final int SVAL = 5;
    public static final int WVAL = 6;
    public static final int LEXGET = 7;
    public static final int LEXBIND = 8;
    public static final int LOCGET = 9;
    public static final int LOCBIND = 10;
    public static final int IFV = 11;
    public static final int IFS = 12;
    public static final int LOOP = 13;
    public static final int DISPATCH = 14;
    public static final int OPCALL = 15;
    public static final int COERCE = 16;
    public static final int PARAMS = 17;
    public static final int GETLEXOUTER = 18;
    public static final int CODEREF = 19;
    public static final int LOOPH = 20;
    public static final int JNULL = 21;
    public static final int HANDLE = 22;
    public static final int HANDLEPAYLOAD = 23;
    public static final int LEXREF = 24;
    public static final int CURLEXPAD = 25;
    public static final int P6ARGVMARRAY = 26;
    /** A classlib op from the registry: rtype, class, method, descriptor, tc, nargs, arg types, then the args. */
    public static final int CLASSLIB = 27;
    /** usecapture: the frame's own args, captured for a re-dispatch. */
    public static final int USECAPTURE = 28;
    /** 29 LEXGET_OUTER type name / 30 LEXBIND_OUTER type name child: a read or
     *  bind of an OUTER lexical, resolved from the program's code ref rather
     *  than its frame, so the block needs no frame for it. */
    public static final int LEXGET_OUTER = 29;
    public static final int LEXBIND_OUTER = 30;
    /** savecapture: the frame's own args saved into a capture (like usecapture). */
    public static final int SAVECAPTURE = 31;
    /** 32 FORLOOP condType lastId nrId outerIdx cond pre body: nqp::for's
     *  handled loop. LOOPH's regions (lastId around cond+loop, nrId around
     *  each iteration), but the iteration is [pre; body] with only `body`
     *  inside the redo loop: `redo` re-runs the call with the values `pre`
     *  fetched, `next` falls through to the cond re-test and re-fetches
     *  (Compiler.nqp's redo label sits between the fetch and the call).
     *  Unlabeled; value null, like LOOP. Additive (stage0 programs predate it). */
    public static final int FORLOOP = 32;
    /** 33 P6BINDSIG: rakudo's p6bindsig, the full-binder prologue of a
     *  custom_args block. Binds the frame's own csd/args through the runtime
     *  Binder (which leaves the flattened pair back on the frame); when the
     *  binder auto-threaded a Junction instead, the call's result is already
     *  on the caller and the program returns at once. Value null (a statement,
     *  like LOOP). Additive. */
    public static final int P6BINDSIG = 33;
    /** 34 P6TRYBINDSIG: rakudo's p6trybindsig over the frame's own csd/args;
     *  answers int 1 bound / 0 failed (a bind the invoking dispatch resumes
     *  on, through assertparamcheck). Leaves the flattened pair on the frame.
     *  Additive. */
    public static final int P6TRYBINDSIG = 34;

    public static final int T_OBJ = 0;
    public static final int T_INT = 1;
    public static final int T_NUM = 2;
    public static final int T_STR = 3;
    // A uint parameter: fetched unsigned (posparam_u) so a value at or
    // above 2^63 unboxes without overflow, then bound into an int slot --
    // the unsignedness lives in the ops that read it, not the storage.
    public static final int T_UINT = 4;

    public record Program(int[] code, String[] pool, int nlocals, int needsFrameWord) {
        /** The tree starts after version, result type, nlocals, needsFrame,
         *  types. */
        public int treeStart() { return 4 + nlocals; }
        public int resultType() { return code[1]; }
        /** Bit 0: false for a frame-free block (runs with cf==null); true otherwise. */
        public boolean needsFrame() { return (needsFrameWord & 1) != 0; }
        /** Bit 1: the block runs no op that reads the current language
         *  (spesh's :useshll), so a frame-free entry may cross languages.
         *  A program encoded before the bit existed reads as not free:
         *  the conservative side. */
        public boolean hllFree() { return (needsFrameWord & 2) != 0; }
        public int localType(int i) { return code[4 + i]; }
    }

    public static boolean isProgram(String source) {
        return source.startsWith(MAGIC);
    }

    /** Parses the wire string; throws on anything malformed. */
    public static Program decode(String s) {
        if (!s.startsWith(MAGIC)) throw new IllegalArgumentException("not an nqpp program");
        int at = MAGIC.length();
        int[] cursor = new int[] { at };
        int nints = parseInt(s, cursor);
        int[] code = new int[nints];
        for (int i = 0; i < nints; i++) code[i] = parseInt(s, cursor);
        if (code.length < 4 || code[0] != VERSION)
            throw new IllegalArgumentException("nqpp version mismatch: " + (code.length > 0 ? code[0] : -1));
        int npool = parseInt(s, cursor);
        String[] pool = new String[npool];
        for (int i = 0; i < npool; i++) pool[i] = parsePooled(s, cursor);
        return new Program(code, pool, code[2], code[3]);
    }

    private static int parseInt(String s, int[] cursor) {
        int at = cursor[0];
        while (at < s.length() && s.charAt(at) == ' ') at++;
        boolean neg = at < s.length() && s.charAt(at) == '-';
        if (neg) at++;
        int v = 0;
        int start = at;
        while (at < s.length() && Character.isDigit(s.charAt(at))) {
            v = v * 10 + (s.charAt(at) - '0');
            at++;
        }
        if (at == start) throw new IllegalArgumentException("nqpp: expected int at " + at);
        cursor[0] = at;
        return neg ? -v : v;
    }

    /** {@code <len>:<chars>}, length-prefixed so any character can travel.
     * NFG: len is a grapheme count (the encoder's nqp::chars), so consume that
     * many graphemes; grapheme count is invariant under NFC. */
    private static String parsePooled(String s, int[] cursor) {
        int len = parseInt(s, cursor);
        int at = cursor[0];
        if (at >= s.length() || s.charAt(at) != ':')
            throw new IllegalArgumentException("nqpp: expected ':' at " + at);
        at++;
        int end = graphemeEnd(s, at, len);
        String out = s.substring(at, end);
        cursor[0] = end;
        return out;
    }

    /** The UTF-16 offset {@code count} graphemes past {@code from}. */
    private static int graphemeEnd(String s, int from, int count) {
        if (count <= 0) return from;
        java.text.BreakIterator bi = java.text.BreakIterator.getCharacterInstance();
        bi.setText(s);
        int pos = from;
        for (int n = count; n > 0; n--) {
            int next = bi.following(pos);
            if (next == java.text.BreakIterator.DONE) return s.length();
            pos = next;
        }
        return pos;
    }

    private NqpWire() { }
}
