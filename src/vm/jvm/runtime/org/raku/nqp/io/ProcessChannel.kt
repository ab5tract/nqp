package org.raku.nqp.io

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

class ProcessChannel : ByteChannel, Runnable {
    // Raw public fields, read by Ops (the `in` channel is re-wrapped when
    // piping process output).
    @JvmField var out: WritableByteChannel? = null
    @JvmField var outStream: OutputStream? = null
    @JvmField var `in`: ReadableByteChannel? = null
    @JvmField var process: Process? = null

    override fun run() {
        try {
            val bb = ByteBuffer.allocate(32768)
            var read = 1
            while (read > 0) {
                read = `in`!!.read(bb)
                if (read > 0)
                    outStream!!.write(bb.array(), 0, read)
            }
        } catch (e: Exception) {
            throw RuntimeException("Broken pipe", e)
        } finally {
            try { out?.close() } catch (e: Exception) { }
            try { `in`?.close() } catch (e: Exception) { }
        }
    }

    constructor(process: Process, out: OutputStream, input: ReadableByteChannel) {
        this.out = Channels.newChannel(out)
        this.outStream = out
        this.`in` = input
        this.process = process
    }

    constructor(process: Process, out: OutputStream) {
        this.out = Channels.newChannel(out)
        this.process = process
    }

    constructor(process: Process, input: InputStream) {
        this.`in` = Channels.newChannel(input)
        this.process = process
    }

    @Throws(IOException::class)
    override fun read(dst: ByteBuffer): Int = `in`!!.read(dst)

    override fun isOpen(): Boolean = `in`!!.isOpen

    @Throws(IOException::class)
    override fun close() {
        `in`?.close()
        out?.close()
    }

    @Throws(InterruptedException::class)
    fun exitValue(): Int = process!!.waitFor()

    @Throws(IOException::class)
    override fun write(src: ByteBuffer): Int = out!!.write(src)
}
