package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** The stored entries of a zip, by name: data offset and size within the
 *  buffer. Reads the end-of-central-directory record and the central
 *  directory only; refuses any entry that is not STORED, since a slice
 *  of a deflated entry is not its data. Zip64 is not supported (no unit
 *  artifact approaches 4 GB). */
internal object ZipDirectory {
    /** [crc] is the entry's CRC32 as the central directory records it: the
     *  unit loader's stamp for the SC an entry carries (DispatchSlot). */
    class Entry(@JvmField val offset: Int, @JvmField val size: Int, @JvmField val crc: Int)

    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50

    fun read(buf0: ByteBuffer, name: String): LinkedHashMap<String, Entry> {
        val buf = buf0.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val end = buf.limit()
        var eocd = -1
        var p = end - 22
        val floor = maxOf(0, end - 22 - 0xFFFF)
        while (p >= floor) { if (buf.getInt(p) == EOCD_SIG) { eocd = p; break }; p-- }
        if (eocd < 0) throw IllegalStateException("unit artifact $name: no end-of-central-directory record")
        val count = buf.getShort(eocd + 10).toInt() and 0xFFFF
        var cen = buf.getInt(eocd + 16)
        val out = LinkedHashMap<String, Entry>(count * 2)
        repeat(count) {
            if (cen + 46 > end || buf.getInt(cen) != CEN_SIG)
                throw IllegalStateException("unit artifact $name: bad central directory entry at $cen")
            val method = buf.getShort(cen + 10).toInt() and 0xFFFF
            val csize = buf.getInt(cen + 20); val usize = buf.getInt(cen + 24)
            val crc = buf.getInt(cen + 16)
            val nameLen = buf.getShort(cen + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(cen + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(cen + 32).toInt() and 0xFFFF
            val loc = buf.getInt(cen + 42)
            val entryName = StandardCharsets.UTF_8.decode(buf.slice(cen + 46, nameLen)).toString()
            if (method != 0 || csize != usize)
                throw IllegalStateException("unit artifact $name: entry $entryName is not stored (method $method)")
            if (loc + 30 > end || buf.getInt(loc) != LOC_SIG)
                throw IllegalStateException("unit artifact $name: entry $entryName has a bad local header at $loc")
            val locName = buf.getShort(loc + 26).toInt() and 0xFFFF
            val locExtra = buf.getShort(loc + 28).toInt() and 0xFFFF
            val data = loc + 30 + locName + locExtra
            if (data + usize > end)
                throw IllegalStateException("unit artifact $name: entry $entryName runs past the end ($data + $usize > $end)")
            out[entryName] = Entry(data, usize, crc)
            cen += 46 + nameLen + extraLen + commentLen
        }
        return out
    }
}
