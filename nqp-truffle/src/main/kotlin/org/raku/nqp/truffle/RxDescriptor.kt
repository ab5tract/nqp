package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/**
 * Decodes a QAST::Regex tree that the JVM backend has flattened for us.
 *
 * The backend already holds the grammar as QAST::Regex by the time it emits
 * code, so nothing needs re-parsing: it walks that tree once, at compile
 * time, into the flat form below, and the engine turns it back into an
 * [RxTree] and then a program. A flat int array plus a pool of strings is
 * chosen because it is what NQP can build and hand across cheaply -- no Java
 * objects constructed from the NQP side, and no NQP types leaking into the
 * engine.
 *
 * Each node is a tag followed by its operands, children inline in the order
 * they are named. The encoding mirrors the rxtypes one for one, so a reader
 * can check it against `QAST::Regex` without a translation table.
 */
class RxDescriptor private constructor(
    private val code: IntArray,
    private val pool: Array<Any?>,
) {
    private var at = 0

    private fun node(): RxTree.Node {
        val tag = code[at++]
        return when (tag) {
            SEQ -> RxTree.Seq(children())
            ALT -> RxTree.Alt(children())

            LITERAL -> {
                val text = pool[code[at++]] as String
                val flags = code[at++]
                RxTree.Literal(
                    text,
                    (flags and RxProgram.F_NEGATE) != 0,
                    (flags and RxProgram.F_ZEROWIDTH) != 0,
                    (flags and RxProgram.F_IGNORECASE) != 0,
                )
            }

            CCLASS -> charClass(predicate(code[at++]), code[at++])

            ENUM -> charClass(RxTree.anyOf(pool[code[at++]] as String), code[at++])

            RANGE -> {
                /* Read in order: Kotlin evaluates arguments left to right,
                 * but naming them keeps the two `at++` from depending on it. */
                val lo = code[at++]
                val hi = code[at++]
                charClass(RxTree.range(lo, hi), code[at++])
            }

            ALT_LTM -> {
                val name = pool[code[at++]] as String
                val ratchet = code[at++] != 0
                val count = code[at++]
                val branches = ArrayList<RxTree.Node>(count)
                repeat(count) { branches.add(node()) }
                RxTree.AltLtm(name, branches, ratchet)
            }

            ANCHOR -> RxTree.Anchor(RxTree.Anchor.Kind.entries[code[at++]])

            QUANT -> {
                val min = code[at++]
                val max = code[at++]
                val greedy = code[at++] != 0
                val ratchet = code[at++] != 0
                val separated = code[at++] != 0
                /* The body is read before the separator, in that order,
                 * because that is the order they are written in. */
                val body = node()
                RxTree.Quant(body, min, max, greedy, ratchet, if (separated) node() else null)
            }

            SUB -> {
                val name = pool[code[at++]] as String
                val flags = code[at++]
                val capture = code[at++]
                RxTree.Sub(
                    name,
                    (flags and RxProgram.F_ZEROWIDTH) != 0,
                    (flags and RxProgram.F_NEGATE) != 0,
                    if (capture == 0) null else pool[capture - 1] as String,
                    args(),
                )
            }

            DYNQUANT -> {
                val index = code[at++]
                val greedy = code[at++] != 0
                val ratchet = code[at++] != 0
                val separated = code[at++] != 0
                val body = node()
                RxTree.DynQuant(body, index, greedy, ratchet, if (separated) node() else null)
            }

            CONJ -> {
                val count = code[at++]
                val zeroWidth = code[at++] != 0
                val branches = ArrayList<RxTree.Node>(count)
                repeat(count) { branches.add(node()) }
                RxTree.Conj(branches, zeroWidth)
            }

            CAPTURE -> {
                val name = pool[code[at++]] as String
                RxTree.Capture(name, node())
            }

            SCAN -> RxTree.Scan(node())

            UNIPROP -> {
                val property = pool[code[at++]] as String
                val flags = code[at++]
                RxTree.UniProp(
                    property,
                    (flags and RxProgram.F_NEGATE) != 0,
                    (flags and RxProgram.F_ZEROWIDTH) != 0,
                )
            }

            QASTNODE -> {
                val index = code[at++]
                val flags = code[at++]
                RxTree.QastNode(
                    index,
                    (flags and RxProgram.F_ZEROWIDTH) != 0,
                    (flags and RxProgram.F_NEGATE) != 0,
                )
            }

            SUBCB -> {
                val index = code[at++]
                val flags = code[at++]
                val capture = code[at++]
                RxTree.SubCallback(
                    index,
                    (flags and RxProgram.F_ZEROWIDTH) != 0,
                    (flags and RxProgram.F_NEGATE) != 0,
                    if (capture == 0) null else pool[capture - 1] as String,
                )
            }

            else -> throw IllegalArgumentException("unknown descriptor tag $tag")
        }
    }

    /** A subrule call's literal arguments, or null when it has none. */
    private fun args(): RxArgs? {
        val count = code[at++]
        if (count == 0) return null
        val kinds = StringBuilder(count)
        val values = arrayOfNulls<Any?>(count)
        for (i in 0 until count) {
            val kind = code[at++]
            val text = pool[code[at++]] as String
            when (kind) {
                ARG_STR -> {
                    kinds.append(RxArgs.STR)
                    values[i] = text
                }
                ARG_INT -> {
                    kinds.append(RxArgs.INT)
                    values[i] = text.toLong()
                }
                else -> throw IllegalArgumentException("unknown subrule argument kind $kind")
            }
        }
        return RxArgs(kinds.toString(), values)
    }

    private fun children(): List<RxTree.Node> {
        val count = code[at++]
        val out = ArrayList<RxTree.Node>(count)
        repeat(count) { out.add(node()) }
        return out
    }

    companion object {
        /* Tags. Children follow inline, so a node's size depends on its kind. */
        const val SEQ = 1        // count, children...
        const val ALT = 2        // count, children...
        const val LITERAL = 3    // pool(text), flags
        const val CCLASS = 4     // kind, negate
        const val ENUM = 5       // pool(chars), negate
        const val RANGE = 6      // lo, hi, negate
        const val ANCHOR = 7     // kind

        /* min, max, greedy, ratchet, separated, child, and the separator
         * after it when `separated` is set. */
        const val QUANT = 8

        /* pool(name), flags, pool(capture)+1 or 0, argc, then argc pairs of
         * (kind, pool(text)) -- see ARG_STR/ARG_INT. */
        const val SUB = 9

        const val CAPTURE = 10   // pool(name), child
        const val SCAN = 11      // child
        const val ALT_LTM = 12   // pool(name), ratchet, count, children...
        const val UNIPROP = 13   // pool(property), flags
        const val QASTNODE = 14  // callback index, flags

        /* callback index, flags, pool(capture)+1 or 0 -- a subrule call the
         * direct SUB form cannot carry (a lexical or computed callee,
         * computed arguments), run as a callback piece whose value is the
         * subcursor. */
        const val SUBCB = 15

        /* callback index, greedy, ratchet, separated, body, separator when
         * separated -- a quantifier whose bounds the rule evaluates at
         * match time (`x ** {$n}`): the callback answers a two-int array
         * of (min, max), -1 meaning unbounded. */
        const val DYNQUANT = 16

        /* count, zerowidth, children... -- rxtype conj/conjseq: every
         * branch must match the same span; the first decides it. */
        const val CONJ = 17

        /* Subrule argument kinds. The pool is strings, so an int argument
         * travels as its decimal text and is read back here, once. */
        const val ARG_STR = 0
        const val ARG_INT = 1

        /* CCLASS kinds, in the order the engine's predicates are listed. */
        const val CC_ANY = 0
        const val CC_DIGIT = 1
        const val CC_SPACE = 2
        const val CC_WORD = 3
        const val CC_NEWLINE = 4
        const val CC_HSPACE = 5
        const val CC_VSPACE = 6

        /** Turns a flattened QAST::Regex tree into what the engine compiles. */
        @JvmStatic
        @TruffleBoundary
        fun decode(code: IntArray, pool: Array<Any?>): RxTree.Node {
            val d = RxDescriptor(code, pool)
            val tree = d.node()
            require(d.at == code.size) {
                "descriptor has ${code.size - d.at} ints left over"
            }
            return tree
        }

        /** Compiles a flattened tree straight to a program. */
        @JvmStatic
        @TruffleBoundary
        fun compile(code: IntArray, pool: Array<Any?>): RxProgram =
            RxProgram.compile(decode(code, pool))

        /**
         * A character class from its predicate and flags.
         *
         * The flags operand used to be a bare negate. It carries zerowidth
         * too, because a class that only looks (`<?[{]>`) and one that
         * consumes are otherwise indistinguishable here, and reading the
         * first as the second runs every rule after it one character too far.
         */
        private fun charClass(pred: RxProgram.CharPred, flags: Int): RxTree.Node {
            val negate = (flags and RxProgram.F_NEGATE) != 0
            val zeroWidth = (flags and RxProgram.F_ZEROWIDTH) != 0
            return RxTree.One(if (negate) RxTree.not(pred) else pred, zeroWidth)
        }

        private fun predicate(kind: Int): RxProgram.CharPred = when (kind) {
            CC_ANY -> RxTree.ANY
            CC_DIGIT -> RxTree.DIGIT
            CC_SPACE -> RxTree.SPACE
            CC_WORD -> RxTree.WORD
            CC_NEWLINE -> RxTree.NEWLINE
            CC_HSPACE -> RxTree.HSPACE
            CC_VSPACE -> RxTree.VSPACE
            else -> throw IllegalArgumentException("unknown cclass kind $kind")
        }
    }
}
