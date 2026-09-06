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

    /* A cache keyed by the source string. The runtime string ops
     * (chars/substr/index/iscclass/...) re-segment on every call, and the
     * compiler calls them per-position on one large source, which is O(n^2)
     * without this — the Actions.nqp compile stalled on it. A WeakHashMap, NOT
     * a size-bounded LRU: parsing a large source produces thousands of distinct
     * capture strings, and a bounded cache thrashes them against the source
     * (re-segmenting it, back to O(n^2)). Weak keys mean the live source stays
     * cached while dead captures GC out — no thrashing and no leak. Strings are
     * immutable and String.equals short-circuits on identity, so a repeat call
     * on the same source object is an O(1) hit. */
    private val cache: MutableMap<String, Array<String>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, Array<String>>())

    /**
     * NFC-normalize [s] and split it into extended grapheme clusters, each a
     * Java `String` of its codepoints. A cluster's base codepoint — what
     * `nqp::ordat` yields — is `cluster.codePointAt(0)`. Cached per source.
     */
    @JvmStatic
    fun graphemeClusters(s: String): Array<String> {
        if (s.isEmpty()) return EMPTY
        cache[s]?.let { return it }
        val computed = segment(s)
        cache[s] = computed
        return computed
    }

    private val EMPTY_BASES = IntArray(0)

    /* Base codepoints (grapheme[i].codePointAt(0)) of every grapheme, cached per
     * source. The LTM NFA reads the base at a handful of positions but is invoked
     * once per parse position, so rebuilding this array per call is O(source) per
     * call -- O(n^2) over a whole file, which stalled the bootstrap on QAST.nqp.
     * Cached, the segmentation and the array are each built once per source. */
    private val baseCache: MutableMap<String, IntArray> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, IntArray>())

    /** Base codepoint of each grapheme of [s], indexed by grapheme. Cached. */
    @JvmStatic
    fun baseCodepoints(s: String): IntArray {
        if (s.isEmpty()) return EMPTY_BASES
        baseCache[s]?.let { return it }
        val g = graphemeClusters(s)
        val a = IntArray(g.size) { g[it].codePointAt(0) }
        baseCache[s] = a
        return a
    }

    private fun segment(s: String): Array<String> {
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
