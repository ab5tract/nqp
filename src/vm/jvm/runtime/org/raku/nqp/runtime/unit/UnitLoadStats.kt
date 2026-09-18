package org.raku.nqp.runtime.unit

/**
 * Per-stage timing of unit loading, for the lazy-loading design's phase 0
 * (docs/superpowers/specs/2026-09-13-jvm-lazy-unit-loading-design.md).
 *
 * NQP_UNIT_LOAD_STATS=1 prints one stderr line per stage:
 *
 *     unit-load <depth> <unit> <stage> <ms> [counts]
 *
 * Depth is the nesting of unit loads on this thread: a dependency loaded
 * while its parent's deserialize program runs prints at depth + 1, and its
 * time is also inside the parent's `deserialize-program` line, so an analysis
 * subtracts the deeper lines. Off by default; unset, each stage costs one
 * boolean check.
 */
object UnitLoadStats {
    @JvmField
    val ON: Boolean = System.getenv("NQP_UNIT_LOAD_STATS") != null

    private val depth = ThreadLocal.withInitial { intArrayOf(0) }

    init {
        // The positive marker: a run that relies on these lines checks for it.
        if (ON) {
            System.err.println("unit-load: stats on")
            Runtime.getRuntime().addShutdownHook(Thread { org.raku.nqp.sixmodel.SerializationReader.reportAll() })
        }
    }

    @JvmStatic
    fun report(unit: String, stage: String, nanos: Long, counts: String = "") {
        val ms = nanos / 1_000_000.0
        System.err.println("unit-load ${depth.get()[0]} $unit $stage ${"%.2f".format(ms)}" +
            (if (counts.isEmpty()) "" else " $counts"))
    }

    /** Times [block] as one stage of [unit]. */
    inline fun <T> time(unit: String, stage: String, counts: () -> String = { "" }, block: () -> T): T {
        if (!ON) return block()
        val t0 = System.nanoTime()
        val r = block()
        report(unit, stage, System.nanoTime() - t0, counts())
        return r
    }

    /** Times a whole unit load, one level deeper for everything inside it. */
    inline fun <T> load(unit: String, block: () -> T): T {
        if (!ON) return block()
        enter()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            leave()
            report(unit, "load-total", System.nanoTime() - t0)
        }
    }

    @PublishedApi internal fun enter() { depth.get()[0]++ }
    @PublishedApi internal fun leave() { depth.get()[0]-- }
}
