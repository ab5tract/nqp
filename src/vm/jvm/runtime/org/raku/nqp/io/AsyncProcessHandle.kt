package org.raku.nqp.io

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

import org.raku.nqp.runtime.Buffers
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.AsyncTaskInstance
import org.raku.nqp.sixmodel.reprs.ConcBlockingQueueInstance

/**
 * Backs nqp::spawnprocasync / nqp::asyncwritebytes / nqp::killprocasync
 * (dispatched from IOOps). A watcher thread runs the process and reports
 * lifecycle events; each captured output stream gets a reader thread that
 * enqueues byte chunks. All events are sent as boxed argument lists on the
 * scheduler queue.
 *
 * Idiomatic rewrite of the historical Java (see kotlin-migration-notes.md):
 * config callbacks are resolved once into a null-free map, the process-start
 * poll loop is a CountDownLatch, `proc` is volatile, and killing a process
 * that never started is a no-op rather than an NPE. The vestigial JNA
 * Kernel32 PID lookup (obsolete since Process.pid(), Java 9) is gone.
 */
class AsyncProcessHandle(
    private val tc: ThreadContext,
    private val queue: SixModelObject,
    prog: String,
    argsObj: SixModelObject,
    cwd: String,
    envObj: SixModelObject,
    configObj: SixModelObject,
) : IIOClosable {

    private val hllConfig = tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig!!
    private val listType = hllConfig.listType!!
    private val intBoxType = hllConfig.intBoxType!!
    private val strBoxType = hllConfig.strBoxType!!

    /** Config entries that are present and not nqp-null. */
    private val config: Map<String, SixModelObject> =
        iterate(configObj).mapNotNull { kv ->
            val value = Ops.iterval(kv, tc)
            if (Ops.isnull(value) == 0L) Ops.iterkey_s(kv, tc) to value else null
        }.toMap()

    private val bufType: SixModelObject? = config["buf_type"]

    @Volatile
    private var proc: Process? = null
    private val procStarted = CountDownLatch(1)

    private var outSeq = 0
    private var errSeq = 0

    init {
        val args = iterate(argsObj).map { it.get_str(tc) }.toMutableList()
        /* arg0 cannot be chosen freely on the JVM; overwrite it with the
         * program to execute. */
        args[0] = prog

        val pb = ProcessBuilder(args)
        pb.directory(File(cwd))
        pb.environment().clear()
        pb.environment().putAll(
            iterate(envObj).associate {
                Ops.iterkey_s(it, tc) to Ops.unbox_s(Ops.iterval(it, tc), tc)
            })
        configureRedirects(pb)

        thread(isDaemon = true) { runProcess(pb) }
    }

    private fun iterate(obj: SixModelObject): Sequence<SixModelObject> {
        val iter = Ops.iter(obj, tc)
        return generateSequence { if (Ops.istrue(iter, tc) != 0L) iter.shift_boxed(tc) else null }
    }

    private fun configureRedirects(pb: ProcessBuilder) {
        when {
            "write" in config -> pb.redirectInput(Redirect.PIPE)
            "stdin_fd" in config -> {} // keep the default pipe; fd passing is not supported
            else -> pb.redirectInput(Redirect.INHERIT)
        }

        if ("merge_bytes" in config) {
            pb.redirectOutput(Redirect.PIPE)
            pb.redirectErrorStream(true)
        }
        else {
            when {
                "stdout_bytes" in config -> pb.redirectOutput(Redirect.PIPE)
                "stdout_fd" in config -> {}
                else -> pb.redirectOutput(Redirect.INHERIT)
            }
            when {
                "stderr_bytes" in config -> pb.redirectError(Redirect.PIPE)
                "stderr_fd" in config -> {}
                else -> pb.redirectError(Redirect.INHERIT)
            }
        }
    }

    private fun runProcess(pb: ProcessBuilder) {
        try {
            val proc = pb.start().also { this.proc = it }
            procStarted.countDown()

            val pid = runCatching { proc.pid() }.getOrDefault(0L)
            config["ready"]?.let { send(it, null, boxInt(pid.toInt())) }

            (config["merge_bytes"] ?: config["stdout_bytes"])
                ?.let { launchReader(it, proc, stderr = false) }
            config["stderr_bytes"]?.let { launchReader(it, proc, stderr = true) }

            val outcome = proc.waitFor()
            /* Return exit code left shifted by 8 for POSIX emulation. */
            config["done"]?.let { send(it, boxInt(outcome shl 8)) }
        }
        catch (t: Throwable) {
            val message = boxError(t.toString())
            /* Report exception message and hard-coded exit code -1. */
            config["error"]?.let { send(it, message, boxInt(-1 shl 8)) }
            config["stdout_bytes"]?.let { send(it, intBoxType, strBoxType, message) }
            config["stderr_bytes"]?.let { send(it, intBoxType, strBoxType, message) }
        }
    }

    private fun launchReader(callback: SixModelObject, proc: Process, stderr: Boolean) {
        val stream = if (stderr) proc.errorStream else proc.inputStream
        fun nextSeq() = boxInt(if (stderr) errSeq++ else outSeq++)
        thread {
            try {
                while (true) {
                    /* A fresh buffer every read: stashBytes hands the array
                     * itself to the VMArray, without copying. */
                    val buffer = ByteArray(32768)
                    val read = stream.read(buffer)
                    if (read == -1) break
                    val result = Ops.create(bufType, tc)
                    Buffers.stashBytes(tc, result, buffer, read)
                    send(callback, nextSeq(), result, strBoxType)
                }
                send(callback, nextSeq(), strBoxType, strBoxType)
            }
            catch (t: Throwable) {
                send(callback, boxInt(-1), strBoxType, boxError(t.toString()))
            }
        }
    }

    private fun send(vararg args: SixModelObject?) {
        val result = listType.st.REPR.allocate(tc, listType.st)
        for (arg in args)
            result.push_boxed(tc, arg)
        (queue as ConcBlockingQueueInstance).push_boxed(tc, result)
    }

    private fun boxError(error: String): SixModelObject =
        Ops.box_s(error, strBoxType, tc)

    private fun boxInt(value: Int): SixModelObject =
        Ops.box_i(value.toLong(), intBoxType, tc)

    @Synchronized
    fun writeBytes(tc: ThreadContext, task: AsyncTaskInstance, toWrite: SixModelObject) {
        val buffer = Buffers.unstashBytes(toWrite, tc)
        val result = listType.st.REPR.allocate(tc, listType.st)
        result.push_boxed(tc, task.schedulee)
        try {
            /* wait up to one second for the process to start */
            procStarted.await(1, TimeUnit.SECONDS)
            /* TODO: is it better to check if proc is null and
             * throw an Exception with a fitting message? */
            val stream = proc!!.outputStream
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            stream.write(bytes)
            result.push_boxed(tc, boxInt(bytes.size))
            result.push_boxed(tc, strBoxType)
        }
        catch (t: Throwable) {
            result.push_boxed(tc, strBoxType)
            result.push_boxed(tc, boxError(t.toString()))
        }
        (task.queue as ConcBlockingQueueInstance).push_boxed(tc, result)
    }

    override fun close(tc: ThreadContext) {
        runCatching { proc?.outputStream?.close() }
    }

    fun kill(tc: ThreadContext) {
        proc?.destroy()
    }

    fun killForcibly(tc: ThreadContext) {
        proc?.destroyForcibly()
    }
}
