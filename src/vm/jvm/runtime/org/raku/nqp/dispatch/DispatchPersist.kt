package org.raku.nqp.dispatch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.runtime.unit.UnitCodec
import org.raku.nqp.runtime.unit.UnitStore

/**
 * The persisted miss (milestone 7 Phase C): a site's first miss restores
 * the programs its unit.dispatch slot holds before anything is recorded.
 *
 * NQP_DISPATCH_PERSIST: unset or "on" consumes slots; "off" ignores them;
 * "verify" restores into DispatchCallSite.verifyPrograms without installing,
 * records fresh, and compares (see verify). NQP_DISPATCH_RECORD ("all" or
 * a comma-separated list of store-name prefixes) makes the process rewrite
 * the selected artifacts' slots at exit (recordAtExit).
 *
 * Stores are registered by identity namespace (store name + "!" + unit
 * id) when a ProgramUnit initializes; they are immutable and process-wide,
 * so the eval server's runs share them.
 */
object DispatchPersist {
    enum class Mode { ON, OFF, VERIFY }

    @JvmField val mode: Mode = when (System.getenv("NQP_DISPATCH_PERSIST")) {
        "off" -> Mode.OFF
        "verify" -> Mode.VERIFY
        else -> Mode.ON
    }

    private val stores = ConcurrentHashMap<String, UnitStore>()

    /** NQP_DISPATCH_RECORD: "all", or comma-separated store-name prefixes. */
    private val recordSelector: List<String>? = System.getenv("NQP_DISPATCH_RECORD")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    private fun selected(storeName: String): Boolean =
        recordSelector!!.any { it == "all" || storeName.startsWith(it) }

    @JvmField val restored = AtomicLong()
    @JvmField val restoredSites = AtomicLong()
    @JvmField val dropped = AtomicLong()
    @JvmField val recorded = AtomicLong()
    @JvmField val verifyMatched = AtomicLong()
    @JvmField val verifyMismatched = AtomicLong()
    @JvmField val verifyUnseen = AtomicLong()

    init {
        if (mode == Mode.VERIFY) {
            System.err.println("dispatch-verify: on")
            Runtime.getRuntime().addShutdownHook(Thread {
                System.err.println("dispatch-verify: matched=$verifyMatched mismatched=$verifyMismatched unseen=$verifyUnseen")
            })
        }
        if (recordSelector != null) Runtime.getRuntime().addShutdownHook(Thread { recordAtExit() })
    }

    @JvmStatic
    fun register(namespace: String, store: UnitStore) { stores.putIfAbsent(namespace, store) }

    fun store(namespace: String): UnitStore? = stores[namespace]

    /** The slot's programs realised against this process, empty when the
     *  site is anonymous, the slot empty, or nothing resolves. */
    fun restore(tc: ThreadContext, site: DispatchCallSite): List<DispatchProgram> {
        val ns = site.unitNamespace ?: return emptyList()
        val store = stores[ns] ?: return emptyList()
        val bytes = store.dispatchSlot(site.programIndex, site.ordinal) ?: return emptyList()
        val slot = try { UnitCodec.decode(DispatchSlot.serializer(), bytes) }
                   catch (e: Exception) { throw IllegalStateException("unit ${ns}: dispatch slot of program ${site.programIndex} ordinal ${site.ordinal} does not decode: ${e.message}", e) }
        val out = ArrayList<DispatchProgram>(slot.programs.size)
        for (p in slot.programs) {
            val r = DispatchSlotCodec.realise(tc, p)
            if (r == null) dropped.incrementAndGet() else out.add(r)
        }
        if (out.isNotEmpty()) { restored.addAndGet(out.size.toLong()); restoredSites.incrementAndGet() }
        return out
    }

    /** verify mode: after a fresh recording, every kept-aside program that
     *  applies to the recorded call must read the same as the recording. */
    fun verify(tc: ThreadContext, site: DispatchCallSite, recorded: DispatchProgram,
               descriptor: CallSiteDescriptor, args: Array<Any?>) {
        val kept = site.verifyPrograms ?: return
        val ctx = Dispatch.guardContext(tc, descriptor, args)
        var applicable = 0
        val text = DispatchDump.describe(recorded)
        for (p in kept) {
            if (!Captures.sameShape(p.descriptor, descriptor) || !p.guardsMatch(ctx)) continue
            applicable++
            val theirs = DispatchDump.describe(p)
            if (theirs == text) verifyMatched.incrementAndGet()
            else {
                verifyMismatched.incrementAndGet()
                System.err.println("dispatch-verify: MISMATCH ${site.identity} ${site.linkedName}\n  persisted: $theirs\n  recorded:  $text")
            }
        }
        if (applicable == 0) verifyUnseen.incrementAndGet()
    }

    /** The training run's exit: every recorded site of every selected
     *  store, persisted into its slot; duplicates of one slot (two live
     *  sites with one identity) merge by text, capped at MAX_PROGRAMS. */
    @JvmStatic
    fun recordAtExit() {
        val bySlot = HashMap<String, HashMap<String, HashMap<Int, LinkedHashMap<String, DispatchProgram>>>>()
        for (site in DispatchBootstrap.sites()) {
            val ns = site.unitNamespace ?: continue
            val programs = site.programs
            if (programs.isEmpty()) continue
            val store = stores[ns] ?: continue
            if (!selected(store.name)) continue
            val slot = store.absoluteSlot(site.programIndex, site.ordinal)
            if (slot < 0) continue
            val byText = bySlot.getOrPut(store.name) { HashMap() }.getOrPut(store.entryPrefix) { HashMap() }.getOrPut(slot) { LinkedHashMap() }
            for (p in programs) byText.putIfAbsent(DispatchDump.describe(p), p)
        }
        for ((path, perPrefix) in bySlot) {
            var slots = 0; var written = 0; var unpersistable = 0
            val encoded = HashMap<String, Map<Int, ByteArray>>()
            for ((prefix, perSlot) in perPrefix) {
                val m = HashMap<Int, ByteArray>()
                for ((slot, byText) in perSlot) {
                    val persisted = ArrayList<PProgram>()
                    for (p in byText.values) {
                        val pp = DispatchSlotCodec.persist(p)
                        if (pp == null) unpersistable++ else if (persisted.size < Dispatch.MAX_PROGRAMS) persisted.add(pp)
                    }
                    if (persisted.isEmpty()) continue
                    m[slot] = UnitCodec.encode(DispatchSlot.serializer(), DispatchSlot(persisted))
                    slots++; written += persisted.size
                }
                if (m.isNotEmpty()) encoded[prefix] = m
            }
            if (encoded.isEmpty()) continue
            org.raku.nqp.runtime.unit.UnitDispatchWriter.rewrite(path, encoded)
            System.err.println("dispatch-record: wrote $slots slots ($written programs, $unpersistable unpersistable) to $path")
        }
    }
}
