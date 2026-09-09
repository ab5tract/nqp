package org.raku.nqp.runtime.unit

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The envelope: a zip with fixed entry names and no class entries. */
object UnitZip {
    const val META = "unit.meta"
    const val PROGRAMS = "unit.programs"
    const val SERIALIZED = "unit.serialized.lz4"
    const val NESTED_DIR = "nested/"
    private const val NESTED_META = ".meta"
    private const val NESTED_PROGRAMS = ".programs"

    /** unit.meta is always the first entry written -- isUnit's sniff
     *  depends on this and reads only the first local file header. */
    @JvmStatic
    fun write(r: UnitRecord, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.setLevel(1)
            put(z, META, UnitFormat.writeMeta(r.meta))
            put(z, PROGRAMS, UnitFormat.writePrograms(r.programs))
            r.serialized?.let { put(z, SERIALIZED, UnitFormat.compress(it)) }
            for ((id, n) in r.nested) {
                put(z, NESTED_DIR + id + NESTED_META, UnitFormat.writeMeta(n.meta))
                put(z, NESTED_DIR + id + NESTED_PROGRAMS, UnitFormat.writePrograms(n.programs))
            }
        }
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
    }

    /** True when the bytes are a zip whose FIRST entry is unit.meta. write()
     *  above always writes unit.meta first, so the sniff reads only the
     *  first local file header -- signature, name length, name -- rather
     *  than walking the whole zip through a ZipInputStream. A class file
     *  (0xCAFEBABE) and a class-road jar (whose first entry is a .class,
     *  never unit.meta) both answer false, cheaply. */
    @JvmStatic
    fun isUnit(bytes: ByteArray): Boolean {
        if (bytes.size < 30) return false
        if (bytes[0] != LOCAL_SIG_0 || bytes[1] != LOCAL_SIG_1 ||
            bytes[2] != LOCAL_SIG_2 || bytes[3] != LOCAL_SIG_3) return false
        val nameLen = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
        if (nameLen != META.length || bytes.size < 30 + nameLen) return false
        for (i in 0 until nameLen)
            if ((bytes[30 + i].toInt() and 0xFF) != META[i].code) return false
        return true
    }

    /** Same sniff as [isUnit], reading the 30+9 header bytes via absolute
     *  gets on a duplicate -- no array copy, and the original buffer's
     *  position/limit/mark are left untouched. */
    @JvmStatic
    fun isUnit(buffer: ByteBuffer): Boolean {
        val dup = buffer.duplicate()
        val base = dup.position()
        val remaining = dup.remaining()
        if (remaining < 30) return false
        if (dup.get(base) != LOCAL_SIG_0 || dup.get(base + 1) != LOCAL_SIG_1 ||
            dup.get(base + 2) != LOCAL_SIG_2 || dup.get(base + 3) != LOCAL_SIG_3) return false
        val nameLen = (dup.get(base + 26).toInt() and 0xFF) or ((dup.get(base + 27).toInt() and 0xFF) shl 8)
        if (nameLen != META.length || remaining < 30 + nameLen) return false
        for (i in 0 until nameLen)
            if ((dup.get(base + 30 + i).toInt() and 0xFF) != META[i].code) return false
        return true
    }

    // The zip local file header signature "PK\x03\x04", byte by byte.
    private const val LOCAL_SIG_0: Byte = 0x50
    private const val LOCAL_SIG_1: Byte = 0x4B
    private const val LOCAL_SIG_2: Byte = 0x03
    private const val LOCAL_SIG_3: Byte = 0x04

    @JvmStatic
    fun read(bytes: ByteArray): UnitRecord {
        var meta: UnitMeta? = null
        var programs: Array<String>? = null
        var serialized: ByteArray? = null
        val nestedMeta = HashMap<String, UnitMeta>()
        val nestedProgs = HashMap<String, Array<String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val data = z.readAllBytes()
                when {
                    e.name == META -> meta = UnitFormat.readMeta(ByteBuffer.wrap(data))
                    e.name == PROGRAMS -> programs = UnitFormat.readPrograms(ByteBuffer.wrap(data))
                    e.name == SERIALIZED -> serialized = UnitFormat.decompress(data)
                    e.name.startsWith(NESTED_DIR) && e.name.endsWith(NESTED_META) ->
                        nestedMeta[e.name.removePrefix(NESTED_DIR).removeSuffix(NESTED_META)] = UnitFormat.readMeta(ByteBuffer.wrap(data))
                    e.name.startsWith(NESTED_DIR) && e.name.endsWith(NESTED_PROGRAMS) ->
                        nestedProgs[e.name.removePrefix(NESTED_DIR).removeSuffix(NESTED_PROGRAMS)] = UnitFormat.readPrograms(ByteBuffer.wrap(data))
                    else -> throw IllegalStateException("unit artifact holds an unexpected entry: ${e.name}")
                }
                e = z.nextEntry
            }
        }
        val m = meta ?: throw IllegalStateException("unit artifact lacks $META")
        val p = programs ?: throw IllegalStateException("unit artifact ${m.unitId} lacks $PROGRAMS")
        val nested = HashMap<String, UnitRecord>()
        for (id in m.nestedIds) {
            val nm = nestedMeta[id] ?: throw IllegalStateException("unit ${m.unitId} names nested unit $id but carries no $NESTED_DIR$id$NESTED_META")
            val np = nestedProgs[id] ?: throw IllegalStateException("unit ${m.unitId} names nested unit $id but carries no $NESTED_DIR$id$NESTED_PROGRAMS")
            nested[id] = UnitRecord(nm, np, null, mapOf())
        }
        for (b in m.blocks) if (b != null && (b.programIndex < 0 || b.programIndex >= p.size))
            throw IllegalStateException("unit ${m.unitId}: block ${b.name} names program ${b.programIndex} of ${p.size}")
        for ((q, b) in m.blocks.withIndex()) if (b != null && b.outerQbid >= m.blocks.size)
            throw IllegalStateException("unit ${m.unitId}: block $q names outer qbid ${b.outerQbid} beyond the table")
        if (m.serializedCodeRefCount > m.blocks.size)
            throw IllegalStateException("unit ${m.unitId}: ${m.serializedCodeRefCount} serialized code refs, table of ${m.blocks.size}")
        return UnitRecord(m, p, serialized, nested)
    }
}
