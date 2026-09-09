package org.raku.nqp.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.ByteChannel
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

abstract class SyncHandle : IIOClosable, IIOEncodable,
        IIOSyncReadable, IIOSyncWritable, IIOLineSeparable, IIOExitable {

    // Public field: read directly by Ops (lateinit exposes the backing field).
    lateinit var chan: ByteChannel
    protected lateinit var enc: CharsetEncoder
    protected lateinit var dec: CharsetDecoder
    protected var eof = false
    protected var readBuffer: ByteBuffer? = null
    protected var writeBuffer: ByteBuffer? = null
    protected var writeBufferSize = 8192
    protected var useWriteBuffer = false
    protected var linesep: ByteArray? = null

    override fun close(tc: ThreadContext) {
        try {
            if (useWriteBuffer)
                flushWriteBuffer(tc)
            chan.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun exitValue(tc: ThreadContext): Int {
        try {
            val chan = this.chan
            if (chan is ProcessChannel)
                return chan.exitValue()
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This channel does not support exitValue")
        } catch (e: InterruptedException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun setEncoding(tc: ThreadContext, cs: Charset) {
        enc = cs.newEncoder()
        dec = cs.newDecoder()
    }

    @Synchronized
    override fun slurp(tc: ThreadContext): String {
        try {
            // Read in file.
            val buffers = ArrayList<ByteBuffer>()
            var curBuffer = ByteBuffer.allocate(32768)
            var total = 0
            val pending = readBuffer
            if (pending != null) {
                total = pending.limit() - pending.position()
                val newBytes = ByteArray(total)
                pending.get(newBytes)
                buffers.add(ByteBuffer.wrap(newBytes))
                readBuffer = null
            }
            var read = chan.read(curBuffer)
            while (read != -1) {
                curBuffer.flip()
                buffers.add(curBuffer)
                curBuffer = ByteBuffer.allocate(32768)
                total += read
                read = chan.read(curBuffer)
            }
            eof = true

            return decodeBuffers(buffers, total)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @Synchronized
    override fun readline(tc: ThreadContext): String {
        try {
            var foundLine = false
            val lineChunks = ArrayList<ByteBuffer>()
            var total = 0

            while (!foundLine) {
                /* Ensure we have a buffer available. */
                var buffer = readBuffer
                if (buffer == null) {
                    buffer = ByteBuffer.allocate(32768)
                    readBuffer = buffer
                    if (chan.read(buffer) == -1) {
                        /* End of file, so what we have is fine. */
                        eof = true
                        foundLine = true
                        buffer.flip()
                        break
                    }
                    buffer.flip()
                }

                /* Look for a line end. */
                val start = buffer.position()
                var end = start
                val linesep = this.linesep
                while (!foundLine && end < buffer.limit()) {
                    if (linesep != null) {
                        var index = 0
                        while (index < linesep.size
                                && end + index < buffer.limit()
                                && buffer.get(end + index) == linesep[index])
                            index++

                        if (index == linesep.size) {
                            end += index
                            foundLine = true
                        } else {
                            end++
                        }
                    }
                    else {
                        val cur = buffer.get(end)
                        if (cur == '\n'.code.toByte()) {
                            foundLine = true
                        }
                        else if (cur == '\r'.code.toByte()) {
                            foundLine = true
                            /* XXX: This could fail if the \r\n sequence is
                             * split over two chunks, with the \r at the very
                             * end of a chunk at the \n at the beginning of
                             * the next one I think. */
                            if (end + 1 < buffer.limit() && buffer.get(end + 1) == '\n'.code.toByte()) {
                                end++
                            }
                        }
                        end++
                    }
                }

                /* Copy what we found into the results. */
                val lineBytes = ByteArray(end - start)
                buffer.get(lineBytes)
                lineChunks.add(ByteBuffer.wrap(lineBytes))
                total += lineBytes.size

                /* If we didn't find a line, will cross chunk boundary. */
                if (!foundLine)
                    readBuffer = null
            }

            return if (lineChunks.size == 1)
                dec.decode(lineChunks[0]).toString()
            else
                decodeBuffers(lineChunks, total)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    private fun decodeBuffers(buffers: ArrayList<ByteBuffer>, total: Int): String {
        // Copy to a single buffer and decode (could be smarter, but need
        // to be wary as UTF-8 chars may span a buffer boundary).
        var remaining = total
        val allBytes = ByteBuffer.allocate(total)
        for (bb in buffers) {
            val amount = minOf(remaining, bb.limit())
            allBytes.put(bb.array(), 0, amount)
            remaining -= amount
        }
        allBytes.rewind()
        return dec.decode(allBytes).toString()
    }

    @Synchronized
    override fun readchars(tc: ThreadContext, chars: Int): String {
        try {
            dec.reset()

            val decoded = CharBuffer.allocate(chars)

            var needMoreChars = true

            val pending = readBuffer
            if (pending != null) {
                val result = dec.decode(pending, decoded, true)

                if (result.isError)
                    result.throwException()

                needMoreChars = result.isUnderflow
            }

            while (needMoreChars && !eof) {
                val oldReadBuffer = readBuffer

                val buffer = ByteBuffer.allocate(32768)
                readBuffer = buffer

                if (oldReadBuffer != null)
                    buffer.put(oldReadBuffer)

                eof = chan.read(buffer) == -1

                buffer.flip()

                val result = dec.decode(buffer, decoded, eof)

                if (eof)
                    dec.flush(decoded)

                if (result.isError)
                    result.throwException()

                needMoreChars = result.isUnderflow
            }

            decoded.flip()

            return decoded.toString()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun eof(tc: ThreadContext): Boolean = eof

    override fun read(tc: ThreadContext, bytes: Int): ByteArray {
        try {
            // look in readBuffer for data from previous read, e.g. via readline
            val pending = readBuffer
            if (pending != null) {
                val res = ByteArray(pending.limit() - pending.position())
                pending.get(res)
                readBuffer = null
                return res
            }
            else {
                val buffer = ByteBuffer.allocate(bytes)
                eof = chan.read(buffer) == -1
                buffer.flip()
                val res = ByteArray(buffer.limit())
                buffer.get(res)
                return res
            }
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun write(tc: ThreadContext, bytes: ByteArray): Long =
        write(tc, ByteBuffer.wrap(bytes))

    protected fun write(tc: ThreadContext, buffer: ByteBuffer): Long {
        try {
            /* remaining(), not limit(): a CharsetEncoder's buffer is
             * allocated at maxBytesPerChar and its limit is the encoded
             * length, but a caller may also hand one with a position. */
            val toWrite = buffer.remaining()
            if (useWriteBuffer && writeBufferSize > 0) {
                /* Ensure we have a buffer available. */
                var wb = writeBuffer
                if (wb == null) {
                    wb = ByteBuffer.allocate(writeBufferSize)
                    writeBuffer = wb
                }
                /* If we can't fit it on the end of the buffer, flush the buffer. */
                if (toWrite > wb.remaining())
                    flushWriteBuffer(tc)
                /* If we can fit it in the buffer now, copy it there, and we're
                 * done. */
                if (toWrite < writeBufferSize) {
                    /* put(buffer), never put(buffer.array()): the backing
                     * array is longer than the encoded content, and the
                     * slack went into the file as NUL padding. */
                    wb.put(buffer)
                    return toWrite.toLong()
                }
            }

            var written = 0
            while (written < toWrite)
                written += chan.write(buffer)
            return written.toLong()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun print(tc: ThreadContext, s: String): Long {
        try {
            val buffer = enc.encode(CharBuffer.wrap(s))
            return write(tc, buffer)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun say(tc: ThreadContext, s: String): Long {
        var bytes = print(tc, s)
        bytes += print(tc, System.lineSeparator())
        return bytes
    }

    protected fun flushWriteBuffer(tc: ThreadContext) {
        val wb = writeBuffer
        if (wb != null) {
            try {
                wb.flip()
                while (wb.hasRemaining())
                    chan.write(wb)
                wb.clear()
            } catch (e: IOException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
        }
    }

    override fun setInputLineSeparator(tc: ThreadContext, sep: String) {
        try {
            linesep = enc.charset().newEncoder().encode(CharBuffer.wrap(sep)).array()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun setBufferSize(tc: ThreadContext, size: Long) {
        if (useWriteBuffer) {
            if (writeBuffer != null)
                flushWriteBuffer(tc)
            writeBufferSize = size.toInt()
            writeBuffer = if (writeBufferSize > 0) ByteBuffer.allocate(writeBufferSize) else null
        }
    }
}
