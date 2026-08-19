package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/**
 * Builds a node tree from a pattern written in a small subset of regex
 * syntax.
 *
 * This exists so the engine can be exercised and measured on its own. The
 * real path does not parse text at all: NQP has already parsed the grammar
 * and holds a QAST::Regex tree, and the backend walks that tree into the same
 * nodes. Keeping the node set free of any syntax lets the two front ends
 * share one engine.
 *
 * Everything here runs once, when a pattern is first seen, so it is behind a
 * boundary rather than something partial evaluation should look into.
 */
class RxParser private constructor(private val src: String) {

    private var at = 0

    /** alternation := concat ('|' concat)* */
    private fun alternation(): RxTree.Node {
        val branches = ArrayList<RxTree.Node>()
        branches.add(concat())
        while (peek() == '|') {
            at++
            branches.add(concat())
        }
        return if (branches.size == 1) branches[0] else RxTree.Alt(branches)
    }

    /** concat := quantified* */
    private fun concat(): RxTree.Node {
        val parts = ArrayList<RxTree.Node>()
        while (at < src.length && peek() != '|' && peek() != ')') {
            parts.add(quantified())
        }
        return if (parts.size == 1) parts[0] else RxTree.Seq(parts)
    }

    /** quantified := atom ('*' | '+' | '?')? */
    private fun quantified(): RxTree.Node {
        val atom = atom()
        val c = peek()
        if (c == '*' || c == '+' || c == '?') {
            at++
            val min = if (c == '+') 1 else 0
            val max = if (c == '?') 1 else -1
            return RxTree.Quant(atom, min, max, true)
        }
        return atom
    }

    private fun atom(): RxTree.Node {
        val c = src[at]
        return when (c) {
            '(' -> {
                at++
                val inner = alternation()
                expect(')')
                inner
            }
            '[' -> {
                at++
                charClass()
            }
            '.' -> {
                at++
                RxTree.One(RxTree.ANY)
            }
            '^' -> {
                at++
                RxTree.Anchor(RxTree.Anchor.Kind.BOS)
            }
            '$' -> {
                at++
                RxTree.Anchor(RxTree.Anchor.Kind.EOS)
            }
            '\\' -> {
                at++
                escape(src[at++])
            }
            else -> {
                at++
                RxTree.Literal(c.toString(), false, false, false)
            }
        }
    }

    private fun escape(c: Char): RxTree.Node = when (c) {
        'd' -> RxTree.One(RxTree.DIGIT)
        'D' -> RxTree.One(RxTree.not(RxTree.DIGIT))
        's' -> RxTree.One(RxTree.SPACE)
        'S' -> RxTree.One(RxTree.not(RxTree.SPACE))
        'w' -> RxTree.One(RxTree.WORD)
        'W' -> RxTree.One(RxTree.not(RxTree.WORD))
        'n' -> RxTree.Literal("\n", false, false, false)
        't' -> RxTree.Literal("\t", false, false, false)
        else -> RxTree.Literal(c.toString(), false, false, false)
    }

    /** A bracketed set, with ranges and a leading '^' for negation. */
    private fun charClass(): RxTree.Node {
        val negate = peek() == '^'
        if (negate) at++
        val singles = StringBuilder()
        var pred: RxProgram.CharPred? = null
        while (at < src.length && peek() != ']') {
            val lo = src[at++]
            if (peek() == '-' && at + 1 < src.length && src[at + 1] != ']') {
                at++
                val hi = src[at++]
                val range = RxTree.range(lo.code, hi.code)
                pred = if (pred == null) range else RxTree.either(pred, range)
            } else {
                singles.append(lo)
            }
        }
        expect(']')
        if (singles.isNotEmpty()) {
            val set = RxTree.anyOf(singles.toString())
            pred = if (pred == null) set else RxTree.either(pred, set)
        }
        val whole = pred ?: RxProgram.CharPred { false }
        /* Negation applies to the set as a whole, which is why the parts are
         * combined first and only then inverted. */
        return RxTree.One(if (negate) RxTree.not(whole) else whole)
    }

    /* NUL past the end: a sentinel that is none of the delimiters this
     * parser tests for, and -- unlike a space -- not something a pattern
     * can legitimately contain. */
    private fun peek(): Char = if (at < src.length) src[at] else '\u0000'

    private fun expect(c: Char) {
        if (at >= src.length || src[at] != c) {
            throw IllegalArgumentException("expected '$c' at $at")
        }
        at++
    }

    companion object {
        private const val SCAN_PREFIX = "(?scan)"

        @JvmStatic
        @TruffleBoundary
        fun parse(pattern: String): RxTree.Node {
            /* A real regex arrives already wrapped in a scan; the harness
             * says so with a prefix rather than growing a syntax for it. */
            if (pattern.startsWith(SCAN_PREFIX)) {
                return RxTree.Scan(parse(pattern.substring(SCAN_PREFIX.length)))
            }
            val p = RxParser(pattern)
            val tree = p.alternation()
            require(p.at == pattern.length) {
                "unconsumed input at ${p.at} of '$pattern'"
            }
            return tree
        }
    }
}
