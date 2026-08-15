package org.raku.nqp.io

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.NotYetBoundException
import java.nio.channels.UnresolvedAddressException

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.AsyncTaskInstance
import org.raku.nqp.sixmodel.reprs.ConcBlockingQueueInstance
import org.raku.nqp.sixmodel.reprs.IOHandleInstance

/**
 * Async TCP listener, backing nqp::asynclisten (dispatched from IOOps).
 * accept() first delivers a synchronous "listening" event carrying the bound
 * address, then an event per accepted connection. Event formats are pinned
 * by t/jvm/07-asyncsocket.t.
 */
class AsyncServerSocketHandle(tc: ThreadContext) : IIOBindable, IIOCancelable {

    private lateinit var listenChan: AsynchronousServerSocketChannel

    init {
        try {
            listenChan = AsynchronousServerSocketChannel.open()
        } catch (e: IOException) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun bind(tc: ThreadContext, host: String, port: Int, backlog: Int) {
        try {
            listenChan.bind(InetSocketAddress(host, port), backlog)
        } catch (uae: UnresolvedAddressException) {
            ExceptionHandling.dieInternal(tc, "Failed to resolve host name")
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    private fun hostAddress(addr: InetSocketAddress): String {
        val host = addr.address.hostAddress
        return if (host == "0:0:0:0:0:0:0:1") "::1" else host
    }

    fun accept(tc: ThreadContext, task: AsyncTaskInstance) {
        val config = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        val ioType = config.ioType!!
        val listType = config.listType!!
        val nullValue = config.nullValue
        val intType = config.intBoxType!!
        val strType = config.strBoxType!!

        fun newIoHandle(curTC: ThreadContext, handle: Any?): IOHandleInstance {
            val io = ioType.st.REPR.allocate(curTC, ioType.st) as IOHandleInstance
            io.handle = handle
            return io
        }

        fun push(curTC: ThreadContext, items: List<SixModelObject?>) {
            val result = listType.st.REPR.allocate(curTC, listType.st)
            result.push_boxed(curTC, task.schedulee)
            for (item in items)
                result.push_boxed(curTC, item)
            (task.queue as ConcBlockingQueueInstance).push_boxed(curTC, result)
        }

        val handler = object : CompletionHandler<AsynchronousSocketChannel, AsyncTaskInstance> {
            override fun completed(channel: AsynchronousSocketChannel, task: AsyncTaskInstance) {
                listenChan.accept(task, this)

                val localAddress = try {
                    channel.localAddress as InetSocketAddress
                } catch (e: IOException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                }
                val remoteAddress = try {
                    channel.remoteAddress as InetSocketAddress
                } catch (e: IOException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                }

                val curTC = tc.gc.getCurrentThreadContext()!!
                /* Event: [schedulee, clientHandle, err, peerHost, peerPort,
                 *         serverHandle, socketHost, socketPort] */
                push(curTC, listOf(
                    newIoHandle(curTC, AsyncSocketHandle(curTC, channel)),
                    nullValue,
                    Ops.box_s(hostAddress(remoteAddress), strType, curTC),
                    Ops.box_i(remoteAddress.port.toLong(), intType, curTC),
                    newIoHandle(curTC, this),
                    Ops.box_s(hostAddress(localAddress), strType, curTC),
                    Ops.box_i(localAddress.port.toLong(), intType, curTC)))
            }

            override fun failed(exc: Throwable, task: AsyncTaskInstance) {
                /* NOTE: faithful to the historical Java, which built this
                 * event list and never pushed it to the queue — accept
                 * failures are silently dropped. */
                val curTC = tc.gc.getCurrentThreadContext()!!
                val result = listType.st.REPR.allocate(curTC, listType.st)
                result.push_boxed(curTC, task.schedulee)
                result.push_boxed(curTC, ioType)
                result.push_boxed(curTC, Ops.box_s(exc.toString(), strType, curTC))
                result.push_boxed(curTC, strType)
                result.push_boxed(curTC, intType)
                result.push_boxed(curTC, ioType)
                result.push_boxed(curTC, strType)
                result.push_boxed(curTC, intType)
            }
        }

        try {
            val localAddress = try {
                listenChan.localAddress as InetSocketAddress
            } catch (e: IOException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }

            val curTC = tc.gc.getCurrentThreadContext()!!
            /* Initial event: [schedulee, IOType, err, Str, Int, serverHandle,
             *                 socketHost, socketPort] */
            push(curTC, listOf(
                ioType,
                nullValue,
                strType,
                intType,
                newIoHandle(curTC, this),
                Ops.box_s(hostAddress(localAddress), strType, curTC),
                Ops.box_i(localAddress.port.toLong(), intType, curTC)))
        } catch (e: Exception) {
            throw ExceptionHandling.dieInternal(tc, e)
        }

        try {
            listenChan.accept(task, handler)
        } catch (e: NotYetBoundException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun cancel(tc: ThreadContext) {
        try {
            listenChan.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }
}
