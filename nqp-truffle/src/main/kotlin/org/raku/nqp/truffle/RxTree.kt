package org.raku.nqp.truffle

/**
 * The shape of a pattern, before it becomes a program.
 *
 * Plain data, with no Truffle in it: both front ends -- the harness parser
 * and, in the real path, a walk over QAST::Regex -- build this, and
 * [RxProgram] turns it into something the engine runs. Keeping the two apart
 * is what lets the engine be driven by NQP's own grammar trees without either
 * side knowing about the other's syntax.
 *
 * One node per QAST::Regex rxtype the engine covers so far. The ones a
 * grammar additionally needs -- dynquant, conj -- have no case here yet.
 *
 * The nodes are `@JvmRecord`s rather than plain data classes so that their
 * accessors keep the record shape (`parts()`, not `getParts()`): the
 * remaining Java in this module reads them, and the descriptor decoder is
 * easier to check against `QAST::Regex` when both spell things the same way.
 */
object RxTree {

    sealed interface Node

    /** rxtype concat. */
    @JvmRecord
    data class Seq(val parts: List<Node>) : Node

    /** rxtype literal. */
    @JvmRecord
    data class Literal(
        val text: String,
        val negate: Boolean,
        val zeroWidth: Boolean,
        val ignoreCase: Boolean,
    ) : Node

    /**
     * One character's worth of test: rxtype cclass, enumcharlist and
     * charrange are all this.
     *
     * @param zeroWidth when set the test is made but the position does not
     *   move -- `<?[{]>` and friends. Treating one as consuming shifts
     *   everything after it by a character.
     */
    @JvmRecord
    data class One(val pred: RxProgram.CharPred, val zeroWidth: Boolean) : Node {
        constructor(pred: RxProgram.CharPred) : this(pred, false)
    }

    /**
     * rxtype anchor.
     *
     * PASS and FAIL are anchors in name only: they are the two constant
     * assertions, and QAST::Compiler emits them for a rule that has already
     * decided the answer. Kept in the same node because the backend spells
     * them the same way, as an anchor subtype.
     */
    @JvmRecord
    data class Anchor(val kind: Kind) : Node {
        enum class Kind { BOS, EOS, BOL, EOL, LWB, RWB, PASS, FAIL }
    }

    /** rxtype alt with no name: the branches are tried in source order. */
    @JvmRecord
    data class Alt(val branches: List<Node>) : Node

    /**
     * rxtype alt WITH a name: longest-token-match.
     *
     * The branch order is not the source order. It comes from an NFA the
     * grammar carries, run at the current position, which reports the
     * branches that could match ordered by how far each one gets. The engine
     * does not compute that itself -- it asks the cursor, which is the same
     * `!alt` the bytecode path calls, so both agree about which branch wins
     * and the NFA stays in one place.
     *
     * @param ratchet whether the alternation commits to its branch, which a
     *   named alt in a ratcheted rule does.
     */
    @JvmRecord
    data class AltLtm(
        val name: String,
        val branches: List<Node>,
        val ratchet: Boolean,
    ) : Node

    /**
     * rxtype quant. A max below zero is unbounded.
     *
     * @param ratchet whether the quantifier keeps what it took. NQP's `token`
     *   and `rule` mark every quantifier in them this way, so a greedy
     *   quantifier that gives characters back is the exception, not the rule
     *   -- and treating one as the other accepts input the bytecode path
     *   rejects.
     * @param separator what has to sit BETWEEN two repetitions -- the `%` of
     *   `<digit>+ % ','` -- or null for the ordinary case. It is not part of
     *   a repetition: `a+ % ','` matches "a,a" and stops before a trailing
     *   comma, so the separator is what the loop carries rather than what the
     *   body ends with.
     */
    @JvmRecord
    data class Quant(
        val body: Node,
        val min: Int,
        val max: Int,
        val greedy: Boolean,
        val ratchet: Boolean,
        val separator: Node?,
    ) : Node {
        constructor(body: Node, min: Int, max: Int, greedy: Boolean) :
            this(body, min, max, greedy, false, null)

        constructor(body: Node, min: Int, max: Int, greedy: Boolean, ratchet: Boolean) :
            this(body, min, max, greedy, ratchet, null)
    }

    /**
     * rxtype uniprop: one character tested against a Unicode property,
     * `<:Alpha>` and friends.
     *
     * The test itself is the runtime's, reached through the cursor for the
     * same reason a subrule is: the property tables live in NQP, and a
     * grammar may be matching against something the engine has no reading of.
     *
     * @param zeroWidth when set the test is made but the position does not
     *   move.
     */
    @JvmRecord
    data class UniProp(
        val property: String,
        val negate: Boolean,
        val zeroWidth: Boolean,
    ) : Node

    /**
     * rxtype qastnode: a piece of the rule written in NQP rather than in
     * regex -- `{ ... }`, `<?{ ... }>`, `:my $x := ...`.
     *
     * The code is not here and cannot be: it belongs to the rule's frame,
     * where it can read the rule's lexicals. What is here is which piece, and
     * whether its answer decides anything.
     *
     * @param zeroWidth set for `<?{ ... }>`, whose truth the match turns on.
     *   A plain `{ ... }` runs for its effect and its answer is dropped.
     */
    @JvmRecord
    data class QastNode(
        val index: Int,
        val zeroWidth: Boolean,
        val negate: Boolean,
    ) : Node

    /**
     * rxtype subrule. A capture name means the cursor this rule answers is
     * itself the capture -- which is what NQP puts in a match tree.
     *
     * @param args the call's literal arguments, or null when it has none.
     *   Only arguments written in the grammar's source can be carried; see
     *   [RxArgs].
     */
    @JvmRecord
    data class Sub(
        val name: String,
        val zeroWidth: Boolean,
        val negate: Boolean,
        val capture: String?,
        val args: RxArgs?,
    ) : Node {
        constructor(name: String, zeroWidth: Boolean, negate: Boolean, capture: String?) :
            this(name, zeroWidth, negate, capture, null)
    }

    /** rxtype subcapture. */
    @JvmRecord
    data class Capture(val name: String, val body: Node) : Node

    /**
     * rxtype scan: try the body at each position from here on.
     *
     * Every NQP regex is wrapped in one of these, so nothing real can be
     * encoded without it.
     */
    @JvmRecord
    data class Scan(val body: Node) : Node

    /* The character predicates the front ends build One nodes from. Written
     * as constants and small records so that each is a single class the
     * compiler sees as constant once the program is. @JvmField keeps them
     * static fields, which is what the identity comparisons in
     * RxProgram.Builder rely on. */

    @JvmField val ANY: RxProgram.CharPred = RxProgram.CharPred { true }
    @JvmField val DIGIT: RxProgram.CharPred = RxProgram.CharPred { Character.isDigit(it) }
    @JvmField val SPACE: RxProgram.CharPred = RxProgram.CharPred { Character.isWhitespace(it) }
    @JvmField val WORD: RxProgram.CharPred =
        RxProgram.CharPred { Character.isLetterOrDigit(it) || it == '_'.code }
    @JvmField val NEWLINE: RxProgram.CharPred =
        RxProgram.CharPred { it == '\n'.code || it == '\r'.code }
    @JvmField val VSPACE: RxProgram.CharPred = RxProgram.CharPred {
        it == '\n'.code || it == '\r'.code || it == 0x0B || it == 0x0C ||
            it == 0x85 || it == 0x2028 || it == 0x2029
    }
    @JvmField val HSPACE: RxProgram.CharPred = RxProgram.CharPred {
        it == ' '.code || it == '\t'.code ||
            (Character.isSpaceChar(it) && it != '\n'.code && it != '\r'.code)
    }

    /* Named rather than lambdas so RxProgram can recognise them and give
     * them opcodes of their own. */
    @JvmRecord
    data class Negated(val of: RxProgram.CharPred) : RxProgram.CharPred {
        override fun holds(codepoint: Int): Boolean = !of.holds(codepoint)
    }

    @JvmRecord
    data class Range(val lo: Int, val hi: Int) : RxProgram.CharPred {
        override fun holds(codepoint: Int): Boolean = codepoint in lo..hi
    }

    @JvmStatic
    fun not(pred: RxProgram.CharPred): RxProgram.CharPred = Negated(pred)

    /*
     * Walked by codepoint rather than by char: a character class may hold a
     * non-BMP character, and comparing against a truncated char would let
     * half a surrogate pair decide the answer. Java's String.indexOf(int)
     * does the same thing; Kotlin has no overload for it.
     */
    @JvmStatic
    fun anyOf(chars: String): RxProgram.CharPred = RxProgram.CharPred { cp ->
        var at = 0
        var found = false
        while (at < chars.length) {
            val here = chars.codePointAt(at)
            if (here == cp) { found = true; break }
            at += Character.charCount(here)
        }
        found
    }

    @JvmStatic
    fun range(lo: Int, hi: Int): RxProgram.CharPred = Range(lo, hi)

    @JvmStatic
    fun either(a: RxProgram.CharPred, b: RxProgram.CharPred): RxProgram.CharPred =
        RxProgram.CharPred { a.holds(it) || b.holds(it) }
}
