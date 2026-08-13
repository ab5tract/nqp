package org.raku.nqp.io

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class ServerSocketHandle(tc: ThreadContext) : IIOBindable, IIOClosable {

    private lateinit var listenChan: ServerSocketChannel
    // Read directly by Ops (getport).
    @JvmField var listenPort = 0

    init {
        try {
            listenChan = ServerSocketChannel.open()
        } catch (e: IOException) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun bind(tc: ThreadContext, host: String, port: Int, backlog: Int) {
        try {
            val addr = InetSocketAddress(host, port)
            listenChan.bind(addr, backlog)
            listenPort = listenChan.socket().localPort
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    fun accept(tc: ThreadContext): SocketHandle? {
        try {
            val chan = listenChan.accept()
            return if (chan == null) null else SocketHandle(tc, chan)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun close(tc: ThreadContext) {
        try {
            listenChan.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }
}
