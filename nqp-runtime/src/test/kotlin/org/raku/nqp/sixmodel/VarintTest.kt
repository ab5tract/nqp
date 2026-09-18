package org.raku.nqp.sixmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VarintTest {
    private fun buf() = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)

    private fun unsignedRoundTrip(v: Long, bytes: Int) {
        val b = buf()
        Varint.writeUnsigned(b, v)
        assertEquals(bytes, b.position(), "size of $v")
        b.flip()
        assertEquals(v, Varint.readUnsigned(b), "value $v")
        assertEquals(bytes, b.position(), "consumed for $v")
    }

    private fun signedRoundTrip(v: Long, bytes: Int) {
        val b = buf()
        Varint.writeSigned(b, v)
        assertEquals(bytes, b.position(), "size of $v")
        b.flip()
        assertEquals(v, Varint.readSigned(b), "value $v")
    }

    @Test fun `unsigned edges`() {
        unsignedRoundTrip(0L, 1)
        unsignedRoundTrip(127L, 1)
        unsignedRoundTrip(128L, 2)
        unsignedRoundTrip(16383L, 2)
        unsignedRoundTrip(16384L, 3)
        unsignedRoundTrip((276157L shl 1) or 1L, 3)   // CORE.c's largest packed own-SC object reference
        unsignedRoundTrip(1L shl 32, 5)
        unsignedRoundTrip(Long.MAX_VALUE, 9)
        unsignedRoundTrip(-1L, 10)                    // all 64 bits set
    }

    @Test fun `signed edges`() {
        signedRoundTrip(0L, 1)
        signedRoundTrip(-1L, 1)
        signedRoundTrip(63L, 1)
        signedRoundTrip(-64L, 1)
        signedRoundTrip(64L, 2)
        signedRoundTrip(-65L, 2)
        signedRoundTrip(Long.MAX_VALUE, 10)
        signedRoundTrip(Long.MIN_VALUE, 10)
    }

    @Test fun `int form`() {
        val b = buf()
        Varint.writeUnsigned(b, 300)
        b.flip()
        assertEquals(300, Varint.readUnsignedInt(b))
    }

    @Test fun `an eleven byte run is corruption`() {
        val b = buf()
        repeat(11) { b.put(0x80.toByte()) }
        b.flip()
        assertFailsWith<RuntimeException> { Varint.readUnsigned(b) }
    }
}
