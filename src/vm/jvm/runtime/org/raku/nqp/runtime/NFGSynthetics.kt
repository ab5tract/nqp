package org.raku.nqp.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-global synthetic-grapheme table for NFG strings.
 *
 * A synthetic grapheme is an extended grapheme cluster that spans more than one
 * Unicode codepoint (base + combining marks with no precomposed form, or a fused
 * `\r\n`). It is addressed by a single **negative** id; this table maps the id
 * back to the codepoint sequence and interns sequences so that equal graphemes
 * share an id (making grapheme-sequence equality an `int[]` compare). Mirrors
 * MoarVM's global synthetic table (`MVMNFGState`).
 *
 * LIFECYCLE — deliberately process-global, monotonic, append-only, and it must
 * NOT register with `DispatchBootstrap.registerResettable`. It holds only
 * immutable `IntArray` codepoint sequences and boxed ids — no run-owned objects —
 * so it does not leak a GlobalContext the way the resolution inline caches did
 * (see docs/jvm-eval-server.md, the `evalserver-leak-hunting` note). Clearing it
 * per run would invalidate every live synthetic id in flight. Leave it alone.
 */
object NFGSynthetics {
    private class CodeSeq(val cps: IntArray) {
        private val hash = cps.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is CodeSeq && cps.contentEquals(other.cps)
    }

    private val seqToId = ConcurrentHashMap<CodeSeq, Int>()
    private val idToSeq = ConcurrentHashMap<Int, IntArray>()
    private val counter = AtomicInteger(0)

    /** Intern a codepoint sequence, returning its (negative) synthetic id. */
    fun intern(codepoints: IntArray): Int {
        val key = CodeSeq(codepoints.copyOf())
        // computeIfAbsent applies the mapping function at most once per key under
        // contention, and we populate idToSeq inside it, so the reverse map is
        // always live before the id becomes visible to any lookup().
        return seqToId.computeIfAbsent(key) {
            val id = -counter.incrementAndGet()   // -1, -2, -3, ...
            idToSeq[id] = it.cps
            id
        }
    }

    /** The codepoint sequence behind a synthetic id (id < 0). */
    fun lookup(id: Int): IntArray =
        idToSeq[id] ?: throw IllegalArgumentException("unknown synthetic grapheme id $id")

    /** The base grapheme of a synthetic — its first codepoint (what Raku `.ord` yields). */
    fun baseOf(id: Int): Int = lookup(id)[0]

    /** Number of distinct synthetics allocated so far (for tests / diagnostics). */
    fun size(): Int = idToSeq.size
}
