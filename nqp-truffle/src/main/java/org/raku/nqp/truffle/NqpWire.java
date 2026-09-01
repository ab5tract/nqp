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
 * The int stream is [version, resultType, nlocals, localType*nlocals,
 * tree...]; the
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
 * 13 LOOP until repeat condType cond body    value null
 * 14 DISPATCH rtype pName nargs (flag [pName])* child*  dispatchUncached
 *    flag bits: 0-1 arg type (obj/str used), 2 named, 3 flat
 * 15 OPCALL opId nargs child*  the NqpOps table
 * 16 COERCE kind child         kinds in NqpOps
 * 17 PARAMS required accepted n
 *    (kind type scope target [pName] hasDefault [default] ntasks task*)*
 *    kind 0 pos, 1 pos-slurpy, 2 named, 3 named-slurpy; scope 0 lex (target
 *    is a pool name), 1 local (target is a local index); optionality is
 *    hasDefault. Emitted only as the first child of the root STMTS.
 * 18 GETLEXOUTER pName
 * 19 CODEREF qbid               cu.lookupCodeRef, the BVal road
 * 20 LOOPH until condType lastId nrId outerIdx cond body
 *    a while/until loop WITH last/next/redo handlers: the encoder
 *    registered lastId (LAST) and nrId (NEXT|REDO) rows in the block's
 *    handler table; the builder emits the same delimited TryCatch shape
 *    the bytecode path does (curHandler=lastId around cond+loop, nrId
 *    around the body; body catch routes NEXT/REDO, loop catch swallows
 *    LAST; outerIdx restored after). Value null, like LOOP.
 * 21 JNULL                      a Java null: what aconst_null answers
 *    (fresh object locals, valueless else branches) -- NOT the VMNull
 *    singleton NULLC stands for.
 * </pre>
 */
public final class NqpWire {

    /** Marks a source as an encoded block program. */
    public static final String MAGIC = "nqpp ";

    public static final int VERSION = 1;

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

    public static final int T_OBJ = 0;
    public static final int T_INT = 1;
    public static final int T_NUM = 2;
    public static final int T_STR = 3;

    public record Program(int[] code, String[] pool, int nlocals) {
        /** The tree starts after version, result type, nlocals, types. */
        public int treeStart() { return 3 + nlocals; }
        public int resultType() { return code[1]; }
        public int localType(int i) { return code[3 + i]; }
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
        if (code.length < 2 || code[0] != VERSION)
            throw new IllegalArgumentException("nqpp version mismatch: " + (code.length > 0 ? code[0] : -1));
        int npool = parseInt(s, cursor);
        String[] pool = new String[npool];
        for (int i = 0; i < npool; i++) pool[i] = parsePooled(s, cursor);
        return new Program(code, pool, code[2]);
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

    /** {@code <len>:<chars>}, length-prefixed so any character can travel. */
    private static String parsePooled(String s, int[] cursor) {
        int len = parseInt(s, cursor);
        int at = cursor[0];
        if (at >= s.length() || s.charAt(at) != ':')
            throw new IllegalArgumentException("nqpp: expected ':' at " + at);
        at++;
        String out = s.substring(at, at + len);
        cursor[0] = at + len;
        return out;
    }

    private NqpWire() { }
}
