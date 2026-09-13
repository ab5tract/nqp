package org.raku.nqp.runtime

import java.text.BreakIterator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphemeCursorTest {
    /** Builds a string from codepoints, so the source stays ASCII and exact. */
    private fun u(vararg cps: Int): String = cps.joinToString("") { String(Character.toChars(it)) }

    /** Today's wire readers: a fresh iterator, setText over the whole text, per call. */
    private fun reference(s: String, from: Int, count: Int): Int {
        if (count <= 0) return from
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        var pos = from
        for (n in count downTo 1) {
            val next = bi.following(pos)
            if (next == BreakIterator.DONE) return s.length
            pos = next
        }
        return pos
    }

    private fun agrees(s: String, from: Int, count: Int) =
        assertEquals(reference(s, from, count), GraphemeCursor(s).end(from, count),
            "text=${s.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) }} from=$from count=$count")

    private fun graphemeCount(s: String): Int {
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        var n = 0
        while (bi.next() != BreakIterator.DONE) n++
        return n
    }

    private val acute = 0x301
    private val eAcute = 0xE9                      // precomposed
    private val crlf = u(0x0D, 0x0A)
    private val grinning = u(0x1F600)
    private val technologist = u(0x1F469, 0x200D, 0x1F4BB)   // ZWJ sequence
    private val flagJp = u(0x1F1EF, 0x1F1F5)
    private val flagFr = u(0x1F1EB, 0x1F1F7)

    @Test
    fun asciiFromStartAndMiddle() {
        agrees("hello world", 0, 5)
        agrees("hello world", 6, 3)
        agrees("hello world", 0, 11)
    }

    @Test
    fun combiningMarkOnAsciiBaseIsOneGrapheme() {
        // The fast path must look at the char after the base.
        val s = "a" + u(0x62, acute) + "c"
        agrees(s, 0, 1)
        agrees(s, 0, 2)
        agrees(s, 1, 1)
        agrees(s, 0, 3)
    }

    @Test
    fun precomposedAndDecomposed() {
        agrees(u(eAcute) + "a", 0, 1)
        agrees(u(0x65, acute) + "a", 0, 1)
        agrees(u(0x65, acute, 0x302) + "a", 0, 2)
    }

    @Test
    fun crlfIsOneGrapheme() {
        agrees("a" + crlf + "b", 0, 2)
        agrees("a" + crlf + "b", 1, 1)
        agrees(crlf + crlf, 0, 1)
        agrees("\r" + crlf, 0, 2)
    }

    @Test
    fun surrogatesZwjAndFlags() {
        agrees(grinning + "a", 0, 1)
        agrees(technologist + "x", 0, 1)
        agrees(flagJp + flagFr, 0, 1)
        agrees(flagJp + flagFr, 0, 2)
    }

    @Test
    fun edges() {
        agrees("abc", 1, 0)
        agrees("abc", 0, 10)
        agrees(u(eAcute), 0, 5)
        agrees("", 0, 3)
    }

    /** Pool entries partition a wire text; one cursor serves all of them. */
    @Test
    fun manySegmentsOneCursorInOrderAndOutOfOrder() {
        val pieces = listOf("x", "op", "\$foo", u(eAcute), crlf, grinning, u(0x61, 0x308),
            technologist, flagJp, " ", "7", u(0x300), "Z")
        val rnd = Random(1234)
        val segs = List(500) { List(rnd.nextInt(1, 6)) { pieces[rnd.nextInt(pieces.size)] }.joinToString("") }
        val text = segs.joinToString("|")
        val cursor = GraphemeCursor(text)

        // In order: each segment walked from its start by its own grapheme count.
        var at = 0
        val queries = ArrayList<Pair<Int, Int>>()
        for (seg in segs) {
            val count = graphemeCount(seg)
            queries.add(at to count)
            val want = reference(text, at, count)
            assertEquals(want, cursor.end(at, count), "segment at $at")
            at = want + 1                               // step over the '|'
        }
        // Out of order: the same queries again, shuffled, on the same cursor.
        for ((from, count) in queries.shuffled(rnd))
            assertEquals(reference(text, from, count), cursor.end(from, count), "shuffled at $from")
    }
}
