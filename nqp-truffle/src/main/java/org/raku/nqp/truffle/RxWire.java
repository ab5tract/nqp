package org.raku.nqp.truffle;

/**
 * The wire format a compiled regex descriptor travels in.
 *
 * <p>The backend flattens a QAST::Regex tree to an int array plus a pool of
 * strings (see {@code src/vm/jvm/QAST/RxDescriptor.nqp}), and the class file
 * has to carry that across to the engine. A string constant is the cheapest
 * thing bytecode can hold: one {@code ldc} and no array-building code at
 * every regex, and it doubles as the Truffle {@code Source} the language
 * parses, so a descriptor gets its own call target and its own compiled code
 * exactly as a pattern does.
 *
 * <p>The format is
 *
 * <pre>{@code
 *   rxd <scan> <codeLen> <int>... <poolLen> (<len>:<chars>)... <len>:<passName>
 * }</pre>
 *
 * <p>Pool entries are length-prefixed rather than delimited because a regex
 * may match any character at all, including whichever one a delimiter would
 * have been.
 *
 * <p>The trailing entry is the name {@code !cursor_pass} is called with,
 * which is what makes it reduce and build the match tree; empty means the
 * rule does not reduce.
 */
public final class RxWire {

    /** Marks a source as a descriptor rather than a pattern to parse. */
    public static final String MAGIC = "rxd ";

    private RxWire() { }

    /**
     * @param passName what {@code !cursor_pass} is called with, empty when
     *                 the rule does not reduce.
     * @param scan     whether the rule may retry at later start positions.
     *                 It is a property of the rule rather than an opcode
     *                 because the retry loop has to update {@code $!from} on
     *                 the cursor, which a program cannot do.
     */
    public record Descriptor(int[] code, Object[] pool, String passName, boolean scan) { }

    public static boolean isDescriptor(String source) {
        return source.startsWith(MAGIC);
    }

    public static String encode(int[] code, Object[] pool, String passName, boolean scan) {
        StringBuilder out = new StringBuilder(MAGIC);
        out.append(scan ? 1 : 0).append(' ');
        out.append(code.length);
        for (int c : code) out.append(' ').append(c);
        out.append(' ').append(pool.length);
        for (Object entry : pool) chunk(out, String.valueOf(entry));
        chunk(out, passName);
        return out.toString();
    }

    private static void chunk(StringBuilder out, String entry) {
        out.append(' ').append(entry.length()).append(':').append(entry);
    }

    public static Descriptor decode(String source) {
        if (!isDescriptor(source)) {
            throw new IllegalArgumentException("not a regex descriptor");
        }
        Reader in = new Reader(source, MAGIC.length());

        boolean scan = in.nextInt() != 0;
        int[] code = new int[in.nextInt()];
        for (int i = 0; i < code.length; i++) code[i] = in.nextInt();

        Object[] pool = new Object[in.nextInt()];
        for (int i = 0; i < pool.length; i++) pool[i] = in.nextChunk();

        return new Descriptor(code, pool, in.nextChunk(), scan);
    }

    /** Reads the format's two token shapes, and insists the input is well formed. */
    private static final class Reader {
        private final String src;
        private int at;

        Reader(String src, int at) { this.src = src; this.at = at; }

        int nextInt() {
            int start = at;
            if (at < src.length() && src.charAt(at) == '-') at++;
            while (at < src.length() && isDigit(src.charAt(at))) at++;
            if (at == start) throw bad("expected an integer");
            int value = Integer.parseInt(src, start, at, 10);
            skipSpace();
            return value;
        }

        /** A {@code <len>:<chars>} pool entry. */
        String nextChunk() {
            int start = at;
            while (at < src.length() && isDigit(src.charAt(at))) at++;
            if (at == start) throw bad("expected a pool entry length");
            int len = Integer.parseInt(src, start, at, 10);
            if (at >= src.length() || src.charAt(at) != ':') throw bad("expected ':'");
            at++;
            if (at + len > src.length()) throw bad("pool entry runs past the end");
            String value = src.substring(at, at + len);
            at += len;
            skipSpace();
            return value;
        }

        private void skipSpace() {
            if (at < src.length() && src.charAt(at) == ' ') at++;
        }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        private IllegalArgumentException bad(String what) {
            return new IllegalArgumentException(
                "malformed regex descriptor at " + at + ": " + what);
        }
    }
}
