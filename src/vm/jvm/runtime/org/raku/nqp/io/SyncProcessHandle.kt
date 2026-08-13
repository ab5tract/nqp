package org.raku.nqp.io

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

import org.raku.nqp.runtime.ThreadContext

class SyncProcessHandle(tc: ThreadContext) : SyncHandle() {

    @JvmField var process: Process? = null

    init {
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    fun bindChannel(tc: ThreadContext, process: Process, out: OutputStream) {
        this.process = process
        this.chan = ProcessChannel(process, out)
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    fun bindChannel(tc: ThreadContext, process: Process, input: InputStream) {
        this.process = process
        this.chan = ProcessChannel(process, input)
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    override fun flush(tc: ThreadContext) {
        // Not provided.
    }
}
