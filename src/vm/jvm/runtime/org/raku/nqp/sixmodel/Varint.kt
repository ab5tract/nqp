package org.raku.nqp.sixmodel

import java.nio.ByteBuffer

/**
 * LEB128 on a ByteBuffer, for serialization format 12 (milestone 8,
 * Phase C): indexes, counts and offsets unsigned; `writeInt`'s longs
 * zigzag-signed so small negatives stay one byte. Ten bytes hold 64
 * bits; an eleventh continuation byte is corruption.
 */
object Varint {
    const val MAX_BYTES = 10

    @JvmStatic
    fun writeUnsigned(b: ByteBuffer, v: Long) {
        var x = v
        while ((x and 0x7FL.inv()) != 0L) {
            b.put(((x and 0x7FL) or 0x80L).toByte())
            x = x ushr 7
        }
        b.put(x.toByte())
    }

    @JvmStatic
    fun writeUnsigned(b: ByteBuffer, v: Int) = writeUnsigned(b, v.toLong() and 0xFFFFFFFFL)

    @JvmStatic
    fun readUnsigned(b: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = b.get().toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
            if (shift >= 7 * MAX_BYTES) throw RuntimeException("varint longer than $MAX_BYTES bytes")
        }
    }

    @JvmStatic
    fun readUnsignedInt(b: ByteBuffer): Int {
        val v = readUnsigned(b)
        if (v < 0 || v > 0xFFFFFFFFL) throw RuntimeException("varint does not fit 32 bits: $v")
        return v.toInt()
    }

    @JvmStatic
    fun writeSigned(b: ByteBuffer, v: Long) = writeUnsigned(b, (v shl 1) xor (v shr 63))

    @JvmStatic
    fun readSigned(b: ByteBuffer): Long {
        val u = readUnsigned(b)
        return (u ushr 1) xor -(u and 1L)
    }
}
