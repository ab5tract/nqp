package org.raku.nqp.truffle

import com.oracle.truffle.api.strings.TruffleString
import org.raku.nqp.runtime.NFGSynthetics
import java.text.BreakIterator
import java.text.Normalizer

/**
 * An NFG (Normal Form Grapheme) string value for the JVM backend.
 *
 * A Raku string is a sequence of extended grapheme clusters, each addressed by a
 * single grapheme index. `.chars` counts graphemes, `substr`/`index`/regex
 * positions are grapheme-indexed, and a cluster spanning more than one codepoint
 * is a **synthetic** grapheme (a negative id in [NFGSynthetics]).
 *
 * Two internal forms (exactly one field is non-null):
 *
 *  - **flat** — a [TruffleString] where every grapheme is exactly one codepoint
 *    and there are no synthetics (ASCII, Latin-1, BMP-without-combining, and
 *    astral singletons — the overwhelming majority of strings). Grapheme index
 *    == codepoint index, so `.chars`/`graphemeAt`/`substr` map straight onto
 *    TruffleString's codepoint-indexed operations. This is MoarVM's blob fast
 *    path; no `int[]` is materialized.
 *
 *  - **general** — `int[] graphemes`, where `graphemes[i] >= 0` is the codepoint
 *    that *is* the grapheme and `graphemes[i] < 0` is a synthetic id. Built only
 *    when some cluster fuses more than one codepoint. By construction a general
 *    string always holds at least one synthetic (single-codepoint slices are
 *    canonicalized back to flat), which keeps equality trivial.
 *
 * Immutable. See docs/jvm-nfg-representation.md for the full decision.
 *
 * PHASE 1 scope: the value type, the synthetic table, the flat fast path, and a
 * Java-`String` interop edge — nothing in the runtime is repointed to it yet.
 * Segmentation currently uses `java.text.BreakIterator` (JDK UAX#29); the
 * hand-rolled UAX#29 table for full GraphemeBreakTest conformance is phase-2+.
 */
class NFGString private constructor(
    @JvmField val flat: TruffleString?,
    @JvmField val graphemes: IntArray?,
) {
    companion object {
        @JvmField val UTF16: TruffleString.Encoding = TruffleString.Encoding.UTF_16
        private val CP_LEN = TruffleString.CodePointLengthNode.getUncached()
        private val CP_AT = TruffleString.CodePointAtIndexNode.getUncached()
        private val SUBSTR = TruffleString.SubstringNode.getUncached()
        private val TO_JS = TruffleString.ToJavaStringNode.getUncached()
        private val TS_EQ = TruffleString.EqualNode.getUncached()

        @JvmField val EMPTY = NFGString(TruffleString.fromJavaStringUncached("", UTF16), null)

        private fun flatOf(nfc: String): NFGString =
            if (nfc.isEmpty()) EMPTY else NFGString(TruffleString.fromJavaStringUncached(nfc, UTF16), null)

        /**
         * Build from a Java `String`: decode (already UTF-16), normalize to NFC,
         * segment into grapheme clusters, then pick the flat or general form.
         */
        @JvmStatic
        fun fromJavaString(s: String): NFGString {
            if (s.isEmpty()) return EMPTY
            val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)

            val bi = BreakIterator.getCharacterInstance()
            bi.setText(nfc)

            // First pass: is any cluster more than one codepoint? If not, flat.
            var anyMulti = false
            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                if (nfc.codePointCount(start, end) > 1) { anyMulti = true; break }
                start = end
                end = bi.next()
            }
            if (!anyMulti) return flatOf(nfc)

            // Second pass: build the grapheme array, fusing multi-codepoint clusters.
            val out = ArrayList<Int>(nfc.length)
            bi.setText(nfc)
            start = bi.first()
            end = bi.next()
            while (end != BreakIterator.DONE) {
                val cpc = nfc.codePointCount(start, end)
                if (cpc == 1) {
                    out.add(nfc.codePointAt(start))
                } else {
                    val seq = IntArray(cpc)
                    var i = 0
                    var idx = start
                    while (idx < end) {
                        val cp = nfc.codePointAt(idx)
                        seq[i++] = cp
                        idx += Character.charCount(cp)
                    }
                    out.add(NFGSynthetics.intern(seq))
                }
                start = end
                end = bi.next()
            }
            return fromGraphemes(out)
        }

        /**
         * NFC-normalize [s] and split it into extended grapheme clusters, each
         * returned as a Java `String` of its codepoints. This is the shared
         * segmentation the grapheme-indexed ops in [Ops] operate on (so the
         * grapheme model has one definition). A cluster's base codepoint —
         * what `nqp::ordat` yields — is `cluster.codePointAt(0)`.
         */
        @JvmStatic
        fun graphemeClusters(s: String): Array<String> {
            if (s.isEmpty()) return EMPTY_CLUSTERS
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

        private val EMPTY_CLUSTERS = emptyArray<String>()

        /* Intern one NFGString per source string. The runtime engine reads a
         * source's graphemes many times over (per grammar-rule invocation, and
         * again for chars/atoms), so caching the VALUE -- not just its atom
         * array -- lets every one of those reads share the same instance and
         * its per-instance chars/atoms caches. A WeakHashMap (not a bounded LRU,
         * which would thrash the live source against parse captures) keeps the
         * live source cached while dead strings GC out; same-source lookups are
         * O(1) by identity. */
        private val internCache: MutableMap<String, NFGString> =
            java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, NFGString>())

        /** The interned (cached) NFGString for [s]. */
        @JvmStatic
        fun of(s: String): NFGString {
            if (s.isEmpty()) return EMPTY
            internCache[s]?.let { return it }
            val v = fromJavaString(s)
            internCache[s] = v
            return v
        }

        /** Grapheme atoms of [s] (codepoint >=0 or synthetic id <0), for the
         *  engine -- the interned value's cached atom array. */
        @JvmStatic
        fun atomsOf(s: String): IntArray =
            if (s.isEmpty()) IntArray(0) else of(s).atoms()

        /** Canonicalizing builder: no synthetics -> flat; else general. */
        private fun fromGraphemes(g: List<Int>): NFGString {
            if (g.isEmpty()) return EMPTY
            var anySynth = false
            for (x in g) if (x < 0) { anySynth = true; break }
            if (!anySynth) {
                val sb = StringBuilder(g.size)
                for (cp in g) sb.appendCodePoint(cp)
                // Already grapheme-sliced from an NFC string, so still NFC.
                return NFGString(TruffleString.fromJavaStringUncached(sb.toString(), UTF16), null)
            }
            return NFGString(null, g.toIntArray())
        }
    }

    val isFlat: Boolean get() = flat != null

    /* Derived views computed once. NFGString is an immutable value, so these
     * never go stale; they turn repeated chars()/atoms() -- which the engine
     * does per source, many times -- from a TruffleString node execution (or an
     * int[] rebuild) into a field read. Benign lazy state on an immutable value:
     * every computation is idempotent, so an unsynchronized race only recomputes. */
    private var cachedChars: Int = -1
    private var cachedAtoms: IntArray? = null

    /** Number of graphemes (`.chars`). */
    fun chars(): Int {
        var c = cachedChars
        if (c < 0) {
            c = if (flat != null) CP_LEN.execute(flat, UTF16) else graphemes!!.size
            cachedChars = c
        }
        return c
    }

    /**
     * The grapheme at grapheme index [i]: a codepoint (`>= 0`) or a synthetic id
     * (`< 0`). This is MoarVM's raw grapheme value; Raku-level `.ord` (base
     * codepoint) and `.ords` (constituent codepoints) are built on top of it.
     */
    fun graphemeAt(i: Int): Int = if (flat != null) CP_AT.execute(flat, i, UTF16) else graphemes!![i]

    /**
     * The full grapheme-atom array: one int per grapheme, a codepoint (`>= 0`)
     * or a synthetic id (`< 0`). This is what the regex engine (RxVmNode) reads,
     * indexing by grapheme; `atoms().size == chars()`.
     */
    fun atoms(): IntArray {
        if (graphemes != null) return graphemes
        cachedAtoms?.let { return it }
        val n = chars()
        val a = IntArray(n)
        var i = 0
        while (i < n) { a[i] = CP_AT.execute(flat, i, UTF16); i++ }
        cachedAtoms = a
        return a
    }

    /** Grapheme-indexed substring. */
    fun substr(offset: Int, length: Int): NFGString {
        if (length <= 0) return EMPTY
        if (flat != null) {
            return NFGString(SUBSTR.execute(flat, offset, length, UTF16, false), null)
        }
        val g = graphemes!!
        val slice = ArrayList<Int>(length)
        var i = offset
        val stop = offset + length
        while (i < stop) { slice.add(g[i]); i++ }
        return fromGraphemes(slice)
    }

    /** Back to a Java `String` (interop edge); expands synthetics to their codepoints. */
    fun toJavaString(): String {
        if (flat != null) return TO_JS.execute(flat)
        val g = graphemes!!
        val sb = StringBuilder(g.size)
        for (x in g) {
            if (x >= 0) sb.appendCodePoint(x)
            else for (cp in NFGSynthetics.lookup(x)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NFGString) return false
        // flat and general never represent the same grapheme sequence (general
        // always holds >=1 synthetic; flat holds none), so cross forms are unequal.
        return when {
            flat != null && other.flat != null -> TS_EQ.execute(flat, other.flat, UTF16)
            graphemes != null && other.graphemes != null -> graphemes.contentEquals(other.graphemes)
            else -> false
        }
    }

    // Synthetic ids are interned and stable process-wide, so the Java-string
    // expansion is a canonical key for both forms.
    override fun hashCode(): Int = toJavaString().hashCode()

    override fun toString(): String = toJavaString()
}
