package org.raku.nqp.io

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset

import jline.ConsoleReader

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class StandardReadHandle(tc: ThreadContext, private val inputStream: InputStream) :
    IIOClosable, IIOEncodable, IIOSyncReadable, IIOInteractive, IIOPossiblyTTY {

    private var br: BufferedReader? = null
    private var cr: ConsoleReader? = null
    private var eof = false
    private lateinit var cs: Charset

    init {
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    override fun close(tc: ThreadContext) {
        try {
            inputStream.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun setEncoding(tc: ThreadContext, cs: Charset) {
        this.cs = cs
    }

    override fun read(tc: ThreadContext, bytes: Int): ByteArray {
        try {
            val array = ByteArray(bytes)
            var read: Int
            var offset = 0
            if (isTTY(tc)) {
                while (offset < bytes) {
                    read = inputStream.read(array, offset, minOf(inputStream.available(), bytes - offset))
                    if (read == -1) {
                        eof = true
                        break
                    }
                    else if (read == 0) {
                        if (inputStream.available() == 0 && offset > 0) {
                            break
                        }
                    }
                    else {
                        offset += read
                    }
                }
            }
            else {
                while (offset < bytes) {
                    read = inputStream.read(array, offset, bytes - offset)
                    if (read == -1) {
                        eof = true
                        break
                    }
                    offset += read
                }
            }
            return array.copyOf(offset)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @Synchronized
    override fun slurp(tc: ThreadContext): String {
        try {
            val reader = br ?: BufferedReader(InputStreamReader(inputStream, cs)).also { br = it }
            val data = StringBuilder()
            val buf = CharArray(4096)
            var read: Int
            while (reader.read(buf).also { read = it } != -1)
                data.append(String(buf, 0, read))
            eof = true
            return data.toString()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @Synchronized
    override fun readline(tc: ThreadContext): String {
        try {
            val reader = br ?: BufferedReader(InputStreamReader(inputStream, cs)).also { br = it }
            var line = reader.readLine()
            if (line == null) {
                eof = true
                line = ""
            }
            return line
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    /* TODO - think about unicode in readchars and getc */
    @Synchronized
    override fun readchars(tc: ThreadContext, chars: Int): String {
        try {
            val reader = br ?: BufferedReader(InputStreamReader(inputStream, cs)).also { br = it }
            val buf = CharArray(chars)

            val actuallyRead = reader.read(buf, 0, chars)

            return if (actuallyRead == -1) "" else String(buf, 0, actuallyRead)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @Synchronized
    override fun readlineInteractive(tc: ThreadContext, prompt: String): String {
        try {
            val console = cr ?: ConsoleReader(inputStream, OutputStreamWriter(tc.gc.out)).also { cr = it }
            var line = console.readLine(prompt)
            if (line == null) {
                eof = true
                line = ""
            }
            return line
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun eof(tc: ThreadContext): Boolean = eof

    override fun isTTY(tc: ThreadContext): Boolean = System.console() != null
}
