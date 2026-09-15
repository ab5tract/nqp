package org.raku.nqp.runtime.unit

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a UnitImage as a stored zip: unit.index first (the sniff reads
 *  the first local header), then records, programs, serialized (when
 *  present), dispatch, and each nested unit's four entries copied from
 *  its store. Stored, not deflated: the reader slices one mapping. */
object UnitImageWriter {
    fun bytes(image: UnitImage): ByteArray = ByteArrayOutputStream(1 shl 16).also { write(image, it) }.toByteArray()

    fun write(image: UnitImage, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.setMethod(ZipOutputStream.STORED)
            val e = encode(image)
            put(z, UnitStore.INDEX, e.index)
            put(z, UnitStore.RECORDS, e.records)
            put(z, UnitStore.PROGRAMS, e.programs)
            image.serialized?.let { put(z, UnitStore.SERIALIZED, it) }
            put(z, UnitStore.DISPATCH, e.dispatch)
            for ((id, n) in image.nested) {
                for (suffix in listOf(".index", ".records", ".programs", ".dispatch")) {
                    val entry = n.entry("unit$suffix")
                        ?: throw IllegalStateException("unit ${image.unitId}: nested unit $id carries no unit$suffix")
                    put(z, UnitStore.NESTED_DIR + id + suffix, ByteArray(entry.remaining()).also { entry.duplicate().get(it) })
                }
            }
        }
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().also { it.update(bytes) }.value
        z.putNextEntry(entry); z.write(bytes); z.closeEntry()
    }

    private class Encoded(val index: ByteArray, val records: ByteArray, val programs: ByteArray, val dispatch: ByteArray)

    private fun encode(image: UnitImage): Encoded {
        val nb = image.blocks.size
        val np = image.programs.size
        require(image.dispatchCounts.size == np) { "unit ${image.unitId}: ${image.dispatchCounts.size} dispatch counts for $np programs" }
        // records, back to back
        val records = ByteArrayOutputStream(1 shl 16)
        val blockRows = IntArray(nb * 4)
        /* Programs are 1:1 with blocks, and ProgramUnit sizes a program's
         * dispatch-slot window from the block that names it, so two blocks
         * naming one program would silently share one slot window. */
        val programClaimed = BooleanArray(np)
        for (q in 0 until nb) {
            val b = image.blocks[q]
            if (b == null) { blockRows[q * 4] = 0; blockRows[q * 4 + 1] = 0; blockRows[q * 4 + 2] = -1; blockRows[q * 4 + 3] = -1; continue }
            require(b.programIndex in 0 until np) { "unit ${image.unitId}: block $q names program ${b.programIndex} of $np" }
            require(!programClaimed[b.programIndex]) { "unit ${image.unitId}: block $q names program ${b.programIndex}, which another block already claims" }
            programClaimed[b.programIndex] = true
            require(b.outerQbid >= -1 && b.outerQbid < nb) { "unit ${image.unitId}: block $q names outer qbid ${b.outerQbid}, not -1 or a qbid of the table" }
            val bytes = UnitCodec.encode(BlockRecord.serializer(), b.record)
            blockRows[q * 4] = records.size(); blockRows[q * 4 + 1] = bytes.size
            blockRows[q * 4 + 2] = b.programIndex; blockRows[q * 4 + 3] = b.outerQbid
            records.write(bytes)
        }
        // programs, raw UTF-8 back to back
        val programs = ByteArrayOutputStream(1 shl 16)
        val programRows = IntArray(np * 4)
        var slot = 0
        for (i in 0 until np) {
            val bytes = image.programs[i].toByteArray(StandardCharsets.UTF_8)
            programRows[i * 4] = programs.size(); programRows[i * 4 + 1] = bytes.size
            programRows[i * 4 + 2] = slot; programRows[i * 4 + 3] = image.dispatchCounts[i]
            slot += image.dispatchCounts[i]
            programs.write(bytes)
        }
        val nslots = slot
        // dispatch slots: empty unless the image fills one
        val dispatch = ByteArrayOutputStream()
        val slotRows = IntArray(nslots * 2)
        for ((s, bytes) in image.dispatchSlots) {
            require(s in 0 until nslots) { "unit ${image.unitId}: dispatch slot $s of $nslots" }
            slotRows[s * 2] = dispatch.size(); slotRows[s * 2 + 1] = bytes.size
            dispatch.write(bytes)
        }
        val header = UnitHeader(image.unitId, image.hll, image.scHandle, image.scDesc,
            image.serializedCodeRefCount, image.mainlineQbid, image.entryQbid, image.deserializeQbid, image.loadQbid,
            nb, np, nslots,
            image.blocks.map { it?.name ?: "" }, image.blocks.map { it?.cuid },
            image.nested.keys.toList())
        val headerBytes = UnitCodec.encode(UnitHeader.serializer(), header)
        val index = ByteBuffer.allocate(12 + headerBytes.size + 4 * (blockRows.size + programRows.size + slotRows.size))
            .order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(UnitStore.MAGIC).putInt(UnitStore.VERSION).putInt(headerBytes.size).put(headerBytes)
        for (v in blockRows) index.putInt(v)
        for (v in programRows) index.putInt(v)
        for (v in slotRows) index.putInt(v)
        return Encoded(index.array(), records.toByteArray(), programs.toByteArray(), dispatch.toByteArray())
    }
}
