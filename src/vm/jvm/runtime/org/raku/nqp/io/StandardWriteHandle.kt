package org.raku.nqp.io

import java.io.IOException
import java.io.PrintStream
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class StandardWriteHandle(tc: ThreadContext, private val ps: PrintStream) :
    IIOClosable, IIOSeekable, IIOEncodable, IIOSyncWritable, IIOPossiblyTTY {

    private lateinit var enc: CharsetEncoder
    private lateinit var dec: CharsetDecoder
    private var pos = 0L

    init {
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    override fun close(tc: ThreadContext) {
        ps.close()
    }

    override fun seek(tc: ThreadContext, offset: Long, whence: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot seek stdout or stderr")
    }

    override fun tell(tc: ThreadContext): Long = pos

    override fun setEncoding(tc: ThreadContext, cs: Charset) {
        enc = cs.newEncoder()
        dec = cs.newDecoder()
    }

    override fun write(tc: ThreadContext, bytes: ByteArray): Long {
        ps.write(bytes, 0, bytes.size)
        pos += bytes.size
        return bytes.size.toLong()
    }

    override fun print(tc: ThreadContext, s: String): Long {
        try {
            val buffer = enc.encode(CharBuffer.wrap(s))
            val bytes = buffer.array()
            ps.write(bytes, 0, buffer.limit())
            /* Faithful to the historical Java: returns the backing array
             * length, which may exceed the bytes actually written. */
            return bytes.size.toLong()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun say(tc: ThreadContext, s: String): Long {
        var bytes = print(tc, s)
        bytes += print(tc, System.lineSeparator())
        return bytes
    }

    override fun flush(tc: ThreadContext) {
        ps.flush()
    }

    override fun isTTY(tc: ThreadContext): Boolean = System.console() != null

    override fun setBufferSize(tc: ThreadContext, size: Long) {
        // TODO: does it make sense to have setBufferSize here?
        // currently required as IIOSyncWritable has this method
    }
}
