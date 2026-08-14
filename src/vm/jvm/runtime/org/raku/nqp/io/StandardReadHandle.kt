package org.raku.nqp.io

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class StandardReadHandle(tc: ThreadContext, private val inputStream: InputStream) :
    IIOClosable, IIOEncodable, IIOSyncReadable, IIOInteractive, IIOPossiblyTTY {

    private var br: BufferedReader? = null
    private var cr: LineReader? = null
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
            /* jline 1's ConsoleReader(in, out) became a LineReader over a
             * Terminal. This handle is process stdin, so let jline attach
             * to the system terminal for real line editing, with a quiet
             * dumb-terminal fallback when there is no tty. */
            val console = cr ?: LineReaderBuilder.builder()
                .terminal(TerminalBuilder.builder().dumb(true).build())
                .build().also { cr = it }
            return try {
                console.readLine(prompt)
            } catch (e: EndOfFileException) {
                /* jline 1 returned null at EOF. Close the terminal too:
                 * its reader pump is a non-daemon thread, and leaving it
                 * open keeps the JVM alive after the REPL loop returns. */
                eof = true
                console.terminal.close()
                cr = null
                ""
            } catch (e: UserInterruptException) {
                /* Ctrl-C mid-line: hand back an empty line and keep the
                 * REPL alive rather than tearing down the VM. */
                ""
            }
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun eof(tc: ThreadContext): Boolean = eof

    override fun isTTY(tc: ThreadContext): Boolean = System.console() != null
}
