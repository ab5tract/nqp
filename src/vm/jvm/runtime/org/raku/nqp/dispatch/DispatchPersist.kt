package org.raku.nqp.dispatch

import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.runtime.unit.UnitCodec
import org.raku.nqp.runtime.unit.UnitStore
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The persisted miss (milestone 7 Phase C): a site's first miss restores
 * the programs its unit.dispatch slot holds before anything is recorded.
 *
 * NQP_DISPATCH_PERSIST: unset or "on" consumes slots; "off" ignores them;
 * "verify" restores into DispatchCallSite.verifyPrograms without installing,
 * records fresh, and compares (see verify). NQP_DISPATCH_VERIFY_LOG=<path>
 * sends verify's lines to that file instead of stderr, pid-prefixed, so a
 * TAP run and a stderr-comparing test survive the mode. NQP_DISPATCH_RECORD
 * ("all" or a comma-separated list of store-name prefixes) makes the process
 * rewrite the selected artifacts' slots at exit (recordAtExit); no selector
 * reaches src/vm/jvm/stage0, which the next build compiles from.
 * NQP_DISPATCH_PERSIST_TRACE names every program a restore drops.
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

    /** Where verify's lines go; null is stderr. */
    private val verifyLog: String? = System.getenv("NQP_DISPATCH_VERIFY_LOG")

    /** Names each dropped program's reason; a restore is otherwise silent. */
    private val TRACE = System.getenv("NQP_DISPATCH_PERSIST_TRACE") != null

    private val stores = ConcurrentHashMap<String, UnitStore>()

    /** NQP_DISPATCH_RECORD: "all", or comma-separated store-name prefixes. */
    private val recordSelector: List<String>? = System.getenv("NQP_DISPATCH_RECORD")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    private fun selected(selector: List<String>, storeName: String): Boolean =
        selector.any { it == "all" || storeName.startsWith(it) }

    /** stage0 is the bootstrap the next build compiles FROM: its jars are
     *  committed, and the gradle build deliberately copies the UNTRAINED
     *  stage2 into them. A hand-set NQP_DISPATCH_RECORD=all would otherwise
     *  rewrite them as a side effect of any run that loads them. */
    private fun isStage0(path: String): Boolean =
        path.contains("/src/vm/jvm/stage0/") || path.startsWith("src/vm/jvm/stage0/")

    @JvmField val restored = AtomicLong()
    @JvmField val restoredSites = AtomicLong()
    @JvmField val dropped = AtomicLong()
    /** Slots skipped because their first int was not DispatchSlot.SCHEMA. */
    @JvmField val staleSchema = AtomicLong()
    /** Slots dropped because a referenced SC's stamp (SerializationContext.stamp)
     *  is not the one the slot was recorded under: the same handle, another
     *  build of the artifact. */
    @JvmField val staleStamp = AtomicLong()
    @JvmField val recorded = AtomicLong()
    @JvmField val verifyMatched = AtomicLong()
    @JvmField val verifyByOutcome = AtomicLong()
    @JvmField val verifyMismatched = AtomicLong()
    @JvmField val verifyUnseen = AtomicLong()

    /** The verify log's stream, opened on its first line; stderr needs none. */
    @Volatile private var logStream: PrintStream? = null

    private fun verifyOut(): PrintStream {
        val path = verifyLog ?: return System.err
        logStream?.let { return it }
        synchronized(this) {
            logStream?.let { return it }
            val s = PrintStream(FileOutputStream(path, true), true)
            logStream = s
            return s
        }
    }

    /** One verify line, pid-prefixed when it goes to the log: several
     *  processes (the eval server's children, a parallel harness) append to
     *  one file, so a line has to say who wrote it. */
    private fun verifySay(text: String) {
        if (verifyLog == null) System.err.println(text)
        else verifyOut().println("[" + ProcessHandle.current().pid() + "] " + text)
    }

    init {
        if (mode == Mode.VERIFY) {
            verifySay("dispatch-verify: on")
            Runtime.getRuntime().addShutdownHook(Thread {
                verifySay("dispatch-verify: matched=$verifyMatched byOutcome=$verifyByOutcome" +
                    " mismatched=$verifyMismatched unseen=$verifyUnseen")
                logStream?.close()
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
        /* The schema int by hand, BEFORE any decode: UnitCodec is untagged
         * and fixed-width, so a slot of another layout would not fail to
         * decode, it would decode into a plausible program. The slice is
         * already little-endian and the version is deliberately its first
         * field. A slot this runtime does not read is simply an empty one --
         * the build that reads a slot is the build that wrote it. */
        val schema = if (bytes.remaining() >= 4) bytes.getInt(bytes.position()) else -1
        if (schema != DispatchSlot.SCHEMA) {
            staleSchema.incrementAndGet()
            if (TRACE) System.err.println("dispatch-persist: stale schema $schema at ${site.identity}")
            return emptyList()
        }
        val slot = try { UnitCodec.decode(DispatchSlot.serializer(), bytes) }
                   catch (e: Exception) { throw IllegalStateException("unit ${ns}: dispatch slot of program ${site.programIndex} ordinal ${site.ordinal} does not decode: ${e.message}", e) }
        /* Every SC the slot names must be the one it was recorded against.
         * A handle this process has not loaded is not a stamp question: its
         * programs drop one by one in realise, as they always did. */
        for (s in slot.stamps) {
            val sc = tc.gc.scs[s.handle] ?: continue
            if (sc.stamp != s.stamp) {
                staleStamp.incrementAndGet()
                if (TRACE) System.err.println("dispatch-persist: stale stamp ${s.handle} recorded=${s.stamp} loaded=${sc.stamp} at ${site.identity}")
                return emptyList()
            }
        }
        val onDrop: ((String) -> Unit)? =
            if (TRACE) { reason -> System.err.println("dispatch-persist: dropped ${site.identity} $reason") }
            else null
        val out = ArrayList<DispatchProgram>(slot.programs.size)
        for (p in slot.programs) {
            val r = DispatchSlotCodec.realise(tc, p, onDrop)
            if (r == null) dropped.incrementAndGet() else out.add(r)
        }
        if (out.isNotEmpty()) { restored.addAndGet(out.size.toLong()); restoredSites.incrementAndGet() }
        return out
    }

    /** verify mode: after a fresh recording, every kept-aside program that
     *  applies to the recorded call must agree with the recording -- by its
     *  text, or failing that by what its outcome evaluates to (see
     *  [sameOutcome]). */
    fun verify(tc: ThreadContext, site: DispatchCallSite, recorded: DispatchProgram,
               descriptor: CallSiteDescriptor, args: Array<Any?>) {
        val kept = site.verifyPrograms ?: return
        if (kept.isEmpty()) { verifyUnseen.incrementAndGet(); return }
        val ctx = Dispatch.guardContext(tc, descriptor, args)
        var applicable = 0
        val text = DispatchDump.describe(recorded)
        for (p in kept) {
            if (!Captures.sameShape(p.descriptor, descriptor) || !p.guardsMatch(ctx)) continue
            applicable++
            val theirs = DispatchDump.describe(p)
            if (theirs == text) verifyMatched.incrementAndGet()
            else if (sameOutcome(ctx, p, recorded)) verifyByOutcome.incrementAndGet()
            else {
                verifyMismatched.incrementAndGet()
                verifySay("dispatch-verify: MISMATCH ${site.identity} ${site.linkedName}\n  persisted: $theirs\n  recorded:  $text")
            }
        }
        if (applicable == 0) verifyUnseen.incrementAndGet()
    }

    /**
     * Do two programs do the same thing to this call, though they are not
     * written the same way? A polymorphic site records the FORM its
     * dispatchers found at the time: nqp's lang-meth-call records a
     * type-guarded program before a class publishes its method cache and a
     * method-cache lookup after, and verify mode -- which keeps the restored
     * programs aside instead of installing them, so the site keeps recording
     * -- then sees two texts that resolve to one target. Comparing what the
     * outcome evaluates to on the recorded call's own arguments tells that
     * apart from a real divergence.
     *
     * Resuming programs stay text-only: their sources read resumption state,
     * which this context does not have, and they are rare. Evaluation itself
     * only reads attributes and hash entries -- no side effects -- but a
     * source that cannot be evaluated here (a shape built for another call)
     * throws rather than answering, and an unanswerable comparison is not an
     * agreement, so it counts as a difference.
     */
    private fun sameOutcome(ctx: DispatchContext, a: DispatchProgram, b: DispatchProgram): Boolean = try {
        if (a.isResuming || b.isResuming) false
        else if (a.bindControl != b.bindControl) false
        else if (a.resumptions.size != b.resumptions.size) false
        else if (a.resumptions.indices.any { i ->
                    val x = a.resumptions[i]; val y = b.resumptions[i]
                    x.dispatcher.id != y.dispatcher.id || !sameCapture(ctx, x.initArgs, y.initArgs) })
            false
        else {
            val ao = a.outcome
            val bo = b.outcome
            when {
                ao is Outcome.Value && bo is Outcome.Value ->
                    sameValue(ao.source.evaluateRaw(ctx), bo.source.evaluateRaw(ctx))
                ao is Outcome.InvokeCode && bo is Outcome.InvokeCode ->
                    sameValue(ao.callee.evaluateRaw(ctx), bo.callee.evaluateRaw(ctx)) &&
                        sameCapture(ctx, ao.args, bo.args)
                ao is Outcome.InvokeSyscall && bo is Outcome.InvokeSyscall ->
                    ao.syscall.name == bo.syscall.name && sameCapture(ctx, ao.args, bo.args)
                else -> false
            }
        }
    }
    catch (_: Exception) { false }

    private fun sameCapture(ctx: DispatchContext, a: CaptureShape, b: CaptureShape): Boolean {
        if (!Captures.sameShape(a.descriptor, b.descriptor)) return false
        val av = a.evaluate(ctx)
        val bv = b.evaluate(ctx)
        if (av.size != bv.size) return false
        for (i in av.indices) if (!sameValue(av[i], bv[i])) return false
        return true
    }

    /** An object is the same only when it IS the same: two type objects of
     *  one type are distinct values to a dispatch. */
    private fun sameValue(x: Any?, y: Any?): Boolean =
        if (x is SixModelObject || y is SixModelObject) x === y else x == y

    /** The hook's entry point: does nothing, and says nothing, unless
     *  NQP_DISPATCH_RECORD armed it. */
    @JvmStatic
    fun recordAtExit() {
        recordAtExit(recordSelector ?: return)
    }

    /**
     * The training run's exit: every recorded site of every selected store,
     * persisted into its slot; duplicates of one slot (two live sites with
     * one identity) merge by text, capped at MAX_PROGRAMS.
     *
     * Nothing here may throw. This runs in a shutdown hook, whose exception
     * the JVM prints to a stream nobody greps and whose exit status stays 0;
     * a throwable part way through -- after some artifacts were rewritten --
     * would leave a HALF-trained build that the per-artifact markers cannot
     * tell from a whole one. So every program and every artifact is
     * contained on its own, and the run ends with one `done` line, in a
     * finally, that the builds gate on together with the absence of FAILED.
     *
     * [selector] is NQP_DISPATCH_RECORD's, parsed; a test passes its own.
     */
    internal fun recordAtExit(selector: List<String>) {
        var paths = 0; var slots = 0; var programs = 0; var unpersistable = 0; var failed = 0
        try {
            val bySlot = HashMap<String, HashMap<String, HashMap<Int, LinkedHashMap<String, DispatchProgram>>>>()
            for (site in DispatchBootstrap.sites()) {
                val ns = site.unitNamespace ?: continue
                val sitePrograms = site.programs
                if (sitePrograms.isEmpty()) continue
                val store = stores[ns] ?: continue
                if (!selected(selector, store.name)) continue
                val slot = store.absoluteSlot(site.programIndex, site.ordinal)
                if (slot < 0) continue
                val byText = bySlot.getOrPut(store.name) { HashMap() }.getOrPut(store.entryPrefix) { HashMap() }.getOrPut(slot) { LinkedHashMap() }
                // Only fresh programs: a stale one (its type republished after recording, evicted
                // at the NEXT install) would be restored under a new guard with its baked callee.
                for (p in sitePrograms) if (p.isFresh) byText.putIfAbsent(DispatchDump.describe(p), p)
            }
            for ((path, perPrefix) in bySlot) {
                if (isStage0(path)) {
                    System.err.println("dispatch-record: refused $path (stage0 is never trained)")
                    continue
                }
                System.err.println("dispatch-record: rewriting $path")
                try {
                    var pathSlots = 0; var pathPrograms = 0; var pathUnpersistable = 0
                    val encoded = HashMap<String, Map<Int, ByteArray>>()
                    for ((prefix, perSlot) in perPrefix) {
                        val m = HashMap<Int, ByteArray>()
                        for ((slot, byText) in perSlot) {
                            val persisted = ArrayList<PProgram>()
                            val stamps = LinkedHashMap<String, Int>()
                            for (p in byText.values) {
                                val mine = LinkedHashMap<String, Int>()
                                val pp = try { DispatchSlotCodec.persist(p, mine) }
                                         catch (t: Throwable) {
                                             failed++
                                             System.err.println("dispatch-record: FAILED program $path!$prefix#$slot: ${reason(t)}")
                                             null
                                         }
                                if (pp == null) pathUnpersistable++
                                else if (persisted.size < Dispatch.MAX_PROGRAMS) { persisted.add(pp); stamps.putAll(mine) }
                            }
                            if (persisted.isEmpty()) continue
                            m[slot] = UnitCodec.encode(DispatchSlot.serializer(),
                                DispatchSlot(DispatchSlot.SCHEMA, persisted, stamps.map { PStamp(it.key, it.value) }))
                            pathSlots++; pathPrograms += persisted.size
                        }
                        if (m.isNotEmpty()) encoded[prefix] = m
                    }
                    if (encoded.isEmpty()) continue
                    org.raku.nqp.runtime.unit.UnitDispatchWriter.rewrite(path, encoded)
                    System.err.println("dispatch-record: wrote $pathSlots slots ($pathPrograms programs, $pathUnpersistable unpersistable) to $path")
                    paths++; slots += pathSlots; programs += pathPrograms; unpersistable += pathUnpersistable
                }
                catch (t: Throwable) {
                    failed++
                    System.err.println("dispatch-record: FAILED $path: ${reason(t)}")
                }
            }
        }
        catch (t: Throwable) {
            failed++
            System.err.println("dispatch-record: FAILED: ${reason(t)}")
        }
        finally {
            System.err.println("dispatch-record: done $paths paths, $slots slots, $programs programs," +
                " $unpersistable unpersistable, $failed failed")
        }
    }

    private fun reason(t: Throwable): String = "${t.javaClass.name}: ${t.message}"
}
