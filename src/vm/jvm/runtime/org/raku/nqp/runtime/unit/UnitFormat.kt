package org.raku.nqp.runtime.unit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4CompressorWithLength
import net.jpountz.lz4.LZ4DecompressorWithLength

/**
 * The unit artifact's binary sections. Little-endian, fixed-width ints,
 * strings as a byte length (-1 for null) plus UTF-8 bytes: framing is by
 * BYTE, never by grapheme (the .codeprograms.lz4 sidecar framed by
 * nqp::chars and paid for it with a reader bug and an O(n^2) fix).
 */
object UnitFormat {
    const val MAGIC = 0x5550514E            // "NQPU" as little-endian bytes
    const val VERSION = 1

    private val lz4c = LZ4CompressorWithLength(LZ4Factory.safeInstance().highCompressor(8))
    private val lz4d = LZ4DecompressorWithLength(LZ4Factory.safeInstance().fastDecompressor())

    private class Out {
        val bytes = ByteArrayOutputStream()
        fun int(v: Int) { bytes.write(v); bytes.write(v ushr 8); bytes.write(v ushr 16); bytes.write(v ushr 24) }
        fun long(v: Long) { int(v.toInt()); int((v ushr 32).toInt()) }
        fun bool(v: Boolean) = bytes.write(if (v) 1 else 0)
        fun str(s: String?) {
            if (s == null) { int(-1); return }
            val b = s.toByteArray(Charsets.UTF_8); int(b.size); bytes.write(b)
        }
        fun strs(a: Array<String>?) { if (a == null) int(-1) else { int(a.size); for (s in a) str(s) } }
        fun ints(a: IntArray?) { if (a == null) int(-1) else { int(a.size); for (v in a) int(v) } }
        fun longs(a: LongArray) { int(a.size); for (v in a) long(v) }
        fun bytesOf(a: ByteArray) { int(a.size); bytes.write(a) }
    }

    private class In(val bb: ByteBuffer) {
        init { bb.order(ByteOrder.LITTLE_ENDIAN) }
        fun int() = bb.getInt()
        fun long() = bb.getLong()
        fun bool() = bb.get().toInt() != 0
        fun str(): String? { val n = int(); if (n < 0) return null; val b = ByteArray(n); bb.get(b); return String(b, Charsets.UTF_8) }
        fun strs(): Array<String>? { val n = int(); if (n < 0) return null; return Array(n) { str()!! } }
        fun ints(): IntArray? { val n = int(); if (n < 0) return null; return IntArray(n) { int() } }
        fun longs(): LongArray { val n = int(); return LongArray(n) { long() } }
        fun bytesOf(): ByteArray { val n = int(); val b = ByteArray(n); bb.get(b); return b }
    }

    @JvmStatic
    fun writeMeta(m: UnitMeta): ByteArray {
        val o = Out()
        o.int(MAGIC); o.int(VERSION)
        o.str(m.unitId); o.str(m.hll); o.str(m.scHandle); o.str(m.scDesc)
        o.int(m.serializedCodeRefCount)
        o.int(m.mainlineQbid); o.int(m.entryQbid); o.int(m.deserializeQbid); o.int(m.loadQbid)
        o.int(m.callSites.size)
        for (cs in m.callSites) { o.bytesOf(cs.flags); o.strs(cs.names) }
        o.int(m.blocks.size)
        for (b in m.blocks) {
            if (b == null) { o.bool(false); continue }
            o.bool(true)
            o.str(b.name); o.str(b.cuid); o.int(b.outerQbid)
            o.strs(b.oLex); o.strs(b.iLex); o.strs(b.nLex); o.strs(b.sLex)
            o.longs(b.handlers); o.bool(b.hasExitHandler); o.bool(b.isThunk)
            o.str(b.sourceFile); o.int(b.sourceLine); o.int(b.sourceLineDelta)
            o.ints(b.sectionRaw); o.ints(b.sectionLine); o.strs(b.sectionFile)
            o.int(b.programIndex)
        }
        o.int(m.staticLexValues.size)
        for (v in m.staticLexValues) { o.int(v.qbid); o.str(v.name); o.str(v.scHandle); o.int(v.scIdx); o.int(v.flags) }
        o.strs(m.nestedIds.toTypedArray())
        return o.bytes.toByteArray()
    }

    @JvmStatic
    fun readMeta(bytes: ByteBuffer): UnitMeta {
        val i = In(bytes)
        check(i.int() == MAGIC) { "not a unit artifact: bad magic in unit.meta" }
        val version = i.int()
        check(version == VERSION) { "unit.meta format version $version, this runtime reads version $VERSION" }
        val unitId = i.str()!!; val hll = i.str()!!; val scHandle = i.str(); val scDesc = i.str()
        val count = i.int()
        val mainline = i.int(); val entry = i.int(); val deser = i.int(); val load = i.int()
        val callSites = List(i.int()) { CallSiteRec(i.bytesOf(), i.strs()) }
        val blocks = arrayOfNulls<BlockRec>(i.int())
        for (q in blocks.indices) {
            if (!i.bool()) continue
            blocks[q] = BlockRec(
                i.str()!!, i.str(), i.int(),
                i.strs()!!, i.strs()!!, i.strs()!!, i.strs()!!,
                i.longs(), i.bool(), i.bool(),
                i.str(), i.int(), i.int(),
                i.ints(), i.ints(), i.strs(),
                i.int(),
            )
        }
        val lex = List(i.int()) { LexValueRec(i.int(), i.str()!!, i.str()!!, i.int(), i.int()) }
        val nested = i.strs()!!.toList()
        return UnitMeta(unitId, hll, scHandle, scDesc, count, mainline, entry, deser, load, callSites, blocks, lex, nested)
    }

    @JvmStatic
    fun writePrograms(programs: Array<String>): ByteArray {
        val o = Out()
        o.int(programs.size)
        for (p in programs) o.bytesOf(p.toByteArray(Charsets.UTF_8))
        return lz4c.compress(o.bytes.toByteArray())
    }

    @JvmStatic
    fun readPrograms(lz4: ByteBuffer): Array<String> {
        val raw = ByteArray(lz4.remaining()); lz4.get(raw)
        val i = In(ByteBuffer.wrap(lz4d.decompress(raw)))
        return Array(i.int()) { String(i.bytesOf(), Charsets.UTF_8) }
    }

    @JvmStatic
    fun compress(bytes: ByteArray): ByteArray = lz4c.compress(bytes)

    @JvmStatic
    fun decompress(bytes: ByteArray): ByteArray = lz4d.decompress(bytes)
}
