package org.raku.nqp.runtime.unit

import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The artifact writer: reads the driver's unit record (the code-ref fields
 * collected per block, the programs list, the serialized blob) through
 * [RecordReader] into a [UnitImage], and either writes it as a stored zip
 * or opens it as a [UnitStore] in memory. Both roads produce the same
 * bytes, so a unit built in memory is loaded exactly like a file.
 */
object UnitWriter {
    private val WHY = System.getenv("NQP_CODE_WHY") != null

    /** The driver's record (QAST::UnitRecord), read into an image. */
    @JvmStatic
    fun image(unit: SixModelObject?, tc: ThreadContext): UnitImage {
        if (unit == null) throw ExceptionHandling.dieInternal(tc, "unit record: needs a QAST::UnitRecord")
        return RecordReader(tc).read(unit)
    }

    /** The in-memory road (EVAL, BEGIN, a script): the same stored zip,
     *  in a heap buffer, opened like a file. */
    @JvmStatic
    fun store(unit: SixModelObject?, tc: ThreadContext): UnitStore {
        val img = image(unit, tc)
        return UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(img)), "<memory:${img.unitId}>")
    }

    @JvmStatic
    fun write(unit: SixModelObject?, filename: String?, tc: ThreadContext) {
        if (filename == null) throw ExceptionHandling.dieInternal(tc, "jvm-write-unit-record: needs a filename")
        val img = image(unit, tc)
        try { FileOutputStream(filename).use { UnitImageWriter.write(img, it) } }
        catch (e: java.io.IOException) { throw ExceptionHandling.dieInternal(tc, e) }
        if (WHY) System.err.println("unit artifact ${img.unitId} -> $filename " +
            "(${img.programs.size} programs, ${img.blocks.size} qbids, ${img.dispatchCounts.sum()} dispatch slots, ${img.nested.size} nested)")
    }
}
