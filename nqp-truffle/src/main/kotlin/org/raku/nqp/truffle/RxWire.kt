package org.raku.nqp.truffle

/**
 * The wire format a compiled regex descriptor travels in.
 *
 * The backend flattens a QAST::Regex tree to an int array plus a pool of
 * strings (see `src/vm/jvm/QAST/RxDescriptor.nqp`), and the class file has to
 * carry that across to the engine. A string constant is the cheapest thing
 * bytecode can hold: one `ldc` and no array-building code at every regex, and
 * it doubles as the Truffle `Source` the language parses, so a descriptor
 * gets its own call target and its own compiled code exactly as a pattern
 * does.
 *
 * The format is
 *
 * ```
 *   rxd <scan> <codeLen> <int>... <poolLen> (<len>:<chars>)... <len>:<passName>
 * ```
 *
 * Pool entries are length-prefixed rather than delimited because a regex may
 * match any character at all, including whichever one a delimiter would have
 * been.
 *
 * The trailing entry is the name `!cursor_pass` is called with, which is what
 * makes it reduce and build the match tree; empty means the rule does not
 * reduce.
 */
object RxWire {

    /** Marks a source as a descriptor rather than a pattern to parse. */
    const val MAGIC = "rxd "

    /**
     * @param passName what `!cursor_pass` is called with, empty when the rule
     *   does not reduce.
     * @param scan whether the rule may retry at later start positions. It is
     *   a property of the rule rather than an opcode because the retry loop
     *   has to update `$!from` on the cursor, which a program cannot do.
     * @param resumable whether the rule passes with :backtrack, so that
     *   `!cursor_next` may re-enter it for its next match; the engine keeps
     *   its choice points after a pass to answer that.
     */
    @JvmRecord
    data class Descriptor(
        val code: IntArray,
        val pool: Array<Any?>,
        val passName: String,
        val scan: Boolean,
        val resumable: Boolean,
    )

    @JvmStatic
    fun isDescriptor(source: String): Boolean = source.startsWith(MAGIC)

    @JvmStatic
    @JvmOverloads
    fun encode(
        code: IntArray, pool: Array<Any?>, passName: String, scan: Boolean,
        resumable: Boolean = false,
    ): String {
        val out = StringBuilder(MAGIC)
        out.append((if (scan) 1 else 0) or (if (resumable) 2 else 0)).append(' ')
        out.append(code.size)
        for (c in code) out.append(' ').append(c)
        out.append(' ').append(pool.size)
        for (entry in pool) chunk(out, entry.toString())
        chunk(out, passName)
        return out.toString()
    }

    private fun chunk(out: StringBuilder, entry: String) {
        out.append(' ').append(entry.length).append(':').append(entry)
    }

    @JvmStatic
    fun decode(source: String): Descriptor {
        require(isDescriptor(source)) { "not a regex descriptor" }
        val input = Reader(source, MAGIC.length)

        val flags = input.nextInt()
        val code = IntArray(input.nextInt())
        for (i in code.indices) code[i] = input.nextInt()

        val pool = arrayOfNulls<Any?>(input.nextInt())
        for (i in pool.indices) pool[i] = input.nextChunk()

        return Descriptor(
            code, pool, input.nextChunk(),
            (flags and 1) != 0, (flags and 2) != 0,
        )
    }

    /** Reads the format's two token shapes, and insists the input is well formed. */
    private class Reader(private val src: String, private var at: Int) {

        fun nextInt(): Int {
            val start = at
            if (at < src.length && src[at] == '-') at++
            while (at < src.length && isDigit(src[at])) at++
            if (at == start) throw bad("expected an integer")
            val value = src.substring(start, at).toInt()
            skipSpace()
            return value
        }

        /** A `<len>:<chars>` pool entry. NFG: len is a grapheme count (the
         * encoder's nqp::chars), so consume that many graphemes. Grapheme count
         * is invariant under NFC, so segmenting the raw descriptor matches. */
        fun nextChunk(): String {
            val start = at
            while (at < src.length && isDigit(src[at])) at++
            if (at == start) throw bad("expected a pool entry length")
            val len = src.substring(start, at).toInt()
            if (at >= src.length || src[at] != ':') throw bad("expected ':'")
            at++
            val end = graphemeEnd(src, at, len)
            if (end > src.length) throw bad("pool entry runs past the end")
            val value = src.substring(at, end)
            at = end
            skipSpace()
            return value
        }

        /** The UTF-16 offset [count] graphemes past [from] in [s]. */
        private fun graphemeEnd(s: String, from: Int, count: Int): Int {
            if (count <= 0) return from
            val bi = java.text.BreakIterator.getCharacterInstance()
            bi.setText(s)
            var pos = from
            var n = count
            while (n > 0) {
                val next = bi.following(pos)
                if (next == java.text.BreakIterator.DONE) return s.length
                pos = next
                n--
            }
            return pos
        }

        private fun skipSpace() {
            if (at < src.length && src[at] == ' ') at++
        }

        private fun isDigit(c: Char): Boolean = c in '0'..'9'

        private fun bad(what: String) =
            IllegalArgumentException("malformed regex descriptor at $at: $what")
    }
}
