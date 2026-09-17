package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The op census (milestone 8, Phase B): NQP_OP_CENSUS counts every
 * table op by id, every classlib op by class and method name (the typed
 * road's calls also under classlibTyped= on the header), and every
 * site's calls, misses, pins (every pin: an unfoldable resolve as much as
 * a polymorphic miss), republishes and named slow paths; printed
 * at exit next to the dispatch stats.
 *
 * The knob is PRESENCE-based, like JESP_DEBUG: set or unset, never a
 * value. `NQP_OP_CENSUS=0` turns the census ON. [ON] is read once into a
 * static final, so a program run with it unset carries no counter at
 * all -- the containers are not allocated and a site's stats field is
 * [NONE] -- and every bump sits behind a boundary, so with it on the
 * counters cost a call and never a deoptimization.
 *
 * The printed block is for machines: every op with a non-zero count and
 * every classlib name, sorted descending, uncut. A reader that wants a
 * top N (tools/build/evalserver-sweep.raku's census block, the rig's
 * parser) takes it itself.
 *
 * Counts rank candidates; the JFR share (tools/build/jfr-attribute.raku
 * --ops) decides what is hot.
 */
object NqpCensus {
    @JvmField val ON: Boolean = System.getenv("NQP_OP_CENSUS") != null

    private val table: Array<LongAdder> = if (ON) Array(NqpOps.OP_COUNT) { LongAdder() } else emptyArray()
    private val classlib: ConcurrentHashMap<String, LongAdder> by lazy { ConcurrentHashMap() }
    private val typed = LongAdder()

    /** One site class's counters. [slow] is keyed by the path a site names
     *  when it takes a slow road it could not fold (istrue.method,
     *  findmethod.nocache, findmethod.advisory). */
    class SiteStats(@JvmField val name: String) {
        @JvmField val calls = LongAdder()
        @JvmField val misses = LongAdder()
        /** Every pin, whether from polymorphism (misses) or from an
         *  unfoldable resolve (see slow=[...] for the reason). */
        @JvmField val pins = LongAdder()
        @JvmField val republished = LongAdder()
        @JvmField val slow = ConcurrentHashMap<String, LongAdder>()
    }

    /** The stats of a knob-off site: never counted, never printed; exists
     *  so the field is non-null. */
    @JvmField val NONE = SiteStats("none")
    private val sites: ConcurrentHashMap<String, SiteStats> by lazy { ConcurrentHashMap() }

    /** The counters for a site class, by simple name; [NONE] when off. */
    @JvmStatic fun stats(name: String): SiteStats = if (ON) sites.computeIfAbsent(name) { SiteStats(it) } else NONE

    @JvmStatic @TruffleBoundary fun table(id: Int) { table[id].increment() }

    /** The site is typed Any: NqpOps.ClassLibSite is package-private in
     *  Java and Kotlin refuses it in a public signature; the typed road's
     *  site is the Kotlin TypedSite. Both count under `Ops.meth`. */
    @JvmStatic @TruffleBoundary fun classlib(site: Any) {
        val name = when (site) {
            is NqpOps.ClassLibSite -> site.cls.substringAfterLast('/').removeSuffix(";") + "." + site.meth
            is NqpClassLibRoad.TypedSite -> site.name
            else -> site.toString()
        }
        classlib.computeIfAbsent(name) { LongAdder() }.increment()
    }

    /** A call through the typed road (batch 2): the per-name count above
     *  plus the header's classlibTyped= total, the routing fact
     *  t/jvm/22-classlib-road.t asserts on. */
    @JvmStatic @TruffleBoundary fun classlibTyped(site: Any) {
        classlib(site)
        typed.increment()
    }

    @JvmStatic @TruffleBoundary fun call(s: SiteStats) { s.calls.increment() }
    @JvmStatic @TruffleBoundary fun miss(s: SiteStats) { s.misses.increment() }

    /** Counted by `Site.pin()` itself, so a resolve-time pin (an
     *  unfoldable fact) lands here as surely as a miss-time one. */
    @JvmStatic @TruffleBoundary fun pin(s: SiteStats) { s.pins.increment() }
    @JvmStatic @TruffleBoundary fun republished(s: SiteStats) { s.republished.increment() }
    @JvmStatic @TruffleBoundary fun slow(s: SiteStats, path: String) { s.slow.computeIfAbsent(path) { LongAdder() }.increment() }

    /** Table op names from NqpOps' own OP_* constants: no build-time
     *  resource, the same names the encoder's table uses (lower-cased,
     *  the arity suffix kept: substr2, substr3). Two constants may share
     *  an id (an alias); the LAST OP_* field per id wins. An empty map
     *  when the reflection is refused -- every op then prints as
     *  op#<id>, which the census is still readable as. */
    private val names: Map<Int, String> by lazy {
        try {
            val out = HashMap<Int, String>()
            for (f in NqpOps::class.java.declaredFields) {
                if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
                if (!f.name.startsWith("OP_") || f.name == "OP_COUNT") continue
                f.trySetAccessible()
                out[f.getInt(null)] = f.name.removePrefix("OP_").lowercase()
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    init {
        if (ON) Runtime.getRuntime().addShutdownHook(Thread { print() })
    }

    /** The whole block, on stderr, at exit. A shutdown hook may not take
     *  the JVM down with it, so every failure here is reported and
     *  swallowed. */
    private fun print() {
        try {
            val tableTotal = table.sumOf { it.sum() }
            val classlibTotal = classlib.values.sumOf { it.sum() }
            val siteCalls = sites.values.sumOf { it.calls.sum() }
            val siteMisses = sites.values.sumOf { it.misses.sum() }
            System.err.println("op census: table=$tableTotal classlib=$classlibTotal siteCalls=$siteCalls siteMisses=$siteMisses classlibTyped=${typed.sum()}")
            table.withIndex().filter { it.value.sum() > 0 }.sortedByDescending { it.value.sum() }
                .forEach { System.err.println("  table " + it.value.sum() + " " + (names[it.index] ?: "op#${it.index}")) }
            classlib.entries.sortedByDescending { it.value.sum() }
                .forEach { System.err.println("  classlib " + it.value.sum() + " " + it.key) }
            sites.values.sortedByDescending { it.calls.sum() }.forEach { s ->
                val slow = s.slow.entries.sortedByDescending { it.value.sum() }.joinToString(" ") { it.key + "=" + it.value.sum() }
                System.err.println("  site ${s.name} calls=${s.calls.sum()} misses=${s.misses.sum()} pins=${s.pins.sum()} republished=${s.republished.sum()} slow=[$slow]")
            }
        } catch (t: Throwable) {
            System.err.println("op census: print failed: " + t)
        }
    }
}
