package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A unit artifact, opened: one read-only mapping (or one heap buffer)
 * sliced per stored zip entry, the index decoded, everything else read
 * on demand by index -- a block's record when its body is first needed,
 * a program when it is first materialized, a dispatch slot when its
 * site first misses (Phase C). Immutable and shared: the eval server
 * keeps one per path across runs (UnitLoader.stores). A file replaced
 * on disk while mapped can fault the process; the server is restarted
 * after a rebuild, as it already had to be.
 */
class UnitStore private constructor(
    @JvmField val name: String,
    private val entries: Map<String, ByteBuffer>,      // each slice: position 0, LITTLE_ENDIAN
    private val prefix: String,                         // "unit" or "nested/<id>"
) {
    companion object {
        const val INDEX = "unit.index"; const val RECORDS = "unit.records"; const val PROGRAMS = "unit.programs"
        const val SERIALIZED = "unit.serialized"; const val DISPATCH = "unit.dispatch"; const val NESTED_DIR = "nested/"
        const val MAGIC = 0x5550514E   // "NQPU"
        const val VERSION = 2
        private const val LOC_SIG = 0x04034b50

        @JvmStatic
        fun open(path: String): UnitStore {
            val mapped = FileChannel.open(Path.of(path), StandardOpenOption.READ).use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
            return open(mapped, path)
        }

        @JvmStatic
        fun open(bytes: ByteBuffer, name: String): UnitStore {
            val whole = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val dir = ZipDirectory.read(whole, name)
            val slices = HashMap<String, ByteBuffer>(dir.size * 2)
            for ((n, e) in dir) slices[n] = whole.slice(e.offset, e.size).order(ByteOrder.LITTLE_ENDIAN)
            return UnitStore(name, slices, "unit")
        }

        /** True when the first local file header names unit.index: the
         *  writer puts it first, so the sniff reads 30 + 10 bytes. */
        @JvmStatic
        fun isUnit(buffer: ByteBuffer): Boolean {
            val d = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val base = d.position()
            if (d.remaining() < 30 + INDEX.length || d.getInt(base) != LOC_SIG) return false
            val nameLen = d.getShort(base + 26).toInt() and 0xFFFF
            if (nameLen != INDEX.length) return false
            for (i in INDEX.indices) if ((d.get(base + 30 + i).toInt() and 0xFF) != INDEX[i].code) return false
            return true
        }
    }

    private val index: ByteBuffer = entries["$prefix.index"]
        ?: throw IllegalStateException("unit artifact $name: no $prefix.index entry")
    @JvmField val header: UnitHeader
    private val tables: Int          // byte offset of the block table within the index
    private val programTable: Int
    private val slotTable: Int
    private val nestedStores = ConcurrentHashMap<String, UnitStore>()

    init {
        if (index.remaining() < 12) throw IllegalStateException("unit artifact $name: $prefix.index is ${index.remaining()} bytes")
        val magic = index.getInt(0); val version = index.getInt(4)
        if (magic != MAGIC) throw IllegalStateException("unit artifact $name: bad magic ${Integer.toHexString(magic)}")
        if (version != VERSION) throw IllegalStateException("unit artifact $name: version $version, this runtime reads $VERSION")
        val headerLen = index.getInt(8)
        header = try { UnitCodec.decode(UnitHeader.serializer(), index.slice(12, headerLen)) }
                 catch (e: Exception) { throw IllegalStateException("unit artifact $name: header does not decode: ${e.message}", e) }
        tables = 12 + headerLen
        programTable = tables + 16 * header.blockCount
        slotTable = programTable + 16 * header.programCount
        val need = slotTable + 8 * header.dispatchSlotCount
        if (index.remaining() < need) throw IllegalStateException("unit artifact $name: $prefix.index holds ${index.remaining()} bytes, tables need $need")
        if (header.names.size != header.blockCount || header.cuids.size != header.blockCount)
            throw IllegalStateException("unit artifact $name: ${header.names.size} names / ${header.cuids.size} cuids for ${header.blockCount} blocks")
        if (header.serializedCodeRefCount > header.blockCount)
            throw IllegalStateException("unit ${header.unitId}: ${header.serializedCodeRefCount} serialized code refs, table of ${header.blockCount}")
    }

    val blockCount: Int get() = header.blockCount
    val programCount: Int get() = header.programCount

    private fun blockRow(qbid: Int, field: Int): Int {
        if (qbid < 0 || qbid >= header.blockCount)
            throw IllegalStateException("unit ${header.unitId}: $prefix.records index $qbid of ${header.blockCount}")
        return index.getInt(tables + 16 * qbid + 4 * field)
    }
    fun programIndex(qbid: Int): Int = blockRow(qbid, 2)
    fun outerQbid(qbid: Int): Int = blockRow(qbid, 3)

    fun blockRecord(qbid: Int): BlockRecord? {
        val len = blockRow(qbid, 1)
        if (len == 0) return null
        val off = blockRow(qbid, 0)
        val records = entry(RECORDS) ?: throw IllegalStateException("unit ${header.unitId}: no $prefix.records entry")
        if (off + len > records.remaining())
            throw IllegalStateException("unit ${header.unitId}: $prefix.records index $qbid at $off+$len past ${records.remaining()}")
        return try { UnitCodec.decode(BlockRecord.serializer(), records.slice(off, len)) }
               catch (e: Exception) { throw IllegalStateException("unit ${header.unitId}: $prefix.records index $qbid does not decode: ${e.message}", e) }
    }

    private fun programRow(idx: Int, field: Int): Int {
        if (idx < 0 || idx >= header.programCount)
            throw IllegalStateException("unit ${header.unitId}: $prefix.programs index $idx of ${header.programCount}")
        return index.getInt(programTable + 16 * idx + 4 * field)
    }
    fun program(idx: Int): String {
        val off = programRow(idx, 0); val len = programRow(idx, 1)
        val programs = entry(PROGRAMS) ?: throw IllegalStateException("unit ${header.unitId}: no $prefix.programs entry")
        if (off + len > programs.remaining())
            throw IllegalStateException("unit ${header.unitId}: $prefix.programs index $idx at $off+$len past ${programs.remaining()}")
        return StandardCharsets.UTF_8.decode(programs.slice(off, len)).toString()
    }

    fun dispatchSlotCount(programIndex: Int): Int =
        if (programIndex < 0 || programIndex >= header.programCount) 0 else programRow(programIndex, 3)
    fun dispatchSlot(programIndex: Int, ordinal: Int): ByteBuffer? {
        if (programIndex < 0 || programIndex >= header.programCount) return null
        if (ordinal < 0 || ordinal >= programRow(programIndex, 3)) return null
        val slot = programRow(programIndex, 2) + ordinal
        val len = index.getInt(slotTable + 8 * slot + 4)
        if (len == 0) return null
        val off = index.getInt(slotTable + 8 * slot)
        val dispatch = entry(DISPATCH) ?: throw IllegalStateException("unit ${header.unitId}: no $prefix.dispatch entry")
        if (off + len > dispatch.remaining())
            throw IllegalStateException("unit ${header.unitId}: $prefix.dispatch slot $slot at $off+$len past ${dispatch.remaining()}")
        return dispatch.slice(off, len).order(ByteOrder.LITTLE_ENDIAN)
    }

    val serialized: ByteBuffer? get() = entry(SERIALIZED)

    /** A raw entry of this unit (unit.index ... unit.dispatch), a fresh
     *  duplicate at position 0. */
    fun entry(unitEntryName: String): ByteBuffer? =
        entries[if (prefix == "unit") unitEntryName else prefix + unitEntryName.removePrefix("unit")]?.duplicate()

    fun nested(id: String): UnitStore? {
        if (id !in header.nestedIds) return null
        return nestedStores.computeIfAbsent(id) { UnitStore(name, entries, NESTED_DIR + it) }
    }
}
