package org.raku.nqp.runtime.unit

import org.raku.nqp.runtime.Base64
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Reads a QAST::UnitRecord (the driver's output) into a UnitImage. Reads
 * by attribute name on the object's own type, so the compiler passes no
 * type map; hints are cached per type object for the life of the reader.
 * A missing field is a hard error naming it -- except the ones read through
 * [intOr], which a stage0 compiler's guest class may not carry yet.
 */
class RecordReader(private val tc: ThreadContext) {
    private val hints = HashMap<Pair<SixModelObject, String>, Long>()

    /** (type, name) pairs [intOr] has already found the guest class does not
     *  carry. Without it every block of every compile throws and catches one
     *  RuntimeException per absent attribute, each carrying a full attribute
     *  dump built by RakuObjectLayout. */
    private val absent = HashSet<Pair<SixModelObject, String>>()

    private fun hint(type: SixModelObject, name: String): Long =
        hints.getOrPut(type to name) { type.st.REPR.hint_for(tc, type.st, type, name) }

    private fun str(o: SixModelObject, name: String): String? =
        Ops.getattr_s(o, o.st.WHAT, name, hint(o.st.WHAT, name), tc)
    private fun int(o: SixModelObject, name: String): Int =
        Ops.getattr_i(o, o.st.WHAT, name, hint(o.st.WHAT, name), tc).toInt()

    /**
     * [int], but [default] when the guest class carries no such attribute at
     * all: during the transition window the compiler that builds the record
     * may be a stage0 one whose QAST::BlockRecord predates the field.
     * RakuObjectLayout.resolve is what refuses an unknown name, with a plain
     * RuntimeException naming it; every other failure -- a native-type
     * mismatch, an nqp-level throw -- is rethrown untouched.
     */
    private fun intOr(o: SixModelObject, name: String, default: Int): Int {
        val key = o.st.WHAT to name
        if (key in absent) return default
        return try { int(o, name) }
        catch (e: RuntimeException) {
            if (e.javaClass === RuntimeException::class.java &&
                e.message?.startsWith("No such attribute '$name'") == true) {
                absent.add(key)
                default
            }
            else throw e
        }
    }

    private fun list(o: SixModelObject, name: String): SixModelObject? =
        o.get_attribute_boxed(tc, o.st.WHAT, name, hint(o.st.WHAT, name))

    private fun strs(l: SixModelObject?): List<String> {
        if (l == null) return listOf()
        val n = Ops.elems(l, tc).toInt()
        return List(n) { l.at_pos_boxed(tc, it.toLong())?.get_str(tc) ?: "" }
    }
    private fun longs(l: SixModelObject?): LongArray {
        if (l == null) return LongArray(0)
        val n = Ops.elems(l, tc).toInt()
        return LongArray(n) { l.at_pos_boxed(tc, it.toLong())!!.get_int(tc) }
    }

    fun read(unit: SixModelObject): UnitImage {
        val unitId = str(unit, "\$!unit_id")
            ?: throw ExceptionHandling.dieInternal(tc, "unit record: no unit id")
        val nested = LinkedHashMap<String, UnitStore>()
        for (id in strs(list(unit, "@!nested_units"))) {
            nested[id] = tc.gc.inMemoryUnitRecords[id]
                ?: throw ExceptionHandling.dieInternal(tc, "unit record: $unitId names nested unit $id, of which no record was retained")
        }
        val programs = strs(list(unit, "@!programs"))

        /* The static lexical values, grouped by the block they belong to
         * before the blocks themselves are built: v1 kept one global list
         * and applied it after deserialization, v2 hands each block its own
         * rows, so the block's body source can apply them when it fills. */
        val lexByQbid = HashMap<Int, ArrayList<StaticLexValue>>()
        list(unit, "@!blockvalues")?.let { bv ->
            val it = Ops.iter(bv, tc)
            while (Ops.istrue(it, tc) != 0L) {
                val row = it.shift_boxed(tc)!!
                val qbid = row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt()
                lexByQbid.getOrPut(qbid) { ArrayList() }.add(StaticLexValue(
                    row.at_pos_boxed(tc, 1)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 2)!!.get_str(tc)!!,
                    row.at_pos_boxed(tc, 3)!!.get_int(tc).toInt(),
                    row.at_pos_boxed(tc, 4)!!.get_int(tc).toInt()))
            }
        }

        val blocks = ArrayList<Pair<Int, BlockEntry>>()
        val dispatchesOf = HashMap<Int, Int>()          // program index -> its site count
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
            // three parallel arrays BlockRecord carries; null when there are none.
            val sections = list(b, "@!sections")
            val nsec = if (sections == null) 0 else Ops.elems(sections, tc).toInt()
            var secRaw: IntArray? = null; var secLine: IntArray? = null; var secFile: MutableList<String>? = null
            if (nsec > 0) {
                secRaw = IntArray(nsec); secLine = IntArray(nsec); secFile = ArrayList(nsec)
                for (i in 0 until nsec) {
                    val row = sections!!.at_pos_boxed(tc, i.toLong())!!
                    secRaw[i] = row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt()
                    secLine[i] = row.at_pos_boxed(tc, 1)!!.get_int(tc).toInt()
                    secFile.add(row.at_pos_boxed(tc, 2)!!.get_str(tc) ?: "")
                }
            }
            dispatchesOf[program] = intOr(b, "\$!dispatches", 0)
            blocks.add(qbid to BlockEntry(name, cuid, int(b, "\$!outer"), program, BlockRecord(
                strs(list(b, "@!olex")), strs(list(b, "@!ilex")), strs(list(b, "@!nlex")), strs(list(b, "@!slex")),
                longs(list(b, "@!handlers")),
                int(b, "\$!has_exit_handler") != 0, int(b, "\$!is_thunk") != 0,
                file, line, rawline - line,
                secRaw, secLine, secFile,
                lexByQbid[qbid] ?: listOf())))
            if (qbid > maxQbid) maxQbid = qbid
        }
        val table = arrayOfNulls<BlockEntry>(maxQbid + 1)
        for ((q, b) in blocks) {
            if (table[q] != null)
                throw ExceptionHandling.dieInternal(tc, "unit record: two blocks with qbid $q")
            table[q] = b
        }
        /* A row may name a qbid that never became a block: cuid_to_qbid()
         * hands every cuid it sees an id, and %*BLOCK_LEX_VALUES keeps the
         * cuids of blocks that were not compiled into this unit -- the gap
         * ProgramUnitTest documents. v1 dropped those rows where it applied
         * them; v2 drops them here, where the grouping happens. */
        if (WHY) for (q in lexByQbid.keys)
            if (q < 0 || q > maxQbid || table[q] == null)
                System.err.println("unit record $unitId: ${lexByQbid[q]!!.size} static lexical row(s) for qbid $q, which is not a block; dropped")

        val serializedString = str(unit, "\$!serialized")
        val serialized: ByteArray? = if (serializedString == null) null else {
            val sbuf = Base64.decode(serializedString)
            ByteArray(sbuf.remaining()).also { sbuf.get(it) }
        }
        /* Programs are 1:1 with blocks, so a block's site count is its
         * program's slot count. */
        val dispatchCounts = IntArray(programs.size) { dispatchesOf[it] ?: 0 }
        return UnitImage(
            unitId, str(unit, "\$!hll")?.ifEmpty { null } ?: "nqp",
            str(unit, "\$!sc_handle")?.ifEmpty { null }, str(unit, "\$!sc_desc")?.ifEmpty { null },
            int(unit, "\$!serialized_count"), int(unit, "\$!mainline_qbid"), int(unit, "\$!entry_qbid"),
            int(unit, "\$!deserialize_qbid"), int(unit, "\$!load_qbid"),
            table.toList(), programs, dispatchCounts, serialized, nested)
    }

    companion object {
        private val WHY = System.getenv("NQP_CODE_WHY") != null
    }
}
