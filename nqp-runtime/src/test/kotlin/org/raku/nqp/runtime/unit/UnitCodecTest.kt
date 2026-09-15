package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.serialization.Serializable

class UnitCodecTest {
    @Serializable
    data class Probe(
        val i: Int, val l: Long, val b: Boolean, val s: String, val ns: String?,
        val list: List<String>, val nlist: List<String>?, val ints: IntArray, val longs: LongArray,
        val nested: List<Inner>,
    )
    @Serializable data class Inner(val name: String, val idx: Int)

    /** Nested, not local to the test function: the serialization compiler
     *  plugin does not process local classes. */
    @Serializable data class N(val s: String?)

    private val sample = Probe(
        -7, 1L shl 40, true, "gr\u00fc\u00dfe \ud83d\udc2a", null,
        listOf("", "a", "\u0000b"), null, intArrayOf(1, -1, Int.MAX_VALUE), longArrayOf(0L, Long.MIN_VALUE),
        listOf(Inner("x", 1), Inner("", -1)),
    )

    @Test fun roundTrips() {
        val bytes = UnitCodec.encode(Probe.serializer(), sample)
        val back = UnitCodec.decode(Probe.serializer(), ByteBuffer.wrap(bytes))
        assertEquals(sample.i, back.i); assertEquals(sample.l, back.l); assertEquals(sample.b, back.b)
        assertEquals(sample.s, back.s); assertNull(back.ns)
        assertEquals(sample.list, back.list); assertNull(back.nlist)
        assertContentEquals(sample.ints, back.ints); assertContentEquals(sample.longs, back.longs)
        assertEquals(sample.nested, back.nested)
    }

    @Test fun layoutIsLittleEndianAndUntagged() {
        val bytes = UnitCodec.encode(Inner.serializer(), Inner("ab", 0x01020304))
        // "ab": Int length 2 (LE) + 2 bytes; then the Int, LE.
        assertContentEquals(byteArrayOf(2, 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte(), 4, 3, 2, 1), bytes)
    }

    @Test fun decodesFromASliceWithoutMovingTheCallersBuffer() {
        val bytes = UnitCodec.encode(Inner.serializer(), Inner("z", 9))
        val padded = ByteBuffer.allocate(bytes.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        padded.position(5); padded.put(bytes); padded.position(5)
        val back = UnitCodec.decode(Inner.serializer(), padded)
        assertEquals(Inner("z", 9), back)
        assertEquals(5, padded.position())
    }

    @Test fun nullMarkIsOneByte() {
        assertContentEquals(byteArrayOf(0), UnitCodec.encode(N.serializer(), N(null)))
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 0), UnitCodec.encode(N.serializer(), N("")))
    }
}
