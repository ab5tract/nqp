package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import org.raku.nqp.runtime.ArgsExpectation
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.GlobalContext
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.StaticBodySource
import org.raku.nqp.runtime.StaticCodeInfo
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable

/**
 * A compilation unit over a unit artifact's store. buildTable builds a
 * shell per live block from the index alone (name, cuid, program index,
 * outer); a block's body is decoded from its record on first need
 * (StaticCodeInfo.ensureBody through BodySource), and its static lexical
 * values are applied then -- or queued until the deserialize program has
 * installed the SC they point into. Frame argument 0 of every engine
 * program is a CompilationUnit, so this stays one.
 */
class ProgramUnit(@JvmField val store: UnitStore) : CompilationUnit() {
    companion object {
        /** NQP_SITE_CHECK, the knob NqpProgramBuilder's site-check line reads:
         *  this side prints what the store holds, so a run's site ordinals can
         *  be judged against the slot table without the engine seeing it. */
        private val SITE_CHECK = System.getenv("NQP_SITE_CHECK") != null
    }

    private val header get() = store.header

    /** cuid -> code ref, for the blocks that carry one. */
    private val byCuid = HashMap<String, CodeRef>()

    /** The GlobalContext this unit was initialized in: a body filled at
     *  run time resolves its static lexical SCs through it. */
    private var gc: GlobalContext? = null

    @Volatile private var lexValuesReady = false
    private val pendingLexValues = ArrayList<Pair<StaticCodeInfo, List<StaticLexValue>>>()  // guarded by this

    fun buildTable(bootSt: STable?) {
        if (SITE_CHECK) System.err.println(
            "unit-check ${header.unitId} programs=${store.programCount}" +
            " slots=${header.dispatchSlotCount}")
        val n = store.blockCount
        val table = arrayOfNulls<CodeRef>(n)
        val list = ArrayList<CodeRef>(n)
        for (qbid in 0 until n) {
            val pidx = store.programIndex(qbid)
            if (pidx < 0) continue   // a gap
            val cuid = header.cuids[qbid]
            val cr = CodeRef(this, ProgramEntry.ENTER, header.names[qbid], cuid,
                ArgsExpectation.USE_BINDER, BodySource(qbid))
            val sci = cr.staticInfo
            sci.programIndex = pidx
            sci.unitEntry = true
            sci.methodName = "qb_$qbid"
            if (cuid != null && cuid.isNotEmpty()) byCuid[cuid] = cr
            if (bootSt != null) cr.st = bootSt
            table[qbid] = cr
            list.add(cr)
        }
        for (qbid in 0 until n) {
            val cr = table[qbid] ?: continue
            val o = store.outerQbid(qbid)
            if (o >= 0) cr.staticInfo.outerStaticInfo = table[o]?.staticInfo
        }
        qbidToCodeRef = table
        codeRefs = list.toTypedArray()
        callSites = emptyArray()   // the v1 call-site table was never written; the engine builds its own descriptors
        if (Ops.REPOINT_TRACE) {
            val sb = StringBuilder("nqp buildTable: unit ${header.unitId}" +
                " blocks=$n live=${list.size}" +
                " serializedCodeRefCount=${header.serializedCodeRefCount}" +
                " mainlineQbid=${header.mainlineQbid}")
            for (q in 0 until n)
                sb.append("\n  qbid ").append(q).append(" -> ")
                  .append(if (table[q] == null) "GAP" else "cuid=" + table[q]!!.staticInfo.uniqueId + " '" + table[q]!!.name + "'")
            System.err.println(sb)
        }
    }

    /** One block's body, decoded from its record slice on first need.
     *  Everything here goes through the setters, which never re-enter the
     *  fill; the static lexical values use the raw accessors for the same
     *  reason (see StaticCodeInfo.rawOLexicalIdx). */
    private inner class BodySource(private val qbid: Int) : StaticBodySource {
        override fun fill(sci: StaticCodeInfo) {
            val r = store.blockRecord(qbid)
                ?: throw IllegalStateException("unit ${header.unitId}: block $qbid is a gap but has a code ref")
            sci.oLexicalNames = r.oLex.takeIf { it.isNotEmpty() }?.toTypedArray()
            sci.iLexicalNames = r.iLex.takeIf { it.isNotEmpty() }?.toTypedArray()
            sci.nLexicalNames = r.nLex.takeIf { it.isNotEmpty() }?.toTypedArray()
            sci.sLexicalNames = r.sLex.takeIf { it.isNotEmpty() }?.toTypedArray()
            sci.handlers = unflatten(r.handlers)
            sci.hasExitHandler = r.hasExitHandler
            sci.isThunk = r.isThunk
            if (r.sourceFile != null) {
                sci.sourceFile = r.sourceFile
                sci.sourceLine = r.sourceLine
                sci.sourceLineDelta = r.sourceLineDelta
                if (r.sectionRaw != null) {
                    sci.sourceSectionRaw = r.sectionRaw
                    sci.sourceSectionLine = r.sectionLine
                    sci.sourceSectionFile = r.sectionFile?.toTypedArray()
                }
            }
            sci.finishBody()
            if (r.staticLex.isNotEmpty()) applyOrQueue(sci, r.staticLex)
        }
    }

    private fun applyOrQueue(sci: StaticCodeInfo, rows: List<StaticLexValue>) {
        if (!lexValuesReady) {
            synchronized(this) {
                if (!lexValuesReady) { pendingLexValues.add(sci to rows); return }
            }
        }
        applyLexValues(sci, rows)
    }

    /** The SC a row names must be installed: this unit's own by the
     *  deserialize program, a dependency's by the dependency load that
     *  program triggers. A body filled before that is queued.
     *
     *  Only the raw accessors are touched: this runs inside the block's own
     *  fill (before its body is published), where a body getter would
     *  recurse. A -1 index is skipped, as v1 skipped it. */
    private fun applyLexValues(sci: StaticCodeInfo, rows: List<StaticLexValue>) {
        val gc = this.gc ?: throw IllegalStateException("unit ${header.unitId}: static lexical values applied before initialization")
        for (v in rows) {
            val idx = sci.rawOLexicalIdx(v.name)
            if (idx == -1) continue
            val sc = gc.scs.get(v.scHandle)
                ?: throw IllegalStateException("unit ${header.unitId}: static lexical ${v.name} of block ${sci.methodName} names unknown SC ${v.scHandle}")
            sci.rawSetOLexStatic(idx, sc.getObject(v.scIdx), v.flags.toByte())
        }
    }

    override fun initializeCompilationUnit(tc: ThreadContext, runDeserialize: Boolean) {
        gc = tc.gc
        UnitLoadStats.time(header.unitId, "shells", { "blocks=${store.blockCount}" }) {
            buildTable(tc.gc.BOOTCode?.st)
        }
        hllConfig = tc.gc.getHLLConfigFor(hllName())
        if (runDeserialize) runDeserializeIfAvailable(tc)
    }

    override fun runDeserializeIfAvailable(tc: ThreadContext) {
        if (gc == null) gc = tc.gc
        // Includes the dependency loads the program triggers; they print
        // their own lines one level deeper.
        UnitLoadStats.time(header.unitId, "deserialize-program") { super.runDeserializeIfAvailable(tc) }
        val pending: List<Pair<StaticCodeInfo, List<StaticLexValue>>>
        synchronized(this) {
            lexValuesReady = true
            pending = ArrayList(pendingLexValues)
            pendingLexValues.clear()
        }
        UnitLoadStats.time(header.unitId, "static-lex-drain", { "blocks=${pending.size}" }) {
            for ((sci, rows) in pending) applyLexValues(sci, rows)
        }
    }

    /** Phase C's consumer entry: the persisted programs of one site, or
     *  null. Every slot is empty in Phase B. */
    fun dispatchSlot(programIndex: Int, ordinal: Int): ByteBuffer? = store.dispatchSlot(programIndex, ordinal)

    override fun getCallSites(): Array<CallSiteDescriptor> = emptyArray()
    override fun hllName(): String = header.hll
    override fun deserializeQbid(): Int = header.deserializeQbid
    override fun loadQbid(): Int = header.loadQbid
    override fun mainlineQbid(): Int = header.mainlineQbid
    override fun entryQbid(): Int = header.entryQbid
    override fun serializedCodeRefCount(): Int = header.serializedCodeRefCount
    override fun unitId(): String = header.unitId
    override fun lookupCodeRef(uniqueId: String): CodeRef? = byCuid[uniqueId]
    override fun engineProgram(idx: Int): String = store.program(idx)
    override fun serializedBlob(): ByteBuffer? = store.serialized

    /** A nested unit rides in the parent's zip, or was resolved into the
     *  parent's in-memory image by UnitWriter.image() before this unit
     *  existed. A missing entry is a hard error, never a lookup elsewhere. */
    override fun claimNested(tc: ThreadContext, name: String): CompilationUnit {
        val s = store.nested(name)
            ?: throw ExceptionHandling.dieInternal(tc, "unit ${unitId()} carries no nested unit named $name")
        val nested = ProgramUnit(s)
        nested.shared = tc.gc.sharingHint
        nested.initializeCompilationUnit(tc, false)
        return nested
    }

    private fun unflatten(flat: LongArray): Array<LongArray> {
        var p = 0
        val n = flat[p++].toInt()
        return Array(n) {
            val len = flat[p++].toInt()
            LongArray(len) { flat[p++] }
        }
    }
}

/**
 * Whether the unit's programs live in a store on disk -- a unit artifact
 * loaded by name -- rather than in one built for this process. Only a
 * stored unit's programs have a stable identity across runs: an in-memory
 * unit's id is a fresh sha1 per compile, so keying its programs by it
 * would stop identical texts from sharing a parsed root. The store name
 * tells them apart: a file-backed store is named after its file, and the
 * two in-memory makers name theirs "<memory:...>" (UnitWriter.store) and
 * "<buffer>" (loadbytecodebuffer). A file name never starts with '<'.
 */
fun ProgramUnit.isStoreBacked(): Boolean = !store.name.startsWith("<")

/**
 * The namespace a stored unit's program identities live in, or null for a
 * unit built in this process (see [isStoreBacked]). It is the store's name
 * and the unit id together, because neither alone identifies a store:
 *
 *  - a nested unit inherits its parent's store NAME (UnitStore.nested),
 *    so the name alone would let nested program 3 answer for the parent's;
 *  - a unit ID is author-supplied -- nqp's `--javaclass` names it, and
 *    Rakudo's build gives rakudo.jar and every BOOTSTRAP jar the same
 *    "perl6" -- so the id alone would let v6c's program 3 answer for
 *    rakudo.jar's (milestone 7 Task 7: that collision ran one unit's
 *    block body in place of another's).
 *
 * The pair is unique where it must be: two stores of one name differ by id
 * (parent vs nested), two stores of one id differ by name (two files), and
 * a pair that repeats is the same artifact opened again -- whose programs
 * are identical, which is exactly when sharing one parsed root is right
 * (an eval server re-opening the same jar per run depends on it).
 */
fun ProgramUnit.identityNamespace(): String? =
    if (isStoreBacked()) store.name + "!" + unitId() else null
