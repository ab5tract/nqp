package org.raku.nqp.runtime.unit

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipOutputStream

/**
 * Rewrites the dispatch slots of a unit artifact in place (milestone 7
 * Phase C, the training run): for every unit in the file whose entry
 * prefix is named ("unit", "nested/<id>"), the named slots get their
 * bytes, every other slot keeps what it had, the .index slot rows are
 * repointed and the .dispatch entry rebuilt; every other entry is copied
 * byte for byte. The result goes to <path>.tmp and is renamed over the
 * original, so a process that has the old file mapped keeps reading the
 * old inode.
 */
object UnitDispatchWriter {
    fun rewrite(path: String, slots: Map<String, Map<Int, ByteArray>>) {
        val file = File(path)
        val whole = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val dir = ZipDirectory.read(whole, path)
        val patched = HashMap<String, Pair<ByteArray, ByteArray>>()   // prefix -> (index, dispatch)
        for ((prefix, newSlots) in slots) {
            val index = dir["$prefix.index"] ?: throw IllegalArgumentException("$path: no $prefix.index entry to patch")
            val dispatch = dir["$prefix.dispatch"] ?: throw IllegalArgumentException("$path: no $prefix.dispatch entry to patch")
            patched[prefix] = patch(path, prefix, whole.slice(index.offset, index.size).order(ByteOrder.LITTLE_ENDIAN),
                whole.slice(dispatch.offset, dispatch.size), newSlots)
        }
        val out = ByteArrayOutputStream(whole.capacity() + (1 shl 16))
        ZipOutputStream(out).use { z ->
            z.setMethod(ZipOutputStream.STORED)
            for ((name, e) in dir) {
                val prefix = when {
                    name.endsWith(".index") -> name.removeSuffix(".index")
                    name.endsWith(".dispatch") -> name.removeSuffix(".dispatch")
                    else -> null
                }
                val p = prefix?.let { patched[it] }
                val bytes = when {
                    p != null && name.endsWith(".index") -> p.first
                    p != null -> p.second
                    else -> ByteArray(e.size).also { whole.slice(e.offset, e.size).get(it) }
                }
                UnitImageWriter.put(z, name, bytes)
            }
        }
        val tmp = File(path + ".tmp")
        tmp.writeBytes(out.toByteArray())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The new (index, dispatch) of one unit: fixed-width slot rows in the
     *  index repointed into a dispatch entry rebuilt slot by slot. */
    private fun patch(path: String, prefix: String, index: ByteBuffer, oldDispatch: ByteBuffer,
                      newSlots: Map<Int, ByteArray>): Pair<ByteArray, ByteArray> {
        val headerLen = index.getInt(8)
        val header = UnitCodec.decode(UnitHeader.serializer(), index.slice(12, headerLen))
        val slotTable = 12 + headerLen + 16 * header.blockCount + 16 * header.programCount
        val n = header.dispatchSlotCount
        for (s in newSlots.keys) require(s in 0 until n) { "$path: $prefix dispatch slot $s of $n" }
        val bytes = ByteArray(index.remaining()).also { index.duplicate().get(it) }
        val idx = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dispatch = ByteArrayOutputStream(oldDispatch.remaining() + newSlots.values.sumOf { it.size })
        for (s in 0 until n) {
            val row = slotTable + 8 * s
            val slot = newSlots[s] ?: run {
                val off = idx.getInt(row); val len = idx.getInt(row + 4)
                if (len == 0) null
                else {
                    require(off >= 0 && off + len <= oldDispatch.remaining()) { "$path: $prefix dispatch slot $s at $off+$len past ${oldDispatch.remaining()}" }
                    ByteArray(len).also { oldDispatch.slice(off, len).get(it) }
                }
            }
            if (slot == null) { idx.putInt(row, 0); idx.putInt(row + 4, 0); continue }
            idx.putInt(row, dispatch.size()); idx.putInt(row + 4, slot.size)
            dispatch.write(slot)
        }
        return bytes to dispatch.toByteArray()
    }
}
