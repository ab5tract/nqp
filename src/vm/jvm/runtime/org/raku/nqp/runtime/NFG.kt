package org.raku.nqp.runtime

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Grapheme segmentation for the NFG string ops in [Ops].
 *
 * Deliberately depends on NOTHING but `java.text` — no TruffleString. This class
 * is loaded from `nqp-runtime.jar`, which the runners place on the JVM
 * **bootclasspath**, isolated from the Truffle module (see nqp-truffle's
 * build.gradle.kts: the engine must load apart so it binds its optimizing
 * runtime). Bootclasspath code cannot resolve `com.oracle.truffle.api.strings.*`,
 * so a grapheme helper that string ops call must stay truffle-free. The
 * TruffleString-backed value type ([NFGString]) is a separate concern that can
 * only run engine-side; do not reference it from the hot op path here. See
 * docs/jvm-nfg-representation.md.
 */
object NFG {
    private val EMPTY = emptyArray<String>()

    /**
     * NFC-normalize [s] and split it into extended grapheme clusters, each a
     * Java `String` of its codepoints. A cluster's base codepoint — what
     * `nqp::ordat` yields — is `cluster.codePointAt(0)`.
     */
    @JvmStatic
    fun graphemeClusters(s: String): Array<String> {
        if (s.isEmpty()) return EMPTY
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(nfc)
        val out = ArrayList<String>(nfc.length)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            out.add(nfc.substring(start, end))
            start = end
            end = bi.next()
        }
        return out.toTypedArray()
    }
}
