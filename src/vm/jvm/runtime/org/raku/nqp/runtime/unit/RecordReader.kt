package org.raku.nqp.runtime.unit

import org.raku.nqp.runtime.Base64
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Reads a QAST::UnitRecord (the driver's output) into a UnitRecord. Reads
 * by attribute name on the object's own type, so the compiler passes no
 * type map; hints are cached per type object for the life of the reader.
 * A missing field is a hard error naming it.
 */
class RecordReader(private val tc: ThreadContext) {
    private val hints = HashMap<Pair<SixModelObject, String>, Long>()

    private fun hint(type: SixModelObject, name: String): Long =
        hints.getOrPut(type to name) { type.st.REPR.hint_for(tc, type.st, type, name) }

    private fun str(o: SixModelObject, name: String): String? =
        Ops.getattr_s(o, o.st.WHAT, name, hint(o.st.WHAT, name), tc)
    private fun int(o: SixModelObject, name: String): Int =
        Ops.getattr_i(o, o.st.WHAT, name, hint(o.st.WHAT, name), tc).toInt()
    private fun list(o: SixModelObject, name: String): SixModelObject? =
        o.get_attribute_boxed(tc, o.st.WHAT, name, hint(o.st.WHAT, name))

    private fun strs(l: SixModelObject?): Array<String> {
        if (l == null) return arrayOf()
        val n = Ops.elems(l, tc).toInt()
        return Array(n) { l.at_pos_boxed(tc, it.toLong())?.get_str(tc) ?: "" }
    }
    private fun longs(l: SixModelObject?): LongArray {
        if (l == null) return LongArray(0)
        val n = Ops.elems(l, tc).toInt()
        return LongArray(n) { l.at_pos_boxed(tc, it.toLong())!!.get_int(tc) }
    }

    fun read(unit: SixModelObject): UnitRecord {
        val unitId = str(unit, "\$!unit_id")
            ?: throw ExceptionHandling.dieInternal(tc, "unit record: no unit id")
        val nested = LinkedHashMap<String, UnitRecord>()
        for (id in strs(list(unit, "@!nested_units"))) {
            nested[id] = tc.gc.inMemoryUnitRecords[id]
                ?: throw ExceptionHandling.dieInternal(tc, "unit record: $unitId names nested unit $id, of which no record was retained")
        }
        val programs = strs(list(unit, "@!programs"))

        val blocks = ArrayList<Pair<Int, BlockRec>>()
        var maxQbid = -1
        val bl = list(unit, "@!blocks")
        val iter = Ops.iter(bl, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val b = iter.shift_boxed(tc)!!
            val qbid = int(b, "\$!qbid")
            val name = str(b, "\$!name") ?: ""
            val program = int(b, "\$!program")
            if (qbid < 0)
                throw ExceptionHandling.dieInternal(tc, "unit record: block $name has no qbid")
            if (program < 0)
                throw ExceptionHandling.dieInternal(tc, "unit record: block $name (qbid $qbid) has no program")
            if (program >= programs.size)
                throw ExceptionHandling.dieInternal(tc, "unit record: block qbid $qbid names program $program of ${programs.size}")
            val cuid = str(b, "\$!cuid")?.ifEmpty { null }
            val file = str(b, "\$!file")?.ifEmpty { null }
            val line = int(b, "\$!line")
            val rawline = int(b, "\$!rawline")
            // #line sections: [rawline, line, file] rows, split into the
            // three parallel arrays BlockRec carries; null when there are none.
            val sections = list(b, "@!sections")
            val nsec = if (sections == null) 0 else Ops.elems(sections, tc).toInt()
            var secRaw: IntArray? = null; var secLine: IntArray? = null; var secFile: Array<String>? = null
            if (nsec > 0) {
                secRaw = IntArray(nsec); secLine = IntArray(nsec); secFile = Array(nsec) { "" }
                for (i in 0 until nsec) {
                    val row = sections!!.at_pos_boxed(tc, i.toLong())!!
                    secRaw[i] = row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt()
                    secLine[i] = row.at_pos_boxed(tc, 1)!!.get_int(tc).toInt()
                    secFile[i] = row.at_pos_boxed(tc, 2)!!.get_str(tc) ?: ""
                }
            }
            blocks.add(qbid to BlockRec(
                name, cuid, int(b, "\$!outer"),
                strs(list(b, "@!olex")), strs(list(b, "@!ilex")), strs(list(b, "@!nlex")), strs(list(b, "@!slex")),
                longs(list(b, "@!handlers")),
                int(b, "\$!has_exit_handler") != 0, int(b, "\$!is_thunk") != 0,
                file, line, rawline - line,
                secRaw, secLine, secFile,
                program))
            if (qbid > maxQbid) maxQbid = qbid
        }
        val table = arrayOfNulls<BlockRec>(maxQbid + 1)
        for ((q, b) in blocks) {
            if (table[q] != null)
                throw ExceptionHandling.dieInternal(tc, "unit record: two blocks with qbid $q")
            table[q] = b
        }

        val callSites = ArrayList<CallSiteRec>()
        list(unit, "@!callsites")?.let { cs ->
            val it = Ops.iter(cs, tc)
            while (Ops.istrue(it, tc) != 0L) {
                val row = it.shift_boxed(tc)!!
                val flagsObj = row.at_pos_boxed(tc, 0)!!
                val flags = ByteArray(Ops.elems(flagsObj, tc).toInt()) { i ->
                    flagsObj.at_pos_boxed(tc, i.toLong())!!.get_int(tc).toByte()
                }
                val names = strs(row.at_pos_boxed(tc, 1))
                callSites.add(CallSiteRec(flags, if (names.isEmpty()) null else names))
            }
        }
        val lexValues = ArrayList<LexValueRec>()
        list(unit, "@!blockvalues")?.let { bv ->
            val it = Ops.iter(bv, tc)
            while (Ops.istrue(it, tc) != 0L) {
                val row = it.shift_boxed(tc)!!
                lexValues.add(LexValueRec(
                    row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt(),
                    row.at_pos_boxed(tc, 1)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 2)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 3)!!.get_int(tc).toInt(),
                    row.at_pos_boxed(tc, 4)!!.get_int(tc).toInt()))
            }
        }
        val serializedString = str(unit, "\$!serialized")
        val serialized: ByteArray? = if (serializedString == null) null else {
            val sbuf = Base64.decode(serializedString)
            ByteArray(sbuf.remaining()).also { sbuf.get(it) }
        }
        val meta = UnitMeta(
            unitId, str(unit, "\$!hll")?.ifEmpty { null } ?: "nqp",
            str(unit, "\$!sc_handle")?.ifEmpty { null }, str(unit, "\$!sc_desc")?.ifEmpty { null },
            int(unit, "\$!serialized_count"), int(unit, "\$!mainline_qbid"), int(unit, "\$!entry_qbid"),
            int(unit, "\$!deserialize_qbid"), int(unit, "\$!load_qbid"),
            callSites, table, lexValues, nested.keys.toList())
        return UnitRecord(meta, programs, serialized, nested)
    }
}
