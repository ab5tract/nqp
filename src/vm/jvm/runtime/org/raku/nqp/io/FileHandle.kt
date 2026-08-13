package org.raku.nqp.io

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

class FileHandle(tc: ThreadContext, filename: String, mode: String) :
    SyncHandle(), IIOSeekable, IIOLockable {

    private lateinit var fc: FileChannel
    private var fileLock: FileLock? = null
    private var append = false

    fun resolveOpenMode(mode: String): Array<OpenOption>? {
        if (mode.isEmpty())
            return null

        var pos = 0
        val opts = mutableListOf<OpenOption>()

        when (mode[pos++]) {
            'r' -> opts.add(StandardOpenOption.READ)
            '-' -> opts.add(StandardOpenOption.WRITE)
            '+' -> { opts.add(StandardOpenOption.READ); opts.add(StandardOpenOption.WRITE) }

            /* legacy alias for "-c" or "-ct" if by itself */
            'w' -> {
                opts.add(StandardOpenOption.WRITE)
                opts.add(StandardOpenOption.CREATE)
                if (pos == mode.length)
                    opts.add(StandardOpenOption.TRUNCATE_EXISTING)
            }

            else -> return null
        }

        while (pos < mode.length) when (mode[pos++]) {
            'a' -> opts.add(StandardOpenOption.APPEND)
            'c' -> opts.add(StandardOpenOption.CREATE)
            't' -> opts.add(StandardOpenOption.TRUNCATE_EXISTING)
            'x' -> opts.add(StandardOpenOption.CREATE_NEW)
            else -> return null
        }

        /* work around differences between Perl 6 and FileChannel.open */
        if (opts.contains(StandardOpenOption.READ) && opts.contains(StandardOpenOption.APPEND)) {
            /* APPEND may not be used in conjunction with READ. */
            append = true
            opts.remove(StandardOpenOption.APPEND)
        }

        return opts.toTypedArray()
    }

    init {
        try {
            val p = File(filename).toPath()
            if (Files.isDirectory(p))
                ExceptionHandling.dieInternal(tc, "Tried to open directory $filename")
            val opts = resolveOpenMode(mode)
            if (opts == null)
                ExceptionHandling.dieInternal(tc, "Unhandled file open mode '$mode'")
            fc = FileChannel.open(p, *opts!!)
            chan = fc
            setEncoding(tc, Charset.forName("UTF-8"))
            useWriteBuffer = true
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            if (chan.isOpen) {
                close(tc)
            }
        })
    }

    override fun write(tc: ThreadContext, array: ByteArray): Long {
        if (append) {
            try {
                fc.position(fc.size())
            } catch (e: IOException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
            /* Reset readBuffer and eof after calling fc.position. */
            readBuffer = null
            eof = false
        }
        return super.write(tc, array)
    }

    override fun print(tc: ThreadContext, s: String): Long {
        if (append) {
            try {
                fc.position(fc.size())
            } catch (e: IOException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
            /* Reset readBuffer and eof after calling fc.position. */
            readBuffer = null
            eof = false
        }
        return super.print(tc, s)
    }

    override fun seek(tc: ThreadContext, offset: Long, whence: Long) {
        try {
            flushWriteBuffer(tc)
            when (whence.toInt()) {
                0 -> fc.position(offset)
                1 -> fc.position(fc.position() + offset)
                2 -> fc.position(fc.size() + offset)
                else -> throw ExceptionHandling.dieInternal(tc, "Invalid seek mode")
            }
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        } catch (e: IllegalArgumentException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        /* Reset readBuffer since content is out of sync after fc.position. */
        readBuffer = null
        /* Reset eof since it might have changed; needs to be checked anew. */
        eof = false
    }

    override fun tell(tc: ThreadContext): Long {
        try {
            flushWriteBuffer(tc)
            val position = fc.position()
            return if (readBuffer != null) position - readBuffer.remaining() else position
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun lock(tc: ThreadContext, flag: Long) {
        if (fileLock != null && fileLock!!.acquiredBy() === fc) {
            /* XXX: *might* not be quite the exact condition we want,
             *      which more precisely would be "are we the thread that locked?"
             *      but the Lock doesn't know which Thread it came from, afaict.
             *      In any case, to match nqp-m behavior, we just don't do anything.
             */
        }
        else {
            val shared = (flag.toInt() and 1) != 0
            val blocking = ((flag.toInt() shr 4) and 1) == 0
            if (blocking) {
                try {
                    fileLock = fc.lock(0, Long.MAX_VALUE, shared)
                } catch (e: IOException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                } catch (e: IllegalArgumentException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                } catch (e: IllegalStateException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                }
            }
            else {
                try {
                    fileLock = fc.tryLock(0, Long.MAX_VALUE, shared)
                    if (fileLock == null)
                        throw ExceptionHandling.dieInternal(tc,
                            "Failed to lock filehandle (locked by other program")
                } catch (e: IOException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                } catch (e: IllegalArgumentException) {
                    throw ExceptionHandling.dieInternal(tc, e)
                }
            }
        }
    }

    override fun unlock(tc: ThreadContext) {
        try {
            fileLock?.release()
            fileLock = null
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        } catch (e: IllegalArgumentException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun flush(tc: ThreadContext) {
        try {
            flushWriteBuffer(tc)
            fc.force(false)
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun eof(tc: ThreadContext): Boolean {
        if (eof)
            return true
        else if (readBuffer != null && readBuffer.remaining() > 0)
            return false
        else {
            return try {
                eof = fc.size() > 0 && fc.position() >= fc.size()
                eof
            } catch (e: Exception) {
                eof = true
                true
            }
        }
    }
}
