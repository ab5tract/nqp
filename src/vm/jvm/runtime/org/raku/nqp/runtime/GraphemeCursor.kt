package org.raku.nqp.runtime

import java.text.BreakIterator

/**
 * Grapheme offsets in one text, for the wire readers that frame pooled strings
 * by grapheme count (NqpWire, RxWire: the encoder writes `nqp::chars`).
 *
 * The readers used to create a BreakIterator and call setText over the whole
 * text for every pooled string. setText precomputes boundaries for the entire
 * text, so a program with P pooled strings cost O(P x length) -- the top of
 * every cold-start profile (2026-09-13, 70% of `nqp -e` samples), the same
 * shape as the 2026-09-06 outer-framing fix.
 *
 * One cursor per text: the iterator is created, and given the text, at most
 * once. Most pooled strings are ASCII identifiers and op names, so a char
 * below U+0300 that is not CR, followed by the end of the text or by another
 * char below U+0300, is taken as one grapheme without the iterator at all.
 * Below U+0300 nothing combines with a following char except CR LF, and nothing
 * there extends a preceding one; anything else goes through the iterator.
 */
class GraphemeCursor(private val text: String) {
    private var iterator: BreakIterator? = null

    private fun bi(): BreakIterator =
        iterator ?: BreakIterator.getCharacterInstance().also {
            it.setText(text)
            iterator = it
        }

    /** The UTF-16 offset [count] graphemes past [from]; the text's length if it runs out. */
    fun end(from: Int, count: Int): Int {
        val len = text.length
        var pos = from
        var n = count
        while (n > 0) {
            if (pos >= len) return len
            val c = text[pos].code
            if (c < FIRST_COMBINING && c != CR && (pos + 1 == len || text[pos + 1].code < FIRST_COMBINING)) {
                pos++
            } else {
                val next = bi().following(pos)
                if (next == BreakIterator.DONE) return len
                pos = next
            }
            n--
        }
        return pos
    }

    private companion object {
        /** U+0300 COMBINING GRAVE ACCENT, the first combining mark. */
        const val FIRST_COMBINING = 0x300
        const val CR = 0x0D
    }
}
