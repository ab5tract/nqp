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

    /** True when the bytes are a zip whose first entries include unit.meta.
     *  A class file (0xCAFEBABE) and a class-road jar answer false. */
    @JvmStatic
    fun isUnit(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) { if (e.name == META) return true; e = z.nextEntry }
                false
            }
        } catch (t: Exception) { false }
    }

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
