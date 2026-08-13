package org.raku.nqp.io

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.Charset

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class SocketHandle : SyncHandle {

    constructor(tc: ThreadContext) {
        try {
            chan = SocketChannel.open()
            setEncoding(tc, Charset.forName("UTF-8"))
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    constructor(tc: ThreadContext, existing: SocketChannel) {
        chan = existing
        setEncoding(tc, Charset.forName("UTF-8"))
    }

    fun connect(tc: ThreadContext, host: String, port: Int) {
        try {
            val addr = InetSocketAddress(host, port)
            (chan as SocketChannel).connect(addr)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun flush(tc: ThreadContext) {
        // Not provided.
    }
}
