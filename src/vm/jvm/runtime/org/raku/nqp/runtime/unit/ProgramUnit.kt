package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import org.raku.nqp.runtime.ArgsExpectation
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable

/**
 * A compilation unit built from a unit artifact's record: the block
 * table gives every code ref, no reflection, no class. Frame argument 0
 * of every engine program is a CompilationUnit, so this stays one.
 */
class ProgramUnit(@JvmField val record: UnitRecord) : CompilationUnit() {
    private val meta get() = record.meta

    /** cuid -> code ref, for the blocks that carry one: a nested unit's
     *  (claimed by cuid) and a runtime-compiled unit's (re-pointed by cuid
     *  through jvm-repoint-dynamic-code, looked up by nqp::getcodecuid
     *  users). Jar-bound comp-mode units write no cuids and leave it empty. */
    private val byCuid = HashMap<String, CodeRef>()

    /** The pure part of initialization: code refs, outers, call sites. */
    fun buildTable(bootSt: STable?) {
        val blocks = meta.blocks
        val table = arrayOfNulls<CodeRef>(blocks.size)
        val list = ArrayList<CodeRef>(blocks.size)
        for (qbid in blocks.indices) {
            val b = blocks[qbid] ?: continue
            val cr = CodeRef(this, ProgramEntry.ENTER, b.name, b.cuid,
                if (b.oLex.isEmpty()) null else b.oLex,
                if (b.iLex.isEmpty()) null else b.iLex,
                if (b.nLex.isEmpty()) null else b.nLex,
                if (b.sLex.isEmpty()) null else b.sLex,
                unflatten(b.handlers), ArgsExpectation.USE_BINDER)
            val sci = cr.staticInfo
            sci.programIndex = b.programIndex
            sci.methodName = "qb_$qbid"
            b.cuid?.let { if (it.isNotEmpty()) byCuid[it] = cr }
            sci.hasExitHandler = b.hasExitHandler
            sci.isThunk = b.isThunk
            if (b.sourceFile != null) {
                sci.sourceFile = b.sourceFile
                sci.sourceLine = b.sourceLine
                sci.sourceLineDelta = b.sourceLineDelta
                if (b.sectionRaw != null) {
                    sci.sourceSectionRaw = b.sectionRaw
                    sci.sourceSectionLine = b.sectionLine
                    sci.sourceSectionFile = b.sectionFile
                }
            }
            if (bootSt != null) cr.st = bootSt
            table[qbid] = cr
            list.add(cr)
        }
        for (qbid in blocks.indices) {
            val b = blocks[qbid] ?: continue
            if (b.outerQbid >= 0)
                table[qbid]!!.staticInfo.outerStaticInfo = table[b.outerQbid]?.staticInfo
        }
        qbidToCodeRef = table
        codeRefs = list.toTypedArray()
        callSites = getCallSites()
    }

    override fun initializeCompilationUnit(tc: ThreadContext, runDeserialize: Boolean) {
        buildTable(tc.gc.BOOTCode?.st)
        hllConfig = tc.gc.getHLLConfigFor(hllName())
        if (runDeserialize) runDeserializeIfAvailable(tc)
    }

    /** The deserialize program installs the SC; the static lexical values
     *  point into it, so they follow (setup_blv was the last post-deserialize
     *  task on the class road, exactly this position). */
    override fun runDeserializeIfAvailable(tc: ThreadContext) {
        super.runDeserializeIfAvailable(tc)
        applyStaticLexValues(tc)
    }

    fun applyStaticLexValues(tc: ThreadContext) {
        val table = qbidToCodeRef ?: return
        for (v in meta.staticLexValues) {
            val cr = table.getOrNull(v.qbid) ?: continue
            val idx = cr.staticInfo.oTryGetLexicalIdx(v.name)
            if (idx == -1) continue
            val sc = tc.gc.scs.get(v.scHandle)
                ?: throw ExceptionHandling.dieInternal(tc, "unit ${unitId()}: static lexical ${v.name} of block ${v.qbid} names unknown SC ${v.scHandle}")
            cr.staticInfo.oLexStatic!![idx] = sc.getObject(v.scIdx)
            cr.staticInfo.oLexStaticFlags!![idx] = v.flags.toByte()
        }
    }

    override fun getCallSites(): Array<CallSiteDescriptor> =
        Array(meta.callSites.size) { CallSiteDescriptor(meta.callSites[it].flags, meta.callSites[it].names) }

    override fun hllName(): String = meta.hll
    override fun deserializeQbid(): Int = meta.deserializeQbid
    override fun loadQbid(): Int = meta.loadQbid
    override fun mainlineQbid(): Int = meta.mainlineQbid
    override fun entryQbid(): Int = meta.entryQbid
    override fun serializedCodeRefCount(): Int = meta.serializedCodeRefCount
    override fun unitId(): String = meta.unitId
    override fun lookupCodeRef(uniqueId: String): CodeRef? = byCuid[uniqueId]
    override fun engineProgram(idx: Int): String = record.programs[idx]
    override fun serializedBlob(): ByteBuffer? = record.serialized?.let { ByteBuffer.wrap(it) }

    /** A nested unit rides in the parent's zip (a loaded artifact) or, for
     *  a parent that is itself a record in memory, in the process's
     *  retention map -- the same map the writer embeds from. */
    override fun claimNested(tc: ThreadContext, name: String): CompilationUnit {
        val rec = record.nested[name] ?: tc.gc.inMemoryUnitRecords[name]
            ?: throw ExceptionHandling.dieInternal(tc, "unit ${unitId()} carries no nested unit named $name")
        val nested = ProgramUnit(rec)
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
