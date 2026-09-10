package org.raku.nqp.runtime.unit

import java.io.FileOutputStream
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The artifact writer: reads the driver's unit record (the code-ref
 * fields collected per block, the programs list, the serialized blob,
 * the call-site data) through [RecordReader] and writes the zip.
 * `record` is the in-memory road's entry: the same reading, no file.
 */
object UnitWriter {
    /** The driver's record (QAST::UnitRecord), read into a [UnitRecord]. */
    @JvmStatic
    fun record(unit: SixModelObject?, tc: ThreadContext): UnitRecord {
        if (unit == null)
            throw ExceptionHandling.dieInternal(tc, "unit record: needs a QAST::UnitRecord")
        return RecordReader(tc).read(unit)
    }

    @JvmStatic
    fun write(unit: SixModelObject?, filename: String?, tc: ThreadContext) {
        if (filename == null)
            throw ExceptionHandling.dieInternal(tc, "jvm-write-unit-record: needs a filename")
        val record = record(unit, tc)
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
}
