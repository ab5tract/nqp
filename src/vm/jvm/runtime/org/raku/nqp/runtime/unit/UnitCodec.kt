package org.raku.nqp.runtime.unit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * The unit artifact's record codec: kotlinx.serialization records over a
 * binary layout of our own. Fixed-width little-endian ints, a byte per
 * boolean, an Int byte length before UTF-8 text, a mark byte before a
 * nullable value, an Int size before a collection; no field tags. A
 * record therefore decodes from a slice that starts at its first byte,
 * which is what unit.index's (offset, length) tables address.
 *
 * AbstractEncoder/AbstractDecoder are kotlinx's experimental surface for
 * a custom format; nothing else experimental is used, and the format
 * modules (ProtoBuf, CBOR) stay out (milestone 7 Phase B, ruling 11).
 */
@OptIn(ExperimentalSerializationApi::class)
object UnitCodec {
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val out = ByteArrayOutputStream(256)
        Writer(out).encodeSerializableValue(serializer, value)
        return out.toByteArray()
    }

    /** Decodes from buf.position() forward, on a duplicate: the caller's
     *  buffer keeps its position, limit and order. */
    fun <T> decode(serializer: DeserializationStrategy<T>, buf: ByteBuffer): T =
        Reader(buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)).decodeSerializableValue(serializer)

    private class Writer(private val out: ByteArrayOutputStream) : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        private fun int(v: Int) { scratch.clear(); scratch.putInt(v); out.write(scratch.array(), 0, 4) }
        override fun encodeInt(value: Int) = int(value)
        override fun encodeLong(value: Long) { scratch.clear(); scratch.putLong(value); out.write(scratch.array(), 0, 8) }
        override fun encodeBoolean(value: Boolean) = out.write(if (value) 1 else 0)
        override fun encodeByte(value: Byte) = out.write(value.toInt())
        override fun encodeShort(value: Short) = throw UnsupportedOperationException("unit codec: no Short")
        override fun encodeChar(value: Char) = throw UnsupportedOperationException("unit codec: no Char")
        override fun encodeFloat(value: Float) = throw UnsupportedOperationException("unit codec: no Float")
        override fun encodeDouble(value: Double) = throw UnsupportedOperationException("unit codec: no Double")
        override fun encodeString(value: String) {
            val b = value.toByteArray(StandardCharsets.UTF_8); int(b.size); out.write(b, 0, b.size)
        }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = int(index)
        override fun encodeNull() = out.write(0)
        override fun encodeNotNullMark() = out.write(1)
        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            int(collectionSize); return this
        }
    }

    private class Reader(private val buf: ByteBuffer) : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        private var elementIndex = 0

        override fun decodeInt(): Int = buf.getInt()
        override fun decodeLong(): Long = buf.getLong()
        override fun decodeBoolean(): Boolean = buf.get() != 0.toByte()
        override fun decodeByte(): Byte = buf.get()
        override fun decodeShort(): Short = throw UnsupportedOperationException("unit codec: no Short")
        override fun decodeChar(): Char = throw UnsupportedOperationException("unit codec: no Char")
        override fun decodeFloat(): Float = throw UnsupportedOperationException("unit codec: no Float")
        override fun decodeDouble(): Double = throw UnsupportedOperationException("unit codec: no Double")
        override fun decodeString(): String {
            val n = buf.getInt()
            val slice = buf.slice(buf.position(), n)
            buf.position(buf.position() + n)
            return StandardCharsets.UTF_8.decode(slice).toString()
        }
        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = buf.getInt()
        override fun decodeNotNullMark(): Boolean = buf.get() != 0.toByte()
        override fun decodeNull(): Nothing? = null
        override fun decodeSequentially(): Boolean = true
        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = buf.getInt()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            if (elementIndex < descriptor.elementsCount) elementIndex++ else CompositeDecoder.DECODE_DONE
        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = Reader(buf)
    }
}
