package org.raku.nqp.io

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.channels.CompletionHandler

import org.raku.nqp.runtime.Buffers
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.AsyncTaskInstance
import org.raku.nqp.sixmodel.reprs.ConcBlockingQueueInstance
import org.raku.nqp.sixmodel.reprs.IOHandleInstance

/**
 * One side of an async TCP connection, backing nqp::asyncconnect /
 * asyncwritebytes / asyncreadbytes (dispatched from IOOps). Completion
 * handlers run on the NIO pool; every event is delivered as a boxed
 * argument list ([schedulee, ...]) on the task's queue. Event formats are
 * pinned by t/jvm/07-asyncsocket.t.
 */
class AsyncSocketHandle : IIOClosable, IIOCancelable {
    private val channel: AsynchronousSocketChannel

    constructor(tc: ThreadContext) {
        channel = try {
            AsynchronousSocketChannel.open()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    constructor(tc: ThreadContext, channel: AsynchronousSocketChannel) {
        this.channel = channel
    }

    /* The HLL types must be captured on the calling thread: completion
     * handlers run on NIO pool threads where tc.curFrame is not ours. */
    private fun hll(tc: ThreadContext) =
        tc.frame.codeRef.staticInfo.compUnit.hllConfig

    private fun send(listType: SixModelObject, tc: ThreadContext,
                     task: AsyncTaskInstance, vararg items: SixModelObject?) {
        val result = listType.st.REPR.allocate(tc, listType.st)
        result.push_boxed(tc, task.schedulee)
        for (item in items)
            result.push_boxed(tc, item)
        (task.queue as ConcBlockingQueueInstance).push_boxed(tc, result)
    }

    fun connect(tc: ThreadContext, host: String, port: Int, task: AsyncTaskInstance) {
        val config = hll(tc)
        val listType = config.listType!!
        val ioType = config.ioType!!
        val intType = config.intBoxType!!
        val strType = config.strBoxType!!

        /* Event: [schedulee, handle, err, peerHost, peerPort, socketHost, socketPort] */
        val handler = object : CompletionHandler<Void, AsyncTaskInstance> {
            override fun completed(v: Void?, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                val ioHandle = ioType.st.REPR.allocate(curTC, ioType.st) as IOHandleInstance
                ioHandle.handle = task.handle
                send(listType, curTC, task, ioHandle, strType,
                    Ops.box_s(host, strType, curTC),
                    Ops.box_i(port.toLong(), intType, curTC),
                    Ops.box_s(host, strType, curTC),  // TODO send socketHost
                    Ops.box_i(port.toLong(), intType, curTC))  // TODO send socketPort
            }

            override fun failed(t: Throwable, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                send(listType, curTC, task, ioType,
                    Ops.box_s(t.toString(), strType, curTC), strType, intType, strType, intType)
            }
        }

        try {
            channel.connect(InetSocketAddress(host, port), task, handler)
        } catch (e: Throwable) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    fun writeBytes(tc: ThreadContext, task: AsyncTaskInstance, toWrite: SixModelObject) {
        val buffer = Buffers.unstashBytes(toWrite, tc)
        val config = hll(tc)
        val listType = config.listType!!
        val intType = config.intBoxType!!
        val strType = config.strBoxType!!
        val nullValue = config.nullValue

        /* Event: [schedulee, bytesWritten, err] */
        val handler = object : CompletionHandler<Int, AsyncTaskInstance> {
            override fun completed(bytesWritten: Int, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                send(listType, curTC, task, Ops.box_i(bytesWritten.toLong(), intType, curTC), nullValue)
            }

            override fun failed(t: Throwable, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                send(listType, curTC, task, strType, Ops.box_s(t.toString(), strType, curTC))
            }
        }

        try {
            channel.write(buffer, task, handler)
        } catch (e: Throwable) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    fun readBytes(tc: ThreadContext, task: AsyncTaskInstance, bufType: SixModelObject) {
        readSocket(tc, task) { curTC, source ->
            val res = bufType.st.REPR.allocate(curTC, bufType.st)
            val bytes = ByteArray(source.remaining())
            source.get(bytes)
            Buffers.stashBytes(curTC, res, bytes)
            res
        }
    }

    private fun readSocket(tc: ThreadContext, task: AsyncTaskInstance,
                           decode: (ThreadContext, ByteBuffer) -> SixModelObject) {
        val readBuffer = ByteBuffer.allocate(32768)
        val config = hll(tc)
        val listType = config.listType!!
        val intType = config.intBoxType!!
        val strType = config.strBoxType!!
        val nullValue = config.nullValue

        /* Event: [schedulee, seq, decoded-or-Str(EOF), err] */
        val handler = object : CompletionHandler<Int, AsyncTaskInstance> {
            fun send4(curTC: ThreadContext, task: AsyncTaskInstance,
                      seq: Long, payload: SixModelObject?, err: SixModelObject?) =
                send(listType, curTC, task, Ops.box_i(seq, intType, curTC), payload, err)

            override fun completed(numRead: Int, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                try {
                    if (numRead == -1) {
                        send4(curTC, task, task.seq, strType, nullValue)
                    } else {
                        readBuffer.flip()
                        val decoded = decode(curTC, readBuffer)
                        readBuffer.compact()

                        send4(curTC, task, task.seq++, decoded, nullValue)

                        channel.read(readBuffer, task, this)
                    }
                } catch (t: Throwable) {
                    failed(t, task)
                }
            }

            override fun failed(t: Throwable, task: AsyncTaskInstance) {
                val curTC = tc.gc.getCurrentThreadContext()!!
                val err =
                    if (t is AsynchronousCloseException || t is ClosedChannelException) strType
                    else Ops.box_s(t.toString(), strType, curTC)
                send4(curTC, task, -1, strType, err)
            }
        }

        try {
            channel.read(readBuffer, task, handler)
        } catch (t: Throwable) {
            handler.failed(t, task)
        }
    }

    override fun close(tc: ThreadContext) {
        try {
            channel.close()
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun cancel(tc: ThreadContext) {
        close(tc)
    }
}
