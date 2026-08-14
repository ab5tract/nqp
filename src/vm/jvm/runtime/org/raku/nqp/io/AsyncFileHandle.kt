package org.raku.nqp.io

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Backs nqp::openasync / slurpasync / spurtasync / linesasync (dispatched
 * from Ops). Completion handlers run on the NIO thread pool; results are
 * delivered by invoking the done/error callbacks (slurp/spurt) or by
 * enqueueing lines on the given queue (lines). See t/jvm/05-asyncfile.t.
 */
class AsyncFileHandle(tc: ThreadContext, filename: String, mode: String) :
    IIOClosable, IIOEncodable, IIOAsyncReadable, IIOAsyncWritable {

    private val chan: AsynchronousFileChannel
    private lateinit var enc: CharsetEncoder
    private lateinit var dec: CharsetDecoder

    init {
        val path = File(filename).toPath()
        chan = try {
            when (mode) {
                "r" -> AsynchronousFileChannel.open(path, StandardOpenOption.READ)
                "w" -> AsynchronousFileChannel.open(path,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                "wa" -> AsynchronousFileChannel.open(path,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                else -> throw ExceptionHandling.dieInternal(tc, "Unhandled file open mode '$mode'")
            }
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    override fun close(tc: ThreadContext) {
        try {
            chan.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun setEncoding(tc: ThreadContext, cs: Charset) {
        enc = cs.newEncoder()
        dec = cs.newDecoder()
    }

    private companion object {
        val oneArgCSD = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
        val zeroArgCSD = CallSiteDescriptor(byteArrayOf(), null)
    }

    /** Boxes a failure and invokes the error callback, from any thread. */
    private fun reportError(tc: ThreadContext, Str: SixModelObject, error: SixModelObject, exc: Throwable) {
        val curTC = tc.gc.getCurrentThreadContext()!!
        Ops.invokeDirect(curTC, error, oneArgCSD,
            arrayOf<Any>(Ops.box_s(exc.toString(), Str, curTC)))
    }

    override fun slurp(tc: ThreadContext, Str: SixModelObject,
                       done: SixModelObject, error: SixModelObject) {
        try {
            val expected = chan.size()
            val bb = ByteBuffer.allocate(expected.toInt())

            chan.read(bb, 0, bb, object : CompletionHandler<Int, ByteBuffer> {
                override fun completed(bytes: Int, bb: ByteBuffer) {
                    if (bb.position().toLong() == expected) {
                        try {
                            /* We're done. Decode, box, call the done handler. */
                            val curTC = tc.gc.getCurrentThreadContext()!!
                            bb.flip()
                            val boxed = Ops.box_s(dec.decode(bb).toString(), Str, curTC)
                            Ops.invokeDirect(curTC, done, oneArgCSD, arrayOf<Any>(boxed))
                        } catch (e: IOException) {
                            failed(e, bb)
                        }
                    }
                    else {
                        /* Need to read some more. */
                        chan.read(bb, bb.position().toLong(), bb, this)
                    }
                }

                override fun failed(exc: Throwable, bb: ByteBuffer) =
                    reportError(tc, Str, error, exc)
            })
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun spurt(tc: ThreadContext, Str: SixModelObject, data: SixModelObject,
                       done: SixModelObject, error: SixModelObject) {
        try {
            val bb = enc.encode(CharBuffer.wrap(Ops.unbox_s(data, tc)))
            val expected = bb.remaining().toLong()
            bb.rewind()

            chan.write(bb, 0, bb, object : CompletionHandler<Int, ByteBuffer> {
                override fun completed(bytes: Int, bb: ByteBuffer) {
                    if (bb.position().toLong() == expected) {
                        /* Done. Call the done handler. */
                        val curTC = tc.gc.getCurrentThreadContext()!!
                        Ops.invokeDirect(curTC, done, zeroArgCSD, arrayOf<Any>())
                    }
                    else {
                        /* Need to write some more. */
                        chan.write(bb, bb.position().toLong(), bb, this)
                    }
                }

                override fun failed(exc: Throwable, bb: ByteBuffer) =
                    reportError(tc, Str, error, exc)
            })
        } catch (e: CharacterCodingException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    private class LinesState {
        val lineChunks = ArrayList<ByteBuffer>()
        var readBuffer: ByteBuffer = ByteBuffer.allocate(32768)
        var total = 0
        var position = 0L
    }

    override fun lines(tc: ThreadContext, Str: SixModelObject, chomp: Boolean,
                       queue: LinkedBlockingQueue<SixModelObject>,
                       done: SixModelObject, error: SixModelObject) {
        val ls = LinesState()

        chan.read(ls.readBuffer, 0, ls, object : CompletionHandler<Int, LinesState> {
            override fun completed(bytes: Int, ss: LinesState) {
                try {
                    val curTC = tc.gc.getCurrentThreadContext()!!

                    /* If we've read it all, send the done notification. */
                    if (bytes == -1) {
                        /* There may be non-linebreak-terminated data left to
                         * spit out; no need to chomp it. */
                        if (ss.lineChunks.isNotEmpty())
                            queue.put(Ops.box_s(decodeChunks(ss), Str, curTC))
                        Ops.invokeDirect(curTC, done, zeroArgCSD, arrayOf<Any>())
                        return
                    }

                    ss.readBuffer.flip()

                    while (true) {
                        /* Hunt a line boundary (the byte after it, if found). */
                        val start = ss.readBuffer.position()
                        var end = start
                        var foundLine = false
                        while (!foundLine && end < ss.readBuffer.limit()) {
                            if (ss.readBuffer.get(end) == '\n'.code.toByte())
                                foundLine = true
                            end++
                        }

                        /* Copy what we found into the pending chunks. */
                        val lineBytes = ByteArray(end - start)
                        ss.readBuffer.get(lineBytes)
                        ss.lineChunks.add(ByteBuffer.wrap(lineBytes))
                        ss.total += lineBytes.size

                        if (!foundLine)
                            break

                        var decoded = decodeChunks(ss)
                        if (chomp && decoded.endsWith("\n"))
                            decoded = decoded.dropLast(if (decoded.endsWith("\r\n")) 2 else 1)

                        queue.put(Ops.box_s(decoded, Str, curTC))
                        ss.lineChunks.clear()
                        ss.total = 0
                    }

                    /* Read more. */
                    ss.position += bytes
                    ss.readBuffer = ByteBuffer.allocate(32768)
                    chan.read(ss.readBuffer, ss.position, ss, this)
                } catch (e: IOException) {
                    failed(e, ss)
                } catch (e: InterruptedException) {
                    failed(e, ss)
                }
            }

            override fun failed(exc: Throwable, ss: LinesState) =
                reportError(tc, Str, error, exc)
        })
    }

    private fun decodeChunks(ss: LinesState): String {
        val chunks = ss.lineChunks
        if (chunks.size == 1)
            return dec.decode(chunks[0]).toString()
        /* Copy to a single buffer and decode contiguously: UTF-8 characters
         * may span a chunk boundary. */
        val all = ByteBuffer.allocate(ss.total)
        for (bb in chunks)
            all.put(bb.array(), 0, bb.limit())
        all.rewind()
        return dec.decode(all).toString()
    }
}
