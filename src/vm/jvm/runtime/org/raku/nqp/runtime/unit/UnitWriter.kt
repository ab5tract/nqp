package org.raku.nqp.runtime.unit

import java.io.FileOutputStream
import org.raku.nqp.jast2bc.JASTCompiler
import org.raku.nqp.jast2bc.JastClass
import org.raku.nqp.jast2bc.JastMethod
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The artifact writer: reads the JAST tree as a record (the code-ref
 * fields Compiler.nqp already collects per method, the programs list,
 * the serialized blob, the call-site data) and writes the zip.
 * Instruction lists are never looked at. Replaces JASTCompiler.writeClass
 * on the artifact road. `record` is the record road's entry: the same
 * reading, no file.
 */
object UnitWriter {
    /** Reads the JAST tree as a unit record. Refuses (a hard error naming
     *  the unit) a tree not compiled on the unit road, one with bytecode
     *  fallbacks, a block without a qbid or a program, two blocks sharing
     *  a qbid, and a nested unit id with no retained record. */
    @JvmStatic
    fun record(jast: SixModelObject?, jastNodes: SixModelObject?, tc: ThreadContext): UnitRecord {
        if (jast == null || jastNodes == null)
            throw ExceptionHandling.dieInternal(tc, "unit record: needs a JAST tree and the node types")
        JASTCompiler.ensureSetup(jastNodes, tc)
        val classType = jastNodes.at_key_boxed(tc, "JAST::Class")!!
        val methodType = jastNodes.at_key_boxed(tc, "JAST::Method")!!
        val jc = JastClass(jast, classType, tc)
        if (!jc.unitRoad)
            throw ExceptionHandling.dieInternal(tc, "unit record: ${jc.className} was not compiled on the artifact road")
        if (jc.fallbacks != 0)
            throw ExceptionHandling.dieInternal(tc, "unit record: ${jc.className} has ${jc.fallbacks} bytecode fallback bodies")

        /* Nested units (BEGIN-time compiles whose code refs this unit's
         * serialization points into) were retained as records by
         * loadcompunit; a missing one is a road mix-up, never a fallback. */
        val nested = LinkedHashMap<String, UnitRecord>()
        for (id in jc.nestedClasses) {
            nested[id] = tc.gc.inMemoryUnitRecords[id]
                ?: throw ExceptionHandling.dieInternal(tc, "unit record: ${jc.className} names nested unit $id, of which no record was retained")
        }

        /* Blocks, keyed by qbid; the table is sized by the highest qbid. */
        val blocks = ArrayList<Pair<Int, BlockRec>>()
        var maxQbid = -1
        val iter = Ops.iter(jc.methods, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val m = JastMethod(iter.shift_boxed(tc)!!, methodType, tc)
            if (m.crOuter == -2) continue                     // not a code ref (hllName, getCallSites, main...)
            if (m.crQbid < 0)
                throw ExceptionHandling.dieInternal(tc, "unit record: block ${m.name} has no qbid")
            if (m.crProgram < 0)
                throw ExceptionHandling.dieInternal(tc, "unit record: block ${m.crName} (${m.name}) has no program")
            val cuid = if (m.crCuid.isNullOrEmpty()) null else m.crCuid
            blocks.add(m.crQbid to BlockRec(
                m.crName ?: "", cuid, m.crOuter,
                strs(m.crOlex), strs(m.crIlex), strs(m.crNlex), strs(m.crSlex),
                m.crHandlers, m.hasExitHandler, m.isThunk,
                m.crFile, m.crLine, m.crRawLine - m.crLine,
                m.crSectionRaw, m.crSectionLine,
                m.crSectionFile?.let { f -> Array(f.size) { f[it] ?: "" } },
                m.crProgram))
            if (m.crQbid > maxQbid) maxQbid = m.crQbid
        }
        val table = arrayOfNulls<BlockRec>(maxQbid + 1)
        for ((q, b) in blocks) {
            if (table[q] != null)
                throw ExceptionHandling.dieInternal(tc, "unit record: two blocks with qbid $q")
            table[q] = b
        }

        val programs = strList(jc.programs, tc)
        for ((q, b) in blocks)
            if (b.programIndex >= programs.size)
                throw ExceptionHandling.dieInternal(tc, "unit record: block qbid $q names program ${b.programIndex} of ${programs.size}")
        val callSites = ArrayList<CallSiteRec>()
        jc.callsites?.let { cs ->
            val csIter = Ops.iter(cs, tc)
            while (Ops.istrue(csIter, tc) != 0L) {
                val row = csIter.shift_boxed(tc)!!
                val flagsObj = row.at_pos_boxed(tc, 0)!!
                val flags = ByteArray(Ops.elems(flagsObj, tc).toInt()) {
                    flagsObj.at_pos_boxed(tc, it.toLong())!!.get_int(tc).toByte()
                }
                val names = strList(row.at_pos_boxed(tc, 1), tc)
                callSites.add(CallSiteRec(flags, if (names.isEmpty()) null else names))
            }
        }
        val lexValues = ArrayList<LexValueRec>()
        jc.blockvalues?.let { bv ->
            val bvIter = Ops.iter(bv, tc)
            while (Ops.istrue(bvIter, tc) != 0L) {
                val row = bvIter.shift_boxed(tc)!!
                lexValues.add(LexValueRec(
                    row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt(),
                    row.at_pos_boxed(tc, 1)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 2)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 3)!!.get_int(tc).toInt(),
                    row.at_pos_boxed(tc, 4)!!.get_int(tc).toInt()))
            }
        }
        val meta = UnitMeta(
            jc.className!!, jc.hll?.ifEmpty { null } ?: "nqp",
            jc.scHandle?.ifEmpty { null }, jc.scDesc?.ifEmpty { null },
            jc.serializedCount, jc.mainlineQbid, jc.entryQbid, jc.deserializeQbid, jc.loadQbid,
            callSites, table, lexValues, nested.keys.toList())
        return UnitRecord(meta, programs, jc.serialized, nested)
    }

    /** The artifact writer: the record, zipped to a file. */
    @JvmStatic
    fun write(jast: SixModelObject?, jastNodes: SixModelObject?, filename: String?, tc: ThreadContext) {
        if (filename == null)
            throw ExceptionHandling.dieInternal(tc, "jvm-write-unit: needs a filename")
        val record = record(jast, jastNodes, tc)
        try {
            FileOutputStream(filename).use { UnitZip.write(record, it) }
        } catch (e: java.io.IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        if (System.getenv("NQP_CODE_WHY") != null)
            System.err.println("unit artifact ${record.meta.unitId} -> $filename " +
                "(${record.programs.size} programs, ${record.meta.blocks.size} qbids, " +
                "${record.meta.callSites.size} call sites, ${record.nested.size} nested)")
    }

    private fun strs(l: List<String?>): Array<String> = Array(l.size) { l[it] ?: "" }

    private fun strList(obj: SixModelObject?, tc: ThreadContext): Array<String> {
        if (obj == null) return arrayOf()
        val n = Ops.elems(obj, tc).toInt()
        return Array(n) { i -> obj.at_pos_boxed(tc, i.toLong())!!.get_str(tc) ?: "" }
    }
}
