package org.raku.nqp.truffle

/**
 * Phase-1 self-test for [NFGString]. Expected values are the MoarVM reference
 * answers (rakubrew moar-2026.07), captured 2026-09-06:
 *
 *   "hello"          chars=5  ords=(104,101,108,108,111)
 *   "A\x1D538B"      chars=3  ords=(65,120120,66)          astral singleton -> flat
 *   "q\x0301"        chars=1  ords=(113,769)               no precomposed form -> synthetic
 *   "a\r\nb"         chars=3  ords=(97,13,10,98)           CRLF fusion -> synthetic
 *   "e\x0301"        chars=1  ords=(233)                   NFC composes -> flat
 *   "a\x0301\x0323"  chars=1  ords=(7841,769)              NFC canonical reorder -> synthetic
 *
 * Run: java -cp <nqp-runtime.jar>:<truffle-api.jar> org.raku.nqp.runtime.NFGSelfTest
 */
import org.raku.nqp.runtime.NFGSynthetics

object NFGSelfTest {
    private var passed = 0
    private var failed = 0

    private fun ok(cond: Boolean, label: String) {
        if (cond) { passed++ } else { failed++; System.out.println("FAIL: $label") }
    }

    private fun eq(actual: Any?, expected: Any?, label: String) {
        val good = actual == expected
        if (good) { passed++ } else { failed++; System.out.println("FAIL: $label -- got <$actual> expected <$expected>") }
    }

    private fun cp(vararg cps: Int): String {
        val sb = StringBuilder()
        for (c in cps) sb.appendCodePoint(c)
        return sb.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. ASCII -- flat
        run {
            val s = NFGString.fromJavaString("hello")
            ok(s.isFlat, "hello flat")
            eq(s.chars(), 5, "hello chars")
            eq(s.graphemeAt(0), 104, "hello graphemeAt(0)")
            eq(s.substr(1, 3).toJavaString(), "ell", "hello substr(1,3)")
            eq(s.toJavaString(), "hello", "hello round-trip")
        }

        // 2. Astral singleton U+1D538 -- flat, grapheme == codepoint
        run {
            val a = cp(0x1D538)               // MATHEMATICAL DOUBLE-STRUCK CAPITAL A
            val s = NFGString.fromJavaString("A" + a + "B")
            ok(s.isFlat, "astral flat")
            eq(s.chars(), 3, "astral chars")
            eq(s.graphemeAt(1), 0x1D538, "astral graphemeAt(1)")
            eq(s.substr(1, 1).toJavaString(), a, "astral substr(1,1)")
            eq(s.toJavaString(), "A" + a + "B", "astral round-trip")
        }

        // 3. q + combining acute (no precomposed form) -- synthetic
        run {
            val src = cp(113, 769)
            val s = NFGString.fromJavaString(src)
            ok(!s.isFlat, "q-acute general")
            eq(s.chars(), 1, "q-acute chars")
            ok(s.graphemeAt(0) < 0, "q-acute graphemeAt(0) synthetic")
            ok(NFGSynthetics.lookup(s.graphemeAt(0)).contentEquals(intArrayOf(113, 769)), "q-acute synthetic seq")
            eq(NFGSynthetics.baseOf(s.graphemeAt(0)), 113, "q-acute base")
            eq(s.toJavaString(), src, "q-acute round-trip")
        }

        // 4. CRLF fusion -- one synthetic grapheme for \r\n
        run {
            val s = NFGString.fromJavaString("a\r\nb")
            eq(s.chars(), 3, "crlf chars")
            eq(s.graphemeAt(0), 97, "crlf graphemeAt(0)")
            ok(s.graphemeAt(1) < 0, "crlf graphemeAt(1) synthetic")
            ok(NFGSynthetics.lookup(s.graphemeAt(1)).contentEquals(intArrayOf(13, 10)), "crlf synthetic seq")
            eq(s.graphemeAt(2), 98, "crlf graphemeAt(2)")
            eq(s.substr(1, 1).toJavaString(), "\r\n", "crlf substr(1,1)")
            eq(s.toJavaString(), "a\r\nb", "crlf round-trip")
        }

        // 5. e + combining acute -> NFC composes to U+00E9 -- flat
        run {
            val s = NFGString.fromJavaString(cp(101, 769))
            ok(s.isFlat, "e-acute flat (NFC composed)")
            eq(s.chars(), 1, "e-acute chars")
            eq(s.graphemeAt(0), 233, "e-acute graphemeAt(0)")
            eq(s.toJavaString(), cp(233), "e-acute round-trip (NFC)")
        }

        // 6. a + acute + dot-below -> NFC reorders to (U+1EA1, U+0301) -- synthetic
        //    (parity check: Java's Normalizer NFC must match moar's UCD ordering)
        run {
            val s = NFGString.fromJavaString(cp(97, 0x0301, 0x0323))
            eq(s.chars(), 1, "a-two-combiners chars")
            ok(s.graphemeAt(0) < 0, "a-two-combiners synthetic")
            ok(NFGSynthetics.lookup(s.graphemeAt(0)).contentEquals(intArrayOf(7841, 769)),
                "a-two-combiners NFC reorder seq")
            eq(s.toJavaString(), cp(7841, 769), "a-two-combiners round-trip (NFC)")
        }

        // Equality / interning
        run {
            eq(NFGString.fromJavaString("hello"), NFGString.fromJavaString("hello"), "flat eq")
            ok(NFGString.fromJavaString("hello") != NFGString.fromJavaString("hallo"), "flat neq")
            eq(NFGString.fromJavaString(cp(113, 769)), NFGString.fromJavaString(cp(113, 769)), "synthetic eq (interned)")
            // NFC-equivalent inputs must be equal (both flat U+00E9)
            eq(NFGString.fromJavaString(cp(101, 769)), NFGString.fromJavaString(cp(233)), "NFC-equivalent eq")
            // flat vs general never equal
            ok(NFGString.fromJavaString("q") != NFGString.fromJavaString(cp(113, 769)), "flat vs general neq")
        }

        // Empty
        run {
            eq(NFGString.fromJavaString("").chars(), 0, "empty chars")
            eq(NFGString.fromJavaString("").toJavaString(), "", "empty round-trip")
        }

        System.out.println("NFGString phase-1 self-test: $passed passed, $failed failed" +
            "  (synthetics allocated: ${NFGSynthetics.size()})")
        if (failed != 0) System.exit(1)
    }
}
