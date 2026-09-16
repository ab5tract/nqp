package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The op census (milestone 8, Phase B): NQP_OP_CENSUS=1 counts every
 * table op by id, every classlib op by class and method name, and every
 * site's calls, misses, pins, republishes and named slow paths; printed
 * at exit next to the dispatch stats. [ON] is read once into a static
 * final, so a program compiled with it off carries no counter at all;
 * every bump sits behind a boundary, so with it on the counters cost a
 * call and never a deoptimization.
 *
 * Counts rank candidates; the JFR share (tools/build/jfr-attribute.raku
 * --ops) decides what is hot.
 */
object NqpCensus {
    @JvmField val ON: Boolean = System.getenv("NQP_OP_CENSUS") != null

    private val table = Array(NqpOps.OP_COUNT) { LongAdder() }
    private val classlib = ConcurrentHashMap<String, LongAdder>()

    /** One site class's counters. [slow] is keyed by the path a site names
     *  when it takes a slow road it could not fold (istrue.method,
     *  findmethod.nonauth). */
    class SiteStats(@JvmField val name: String) {
        @JvmField val calls = LongAdder()
        @JvmField val misses = LongAdder()
        @JvmField val pins = LongAdder()
        @JvmField val republished = LongAdder()
        @JvmField val slow = ConcurrentHashMap<String, LongAdder>()
    }

    /** The stats of an unrecorded site: counted, never printed. */
    @JvmField val NONE = SiteStats("none")
    private val sites = ConcurrentHashMap<String, SiteStats>()

    /** The counters for a site class, by simple name; [NONE] when off. */
    @JvmStatic fun stats(name: String): SiteStats = if (ON) sites.computeIfAbsent(name) { SiteStats(it) } else NONE

    @JvmStatic @TruffleBoundary fun table(id: Int) { table[id].increment() }

    /** The site is typed Any, not NqpOps.ClassLibSite: that class is
     *  package-private in Java, and Kotlin refuses to expose it in a public
     *  signature. ClassLibOp holds it as an Object operand anyway. */
    @JvmStatic @TruffleBoundary fun classlib(site: Any) {
        val s = site as NqpOps.ClassLibSite
        classlib.computeIfAbsent(s.cls.substringAfterLast('/').removeSuffix(";") + "." + s.meth) { LongAdder() }.increment()
    }

    @JvmStatic @TruffleBoundary fun call(s: SiteStats) { s.calls.increment() }
    @JvmStatic @TruffleBoundary fun miss(s: SiteStats, pinned: Boolean) { s.misses.increment(); if (pinned) s.pins.increment() }
    @JvmStatic @TruffleBoundary fun republished(s: SiteStats) { s.republished.increment() }
    @JvmStatic @TruffleBoundary fun slow(s: SiteStats, path: String) { s.slow.computeIfAbsent(path) { LongAdder() }.increment() }

    /** Table op names from NqpOps' own OP_* constants: no build-time
     *  resource, the same names the encoder's table uses (lower-cased,
     *  the arity suffix kept: substr2, substr3). */
    private val names: Map<Int, String> by lazy {
        val out = HashMap<Int, String>()
        for (f in NqpOps::class.java.declaredFields) {
            if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
            if (!f.name.startsWith("OP_") || f.name == "OP_COUNT") continue
            f.trySetAccessible()
            out[f.getInt(null)] = f.name.removePrefix("OP_").lowercase()
        }
        out
    }

    init {
        if (ON) Runtime.getRuntime().addShutdownHook(Thread { print() })
    }

    private fun print() {
        val tableTotal = table.sumOf { it.sum() }
        val classlibTotal = classlib.values.sumOf { it.sum() }
        val siteCalls = sites.values.sumOf { it.calls.sum() }
        val siteMisses = sites.values.sumOf { it.misses.sum() }
        System.err.println("op census: table=$tableTotal classlib=$classlibTotal siteCalls=$siteCalls siteMisses=$siteMisses")
        table.withIndex().filter { it.value.sum() > 0 }.sortedByDescending { it.value.sum() }.take(30)
            .forEach { System.err.println("  table " + it.value.sum() + " " + (names[it.index] ?: "op#${it.index}")) }
        classlib.entries.sortedByDescending { it.value.sum() }.take(30)
            .forEach { System.err.println("  classlib " + it.value.sum() + " " + it.key) }
        sites.values.sortedByDescending { it.calls.sum() }.forEach { s ->
            val slow = s.slow.entries.sortedByDescending { it.value.sum() }.joinToString(" ") { it.key + "=" + it.value.sum() }
            System.err.println("  site ${s.name} calls=${s.calls.sum()} misses=${s.misses.sum()} pins=${s.pins.sum()} republished=${s.republished.sum()} slow=[$slow]")
        }
    }
}
