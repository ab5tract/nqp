package org.raku.nqp.sixmodel.reprs

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.MalformedInputException
import java.text.Normalizer
import java.util.ArrayList
import org.raku.nqp.runtime.Buffers
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec

class DecoderInstance : SixModelObject() {
    private var charset: Charset? = null
    private var decoder: CharsetDecoder? = null
    private var toDecode: MutableList<ByteBuffer>? = null
    private var decoded: MutableList<CharBuffer>? = null
    private var lineSeps: MutableList<String>? = null
    private var translate_newlines = false

    fun configure(tc: ThreadContext, encoding: String, config: SixModelObject) {
        if (decoder == null) {
            charset = Charset.forName(encoding)
            decoder = charset!!.newDecoder()
            decoded = ArrayList()
            lineSeps = ArrayList()
            lineSeps!!.add("\n")
            lineSeps!!.add("\r\n")
            if (config.exists_key(tc, "translate_newlines") != 0L)
                translate_newlines = config.at_key_boxed(tc, "translate_newlines")!!.get_int(tc) != 0L
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "Decoder already configured")
        }
    }

    fun setLineSeps(tc: ThreadContext, seps: SixModelObject) {
        val prim = seps.st.REPR.get_value_storage_spec(tc, seps.st)!!.boxed_primitive
        if (prim != StorageSpec.BP_STR)
            ExceptionHandling.dieInternal(tc,
                    "Line separators must be provided as an array of native strings")
        lineSeps!!.clear()
        val numSeps = seps.elems(tc)
        for (i in 0 until numSeps) {
            seps.at_pos_native(tc, i)
            lineSeps!!.add(tc.native_s!!)
        }
    }

    @Synchronized
    fun addBytes(tc: ThreadContext, bytes: ByteBuffer) {
        ensureConfigured(tc)
        if (toDecode == null)
            toDecode = ArrayList()
        if (bytes.remaining() > 0) {
            val clone = ByteBuffer.allocate(bytes.capacity())
            bytes.rewind()
            clone.put(bytes)
            bytes.rewind()
            clone.flip()
            toDecode!!.add(clone)
        }
    }

    @Synchronized
    fun maybe_translate_newlines(tc: ThreadContext, str: String): String {
        return if (translate_newlines) str.replace("\r\n", "\n") else str
    }

    @Synchronized
    fun takeChars(tc: ThreadContext, chars: Long, eof: Boolean): String? {
        ensureConfigured(tc)

        if (chars == 0L)
            return ""

        val target = CharBuffer.allocate(chars.toInt() + 1)
        eatDecodedChars(target, chars.toInt() + 1)
        if (target.position().toLong() != chars + 1) {
            try {
                eatUndecodedBytes(target, false)
            }
            catch (e: MalformedInputException) {
                Ops.die_s("Will not decode invalid " + charset, tc)
            }
            catch (e: CharacterCodingException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
        }

        var normalized = Normalizer.normalize(
            decodedBuffer(target),
            Normalizer.Form.NFC)
        normalized = maybe_translate_newlines(tc, normalized)
        if (normalized.length > chars) {
            val result = normalized.substring(0, chars.toInt())
            val remaining = normalized.substring(chars.toInt(), normalized.length)
            if (remaining.length > 0) {
                decoded!!.add(0, CharBuffer.wrap(remaining))
            }
            return result
        }
        else if (normalized.length.toLong() == chars) {
            if (isNormTerminated(normalized))
                return normalized
        }

        if (eof)
            return normalized
        else
            decoded!!.add(CharBuffer.wrap(normalized))
        return null
    }

    @Synchronized
    fun takeAvailableChars(tc: ThreadContext): String {
        ensureConfigured(tc)

        val maxChars = availableDecodedChars() + availableUndecodedBytes()
        val target = CharBuffer.allocate(maxChars)
        eatAllDecodedChars(target)
        try {
            eatUndecodedBytes(target, false)
        }
        catch (e: MalformedInputException) {
            Ops.die_s("Will not decode invalid " + charset, tc)
        }
        catch (e: CharacterCodingException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }

        var normalized = Normalizer.normalize(
            decodedBuffer(target),
            Normalizer.Form.NFC)
        normalized = maybe_translate_newlines(tc, normalized)
        if (normalized.length == 0 || isNormTerminated(normalized))
            return normalized
        val result = normalized.substring(0, normalized.length - 1)
        val last = normalized.substring(normalized.length - 1, normalized.length)
        decoded!!.add(CharBuffer.wrap(last))
        return result
    }

    @Synchronized
    fun takeAllChars(tc: ThreadContext): String {
        ensureConfigured(tc)
        val maxChars = availableDecodedChars() + availableUndecodedBytes()
        val target = CharBuffer.allocate(maxChars)
        eatAllDecodedChars(target)
        if (toDecode != null) {
            if (toDecode!!.size == 0)
                toDecode!!.add(ByteBuffer.allocate(0))
            try {
                eatUndecodedBytes(target, true)
            }
            catch (e: MalformedInputException) {
                Ops.die_s("Will not decode invalid " + charset, tc)
            }
            catch (e: CharacterCodingException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
            decoder!!.flush(target)
            decoder!!.reset()
        }
        val normalized = Normalizer.normalize(decodedBuffer(target), Normalizer.Form.NFC)
        return maybe_translate_newlines(tc, normalized)
    }

    @Synchronized
    fun takeLine(tc: ThreadContext, chomp: Boolean, eof: Boolean): String? {
        ensureConfigured(tc)
        while (true) {
            /* See if we can find the separator in any of the decoded chars. */
            var charsToTake = 0
            for (i in 0 until (decoded?.size ?: 0)) {
                val search = decoded!![i]
                for (j in 0 until search.remaining()) {
                    val c = (search as CharSequence)[j]
                    for (k in 0 until lineSeps!!.size) {
                        val sep = lineSeps!![k]
                        if (sep[0] == c) {
                            if (sep.length == 1 || sepMatchAt(i, j, sep)) {
                                return takeCharsSkipChars(
                                        if (chomp) charsToTake else charsToTake + sep.length,
                                        if (chomp) sep.length else 0)
                            }
                        }
                    }
                    charsToTake++
                }
            }

            /* If there are no more buffers to decode then we're done. */
            if (toDecode == null || toDecode!!.size == 0)
                break

            /* Otherwise decode one of them. */
            val decodee = toDecode!![0]
            val target = CharBuffer.allocate(decodee.limit())

            val result = decoder!!.decode(decodee, target, eof && toDecode!!.size == 1)
            if (result.isMalformed()) {
                /* We encountered malformed input. But it could be that we found enough
                 * valid input to return a whole line. So we should only die if we
                 * didn't get anything in the output buffer. */
                if (target.position() == 0)
                    Ops.die_s("Will not decode invalid " + charset, tc)
            }

            decoded!!.add(decodedBuffer(target))
            if (decodee.remaining() == 0)
                toDecode!!.removeAt(0)
        }

        return if (eof) takeAllChars(tc) else null
    }

    @Synchronized
    fun bytesAvailable(tc: ThreadContext): Long {
        ensureConfigured(tc)
        forceDecodedBackToBytes()
        return availableUndecodedBytes().toLong()
    }

    @Synchronized
    fun takeBytes(tc: ThreadContext, bufType: SixModelObject, lBytes: Long): SixModelObject? {
        val available = bytesAvailable(tc).toInt() // Implicitly forces decoded back to bytes
        if (available < lBytes)
            return null
        val res = bufType.st.REPR.allocate(tc, bufType.st)
        var bytes = lBytes.toInt()
        val resBytes = ByteArray(bytes)
        if (bytes > available)
            bytes = available
        var need = bytes
        while (need > 0) {
            val takeFrom = toDecode!![0]
            val fromAvailable = takeFrom.remaining()
            if (need >= fromAvailable) {
                takeFrom.get(resBytes, bytes - need, fromAvailable)
                need -= fromAvailable
                toDecode!!.removeAt(0)
            }
            else {
                takeFrom.get(resBytes, bytes - need, need)
                need = 0
            }
        }
        Buffers.stashBytes(tc, res, resBytes)
        return res
    }

    private fun sepMatchAt(decStart: Int, charStart: Int, sep: String): Boolean {
        var sepIndex = 0
        var firstBuffer = true
        for (i in decStart until decoded!!.size) {
            val search = decoded!![i]
            for (j in (if (firstBuffer) charStart else 0) until search.remaining()) {
                if ((search as CharSequence)[j] != sep[sepIndex++])
                    return false
                if (sepIndex == sep.length)
                    return true
            }
            firstBuffer = false
        }
        return false
    }

    private fun takeCharsSkipChars(take: Int, skip: Int): String {
        val target = CharBuffer.allocate(take)
        eatDecodedChars(target, take)
        if (skip > 0)
            eatDecodedChars(CharBuffer.allocate(skip), skip)
        target.rewind()
        return Normalizer.normalize(target, Normalizer.Form.NFC)
    }

    private fun availableDecodedChars(): Int {
        var available = 0
        val decoded = this.decoded
        if (decoded != null)
            for (i in 0 until decoded.size)
                available += decoded[i].length
        return available
    }

    private fun availableUndecodedBytes(): Int {
        var available = 0
        val toDecode = this.toDecode
        if (toDecode != null)
            for (i in 0 until toDecode.size)
                available += toDecode[i].remaining()
        return available
    }

    private fun eatAllDecodedChars(target: CharBuffer) {
        val decoded = this.decoded
        if (decoded != null) {
            for (i in 0 until decoded.size) {
                target.append(decoded[i])
            }
            decoded.clear()
        }
    }

    private fun eatDecodedChars(target: CharBuffer, n: Int) {
        var remaining = n
        val decoded = this.decoded!!
        while (remaining > 0 && decoded.size > 0) {
            val source = decoded[0]
            if (source.remaining() <= remaining) {
                /* Snapshot the count first: modern JDKs fast-path
                 * append(CharSequence) for a CharBuffer argument into
                 * put(CharBuffer), which drains the source — so reading
                 * source.remaining() after the append yields 0, the loop
                 * never converged, and the next buffer overflowed the
                 * target (the long-standing 116-streaming-decoder.t
                 * BufferOverflowException, present in the Java original
                 * too). */
                val count = source.remaining()
                target.append(source)
                remaining -= count
                decoded.removeAt(0)
            }
            else {
                target.append(source.subSequence(0, remaining))
                decoded[0] = source.subSequence(remaining, source.remaining())
                remaining = 0
            }
        }
    }

    @Throws(CharacterCodingException::class)
    private fun eatUndecodedBytes(target: CharBuffer, eof: Boolean) {
        val toDecode = this.toDecode
        if (toDecode != null) {
            while (toDecode.size > 0) {
                val use = toDecode[0]

                val result = decoder!!.decode(use, target, eof && toDecode.size == 1)
                if (result.isError())
                    result.throwException()

                if (use.position() == use.limit()) {
                    toDecode.removeAt(0)
                }
                else if (toDecode.size > 1) {
                    // We might have to merge the remaining bytes with those
                    // in the next element of toDecode to get a valid char.
                    val useFirst = toDecode[0]
                    val useSecond = toDecode[1]
                    val size = useFirst.remaining() + useSecond.remaining()
                    val useCombined = ByteBuffer.allocate(size).put(useFirst).put(useSecond)
                    useCombined.rewind()
                    toDecode.removeAt(0)
                    toDecode[0] = useCombined
                }
                else
                    break
            }
        }
    }

    private fun isNormTerminated(s: String): Boolean {
        val last = s[s.length - 1].code
        return last < 0x20 || last >= 0x7F && last <= 0x9F || last == 0xAD
    }

    private fun decodedBuffer(buf: CharBuffer): CharBuffer {
        val pos = buf.position()
        buf.rewind()
        return buf.subSequence(0, pos)
    }

    private fun forceDecodedBackToBytes() {
        for (i in decoded!!.size - 1 downTo 0) {
            toDecode!!.add(0, charset!!.encode(decoded!![i]))
            decoded!!.removeAt(i)
        }
    }

    fun isEmpty(tc: ThreadContext): Long {
        ensureConfigured(tc)
        if (toDecode != null && toDecode!!.size > 0)
            return 0
        if (decoded != null && decoded!!.size > 0)
            return 0
        return 1
    }

    private fun ensureConfigured(tc: ThreadContext) {
        if (decoder == null)
            throw ExceptionHandling.dieInternal(tc, "Decoder not yet configured")
    }
}
