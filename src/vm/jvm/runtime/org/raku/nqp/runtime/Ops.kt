package org.raku.nqp.runtime

import com.sun.management.OperatingSystemMXBean
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.lang.ProcessBuilder.Redirect
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.management.ManagementFactory
import java.lang.reflect.Field
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.InetAddress
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.ByteBuffer
import java.nio.BufferUnderflowException
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CoderResult
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NotLinkException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.Normalizer
import java.util.AbstractMap
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.EnumSet
import java.util.HashMap
import java.util.Properties
import java.util.TimerTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntComparators

import org.raku.nqp.io.AsyncFileHandle
import org.raku.nqp.io.FileHandle
import org.raku.nqp.io.IIOAsyncReadable
import org.raku.nqp.io.IIOAsyncWritable
import org.raku.nqp.io.IIOBindable
import org.raku.nqp.io.IIOCancelable
import org.raku.nqp.io.IIOClosable
import org.raku.nqp.io.IIOLockable
import org.raku.nqp.io.IIOSeekable
import org.raku.nqp.io.IIOSyncReadable
import org.raku.nqp.io.IIOSyncWritable
import org.raku.nqp.io.IIOPossiblyTTY
import org.raku.nqp.io.SyncProcessHandle
import org.raku.nqp.io.ProcessChannel
import org.raku.nqp.io.ServerSocketHandle
import org.raku.nqp.io.SocketHandle
import org.raku.nqp.io.StandardReadHandle
import org.raku.nqp.io.StandardWriteHandle
import org.raku.nqp.jast2bc.JASTCompiler
import org.raku.nqp.dispatch.BindFailure
import org.raku.nqp.sixmodel.BoolificationSpec
import org.raku.nqp.sixmodel.Boxable
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.ContainerConfigurer
import org.raku.nqp.sixmodel.ContainerSpec
import org.raku.nqp.sixmodel.InvocationSpec
import org.raku.nqp.sixmodel.NativeRefContainerSpec
import org.raku.nqp.sixmodel.ParameterizedType
import org.raku.nqp.sixmodel.ParametricType
import org.raku.nqp.sixmodel.REPRRegistry
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.AsyncTaskInstance
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance
import org.raku.nqp.sixmodel.reprs.ConcBlockingQueueInstance
import org.raku.nqp.sixmodel.reprs.ConditionVariable
import org.raku.nqp.sixmodel.reprs.ConditionVariableInstance
import org.raku.nqp.sixmodel.reprs.ContextRef
import org.raku.nqp.sixmodel.reprs.ContextRefInstance
import org.raku.nqp.sixmodel.reprs.DecoderInstance
import org.raku.nqp.sixmodel.reprs.IOHandleInstance
import org.raku.nqp.sixmodel.reprs.MultiCacheInstance
import org.raku.nqp.sixmodel.reprs.NFA
import org.raku.nqp.sixmodel.reprs.NFAInstance
import org.raku.nqp.sixmodel.reprs.NFAStateInfo
import org.raku.nqp.sixmodel.reprs.NativeRefInstance
import org.raku.nqp.sixmodel.reprs.NativeRefInstanceAttribute
import org.raku.nqp.sixmodel.reprs.NativeRefInstanceIntLex
import org.raku.nqp.sixmodel.reprs.NativeRefInstanceMultidim
import org.raku.nqp.sixmodel.reprs.NativeRefInstanceNumLex
import org.raku.nqp.sixmodel.reprs.NativeRefInstancePositional
import org.raku.nqp.sixmodel.reprs.NativeRefInstanceStrLex
import org.raku.nqp.sixmodel.reprs.NativeRefREPRData
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance
import org.raku.nqp.sixmodel.reprs.P6OpaqueREPRData
import org.raku.nqp.sixmodel.reprs.P6bigintInstance
import org.raku.nqp.sixmodel.reprs.P6int
import org.raku.nqp.sixmodel.reprs.P6num
import org.raku.nqp.sixmodel.reprs.P6str
import org.raku.nqp.sixmodel.reprs.ReentrantMutexInstance
import org.raku.nqp.sixmodel.reprs.SCRefInstance
import org.raku.nqp.sixmodel.reprs.SemaphoreInstance
import org.raku.nqp.sixmodel.reprs.VMArray
import org.raku.nqp.sixmodel.reprs.VMArrayInstance
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i32
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i8
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u32
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u8
import org.raku.nqp.sixmodel.reprs.VMExceptionInstance
import org.raku.nqp.sixmodel.reprs.VMHash
import org.raku.nqp.sixmodel.reprs.VMHashInstance
import org.raku.nqp.sixmodel.reprs.VMIterInstance
import org.raku.nqp.sixmodel.reprs.VMNull
import org.raku.nqp.sixmodel.reprs.VMNullInstance
import org.raku.nqp.sixmodel.reprs.VMThreadInstance


/**
 * Contains complex operations that are more involved than the simple ops that the
 * JVM makes available.
 */
object Ops {
    private var theVMNull: SixModelObject? = null

    /* I/O opcodes */
    @JvmStatic
    fun print(v: String?, tc: ThreadContext): String? {
        tc.gc.out.print(v)
        return v
    }

    @JvmStatic
    fun say(v: String?, tc: ThreadContext): String? {
        tc.gc.out.println(v)
        return v
    }

    const val STAT_EXISTS             =  0
    const val STAT_FILESIZE           =  1
    const val STAT_ISDIR              =  2
    const val STAT_ISREG              =  3
    const val STAT_ISDEV              =  4
    const val STAT_CREATETIME         =  5
    const val STAT_ACCESSTIME         =  6
    const val STAT_MODIFYTIME         =  7
    const val STAT_CHANGETIME         =  8
    const val STAT_BACKUPTIME         =  9
    const val STAT_UID                = 10
    const val STAT_GID                = 11
    const val STAT_ISLNK              = 12
    const val STAT_PLATFORM_DEV       = -1
    const val STAT_PLATFORM_INODE     = -2
    const val STAT_PLATFORM_MODE      = -3
    const val STAT_PLATFORM_NLINKS    = -4
    const val STAT_PLATFORM_DEVTYPE   = -5
    const val STAT_PLATFORM_BLOCKSIZE = -6
    const val STAT_PLATFORM_BLOCKS    = -7

    const val MAX_GRAPHEMES           = 2147483647

    private fun windows_native_changetime(filename: String?): Long =
        WindowsChangeTime.of(filename)

    @JvmStatic
    fun stat(filename: String?, status: Long): Long {
        return stat_internal(filename, status)
    }

    @JvmStatic
    fun lstat(filename: String?, status: Long): Long {
        return stat_internal(filename, status, LinkOption.NOFOLLOW_LINKS)
    }

    @JvmStatic
    fun stat_internal(filename: String?, status: Long, vararg linkOption: LinkOption): Long {
        var rval: Long = -1

        when (status.toInt()) {
            STAT_EXISTS ->
                rval = if (Files.exists(Paths.get(filename), *linkOption)) 1 else 0

            STAT_FILESIZE ->
                try {
                    rval = Files.readAttributes(Paths.get(filename), BasicFileAttributes::class.java, *linkOption).size()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_ISDIR ->
                try {
                    rval = if (Files.getAttribute(Paths.get(filename), "basic:isDirectory", *linkOption) as Boolean) 1 else 0
                } catch (e: NoSuchFileException) {
                    rval = 0
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_ISREG ->
                try {
                    rval = if (Files.getAttribute(Paths.get(filename), "basic:isRegularFile", *linkOption) as Boolean) 1 else 0
                } catch (e: NoSuchFileException) {
                    rval = 0
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_ISDEV ->
                try {
                    rval = if (Files.getAttribute(Paths.get(filename), "basic:isOther", *linkOption) as Boolean) 1 else 0
                } catch (e: NoSuchFileException) {
                    rval = 0
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_CREATETIME ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "basic:creationTime", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_ACCESSTIME ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "basic:lastAccessTime", *linkOption) as FileTime).to(TimeUnit.SECONDS)
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_MODIFYTIME ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "basic:lastModifiedTime", *linkOption) as FileTime).to(TimeUnit.SECONDS)
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_CHANGETIME ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:ctime", *linkOption) as FileTime).to(TimeUnit.SECONDS)
                } catch (oue: UnsupportedOperationException) {
                    if (System.getProperty("os.name").lowercase().indexOf("win") >= 0) {
                        rval = windows_native_changetime(filename)
                    } else {
                        rval = -1
                    }
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_BACKUPTIME ->
                rval = -1

            STAT_UID ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:uid", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_GID ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:gid", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_ISLNK ->
                try {
                    rval = if (Files.getAttribute(Paths.get(filename), "basic:isSymbolicLink", LinkOption.NOFOLLOW_LINKS) as Boolean) 1 else 0
                } catch (e: NoSuchFileException) {
                    rval = 0
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_DEV ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:dev", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_INODE ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:ino", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_MODE ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:mode", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_NLINKS ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:nlink", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_DEVTYPE ->
                try {
                    rval = (Files.getAttribute(Paths.get(filename), "unix:rdev", *linkOption) as Number).toLong()
                } catch (e: Exception) {
                    rval = -1
                }

            STAT_PLATFORM_BLOCKSIZE ->
                throw UnsupportedOperationException("STAT_PLATFORM_BLOCKSIZE not supported")

            STAT_PLATFORM_BLOCKS ->
                throw UnsupportedOperationException("STAT_PLATFORM_BLOCKS not supported")

            else -> {}
        }

        return rval
    }

    @JvmStatic
    fun stat_time(filename: String?, status: Long): Double {
        return stat_time_internal(filename, status)
    }

    @JvmStatic
    fun lstat_time(filename: String?, status: Long): Double {
        return stat_time_internal(filename, status, LinkOption.NOFOLLOW_LINKS)
    }

    private fun stat_time_internal(filename: String?, status: Long, vararg linkOption: LinkOption): Double {
        val attrName: String
        when (status.toInt()) {
            STAT_CREATETIME ->
                attrName = "basic:creationTime"
            STAT_ACCESSTIME ->
                attrName = "basic:lastAccessTime"
            STAT_MODIFYTIME ->
                attrName = "basic:lastModifiedTime"
            STAT_CHANGETIME ->
                attrName = "unix:ctime"
            else ->
                return -1.0
        }

        try {
            val ft = Files.getAttribute(Paths.get(filename), attrName, *linkOption) as FileTime
            return (ft.to(TimeUnit.NANOSECONDS) / 1000000000).toDouble()
        } catch (oue: UnsupportedOperationException) {
            if (status.toInt() == STAT_CHANGETIME && System.getProperty("os.name").lowercase().indexOf("win") >= 0) {
                return windows_native_changetime(filename).toDouble()
            } else {
                return -1.0
            }
        } catch (e: Exception) {
            return -1.0
        }
    }

    @JvmStatic
    fun open(path: String?, mode: String?, tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        h.handle = FileHandle(tc, path!!, mode!!)
        return h
    }

    /* Async file IO. openasync/spurtasync originally existed 2013-2020 and
     * were removed as unwired; reinstated (with the previously missing read
     * side) to make AsyncFileHandle reachable and testable. */
    @JvmStatic
    fun openasync(path: String?, mode: String?, tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        h.handle = AsyncFileHandle(tc, path!!, mode!!)
        return h
    }

    @JvmStatic
    fun spurtasync(obj: SixModelObject?, resultType: SixModelObject?, data: SixModelObject?,
            done: SixModelObject?, error: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOAsyncWritable)
                handle.spurt(tc, resultType!!, data!!, done!!, error!!)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support async spurt")
        }
        else {
            die_s("spurtasync requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun slurpasync(obj: SixModelObject?, resultType: SixModelObject?,
            done: SixModelObject?, error: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOAsyncReadable)
                handle.slurp(tc, resultType!!, done!!, error!!)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support async slurp")
        }
        else {
            die_s("slurpasync requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun linesasync(obj: SixModelObject?, resultType: SixModelObject?, chomp: Long,
            queue: SixModelObject?, done: SixModelObject?, error: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOAsyncReadable) {
                if (queue is ConcBlockingQueueInstance)
                    handle.lines(tc, resultType!!, chomp != 0L,
                        queue.queue, done!!, error!!)
                else
                    throw ExceptionHandling.dieInternal(tc,
                        "linesasync requires a queue with the ConcBlockingQueue REPR")
            }
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support async lines")
        }
        else {
            die_s("linesasync requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun socket(listener: Long, tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        if (listener == 0L) {
            h.handle = SocketHandle(tc)
        } else if (listener > 0) {
            h.handle = ServerSocketHandle(tc)
        } else {
            ExceptionHandling.dieInternal(tc,
                "Socket handle does not support a negative listener value")
        }
        return h
    }

    const val SOCKET_FAMILY_UNSPEC = 0
    const val SOCKET_FAMILY_INET   = 1
    const val SOCKET_FAMILY_INET6  = 2
    const val SOCKET_FAMILY_UNIX   = 3

    @JvmStatic
    fun connect(obj: SixModelObject?, host: String?, port: Long, family: Long, tc: ThreadContext): SixModelObject? {
        val h = obj as IOHandleInstance

        when (family.toInt()) {
            SOCKET_FAMILY_UNSPEC, SOCKET_FAMILY_INET, SOCKET_FAMILY_INET6 -> {
                val handle = h.handle
                if (handle is SocketHandle) {
                    handle.connect(tc, host!!, port.toInt())
                } else {
                    ExceptionHandling.dieInternal(tc,
                        "This handle does not support connect")
                }
            }
            SOCKET_FAMILY_UNIX ->
                ExceptionHandling.dieInternal(tc,
                    "UNIX sockets are not supported on the JVM")
            else ->
                ExceptionHandling.dieInternal(tc,
                    "Unsupported socket family: " + family.toString())
        }

        return obj
    }

    @JvmStatic
    fun bindsock(obj: SixModelObject?, host: String?, port: Long, family: Long, backlog: Long, tc: ThreadContext): SixModelObject? {
        val h = obj as IOHandleInstance

        when (family.toInt()) {
            SOCKET_FAMILY_UNSPEC, SOCKET_FAMILY_INET, SOCKET_FAMILY_INET6 -> {
                val handle = h.handle
                if (handle is IIOBindable) {
                    handle.bind(tc, host!!, port.toInt(), backlog.toInt())
                } else {
                    ExceptionHandling.dieInternal(tc,
                        "This handle does not support bind")
                }
            }
            SOCKET_FAMILY_UNIX ->
                ExceptionHandling.dieInternal(tc,
                    "UNIX sockets are not supported on the JVM")
            else ->
                ExceptionHandling.dieInternal(tc,
                    "Unsupported socket family: " + family.toString())
        }

        return obj
    }

    @JvmStatic
    fun accept(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val listener = obj as IOHandleInstance
        val listenerHandle = listener.handle
        if (listenerHandle is ServerSocketHandle) {
            val handle = listenerHandle.accept(tc)
            if (handle != null) {
                val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
                val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
                h.handle = handle
                return h
            }
        } else {
            ExceptionHandling.dieInternal(tc,
                "This handle does not support accept")
        }
        return null
    }

    @JvmStatic
    fun getport(obj: SixModelObject?, tc: ThreadContext): Long {
        val h = obj as IOHandleInstance
        val handle = h.handle
        if (handle is ServerSocketHandle) {
            return handle.listenPort.toLong()
        } else {
            ExceptionHandling.dieInternal(tc,
                "This handle does not support getport")
        }
        return -1
    }

    @JvmStatic
    fun filereadable(path: String?, tc: ThreadContext): Long {
        val pathO: Path
        var res: Long
        try {
            pathO = Paths.get(path)
            res = if (Files.isReadable(pathO)) 1 else 0
        }
        catch (e: Exception) {
            die_s(e.message, tc)
            res = -1 /* unreachable */
        }
        return res
    }

    @JvmStatic
    fun filewritable(path: String?, tc: ThreadContext): Long {
        val pathO: Path
        var res: Long
        try {
            pathO = Paths.get(path)
            res = if (Files.isWritable(pathO)) 1 else 0
        }
        catch (e: Exception) {
            die_s(e.message, tc)
            res = -1 /* unreachable */
        }
        return res
    }

    @JvmStatic
    fun fileexecutable(path: String?, tc: ThreadContext): Long {
        val pathO: Path
        var res: Long
        try {
            pathO = Paths.get(path)
            res = if (Files.isExecutable(pathO)) 1 else 0
        }
        catch (e: Exception) {
            die_s(e.message, tc)
            res = -1 /* unreachable */
        }
        return res
    }

    @JvmStatic
    fun fileislink(path: String?, tc: ThreadContext): Long {
        val pathO: Path
        var res: Long
        try {
            pathO = Paths.get(path)
            res = if (Files.isSymbolicLink(pathO)) 1 else 0
        }
        catch (e: Exception) {
            die_s(e.message, tc)
            res = -1 /* unreachable */
        }
        return res
    }

    @JvmStatic
    fun getstdin(tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        h.handle = StandardReadHandle(tc, tc.gc.`in`)
        return h
    }

    @JvmStatic
    fun getstdout(tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        h.handle = StandardWriteHandle(tc, tc.gc.out)
        return h
    }

    @JvmStatic
    fun getstderr(tc: ThreadContext): SixModelObject {
        val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
        val h = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
        h.handle = StandardWriteHandle(tc, tc.gc.err)
        return h
    }

    @JvmStatic
    fun seekfh(obj: SixModelObject?, offset: Long, whence: Long, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOSeekable) {
                handle.seek(tc, offset, whence)
            }
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support seek")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "seekfh requires an object with the IOHandle REPR")
        }
        return null
    }

    @JvmStatic
    fun tellfh(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOSeekable)
                return handle.tell(tc)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support tell")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "tellfh requires an object with the IOHandle REPR")
        }
    }

    @JvmStatic
    fun lockfh(obj: SixModelObject?, flag: Long, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOLockable) {
                handle.lock(tc, flag)
                return obj
            }
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support locking")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "lockfh requires an object with the IOHandle REPR")
        }
    }

    @JvmStatic
    fun unlockfh(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOLockable) {
                handle.unlock(tc)
                return obj
            }
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support locking")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "unlockfh requires an object with the IOHandle REPR")
        }
    }

    @JvmStatic
    fun readfh(io: SixModelObject?, res: SixModelObject?, bytes: Long, tc: ThreadContext): SixModelObject? {
        if (io is IOHandleInstance) {
            val handle = io.handle
            if (handle is IIOSyncReadable) {
                if (res is VMArrayInstance_i8) {
                    val array = handle.read(tc, bytes.toInt())
                    res.elems = array.size
                    res.start = 0
                    res.slots = array

                    return res
                } else if (res is VMArrayInstance_u8) {
                    val array = handle.read(tc, bytes.toInt())
                    res.elems = array.size
                    res.start = 0
                    res.slots = array

                    return res
                } else {
                    throw ExceptionHandling.dieInternal(tc,
                        "readfh requires a Buf[int8] or a Buf[uint8]")
                }
            } else {
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support read")
            }
        } else {
            throw ExceptionHandling.dieInternal(tc,
                "readfh requires an object with the IOHandle REPR")
        }
    }

    @JvmStatic
    fun writefh(obj: SixModelObject?, buf: SixModelObject?, tc: ThreadContext): Long {
        val bb = Buffers.unstashBytes(buf!!, tc)
        val written: Long
        if (obj is IOHandleInstance) {
            val bytesToWrite = ByteArray(bb.limit())
            bb.get(bytesToWrite)
            val handle = obj.handle
            if (handle is IIOSyncWritable)
                written = handle.write(tc, bytesToWrite)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support write")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "writefh requires an object with the IOHandle REPR")
        }
        return written
    }

    @JvmStatic
    fun flushfh(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOSyncWritable)
                handle.flush(tc)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support flush")
        }
        else {
            die_s("flushfh requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun readlink(path: String?, tc: ThreadContext): String {
        try {
            return Files.readSymbolicLink(File(path!!).toPath()).toString()
        } catch (e: NotLinkException) {
            throw ExceptionHandling.dieInternal(tc, path + " is not a symbolic link")
        } catch (e: IOException) {
            throw ExceptionHandling.dieInternal(tc, "Failed to readlink file: " + e)
        }
    }

    @JvmStatic
    fun eoffh(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOSyncReadable)
                return if (handle.eof(tc)) 1 else 0
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support eof")
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "eoffh requires an object with the IOHandle REPR")
        }
    }

    @JvmStatic
    fun closefh(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOClosable)
                handle.close(tc)
            else
                throw ExceptionHandling.dieInternal(tc,
                    "This handle does not support close")
        }
        else {
            die_s("closefh requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun setbuffersizefh(obj: SixModelObject?, size: Long, tc: ThreadContext): SixModelObject? {
        if (obj is IOHandleInstance) {
            // TODO: what to do with instances of other classes like BOOTIO?
            val handle = obj.handle
            if (handle is IIOSyncWritable)
                handle.setBufferSize(tc, size)
        }
        else {
            die_s("setbuffersizefh requires an object with the IOHandle REPR", tc)
        }
        return obj
    }

    @JvmStatic
    fun isttyfh(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is IOHandleInstance) {
            val handle = obj.handle
            if (handle is IIOPossiblyTTY)
                return if (handle.isTTY(tc)) 1 else 0
            else
                return 0
        }
        else {
            die_s("isttyfh requires an object with the IOHandle REPR", tc)
        }
        return -1
    }

    @JvmStatic
    fun filenofh(obj: SixModelObject?, tc: ThreadContext): Long {
        /* XXX Implement this */
        return -1
    }

    /* NOTE: the mode masks below were octal literals in the Java original;
     * Kotlin has no octal literals, so they are spelled in decimal. */
    @JvmStatic
    fun modeToPosixFilePermission(mode: Long): Set<PosixFilePermission> {
        val perms = EnumSet.noneOf(PosixFilePermission::class.java)
        if ((mode and 1L) != 0L) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        if ((mode and 2L) != 0L) perms.add(PosixFilePermission.OTHERS_WRITE)
        if ((mode and 4L) != 0L) perms.add(PosixFilePermission.OTHERS_READ)
        if ((mode and 8L) != 0L) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if ((mode and 16L) != 0L) perms.add(PosixFilePermission.GROUP_WRITE)
        if ((mode and 32L) != 0L) perms.add(PosixFilePermission.GROUP_READ)
        if ((mode and 64L) != 0L) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if ((mode and 128L) != 0L) perms.add(PosixFilePermission.OWNER_WRITE)
        if ((mode and 256L) != 0L) perms.add(PosixFilePermission.OWNER_READ)
        return perms
    }

    @JvmStatic
    fun chmod(path: String?, mode: Long, tc: ThreadContext): Long {
        val pathO: Path
        try {
            pathO = Paths.get(path)
            val perms = modeToPosixFilePermission(mode)
            Files.setPosixFilePermissions(pathO, perms)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun chown(path: String?, uid: Long, gid: Long, tc: ThreadContext): Long {
        val pathO: Path
        try {
            pathO = Paths.get(path)
            Files.setAttribute(pathO, "unix:uid", uid)
            Files.setAttribute(pathO, "unix:gid", gid)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun unlink(path: String?, tc: ThreadContext): Long {
        val pathO = Paths.get(path)
        if (Files.isDirectory(pathO)) {
            die_s("Failed to delete file: is a directory", tc)
        }
        else {
            try {
                Files.deleteIfExists(pathO)
            }
            catch (e: Exception) {
                die_s(IOExceptionMessages.message(e), tc)
            }
        }
        return 0
    }

    @JvmStatic
    fun rmdir(path: String?, tc: ThreadContext): Long {
        val pathO = Paths.get(path)
        if (!Files.isDirectory(pathO)) {
            die_s("Failed to rmdir: not a directory", tc)
        }
        else {
            try {
                Files.delete(pathO)
            }
            catch (e: Exception) {
                die_s(IOExceptionMessages.message(e), tc)
            }
        }
        return 0
    }

    @JvmStatic
    fun cwd(): String {
        return System.getProperty("user.dir")
    }

    @JvmStatic
    fun chdir(path: String?, tc: ThreadContext): String? {
        die_s("chdir is not available on JVM", tc)
        return null
    }

    @JvmStatic
    fun mkdir(path: String?, mode: Long, tc: ThreadContext): Long {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (os.indexOf("win") >= 0)
                Files.createDirectories(Paths.get(path))
            else
                Files.createDirectories(Paths.get(path),
                    PosixFilePermissions.asFileAttribute(modeToPosixFilePermission(mode)))
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun rename(before: String?, after: String?, tc: ThreadContext): Long {
        val beforeO = Paths.get(before)
        val afterO = Paths.get(after)
        try {
            Files.move(beforeO, afterO, StandardCopyOption.REPLACE_EXISTING)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun copy(before: String?, after: String?, tc: ThreadContext): Long {
        val beforeO = Paths.get(before)
        val afterO = Paths.get(after)
        try {
            Files.copy(beforeO, afterO, StandardCopyOption.REPLACE_EXISTING)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun link(before: String?, after: String?, tc: ThreadContext): Long {
        val beforeO = Paths.get(before)
        val afterO = Paths.get(after)
        try {
            Files.createLink(afterO, beforeO)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun gethostname(): String? {
        try {
            val hostname = InetAddress.getLocalHost().hostName
            return hostname
        } catch (e: Exception) {
            return null
        }
    }

    const val PIPE_INHERIT        = 1
    const val PIPE_IGNORE         = 2
    const val PIPE_CAPTURE        = 4
    const val PIPE_INHERIT_IN     = 1
    const val PIPE_IGNORE_IN      = 2
    const val PIPE_CAPTURE_IN     = 4
    const val PIPE_INHERIT_OUT    = 8
    const val PIPE_IGNORE_OUT     = 16
    const val PIPE_CAPTURE_OUT    = 32
    const val PIPE_INHERIT_ERR    = 64
    const val PIPE_IGNORE_ERR     = 128
    const val PIPE_CAPTURE_ERR    = 256

    private fun setup_process_builder(tc: ThreadContext, pb: ProcessBuilder, `in`: SixModelObject?, out: SixModelObject?, err: SixModelObject?, flags: Long) {
        if ((flags and PIPE_INHERIT_IN.toLong()) == 0L || `in` is IOHandleInstance)
            pb.redirectInput(Redirect.PIPE)
        else
            pb.redirectInput(Redirect.INHERIT)

        if ((flags and PIPE_INHERIT_OUT.toLong()) == 0L || out is IOHandleInstance)
            pb.redirectOutput(Redirect.PIPE)
        else
            pb.redirectOutput(Redirect.INHERIT)

        if ((flags and PIPE_INHERIT_ERR.toLong()) == 0L || err is IOHandleInstance)
            pb.redirectError(Redirect.PIPE)
        else
            pb.redirectError(Redirect.INHERIT)
    }

    private fun setup_process_streams(tc: ThreadContext, process: Process, `in`: SixModelObject?, out: SixModelObject?, err: SixModelObject?, flags: Long) {
        if (`in` is IOHandleInstance) {
            if ((flags and PIPE_CAPTURE_IN.toLong()) != 0L)
                /* getOutputStream() returns the output stream connected to the normal input of the subprocess. */
                (`in`.handle as SyncProcessHandle).bindChannel(tc, process, process.outputStream)

            if ((flags and PIPE_INHERIT_IN.toLong()) != 0L) {
                /* If our stdin is connected to an output stream of another process, we need to let it run in a thread. */
                val pc = ProcessChannel(process, process.outputStream,
                    ((`in`.handle as SyncProcessHandle).chan as ProcessChannel).`in`!!)
                /* A blocking pump: exactly what a virtual thread is for. */
                Thread.ofVirtual().start(pc)
            }
        }

        if ((flags and PIPE_CAPTURE_OUT.toLong()) != 0L && out is IOHandleInstance)
            /* getInputStream() returns the input stream connected to the normal output of the subprocess. */
            (out.handle as SyncProcessHandle).bindChannel(tc, process, process.inputStream)

        if ((flags and PIPE_CAPTURE_ERR.toLong()) != 0L && err is IOHandleInstance)
            /* getErrorStream() returns the input stream connected to the error output of the subprocess. */
            (err.handle as SyncProcessHandle).bindChannel(tc, process, process.errorStream)
    }

    @JvmStatic
    fun symlink(before: String?, after: String?, tc: ThreadContext): Long {
        val beforeO = Paths.get(before)
        val afterO = Paths.get(after)
        try {
            Files.createSymbolicLink(afterO, beforeO)
        }
        catch (e: Exception) {
            die_s(IOExceptionMessages.message(e), tc)
        }
        return 0
    }

    @JvmStatic
    fun opendir(path: String?, tc: ThreadContext): SixModelObject? {
        try {
            val dirstrm = Files.newDirectoryStream(Paths.get(path))
            val IOType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.ioType!!
            val ioh = IOType.st.REPR.allocate(tc, IOType.st) as IOHandleInstance
            ioh.dirstrm = dirstrm
            ioh.diri = dirstrm.iterator()
            return ioh
        }
        catch (e: Exception) {
            die_s("nqp::opendir: unable to get a DirectoryStream", tc)
        }
        return null
    }

    @JvmStatic
    fun nextfiledir(obj: SixModelObject?, tc: ThreadContext): String? {
        try {
            if (obj is IOHandleInstance) {
                if (obj.dirstrm != null && obj.diri != null) {
                    if (obj.diri!!.hasNext()) {
                        return obj.diri!!.next().fileName.toString()
                    } else {
                        return null
                    }
                } else {
                    die_s("called nextfiledir on an IOHandle without a dirstream and/or iterator.", tc)
                }
            } else {
                die_s("nextfiledir requires an object with the IOHandle REPR", tc)
            }
        }
        catch (e: Exception) {
            die_s("nqp::nextfiledir: unhandled exception", tc)
        }
        return null
    }

    @JvmStatic
    fun closedir(obj: SixModelObject?, tc: ThreadContext): Long {
        try {
            if (obj is IOHandleInstance) {
                obj.diri = null
                obj.dirstrm!!.close()
                obj.dirstrm = null
            } else {
                die_s("closedir requires an object with the IOHandle REPR", tc)
            }
        }
        catch (e: Exception) {
            die_s("nqp::closedir: unhandled exception", tc)
        }
        return 0
    }

    /* Lexical lookup in current scope. */
    @JvmStatic
    fun getlex_i(cf: CallFrame, i: Int): Long { return cf.iLex!![i] }
    @JvmStatic
    fun getlex_u(cf: CallFrame, i: Int): Long { return cf.iLex!![i] }
    @JvmStatic
    fun getlex_n(cf: CallFrame, i: Int): Double { return cf.nLex!![i] }
    @JvmStatic
    fun getlex_s(cf: CallFrame, i: Int): String? { return cf.sLex!![i] }
    @JvmStatic
    fun getlex_o(cf: CallFrame, i: Int): SixModelObject? { return cf.oLexOrVivify(i) }

    /* Lexical binding in current scope. */
    @JvmStatic
    fun bindlex_i(v: Long, cf: CallFrame, i: Int): Long { cf.iLex!![i] = v; return v }
    @JvmStatic
    fun bindlex_u(v: Long, cf: CallFrame, i: Int): Long { cf.iLex!![i] = v; return v }
    @JvmStatic
    fun bindlex_n(v: Double, cf: CallFrame, i: Int): Double { cf.nLex!![i] = v; return v }
    @JvmStatic
    fun bindlex_s(v: String?, cf: CallFrame, i: Int): String? { cf.sLex!![i] = v; return v }
    @JvmStatic
    fun bindlex_o(v: SixModelObject?, cf: CallFrame, i: Int): SixModelObject? { cf.oLex!![i] = v; return v }

    /* Lexical lookup in outer scope. */
    @JvmStatic
    fun getlex_i_si(cf: CallFrame, i: Int, si: Int): Long {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        return frame.iLex!![i]
    }
    @JvmStatic
    fun getlex_u_si(cf: CallFrame, i: Int, si: Int): Long {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        return frame.iLex!![i]
    }
    @JvmStatic
    fun getlex_n_si(cf: CallFrame, i: Int, si: Int): Double {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        return frame.nLex!![i]
    }
    @JvmStatic
    fun getlex_s_si(cf: CallFrame, i: Int, si: Int): String? {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        return frame.sLex!![i]
    }
    @JvmStatic
    fun getlex_o_si(cf: CallFrame, i: Int, si: Int): SixModelObject? {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        return frame.oLexOrVivify(i)
    }

    /* Lexical binding in outer scope. */
    @JvmStatic
    fun bindlex_i_si(v: Long, cf: CallFrame, i: Int, si: Int): Long {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        frame.iLex!![i] = v
        return v
    }
    @JvmStatic
    fun bindlex_u_si(v: Long, cf: CallFrame, i: Int, si: Int): Long {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        frame.iLex!![i] = v
        return v
    }
    @JvmStatic
    fun bindlex_n_si(v: Double, cf: CallFrame, i: Int, si: Int): Double {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        frame.nLex!![i] = v
        return v
    }
    @JvmStatic
    fun bindlex_s_si(v: String?, cf: CallFrame, i: Int, si: Int): String? {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        frame.sLex!![i] = v
        return v
    }
    @JvmStatic
    fun bindlex_o_si(v: SixModelObject?, cf: CallFrame, i: Int, si: Int): SixModelObject? {
        var frame = cf
        var s = si
        while (s-- > 0)
            frame = frame.outer!!
        frame.oLex!![i] = v
        return v
    }

    /* Lexical lookup by name. */
    @JvmStatic
    fun getlex(name: String, tc: ThreadContext): SixModelObject? {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.oLexOrVivify(found)
            curFrame = curFrame.outer
        }
        return createNull(tc)
    }
    @JvmStatic
    fun getlex_i(name: String, tc: ThreadContext): Long {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.iTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.iLex!![found]
            curFrame = curFrame.outer
        }
        if (System.getenv("NQP_EH_DEBUG") != null) {
            val sb = StringBuilder("getlex_i MISS '$name' outer chain:")
            var f = tc.curFrame
            var i = 0
            while (f != null && i < 8) {
                sb.append(" [").append(f.codeRef.name).append("]")
                f = f.outer
                i++
            }
            System.err.println(sb)
            var s: StaticCodeInfo? = tc.curFrame?.codeRef?.staticInfo
            val sb2 = StringBuilder("static chain:")
            var j = 0
            while (s != null && j < 8) {
                sb2.append(" [").append(s.oLexicalNames?.joinToString(",") ?: "-")
                   .append(if (s.iTryGetLexicalIdx(name) != -1) " HAS-$name" else "")
                   .append("]")
                s = s.outerStaticInfo
                j++
            }
            System.err.println(sb2)
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlex_u(name: String, tc: ThreadContext): Long {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.iTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.iLex!![found]
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlex_n(name: String, tc: ThreadContext): Double {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.nTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.nLex!![found]
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlex_s(name: String, tc: ThreadContext): String? {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.sTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.sLex!![found]
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlexouter(name: String, tc: ThreadContext): SixModelObject? {
        var curFrame = tc.frame.outer
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
            if (found != -1)
                return curFrame.oLexOrVivify(found)
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }

    /* Lexical binding by name. */
    @JvmStatic
    fun bindlex(name: String, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
            if (found != -1) {
                curFrame.oLex!![found] = value
                return value
            }
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun bindlex_i(name: String, value: Long, tc: ThreadContext): Long {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.iTryGetLexicalIdx(name)
            if (found != -1) {
                curFrame.iLex!![found] = value
                return value
            }
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun bindlex_u(name: String, value: Long, tc: ThreadContext): Long {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.uTryGetLexicalIdx(name)
            if (found != -1) {
                curFrame.iLex!![found] = value
                return value
            }
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun bindlex_n(name: String, value: Double, tc: ThreadContext): Double {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.nTryGetLexicalIdx(name)
            if (found != -1) {
                curFrame.nLex!![found] = value
                return value
            }
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun bindlex_s(name: String, value: String?, tc: ThreadContext): String? {
        var curFrame = tc.curFrame
        while (curFrame != null) {
            val found = curFrame.codeRef.staticInfo.sTryGetLexicalIdx(name)
            if (found != -1) {
                curFrame.sLex!![found] = value
                return value
            }
            curFrame = curFrame.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }

    /* Native lexical references. */
    @JvmStatic
    fun getlexref_i(tc: ThreadContext, idx: Int): SixModelObject {
        val cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.intLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
        ref.lexicals = cf.iLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_u(tc: ThreadContext, idx: Int): SixModelObject {
        val cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.uintLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
        ref.lexicals = cf.iLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_n(tc: ThreadContext, idx: Int): SixModelObject {
        val cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.numLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num lexical reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceNumLex
        ref.lexicals = cf.nLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_s(tc: ThreadContext, idx: Int): SixModelObject {
        val cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.strLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No str lexical reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceStrLex
        ref.lexicals = cf.sLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_i_si(tc: ThreadContext, idx: Int, si: Int): SixModelObject {
        var cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.intLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        var s = si
        while (s-- > 0)
            cf = cf.outer!!
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
        ref.lexicals = cf.iLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_u_si(tc: ThreadContext, idx: Int, si: Int): SixModelObject {
        var cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.uintLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        var s = si
        while (s-- > 0)
            cf = cf.outer!!
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
        ref.lexicals = cf.iLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_n_si(tc: ThreadContext, idx: Int, si: Int): SixModelObject {
        var cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.numLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num lexical reference type registered for current HLL")
        var s = si
        while (s-- > 0)
            cf = cf.outer!!
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceNumLex
        ref.lexicals = cf.nLex
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun getlexref_s_si(tc: ThreadContext, idx: Int, si: Int): SixModelObject {
        var cf = tc.frame
        val refType = cf.codeRef.staticInfo.compUnit.hllConfig.strLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No str lexical reference type registered for current HLL")
        var s = si
        while (s-- > 0)
            cf = cf.outer!!
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceStrLex
        ref.lexicals = cf.sLex
        ref.idx = idx
        return ref
    }
    /** Notes the declared width of the lexical a fresh native reference
     * points at, from the compiler's knowledge: the long slots themselves
     * are unsized, so a sized store could not truncate without this. The low
     * byte of the spec is the bit width, +256 marks unsigned, 32 alone marks
     * num32; 0 never gets here. */
    @JvmStatic
    fun sizedref(ref: SixModelObject?, spec: Long, tc: ThreadContext): SixModelObject? {
        when (ref) {
            is NativeRefInstanceIntLex -> ref.sizeSpec = spec.toInt()
            is NativeRefInstanceNumLex -> ref.sizeSpec = spec.toInt()
            else -> { }
        }
        return ref
    }

    /* A native lexical reference over a slot of a given frame, for the
     * code engine: its lexical sites resolve the declaring frame and slot
     * themselves, so this only allocates. Type is the wire type (1 int,
     * 2 num, 3 str); spec is sizedref's width encoding, 0 for full width.
     * The reference type comes from the running frame's HLL, exactly as
     * getlexref_* takes it from tc.frame. */
    @JvmStatic
    fun lexref_at(target: CallFrame, type: Int, idx: Int, spec: Int,
                  cur: CallFrame, tc: ThreadContext): SixModelObject {
        val hll = cur.codeRef.staticInfo.compUnit.hllConfig
        when (type) {
            1 -> {
                val refType = hll.intLexRef
                if (refType == null || isnull(refType) == 1L)
                    throw ExceptionHandling.dieInternal(tc,
                        "No int lexical reference type registered for current HLL")
                val ref = refType.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
                ref.lexicals = target.iLex
                ref.idx = idx
                ref.sizeSpec = spec
                return ref
            }
            2 -> {
                val refType = hll.numLexRef
                if (refType == null || isnull(refType) == 1L)
                    throw ExceptionHandling.dieInternal(tc,
                        "No num lexical reference type registered for current HLL")
                val ref = refType.st.REPR.allocate(tc, refType.st) as NativeRefInstanceNumLex
                ref.lexicals = target.nLex
                ref.idx = idx
                ref.sizeSpec = spec
                return ref
            }
            3 -> {
                val refType = hll.strLexRef
                if (refType == null || isnull(refType) == 1L)
                    throw ExceptionHandling.dieInternal(tc,
                        "No str lexical reference type registered for current HLL")
                val ref = refType.st.REPR.allocate(tc, refType.st) as NativeRefInstanceStrLex
                ref.lexicals = target.sLex
                ref.idx = idx
                return ref
            }
            else -> throw ExceptionHandling.dieInternal(tc,
                "Cannot take a reference to a non-native lexical")
        }
    }

    @JvmStatic
    fun getlexref_i(name: String, tc: ThreadContext): SixModelObject {
        var cf = tc.curFrame
        val refType = cf!!.codeRef.staticInfo.compUnit.hllConfig.intLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        while (cf != null) {
            val found = cf.codeRef.staticInfo.iTryGetLexicalIdx(name)
            if (found != -1) {
                val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
                ref.lexicals = cf.iLex
                ref.idx = found
                return ref
            }
            cf = cf.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlexref_u(name: String, tc: ThreadContext): SixModelObject {
        var cf = tc.curFrame
        val refType = cf!!.codeRef.staticInfo.compUnit.hllConfig.uintLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int lexical reference type registered for current HLL")
        while (cf != null) {
            val found = cf.codeRef.staticInfo.iTryGetLexicalIdx(name)
            if (found != -1) {
                val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceIntLex
                ref.lexicals = cf.iLex
                ref.idx = found
                return ref
            }
            cf = cf.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlexref_n(name: String, tc: ThreadContext): SixModelObject {
        var cf = tc.curFrame
        val refType = cf!!.codeRef.staticInfo.compUnit.hllConfig.numLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num lexical reference type registered for current HLL")
        while (cf != null) {
            val found = cf.codeRef.staticInfo.nTryGetLexicalIdx(name)
            if (found != -1) {
                val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceNumLex
                ref.lexicals = cf.nLex
                ref.idx = found
                return ref
            }
            cf = cf.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }
    @JvmStatic
    fun getlexref_s(name: String, tc: ThreadContext): SixModelObject {
        var cf = tc.curFrame
        val refType = cf!!.codeRef.staticInfo.compUnit.hllConfig.strLexRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No str lexical reference type registered for current HLL")
        while (cf != null) {
            val found = cf.codeRef.staticInfo.sTryGetLexicalIdx(name)
            if (found != -1) {
                val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceStrLex
                ref.lexicals = cf.sLex
                ref.idx = found
                return ref
            }
            cf = cf.outer
        }
        throw ExceptionHandling.dieInternal(tc, "Lexical '" + name + "' not found")
    }

    /* Dynamic lexicals. */
    @JvmStatic
    fun bindlexdyn(name: String, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        var curFrame = tc.frame.caller
        while (curFrame != null) {
            val idx = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
            if (idx != -1) {
                curFrame.oLex!![idx] = value
                return value
            }
            curFrame = curFrame.caller
        }
        throw ExceptionHandling.dieInternal(tc, "Dynamic variable '" + name + "' not found")
    }
    @JvmStatic
    fun getlexdyn(name: String, tc: ThreadContext): SixModelObject? {
        var curFrame = tc.frame.caller
        while (curFrame != null) {
            val idx = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
            if (idx != -1)
                return curFrame.oLexOrVivify(idx)
            curFrame = curFrame.caller
        }
        return createNull(tc)
    }
    @JvmStatic
    fun getlexcaller(name: String, tc: ThreadContext): SixModelObject? {
        var curCallerFrame = tc.frame.caller
        while (curCallerFrame != null) {
            var curFrame: CallFrame? = curCallerFrame
            while (curFrame != null) {
                val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
                if (found != -1)
                    return curFrame.oLexOrVivify(found)
                curFrame = curFrame.outer
            }
            curCallerFrame = curCallerFrame.caller
        }
        return createNull(tc)
    }

    /* Relative lexical lookups. */
    @JvmStatic
    fun getlexrel(ctx: SixModelObject?, name: String, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            var curFrame: CallFrame? = ctx.context
            while (curFrame != null) {
                val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
                if (found != -1)
                    return curFrame.oLexOrVivify(found)
                curFrame = curFrame.outer
            }
            return createNull(tc)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "getlexrel requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun getlexreldyn(ctx: SixModelObject?, name: String, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            var curFrame: CallFrame? = ctx.context
            while (curFrame != null) {
                val idx = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
                if (idx != -1)
                    return curFrame.oLexOrVivify(idx)
                curFrame = curFrame.caller
            }
            return createNull(tc)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "getlexreldyn requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun getlexrelcaller(ctx: SixModelObject?, name: String, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            var curCallerFrame: CallFrame? = ctx.context
            while (curCallerFrame != null) {
                var curFrame: CallFrame? = curCallerFrame
                while (curFrame != null) {
                    val found = curFrame.codeRef.staticInfo.oTryGetLexicalIdx(name)
                    if (found != -1)
                        return curFrame.oLexOrVivify(found)
                    curFrame = curFrame.outer
                }
                curCallerFrame = curCallerFrame.caller
            }
            return createNull(tc)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "getlexrelcaller requires an operand with REPR ContextRef")
        }
    }

    /* Context introspection. */
    @JvmStatic
    fun ctx(tc: ThreadContext): SixModelObject {
        val ContextRef = tc.gc.ContextRef!!
        val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
        (wrap as ContextRefInstance).context = tc.frame
        return wrap
    }
    /* A context reference over a given frame, for the code engine: its
     * curlexpad anchors at the program's own frame, never tc.curFrame. */
    @JvmStatic
    fun ctx_of(cf: CallFrame, tc: ThreadContext): SixModelObject {
        val ContextRef = tc.gc.ContextRef!!
        val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
        (wrap as ContextRefInstance).context = cf
        return wrap
    }
    @JvmStatic
    fun ctxouter(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            val outer = ctx.context!!.outer
                ?: return createNull(tc)

            val ContextRef = tc.gc.ContextRef!!
            val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
            (wrap as ContextRefInstance).context = outer
            return wrap
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "ctxouter requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun ctxcaller(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            val caller = ctx.context!!.caller
                ?: return createNull(tc)

            val ContextRef = tc.gc.ContextRef!!
            val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
            (wrap as ContextRefInstance).context = caller
            return wrap
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "ctxcaller requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun ctxcode(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance)
            return ctx.context!!.codeRef
        else
            throw ExceptionHandling.dieInternal(tc, "ctxcode requires an operand with REPR ContextRef")
    }
    @JvmStatic
    fun ctxouterskipthunks(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            var outer = ctx.context!!.outer
            while (outer != null && outer.codeRef.staticInfo.isThunk)
                outer = outer.outer
            if (outer == null)
                return createNull(tc)

            val ContextRef = tc.gc.ContextRef!!
            val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
            (wrap as ContextRefInstance).context = outer
            return wrap
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "ctxouter requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun ctxcallerskipthunks(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            var caller = ctx.context!!.caller
            /* A compiler stub stands in for a routine that has not been
             * compiled yet, so its frame is an artifact of compilation and
             * not something the caller wrote - skip it as well, which is what
             * ExceptionHandling already does when it walks a backtrace.
             * Rakudo asks for the caller here to find the `$/` a smartmatch
             * should bind; stopping on the stub bound the match to the stub's
             * frame and left the real caller's `$/` unset. */
            while (caller != null
                    && (caller.codeRef.staticInfo.isThunk || caller.codeRef.isCompilerStub))
                caller = caller.caller
            if (caller == null)
                return createNull(tc)

            val ContextRef = tc.gc.ContextRef!!
            val wrap = ContextRef.st.REPR.allocate(tc, ContextRef.st)
            (wrap as ContextRefInstance).context = caller
            return wrap
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "ctxcaller requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun ctxlexpad(ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (ctx is ContextRefInstance) {
            // The context serves happily enough as the lexpad also (provides
            // the associative bit of the REPR API, mapped to the lexpad).
            return ctx
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "ctxlexpad requires an operand with REPR ContextRef")
        }
    }
    @JvmStatic
    fun curcode(tc: ThreadContext): SixModelObject {
        return tc.frame.codeRef
    }
    @JvmStatic
    fun callercode(tc: ThreadContext): SixModelObject? {
        val caller = tc.frame.caller
        return if (caller == null) null else caller.codeRef
    }
    @JvmStatic
    fun lexprimspec(pad: SixModelObject?, key: String, tc: ThreadContext): Long {
        if (pad is ContextRefInstance) {
            val sci = pad.context!!.codeRef.staticInfo
            if (sci.oTryGetLexicalIdx(key) != -1) return BoxedPrimitive.NONE.spec.toLong()
            if (sci.iTryGetLexicalIdx(key) != -1) return BoxedPrimitive.INT.spec.toLong()
            if (sci.uTryGetLexicalIdx(key) != -1) return BoxedPrimitive.UINT.spec.toLong()
            if (sci.nTryGetLexicalIdx(key) != -1) return BoxedPrimitive.NUM.spec.toLong()
            if (sci.sTryGetLexicalIdx(key) != -1) return BoxedPrimitive.STR.spec.toLong()
            throw ExceptionHandling.dieInternal(tc, "Invalid lexical name passed to lexprimspec")
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "lexprimspec requires an operand with REPR ContextRef")
        }
    }

    /* Worded as MoarVM's arity_fail words it: the message reaches the user
     * through an unhandled-exception report, and tests read it. */
    private fun arityFail(got: Int, min: Int, max: Int): String {
        val problem = if (max != -1 && got > max) "Too many" else "Too few"
        return when {
            min == max ->
                "$problem positionals passed; expected $min argument" +
                    (if (min == 1) "" else "s") + " but got $got"
            max == -1 ->
                "$problem positionals passed; expected at least $min arguments but got only $got"
            else ->
                "$problem positionals passed; expected $min " +
                    (if (min + 1 == max) "or" else "to") + " $max arguments but got $got"
        }
    }

    /* Invocation arity check. */
    @JvmStatic
    fun checkarity(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, required: Int, accepted: Int): CallSiteDescriptor {
        var callSite = cs
        if (callSite.hasFlattening)
            callSite = callSite.explodeFlattening(cf, args)
        else
            cf.tc.flatArgs = args
        val positionals = callSite.numPositionals
        if (positionals < required || positionals > accepted && accepted != -1)
            throw ExceptionHandling.dieInternal(cf.tc, arityFail(positionals, required, accepted))
        /* Keep the arguments on the frame. A parameter bound here can still
         * fail a check the HLL wants to report against the original
         * arguments, and this route is the only place they survive. */
        cf.csd = callSite
        cf.args = cf.tc.flatArgs
        return callSite
    }

    /* Required positional parameter fetching. */
    @JvmStatic
    fun posparam_o(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): SixModelObject? {
        when (cs.argFlags[idx]) {
        CallSiteDescriptor.ARG_OBJ ->
            return args[idx] as SixModelObject?
        CallSiteDescriptor.ARG_INT ->
            return box_i(args[idx] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
        CallSiteDescriptor.ARG_UINT ->
            return box_i(args[idx] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
        CallSiteDescriptor.ARG_NUM ->
            return box_n(args[idx] as Double, cf.codeRef.staticInfo.compUnit.hllConfig.numBoxType, cf.tc)
        CallSiteDescriptor.ARG_STR ->
            return box_s(args[idx] as String?, cf.codeRef.staticInfo.compUnit.hllConfig.strBoxType, cf.tc)
        else ->
            throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
        }
    }
    @JvmStatic
    fun posparam_i(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Long {
        when (cs.argFlags[idx]) {
        CallSiteDescriptor.ARG_INT ->
            return args[idx] as Long
        CallSiteDescriptor.ARG_UINT ->
            return args[idx] as Long
        CallSiteDescriptor.ARG_NUM ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got num")
        CallSiteDescriptor.ARG_STR ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got str")
        CallSiteDescriptor.ARG_OBJ ->
            return decont(args[idx] as SixModelObject?, cf.tc)!!.get_int(cf.tc)
        else ->
            throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
        }
    }
    @JvmStatic
    fun posparam_u(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Long {
        when (cs.argFlags[idx]) {
        CallSiteDescriptor.ARG_INT ->
            return args[idx] as Long
        CallSiteDescriptor.ARG_UINT ->
            return args[idx] as Long
        CallSiteDescriptor.ARG_NUM ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native uint argument, but got num")
        CallSiteDescriptor.ARG_STR ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native uint argument, but got str")
        CallSiteDescriptor.ARG_OBJ ->
            return decont(args[idx] as SixModelObject?, cf.tc)!!.get_uint(cf.tc)
        else ->
            throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
        }
    }
    @JvmStatic
    fun posparam_n(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Double {
        when (cs.argFlags[idx]) {
        CallSiteDescriptor.ARG_NUM ->
            return args[idx] as Double
        CallSiteDescriptor.ARG_INT ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got int")
        CallSiteDescriptor.ARG_UINT ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got uint")
        CallSiteDescriptor.ARG_STR ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got str")
        CallSiteDescriptor.ARG_OBJ ->
            return decont(args[idx] as SixModelObject?, cf.tc)!!.get_num(cf.tc)
        else ->
            throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
        }
    }
    @JvmStatic
    fun posparam_s(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): String? {
        when (cs.argFlags[idx]) {
        CallSiteDescriptor.ARG_STR ->
            return args[idx] as String?
        CallSiteDescriptor.ARG_INT ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got int")
        CallSiteDescriptor.ARG_UINT ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got uint")
        CallSiteDescriptor.ARG_NUM ->
            throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got num")
        CallSiteDescriptor.ARG_OBJ ->
            return decont(args[idx] as SixModelObject?, cf.tc)!!.get_str(cf.tc)
        else ->
            throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
        }
    }

    /* Optional positional parameter fetching. */
    @JvmStatic
    fun posparam_opt_o(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): SixModelObject? {
        if (idx < cs.numPositionals) {
            cf.tc.lastParameterExisted = 1
            return posparam_o(cf, cs, args, idx)
        }
        else {
            cf.tc.lastParameterExisted = 0
            return null
        }
    }
    @JvmStatic
    fun posparam_opt_i(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Long {
        if (idx < cs.numPositionals) {
            cf.tc.lastParameterExisted = 1
            return posparam_i(cf, cs, args, idx)
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0
        }
    }
    @JvmStatic
    fun posparam_opt_u(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Long {
        if (idx < cs.numPositionals) {
            cf.tc.lastParameterExisted = 1
            return posparam_i(cf, cs, args, idx)
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0
        }
    }
    @JvmStatic
    fun posparam_opt_n(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): Double {
        if (idx < cs.numPositionals) {
            cf.tc.lastParameterExisted = 1
            return posparam_n(cf, cs, args, idx)
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0.0
        }
    }
    @JvmStatic
    fun posparam_opt_s(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, idx: Int): String? {
        if (idx < cs.numPositionals) {
            cf.tc.lastParameterExisted = 1
            return posparam_s(cf, cs, args, idx)
        }
        else {
            cf.tc.lastParameterExisted = 0
            return null
        }
    }

    /* Slurpy positional parameter. */
    @JvmStatic
    fun posslurpy(tc: ThreadContext, cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, fromIdx: Int): SixModelObject {
        /* Create result. */
        val hllConfig = cf.codeRef.staticInfo.compUnit.hllConfig
        val resType = hllConfig.slurpyArrayType!!
        val result = resType.st.REPR.allocate(tc, resType.st)

        /* Populate it. */
        for (i in fromIdx until cs.numPositionals) {
            when (cs.argFlags[i]) {
            CallSiteDescriptor.ARG_OBJ ->
                result.push_boxed(tc, args[i] as SixModelObject?)
            CallSiteDescriptor.ARG_INT ->
                result.push_boxed(tc, box_i(args[i] as Long, hllConfig.intBoxType, tc))
            CallSiteDescriptor.ARG_UINT ->
                result.push_boxed(tc, box_i(args[i] as Long, hllConfig.intBoxType, tc))
            CallSiteDescriptor.ARG_NUM ->
                result.push_boxed(tc, box_n(args[i] as Double, hllConfig.numBoxType, tc))
            CallSiteDescriptor.ARG_STR ->
                result.push_boxed(tc, box_s(args[i] as String?, hllConfig.strBoxType, tc))
            }
        }

        return result
    }

    /* Required named parameter getting. */
    @JvmStatic
    fun namedparam_o(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): SixModelObject? {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_OBJ ->
                return args[lookup shr 6] as SixModelObject?
            CallSiteDescriptor.ARG_INT ->
                return box_i(args[lookup shr 6] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
            CallSiteDescriptor.ARG_UINT ->
                return box_i(args[lookup shr 6] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
            CallSiteDescriptor.ARG_NUM ->
                return box_n(args[lookup shr 6] as Double, cf.codeRef.staticInfo.compUnit.hllConfig.numBoxType, cf.tc)
            CallSiteDescriptor.ARG_STR ->
                return box_s(args[lookup shr 6] as String?, cf.codeRef.staticInfo.compUnit.hllConfig.strBoxType, cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else
            throw ExceptionHandling.dieInternal(cf.tc, "Required named argument '" + name + "' not passed")
    }
    @JvmStatic
    fun namedparam_i(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Long {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_INT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_UINT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got num")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_int(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else
            throw ExceptionHandling.dieInternal(cf.tc, "Required named argument '" + name + "' not passed")
    }
    @JvmStatic
    fun namedparam_u(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Long {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_INT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_UINT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got num")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_uint(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else
            throw ExceptionHandling.dieInternal(cf.tc, "Required named argument '" + name + "' not passed")
    }
    @JvmStatic
    fun namedparam_n(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Double {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_NUM ->
                return args[lookup shr 6] as Double
            CallSiteDescriptor.ARG_INT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got int")
            CallSiteDescriptor.ARG_UINT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got uint")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_num(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else
            throw ExceptionHandling.dieInternal(cf.tc, "Required named argument '" + name + "' not passed")
    }
    @JvmStatic
    fun namedparam_s(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): String? {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_STR ->
                return args[lookup shr 6] as String?
            CallSiteDescriptor.ARG_INT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got int")
            CallSiteDescriptor.ARG_UINT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got uint")
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got num")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_str(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else
            throw ExceptionHandling.dieInternal(cf.tc, "Required named argument '" + name + "' not passed")
    }

    /* Optional named parameter getting. */
    @JvmStatic
    fun namedparam_opt_o(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): SixModelObject? {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            cf.tc.lastParameterExisted = 1
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_OBJ ->
                return args[lookup shr 6] as SixModelObject?
            CallSiteDescriptor.ARG_INT ->
                return box_i(args[lookup shr 6] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
            CallSiteDescriptor.ARG_UINT ->
                return box_i(args[lookup shr 6] as Long, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
            CallSiteDescriptor.ARG_NUM ->
                return box_n(args[lookup shr 6] as Double, cf.codeRef.staticInfo.compUnit.hllConfig.numBoxType, cf.tc)
            CallSiteDescriptor.ARG_STR ->
                return box_s(args[lookup shr 6] as String?, cf.codeRef.staticInfo.compUnit.hllConfig.strBoxType, cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else {
            cf.tc.lastParameterExisted = 0
            return null
        }
    }
    @JvmStatic
    fun namedparam_opt_i(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Long {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            cf.tc.lastParameterExisted = 1
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_INT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_UINT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got num")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_int(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0
        }
    }
    @JvmStatic
    fun namedparam_opt_u(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Long {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            cf.tc.lastParameterExisted = 1
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_INT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_UINT ->
                return args[lookup shr 6] as Long
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got num")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native int argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_uint(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0
        }
    }
    @JvmStatic
    fun namedparam_opt_n(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): Double {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            cf.tc.lastParameterExisted = 1
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_NUM ->
                return args[lookup shr 6] as Double
            CallSiteDescriptor.ARG_INT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got int")
            CallSiteDescriptor.ARG_UINT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got uint")
            CallSiteDescriptor.ARG_STR ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native num argument, but got str")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_num(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else {
            cf.tc.lastParameterExisted = 0
            return 0.0
        }
    }
    @JvmStatic
    fun namedparam_opt_s(cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>, name: String): String? {
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        if (cf.workingNameMap!!.containsKey(name)) {
            val lookup = cf.workingNameMap!!.removeInt(name)
            cf.tc.lastParameterExisted = 1
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_STR ->
                return args[lookup shr 6] as String?
            CallSiteDescriptor.ARG_INT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got int")
            CallSiteDescriptor.ARG_UINT ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got uint")
            CallSiteDescriptor.ARG_NUM ->
                throw ExceptionHandling.dieInternal(cf.tc, "Expected native str argument, but got num")
            CallSiteDescriptor.ARG_OBJ ->
                return decont(args[lookup shr 6] as SixModelObject?, cf.tc)!!.get_str(cf.tc)
            else ->
                throw ExceptionHandling.dieInternal(cf.tc, "Error in argument processing")
            }
        }
        else {
            cf.tc.lastParameterExisted = 0
            return null
        }
    }

    /* Slurpy named parameter. */
    @JvmStatic
    fun namedslurpy(tc: ThreadContext, cf: CallFrame, cs: CallSiteDescriptor, args: Array<Any?>): SixModelObject {
        /* Create result. */
        val hllConfig = cf.codeRef.staticInfo.compUnit.hllConfig
        val resType = hllConfig.slurpyHashType!!
        val result = resType.st.REPR.allocate(tc, resType.st)

        /* Populate it. */
        if (cf.workingNameMap == null)
            cf.workingNameMap = Object2IntOpenHashMap<String>(cs.nameMap)
        for (name in cf.workingNameMap!!.keys) {
            val lookup = cf.workingNameMap!!.getInt(name)
            when ((lookup and 7).toByte()) {
            CallSiteDescriptor.ARG_OBJ ->
                result.bind_key_boxed(tc, name, args[lookup shr 6] as SixModelObject?)
            CallSiteDescriptor.ARG_INT ->
                result.bind_key_boxed(tc, name, box_i(args[lookup shr 6] as Long, hllConfig.intBoxType, tc))
            CallSiteDescriptor.ARG_UINT ->
                result.bind_key_boxed(tc, name, box_i(args[lookup shr 6] as Long, hllConfig.intBoxType, tc))
            CallSiteDescriptor.ARG_NUM ->
                result.bind_key_boxed(tc, name, box_n(args[lookup shr 6] as Double, hllConfig.numBoxType, tc))
            CallSiteDescriptor.ARG_STR ->
                result.bind_key_boxed(tc, name, box_s(args[lookup shr 6] as String?, hllConfig.strBoxType, tc))
            }
        }

        return result
    }

    /* Return value setting. */
    @JvmStatic
    fun return_o(v: SixModelObject?, cf: CallFrame) {
        var caller = cf.caller
        if (caller == null) caller = cf.tc.dummyCaller
        caller.oRet = v
        caller.retType = CallFrame.RET_OBJ.toByte()
    }
    @JvmStatic
    fun return_i(v: Long, cf: CallFrame) {
        var caller = cf.caller
        if (caller == null) caller = cf.tc.dummyCaller
        caller.iRet = v
        caller.retType = CallFrame.RET_INT.toByte()
    }
    @JvmStatic
    fun return_u(v: Long, cf: CallFrame) {
        var caller = cf.caller
        if (caller == null) caller = cf.tc.dummyCaller
        caller.iRet = v
        caller.retType = CallFrame.RET_INT.toByte()
    }
    @JvmStatic
    fun return_n(v: Double, cf: CallFrame) {
        var caller = cf.caller
        if (caller == null) caller = cf.tc.dummyCaller
        caller.nRet = v
        caller.retType = CallFrame.RET_NUM.toByte()
    }
    @JvmStatic
    fun return_s(v: String?, cf: CallFrame) {
        var caller = cf.caller
        if (caller == null) caller = cf.tc.dummyCaller
        caller.sRet = v
        caller.retType = CallFrame.RET_STR.toByte()
    }

    /* Get returned result. */
    @JvmStatic
    fun result_o(cf: CallFrame): SixModelObject? {
        when (cf.retType.toInt()) {
        CallFrame.RET_INT ->
            return box_i(cf.iRet, cf.codeRef.staticInfo.compUnit.hllConfig.intBoxType, cf.tc)
        CallFrame.RET_NUM ->
            return box_n(cf.nRet, cf.codeRef.staticInfo.compUnit.hllConfig.numBoxType, cf.tc)
        CallFrame.RET_STR ->
            return box_s(cf.sRet, cf.codeRef.staticInfo.compUnit.hllConfig.strBoxType, cf.tc)
        else ->
            return cf.oRet
        }
    }
    @JvmStatic
    fun result_i(cf: CallFrame): Long {
        when (cf.retType.toInt()) {
            CallFrame.RET_INT ->
                return cf.iRet
            CallFrame.RET_UINT ->
                return cf.iRet
            CallFrame.RET_NUM ->
                return cf.nRet.toLong()
            CallFrame.RET_STR ->
                return coerce_s2i(cf.sRet)
            else ->
                return unbox_i(cf.oRet, cf.tc)
        }
    }
    @JvmStatic
    fun result_u(cf: CallFrame): Long {
        when (cf.retType.toInt()) {
            CallFrame.RET_INT ->
                return cf.iRet
            CallFrame.RET_UINT ->
                return cf.iRet
            CallFrame.RET_NUM ->
                return cf.nRet.toLong()
            CallFrame.RET_STR ->
                return coerce_s2i(cf.sRet)
            else ->
                /* Unsigned: the wider reading, or a value that only an
                 * unsigned native can hold is rejected on its way into one. */
                return unbox_u(cf.oRet, cf.tc)
        }
    }
    @JvmStatic
    fun result_n(cf: CallFrame): Double {
        when (cf.retType.toInt()) {
            CallFrame.RET_INT ->
                return cf.iRet.toDouble()
            CallFrame.RET_NUM ->
                return cf.nRet
            CallFrame.RET_STR ->
                return coerce_s2n(cf.sRet)
            else ->
                return unbox_n(cf.oRet, cf.tc)
        }
    }
    @JvmStatic
    fun result_s(cf: CallFrame): String? {
        when (cf.retType.toInt()) {
            CallFrame.RET_INT ->
                return coerce_i2s(cf.iRet)
            CallFrame.RET_NUM ->
                return coerce_n2s(cf.nRet)
            CallFrame.RET_STR ->
                return cf.sRet
            else ->
                return unbox_s(cf.oRet, cf.tc)
        }
    }

    /* Capture related operations. */
    @JvmStatic
    fun usecapture(tc: ThreadContext, cs: CallSiteDescriptor, args: Array<Any?>): SixModelObject {
        val cc = tc.savedCC!!
        cc.descriptor = cs
        cc.args = args.clone()
        return cc
    }
    @JvmStatic
    fun savecapture(tc: ThreadContext, cs: CallSiteDescriptor, args: Array<Any?>): SixModelObject {
        val CallCapture = tc.gc.CallCapture!!
        val cc = CallCapture.st.REPR.allocate(tc, CallCapture.st) as CallCaptureInstance
        cc.descriptor = cs
        cc.args = args.clone()
        return cc
    }
    @JvmStatic
    fun captureposelems(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance)
            return obj.descriptor!!.numPositionals.toLong()
        else
            throw ExceptionHandling.dieInternal(tc, "captureposelems requires a CallCapture")
    }
    @JvmStatic
    fun captureposarg(obj: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject? {
        if (obj is CallCaptureInstance) {
            val i = idx.toInt()
            when (argType(obj.descriptor!!.argFlags[i])) {
            CallSiteDescriptor.ARG_OBJ ->
                return obj.args!![i] as SixModelObject?
            CallSiteDescriptor.ARG_INT ->
                return box_i(obj.args!![i] as Long,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc)
            CallSiteDescriptor.ARG_UINT ->
                return box_i(obj.args!![i] as Long,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc)
            CallSiteDescriptor.ARG_NUM ->
                return box_n(obj.args!![i] as Double,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType, tc)
            CallSiteDescriptor.ARG_STR ->
                return box_s(obj.args!![i] as String?,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType, tc)
            else ->
                throw ExceptionHandling.dieInternal(tc, "Invalid positional argument access from capture")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg requires a CallCapture")
        }
    }
    @JvmStatic
    fun captureposarg_i(obj: SixModelObject?, idx: Long, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance) {
            val i = idx.toInt()
            if (obj.descriptor!!.argFlags[i] == CallSiteDescriptor.ARG_INT) {
                return obj.args!![i] as Long
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Expected native int argument")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg_i requires a CallCapture")
        }
    }
    @JvmStatic
    fun captureposarg_u(obj: SixModelObject?, idx: Long, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance) {
            val i = idx.toInt()
            if (obj.descriptor!!.argFlags[i] == CallSiteDescriptor.ARG_UINT) {
                return obj.args!![i] as Long
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Expected native int argument")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg_u requires a CallCapture")
        }
    }
    @JvmStatic
    fun captureposarg_s(obj: SixModelObject?, idx: Long, tc: ThreadContext): String? {
        if (obj is CallCaptureInstance) {
            val i = idx.toInt()
            if (obj.descriptor!!.argFlags[i] == CallSiteDescriptor.ARG_STR) {
                val v = obj.args!![i]
                if (v != null && v !is String) {
                    System.err.println("CAPTURE CORRUPT: captureposarg_s(" + i + ") flags=" +
                        obj.descriptor!!.argFlags.joinToString(",") + " nargs=" + obj.args!!.size +
                        " types=" + obj.args!!.joinToString(",") { a -> a?.javaClass?.simpleName ?: "null" })
                    Throwable("capture corrupt").printStackTrace()
                }
                return v as String?
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Expected native str argument")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg_s requires a CallCapture")
        }
    }
    @JvmStatic
    fun captureposarg_n(obj: SixModelObject?, idx: Long, tc: ThreadContext): Double {
        if (obj is CallCaptureInstance) {
            val i = idx.toInt()
            if (obj.descriptor!!.argFlags[i] == CallSiteDescriptor.ARG_NUM) {
                return obj.args!![i] as Double
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Expected native num argument")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg_n requires a CallCapture")
        }
    }
    @JvmStatic
    fun captureexistsnamed(obj: SixModelObject?, name: String?, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance) {
            return if (obj.descriptor!!.nameMap.containsKey(name)) 1 else 0
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "capturehasnameds requires a CallCapture")
        }
    }
    @JvmStatic
    fun capturenamedshash(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is CallCaptureInstance) {
            val hashType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType!!
            val res = hashType.st.REPR.allocate(tc, hashType.st)

            for (n in obj.descriptor!!.nameMap.keys) {
                val name = n as String
                val flagged = obj.descriptor!!.nameMap.getInt(name)
                val i = flagged shr 6

                var arg: SixModelObject? = null

                if ((flagged and CallSiteDescriptor.ARG_INT.toInt()) != 0) {
                    arg = box_i(obj.args!![i] as Long,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc)
                }
                else if ((flagged and CallSiteDescriptor.ARG_STR.toInt()) != 0) {
                    arg = box_s(obj.args!![i] as String?,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType, tc)
                }
                else if ((flagged and CallSiteDescriptor.ARG_NUM.toInt()) != 0) {
                    arg = box_n(obj.args!![i] as Double,
                        tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType, tc)
                }
                else if (obj.args!![i] != null) {
                    try {
                        arg = obj.args!![i] as SixModelObject
                    } catch (e: Exception) {
                        throw ExceptionHandling.dieInternal(tc,
                            "capturenamedshash failed due to casting failure of argument to SixModelObject")
                    }
                }

                res.bind_key_boxed(tc, name, arg)
            }

            return res
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "capturenamedshash requires a CallCapture")
        }
    }
    @JvmStatic
    fun capturehasnameds(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance) {
            return if (obj.descriptor!!.names == null) 0 else 1
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "capturehasnameds requires a CallCapture")
        }
    }
    /**
     * The type an argument flag names, with the named and flat bits taken off.
     * A capture is indexed across all its arguments, positional and named
     * alike, and a named one carries those bits alongside its type.
     */
    @JvmStatic
    fun argType(flag: Byte): Byte =
        (flag.toInt() and (CallSiteDescriptor.ARG_NAMED.toInt() or
            CallSiteDescriptor.ARG_FLAT.toInt()).inv()).toByte()

    @JvmStatic
    fun captureposprimspec(obj: SixModelObject?, idx: Long, tc: ThreadContext): Long {
        if (obj is CallCaptureInstance) {
            when (argType(obj.descriptor!!.argFlags[idx.toInt()])) {
            CallSiteDescriptor.ARG_INT ->
                return BoxedPrimitive.INT.spec.toLong()
            CallSiteDescriptor.ARG_UINT ->
                return BoxedPrimitive.UINT.spec.toLong()
            CallSiteDescriptor.ARG_NUM ->
                return BoxedPrimitive.NUM.spec.toLong()
            CallSiteDescriptor.ARG_STR ->
                return BoxedPrimitive.STR.spec.toLong()
            else ->
                return BoxedPrimitive.NONE.spec.toLong()
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "captureposarg requires a CallCapture")
        }
    }

    /* Invocation. */
    @JvmField val emptyCallSite = CallSiteDescriptor(ByteArray(0), null)
    @JvmField val emptyArgList = arrayOfNulls<Any>(0)
    @JvmField val invocantCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
    @JvmField val methodWithInvocantCallSite = CallSiteDescriptor(
        byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)

    /* Invokes a method resolved by a runtime helper on the given invocant,
     * going through the dispatcher, the way MoarVM's boolification delegates
     * to lang-call. A direct invocation would run a multi's proto without
     * the dispatch its resumption resumes. When the method's language has no
     * call dispatcher registered (the stage0 bootstrap), invoke directly:
     * such a world has no dispatch-dependent protos either. The callsite is
     * cached per method, so this stays a replay rather than recording a
     * dispatch program on every boolification or stringification. */
    private val helperDispatchSites =
        java.util.concurrent.ConcurrentHashMap<Pair<SixModelObject, String>, org.raku.nqp.dispatch.DispatchCallSite>()

    /* These are keyed by the method object, which belongs to one
     * GlobalContext, so a process running unrelated programs in turn must drop
     * them along with the instruction callsites; see
     * DispatchBootstrap.resetAll. */
    @JvmStatic
    fun resetHelperDispatchSites() {
        helperDispatchSites.clear()
        resetLangCallSites()
    }
    private val helperDispatchSiteType =
        java.lang.invoke.MethodType.methodType(Void.TYPE)

    private fun invokeMethodViaDispatch(tc: ThreadContext, method: SixModelObject?,
                                        invocant: SixModelObject?) {
        invokeMethodViaDispatch(tc, method, invocantCallSite, arrayOf<Any?>(invocant))
    }

    /* As above, for a method with an arbitrary callsite (invocant first, the
     * method itself not included). The runtime binder uses this for the
     * ACCEPTS of a constraint check: raw invocation of a resolved method
     * that turns out to be an onlystar proto would make its {*} resume
     * whatever unrelated dispatch encloses the call. */
    @JvmStatic
    fun invokeMethodViaDispatch(tc: ThreadContext, method: SixModelObject?,
                                csd: CallSiteDescriptor, args: Array<Any?>) {
        val routable = method is CodeRef ||
            (method != null && method.stInitialized &&
                method.st.hllOwner?.callDispatcher != null)
        if (!routable) {
            invokeDirect(tc, method, csd, args)
            return
        }
        val flags = ByteArray(csd.argFlags.size + 1)
        flags[0] = CallSiteDescriptor.ARG_OBJ
        csd.argFlags.copyInto(flags, 1)
        val fullCsd = CallSiteDescriptor(flags, csd.names)
        /* Keyed by the argument SHAPE as well as the method. A DispatchCallSite
         * caches the program recorded against the shape it first saw, so one
         * site per method replays that program for a call of different arity --
         * and the arguments then land in the wrong slots, which surfaces far
         * away as a DispatchCallSite where a string was expected. */
        val shapeKey = StringBuilder(flags.size + 8)
        for (f in flags) shapeKey.append(f.toInt()).append(',')
        fullCsd.names?.let { for (n in it) shapeKey.append(n).append(';') }
        val site = helperDispatchSites.computeIfAbsent(Pair(method!!, shapeKey.toString())) {
            org.raku.nqp.dispatch.DispatchCallSite(helperDispatchSiteType)
        }
        val fullArgs = arrayOfNulls<Any>(args.size + 1)
        fullArgs[0] = method
        args.copyInto(fullArgs, 1)
        org.raku.nqp.dispatch.Dispatch.dispatchWithDescriptor(site, "lang-call",
            fullCsd, tc, fullArgs)
    }
    @JvmField val storeCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
    @JvmField val storeCallSiteI = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT), null)
    @JvmField val storeCallSiteN = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_NUM), null)
    @JvmField val storeCallSiteS = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_STR), null)
    @JvmField val findmethCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_STR), null)
    @JvmField val typeCheckCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
    @JvmField val howObjCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
    @JvmField val parameterizeCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
    @JvmField val intIntCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_INT, CallSiteDescriptor.ARG_INT), null)

    @JvmStatic
    @Throws(Exception::class)
    fun invoke(invokee: SixModelObject?, callsiteIndex: Int, args: Array<Any?>, tc: ThreadContext) {
        // TODO Find a smarter way to do this without all the pointer chasing.
        if (callsiteIndex >= 0)
            invokeDirect(tc, invokee, tc.frame.codeRef.staticInfo.compUnit.callSites!![callsiteIndex], args)
        else
            invokeDirect(tc, invokee, emptyCallSite, args)
    }
    @JvmStatic
    fun invokeArgless(tc: ThreadContext, invokee: SixModelObject?) {
        invokeDirect(tc, invokee, emptyCallSite, arrayOfNulls<Any>(0))
    }
    @JvmStatic
    fun invokeMain(tc: ThreadContext, invokee: SixModelObject?, prog: String?, argv: Array<String>) {
        /* Build argument list from argv. */
        val Str = (invokee as CodeRef).staticInfo.compUnit.hllConfig.strBoxType
        val args = arrayOfNulls<Any>(argv.size + 1)
        val callsite = ByteArray(argv.size + 1)
        args[0] = box_s(prog, Str, tc)
        callsite[0] = CallSiteDescriptor.ARG_OBJ
        for (i in argv.indices) {
            args[i + 1] = box_s(argv[i], Str, tc)
            callsite[i + 1] = CallSiteDescriptor.ARG_OBJ
        }

        /* Invoke with the descriptor and arg list. */
        invokeDirect(tc, invokee, CallSiteDescriptor(callsite, null), args)
    }
    /* Dispatch sites for invocations routed through the HLL's registered
     * call dispatcher (lang-call -> raku-invoke on Raku). Keyed by the code
     * object and callsite descriptor identity, so each shape records its own
     * program; the map drops with the rest of the dispatch state between
     * eval-server runs (see resetHelperDispatchSites' registration). */
    private val langCallSites =
        java.util.concurrent.ConcurrentHashMap<Pair<SixModelObject, CallSiteDescriptor>, org.raku.nqp.dispatch.DispatchCallSite>()

    /* Invoke an HLL code object through its language's registered call
     * dispatcher, as MoarVM's lang-call does for every call site. This is
     * what runs raku-invoke on the JVM: custom dispatchers, CALL-ME, wrapper
     * handling and revision gating all live in the dispatcher, none of which
     * the InvocationSpec shortcut below can see. */
    private fun invokeViaCallDispatcher(tc: ThreadContext, invokee: SixModelObject,
                                        csd: CallSiteDescriptor, args: Array<Any?>) {
        val flags = ByteArray(csd.argFlags.size + 1)
        flags[0] = CallSiteDescriptor.ARG_OBJ
        csd.argFlags.copyInto(flags, 1)
        val fullCsd = CallSiteDescriptor(flags, csd.names)
        val site = langCallSites.computeIfAbsent(Pair(invokee, csd)) {
            org.raku.nqp.dispatch.DispatchCallSite(helperDispatchSiteType)
        }
        val fullArgs = arrayOfNulls<Any>(args.size + 1)
        fullArgs[0] = invokee
        args.copyInto(fullArgs, 1)
        org.raku.nqp.dispatch.Dispatch.dispatchWithDescriptor(site, "lang-call",
            fullCsd, tc, fullArgs)
    }

    @JvmStatic
    fun resetLangCallSites() = langCallSites.clear()

    @JvmStatic
    fun invokeDirect(tc: ThreadContext, invokee: SixModelObject?, csd: CallSiteDescriptor, args: Array<Any?>) {
        invokeDirect(tc, invokee, csd, true, args)
    }
    @JvmStatic
    fun invokeDirect(tc: ThreadContext, invokee: SixModelObject?, csd: CallSiteDescriptor, barrier: Boolean, args: Array<Any?>) {
        var callSite = csd
        var argList = args
        // Otherwise, get the code ref.
        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            if (invokee != null && invokee.stInitialized
                    && invokee.st.hllOwner?.callDispatcher != null) {
                invokeViaCallDispatcher(tc, invokee, callSite, argList)
                return
            }
            val invSpec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Cannot invoke this object")
            if (isnull(invSpec.ClassHandle) == 0L)
                cr = invokee.get_attribute_boxed(tc, invSpec.ClassHandle, invSpec.AttrName, invSpec.Hint) as CodeRef
            else {
                cr = invSpec.InvocationHandler as CodeRef
                callSite = callSite.injectInvokee(tc, argList, invokee)
                argList = tc.flatArgs!!
            }
        }

        val callerFrame = tc.curFrame
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, callSite, argList)
        }
        catch (r: org.raku.nqp.dispatch.BindReturnException) {
            /* The callee's signature bind failed on a Junction argument and
             * the language's bind_error handler autothreaded the call: its
             * result IS the call's result. The callee frame has already
             * unwound; land the value where a normal return would have. */
            val caller = callerFrame ?: tc.dummyCaller
            caller.oRet = r.value
            caller.retType = CallFrame.RET_OBJ.toByte()
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }
    @JvmStatic
    @Throws(Exception::class)
    fun invokewithcapture(invokee: SixModelObject?, capture: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (capture is CallCaptureInstance) {
            invokeDirect(tc, invokee, capture.descriptor!!, capture.args!!)
            return result_o(tc.frame)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "invokewithcapture requires a CallCapture")
        }
    }
    /* Multi-dispatch cache. */
    @JvmStatic
    fun multicacheadd(cache: SixModelObject?, capture: SixModelObject?, result: SixModelObject?, tc: ThreadContext): SixModelObject {
        var theCache = cache
        if (theCache !is MultiCacheInstance)
            theCache = tc.gc.MultiCache!!.st.REPR.allocate(tc, tc.gc.MultiCache!!.st)
        (theCache as MultiCacheInstance).add(capture as CallCaptureInstance, result, tc)
        return theCache
    }
    @JvmStatic
    fun multicachefind(cache: SixModelObject?, capture: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (cache is MultiCacheInstance)
            return cache.lookup(capture as CallCaptureInstance, tc)
        else
            return null
    }

    @JvmStatic
    fun what(o: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return decont(o, tc)!!.st.WHAT
    }
    @JvmStatic
    fun what_nd(o: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return o!!.st.WHAT
    }
    @JvmStatic
    fun how(o: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return decont(o, tc)!!.st.HOW
    }
    @JvmStatic
    fun how_nd(o: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return o!!.st.HOW
    }
    @JvmStatic
    fun who(o: SixModelObject?, tc: ThreadContext): SixModelObject? {
        /* Same story as where() below: MoarVM's null is a real object whose
         * STable has no WHO, so nqp::who(nqp::null) hands back a null there
         * rather than blowing up. Match that instead of an NPE. */
        val d = decont(o, tc) ?: return null
        return d.st.WHO
    }
    @JvmStatic
    fun where(o: SixModelObject?, tc: ThreadContext): Long {
        /* Null is a real value here: the REPL calls where() on eval results,
         * which are null for statements with no return value. MoarVM's null
         * is a genuine VMNull object with an address, so where() works there;
         * give the JVM's Java null a stable identity too instead of an NPE. */
        val d = decont(o, tc)
        return if (d == null) 0 else d.hashCode().toLong()
    }
    @JvmStatic
    fun setwho(o: SixModelObject?, who: SixModelObject?, tc: ThreadContext): SixModelObject? {
        decont(o, tc)!!.st.WHO = who
        return o
    }
    @JvmStatic
    fun rebless(obj: SixModelObject?, newType: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val theObj = decont(obj, tc)!!
        val theNewType = decont(newType, tc)
        if (theObj.st !== theNewType!!.st) {
            theObj.st.REPR.change_type(tc, theObj, theNewType)
            if (theObj.sc != null)
                scwbObject(tc, theObj)
        }
        return theObj
    }
    @JvmStatic
    fun create(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = obj!!.st.REPR.allocate(tc, obj.st)
        return res
    }
    @JvmStatic
    fun clone(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        return decont(obj, tc)!!.clone(tc)
    }
    @JvmStatic
    fun clone_nd(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        return obj!!.clone(tc)
    }
    @JvmStatic
    fun isconcrete(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 1L || decont(obj, tc) is TypeObject) 0 else 1
    }
    @JvmStatic
    fun isconcrete_nd(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 1L || obj is TypeObject) 0 else 1
    }
    @JvmStatic
    fun knowhow(tc: ThreadContext): SixModelObject? {
        return tc.gc.KnowHOW
    }
    @JvmStatic
    fun knowhowattr(tc: ThreadContext): SixModelObject? {
        return tc.gc.KnowHOWAttribute
    }
    @JvmStatic
    fun bootint(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTInt
    }
    @JvmStatic
    fun bootnum(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTNum
    }
    @JvmStatic
    fun bootstr(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTStr
    }
    @JvmStatic
    fun bootarray(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTArray
    }
    @JvmStatic
    fun bootintarray(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTIntArray
    }
    @JvmStatic
    fun bootnumarray(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTNumArray
    }
    @JvmStatic
    fun bootstrarray(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTStrArray
    }
    @JvmStatic
    fun boothash(tc: ThreadContext): SixModelObject? {
        return tc.gc.BOOTHash
    }
    @JvmStatic
    fun hlllist(tc: ThreadContext): SixModelObject? {
        return tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType
    }
    @JvmStatic
    fun hllhash(tc: ThreadContext): SixModelObject? {
        return tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType
    }
    @JvmStatic
    fun findmethodInCache(tc: ThreadContext, invocant: SixModelObject?, name: String): SixModelObject {
        if (isnull(invocant) == 1L)
            throw ExceptionHandling.dieInternal(tc, "Cannot call method '" + name + "' on a null object")
        val theInvocant = decont(invocant, tc)!!

        val meth = theInvocant.st.MethodCache!!.get(name)
        if (isnull(meth) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "Method '" + name + "' not found for invocant of class '" + typeName(theInvocant, tc) + "'")
        return meth!!
    }
    @JvmStatic
    fun findmethod(invocant: SixModelObject?, name: String, tc: ThreadContext): SixModelObject {
        val method = findmethodNonFatal(invocant, name, tc)
        if (isnull(method) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "Method '" + name + "' not found for invocant of class '" + typeName(invocant, tc) + "'")
        return method!!
    }
    @JvmStatic
    fun findmethodNonFatal(invocant: SixModelObject?, name: String, tc: ThreadContext): SixModelObject? {
        if (isnull(invocant) == 1L)
            throw ExceptionHandling.dieInternal(tc, "Cannot call method '" + name + "' on a null object")
        val theInvocant = decont(invocant, tc)!!

        val cache = theInvocant.st.MethodCache

        /* Try the by-name method cache, if the HOW published one. */
        if (cache != null) {
            val found = cache.get(name)
            if (isnull(found) == 0L)
                return found
            if ((theInvocant.st.ModeFlags and STable.METHOD_CACHE_AUTHORITATIVE) != 0)
                return null
        }

        /* Otherwise delegate to the HOW. */
        val how = theInvocant.st.HOW
        val findMethod = findmethod(how, "find_method", tc)
        invokeDirect(tc, findMethod, findmethCallSite,
                arrayOf<Any?>(how, theInvocant, name))
        return result_o(tc.frame)
    }
    @JvmStatic
    fun typeName(invocant: SixModelObject?, tc: ThreadContext): String? {
        val theInvocant = decont(invocant, tc)
        val how = theInvocant!!.st.HOW
        val nameMeth = findmethodInCache(tc, how, "name")
        invokeDirect(tc, nameMeth, howObjCallSite, arrayOf<Any?>(how, theInvocant))
        return result_s(tc.frame)
    }
    @JvmStatic
    fun can(invocant: SixModelObject?, name: String, tc: ThreadContext): Long {
        return if (isnull(findmethodNonFatal(invocant, name, tc)) == 1L) 0 else 1
    }
    @JvmStatic
    fun eqaddr(a: SixModelObject?, b: SixModelObject?): Long {
        return if (a === b) 1 else 0
    }
    @JvmStatic
    fun createNull(tc: ThreadContext): SixModelObject {
        if (theVMNull == null)
            theVMNull = VMNullInstance.getInstance(tc)
        return theVMNull!!
    }
    @JvmStatic
    fun isnull(obj: SixModelObject?): Long {
        return if (obj == null || obj === theVMNull) 1 else 0
    }
    @JvmStatic
    fun isnull_s(str: String?): Long {
        return if (str == null) 1 else 0
    }
    @JvmStatic
    fun reprname(obj: SixModelObject?, tc: ThreadContext): String {
        return decont(obj, tc)!!.st.REPR.name
    }
    @JvmStatic
    fun newtype(how: SixModelObject?, reprname: String, tc: ThreadContext): SixModelObject {
        return REPRRegistry.getByName(reprname).type_object_for(tc, decont(how, tc))
    }
    @JvmStatic
    fun composetype(obj: SixModelObject?, reprinfo: SixModelObject?, tc: ThreadContext): SixModelObject? {
        obj!!.st.REPR.compose(tc, obj.st, reprinfo!!)
        return obj
    }
    @JvmStatic
    fun setmethcache(obj: SixModelObject?, meths: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val iter = iter(meths, tc)
        val cache = HashMap<String, SixModelObject?>()
        while (istrue(iter, tc) != 0L) {
            val cur = iter.shift_boxed(tc)
            cache.put(iterkey_s(cur, tc)!!, iterval(cur, tc))
        }
        obj!!.st.MethodCache = cache
        if (obj.st.sc != null)
            scwbSTable(tc, obj.st)
        return obj
    }
    @JvmStatic
    fun setmethcacheauth(obj: SixModelObject?, flag: Long, tc: ThreadContext): SixModelObject? {
        var newFlags = obj!!.st.ModeFlags and (STable.METHOD_CACHE_AUTHORITATIVE.inv())
        if (flag != 0L)
            newFlags = newFlags or STable.METHOD_CACHE_AUTHORITATIVE
        obj.st.ModeFlags = newFlags
        if (obj.st.sc != null)
            scwbSTable(tc, obj.st)
        return obj
    }
    @JvmStatic
    fun settypecache(obj: SixModelObject?, types: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val elems = types!!.elems(tc)
        val cache = arrayOfNulls<SixModelObject>(elems.toInt())
        for (i in 0 until elems)
            cache[i.toInt()] = types.at_pos_boxed(tc, i)
        obj!!.st.TypeCheckCache = cache
        if (obj.st.sc != null)
            scwbSTable(tc, obj.st)
        return obj
    }
    @JvmStatic
    fun settypecheckmode(obj: SixModelObject?, mode: Long, tc: ThreadContext): SixModelObject? {
        obj!!.st.ModeFlags = mode.toInt() or
            (obj.st.ModeFlags and (STable.TYPE_CHECK_CACHE_FLAG_MASK.inv()))
        if (obj.st.sc != null)
            scwbSTable(tc, obj.st)
        return obj
    }
    @JvmStatic
    fun objprimspec(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 1L) 0 else obj!!.st.REPR.get_storage_spec(tc, obj.st).boxedPrimitive.spec.toLong()
    }
    @JvmStatic
    fun objprimunsigned(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 1L) 0 else if (obj!!.st.REPR.get_storage_spec(tc, obj.st).isUnsigned) 1L else 0L
    }
    @JvmStatic
    fun objprimbits(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 1L) 0 else obj!!.st.REPR.get_storage_spec(tc, obj.st).bits.toLong()
    }
    @JvmStatic
    fun setinvokespec(obj: SixModelObject?, ch: SixModelObject?,
            name: String?, invocationHandler: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val spec = InvocationSpec()
        spec.ClassHandle = ch
        spec.AttrName = name
        spec.Hint = STable.NO_HINT
        spec.InvocationHandler = invocationHandler
        obj!!.st.InvocationSpec = spec
        return obj
    }
    @JvmStatic
    fun isinvokable(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (obj is CodeRef || obj!!.st.InvocationSpec != null) 1 else 0
    }
    /* Assert that a signature bind check passed. A failure either becomes a
     * resumption of the dispatch that invoked us, if it asked for that, or an
     * error. */
    @JvmStatic
    fun assertparamcheck(ok: Long, tc: ThreadContext): SixModelObject? {
        if (ok == 0L)
            BindFailure.failed(tc)
        return null
    }

    /* Signature binding in the current frame completed successfully. A no-op
     * unless the invoking dispatch asked for bind success to be a
     * resumption. */
    @JvmStatic
    fun bindcomplete(tc: ThreadContext): SixModelObject? {
        BindFailure.complete(tc)
        return null
    }

    /* Will a bind failure in the current frame become a resumption of the
     * dispatch that invoked it? The same answer as the
     * bind-will-resume-on-failure syscall, callable without a dispatch
     * instruction: this is asked in every full-binder frame's prologue, and
     * an invokedynamic per prologue eats into the per-class indy budget. */
    @JvmStatic
    fun bindWillResumeOnFailure(tc: ThreadContext): Long {
        val record = tc.frame.dispatchRecord ?: return 0
        return if ((record.program?.bindControl ?: record.bindControl) != null) 1 else 0
    }

    /* The role a type plays in its language: one of the HLL_ROLE_*
     * constants, which is how hllization decides what to map. */
    @JvmStatic
    fun gettypehllrole(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (obj == null || !obj.stInitialized) 0L else obj.st.hllRole
    }
    /* Is this a VM level code handle, as opposed to something a language
     * wrapped around one? */
    @JvmStatic
    fun iscoderef(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (obj is CodeRef) 1 else 0
    }
    @JvmStatic
    fun istype(obj: SixModelObject?, type: SixModelObject?, tc: ThreadContext): Long {
        return istype_nd(decont(obj, tc), decont(type, tc), tc)
    }
    @JvmStatic
    fun istype_nd(obj: SixModelObject?, type: SixModelObject?, tc: ThreadContext): Long {
        /* Null always type checks false. */
        if (isnull(obj) == 1L)
            return 0

        /* Start by considering cache. */
        val objTypeCheckMode = obj!!.st.ModeFlags and STable.TYPE_CHECK_CACHE_FLAG_MASK
        val typeCheckNeedsAccept = (type!!.st.ModeFlags and STable.TYPE_CHECK_NEEDS_ACCEPTS) != 0
        val cache = obj.st.TypeCheckCache
        if (cache != null) {
            /* We have the cache, so just look for the type object we
             * want to be in there. */
            for (i in cache.indices)
                if (cache[i] === type)
                    return 1

            /* If the type check cache is definitive and the flag to call
             * .accepts_type on the target type isn't set, we're done. */
            if ((objTypeCheckMode and STable.TYPE_CHECK_CACHE_THEN_METHOD) == 0 &&
                    !typeCheckNeedsAccept)
                return 0
        }

        /* If we get here, need to call .^type_check on the value we're
         * checking. */
        if (cache == null || (objTypeCheckMode and STable.TYPE_CHECK_CACHE_THEN_METHOD) != 0) {
            val tcMeth = findmethodNonFatal(obj.st.HOW, "type_check", tc)
            if (isnull(tcMeth) == 1L)
                return 0
                /* TODO: Review why the following busts stuff. */
                /*throw ExceptionHandling.dieInternal(tc,
                    "No type check cache and no type_check method in meta-object");*/
            invokeDirect(tc, tcMeth, typeCheckCallSite, arrayOf<Any?>(obj.st.HOW, obj, type))
            if (tc.frame.retType.toInt() == CallFrame.RET_INT) {
                if (result_i(tc.frame) != 0L)
                    return 1
            }
            else {
                if (istrue(result_o(tc.frame), tc) != 0L)
                    return 1
            }
        }

        /* If the flag to call .accepts_type on the target type is set, do so. */
        if (typeCheckNeedsAccept) {
            val atMeth = findmethodNonFatal(type.st.HOW, "accepts_type", tc)
            if (isnull(atMeth) == 1L)
                throw ExceptionHandling.dieInternal(tc,
                    "Expected accepts_type method, but none found in meta-object")
            invokeDirect(tc, atMeth, typeCheckCallSite, arrayOf<Any?>(type.st.HOW, type, obj))
            return istrue(result_o(tc.frame), tc)
        }

        /* If we get here, type check failed. */
        return 0
    }

    /* Box/unbox operations. */
    @JvmStatic
    fun box_i(value: Long, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = type!!.st.REPR.allocate(tc, type.st)
        res.set_int(tc, value)
        return res
    }
    @JvmStatic
    fun box_u(value: Long, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = type!!.st.REPR.allocate(tc, type.st)
        res.set_uint(tc, value)
        return res
    }
    @JvmStatic
    fun box_n(value: Double, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = type!!.st.REPR.allocate(tc, type.st)
        res.set_num(tc, value)
        return res
    }
    @JvmStatic
    fun box_s(value: String?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = type!!.st.REPR.allocate(tc, type.st)
        res.set_str(tc, value)
        return res
    }
    @JvmStatic
    fun unbox_i(obj: SixModelObject?, tc: ThreadContext): Long {
        return decont(obj, tc)!!.get_int(tc)
    }
    /* MoarVM reads a bigint into an unsigned native through an accessor of
     * its own, which takes any value of 64 bits or fewer, where the signed
     * one stops at 63 bits for a positive value. There is a single get_int
     * here to serve both, so the unsigned readers apply the wider rule
     * themselves and let the value wrap into the signed range, which is
     * what an unsigned native holds it as anyway. */
    @JvmStatic
    fun unsignedFrom(obj: SixModelObject, tc: ThreadContext): Long {
        return obj.get_uint(tc)
    }
    @JvmStatic
    fun unbox_u(obj: SixModelObject?, tc: ThreadContext): Long {
        return unsignedFrom(decont(obj, tc)!!, tc)
    }
    @JvmStatic
    fun unbox_n(obj: SixModelObject?, tc: ThreadContext): Double {
        return decont(obj, tc)!!.get_num(tc)
    }
    @JvmStatic
    fun unbox_s(obj: SixModelObject?, tc: ThreadContext): String? {
        return decont(obj, tc)!!.get_str(tc)
    }
    @JvmStatic
    fun isint(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 0L && decont(obj, tc)!!.st.REPR.javaClass == P6int::class.java) 1 else 0
    }
    @JvmStatic
    fun isnum(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 0L && decont(obj, tc)!!.st.REPR.javaClass == P6num::class.java) 1 else 0
    }
    @JvmStatic
    fun isstr(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 0L && decont(obj, tc)!!.st.REPR.javaClass == P6str::class.java) 1 else 0
    }

    /* HLL aware boxing operations */
    @JvmStatic
    fun hllboxtype_i(tc: ThreadContext): SixModelObject? {
        return tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType
    }
    @JvmStatic
    fun hllboxtype_i(value: Long, tc: ThreadContext): SixModelObject {
        val type = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType!!
        val res = type.st.REPR.allocate(tc, type.st)
        res.set_int(tc, value)
        return res
    }
    @JvmStatic
    fun hllboxtype_n(tc: ThreadContext): SixModelObject? {
        return tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType
    }
    @JvmStatic
    fun hllboxtype_n(value: Double, tc: ThreadContext): SixModelObject {
        val type = tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType!!
        val res = type.st.REPR.allocate(tc, type.st)
        res.set_num(tc, value)
        return res
    }
    @JvmStatic
    fun hllboxtype_s(tc: ThreadContext): SixModelObject? {
        return tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
    }
    @JvmStatic
    fun hllboxtype_s(value: String?, tc: ThreadContext): SixModelObject {
        val type = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType!!
        val res = type.st.REPR.allocate(tc, type.st)
        res.set_str(tc, value)
        return res
    }

    /* Attribute operations. */
    @JvmStatic
    fun getattr(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject? {
        try {
            return obj!!.get_attribute_boxed(tc, decont(ch, tc), name, STable.NO_HINT)
        }
        catch (badRef: P6OpaqueBaseInstance.BadReferenceRuntimeException) {
            var retval: SixModelObject? = createNull(tc)
            obj!!.get_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
            if (tc.nativeType == ThreadContext.NATIVE_INT) {
                retval = box_i(tc.nativeI, tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_NUM) {
                retval = box_n(tc.nativeN, tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_STR) {
                retval = box_s(tc.nativeS, tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_JVM_OBJ) {
                /* Resolve through the class handle the access named, not
                 * the object's current type: after a mixin the two differ,
                 * and only the handle is a key in the attribute maps. */
                val slot = (obj as P6OpaqueBaseInstance).resolveAttribute(decont(ch, tc), name)
                val attrSt = (obj.st.REPRData as P6OpaqueREPRData).flattenedSTables!![slot]
                if (attrSt != null) {
                    retval = attrSt.REPR.allocate(tc, attrSt)
                    for (field in retval.javaClass.declaredFields) {
                        try {
                            if (tc.nativeJ == null || field.type.isAssignableFrom(tc.nativeJ!!.javaClass)) {
                                field.set(retval, tc.nativeJ)
                                break
                            }
                        }
                        catch (iae: IllegalAccessException) {
                            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' couldn't be boxed")
                        }
                    }
                }
            }
            return retval
        }
    }
    @JvmStatic
    fun getattr_i(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): Long {
        obj!!.get_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType == ThreadContext.NATIVE_INT)
            return tc.nativeI
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
    }
    @JvmStatic
    fun getattr_u(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): Long {
        obj!!.get_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType == ThreadContext.NATIVE_INT)
            return tc.nativeI
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
    }
    @JvmStatic
    fun getattr_n(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): Double {
        obj!!.get_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType == ThreadContext.NATIVE_NUM)
            return tc.nativeN
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native num")
    }
    @JvmStatic
    fun getattr_s(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): String? {
        obj!!.get_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType == ThreadContext.NATIVE_STR)
            return tc.nativeS
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native str")
    }
    @JvmStatic
    fun getattr(obj: SixModelObject?, ch: SixModelObject?, name: String?, hint: Long, tc: ThreadContext): SixModelObject? {
        var theHint = hint
        try {
            // XXX: as below (getattr_i)
            if (obj!!.st.REPRData is P6OpaqueREPRData && (obj.st.REPRData as P6OpaqueREPRData).mi)
                theHint = STable.NO_HINT
            return obj.get_attribute_boxed(tc, decont(ch, tc), name, theHint)
        }
        catch (badRef: P6OpaqueBaseInstance.BadReferenceRuntimeException) {
            var retval: SixModelObject? = createNull(tc)
            obj!!.get_attribute_native(tc, decont(ch, tc), name, theHint)
            if (tc.nativeType == ThreadContext.NATIVE_INT) {
                retval = box_i(tc.nativeI, tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_NUM) {
                retval = box_n(tc.nativeN, tc.frame.codeRef.staticInfo.compUnit.hllConfig.numBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_STR) {
                retval = box_s(tc.nativeS, tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType, tc)
            }
            else if (tc.nativeType == ThreadContext.NATIVE_JVM_OBJ) {
                /* Resolve through the class handle the access named, not
                 * the object's current type: after a mixin the two differ,
                 * and only the handle is a key in the attribute maps. */
                val slot = (obj as P6OpaqueBaseInstance).resolveAttribute(decont(ch, tc), name)
                val attrSt = (obj.st.REPRData as P6OpaqueREPRData).flattenedSTables!![slot]
                if (attrSt != null) {
                    retval = attrSt.REPR.allocate(tc, attrSt)
                    for (field in retval.javaClass.declaredFields) {
                        try {
                            if (tc.nativeJ == null || field.type.isAssignableFrom(tc.nativeJ!!.javaClass)) {
                                field.set(retval, tc.nativeJ)
                                break
                            }
                        }
                        catch (iae: IllegalAccessException) {
                            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' couldn't be boxed")
                        }
                    }
                }
            }
            return retval
        }
    }
    @JvmStatic
    fun getattr_i(obj: SixModelObject?, ch: SixModelObject?, name: String?, hint: Long, tc: ThreadContext): Long {
        var theHint = hint
        // XXX: when we get other REPRs that do multiple inheritance
        //      this check should probably move into codegen
        if (obj!!.st.REPRData is P6OpaqueREPRData && (obj.st.REPRData as P6OpaqueREPRData).mi)
            theHint = STable.NO_HINT
        obj.get_attribute_native(tc, decont(ch, tc), name, theHint)
        if (tc.nativeType == ThreadContext.NATIVE_INT)
            return tc.nativeI
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
    }
    @JvmStatic
    fun getattr_u(obj: SixModelObject?, ch: SixModelObject?, name: String?, hint: Long, tc: ThreadContext): Long {
        var theHint = hint
        // XXX: when we get other REPRs that do multiple inheritance
        //      this check should probably move into codegen
        if (obj!!.st.REPRData is P6OpaqueREPRData && (obj.st.REPRData as P6OpaqueREPRData).mi)
            theHint = STable.NO_HINT
        obj.get_attribute_native(tc, decont(ch, tc), name, theHint)
        if (tc.nativeType == ThreadContext.NATIVE_INT)
            return tc.nativeI
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
    }
    @JvmStatic
    fun getattr_n(obj: SixModelObject?, ch: SixModelObject?, name: String?, hint: Long, tc: ThreadContext): Double {
        var theHint = hint
        // XXX: as above
        if (obj!!.st.REPRData is P6OpaqueREPRData && (obj.st.REPRData as P6OpaqueREPRData).mi)
            theHint = STable.NO_HINT
        obj.get_attribute_native(tc, decont(ch, tc), name, theHint)
        if (tc.nativeType == ThreadContext.NATIVE_NUM)
            return tc.nativeN
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native num")
    }
    @JvmStatic
    fun getattr_s(obj: SixModelObject?, ch: SixModelObject?, name: String?, hint: Long, tc: ThreadContext): String? {
        var theHint = hint
        // XXX: as above
        if (obj!!.st.REPRData is P6OpaqueREPRData && (obj.st.REPRData as P6OpaqueREPRData).mi)
            theHint = STable.NO_HINT
        obj.get_attribute_native(tc, decont(ch, tc), name, theHint)
        if (tc.nativeType == ThreadContext.NATIVE_STR)
            return tc.nativeS
        else
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native str")
    }
    @JvmStatic
    fun bindattr(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        obj!!.bind_attribute_boxed(tc, decont(ch, tc), name, STable.NO_HINT, value)
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_i(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_u(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native unsigned int")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_n(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native num")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_s(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, STable.NO_HINT)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native str")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: SixModelObject?, hint: Long, tc: ThreadContext): SixModelObject? {
        obj!!.bind_attribute_boxed(tc, decont(ch, tc), name, hint, value)
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_i(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Long, hint: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, hint)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native int")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_u(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Long, hint: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, hint)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native unsigned int")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_n(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: Double, hint: Long, tc: ThreadContext): Double {
        tc.nativeN = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, hint)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native num")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun bindattr_s(obj: SixModelObject?, ch: SixModelObject?, name: String?, value: String?, hint: Long, tc: ThreadContext): String? {
        tc.nativeS = value
        obj!!.bind_attribute_native(tc, decont(ch, tc), name, hint)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "Attribute '" + name + "' is not a native str")
        if (obj.sc != null)
            scwbObject(tc, obj)
        return value
    }
    @JvmStatic
    fun attrinited(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): Long {
        return obj!!.is_attribute_initialized(tc, decont(ch, tc), name, STable.NO_HINT)
    }
    @JvmStatic
    fun attrhintfor(ch: SixModelObject?, name: String?, tc: ThreadContext): Long {
        val theCh = decont(ch, tc)
        return theCh!!.st.REPR.hint_for(tc, theCh.st, theCh, name)
    }

    /* Attribute reference operations. */
    @JvmStatic
    fun getattrref_i(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intAttrRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int attribute reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceAttribute
        ref.obj = obj
        ref.classHandle = decont(ch, tc)
        ref.name = name
        ref.hint = STable.NO_HINT
        return ref
    }
    @JvmStatic
    fun getattrref_u(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.uintAttrRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int attribute reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceAttribute
        ref.obj = obj
        ref.classHandle = decont(ch, tc)
        ref.name = name
        ref.hint = STable.NO_HINT
        return ref
    }
    @JvmStatic
    fun getattrref_n(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.numAttrRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num attribute reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceAttribute
        ref.obj = obj
        ref.classHandle = decont(ch, tc)
        ref.name = name
        ref.hint = STable.NO_HINT
        return ref
    }
    @JvmStatic
    fun getattrref_s(obj: SixModelObject?, ch: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strAttrRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No string attribute reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceAttribute
        ref.obj = obj
        ref.classHandle = decont(ch, tc)
        ref.name = name
        ref.hint = STable.NO_HINT
        return ref
    }

    /* Positional operations. */
    @JvmStatic
    fun atpos(arr: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject? {
        /* Indexing a null reads back as null on MoarVM rather than throwing,
         * and code in the wild relies on that (unlike elems/existskey, which
         * do throw there); keep the two backends saying the same thing. */
        return arr?.at_pos_boxed(tc, idx)
    }
    @JvmStatic
    fun atpos_i(arr: SixModelObject?, idx: Long, tc: ThreadContext): Long {
        arr!!.at_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun atpos_u(arr: SixModelObject?, idx: Long, tc: ThreadContext): Long {
        arr!!.at_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun atpos_n(arr: SixModelObject?, idx: Long, tc: ThreadContext): Double {
        arr!!.at_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun atpos_s(arr: SixModelObject?, idx: Long, tc: ThreadContext): String? {
        arr!!.at_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun bindpos(arr: SixModelObject?, idx: Long, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        arr!!.bind_pos_boxed(tc, idx, value)
        if (arr.sc != null) { /*new Exception("bindpos").printStackTrace(); */
            scwbObject(tc, arr) }
        return value
    }
    @JvmStatic
    fun bindpos_i(arr: SixModelObject?, idx: Long, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        if (arr.sc != null) { /*new Exception("bindpos_i").printStackTrace(); */
            scwbObject(tc, arr) }
        return value
    }
    @JvmStatic
    fun bindpos_u(arr: SixModelObject?, idx: Long, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        if (arr.sc != null) { /*new Exception("bindpos_u").printStackTrace(); */
            scwbObject(tc, arr) }
        return value
    }
    @JvmStatic
    fun bindpos_n(arr: SixModelObject?, idx: Long, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        arr!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        if (arr.sc != null) { /*new Exception("bindpos_n").printStackTrace(); */
            scwbObject(tc, arr) }
        return value
    }
    @JvmStatic
    fun bindpos_s(arr: SixModelObject?, idx: Long, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        arr!!.bind_pos_native(tc, idx)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        if (arr.sc != null) { /*new Exception("bindpos_s").printStackTrace(); */
            scwbObject(tc, arr) }
        return value
    }
    @JvmStatic
    fun push(arr: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        arr!!.push_boxed(tc, value)
        if (arr.sc != null)
            scwbObject(tc, arr)
        return value
    }
    @JvmStatic
    fun push_i(arr: SixModelObject?, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.push_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        if (arr.sc != null)
            scwbObject(tc, arr)
        return value
    }
    @JvmStatic
    fun push_n(arr: SixModelObject?, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        arr!!.push_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        if (arr.sc != null)
            scwbObject(tc, arr)
        return value
    }
    @JvmStatic
    fun push_s(arr: SixModelObject?, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        arr!!.push_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        if (arr.sc != null)
            scwbObject(tc, arr)
        return value
    }
    @JvmStatic
    fun pop(arr: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return arr!!.pop_boxed(tc)
    }
    @JvmStatic
    fun pop_i(arr: SixModelObject?, tc: ThreadContext): Long {
        arr!!.pop_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun pop_n(arr: SixModelObject?, tc: ThreadContext): Double {
        arr!!.pop_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun pop_s(arr: SixModelObject?, tc: ThreadContext): String? {
        arr!!.pop_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun unshift(arr: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        arr!!.unshift_boxed(tc, value)
        return value
    }
    @JvmStatic
    fun unshift_i(arr: SixModelObject?, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.unshift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return value
    }
    @JvmStatic
    fun unshift_n(arr: SixModelObject?, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        arr!!.unshift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return value
    }
    @JvmStatic
    fun unshift_s(arr: SixModelObject?, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        arr!!.unshift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return value
    }
    @JvmStatic
    fun shift(arr: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return arr!!.shift_boxed(tc)
    }
    @JvmStatic
    fun shift_i(arr: SixModelObject?, tc: ThreadContext): Long {
        arr!!.shift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun shift_n(arr: SixModelObject?, tc: ThreadContext): Double {
        arr!!.shift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun shift_s(arr: SixModelObject?, tc: ThreadContext): String? {
        arr!!.shift_native(tc)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun slice(arr: SixModelObject?, beginning: Long, end: Long, tc: ThreadContext): SixModelObject? {
        val dest = arr!!.st.REPR.allocate(tc, arr.st)
        return arr.slice(tc, dest, beginning, end)
    }
    @JvmStatic
    fun splice(arr: SixModelObject?, from: SixModelObject?, offset: Long, count: Long, tc: ThreadContext): SixModelObject? {
        arr!!.splice(tc, from!!, offset, count)
        return arr
    }

    /* Multi-dimensional positional access ops. */
    private fun smoToLongArray(tc: ThreadContext, arr: SixModelObject): LongArray {
        val res = LongArray(arr.elems(tc).toInt())
        for (i in res.indices) {
            arr.at_pos_native(tc, i.toLong())
            res[i] = tc.nativeI
        }
        return res
    }
    @JvmStatic
    fun atpos2d_o(arr: SixModelObject?, idx1: Long, idx2: Long, tc: ThreadContext): SixModelObject? {
        return arr!!.at_pos_multidim_boxed(tc, longArrayOf(idx1, idx2))
    }
    @JvmStatic
    fun atpos2d_i(arr: SixModelObject?, idx1: Long, idx2: Long, tc: ThreadContext): Long {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun atpos2d_n(arr: SixModelObject?, idx1: Long, idx2: Long, tc: ThreadContext): Double {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun atpos2d_s(arr: SixModelObject?, idx1: Long, idx2: Long, tc: ThreadContext): String? {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun atpos3d_o(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, tc: ThreadContext): SixModelObject? {
        return arr!!.at_pos_multidim_boxed(tc, longArrayOf(idx1, idx2, idx3))
    }
    @JvmStatic
    fun atpos3d_i(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, tc: ThreadContext): Long {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun atpos3d_n(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, tc: ThreadContext): Double {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun atpos3d_s(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, tc: ThreadContext): String? {
        arr!!.at_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun atposnd_o(arr: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return arr!!.at_pos_multidim_boxed(tc, smoToLongArray(tc, indices!!))
    }
    @JvmStatic
    fun atposnd_i(arr: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): Long {
        arr!!.at_pos_multidim_native(tc, smoToLongArray(tc, indices!!))
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return tc.nativeI
    }
    @JvmStatic
    fun atposnd_n(arr: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): Double {
        arr!!.at_pos_multidim_native(tc, smoToLongArray(tc, indices!!))
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return tc.nativeN
    }
    @JvmStatic
    fun atposnd_s(arr: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): String? {
        arr!!.at_pos_multidim_native(tc, smoToLongArray(tc, indices!!))
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return tc.nativeS
    }
    @JvmStatic
    fun bindpos2d_o(arr: SixModelObject?, idx1: Long, idx2: Long, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        arr!!.bind_pos_multidim_boxed(tc, longArrayOf(idx1, idx2), value)
        return value
    }
    @JvmStatic
    fun bindpos2d_i(arr: SixModelObject?, idx1: Long, idx2: Long, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return value
    }
    @JvmStatic
    fun bindpos2d_n(arr: SixModelObject?, idx1: Long, idx2: Long, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return value
    }

    @JvmStatic
    fun bindpos2d_s(arr: SixModelObject?, idx1: Long, idx2: Long, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2))
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return value
    }
    @JvmStatic
    fun bindpos3d_o(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        arr!!.bind_pos_multidim_boxed(tc, longArrayOf(idx1, idx2, idx3), value)
        return value
    }
    @JvmStatic
    fun bindpos3d_i(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return value
    }
    @JvmStatic
    fun bindpos3d_n(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return value
    }
    @JvmStatic
    fun bindpos3d_s(arr: SixModelObject?, idx1: Long, idx2: Long, idx3: Long, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        arr!!.bind_pos_multidim_native(tc, longArrayOf(idx1, idx2, idx3))
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return value
    }
    @JvmStatic
    fun bindposnd_o(arr: SixModelObject?, indices: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val jIndices = smoToLongArray(tc, indices!!)
        arr!!.bind_pos_multidim_boxed(tc, jIndices, value)
        return value
    }
    @JvmStatic
    fun bindposnd_i(arr: SixModelObject?, indices: SixModelObject?, value: Long, tc: ThreadContext): Long {
        val jIndices = smoToLongArray(tc, indices!!)
        tc.nativeI = value
        arr!!.bind_pos_multidim_native(tc, jIndices)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int array")
        return value
    }
    @JvmStatic
    fun bindposnd_n(arr: SixModelObject?, indices: SixModelObject?, value: Double, tc: ThreadContext): Double {
        val jIndices = smoToLongArray(tc, indices!!)
        tc.nativeN = value
        arr!!.bind_pos_multidim_native(tc, jIndices)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num array")
        return value
    }
    @JvmStatic
    fun bindposnd_s(arr: SixModelObject?, indices: SixModelObject?, value: String?, tc: ThreadContext): String? {
        val jIndices = smoToLongArray(tc, indices!!)
        tc.nativeS = value
        arr!!.bind_pos_multidim_native(tc, jIndices)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str array")
        return value
    }

    /* Positional reference operations. */
    @JvmStatic
    fun atposref_i(obj: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intPosRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstancePositional
        ref.obj = obj
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun atposref_u(obj: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.uintPosRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No uint positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstancePositional
        ref.obj = obj
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun atposref_n(obj: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.numPosRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstancePositional
        ref.obj = obj
        ref.idx = idx
        return ref
    }
    @JvmStatic
    fun atposref_s(obj: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strPosRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No string positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstancePositional
        ref.obj = obj
        ref.idx = idx
        return ref
    }

    /* Positional multidim reference operations. */
    @JvmStatic
    fun multidimref_i(obj: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intMultidimRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int multidim positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceMultidim
        ref.obj = obj
        ref.indices = smoToLongArray(tc, indices!!)
        return ref
    }

    @JvmStatic
    fun multidimref_u(obj: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.uintMultidimRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No int multidim positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceMultidim
        ref.obj = obj
        ref.indices = smoToLongArray(tc, indices!!)
        return ref
    }

    @JvmStatic
    fun multidimref_n(obj: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.numMultidimRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No num multidim positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceMultidim
        ref.obj = obj
        ref.indices = smoToLongArray(tc, indices!!)
        return ref
    }

    @JvmStatic
    fun multidimref_s(obj: SixModelObject?, indices: SixModelObject?, tc: ThreadContext): SixModelObject {
        val refType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strMultidimRef
        if (isnull(refType) == 1L)
            throw ExceptionHandling.dieInternal(tc,
                "No str multidim positional reference type registered for current HLL")
        val ref = refType!!.st.REPR.allocate(tc, refType.st) as NativeRefInstanceMultidim
        ref.obj = obj
        ref.indices = smoToLongArray(tc, indices!!)
        return ref
    }

    /* Associative operations. */
    @JvmStatic
    fun atkey(hash: SixModelObject?, key: String?, tc: ThreadContext): SixModelObject? {
        /* As with atpos above: MoarVM hands back a null here instead of
         * throwing, so match it. */
        return hash?.at_key_boxed(tc, key)
    }
    @JvmStatic
    fun atkey_i(hash: SixModelObject?, key: String?, tc: ThreadContext): Long {
        hash!!.at_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int hash")
        return tc.nativeI
    }
    @JvmStatic
    fun atkey_n(hash: SixModelObject?, key: String?, tc: ThreadContext): Double {
        hash!!.at_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num hash")
        return tc.nativeN
    }
    @JvmStatic
    fun atkey_s(hash: SixModelObject?, key: String?, tc: ThreadContext): String? {
        hash!!.at_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str hash")
        return tc.nativeS
    }
    @JvmStatic
    fun bindkey(hash: SixModelObject?, key: String?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        hash!!.bind_key_boxed(tc, key, value)
        if (hash.sc != null)
            scwbObject(tc, hash)
        return value
    }
    @JvmStatic
    fun bindkey_i(hash: SixModelObject?, key: String?, value: Long, tc: ThreadContext): Long {
        tc.nativeI = value
        hash!!.bind_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_INT)
            throw ExceptionHandling.dieInternal(tc, "This is not a native int hash")
        if (hash.sc != null)
            scwbObject(tc, hash)
        return value
    }
    @JvmStatic
    fun bindkey_n(hash: SixModelObject?, key: String?, value: Double, tc: ThreadContext): Double {
        tc.nativeN = value
        hash!!.bind_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_NUM)
            throw ExceptionHandling.dieInternal(tc, "This is not a native num hash")
        if (hash.sc != null)
            scwbObject(tc, hash)
        return value
    }
    @JvmStatic
    fun bindkey_s(hash: SixModelObject?, key: String?, value: String?, tc: ThreadContext): String? {
        tc.nativeS = value
        hash!!.bind_key_native(tc, key)
        if (tc.nativeType != ThreadContext.NATIVE_STR)
            throw ExceptionHandling.dieInternal(tc, "This is not a native str hash")
        if (hash.sc != null)
            scwbObject(tc, hash)
        return value
    }
    @JvmStatic
    fun existskey(hash: SixModelObject?, key: String?, tc: ThreadContext): Long {
        return hash!!.exists_key(tc, key)
    }
    @JvmStatic
    fun deletekey(hash: SixModelObject?, key: String?, tc: ThreadContext): SixModelObject? {
        hash!!.delete_key(tc, key)
        if (hash.sc != null)
            scwbObject(tc, hash)
        return hash
    }

    /* Terms */
    @JvmStatic
    fun time(): Long {
        return System.currentTimeMillis() * 1000000
    }

    /* Aggregate operations. */
    @JvmStatic
    fun elems(agg: SixModelObject?, tc: ThreadContext): Long {
        return agg!!.elems(tc)
    }
    @JvmStatic
    fun setelems(agg: SixModelObject?, elems: Long, tc: ThreadContext): SixModelObject? {
        agg!!.set_elems(tc, elems)
        return agg
    }
    @JvmStatic
    fun numdimensions(agg: SixModelObject?, tc: ThreadContext): Long {
        return agg!!.dimensions(tc).size.toLong()
    }
    @JvmStatic
    fun dimensions(agg: SixModelObject?, tc: ThreadContext): SixModelObject {
        val dims = agg!!.dimensions(tc)
        val BOOTIntArray = tc.gc.BOOTIntArray!!
        val dimRes = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st)
        for (i in dims.indices) {
            tc.nativeI = dims[i]
            dimRes.bind_pos_native(tc, i.toLong())
        }
        return dimRes
    }
    @JvmStatic
    fun setdimensions(agg: SixModelObject?, dims: SixModelObject?, tc: ThreadContext): SixModelObject? {
        agg!!.set_dimensions(tc, smoToLongArray(tc, dims!!))
        return agg
    }
    @JvmStatic
    fun existspos(agg: SixModelObject?, key: Long, tc: ThreadContext): Long {
        return agg!!.exists_pos(tc, key)
    }
    @JvmStatic
    fun islist(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 0L && obj!!.st.REPR is VMArray) 1 else 0
    }
    @JvmStatic
    fun ishash(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (isnull(obj) == 0L && obj!!.st.REPR is VMHash) 1 else 0
    }

    /* Parametricity operations. */
    @JvmStatic
    fun setparameterizer(type: SixModelObject?, parameterizer: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val st = type!!.st

        /* Ensure that the type is not already parametric or parameterized. */
        if (st.parametricity != null) {
            if (st.parametricity is ParametricType)
                ExceptionHandling.dieInternal(tc, "This type is already parametric")
            if (st.parametricity is ParameterizedType)
                ExceptionHandling.dieInternal(tc, "Cannot make a parameterized type also be parametric")
        }

        /* Set up the type as parameterized. */
        val pt = ParametricType()
        pt.parameterizer = parameterizer
        pt.lookup = ArrayList()
        st.parametricity = pt

        return type
    }
    @JvmStatic
    fun parameterizetype(type: SixModelObject?, params: SixModelObject?, tc: ThreadContext): SixModelObject? {
        /* Ensure we have a parametric type. */
        val st = type!!.st
        if (st.parametricity !is ParametricType)
            ExceptionHandling.dieInternal(tc, "This type is not parametric")

        /* Do a lookup in the parameterizations array. */
        val lookup = (st.parametricity as ParametricType).lookup!!
        val numLookups = lookup.size
        val paramsElems = params!!.elems(tc)
        for (i in 0 until numLookups) {
            val entry = lookup[i]
            val compare = entry.key
            if (paramsElems == compare.elems(tc)) {
                var match = true
                for (j in 0 until paramsElems) {
                    val want = params.at_pos_boxed(tc, j)
                    val got = compare.at_pos_boxed(tc, j)
                    /* XXX More cases to consider here. */
                    if (want !== got) {
                        match = false
                        break
                    }
                }
                if (match)
                    return entry.value
            }
        }

        /* It wasn't found; run parameterizer. */
        invokeDirect(tc, (st.parametricity as ParametricType).parameterizer,
            parameterizeCallSite, arrayOf<Any?>(st.WHAT, params))
        val result = result_o(tc.frame)

        /* Mark parametric and stash required data. */
        val newSTable = result!!.st
        val pt = ParameterizedType()
        pt.parametricType = type
        pt.parameters = params
        newSTable.parametricity = pt

        /* Add to lookup table. */
        /* XXX handle possible race. */
        lookup.add(AbstractMap.SimpleEntry(params, result))

        return result
    }
    @JvmStatic
    fun typeparameterized(type: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val theType = decont(type, tc)
        val st = theType!!.st
        return if (st.parametricity is ParameterizedType)
            (st.parametricity as ParameterizedType).parametricType
        else null
    }
    @JvmStatic
    fun typeparameters(type: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val theType = decont(type, tc)
        val st = theType!!.st
        if (st.parametricity !is ParameterizedType)
            ExceptionHandling.dieInternal(tc, "This type is not parameterized")
        return (st.parametricity as ParameterizedType).parameters
    }
    @JvmStatic
    fun typeparameterat(type: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject? {
        return typeparameters(type, tc)!!.at_pos_boxed(tc, idx)
    }

    @JvmStatic
    fun setdebugtypename(type: SixModelObject?, debugName: String?, tc: ThreadContext): SixModelObject? {
        type!!.st.debugName = debugName
        return type
    }

    /* Container operations. */
    @JvmStatic
    fun setcontspec(obj: SixModelObject?, confname: String?, confarg: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj!!.st.ContainerSpec != null)
            ExceptionHandling.dieInternal(tc, "Cannot change a type's container specification")

        val cc = tc.gc.contConfigs.get(confname)
        if (cc == null)
            ExceptionHandling.dieInternal(tc, "No such container spec " + confname)
        cc!!.setContainerSpec(tc, obj.st)
        cc.configureContainerSpec(tc, obj.st, confarg!!)

        return obj
    }
    @JvmStatic
    fun iscont(obj: SixModelObject?): Long {
        return if (isnull(obj) == 1L || obj!!.st.ContainerSpec == null) 0 else 1
    }
    @JvmStatic
    fun isrwcont(obj: SixModelObject?, tc: ThreadContext): Long {
        if (isnull(obj) == 0L) {
            val cs = obj!!.st.ContainerSpec
            if (cs != null && cs.canStore(tc, obj))
                return 1
        }
        return 0
    }
    private fun getContainerPrimitive(obj: SixModelObject?): BoxedPrimitive {
        if (isnull(obj) == 0L && obj !is TypeObject) {
            val cs = obj!!.st.ContainerSpec
            if (cs is NativeRefContainerSpec)
                return (obj.st.REPRData as NativeRefREPRData).primitiveType
        }
        return BoxedPrimitive.NONE
    }
    @JvmStatic
    fun iscont_i(obj: SixModelObject?): Long {
        return if (getContainerPrimitive(obj) == BoxedPrimitive.INT) 1 else 0
    }
    @JvmStatic
    fun iscont_u(obj: SixModelObject?): Long {
        return if (getContainerPrimitive(obj) == BoxedPrimitive.UINT) 1 else 0
    }
    @JvmStatic
    fun iscont_n(obj: SixModelObject?): Long {
        return if (getContainerPrimitive(obj) == BoxedPrimitive.NUM) 1 else 0
    }
    @JvmStatic
    fun iscont_s(obj: SixModelObject?): Long {
        return if (getContainerPrimitive(obj) == BoxedPrimitive.STR) 1 else 0
    }
    @JvmStatic
    fun decont(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        /* Deconting our null used to hand back a Java null, which is a
         * different thing: a Java null in a P6opaque attribute slot is
         * indistinguishable from "never assigned", so reading the attribute
         * back auto-vivified it to the attribute's type object. MoarVM's null
         * is an ordinary object that survives a decont, so nqp::null stored in
         * an attribute reads back as nqp::null. Only a real Java null stays
         * one now; the null object falls through the ContainerSpec check
         * below and is returned as-is. */
        if (obj == null)
            return null
        val cs = obj.st.ContainerSpec
        return if (cs == null || obj is TypeObject) obj else cs.fetch(tc, obj)
    }
    @JvmStatic
    fun decont_i(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj !is TypeObject) {
            val cs = obj!!.st.ContainerSpec
            if (cs != null)
                return cs.fetch_i(tc, obj)
        }
        return obj!!.get_int(tc)
    }
    @JvmStatic
    fun decont_u(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj !is TypeObject) {
            val cs = obj!!.st.ContainerSpec
            if (cs != null)
                return cs.fetch_i(tc, obj)
        }
        return unsignedFrom(obj!!, tc)
    }
    @JvmStatic
    fun decont_n(obj: SixModelObject?, tc: ThreadContext): Double {
        if (obj !is TypeObject) {
            val cs = obj!!.st.ContainerSpec
            if (cs != null)
                return cs.fetch_n(tc, obj)
        }
        return obj!!.get_num(tc)
    }
    @JvmStatic
    fun decont_s(obj: SixModelObject?, tc: ThreadContext): String? {
        if (obj !is TypeObject) {
            val cs = obj!!.st.ContainerSpec
            if (cs != null)
                return cs.fetch_s(tc, obj)
        }
        return obj!!.get_str(tc)
    }
    @JvmStatic
    fun assign(cont: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        /* A bare `!!` here reports both a missing container and a missing
         * value as the same bare NullPointerException, with nothing to say
         * which side was null. Say which. */
        if (cont == null)
            throw ExceptionHandling.dieInternal(tc, "Cannot assign: the container is null")
        val cs = cont.st.ContainerSpec
        if (cs != null) {
            val v = decont(value, tc)
                ?: throw ExceptionHandling.dieInternal(tc, "Cannot assign: the value is null")
            cs.store(tc, cont, v)
        }
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }
    @JvmStatic
    fun assign_i(cont: SixModelObject?, value: Long, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            cs.store_i(tc, cont, value)
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }
    @JvmStatic
    fun assign_u(cont: SixModelObject?, value: Long, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            cs.store_i(tc, cont, value) /* FIXME Need a store_u */
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }

    @JvmStatic
    fun assign_n(cont: SixModelObject?, value: Double, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            cs.store_n(tc, cont, value)
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }
    @JvmStatic
    fun assign_s(cont: SixModelObject?, value: String?, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            cs.store_s(tc, cont, value)
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }
    @JvmStatic
    fun assignunchecked(cont: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            cs.storeUnchecked(tc, cont, decont(value, tc)!!)
        else
            ExceptionHandling.dieInternal(tc, "Cannot assign to an immutable value")
        return cont
    }

    /* Iteration. */
    @JvmStatic
    fun iter(agg: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (agg!!.st.REPR is VMArray) {
            val iterType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.arrayIteratorType!!
            val iter = iterType.st.REPR.allocate(tc, iterType.st) as VMIterInstance
            iter.target = agg
            iter.idx = -1
            iter.limit = agg.elems(tc)
            when (agg.st.REPR.get_value_storage_spec(tc, agg.st)!!.boxedPrimitive) {
                BoxedPrimitive.UINT, BoxedPrimitive.INT ->
                    iter.iterMode = VMIterInstance.MODE_ARRAY_INT
                BoxedPrimitive.NUM ->
                    iter.iterMode = VMIterInstance.MODE_ARRAY_NUM
                BoxedPrimitive.STR ->
                    iter.iterMode = VMIterInstance.MODE_ARRAY_STR
                else ->
                    iter.iterMode = VMIterInstance.MODE_ARRAY
            }
            return iter
        }
        else if (agg.st.REPR is VMHash) {
            val iterType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashIteratorType!!
            val iter = iterType.st.REPR.allocate(tc, iterType.st) as VMIterInstance
            iter.target = agg
            @Suppress("UNCHECKED_CAST")
            iter.hashKeyIter = ((agg as VMHashInstance).storage.clone() as HashMap<String, SixModelObject?>).keys.iterator()
            iter.iterMode = VMIterInstance.MODE_HASH
            return iter
        }
        else if (agg.st.REPR is ContextRef) {
            /* Fake up a VMHash and then get its iterator. */
            val BOOTHash = tc.gc.BOOTHash!!
            val hash = BOOTHash.st.REPR.allocate(tc, BOOTHash.st)

            val sci = (agg as ContextRefInstance).context!!.codeRef.staticInfo
            val oLexicalNames = sci.oLexicalNames
            if (oLexicalNames != null) {
                for (i in oLexicalNames.indices)
                    hash.bind_key_boxed(tc, oLexicalNames[i],
                        agg.at_key_boxed(tc, oLexicalNames[i]))
            }
            val iLexicalNames = sci.iLexicalNames
            if (iLexicalNames != null) {
                for (i in iLexicalNames.indices) {
                    agg.at_key_boxed(tc, iLexicalNames[i])
                    hash.bind_key_boxed(tc, iLexicalNames[i],
                        box_i(tc.nativeI, agg.context!!.codeRef.staticInfo.compUnit.hllConfig.intBoxType, tc))
                }
            }
            val nLexicalNames = sci.nLexicalNames
            if (nLexicalNames != null) {
                for (i in nLexicalNames.indices) {
                    agg.at_key_boxed(tc, nLexicalNames[i])
                    hash.bind_key_boxed(tc, nLexicalNames[i],
                        box_n(tc.nativeN, agg.context!!.codeRef.staticInfo.compUnit.hllConfig.numBoxType, tc))
                }
            }
            val sLexicalNames = sci.sLexicalNames
            if (sLexicalNames != null) {
                for (i in sLexicalNames.indices) {
                    agg.at_key_boxed(tc, sLexicalNames[i])
                    hash.bind_key_boxed(tc, sLexicalNames[i],
                        box_s(tc.nativeS, agg.context!!.codeRef.staticInfo.compUnit.hllConfig.strBoxType, tc))
                }
            }

            return iter(hash, tc)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "Can only use iter with representation VMArray and VMHash")
        }
    }
    @JvmStatic
    fun iterkey_s(obj: SixModelObject?, tc: ThreadContext): String? {
        return (obj as VMIterInstance).key_s(tc)
    }
    @JvmStatic
    fun iterval(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return (obj as VMIterInstance).`val`(tc)
    }

    /* Boolification operations. */
    @JvmStatic
    fun setboolspec(obj: SixModelObject?, mode: Long, method: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val bs = BoolificationSpec()
        bs.Mode = mode.toInt()
        bs.Method = method
        obj!!.st.BoolificationSpec = bs
        return obj
    }
    @JvmStatic
    fun istrue(obj: SixModelObject?, tc: ThreadContext): Long {
        val o = decont(obj, tc)
        if (isnull(o) == 1L) return 0
        val bs = o!!.st.BoolificationSpec
        when (if (bs == null) BoolificationSpec.MODE_NOT_TYPE_OBJECT else bs.Mode) {
        BoolificationSpec.MODE_CALL_METHOD -> {
            invokeMethodViaDispatch(tc, bs!!.Method, o)
            return istrue(result_o(tc.frame), tc)
        }
        BoolificationSpec.MODE_UNBOX_INT ->
            return if (o is TypeObject || o.get_int(tc) == 0L) 0 else 1
        BoolificationSpec.MODE_UNBOX_NUM ->
            return if (o is TypeObject || o.get_num(tc) == 0.0) 0 else 1
        BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY ->
            return if (o is TypeObject || o.get_str(tc) == null || o.get_str(tc) == "") 0 else 1
        BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY_OR_ZERO -> {
            if (o is TypeObject)
                return 0
            val str = o.get_str(tc)
            return if (str == null || str == "" || str == "0") 0 else 1
        }
        BoolificationSpec.MODE_NOT_TYPE_OBJECT ->
            return if (o is TypeObject) 0 else 1
        BoolificationSpec.MODE_BIGINT ->
            return if (o is TypeObject || getBI(tc, o).compareTo(BigInteger.ZERO) == 0) 0 else 1
        BoolificationSpec.MODE_ITER ->
            return if ((o as VMIterInstance).boolify()) 1 else 0
        BoolificationSpec.MODE_HAS_ELEMS ->
            return if (o.elems(tc) == 0L) 0 else 1
        else ->
            throw ExceptionHandling.dieInternal(tc, "Invalid boolification spec mode used")
        }
    }
    @JvmStatic
    fun isfalse(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (istrue(obj, tc) == 0L) 1 else 0
    }
    @JvmStatic
    fun istrue_s(str: String?): Long {
        return if (str == null || str == "") 0 else 1
    }
    @JvmStatic
    fun isfalse_s(str: String?): Long {
        return if (str == null || str == "") 1 else 0
    }
    @JvmStatic
    fun not_i(v: Long): Long {
        return if (v == 0L) 1 else 0
    }

    /* Smart coercions. */
    @JvmStatic
    fun smart_stringify(obj: SixModelObject?, tc: ThreadContext): String? {
        val o = decont(obj, tc)

        // If it's null, it's "", 'cause that way we at least don't NPE.
        if (isnull(o) == 1L)
            return ""

        // If it can unbox to a string, that wins right off.
        val ss = o!!.st.REPR.get_storage_spec(tc, o.st)
        if (Boxable.STR in ss.canBox && o !is TypeObject)
            return o.get_str(tc)

        // If it has a Str method, that wins.
        // We could put this in the generated code, but it's here to avoid the
        // bulk.
        // Full resolution, not just the published cache: a HOW that answers
        // find_method itself (the cache un-authoritative or absent) supplies
        // Str here on MoarVM, so it must on the JVM too. The concreteness
        // test is the dispatcher's as well.
        val strMeth = findmethodNonFatal(o, "Str", tc)
        if (isnull(strMeth) == 0L && isconcrete(strMeth, tc) == 1L) {
            invokeMethodViaDispatch(tc, strMeth, o)
            return result_s(tc.frame)
        }

        // If it's a type object, empty string.
        if (o is TypeObject)
            return ""

        // See if it can unbox to another primitive we can stringify.
        if (Boxable.INT in ss.canBox)
            return coerce_i2s(o.get_int(tc))
        if (Boxable.NUM in ss.canBox)
            return coerce_n2s(o.get_num(tc))

        // If it's an exception, take the message.
        if (o is VMExceptionInstance) {
            val msg = o.message
            return if (msg == null) "Died" else msg
        }

        // If anything else, we can't do it.
        throw ExceptionHandling.dieInternal(tc, "Cannot stringify this")
    }
    @JvmStatic
    fun smart_numify(obj: SixModelObject?, tc: ThreadContext): Double {
        val o = decont(obj, tc)

        // The nqp-numify dispatcher's case order, so the backends agree:
        // null, concrete num unbox, Num method, type object, elems,
        // boxed str, boxed int.
        if (isnull(o) == 1L)
            return 0.0

        val ss = o!!.st.REPR.get_storage_spec(tc, o.st)
        if (Boxable.NUM in ss.canBox && o !is TypeObject)
            return o.get_num(tc)

        val numMeth = findmethodNonFatal(o, "Num", tc)
        if (isnull(numMeth) == 0L && isconcrete(numMeth, tc) == 1L) {
            invokeMethodViaDispatch(tc, numMeth, o)
            return result_n(tc.frame)
        }

        if (o is TypeObject)
            return 0.0

        if (o is VMArrayInstance || o is VMHashInstance)
            return o.elems(tc).toDouble()
        if (Boxable.STR in ss.canBox)
            return coerce_s2n(o.get_str(tc))
        if (Boxable.INT in ss.canBox)
            return o.get_int(tc).toDouble()

        throw ExceptionHandling.dieInternal(tc, "Cannot numify this")
    }
    @JvmStatic
    fun smart_intify(obj: SixModelObject?, tc: ThreadContext): Long {
        val o = decont(obj, tc)

        // The nqp-intify dispatcher's case order, so the backends agree:
        // null, concrete int unbox, Int method, type object, elems,
        // boxed str, boxed num.
        if (isnull(o) == 1L)
            return 0

        val ss = o!!.st.REPR.get_storage_spec(tc, o.st)
        if (Boxable.INT in ss.canBox && o !is TypeObject)
            return o.get_int(tc)

        // Through the dispatcher: a raw invocation of an onlystar proto's
        // {*} would resume whatever unrelated dispatch is innermost (see
        // invokeMethodViaDispatch).
        val intMeth = findmethodNonFatal(o, "Int", tc)
        if (isnull(intMeth) == 0L && isconcrete(intMeth, tc) == 1L) {
            invokeMethodViaDispatch(tc, intMeth, o)
            return result_i(tc.frame)
        }

        // If it's a type object, zero.
        if (o is TypeObject)
            return 0

        if (o is VMArrayInstance || o is VMHashInstance)
            return o.elems(tc)
        if (Boxable.STR in ss.canBox)
            return coerce_s2i(o.get_str(tc))
        if (Boxable.NUM in ss.canBox)
            return o.get_num(tc).toLong()

        throw ExceptionHandling.dieInternal(tc, "Cannot intify this")
    }

    /* Math operations. */
    @JvmStatic
    fun gcd_i(valA: Long, valB: Long): Long {
        return BigInteger.valueOf(valA).gcd(BigInteger.valueOf(valB))
                .toLong()
    }

    @JvmStatic
    fun gcd_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).gcd(getBI(tc, b)))
    }

    @JvmStatic
    fun lcm_i(valA: Long, valB: Long): Long {
        return valA * (valB / gcd_i(valA, valB))
    }

    @JvmStatic
    fun lcm_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val valA = getBI(tc, a)
        val valB = getBI(tc, b)
        val gcd = valA.gcd(valB)
        return makeBI(tc, type, valA.multiply(valB).divide(gcd).abs())
    }

    @JvmStatic
    fun isnanorinf(n: Double): Long {
        return if (n.isInfinite() || n.isNaN()) 1 else 0
    }

    @JvmStatic
    fun inf(): Double {
        return Double.POSITIVE_INFINITY
    }

    @JvmStatic
    fun neginf(): Double {
        return Double.NEGATIVE_INFINITY
    }

    @JvmStatic
    fun nan(): Double {
        return Double.NaN
    }

    @JvmStatic
    fun radix(radix: Long, str: String?, zpos: Long, flags: Long, tc: ThreadContext): SixModelObject {
        var zvalue: Long = 0
        var charsConverted = 0
        var thePos = zpos
        val chars = str!!.length
        var value = zvalue
        var charsReallyConverted = charsConverted
        var pos: Long = -1
        var ch: Char
        var charValue: Int
        var neg = false

        if (radix > 36) {
            throw ExceptionHandling.dieInternal(tc, "Cannot convert radix of " + radix + " (max 36)")
        }

        ch = if (thePos < chars) str[thePos.toInt()] else '\u0000'

        /* flag 0x02 asks for parsing a leading +/-.
         * We allow both, "HYPHEN-MINUS" and "MINUS SIGN", for negation. */
        if ((flags and 0x02L) != 0L && (ch == '+' || ch == '-' || ch == '−')) {
            neg = (ch == '-' || ch == '−')
            thePos++
            ch = if (thePos < chars) str[thePos.toInt()] else '\u0000'
        }

        while (thePos < chars) {
            charValue = Character.digit(ch, radix.toInt())
            if (charValue == -1) break
            zvalue = zvalue * radix + charValue
            charsConverted++
            thePos++; pos = thePos
            if (charValue != 0 || (flags and 0x04L) == 0L) { value = zvalue; charsReallyConverted = charsConverted }
            if (thePos >= chars) break
            ch = str[thePos.toInt()]
            if (ch != '_') continue
            thePos++
            if (thePos >= chars) break
            ch = str[thePos.toInt()]
        }

        if (neg || (flags and 0x01L) != 0L) { value = -value }

        val hllConfig = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        val result = hllConfig.slurpyArrayType!!.st.REPR.allocate(tc,
                hllConfig.slurpyArrayType!!.st)

        result.push_boxed(tc, box_i(value, hllConfig.intBoxType, tc))
        result.push_boxed(tc, box_i(charsReallyConverted.toLong(), hllConfig.intBoxType, tc))
        result.push_boxed(tc, box_i(pos, hllConfig.intBoxType, tc))

        return result
    }

    @JvmStatic
    fun rand_n(n: Double, tc: ThreadContext): Double {
        return n * tc.random.nextDouble()
    }

    @JvmStatic
    fun srand(n: Long, tc: ThreadContext): Long {
        tc.random.setSeed(n)
        return n
    }

    /* String operations. */
    @JvmStatic
    fun chars(`val`: String?): Long {
        // NOTE: UTF-16 length. Grapheme-indexed chars requires the regex engine's
        // position model to also be grapheme-based (RxCursor/Cursor.nqp track
        // UTF-16 positions and share nqp::substr/eqat with Raku values); until
        // that engine port lands, grapheme-indexing here desyncs the compiler at
        // astral source chars. See docs/jvm-nfg-representation.md.
        return `val`!!.length.toLong()
    }

    @JvmStatic
    fun lc(`val`: String?): String {
        return `val`!!.lowercase()
    }

    @JvmStatic
    fun uc(`val`: String?): String {
        return `val`!!.uppercase()
    }

    private fun codepointToTitleCase(codepoint: Int): String {
        if (codepoint == 223) return "Ss"
        return String(Character.toChars(Character.toTitleCase(codepoint)))
    }

    @JvmStatic
    fun tc(`val`: String?): String {
        var ret = ""
        var offset = 0
        while (offset < `val`!!.length) {
            val codepoint = `val`.codePointAt(offset)
            ret += codepointToTitleCase(codepoint)
            offset += Character.charCount(codepoint)
        }
        return ret
    }

    @JvmStatic
    fun tclc(`in`: String?): String {
        if (`in`!!.length == 0)
            return `in`
        val first = `in`.codePointAt(0)
        return codepointToTitleCase(first) +
            `in`.substring(Character.charCount(first)).lowercase()
    }

    @JvmStatic
    fun x(`val`: String?, count: Long, tc: ThreadContext): String {
        /* Validate count; handle common cases. */
        if (count == 0L)
            return ""
        if (count == 1L)
            return `val`!!
        if (count < 0)
            throw ExceptionHandling.dieInternal(tc, "repeat count (" + count + ") cannot be negative")
        if (count > MAX_GRAPHEMES)
            throw ExceptionHandling.dieInternal(tc, "repeat count (" + count + ") cannot be greater than max allowed number of graphemes " + MAX_GRAPHEMES)

        /* If input string is empty, repeating it is empty. */
        if (`val`!!.length == 0)
            return ""

        /* Total size of the resulting string can't be bigger than a String is allowed to be. */
        val totalCount = `val`.length * count
        if (totalCount > MAX_GRAPHEMES)
            throw ExceptionHandling.dieInternal(tc, "Can't repeat string, required number of graphemes " + totalCount + " > max allowed of " + MAX_GRAPHEMES)

        val retval = StringBuilder(totalCount.toInt())
        for (ii in 1..count) {
            retval.append(`val`)
        }
        return retval.toString()
    }

    @JvmStatic
    fun concat(valA: String?, valB: String?): String {
        return valA + valB
    }

    @JvmStatic
    fun chr(ord: Long, tc: ThreadContext): String {
        if (ord < 0)
            throw ExceptionHandling.dieInternal(tc, "chr codepoint cannot be negative")

        return StringBuffer().append(Character.toChars(ord.toInt())).toString()
    }

    @JvmStatic
    fun join(delimiter: String?, arr: SixModelObject?, tc: ThreadContext): String {
        val prim = arr!!.st.REPR.get_value_storage_spec(tc, arr.st)!!.boxedPrimitive
        if (prim != BoxedPrimitive.NONE && prim != BoxedPrimitive.STR)
            ExceptionHandling.dieInternal(tc, "Unsupported native array type in join")

        val numElems = arr.elems(tc).toInt()
        if (numElems == 0)
            return ""

        val strings = arrayOfNulls<String>(numElems)
        var totalLength = delimiter!!.length * (numElems - 1)

        if (prim == BoxedPrimitive.STR) {
            for (i in 0 until numElems) {
                arr.at_pos_native(tc, i.toLong())
                strings[i] = tc.nativeS
                totalLength += tc.nativeS!!.length
            }
        } else {
            for (i in 0 until numElems) {
                val s = arr.at_pos_boxed(tc, i.toLong())!!.get_str(tc)
                strings[i] = s
                totalLength += s!!.length
            }
        }

        val chars = CharArray(totalLength)
        strings[0]!!.toCharArray(chars, 0, 0, strings[0]!!.length)

        var pos = strings[0]!!.length
        for (i in 1 until strings.size) {
            delimiter.toCharArray(chars, pos, 0, delimiter.length)
            pos += delimiter.length

            strings[i]!!.toCharArray(chars, pos, 0, strings[i]!!.length)
            pos += strings[i]!!.length
        }

        return String(chars)
    }

    @JvmStatic
    fun split(delimiter: String?, string: String?, tc: ThreadContext): SixModelObject? {

        if (string == null || delimiter == null) {
            return null
        }

        val hllConfig = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        val arrayType = hllConfig.slurpyArrayType!!
        val array = arrayType.st.REPR.allocate(tc, arrayType.st)

        val slen = string.length
        if (slen == 0) {
            return array
        }

        val dlen = delimiter.length
        if (dlen == 0) {
            for (i in 0 until slen) {
                val item = string.substring(i, i + 1)
                val value = box_s(item, hllConfig.strBoxType, tc)
                array.push_boxed(tc, value)
            }
        } else {
            var curpos = 0
            var matchpos = string.indexOf(delimiter)
            while (matchpos > -1) {
                val item = string.substring(curpos, matchpos)
                val value = box_s(item, hllConfig.strBoxType, tc)
                array.push_boxed(tc, value)

                curpos = matchpos + dlen
                matchpos = string.indexOf(delimiter, curpos)
            }

            val tail = string.substring(curpos)
            val value = box_s(tail, hllConfig.strBoxType, tc)
            array.push_boxed(tc, value)
        }
        return array
    }

    @JvmStatic
    fun indexfrom(string: String?, pattern: String?, fromIndex: Long): Long {
        if (fromIndex > string!!.length) { return -1 }
        return string.indexOf(pattern!!, fromIndex.toInt()).toLong()
    }

    @JvmStatic
    fun indexic(string: String?, pattern: String?, fromIndex: Long): Long =
        foldedIndex(string!!, pattern!!, fromIndex, ignoreCase = true, ignoreMark = false)

    @JvmStatic
    fun indexim(string: String?, pattern: String?, fromIndex: Long): Long =
        foldedIndex(string!!, pattern!!, fromIndex, ignoreCase = false, ignoreMark = true)

    @JvmStatic
    fun indexicim(string: String?, pattern: String?, fromIndex: Long): Long =
        foldedIndex(string!!, pattern!!, fromIndex, ignoreCase = true, ignoreMark = true)

    private fun isCombiningMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
               type == Character.COMBINING_SPACING_MARK.toInt() ||
               type == Character.ENCLOSING_MARK.toInt()
    }

    /* Folds a string one character at a time, recording for every character of
     * the result which character of the original produced it.
     *
     * Folding is not length-preserving - "ﬆ" casefolds to "st" and "ß" to "ss"
     * - so a match has to be found in folded text and then reported as an
     * index into the original, which is what the map is for. Java has no
     * casefold, but uppercasing applies the full (expanding) mappings and
     * lowercasing then normalises the result, so the pair does the job.
     * Locale.ROOT keeps Turkish dotless i out of it. */
    private fun foldWithMap(s: String, ignoreCase: Boolean, ignoreMark: Boolean): Pair<String, IntArray> {
        val folded = StringBuilder(s.length)
        val origin = IntArray(s.length * 2)
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val width = Character.charCount(cp)
            if (!(ignoreMark && isCombiningMark(cp))) {
                var piece = String(Character.toChars(cp))
                if (ignoreMark) {
                    piece = java.text.Normalizer.normalize(piece, java.text.Normalizer.Form.NFD)
                    piece = piece.filterNot { isCombiningMark(it.code) }
                }
                if (ignoreCase)
                    piece = piece.uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT)
                for (k in piece.indices) {
                    folded.append(piece[k])
                    if (n == origin.size) return foldWithMapSlow(s, ignoreCase, ignoreMark)
                    origin[n++] = i
                }
            }
            i += width
        }
        return Pair(folded.toString(), origin.copyOf(n))
    }

    /* Same thing for the rare string that folds to more than twice its
     * length, where the pre-sized map above would not have fitted. */
    private fun foldWithMapSlow(s: String, ignoreCase: Boolean, ignoreMark: Boolean): Pair<String, IntArray> {
        val folded = StringBuilder(s.length * 2)
        val origin = java.util.ArrayList<Int>(s.length * 2)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!(ignoreMark && isCombiningMark(cp))) {
                var piece = String(Character.toChars(cp))
                if (ignoreMark) {
                    piece = java.text.Normalizer.normalize(piece, java.text.Normalizer.Form.NFD)
                    piece = piece.filterNot { isCombiningMark(it.code) }
                }
                if (ignoreCase)
                    piece = piece.uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT)
                for (k in piece.indices) {
                    folded.append(piece[k])
                    origin.add(i)
                }
            }
            i += Character.charCount(cp)
        }
        return Pair(folded.toString(), origin.toIntArray())
    }

    private fun foldedIndex(haystack: String, needle: String, fromIndex: Long,
                            ignoreCase: Boolean, ignoreMark: Boolean): Long {
        if (fromIndex > haystack.length) return -1
        val from = if (fromIndex < 0) 0 else fromIndex.toInt()
        if (needle.isEmpty()) return from.toLong()

        val (hay, origin) = foldWithMap(haystack, ignoreCase, ignoreMark)
        val (pat, _) = foldWithMap(needle, ignoreCase, ignoreMark)
        if (pat.isEmpty()) return from.toLong()

        // Start where the caller asked, in folded coordinates.
        var foldedFrom = 0
        while (foldedFrom < origin.size && origin[foldedFrom] < from) foldedFrom++

        val hit = hay.indexOf(pat, foldedFrom)
        return if (hit < 0) -1 else origin[hit].toLong()
    }

    @JvmStatic
    fun rindexfromend(string: String?, pattern: String?): Long {
        /* NOTE: explicit start index, because Kotlin's lastIndexOf(String)
         * extension defaults to lastIndex (length - 1) while Java's
         * String.lastIndexOf(String) searches from length — they differ
         * for an empty pattern. */
        return string!!.lastIndexOf(pattern!!, string.length).toLong()
    }

    @JvmStatic
    fun rindexfrom(string: String?, pattern: String?, fromIndex: Long): Long {
        if (fromIndex > string!!.length) { return -1 }
        return string.lastIndexOf(pattern!!, fromIndex.toInt()).toLong()
    }

    @JvmStatic
    fun substr2(`val`: String?, offset: Long): String {
        var theOffset = offset
        if (theOffset >= `val`!!.length)
            return ""
        if (theOffset < 0)
            theOffset += `val`.length
        return `val`.substring(theOffset.toInt())
    }

    @JvmStatic
    fun substr3(`val`: String?, offset: Long, length: Long): String {
        if (offset >= `val`!!.length)
            return ""
        var end = (offset + length).toInt()
        if (end > `val`.length)
            end = `val`.length
        return `val`.substring(offset.toInt(), end)
    }

    // keep this till we reboostrap
    @JvmStatic
    fun string_equal_at(haystack: String?, needle: String?, offset: Long): Long {
        return string_equal_at(false, haystack, needle, offset)
    }

    // does haystack have needle as a substring at offset?
    private fun string_equal_at(ignoreCase: Boolean, haystack: String?, needle: String?, offset: Long): Long {
        val haylen = haystack!!.length.toLong()
        val needlelen = needle!!.length.toLong()

        var theOffset = offset
        if (theOffset < 0) {
            theOffset += haylen
            if (theOffset < 0) {
                theOffset = 0
            }
        }
        if (haylen - theOffset < needlelen) {
            return 0
        }
        return if (haystack.regionMatches(theOffset.toInt(), needle, 0, needlelen.toInt(), ignoreCase = ignoreCase)) 1 else 0
    }

    @JvmStatic
    fun eqat(haystack: String?, needle: String?, offset: Long): Long {
        return string_equal_at(false, haystack, needle, offset)
    }

    @JvmStatic
    fun eqatic(haystack: String?, needle: String?, offset: Long): Long =
        foldedEqAt(haystack!!, needle!!, offset, ignoreCase = true, ignoreMark = false)

    @JvmStatic
    fun eqatim(haystack: String?, needle: String?, offset: Long): Long =
        foldedEqAt(haystack!!, needle!!, offset, ignoreCase = false, ignoreMark = true)

    @JvmStatic
    fun eqaticim(haystack: String?, needle: String?, offset: Long): Long =
        foldedEqAt(haystack!!, needle!!, offset, ignoreCase = true, ignoreMark = true)

    /* Like eqat, but comparing folded text. The offset is an index into the
     * original haystack, and folding is not length-preserving, so it has to be
     * carried over to the folded string before the comparison - see
     * foldWithMap for why. Folding both sides is what makes eqatic('ﬆ', 'st')
     * and eqatic('st', 'ﬆ') both true. */
    private fun foldedEqAt(haystack: String, needle: String, offset: Long,
                           ignoreCase: Boolean, ignoreMark: Boolean): Long {
        var pos = offset
        if (pos < 0) {
            pos += haystack.length
            if (pos < 0) pos = 0
        }
        if (pos > haystack.length) return 0

        val (hay, origin) = foldWithMap(haystack, ignoreCase, ignoreMark)
        val (pat, _) = foldWithMap(needle, ignoreCase, ignoreMark)
        if (pat.isEmpty()) return 1

        // The first folded character at or after the requested offset. "At or
        // after" rather than "at" so that an offset landing on a character
        // that folding dropped still starts somewhere sensible.
        var at = 0
        while (at < origin.size && origin[at] < pos) at++
        if (at >= origin.size) return 0

        return if (hay.startsWith(pat, at)) 1 else 0
    }

    @JvmStatic
    fun ordfirst(str: String?): Long {
        if (str!!.isEmpty()) {
            return -1
        }
        else {
            return str.codePointAt(0).toLong()
        }
    }

    @JvmStatic
    fun ordat(str: String?, offset: Long): Long {
        if (offset < 0 || offset >= str!!.length) {
            return -1
        }
        else {
            return str.codePointAt(offset.toInt()).toLong()
        }
    }

    @JvmStatic
    fun ordbaseat(str: String?, offset: Long): Long {
        if (offset < 0 || offset >= str!!.length) {
            return -1
        }
        else {
            val code = str.codePointAt(offset.toInt())
            val letter = String(intArrayOf(code), 0, 1)
            return Normalizer.normalize(letter, Normalizer.Form.NFD).codePointAt(0).toLong()
        }
    }

    @JvmStatic
    fun sprintf(format: String?, arr: SixModelObject?, tc: ThreadContext): String {
        // This function just assumes that Java's printf format is compatible
        // with NQP's printf format...

        val numElems = arr!!.elems(tc).toInt()
        val args = arrayOfNulls<Any>(numElems)

        for (i in 0 until numElems) {
            val obj = arr.at_pos_boxed(tc, i.toLong())
            val ss = obj!!.st.REPR.get_storage_spec(tc, obj.st)
            if (Boxable.INT in ss.canBox) {
                args[i] = java.lang.Long.valueOf(obj.get_int(tc))
            } else if (Boxable.NUM in ss.canBox) {
                args[i] = java.lang.Double.valueOf(obj.get_num(tc))
            } else if (Boxable.STR in ss.canBox) {
                args[i] = obj.get_str(tc)
            } else {
                throw IllegalArgumentException("sprintf only accepts ints, nums, and strs, not " + obj.javaClass)
            }
        }

        return String.format(format!!, *args)
    }

    @JvmStatic
    fun escape(str: String?): String {
        val len = str!!.length
        val sb = StringBuilder(2 * len)
        for (i in 0 until len) {
            val c = str[i]
            when (c) {
            '\\' -> sb.append("\\\\")
            7.toChar() -> sb.append("\\a")
            '\b' -> sb.append("\\b")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u000C' -> sb.append("\\f")
            '"' -> sb.append("\\\"")
            27.toChar() -> sb.append("\\e")
            else ->
                sb.append(c)
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun flip(str: String?): String {
        return StringBuffer(str!!).reverse().toString()
    }

    @JvmStatic
    fun replace(str: String?, offset: Long, count: Long, repl: String?): String {
        return StringBuffer(str!!).replace(offset.toInt(), (offset + count).toInt(), repl!!).toString()
    }

    /* Brute force, but not normally needed for most programs. */
    @Volatile private var cpNameMap: Object2IntOpenHashMap<String>? = null
    @Volatile private var cpNameMapAboveBMP: Boolean? = null
    @JvmStatic
    fun codepointfromname(name: String?): Long {
        var names = cpNameMap
        /* Gets the first half (the BMP) */
        if (names == null) {
            /* Initialize the expected max hash size as 0x10FFFF */
            names = Object2IntOpenHashMap<String>(0x10FFFF)
            for (i in 0 until Character.MAX_VALUE.code)
                if (Character.isValidCodePoint(i))
                    names.put(Character.getName(i), i)
            names.put("ALERT",            7)
            names.put("BEL",              7)
            names.put("LF",               10)
            names.put("LINE FEED",        10)
            names.put("FF",               12)
            names.put("FORM FEED",        12)
            names.put("CR",               13)
            names.put("CARRIAGE RETURN",  13)
            names.put("NEL",              133)
            names.put("NEXT LINE",        133)
            names.removeInt("BELL") // added below as 0x1F514, cmp. RT #130542
            cpNameMap = names
            cpNameMapAboveBMP = false
        }
        var found = names.getOrDefault(name, -1)
        /* If we have not found it yet, put all other possible codepoints into
         * the hash */
        if (found == -1 && !cpNameMapAboveBMP!!) {
            for (i in Character.MAX_VALUE.code..0x10FFFF)
                if (Character.isValidCodePoint(i))
                    names.put(Character.getName(i), i)
            names.put("BELL",             0x1F514)
            cpNameMapAboveBMP = true
            found = names.getOrDefault(name, -1)
        }
        return found.toLong()
    }

    @JvmStatic
    fun strfromname(name: String?): String {
        val cp = codepointfromname(name!!.uppercase())
        /* nqp::chr has been inlined, since it needs a thread context */
        return if (cp < 0)
            ""
        else
            StringBuffer().append(Character.toChars(cp.toInt())).toString()
    }

    @JvmStatic
    fun strfromcodes(codes: SixModelObject?, tc: ThreadContext): String {
        val builder = StringBuilder()
        val n = codes!!.elems(tc).toInt()
        for (i in 0 until n) {
            codes.at_pos_native(tc, i.toLong())
            builder.appendCodePoint(tc.nativeI.toInt())
        }
        return builder.toString()
    }

    private fun javaNormalizationForm(normalization: Long, tc: ThreadContext): Normalizer.Form {
        /* nqp::const values (Compiler.nqp): NFC=1, NFD=2, NFKC=3, NFKD=4.
         * The old code duplicated `== 1L`, leaving NFD unreachable and NFKC/NFKD
         * off by one. */
        return when (normalization) {
            1L -> Normalizer.Form.NFC
            2L -> Normalizer.Form.NFD
            3L -> Normalizer.Form.NFKC
            4L -> Normalizer.Form.NFKD
            else -> throw ExceptionHandling.dieInternal(tc, "Unknown normalization form: '" + normalization + "'")
        }
    }

    @JvmStatic
    fun normalizecodes(`in`: SixModelObject?, normalization: Long, out: SixModelObject?, tc: ThreadContext): SixModelObject? {
      if (normalization == 0L) {
          val n = `in`!!.elems(tc).toInt()
          out!!.set_elems(tc, n.toLong())
          for (i in 0 until n) {
              `in`.at_pos_native(tc, i.toLong())
              out.bind_pos_native(tc, i.toLong())
          }
      }
      else {
          val builder = StringBuilder()
          val n = `in`!!.elems(tc).toInt()
          for (i in 0 until n) {
              `in`.at_pos_native(tc, i.toLong())
              builder.appendCodePoint(tc.nativeI.toInt())
          }

          var i = 0
          for (c in Normalizer.normalize(builder, javaNormalizationForm(normalization, tc)).codePoints().toArray()) {
              tc.nativeI = c.toLong()
              out!!.bind_pos_native(tc, (i++).toLong())
          }
      }

      return out
    }

    @JvmStatic
    fun codes(str: String?): Long {
        return Normalizer.normalize(str, Normalizer.Form.NFC).codePoints().count()
    }

    @JvmStatic
    fun strtocodes(str: String?, normalization: Long, codes: SixModelObject?, tc: ThreadContext): SixModelObject? {
        var i = 0
        for (c in Normalizer.normalize(str, javaNormalizationForm(normalization, tc)).codePoints().toArray()) {
            tc.nativeI = c.toLong()
            codes!!.bind_pos_native(tc, (i++).toLong())
        }
        return codes
    }

    private fun javaEncodingName(nameIn: String): String? {
        if (nameIn == "utf8")
            return "UTF-8"
        if (nameIn == "ascii")
            return "US-ASCII"
        if (nameIn == "iso-8859-1")
            return "ISO-8859-1"
        if (nameIn == "windows-1252")
            return "windows-1252"
        if (nameIn == "windows-1251")
            return "windows-1251"
        if (nameIn == "utf16be")
            return "UTF-16BE"
        if (nameIn == "utf16le")
            return "UTF-16LE"
        return null
    }

    private fun encodeUTF16(str: String, res: SixModelObject, tc: ThreadContext) {
        val buffer = ShortArray(str.length)
        for (i in 0 until str.length)
            buffer[i] = str[i].code.toShort()
        if (res is VMArrayInstance_i16) {
            res.elems = buffer.size
            res.start = 0
            res.slots = buffer
        }
        else {
            res.set_elems(tc, buffer.size.toLong())
            for (i in buffer.indices) {
                tc.nativeI = buffer[i].toLong()
                res.bind_pos_native(tc, i.toLong())
            }
        }
    }

    private fun encodeUTF32(str: String, res: SixModelObject, tc: ThreadContext) {
        val buffer = IntArray(str.length) /* Can be an overestimate. */
        var bufPos = 0
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            buffer[bufPos++] = cp
            i += Character.charCount(cp)
        }
        if (res is VMArrayInstance_i32) {
            res.elems = bufPos
            res.start = 0
            res.slots = buffer
        }
        else {
            res.set_elems(tc, buffer.size.toLong())
            for (j in 0 until bufPos) {
                tc.nativeI = buffer[j].toLong()
                res.bind_pos_native(tc, j.toLong())
            }
        }
    }

    @JvmStatic
    fun encode(str: String?, encoding: String?, res: SixModelObject?, tc: ThreadContext): SixModelObject? {
        try {
            val mangledEncoding = javaEncodingName(encoding!!)
            if (mangledEncoding != null) {
                Buffers.stashBytes(tc, res!!, str!!.toByteArray(charset(mangledEncoding)))
            }
            else if (encoding == "utf16") {
                encodeUTF16(str!!, res!!, tc)
            }
            else if (encoding == "utf32") {
                encodeUTF32(str!!, res!!, tc)
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Unknown encoding '" + encoding + "'")
            }
            return res
        }
        catch (e: UnsupportedEncodingException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    private fun growBuffer(tooSmall: ByteBuffer): ByteBuffer {
        val biggerBuffer = ByteBuffer.allocate(tooSmall.capacity() * 2)
        tooSmall.flip()
        biggerBuffer.put(tooSmall)
        return biggerBuffer
    }

    @JvmStatic
    fun encoderep(str: String?, encoding: String?, replacement: String?, res: SixModelObject?, tc: ThreadContext): SixModelObject? {
        try {
            val mangledEncoding = javaEncodingName(encoding!!)
            if (mangledEncoding != null) {
                val encoder = Charset.forName(encoding).newEncoder()
                val input = CharBuffer.wrap(str)
                var outputBuffer = ByteBuffer.allocate(Math.ceil(
                  (encoder.maxBytesPerChar() * input.remaining()).toDouble()).toInt())

                while (true) {
                    val result = encoder.encode(input, outputBuffer, true)

                    if (result.isUnderflow()) {
                        break
                    } else if (result.isOverflow()) {
                        outputBuffer = growBuffer(outputBuffer)
                    } else if (result.isUnmappable()) {
                        val unmappableChar = input.get()

                        if (Character.isHighSurrogate(unmappableChar)) {
                            if (!Character.isSurrogatePair(unmappableChar, input.get())) {
                                throw ExceptionHandling.dieInternal(tc, "Encode with string with malformed unicode")
                            }
                        }

                        val replacementBuffer = CharBuffer.wrap(replacement)
                        while (true) {
                            val replacementResult = encoder.encode(replacementBuffer, outputBuffer, true)
                            if (replacementResult.isOverflow()) {
                                outputBuffer = growBuffer(outputBuffer)
                            } else if (replacementResult.isUnderflow()) {
                                break
                            } else {
                                replacementResult.throwException()
                            }
                        }
                    } else {
                        result.throwException()
                    }
                }
                Buffers.stashBytes(tc, res!!, outputBuffer.array(), outputBuffer.position())
            }
            else if (encoding == "utf16") {
                encodeUTF16(str!!, res!!, tc)
            }
            else if (encoding == "utf32") {
                encodeUTF32(str!!, res!!, tc)
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Unknown encoding '" + encoding + "'")
            }

            return res
        }
        catch (e: CharacterCodingException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        catch (e: BufferUnderflowException) {
            /* If this happens we got a string with malformed UTF-16 */
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    fun decode8(buf: SixModelObject?, csName: String?, tc: ThreadContext): String {
        val bb = Buffers.unstashBytes(buf!!, tc)
        return Charset.forName(csName).decode(bb).toString()
    }

    @JvmStatic
    fun decode(buf: SixModelObject?, encoding: String?, tc: ThreadContext): String {
        val mangledEncoding = javaEncodingName(encoding!!)
        if (mangledEncoding != null) {
            if ((mangledEncoding == "UTF-16BE" || mangledEncoding == "UTF-16LE")
                && (buf is VMArrayInstance_u8 || buf is VMArrayInstance_i8)
                && (buf.elems(tc).toInt() % 2 == 1)
            ) {
                throw ExceptionHandling.dieInternal(tc, "Malformed UTF-16; odd number of bytes")
            }
            return decode8(buf, mangledEncoding, tc)
        }
        else if (encoding == "utf16" || encoding == "utf32") {
            val n = buf!!.elems(tc).toInt()
            val sb = StringBuilder(n)
            if (buf is VMArrayInstance_u8 || buf is VMArrayInstance_i8) {
                if (encoding == "utf16" && n % 2 == 1) {
                    throw ExceptionHandling.dieInternal(tc, "Malformed UTF-16; odd number of bytes")
                }
                if (encoding == "utf32" && n % 4 > 0) {
                    throw ExceptionHandling.dieInternal(tc, "Malformed UTF-32; number of bytes must be factor of four")
                }
                var i = 0
                while (i < n) {
                    buf.at_pos_native(tc, (i++).toLong())
                    val a = tc.nativeI.toInt()
                    buf.at_pos_native(tc, (i++).toLong())
                    val b = tc.nativeI.toInt()
                    sb.appendCodePoint(a + (b shl 8))
                }
            }
            else if (buf is VMArrayInstance_i16 || buf is VMArrayInstance_u16) {
                for (i in 0 until n) {
                    buf.at_pos_native(tc, i.toLong())
                    sb.appendCodePoint(tc.nativeI.toInt())
                }
            }
            else if (buf is VMArrayInstance_i32 || buf is VMArrayInstance_u32) {
                for (i in 0 until n) {
                    buf.at_pos_native(tc, i.toLong())
                    val a = tc.nativeI.toInt()
                    sb.appendCodePoint(a and 0xFFFF)
                    sb.appendCodePoint(a shr 16)
                }
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Unknown buf type: " + buf.javaClass + "/" + typeName(buf, tc))
            }
            return sb.toString()
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "Unknown encoding '" + encoding + "'")
        }
    }

    @JvmStatic
    fun decoderconfigure(decoder: SixModelObject?, encoding: String?, config: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (decoder is DecoderInstance) {
            val mangledEncoding = javaEncodingName(encoding!!)
            if (mangledEncoding != null)
                decoder.configure(tc, mangledEncoding, config!!)
            else
                throw ExceptionHandling.dieInternal(tc, "Unsupported VM encoding '" + encoding + "'")
            return decoder
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "decoderconfigure requires an instance with the Decoder REPR")
        }
    }

    @JvmStatic
    fun decoderempty(decoder: SixModelObject?, tc: ThreadContext): Long {
        if (decoder is DecoderInstance)
            return decoder.isEmpty(tc)
        else
            throw ExceptionHandling.dieInternal(tc, "decoderempty requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decoderaddbytes(decoder: SixModelObject?, bytes: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (decoder is DecoderInstance) {
            decoder.addBytes(tc, Buffers.unstashBytes(bytes!!, tc))
            return decoder
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "decoderaddbytes requires an instance with the Decoder REPR")
        }
    }

    @JvmStatic
    fun decodertakechars(decoder: SixModelObject?, chars: Long, tc: ThreadContext): String? {
        if (decoder is DecoderInstance)
            return decoder.takeChars(tc, chars, false)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakechars requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodertakecharseof(decoder: SixModelObject?, chars: Long, tc: ThreadContext): String? {
        if (decoder is DecoderInstance)
            return decoder.takeChars(tc, chars, true)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakecharseof requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodertakeavailablechars(decoder: SixModelObject?, tc: ThreadContext): String? {
        if (decoder is DecoderInstance)
            return decoder.takeAvailableChars(tc)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakeavailablechars requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodertakeallchars(decoder: SixModelObject?, tc: ThreadContext): String? {
        if (decoder is DecoderInstance)
            return decoder.takeAllChars(tc)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakeallchars requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodertakeline(decoder: SixModelObject?, chomp: Long, eof: Long,
                        tc: ThreadContext): String? {
        if (decoder is DecoderInstance)
            return decoder.takeLine(tc, chomp != 0L, eof != 0L)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakeline requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodersetlineseps(decoder: SixModelObject?, seps: SixModelObject?,
                           tc: ThreadContext): SixModelObject? {
        if (decoder is DecoderInstance) {
            decoder.setLineSeps(tc, seps!!)
            return decoder
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "decodersetlineseps requires an instance with the Decoder REPR")
        }
    }

    @JvmStatic
    fun decoderbytesavailable(decoder: SixModelObject?, tc: ThreadContext): Long {
        if (decoder is DecoderInstance)
            return decoder.bytesAvailable(tc)
        else
            throw ExceptionHandling.dieInternal(tc, "decoderbytesavailable requires an instance with the Decoder REPR")
    }

    @JvmStatic
    fun decodertakebytes(decoder: SixModelObject?, bufType: SixModelObject?,
                         bytes: Long, tc: ThreadContext): SixModelObject? {
        if (decoder is DecoderInstance)
            return decoder.takeBytes(tc, bufType!!, bytes)
        else
            throw ExceptionHandling.dieInternal(tc, "decodertakebytes requires an instance with the Decoder REPR")
    }

    private const val CCLASS_ANY          = 65535
    private const val CCLASS_UPPERCASE    = 1
    private const val CCLASS_LOWERCASE    = 2
    private const val CCLASS_ALPHABETIC   = 4
    private const val CCLASS_NUMERIC      = 8
    private const val CCLASS_HEXADECIMAL  = 16
    private const val CCLASS_WHITESPACE   = 32
    private const val CCLASS_PRINTING     = 64
    private const val CCLASS_BLANK        = 256
    private const val CCLASS_CONTROL      = 512
    private const val CCLASS_PUNCTUATION  = 1024
    private const val CCLASS_ALPHANUMERIC = 2048
    private const val CCLASS_NEWLINE      = 4096
    private const val CCLASS_WORD         = 8192
    private val PUNCT_TYPES =
        (1 shl Character.CONNECTOR_PUNCTUATION.toInt()) or (1 shl Character.DASH_PUNCTUATION.toInt()) or
        (1 shl Character.END_PUNCTUATION.toInt()) or (1 shl Character.FINAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.INITIAL_QUOTE_PUNCTUATION.toInt()) or (1 shl Character.OTHER_PUNCTUATION.toInt()) or
        (1 shl Character.START_PUNCTUATION.toInt())
    private val NONPRINT_TYPES =
        (1 shl Character.CONTROL.toInt()) or (1 shl Character.SURROGATE.toInt()) or (1 shl Character.UNASSIGNED.toInt()) or
        (1 shl Character.LINE_SEPARATOR.toInt()) or (1 shl Character.PARAGRAPH_SEPARATOR.toInt())


    @JvmStatic
    fun iscclass(cclass: Long, target: String?, offset: Long): Long {
        if (offset < 0 || offset >= target!!.length)
            return 0
        val test = target[offset.toInt()]
        when (cclass.toInt()) {
        CCLASS_ANY ->
            return 1
        CCLASS_NUMERIC ->
            return if (Character.isDigit(test)) 1 else 0
        CCLASS_WHITESPACE -> {
            if (Character.isSpaceChar(test)) return 1
            if (test >= '\t' && test <= '\r') return 1
            if (test == '\u0085') return 1
            return 0
        }
        CCLASS_PRINTING -> {
            if (((1 shl Character.getType(test)) and NONPRINT_TYPES) != 0) return 0
            return if (test < '\t' || test > '\r') 1 else 0
        }
        CCLASS_WORD ->
            return if (test == '_' || Character.isLetterOrDigit(test)) 1 else 0
        CCLASS_NEWLINE ->
            return if ((Character.getType(test) == Character.LINE_SEPARATOR.toInt()) ||
                    (test == '\n' || test == '\u000b' || test == '\u000C' || test == '\r' ||
                     test == '\u0085' || test == '\u2029'))
                    1 else 0
        CCLASS_ALPHABETIC ->
            return if (Character.isAlphabetic(test.code)) 1 else 0
        CCLASS_UPPERCASE ->
            return if (Character.isUpperCase(test)) 1 else 0
        CCLASS_LOWERCASE ->
            return if (Character.isLowerCase(test)) 1 else 0
        CCLASS_HEXADECIMAL ->
            return if (Character.isDigit(test) ||
                    (test >= 'A' && test <= 'F' || test >= 'a' && test <= 'f'))
                    1 else 0
        CCLASS_BLANK ->
            return if ((Character.getType(test) == Character.SPACE_SEPARATOR.toInt()) ||
                    (test == '\t'))
                    1 else 0
        CCLASS_CONTROL ->
            return if (Character.isISOControl(test)) 1 else 0
        CCLASS_PUNCTUATION ->
            return if (((1 shl Character.getType(test)) and PUNCT_TYPES) != 0) 1 else 0
        CCLASS_ALPHANUMERIC ->
            return if (Character.isLetterOrDigit(test)) 1 else 0
        else ->
            return 0
        }
    }

    @JvmStatic
    fun checkcrlf(tgt: String?, pos: Long, eos: Long): Long {
        return if (pos <= eos - 2 && tgt!!.substring(pos.toInt(), pos.toInt() + 2) == "\r\n") pos + 1 else pos
    }

    @JvmStatic
    fun findcclass(cclass: Long, target: String?, offset: Long, count: Long): Long {
        val length = target!!.length.toLong()
        var end = offset + count
        end = if (length < end) length else end

        for (pos in offset until end) {
            if (iscclass(cclass, target, pos) > 0) {
                return pos
            }
        }

        return end
    }

    @JvmStatic
    fun findnotcclass(cclass: Long, target: String?, offset: Long, count: Long): Long {
        val length = target!!.length.toLong()
        var end = offset + count
        end = if (length < end) length else end

        for (pos in offset until end) {
            if (iscclass(cclass, target, pos) == 0L) {
                return pos
            }
        }

        return end
    }
    private val canonNames = HashMap<String, String>()
    private val derivedProps = HashMap<String, IntArray>()
    init {
        canonNames.put("inlatin1supplement", "InLatin-1Supplement")
        canonNames.put("inlatinextendeda", "InLatinExtended-A")
        canonNames.put("inlatinextendedb", "InLatinExtended-B")
        canonNames.put("inarabicextendeda", "InArabicExtended-A")
        canonNames.put("inmiscellaneousmathematicalsymbolsa", "InMiscellaneousMathematicalSymbols-A")
        canonNames.put("insupplementalarrowsa", "InSupplementalArrows-A")
        canonNames.put("insupplementalarrowsb", "InSupplementalArrows-B")
        canonNames.put("inmiscellaneousmathematicalsymbolsb", "InMiscellaneousMathematicalSymbols-B")
        canonNames.put("inlatinextendedc", "InLatinExtended-C")
        canonNames.put("incyrillicextendeda", "InCyrillicExtended-A")
        canonNames.put("incyrillicextendedb", "InCyrillicExtended-B")
        canonNames.put("inlatinextendedd", "InLatinExtended-D")
        canonNames.put("inhanguljamoextendeda", "InHangulJamoExtended-A")
        canonNames.put("inmyanmarextendeda", "InMyanmarExtended-A")
        canonNames.put("inethiopicextendeda", "InEthiopicExtended-A")
        canonNames.put("inhanguljamoextendedb", "InHangulJamoExtended-B")
        canonNames.put("inarabicpresentationformsa", "InArabicPresentationForms-A")
        canonNames.put("inarabicpresentationformsb", "InArabicPresentationForms-B")
        canonNames.put("insupplementaryprivateuseareaa", "InSupplementaryPrivateUseArea-A")
        canonNames.put("insupplementaryprivateuseareab", "InSupplementaryPrivateUseArea-B")
        canonNames.put("ascii", "ASCII")
        canonNames.put("alpha", "IsAlphabetic")
        canonNames.put("alphabetic", "IsAlphabetic")
        canonNames.put("ideographic", "IsIdeographic")
        canonNames.put("letter", "IsLetter")
        canonNames.put("lower", "IsLowercase")
        canonNames.put("lowercase", "IsLowercase")
        canonNames.put("upper", "IsUppercase")
        canonNames.put("uppercase", "IsUppercase")
        canonNames.put("titlecase", "IsTitlecase")
        canonNames.put("punct", "IsPunctuation")
        canonNames.put("punctuation", "IsPunctuation")
        canonNames.put("cntrl", "IsControl")
        canonNames.put("control", "IsControl")
        canonNames.put("white_space", "IsWhite_Space")
        canonNames.put("digit", "IsDigit")
        canonNames.put("hex_digit", "IsHex_Digit")
        canonNames.put("noncharacter_code_point", "IsNoncharacter_Code_Point")
        canonNames.put("assigned", "IsAssigned")
        canonNames.put("uppercaseletter", "Lu")
        canonNames.put("lowercaseletter", "Ll")
        canonNames.put("titlecaseletter", "Lt")
        canonNames.put("casedletter", "LC")
        canonNames.put("modifierletter", "Lm")
        canonNames.put("otherletter", "Lo")
        canonNames.put("letter", "L")
        canonNames.put("nonspacingmark", "Mn")
        canonNames.put("spacingmark", "Mc")
        canonNames.put("enclosingmark", "Me")
        canonNames.put("mark", "M")
        canonNames.put("decimalnumber", "Nd")
        canonNames.put("letternumber", "Nl")
        canonNames.put("othernumber", "No")
        canonNames.put("number", "N")
        canonNames.put("connectorpunctuation", "Pc")
        canonNames.put("dashpunctuation", "Pd")
        canonNames.put("openpunctuation", "Ps")
        canonNames.put("closepunctuation", "Pe")
        canonNames.put("initialpunctuation", "Pi")
        canonNames.put("finalpunctuation", "Pf")
        canonNames.put("otherpunctuation", "Po")
        canonNames.put("punctuation", "P")
        canonNames.put("mathsymbol", "Sm")
        canonNames.put("currencysymbol", "Sc")
        canonNames.put("modifiersymbol", "Sk")
        canonNames.put("othersymbol", "So")
        canonNames.put("symbol", "S")
        canonNames.put("spaceseparator", "Zs")
        canonNames.put("lineseparator", "Zl")
        canonNames.put("paragraphseparator", "Zp")
        canonNames.put("separator", "Z")
        canonNames.put("control", "Cc")
        canonNames.put("format", "Cf")
        canonNames.put("surrogate", "Cs")
        canonNames.put("privateuse", "Co")
        canonNames.put("unassigned", "Cn")
        canonNames.put("other", "C")
        derivedProps.put("space", intArrayOf(32, 32))
        derivedProps.put("WhiteSpace", intArrayOf(9,13,32,32,133,133,160,160,5760,5760,6158,6158,8192,8202,8232,8232,8233,8233,8239,8239,8287,8287,12288,12288))
        derivedProps.put("BidiControl", intArrayOf(8206,8207,8234,8238))
        derivedProps.put("JoinControl", intArrayOf(8204,8205))
        derivedProps.put("Dash", intArrayOf(45,45,1418,1418,1470,1470,5120,5120,6150,6150,8208,8213,8275,8275,8315,8315,8331,8331,8722,8722,11799,11799,11802,11802,11834,11835,12316,12316,12336,12336,12448,12448,65073,65074,65112,65112,65123,65123,65293,65293))
        derivedProps.put("Hyphen", intArrayOf(45,45,173,173,1418,1418,6150,6150,8208,8209,11799,11799,12539,12539,65123,65123,65293,65293,65381,65381))
        derivedProps.put("QuotationMark", intArrayOf(34,34,39,39,171,171,187,187,8216,8216,8217,8217,8218,8218,8219,8220,8221,8221,8222,8222,8223,8223,8249,8249,8250,8250,12300,12300,12301,12301,12302,12302,12303,12303,12317,12317,12318,12319,65089,65089,65090,65090,65091,65091,65092,65092,65282,65282,65287,65287,65378,65378,65379,65379))
        derivedProps.put("TerminalPunctuation", intArrayOf(33,33,44,44,46,46,58,59,63,63,894,894,903,903,1417,1417,1475,1475,1548,1548,1563,1563,1567,1567,1748,1748,1792,1802,1804,1804,2040,2041,2096,2110,2142,2142,2404,2405,3674,3675,3848,3848,3853,3858,4170,4171,4961,4968,5741,5742,5867,5869,6100,6102,6106,6106,6146,6149,6152,6153,6468,6469,6824,6827,7002,7003,7005,7007,7227,7231,7294,7295,8252,8253,8263,8265,11822,11822,12289,12290,42238,42239,42509,42511,42739,42743,43126,43127,43214,43215,43311,43311,43463,43465,43613,43615,43743,43743,43760,43761,44011,44011,65104,65106,65108,65111,65281,65281,65292,65292,65294,65294,65306,65307,65311,65311,65377,65377,65380,65380,66463,66463,66512,66512,67671,67671,67871,67871,68410,68415,69703,69709,69822,69825,69953,69955,70085,70086,74864,74867))
        derivedProps.put("OtherMath", intArrayOf(94,94,976,978,981,981,1008,1009,1012,1013,8214,8214,8242,8244,8256,8256,8289,8292,8317,8317,8318,8318,8333,8333,8334,8334,8400,8412,8417,8417,8421,8422,8427,8431,8450,8450,8455,8455,8458,8467,8469,8469,8473,8477,8484,8484,8488,8488,8489,8489,8492,8493,8495,8497,8499,8500,8501,8504,8508,8511,8517,8521,8597,8601,8604,8607,8609,8610,8612,8613,8615,8615,8617,8621,8624,8625,8630,8631,8636,8653,8656,8657,8659,8659,8661,8667,8669,8669,8676,8677,9140,9141,9143,9143,9168,9168,9186,9186,9632,9633,9646,9654,9660,9664,9670,9671,9674,9675,9679,9683,9698,9698,9700,9700,9703,9708,9733,9734,9792,9792,9794,9794,9824,9827,9837,9838,10181,10181,10182,10182,10214,10214,10215,10215,10216,10216,10217,10217,10218,10218,10219,10219,10220,10220,10221,10221,10222,10222,10223,10223,10627,10627,10628,10628,10629,10629,10630,10630,10631,10631,10632,10632,10633,10633,10634,10634,10635,10635,10636,10636,10637,10637,10638,10638,10639,10639,10640,10640,10641,10641,10642,10642,10643,10643,10644,10644,10645,10645,10646,10646,10647,10647,10648,10648,10712,10712,10713,10713,10714,10714,10715,10715,10748,10748,10749,10749,65121,65121,65123,65123,65128,65128,65340,65340,65342,65342,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120514,120538,120540,120570,120572,120596,120598,120628,120630,120654,120656,120686,120688,120712,120714,120744,120746,120770,120772,120779,120782,120831,126464,126467,126469,126495,126497,126498,126500,126500,126503,126503,126505,126514,126516,126519,126521,126521,126523,126523,126530,126530,126535,126535,126537,126537,126539,126539,126541,126543,126545,126546,126548,126548,126551,126551,126553,126553,126555,126555,126557,126557,126559,126559,126561,126562,126564,126564,126567,126570,126572,126578,126580,126583,126585,126588,126590,126590,126592,126601,126603,126619,126625,126627,126629,126633,126635,126651))
        derivedProps.put("HexDigit", intArrayOf(48,57,65,70,97,102,65296,65305,65313,65318,65345,65350))
        derivedProps.put("ASCIIHexDigit", intArrayOf(48,57,65,70,97,102))
        derivedProps.put("OtherAlphabetic", intArrayOf(837,837,1456,1469,1471,1471,1473,1474,1476,1477,1479,1479,1552,1562,1611,1623,1625,1631,1648,1648,1750,1756,1761,1764,1767,1768,1773,1773,1809,1809,1840,1855,1958,1968,2070,2071,2075,2083,2085,2087,2089,2092,2276,2281,2288,2302,2304,2306,2307,2307,2362,2362,2363,2363,2366,2368,2369,2376,2377,2380,2382,2383,2389,2391,2402,2403,2433,2433,2434,2435,2494,2496,2497,2500,2503,2504,2507,2508,2519,2519,2530,2531,2561,2562,2563,2563,2622,2624,2625,2626,2631,2632,2635,2636,2641,2641,2672,2673,2677,2677,2689,2690,2691,2691,2750,2752,2753,2757,2759,2760,2761,2761,2763,2764,2786,2787,2817,2817,2818,2819,2878,2878,2879,2879,2880,2880,2881,2884,2887,2888,2891,2892,2902,2902,2903,2903,2914,2915,2946,2946,3006,3007,3008,3008,3009,3010,3014,3016,3018,3020,3031,3031,3073,3075,3134,3136,3137,3140,3142,3144,3146,3148,3157,3158,3170,3171,3202,3203,3262,3262,3263,3263,3264,3268,3270,3270,3271,3272,3274,3275,3276,3276,3285,3286,3298,3299,3330,3331,3390,3392,3393,3396,3398,3400,3402,3404,3415,3415,3426,3427,3458,3459,3535,3537,3538,3540,3542,3542,3544,3551,3570,3571,3633,3633,3636,3642,3661,3661,3761,3761,3764,3769,3771,3772,3789,3789,3953,3966,3967,3967,3968,3969,3981,3991,3993,4028,4139,4140,4141,4144,4145,4145,4146,4150,4152,4152,4155,4156,4157,4158,4182,4183,4184,4185,4190,4192,4194,4194,4199,4200,4209,4212,4226,4226,4227,4228,4229,4230,4252,4252,4253,4253,4959,4959,5906,5907,5938,5939,5970,5971,6002,6003,6070,6070,6071,6077,6078,6085,6086,6086,6087,6088,6313,6313,6432,6434,6435,6438,6439,6440,6441,6443,6448,6449,6450,6450,6451,6456,6576,6592,6600,6601,6679,6680,6681,6683,6741,6741,6742,6742,6743,6743,6744,6750,6753,6753,6754,6754,6755,6756,6757,6764,6765,6770,6771,6772,6912,6915,6916,6916,6965,6965,6966,6970,6971,6971,6972,6972,6973,6977,6978,6978,6979,6979,7040,7041,7042,7042,7073,7073,7074,7077,7078,7079,7080,7081,7084,7085,7143,7143,7144,7145,7146,7148,7149,7149,7150,7150,7151,7153,7204,7211,7212,7219,7220,7221,7410,7411,9398,9449,11744,11775,42612,42619,42655,42655,43043,43044,43045,43046,43047,43047,43136,43137,43188,43203,43302,43306,43335,43345,43346,43346,43392,43394,43395,43395,43444,43445,43446,43449,43450,43451,43452,43452,43453,43455,43561,43566,43567,43568,43569,43570,43571,43572,43573,43574,43587,43587,43596,43596,43597,43597,43696,43696,43698,43700,43703,43704,43710,43710,43755,43755,43756,43757,43758,43759,43765,43765,44003,44004,44005,44005,44006,44007,44008,44008,44009,44010,64286,64286,68097,68099,68101,68102,68108,68111,69632,69632,69633,69633,69634,69634,69688,69701,69762,69762,69808,69810,69811,69814,69815,69816,69888,69890,69927,69931,69932,69932,69933,69938,70016,70017,70018,70018,70067,70069,70070,70078,70079,70079,71339,71339,71340,71340,71341,71341,71342,71343,71344,71349,94033,94078))
        derivedProps.put("Ideographic", intArrayOf(12294,12294,12295,12295,12321,12329,12344,12346,13312,19893,19968,40908,63744,64109,64112,64217,131072,173782,173824,177972,177984,178205,194560,195101))
        derivedProps.put("Diacritic", intArrayOf(94,94,96,96,168,168,175,175,180,180,183,183,184,184,688,705,706,709,710,721,722,735,736,740,741,747,748,748,749,749,750,750,751,767,768,846,848,855,861,866,884,884,885,885,890,890,900,901,1155,1159,1369,1369,1425,1441,1443,1469,1471,1471,1473,1474,1476,1476,1611,1618,1623,1624,1759,1760,1765,1766,1770,1772,1840,1866,1958,1968,2027,2035,2036,2037,2072,2073,2276,2302,2364,2364,2381,2381,2385,2388,2417,2417,2492,2492,2509,2509,2620,2620,2637,2637,2748,2748,2765,2765,2876,2876,2893,2893,3021,3021,3149,3149,3260,3260,3277,3277,3405,3405,3530,3530,3655,3660,3662,3662,3784,3788,3864,3865,3893,3893,3895,3895,3897,3897,3902,3903,3970,3972,3974,3975,4038,4038,4151,4151,4153,4154,4231,4236,4237,4237,4239,4239,4250,4251,6089,6099,6109,6109,6457,6459,6773,6780,6783,6783,6964,6964,6980,6980,7019,7027,7082,7082,7083,7083,7222,7223,7288,7293,7376,7378,7379,7379,7380,7392,7393,7393,7394,7400,7405,7405,7412,7412,7468,7530,7620,7631,7677,7679,8125,8125,8127,8129,8141,8143,8157,8159,8173,8175,8189,8190,11503,11505,11823,11823,12330,12333,12334,12335,12441,12442,12443,12444,12540,12540,42607,42607,42620,42621,42623,42623,42736,42737,42775,42783,42784,42785,42888,42888,43000,43001,43204,43204,43232,43249,43307,43309,43310,43310,43347,43347,43443,43443,43456,43456,43643,43643,43711,43711,43712,43712,43713,43713,43714,43714,43766,43766,44012,44012,44013,44013,64286,64286,65056,65062,65342,65342,65344,65344,65392,65392,65438,65439,65507,65507,69817,69818,69939,69940,70080,70080,71350,71350,71351,71351,94095,94098,94099,94111,119143,119145,119149,119154,119163,119170,119173,119179,119210,119213))
        derivedProps.put("Extender", intArrayOf(183,183,720,721,1600,1600,2042,2042,3654,3654,3782,3782,6154,6154,6211,6211,6823,6823,7222,7222,7291,7291,12293,12293,12337,12341,12445,12446,12540,12542,40981,40981,42508,42508,43471,43471,43632,43632,43741,43741,43763,43764,65392,65392))
        derivedProps.put("OtherLowercase", intArrayOf(170,170,186,186,688,696,704,705,736,740,837,837,890,890,7468,7530,7544,7544,7579,7615,8305,8305,8319,8319,8336,8348,8560,8575,9424,9449,11388,11389,42864,42864,43000,43001))
        derivedProps.put("OtherUppercase", intArrayOf(8544,8559,9398,9423))
        derivedProps.put("NoncharacterCodePoint", intArrayOf(64976,65007,65534,65535,131070,131071,196606,196607,262142,262143,327678,327679,393214,393215,458750,458751,524286,524287,589822,589823,655358,655359,720894,720895,786430,786431,851966,851967,917502,917503,983038,983039,1048574,1048575,1114110,1114111))
        derivedProps.put("OtherGraphemeExtend", intArrayOf(2494,2494,2519,2519,2878,2878,2903,2903,3006,3006,3031,3031,3266,3266,3285,3286,3390,3390,3415,3415,3535,3535,3551,3551,8204,8205,12334,12335,65438,65439,119141,119141,119150,119154))
        derivedProps.put("IDSBinaryOperator", intArrayOf(12272,12273,12276,12283))
        derivedProps.put("IDSTrinaryOperator", intArrayOf(12274,12275))
        derivedProps.put("Radical", intArrayOf(11904,11929,11931,12019,12032,12245))
        derivedProps.put("UnifiedIdeograph", intArrayOf(13312,19893,19968,40908,64014,64015,64017,64017,64019,64020,64031,64031,64033,64033,64035,64036,64039,64041,131072,173782,173824,177972,177984,178205))
        derivedProps.put("OtherDefaultIgnorableCodePoint", intArrayOf(847,847,4447,4448,6068,6069,8293,8297,12644,12644,65440,65440,65520,65528,917504,917504,917506,917535,917632,917759,918000,921599))
        derivedProps.put("Deprecated", intArrayOf(329,329,1651,1651,3959,3959,3961,3961,6051,6052,8298,8303,9001,9001,9002,9002,917505,917505,917536,917631))
        derivedProps.put("SoftDotted", intArrayOf(105,106,303,303,585,585,616,616,669,669,690,690,1011,1011,1110,1110,1112,1112,7522,7522,7574,7574,7588,7588,7592,7592,7725,7725,7883,7883,8305,8305,8520,8521,11388,11388,119842,119843,119894,119895,119946,119947,119998,119999,120050,120051,120102,120103,120154,120155,120206,120207,120258,120259,120310,120311,120362,120363,120414,120415,120466,120467))
        derivedProps.put("LogicalOrderException", intArrayOf(3648,3652,3776,3780,43701,43702,43705,43705,43707,43708))
        derivedProps.put("OtherIDStart", intArrayOf(8472,8472,8494,8494,12443,12444))
        derivedProps.put("OtherIDContinue", intArrayOf(183,183,903,903,4969,4977,6618,6618))
        derivedProps.put("STerm", intArrayOf(33,33,46,46,63,63,1372,1372,1374,1374,1417,1417,1567,1567,1748,1748,1792,1794,2041,2041,2404,2405,4170,4171,4962,4962,4967,4968,5742,5742,5941,5942,6147,6147,6153,6153,6468,6469,6824,6827,7002,7003,7006,7007,7227,7228,7294,7295,8252,8253,8263,8265,11822,11822,12290,12290,42239,42239,42510,42511,42739,42739,42743,42743,43126,43127,43214,43215,43311,43311,43464,43465,43613,43615,43760,43761,44011,44011,65106,65106,65110,65111,65281,65281,65294,65294,65311,65311,65377,65377,68182,68183,69703,69704,69822,69825,69953,69955,70085,70086))
        derivedProps.put("VariationSelector", intArrayOf(6155,6157,65024,65039,917760,917999))
        derivedProps.put("PatternWhiteSpace", intArrayOf(9,13,32,32,133,133,8206,8207,8232,8232,8233,8233))
        derivedProps.put("PatternSyntax", intArrayOf(33,35,36,36,37,39,40,40,41,41,42,42,43,43,44,44,45,45,46,47,58,59,60,62,63,64,91,91,92,92,93,93,94,94,96,96,123,123,124,124,125,125,126,126,161,161,162,165,166,166,167,167,169,169,171,171,172,172,174,174,176,176,177,177,182,182,187,187,191,191,215,215,247,247,8208,8213,8214,8215,8216,8216,8217,8217,8218,8218,8219,8220,8221,8221,8222,8222,8223,8223,8224,8231,8240,8248,8249,8249,8250,8250,8251,8254,8257,8259,8260,8260,8261,8261,8262,8262,8263,8273,8274,8274,8275,8275,8277,8286,8592,8596,8597,8601,8602,8603,8604,8607,8608,8608,8609,8610,8611,8611,8612,8613,8614,8614,8615,8621,8622,8622,8623,8653,8654,8655,8656,8657,8658,8658,8659,8659,8660,8660,8661,8691,8692,8959,8960,8967,8968,8971,8972,8991,8992,8993,8994,9000,9001,9001,9002,9002,9003,9083,9084,9084,9085,9114,9115,9139,9140,9179,9180,9185,9186,9203,9204,9215,9216,9254,9255,9279,9280,9290,9291,9311,9472,9654,9655,9655,9656,9664,9665,9665,9666,9719,9720,9727,9728,9838,9839,9839,9840,9983,9984,9984,9985,10087,10088,10088,10089,10089,10090,10090,10091,10091,10092,10092,10093,10093,10094,10094,10095,10095,10096,10096,10097,10097,10098,10098,10099,10099,10100,10100,10101,10101,10132,10175,10176,10180,10181,10181,10182,10182,10183,10213,10214,10214,10215,10215,10216,10216,10217,10217,10218,10218,10219,10219,10220,10220,10221,10221,10222,10222,10223,10223,10224,10239,10240,10495,10496,10626,10627,10627,10628,10628,10629,10629,10630,10630,10631,10631,10632,10632,10633,10633,10634,10634,10635,10635,10636,10636,10637,10637,10638,10638,10639,10639,10640,10640,10641,10641,10642,10642,10643,10643,10644,10644,10645,10645,10646,10646,10647,10647,10648,10648,10649,10711,10712,10712,10713,10713,10714,10714,10715,10715,10716,10747,10748,10748,10749,10749,10750,11007,11008,11055,11056,11076,11077,11078,11079,11084,11085,11087,11088,11097,11098,11263,11776,11777,11778,11778,11779,11779,11780,11780,11781,11781,11782,11784,11785,11785,11786,11786,11787,11787,11788,11788,11789,11789,11790,11798,11799,11799,11800,11801,11802,11802,11803,11803,11804,11804,11805,11805,11806,11807,11808,11808,11809,11809,11810,11810,11811,11811,11812,11812,11813,11813,11814,11814,11815,11815,11816,11816,11817,11817,11818,11822,11823,11823,11824,11833,11834,11835,11836,11903,12289,12291,12296,12296,12297,12297,12298,12298,12299,12299,12300,12300,12301,12301,12302,12302,12303,12303,12304,12304,12305,12305,12306,12307,12308,12308,12309,12309,12310,12310,12311,12311,12312,12312,12313,12313,12314,12314,12315,12315,12316,12316,12317,12317,12318,12319,12320,12320,12336,12336,64830,64830,64831,64831,65093,65094))
        derivedProps.put("Math", intArrayOf(43,43,60,62,94,94,124,124,126,126,172,172,177,177,215,215,247,247,976,978,981,981,1008,1009,1012,1013,1014,1014,1542,1544,8214,8214,8242,8244,8256,8256,8260,8260,8274,8274,8289,8292,8314,8316,8317,8317,8318,8318,8330,8332,8333,8333,8334,8334,8400,8412,8417,8417,8421,8422,8427,8431,8450,8450,8455,8455,8458,8467,8469,8469,8472,8472,8473,8477,8484,8484,8488,8488,8489,8489,8492,8493,8495,8497,8499,8500,8501,8504,8508,8511,8512,8516,8517,8521,8523,8523,8592,8596,8597,8601,8602,8603,8604,8607,8608,8608,8609,8610,8611,8611,8612,8613,8614,8614,8615,8615,8617,8621,8622,8622,8624,8625,8630,8631,8636,8653,8654,8655,8656,8657,8658,8658,8659,8659,8660,8660,8661,8667,8669,8669,8676,8677,8692,8959,8968,8971,8992,8993,9084,9084,9115,9139,9140,9141,9143,9143,9168,9168,9180,9185,9186,9186,9632,9633,9646,9654,9655,9655,9660,9664,9665,9665,9670,9671,9674,9675,9679,9683,9698,9698,9700,9700,9703,9708,9720,9727,9733,9734,9792,9792,9794,9794,9824,9827,9837,9838,9839,9839,10176,10180,10181,10181,10182,10182,10183,10213,10214,10214,10215,10215,10216,10216,10217,10217,10218,10218,10219,10219,10220,10220,10221,10221,10222,10222,10223,10223,10224,10239,10496,10626,10627,10627,10628,10628,10629,10629,10630,10630,10631,10631,10632,10632,10633,10633,10634,10634,10635,10635,10636,10636,10637,10637,10638,10638,10639,10639,10640,10640,10641,10641,10642,10642,10643,10643,10644,10644,10645,10645,10646,10646,10647,10647,10648,10648,10649,10711,10712,10712,10713,10713,10714,10714,10715,10715,10716,10747,10748,10748,10749,10749,10750,11007,11056,11076,11079,11084,64297,64297,65121,65121,65122,65122,65123,65123,65124,65126,65128,65128,65291,65291,65308,65310,65340,65340,65342,65342,65372,65372,65374,65374,65506,65506,65513,65516,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120513,120513,120514,120538,120539,120539,120540,120570,120571,120571,120572,120596,120597,120597,120598,120628,120629,120629,120630,120654,120655,120655,120656,120686,120687,120687,120688,120712,120713,120713,120714,120744,120745,120745,120746,120770,120771,120771,120772,120779,120782,120831,126464,126467,126469,126495,126497,126498,126500,126500,126503,126503,126505,126514,126516,126519,126521,126521,126523,126523,126530,126530,126535,126535,126537,126537,126539,126539,126541,126543,126545,126546,126548,126548,126551,126551,126553,126553,126555,126555,126557,126557,126559,126559,126561,126562,126564,126564,126567,126570,126572,126578,126580,126583,126585,126588,126590,126590,126592,126601,126603,126619,126625,126627,126629,126633,126635,126651,126704,126705))
        derivedProps.put("ID_Start", intArrayOf(65,90,97,122,170,170,181,181,186,186,192,214,216,246,248,442,443,443,444,447,448,451,452,659,660,660,661,687,688,705,710,721,736,740,748,748,750,750,880,883,884,884,886,887,890,890,891,893,902,902,904,906,908,908,910,929,931,1013,1015,1153,1162,1319,1329,1366,1369,1369,1377,1415,1488,1514,1520,1522,1568,1599,1600,1600,1601,1610,1646,1647,1649,1747,1749,1749,1765,1766,1774,1775,1786,1788,1791,1791,1808,1808,1810,1839,1869,1957,1969,1969,1994,2026,2036,2037,2042,2042,2048,2069,2074,2074,2084,2084,2088,2088,2112,2136,2208,2208,2210,2220,2308,2361,2365,2365,2384,2384,2392,2401,2417,2417,2418,2423,2425,2431,2437,2444,2447,2448,2451,2472,2474,2480,2482,2482,2486,2489,2493,2493,2510,2510,2524,2525,2527,2529,2544,2545,2565,2570,2575,2576,2579,2600,2602,2608,2610,2611,2613,2614,2616,2617,2649,2652,2654,2654,2674,2676,2693,2701,2703,2705,2707,2728,2730,2736,2738,2739,2741,2745,2749,2749,2768,2768,2784,2785,2821,2828,2831,2832,2835,2856,2858,2864,2866,2867,2869,2873,2877,2877,2908,2909,2911,2913,2929,2929,2947,2947,2949,2954,2958,2960,2962,2965,2969,2970,2972,2972,2974,2975,2979,2980,2984,2986,2990,3001,3024,3024,3077,3084,3086,3088,3090,3112,3114,3123,3125,3129,3133,3133,3160,3161,3168,3169,3205,3212,3214,3216,3218,3240,3242,3251,3253,3257,3261,3261,3294,3294,3296,3297,3313,3314,3333,3340,3342,3344,3346,3386,3389,3389,3406,3406,3424,3425,3450,3455,3461,3478,3482,3505,3507,3515,3517,3517,3520,3526,3585,3632,3634,3635,3648,3653,3654,3654,3713,3714,3716,3716,3719,3720,3722,3722,3725,3725,3732,3735,3737,3743,3745,3747,3749,3749,3751,3751,3754,3755,3757,3760,3762,3763,3773,3773,3776,3780,3782,3782,3804,3807,3840,3840,3904,3911,3913,3948,3976,3980,4096,4138,4159,4159,4176,4181,4186,4189,4193,4193,4197,4198,4206,4208,4213,4225,4238,4238,4256,4293,4295,4295,4301,4301,4304,4346,4348,4348,4349,4680,4682,4685,4688,4694,4696,4696,4698,4701,4704,4744,4746,4749,4752,4784,4786,4789,4792,4798,4800,4800,4802,4805,4808,4822,4824,4880,4882,4885,4888,4954,4992,5007,5024,5108,5121,5740,5743,5759,5761,5786,5792,5866,5870,5872,5888,5900,5902,5905,5920,5937,5952,5969,5984,5996,5998,6000,6016,6067,6103,6103,6108,6108,6176,6210,6211,6211,6212,6263,6272,6312,6314,6314,6320,6389,6400,6428,6480,6509,6512,6516,6528,6571,6593,6599,6656,6678,6688,6740,6823,6823,6917,6963,6981,6987,7043,7072,7086,7087,7098,7141,7168,7203,7245,7247,7258,7287,7288,7293,7401,7404,7406,7409,7413,7414,7424,7467,7468,7530,7531,7543,7544,7544,7545,7578,7579,7615,7680,7957,7960,7965,7968,8005,8008,8013,8016,8023,8025,8025,8027,8027,8029,8029,8031,8061,8064,8116,8118,8124,8126,8126,8130,8132,8134,8140,8144,8147,8150,8155,8160,8172,8178,8180,8182,8188,8305,8305,8319,8319,8336,8348,8450,8450,8455,8455,8458,8467,8469,8469,8472,8472,8473,8477,8484,8484,8486,8486,8488,8488,8490,8493,8494,8494,8495,8500,8501,8504,8505,8505,8508,8511,8517,8521,8526,8526,8544,8578,8579,8580,8581,8584,11264,11310,11312,11358,11360,11387,11388,11389,11390,11492,11499,11502,11506,11507,11520,11557,11559,11559,11565,11565,11568,11623,11631,11631,11648,11670,11680,11686,11688,11694,11696,11702,11704,11710,11712,11718,11720,11726,11728,11734,11736,11742,12293,12293,12294,12294,12295,12295,12321,12329,12337,12341,12344,12346,12347,12347,12348,12348,12353,12438,12443,12444,12445,12446,12447,12447,12449,12538,12540,12542,12543,12543,12549,12589,12593,12686,12704,12730,12784,12799,13312,19893,19968,40908,40960,40980,40981,40981,40982,42124,42192,42231,42232,42237,42240,42507,42508,42508,42512,42527,42538,42539,42560,42605,42606,42606,42623,42623,42624,42647,42656,42725,42726,42735,42775,42783,42786,42863,42864,42864,42865,42887,42888,42888,42891,42894,42896,42899,42912,42922,43000,43001,43002,43002,43003,43009,43011,43013,43015,43018,43020,43042,43072,43123,43138,43187,43250,43255,43259,43259,43274,43301,43312,43334,43360,43388,43396,43442,43471,43471,43520,43560,43584,43586,43588,43595,43616,43631,43632,43632,43633,43638,43642,43642,43648,43695,43697,43697,43701,43702,43705,43709,43712,43712,43714,43714,43739,43740,43741,43741,43744,43754,43762,43762,43763,43764,43777,43782,43785,43790,43793,43798,43808,43814,43816,43822,43968,44002,44032,55203,55216,55238,55243,55291,63744,64109,64112,64217,64256,64262,64275,64279,64285,64285,64287,64296,64298,64310,64312,64316,64318,64318,64320,64321,64323,64324,64326,64433,64467,64829,64848,64911,64914,64967,65008,65019,65136,65140,65142,65276,65313,65338,65345,65370,65382,65391,65392,65392,65393,65437,65438,65439,65440,65470,65474,65479,65482,65487,65490,65495,65498,65500,65536,65547,65549,65574,65576,65594,65596,65597,65599,65613,65616,65629,65664,65786,65856,65908,66176,66204,66208,66256,66304,66334,66352,66368,66369,66369,66370,66377,66378,66378,66432,66461,66464,66499,66504,66511,66513,66517,66560,66639,66640,66717,67584,67589,67592,67592,67594,67637,67639,67640,67644,67644,67647,67669,67840,67861,67872,67897,67968,68023,68030,68031,68096,68096,68112,68115,68117,68119,68121,68147,68192,68220,68352,68405,68416,68437,68448,68466,68608,68680,69635,69687,69763,69807,69840,69864,69891,69926,70019,70066,70081,70084,71296,71338,73728,74606,74752,74850,77824,78894,92160,92728,93952,94020,94032,94032,94099,94111,110592,110593,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120514,120538,120540,120570,120572,120596,120598,120628,120630,120654,120656,120686,120688,120712,120714,120744,120746,120770,120772,120779,126464,126467,126469,126495,126497,126498,126500,126500,126503,126503,126505,126514,126516,126519,126521,126521,126523,126523,126530,126530,126535,126535,126537,126537,126539,126539,126541,126543,126545,126546,126548,126548,126551,126551,126553,126553,126555,126555,126557,126557,126559,126559,126561,126562,126564,126564,126567,126570,126572,126578,126580,126583,126585,126588,126590,126590,126592,126601,126603,126619,126625,126627,126629,126633,126635,126651,131072,173782,173824,177972,177984,178205,194560,195101))
        derivedProps.put("ID_Continue", intArrayOf(48,57,65,90,95,95,97,122,170,170,181,181,183,183,186,186,192,214,216,246,248,442,443,443,444,447,448,451,452,659,660,660,661,687,688,705,710,721,736,740,748,748,750,750,768,879,880,883,884,884,886,887,890,890,891,893,902,902,903,903,904,906,908,908,910,929,931,1013,1015,1153,1155,1159,1162,1319,1329,1366,1369,1369,1377,1415,1425,1469,1471,1471,1473,1474,1476,1477,1479,1479,1488,1514,1520,1522,1552,1562,1568,1599,1600,1600,1601,1610,1611,1631,1632,1641,1646,1647,1648,1648,1649,1747,1749,1749,1750,1756,1759,1764,1765,1766,1767,1768,1770,1773,1774,1775,1776,1785,1786,1788,1791,1791,1808,1808,1809,1809,1810,1839,1840,1866,1869,1957,1958,1968,1969,1969,1984,1993,1994,2026,2027,2035,2036,2037,2042,2042,2048,2069,2070,2073,2074,2074,2075,2083,2084,2084,2085,2087,2088,2088,2089,2093,2112,2136,2137,2139,2208,2208,2210,2220,2276,2302,2304,2306,2307,2307,2308,2361,2362,2362,2363,2363,2364,2364,2365,2365,2366,2368,2369,2376,2377,2380,2381,2381,2382,2383,2384,2384,2385,2391,2392,2401,2402,2403,2406,2415,2417,2417,2418,2423,2425,2431,2433,2433,2434,2435,2437,2444,2447,2448,2451,2472,2474,2480,2482,2482,2486,2489,2492,2492,2493,2493,2494,2496,2497,2500,2503,2504,2507,2508,2509,2509,2510,2510,2519,2519,2524,2525,2527,2529,2530,2531,2534,2543,2544,2545,2561,2562,2563,2563,2565,2570,2575,2576,2579,2600,2602,2608,2610,2611,2613,2614,2616,2617,2620,2620,2622,2624,2625,2626,2631,2632,2635,2637,2641,2641,2649,2652,2654,2654,2662,2671,2672,2673,2674,2676,2677,2677,2689,2690,2691,2691,2693,2701,2703,2705,2707,2728,2730,2736,2738,2739,2741,2745,2748,2748,2749,2749,2750,2752,2753,2757,2759,2760,2761,2761,2763,2764,2765,2765,2768,2768,2784,2785,2786,2787,2790,2799,2817,2817,2818,2819,2821,2828,2831,2832,2835,2856,2858,2864,2866,2867,2869,2873,2876,2876,2877,2877,2878,2878,2879,2879,2880,2880,2881,2884,2887,2888,2891,2892,2893,2893,2902,2902,2903,2903,2908,2909,2911,2913,2914,2915,2918,2927,2929,2929,2946,2946,2947,2947,2949,2954,2958,2960,2962,2965,2969,2970,2972,2972,2974,2975,2979,2980,2984,2986,2990,3001,3006,3007,3008,3008,3009,3010,3014,3016,3018,3020,3021,3021,3024,3024,3031,3031,3046,3055,3073,3075,3077,3084,3086,3088,3090,3112,3114,3123,3125,3129,3133,3133,3134,3136,3137,3140,3142,3144,3146,3149,3157,3158,3160,3161,3168,3169,3170,3171,3174,3183,3202,3203,3205,3212,3214,3216,3218,3240,3242,3251,3253,3257,3260,3260,3261,3261,3262,3262,3263,3263,3264,3268,3270,3270,3271,3272,3274,3275,3276,3277,3285,3286,3294,3294,3296,3297,3298,3299,3302,3311,3313,3314,3330,3331,3333,3340,3342,3344,3346,3386,3389,3389,3390,3392,3393,3396,3398,3400,3402,3404,3405,3405,3406,3406,3415,3415,3424,3425,3426,3427,3430,3439,3450,3455,3458,3459,3461,3478,3482,3505,3507,3515,3517,3517,3520,3526,3530,3530,3535,3537,3538,3540,3542,3542,3544,3551,3570,3571,3585,3632,3633,3633,3634,3635,3636,3642,3648,3653,3654,3654,3655,3662,3664,3673,3713,3714,3716,3716,3719,3720,3722,3722,3725,3725,3732,3735,3737,3743,3745,3747,3749,3749,3751,3751,3754,3755,3757,3760,3761,3761,3762,3763,3764,3769,3771,3772,3773,3773,3776,3780,3782,3782,3784,3789,3792,3801,3804,3807,3840,3840,3864,3865,3872,3881,3893,3893,3895,3895,3897,3897,3902,3903,3904,3911,3913,3948,3953,3966,3967,3967,3968,3972,3974,3975,3976,3980,3981,3991,3993,4028,4038,4038,4096,4138,4139,4140,4141,4144,4145,4145,4146,4151,4152,4152,4153,4154,4155,4156,4157,4158,4159,4159,4160,4169,4176,4181,4182,4183,4184,4185,4186,4189,4190,4192,4193,4193,4194,4196,4197,4198,4199,4205,4206,4208,4209,4212,4213,4225,4226,4226,4227,4228,4229,4230,4231,4236,4237,4237,4238,4238,4239,4239,4240,4249,4250,4252,4253,4253,4256,4293,4295,4295,4301,4301,4304,4346,4348,4348,4349,4680,4682,4685,4688,4694,4696,4696,4698,4701,4704,4744,4746,4749,4752,4784,4786,4789,4792,4798,4800,4800,4802,4805,4808,4822,4824,4880,4882,4885,4888,4954,4957,4959,4969,4977,4992,5007,5024,5108,5121,5740,5743,5759,5761,5786,5792,5866,5870,5872,5888,5900,5902,5905,5906,5908,5920,5937,5938,5940,5952,5969,5970,5971,5984,5996,5998,6000,6002,6003,6016,6067,6068,6069,6070,6070,6071,6077,6078,6085,6086,6086,6087,6088,6089,6099,6103,6103,6108,6108,6109,6109,6112,6121,6155,6157,6160,6169,6176,6210,6211,6211,6212,6263,6272,6312,6313,6313,6314,6314,6320,6389,6400,6428,6432,6434,6435,6438,6439,6440,6441,6443,6448,6449,6450,6450,6451,6456,6457,6459,6470,6479,6480,6509,6512,6516,6528,6571,6576,6592,6593,6599,6600,6601,6608,6617,6618,6618,6656,6678,6679,6680,6681,6683,6688,6740,6741,6741,6742,6742,6743,6743,6744,6750,6752,6752,6753,6753,6754,6754,6755,6756,6757,6764,6765,6770,6771,6780,6783,6783,6784,6793,6800,6809,6823,6823,6912,6915,6916,6916,6917,6963,6964,6964,6965,6965,6966,6970,6971,6971,6972,6972,6973,6977,6978,6978,6979,6980,6981,6987,6992,7001,7019,7027,7040,7041,7042,7042,7043,7072,7073,7073,7074,7077,7078,7079,7080,7081,7082,7082,7083,7083,7084,7085,7086,7087,7088,7097,7098,7141,7142,7142,7143,7143,7144,7145,7146,7148,7149,7149,7150,7150,7151,7153,7154,7155,7168,7203,7204,7211,7212,7219,7220,7221,7222,7223,7232,7241,7245,7247,7248,7257,7258,7287,7288,7293,7376,7378,7380,7392,7393,7393,7394,7400,7401,7404,7405,7405,7406,7409,7410,7411,7412,7412,7413,7414,7424,7467,7468,7530,7531,7543,7544,7544,7545,7578,7579,7615,7616,7654,7676,7679,7680,7957,7960,7965,7968,8005,8008,8013,8016,8023,8025,8025,8027,8027,8029,8029,8031,8061,8064,8116,8118,8124,8126,8126,8130,8132,8134,8140,8144,8147,8150,8155,8160,8172,8178,8180,8182,8188,8255,8256,8276,8276,8305,8305,8319,8319,8336,8348,8400,8412,8417,8417,8421,8432,8450,8450,8455,8455,8458,8467,8469,8469,8472,8472,8473,8477,8484,8484,8486,8486,8488,8488,8490,8493,8494,8494,8495,8500,8501,8504,8505,8505,8508,8511,8517,8521,8526,8526,8544,8578,8579,8580,8581,8584,11264,11310,11312,11358,11360,11387,11388,11389,11390,11492,11499,11502,11503,11505,11506,11507,11520,11557,11559,11559,11565,11565,11568,11623,11631,11631,11647,11647,11648,11670,11680,11686,11688,11694,11696,11702,11704,11710,11712,11718,11720,11726,11728,11734,11736,11742,11744,11775,12293,12293,12294,12294,12295,12295,12321,12329,12330,12333,12334,12335,12337,12341,12344,12346,12347,12347,12348,12348,12353,12438,12441,12442,12443,12444,12445,12446,12447,12447,12449,12538,12540,12542,12543,12543,12549,12589,12593,12686,12704,12730,12784,12799,13312,19893,19968,40908,40960,40980,40981,40981,40982,42124,42192,42231,42232,42237,42240,42507,42508,42508,42512,42527,42528,42537,42538,42539,42560,42605,42606,42606,42607,42607,42612,42621,42623,42623,42624,42647,42655,42655,42656,42725,42726,42735,42736,42737,42775,42783,42786,42863,42864,42864,42865,42887,42888,42888,42891,42894,42896,42899,42912,42922,43000,43001,43002,43002,43003,43009,43010,43010,43011,43013,43014,43014,43015,43018,43019,43019,43020,43042,43043,43044,43045,43046,43047,43047,43072,43123,43136,43137,43138,43187,43188,43203,43204,43204,43216,43225,43232,43249,43250,43255,43259,43259,43264,43273,43274,43301,43302,43309,43312,43334,43335,43345,43346,43347,43360,43388,43392,43394,43395,43395,43396,43442,43443,43443,43444,43445,43446,43449,43450,43451,43452,43452,43453,43456,43471,43471,43472,43481,43520,43560,43561,43566,43567,43568,43569,43570,43571,43572,43573,43574,43584,43586,43587,43587,43588,43595,43596,43596,43597,43597,43600,43609,43616,43631,43632,43632,43633,43638,43642,43642,43643,43643,43648,43695,43696,43696,43697,43697,43698,43700,43701,43702,43703,43704,43705,43709,43710,43711,43712,43712,43713,43713,43714,43714,43739,43740,43741,43741,43744,43754,43755,43755,43756,43757,43758,43759,43762,43762,43763,43764,43765,43765,43766,43766,43777,43782,43785,43790,43793,43798,43808,43814,43816,43822,43968,44002,44003,44004,44005,44005,44006,44007,44008,44008,44009,44010,44012,44012,44013,44013,44016,44025,44032,55203,55216,55238,55243,55291,63744,64109,64112,64217,64256,64262,64275,64279,64285,64285,64286,64286,64287,64296,64298,64310,64312,64316,64318,64318,64320,64321,64323,64324,64326,64433,64467,64829,64848,64911,64914,64967,65008,65019,65024,65039,65056,65062,65075,65076,65101,65103,65136,65140,65142,65276,65296,65305,65313,65338,65343,65343,65345,65370,65382,65391,65392,65392,65393,65437,65438,65439,65440,65470,65474,65479,65482,65487,65490,65495,65498,65500,65536,65547,65549,65574,65576,65594,65596,65597,65599,65613,65616,65629,65664,65786,65856,65908,66045,66045,66176,66204,66208,66256,66304,66334,66352,66368,66369,66369,66370,66377,66378,66378,66432,66461,66464,66499,66504,66511,66513,66517,66560,66639,66640,66717,66720,66729,67584,67589,67592,67592,67594,67637,67639,67640,67644,67644,67647,67669,67840,67861,67872,67897,67968,68023,68030,68031,68096,68096,68097,68099,68101,68102,68108,68111,68112,68115,68117,68119,68121,68147,68152,68154,68159,68159,68192,68220,68352,68405,68416,68437,68448,68466,68608,68680,69632,69632,69633,69633,69634,69634,69635,69687,69688,69702,69734,69743,69760,69761,69762,69762,69763,69807,69808,69810,69811,69814,69815,69816,69817,69818,69840,69864,69872,69881,69888,69890,69891,69926,69927,69931,69932,69932,69933,69940,69942,69951,70016,70017,70018,70018,70019,70066,70067,70069,70070,70078,70079,70080,70081,70084,70096,70105,71296,71338,71339,71339,71340,71340,71341,71341,71342,71343,71344,71349,71350,71350,71351,71351,71360,71369,73728,74606,74752,74850,77824,78894,92160,92728,93952,94020,94032,94032,94033,94078,94095,94098,94099,94111,110592,110593,119141,119142,119143,119145,119149,119154,119163,119170,119173,119179,119210,119213,119362,119364,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120514,120538,120540,120570,120572,120596,120598,120628,120630,120654,120656,120686,120688,120712,120714,120744,120746,120770,120772,120779,120782,120831,126464,126467,126469,126495,126497,126498,126500,126500,126503,126503,126505,126514,126516,126519,126521,126521,126523,126523,126530,126530,126535,126535,126537,126537,126539,126539,126541,126543,126545,126546,126548,126548,126551,126551,126553,126553,126555,126555,126557,126557,126559,126559,126561,126562,126564,126564,126567,126570,126572,126578,126580,126583,126585,126588,126590,126590,126592,126601,126603,126619,126625,126627,126629,126633,126635,126651,131072,173782,173824,177972,177984,178205,194560,195101,917760,917999))
    }

    /* How many UTF-16 units the codepoint at this position occupies. A regex
     * step MoarVM measures in codepoints has to advance by this, or it lands
     * between the halves of a non-BMP character. */
    @JvmStatic
    fun cpwidth(target: String?, offset: Long): Long {
        val i = offset.toInt()
        if (target == null || i >= target.length)
            return 1L
        return Character.charCount(target.codePointAt(i)).toLong()
    }

    @JvmStatic
    fun ischarprop(propName: String?, target: String?, offset: Long): Long {
        val iOffset = offset.toInt()
        if (offset >= target!!.length)
            return 0
        val check = if (target.codePointAt(iOffset) >= 65536)
            target.substring(iOffset, iOffset + 2)
        else
            target.substring(iOffset, iOffset + 1)
        val derived = derivedProps.get(propName)
        if (derived != null) {
            /* It's one of the derived properties; see if the codepoint is
             * in it. */
            val cp = check.codePointAt(0)
            var i = 0
            while (i < derived.size) {
                if (cp >= derived[i] && cp <= derived[i + 1])
                    return 1
                i += 2
            }
            return 0
        }
        try {
            // This throws if we can't get the script name, meaning it's
            // not a script.
            val script = Character.UnicodeScript.forName(propName)
            return if (Character.UnicodeScript.of(check.codePointAt(0)) == script) 1 else 0
        }
        catch (e: IllegalArgumentException) {
            var theName = propName!!
            val canon = canonNames.get(theName.lowercase())
            if (canon != null)
                theName = canon
            return if (check.matches(("\\p{" + theName + "}").toRegex())) 1 else 0
        }
    }

    @JvmStatic
    fun bitor_s(a: String?, b: String?): String {
        val alength = a!!.length
        val blength = b!!.length
        val mlength = if (alength > blength) alength else blength
        val r = StringBuilder(mlength)
        var apos = 0
        var bpos = 0
        while (apos < alength || bpos < blength) {
            val cpa = if (apos < alength) a.codePointAt(apos) else 0
            val cpb = if (bpos < blength) b.codePointAt(bpos) else 0
            r.appendCodePoint(cpa or cpb)
            apos += Character.charCount(cpa)
            bpos += Character.charCount(cpb)
        }
        return r.toString()
    }

    @JvmStatic
    fun bitxor_s(a: String?, b: String?): String {
        val alength = a!!.length
        val blength = b!!.length
        val mlength = if (alength > blength) alength else blength
        val r = StringBuilder(mlength)
        var apos = 0
        var bpos = 0
        while (apos < alength || bpos < blength) {
            val cpa = if (apos < alength) a.codePointAt(apos) else 0
            val cpb = if (bpos < blength) b.codePointAt(bpos) else 0
            r.appendCodePoint(cpa xor cpb)
            apos += Character.charCount(cpa)
            bpos += Character.charCount(cpb)
        }
        return r.toString()
    }

    @JvmStatic
    fun bitand_s(a: String?, b: String?): String {
        val alength = a!!.length
        val blength = b!!.length
        val mlength = if (alength > blength) alength else blength
        val r = StringBuilder(mlength)
        var apos = 0
        var bpos = 0
        while (apos < alength && bpos < blength) {
            val cpa = a.codePointAt(apos)
            val cpb = b.codePointAt(bpos)
            r.appendCodePoint(cpa and cpb)
            apos += Character.charCount(cpa)
            bpos += Character.charCount(cpb)
        }
        return r.toString()
    }

    /* serialization context related opcodes */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class, UnsupportedEncodingException::class)
    fun sha1(str: String?): String {
        val md = MessageDigest.getInstance("SHA1")

        val inBytes = str!!.toByteArray(Charsets.UTF_8)
        val outBytes = md.digest(inBytes)

        val sb = StringBuilder()
        for (b in outBytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
    @JvmStatic
    fun createsc(handle: String, tc: ThreadContext): SixModelObject? {
        if (tc.gc.scs.containsKey(handle))
            return tc.gc.scRefs.get(handle)

        val sc = SerializationContext(handle)
        tc.gc.scs.put(handle, sc)

        val SCRef = tc.gc.SCRef!!
        val ref = SCRef.st.REPR.allocate(tc, SCRef.st) as SCRefInstance
        ref.referencedSC = sc
        tc.gc.scRefs.put(handle, ref)

        return ref
    }
    @JvmStatic
    fun scsetobj(scRef: SixModelObject?, idx: Long, obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            val sc = scRef.referencedSC!!
            sc.addObject(obj, idx.toInt())
            if (obj!!.st.sc == null) {
                sc.addSTable(obj.st)
                obj.st.sc = sc
            }
            return obj
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scsetobj can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scsetcode(scRef: SixModelObject?, idx: Long, obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            if (obj is CodeRef) {
                val referencedSC = scRef.referencedSC!!
                referencedSC.addCodeRef(obj, idx.toInt())
                obj.sc = referencedSC
                return obj
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "scsetcode can only store a CodeRef")
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scsetcode can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scgetobj(scRef: SixModelObject?, idx: Long, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            return scRef.referencedSC!!.getObject(idx.toInt())
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scgetobj can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scgethandle(scRef: SixModelObject?, tc: ThreadContext): String? {
        if (scRef is SCRefInstance) {
            return scRef.referencedSC!!.handle
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scgethandle can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scgetdesc(scRef: SixModelObject?, tc: ThreadContext): String? {
        if (scRef is SCRefInstance) {
            return scRef.referencedSC!!.description
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scgetdesc can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scgetobjidx(scRef: SixModelObject?, find: SixModelObject?, tc: ThreadContext): Long {
        if (scRef is SCRefInstance) {
            val idx = scRef.referencedSC!!.getObjectIndex(find)
            if (idx < 0)
                throw ExceptionHandling.dieInternal(tc, "Object does not exist in this SC")
            return idx.toLong()
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scgetobjidx can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scsetdesc(scRef: SixModelObject?, desc: String?, tc: ThreadContext): String? {
        if (scRef is SCRefInstance) {
            scRef.referencedSC!!.description = desc
            return desc
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scsetdesc can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun scobjcount(scRef: SixModelObject?, tc: ThreadContext): Long {
        if (scRef is SCRefInstance) {
            return scRef.referencedSC!!.objectCount().toLong()
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scobjcount can only operate on an SCRef")
        }
    }
    @JvmStatic
    fun setobjsc(obj: SixModelObject?, scRef: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            obj!!.sc = scRef.referencedSC
            return obj
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "setobjsc requires an SCRef")
        }
    }
    @JvmStatic
    fun getobjsc(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (isnull(obj) == 1L)
            return null
        val sc = obj!!.sc
            ?: return null
        if (!tc.gc.scRefs.containsKey(sc.handle)) {
            val SCRef = tc.gc.SCRef!!
            val ref = SCRef.st.REPR.allocate(tc, SCRef.st) as SCRefInstance
            ref.referencedSC = sc
            tc.gc.scRefs.put(sc.handle, ref)
        }
        return tc.gc.scRefs.get(sc.handle)
    }
    @JvmStatic
    fun serialize(scRef: SixModelObject?, sh: SixModelObject?, tc: ThreadContext): String {
        if (scRef is SCRefInstance) {
            val stringHeap = ArrayList<String?>()
            val sw = SerializationWriter(tc,
                    scRef.referencedSC!!,
                    stringHeap)

            val serialized = Base64.encode(sw.serialize())

            var index = 0
            for (s in stringHeap) {
                tc.nativeS = s
                sh!!.bind_pos_native(tc, (index++).toLong())
            }

            return serialized
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "serialize was not passed a valid SCRef")
        }
    }
    @JvmStatic
    fun serializetobuf(scRef: SixModelObject?, sh: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            val stringHeap = ArrayList<String?>()
            val sw = SerializationWriter(tc,
                    scRef.referencedSC!!,
                    stringHeap)

            val buf = type!!.st.REPR.allocate(tc, type.st)

            val serialized = sw.serialize().array()

            Buffers.stashBytes(tc, buf, serialized)

            var index = 0
            for (s in stringHeap) {
                tc.nativeS = s
                sh!!.bind_pos_native(tc, (index++).toLong())
            }

            return buf
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "serialize was not passed a valid SCRef")
        }
    }
    @JvmStatic
    fun deserialize(
        blob: String?,
        scRef: SixModelObject?,
        sh: SixModelObject?,
        cr: SixModelObject?,
        conflict: SixModelObject?,
        tc: ThreadContext
    ): String? {
        if (scRef !is SCRefInstance)
            throw ExceptionHandling.dieInternal(tc, "deserialize was not passed a valid SCRef")
        val sc = scRef.referencedSC!!

        val shArray = arrayOfNulls<String>(sh!!.elems(tc).toInt())
        for (i in shArray.indices) {
            sh.at_pos_native(tc, i.toLong())
            shArray[i] = tc.nativeS
        }

        val cu = tc.frame.codeRef.staticInfo.compUnit
        val crArray: Array<CodeRef?>
        val crCount: Int
        if (isnull(cr) == 1L) {
            crArray = cu.qbidToCodeRef!!
            crCount = cu.serializedCodeRefCount()
        } else {
            crArray = arrayOfNulls<CodeRef>(cr!!.elems(tc).toInt())
            crCount = crArray.size
            for (i in crArray.indices)
                crArray[i] = cr.at_pos_boxed(tc, i.toLong()) as CodeRef?
        }

        val binaryBlob: ByteBuffer
        if (blob == null)
            try {
                val cuKlass: Class<*> = cu.javaClass
                val cuName = cuKlass.simpleName
                var cuStream = cuKlass.getResourceAsStream(cuName + ".serialized.lz4")
                try {
                    if (cuStream != null)
                        binaryBlob = LibraryLoader.readToHeapBufferLz4(cuStream)
                    else {
                        cuStream = cuKlass.getResourceAsStream(cuName + ".serialized")
                        binaryBlob = LibraryLoader.readToHeapBuffer(cuStream)
                    }
                }
                finally {
                    cuStream!!.close()
                }
            }
            catch (e: IOException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }
        else
            try {
                binaryBlob = Base64.decode(blob)
            }
            catch (e: IllegalArgumentException) {
                throw ExceptionHandling.dieInternal(tc, e)
            }

        @Suppress("UNCHECKED_CAST")
        val sr = SerializationReader(tc, sc, shArray, crArray as Array<CodeRef>, crCount, binaryBlob)
        sr.deserialize()
        return blob
    }
    @JvmStatic
    fun wval(sc: String, idx: Long, tc: ThreadContext): SixModelObject? {
        return tc.gc.scs.get(sc)!!.getObject(idx.toInt())
    }
    @JvmStatic
    fun scwbdisable(tc: ThreadContext): Long {
        return (++tc.scwbDisableDepth).toLong()
    }

    @JvmStatic
    fun scwbenable(tc: ThreadContext): Long {
        return (--tc.scwbDisableDepth).toLong()
    }
    @JvmStatic
    fun pushcompsc(sc: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (sc is SCRefInstance) {
            if (tc.compilingSCs == null)
                tc.compilingSCs = ArrayList()
            tc.compilingSCs!!.add(sc)
            return sc
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "Can only push an SCRef with pushcompsc")
        }
    }
    @JvmStatic
    fun popcompsc(tc: ThreadContext): SixModelObject {
        if (tc.compilingSCs == null)
            throw ExceptionHandling.dieInternal(tc, "No current compiling SC")
        val idx = tc.compilingSCs!!.size - 1
        val result: SixModelObject = tc.compilingSCs!![idx]
        tc.compilingSCs!!.removeAt(idx)
        if (idx == 0)
            tc.compilingSCs = null
        return result
    }
    @JvmStatic
    fun neverrepossess(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        tc.gc.neverRepossess.put(obj, null)
        return obj
    }
    @JvmStatic
    fun scdisclaim(scRef: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (scRef is SCRefInstance) {
            val sc = scRef.referencedSC!!
            sc.disclaimObjects()
            sc.disclaimSTables()
            sc.disclaimCodes()
            return scRef
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "scdisclaim was not passed a valid SCRef")
        }
    }

    /* SC write barriers (not really ops, but putting them here with the SC
     * related bits). */
    @JvmStatic
    fun scwbObject(tc: ThreadContext, obj: SixModelObject?) {
        var theObj = obj
        val cscSize = if (tc.compilingSCs == null) 0 else tc.compilingSCs!!.size
        if (cscSize == 0 || tc.scwbDisableDepth > 0)
            return
        if (tc.gc.neverRepossess.containsKey(theObj))
            return

        /* See if the object is actually owned by another, and it's the
         * owner we need to repossess. */
        val owner = theObj!!.sc!!.ownedObjects.get(theObj)
        if (isnull(owner) == 0L)
            theObj = owner

        val compSC = tc.compilingSCs!![cscSize - 1].referencedSC
        val objSC = theObj!!.sc
        if (objSC == null) { /* Probably disclaimed. */
            return
        }
        else if (objSC !== compSC) {
            compSC!!.repossessObject(objSC, theObj)
            theObj.sc = compSC
        }
    }
    @JvmStatic
    fun scwbSTable(tc: ThreadContext, st: STable) {
        val cscSize = if (tc.compilingSCs == null) 0 else tc.compilingSCs!!.size
        if (cscSize == 0 || tc.scwbDisableDepth > 0)
            return
        val compSC = tc.compilingSCs!![cscSize - 1].referencedSC
        val stSC = st.sc
        if (stSC !== compSC) {
            compSC!!.repossessSTable(stSC!!, st)
            st.sc = compSC
        }
    }

    /* bitwise operations. */
    @JvmStatic
    fun bitor_i(valA: Long, valB: Long): Long {
        return valA or valB
    }

    @JvmStatic
    fun bitxor_i(valA: Long, valB: Long): Long {
        return valA xor valB
    }

    @JvmStatic
    fun bitand_i(valA: Long, valB: Long): Long {
        return valA and valB
    }

    @JvmStatic
    fun bitshiftl_i(valA: Long, valB: Long): Long {
        return valA shl valB.toInt()
    }

    @JvmStatic
    fun bitshiftr_i(valA: Long, valB: Long): Long {
        return valA shr valB.toInt()
    }

    @JvmStatic
    fun bitneg_i(`val`: Long): Long {
        return `val`.inv()
    }

    /* Relational. */
    @JvmStatic
    fun cmp_i(a: Long, b: Long): Long {
        if (a < b) {
            return -1
        } else if (a > b) {
            return 1
        } else {
            return 0
        }
    }
    @JvmStatic
    fun iseq_i(a: Long, b: Long): Long {
        return if (a == b) 1 else 0
    }
    @JvmStatic
    fun isne_i(a: Long, b: Long): Long {
        return if (a != b) 1 else 0
    }
    @JvmStatic
    fun islt_i(a: Long, b: Long): Long {
        return if (a < b) 1 else 0
    }
    @JvmStatic
    fun isle_i(a: Long, b: Long): Long {
        return if (a <= b) 1 else 0
    }
    @JvmStatic
    fun isgt_i(a: Long, b: Long): Long {
        return if (a > b) 1 else 0
    }
    @JvmStatic
    fun isge_i(a: Long, b: Long): Long {
        return if (a >= b) 1 else 0
    }

    @JvmStatic
    fun cmp_u(a: Long, b: Long): Long {
        return java.lang.Long.compareUnsigned(a, b).toLong()
    }
    @JvmStatic
    fun iseq_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) == 0) 1 else 0
    }
    @JvmStatic
    fun isne_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) != 0) 1 else 0
    }
    @JvmStatic
    fun islt_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) < 0) 1 else 0
    }
    @JvmStatic
    fun isle_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) <= 0) 1 else 0
    }
    @JvmStatic
    fun isgt_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) > 0) 1 else 0
    }
    @JvmStatic
    fun isge_u(a: Long, b: Long): Long {
        return if (java.lang.Long.compareUnsigned(a, b) >= 0) 1 else 0
    }

    @JvmStatic
    fun cmp_n(a: Double, b: Double): Long {
        if (a < b) {
            return -1
        } else if (a > b) {
            return 1
        } else {
            return 0
        }
    }
    @JvmStatic
    fun iseq_n(a: Double, b: Double): Long {
        return if (a == b) 1 else 0
    }
    @JvmStatic
    fun isne_n(a: Double, b: Double): Long {
        return if (a != b) 1 else 0
    }
    @JvmStatic
    fun islt_n(a: Double, b: Double): Long {
        return if (a < b) 1 else 0
    }
    @JvmStatic
    fun isle_n(a: Double, b: Double): Long {
        return if (a <= b) 1 else 0
    }
    @JvmStatic
    fun isgt_n(a: Double, b: Double): Long {
        return if (a > b) 1 else 0
    }
    @JvmStatic
    fun isge_n(a: Double, b: Double): Long {
        return if (a >= b) 1 else 0
    }

    // NFG: string identity/order is over the NFC (canonical) form, so that
    // canonically-equivalent strings ("é" and "é") compare equal.
    // Normalizer short-circuits when the input is already NFC.
    @JvmStatic
    fun nfcKey(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    @JvmStatic
    fun cmp_s(a: String?, b: String?): Long {
        val result = nfcKey(a!!).compareTo(nfcKey(b!!))
        return if (result < 0) -1 else if (result > 0) 1 else 0
    }
    @JvmStatic
    fun iseq_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!) == nfcKey(b!!)) 1 else 0
    }
    @JvmStatic
    fun isne_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!) == nfcKey(b!!)) 0 else 1
    }
    @JvmStatic
    fun islt_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!).compareTo(nfcKey(b!!)) < 0) 1 else 0
    }
    @JvmStatic
    fun isle_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!).compareTo(nfcKey(b!!)) <= 0) 1 else 0
    }
    @JvmStatic
    fun isgt_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!).compareTo(nfcKey(b!!)) > 0) 1 else 0
    }
    @JvmStatic
    fun isge_s(a: String?, b: String?): Long {
        return if (nfcKey(a!!).compareTo(nfcKey(b!!)) >= 0) 1 else 0
    }

    /* Code object related. */
    @JvmStatic
    fun takeclosure(code: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (code is CodeRef) {
            val clone = code.clone(tc) as CodeRef
            clone.outer = tc.curFrame
            return clone
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "takeclosure can only be used with a CodeRef")
        }
    }
    @JvmStatic
    fun getcodeobj(code: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (code is CodeRef)
            return code.codeObject
        else
            throw ExceptionHandling.dieInternal(tc, "getcodeobj can only be used with a CodeRef")
    }
    @JvmStatic
    fun setcodeobj(code: SixModelObject?, obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (code is CodeRef) {
            code.codeObject = obj
            return code
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "setcodeobj can only be used with a CodeRef")
        }
    }
    @JvmStatic
    fun getcodename(code: SixModelObject?, tc: ThreadContext): String? {
        if (code is CodeRef)
            return code.name
        else
            throw ExceptionHandling.dieInternal(tc, "getcodename can only be used with a CodeRef")
    }
    @JvmStatic
    fun getcodelocation(code: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "getcodelocation can only be used with a CodeRef")
        val hllConfig = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        val res = hllConfig.hashType!!.st.REPR.allocate(tc, hllConfig.hashType!!.st)
        val si = code.staticInfo
        res.bind_key_boxed(tc, "file",
            box_s(si.sourceFile ?: "unknown", hllConfig.strBoxType, tc))
        res.bind_key_boxed(tc, "line",
            box_i(si.sourceLine.toLong(), hllConfig.intBoxType, tc))
        return res
    }
    @JvmStatic
    fun setcodename(code: SixModelObject?, name: String?, tc: ThreadContext): SixModelObject? {
        if (code is CodeRef) {
            code.name = name
            return code
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "setcodename can only be used with a CodeRef")
        }
    }
    @JvmStatic
    fun getcodecuid(code: SixModelObject?, tc: ThreadContext): String? {
        if (code is CodeRef)
            return code.staticInfo.uniqueId
        else
            throw ExceptionHandling.dieInternal(tc, "getcodename can only be used with a CodeRef")
    }
    /**
     * Gives the code a scope to close over that was never entered. A phaser
     * can be asked to run without its enclosing block ever having been
     * invoked -- a QUIT on a whenever that never fired, say -- and it still
     * has to find that block's lexicals. Build a frame for the enclosing
     * static scope and hand it to the code as its outer; that frame in turn
     * finds the nearest live instance of the scope around it, so the phaser
     * reaches real values wherever there are any. MoarVM's
     * MVM_frame_capture_inner does the same two steps.
     */
    @JvmStatic
    fun captureinnerlex(code: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "captureinnerlex must be used on a CodeRef")
        val outerStatic = code.staticInfo.outerStaticInfo ?: return code
        code.outer = CallFrame.contextOnly(tc, outerStatic)
        return code
    }
    @JvmStatic
    fun forceouterctx(code: SixModelObject?, ctx: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "forceouterctx first operand must be a CodeRef")
        if (ctx !is ContextRefInstance)
            throw ExceptionHandling.dieInternal(tc, "forceouterctx second operand must be a ContextRef")
        code.outer = ctx.context
        return code
    }
    @JvmStatic
    fun freshcoderef(code: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "freshcoderef must be used on a CodeRef")
        val clone = code.clone(tc) as CodeRef
        clone.staticInfo = clone.staticInfo.clone()
        clone.staticInfo.staticCode = clone
        return clone
    }
    @JvmStatic
    fun markcodestatic(code: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "markcodestatic must be used on a CodeRef")
        code.isStaticCodeRef = true
        return code
    }
    @JvmStatic
    fun markcodestub(code: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc, "markcodestub must be used on a CodeRef")
        code.isCompilerStub = true
        return code
    }
    @JvmStatic
    fun getstaticcode(code: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (code is CodeRef)
            return code.staticInfo.staticCode
        else
            throw ExceptionHandling.dieInternal(tc, "getstaticcode can only be used with a CodeRef")
    }
    @JvmStatic
    fun setdispatcher(disp: SixModelObject?, tc: ThreadContext): SixModelObject? {
        tc.currentDispatcher = disp
        return disp
    }
    @JvmStatic
    fun setdispatcherfor(disp: SixModelObject?, dispFor: SixModelObject?, tc: ThreadContext): SixModelObject? {
        tc.currentDispatcher = disp
        if (dispFor is CodeRef) {
            tc.currentDispatcherFor = dispFor
        }
        else {
            val invSpec = dispFor!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "setdispatcherfor needs invokable target")
            if (isnull(invSpec.ClassHandle) == 0L)
                tc.currentDispatcherFor = dispFor.get_attribute_boxed(tc,
                        invSpec.ClassHandle, invSpec.AttrName, invSpec.Hint) as CodeRef?
            else
                throw ExceptionHandling.dieInternal(tc, "setdispatcherfor needs simple invokable target")
        }
        return disp
    }
    @JvmStatic
    fun takedispatcher(lexIdx: Int, tc: ThreadContext) {
        if (isnull(tc.currentDispatcher) == 0L) {
            if (isnull(tc.currentDispatcherFor) == 1L ||
                    tc.currentDispatcherFor === tc.frame.codeRef) {
                tc.frame.oLex!![lexIdx] = tc.currentDispatcher
                tc.currentDispatcher = null
            }
        }
    }
    @JvmStatic
    fun nextdispatcherfor(disp: SixModelObject?, dispFor: SixModelObject?, tc: ThreadContext): SixModelObject? {
        tc.nextDispatcher = disp
        if (dispFor is CodeRef) {
            tc.nextDispatcherFor = dispFor
        }
        else {
            val invSpec = dispFor!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "nextdispatcherfor needs invokable target")
            if (isnull(invSpec.ClassHandle) == 0L)
                tc.nextDispatcherFor = dispFor.get_attribute_boxed(tc,
                        invSpec.ClassHandle, invSpec.AttrName, invSpec.Hint) as CodeRef?
            else
                throw ExceptionHandling.dieInternal(tc, "nextdispatcherfor needs simple invokable target")
        }
        return disp
    }
    @JvmStatic
    fun takenextdispatcher(lexIdx: Int, tc: ThreadContext) {
        if (isnull(tc.nextDispatcher) == 0L) {
            if (isnull(tc.nextDispatcherFor) == 1L ||
                    tc.nextDispatcherFor === tc.frame.codeRef) {
                tc.frame.oLex!![lexIdx] = tc.nextDispatcher
                tc.nextDispatcher = null
            }
        }
    }

    /* process related opcodes */
    @JvmStatic
    fun exit(status: Long, tc: ThreadContext): Long {
        tc.gc.exit(status.toInt())
        return status
    }

    @JvmStatic
    fun sleep(seconds: Double): Double {
        // Is this really the right behavior, i.e., swallowing all
        // InterruptedExceptions?  As far as I can tell the original
        // nqp::sleep could not be interrupted, so that behavior is
        // duplicated here, but that doesn't mean it's the right thing
        // to do on the JVM...

        var now = System.currentTimeMillis()

        val awake = now + (seconds * 1000).toLong()

        while (true) {
            now = System.currentTimeMillis()
            if (now >= awake)
                break
            val millis = awake - now
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                // swallow
            }
        }

        return seconds
    }

    @JvmStatic
    fun getenvhash(tc: ThreadContext): SixModelObject {
        val hashType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType!!
        val strType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
        val res = hashType.st.REPR.allocate(tc, hashType.st)

        val env = System.getenv()
        for (envName in env.keys)
            res.bind_key_boxed(tc, envName, box_s(env.get(envName), strType, tc))

        return res
    }

    @JvmStatic
    fun getpid(tc: ThreadContext): Long {
        try {
            val ph = ProcessHandle.current()
            val res = ph.pid()
            return res
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    @JvmStatic
    fun getppid(tc: ThreadContext): Long {
        try {
            val ph = ProcessHandle.current()
            val res = ph.parent().get().pid()
            return res
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    /* There's not a getrusage (with Windows fakery) equivalent on JVM, sadly.
     * The main reason this op exists is for the thread pool scheduler in
     * Rakudo, and we can get (or fake up enough of) what it needs. */
    @JvmStatic
    fun getrusage(res: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (isconcrete(res, tc) == 1L) {
            val cpuNanos = (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean)
                .processCpuTime
            val cpuMillis = cpuNanos / 1000
            if (res is VMArrayInstance_i) {
                tc.nativeI = cpuMillis / 1000000 // UTIME_SEC
                res.bind_pos_native(tc, 0)
                tc.nativeI = cpuMillis % 1000000 // UTIME_MSEC
                res.bind_pos_native(tc, 1)
            }
            /* TODO remove workar^H^H^Hdirty hack for non-working native arrays in ThreadPoolScheduler */
            /* https://github.com/rakudo/rakudo/issues/1666 */
            else {
                val Int = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType
                res!!.bind_pos_boxed(tc, 0, box_i(cpuMillis / 1000000, Int, tc)) // UTIME_SEC
                res.bind_pos_boxed(tc, 1, box_i(cpuMillis % 1000000, Int, tc)) // UTIME_MSEC
                res.bind_pos_boxed(tc, 2, box_i(0, Int, tc))                   // STIME_SEC
                res.bind_pos_boxed(tc, 3, box_i(0, Int, tc))                   // STIME_SEC
            }
            return res
        }
        else {
            throw RuntimeException("getrusage needs a concrete 64bit int array, got " + res!!.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun jvmgetproperties(tc: ThreadContext): SixModelObject {
        val hashType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType!!
        val strType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
        val res = hashType.st.REPR.allocate(tc, hashType.st)

        val env = System.getProperties()
        for (envName in env.stringPropertyNames()) {
            var propVal = env.getProperty(envName)
            if (envName == "os.name") {
                // Normalize OS name (some cases likely missing).
                val pvlc = propVal.lowercase()
                if (pvlc.indexOf("win") >= 0)
                    propVal = "MSWin32"
                else if (pvlc.indexOf("mac os x") >= 0)
                    propVal = "darwin"
            }
            res.bind_key_boxed(tc, envName, box_s(propVal, strType, tc))
        }

        return res
    }

    @JvmStatic
    fun execname(tc: ThreadContext): String {
        val env = System.getProperties()
        if (env.containsKey("raku.execname"))
            return env.getProperty("raku.execname")
        else if (env.containsKey("perl6.execname"))
            return env.getProperty("perl6.execname")
        else if (env.containsKey("nqp.execname"))
            return env.getProperty("nqp.execname")

        return ""
    }

    /* Thread related. */
    private class CodeRunnable(
        private val gc: GlobalContext,
        private val vmthread: SixModelObject,
        private val code: SixModelObject?
    ) : Runnable {
        override fun run() {
            val tc = gc.getCurrentThreadContext()
            tc!!.VMThread = vmthread
            invokeArgless(tc, code)
        }
    }
    /* nqp threads are virtual by default (Project Loom): the thread-pool
     * scheduler's workers and hyper/race batches are exactly the cheap,
     * blocking-friendly tasks virtual threads are for, and Truffle 25
     * runs guest code on them (pinning its carrier for the duration,
     * which a worker would have monopolized anyway). Two carve-outs:
     * a non-daemon thread must hold the JVM open, which only a platform
     * thread can, and NQP_JVM_PLATFORM_THREADS=1 restores the old
     * behavior wholesale as the measurement/kill switch. */
    private val platformThreadsOnly = System.getenv("NQP_JVM_PLATFORM_THREADS") != null

    @JvmStatic
    fun newthread(code: SixModelObject?, appLifetime: Long, tc: ThreadContext): SixModelObject {
        val thread = tc.gc.Thread!!.st.REPR.allocate(tc, tc.gc.Thread!!.st)
        val body = CodeRunnable(tc.gc, thread, code)
        thread as VMThreadInstance
        if (appLifetime != 0L && !platformThreadsOnly) {
            thread.thread = Thread.ofVirtual().unstarted(body)
        }
        else {
            thread.thread = Thread(body)
            thread.thread!!.setDaemon(appLifetime != 0L)
        }
        return thread
    }

    @JvmStatic
    fun threadrun(thread: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (thread is VMThreadInstance)
            thread.thread!!.start()
        else
            throw ExceptionHandling.dieInternal(tc, "threadrun requires an operand with REPR VMThread")
        return thread
    }

    @JvmStatic
    fun threadjoin(thread: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (thread is VMThreadInstance) {
            try {
                thread.thread!!.join()
            }
            catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "threadjoin requires an operand with REPR VMThread")
        }
        return thread
    }

    @JvmStatic
    fun threadid(thread: SixModelObject?, tc: ThreadContext): Long {
        if (thread is VMThreadInstance)
            return thread.thread!!.getId()
        else
            throw ExceptionHandling.dieInternal(tc, "threadid requires an operand with REPR VMThread")
    }

    @JvmStatic
    fun threadlockcount(thread: SixModelObject?, tc: ThreadContext): Long {
        if (thread is VMThreadInstance)
            return thread.lockCount
        else
            throw ExceptionHandling.dieInternal(tc,
                "threadlockcount requires an operand with REPR VMThread")
    }

    @JvmStatic
    fun threadyield(tc: ThreadContext): Long {
        Thread.yield()
        return 0
    }

    @JvmStatic
    fun currentthread(tc: ThreadContext): SixModelObject {
        var thread = tc.VMThread
        if (isnull(thread) == 1L) {
            thread = tc.gc.Thread!!.st.REPR.allocate(tc, tc.gc.Thread!!.st)
            (thread as VMThreadInstance).thread = Thread.currentThread()
            tc.VMThread = thread
        }
        return thread!!
    }

    @JvmStatic
    fun cpucores(tc: ThreadContext): Long {
        return Runtime.getRuntime().availableProcessors().toLong()
    }

    @JvmStatic
    fun freemem(tc: ThreadContext): Long {
        return Runtime.getRuntime().freeMemory()
    }

    @JvmStatic
    fun totalmem(tc: ThreadContext): Long {
        return Runtime.getRuntime().totalMemory()
    }

    @JvmStatic
    fun lock(lock: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (lock is ReentrantMutexInstance) {
            lock.lock!!.lock()
            (currentthread(tc) as VMThreadInstance).lockCount++
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "lock requires an operand with REPR ReentrantMutex")
        }
        return lock
    }

    @JvmStatic
    fun unlock(lock: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (lock is ReentrantMutexInstance) {
            lock.lock!!.unlock()
            (currentthread(tc) as VMThreadInstance).lockCount--
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "unlock requires an operand with REPR ReentrantMutex")
        }
        return lock
    }

    @JvmStatic
    fun getlockcondvar(lock: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (lock !is ReentrantMutexInstance)
            throw ExceptionHandling.dieInternal(tc, "getlockcondvar requires an operand with REPR ReentrantMutex")
        if (type!!.st.REPR !is ConditionVariable)
            throw ExceptionHandling.dieInternal(tc, "getlockcondvar requires a result type with REPR ConditionVariable")
        val result = ConditionVariableInstance()
        result.st = type.st
        result.condvar = lock.lock!!.newCondition()
        return result
    }

    @JvmStatic
    @Throws(InterruptedException::class)
    fun condwait(cv: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (cv is ConditionVariableInstance)
            cv.condvar!!.await()
        else
            throw ExceptionHandling.dieInternal(tc, "condwait requires an operand with REPR ConditionVariable")
        return cv
    }

    @JvmStatic
    fun condsignalone(cv: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (cv is ConditionVariableInstance)
            try {
                cv.condvar!!.signal()
            } catch (imse: IllegalMonitorStateException) {
                throw ExceptionHandling.dieInternal(tc, "condsignalone requires the lock corresponding to the condition variable to be locked")
            }
        else
            throw ExceptionHandling.dieInternal(tc, "condsignalone requires an operand with REPR ConditionVariable")
        return cv
    }

    @JvmStatic
    fun condsignalall(cv: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (cv is ConditionVariableInstance)
            try {
                cv.condvar!!.signalAll()
            } catch (imse: IllegalMonitorStateException) {
                throw ExceptionHandling.dieInternal(tc, "condsignalall requires the lock corresponding to the condition variable to be locked")
            }
        else
            throw ExceptionHandling.dieInternal(tc, "condsignalall requires an operand with REPR ConditionVariable")
        return cv
    }

    @JvmStatic
    fun semacquire(sem: SixModelObject?, tc: ThreadContext): SixModelObject? {
        try {
            if (sem is SemaphoreInstance)
                sem.sem!!.acquire()
            else
                throw ExceptionHandling.dieInternal(tc, "semacquire requires an operand with REPR Semaphore")
        } catch (e: InterruptedException) {
            throw ExceptionHandling.dieInternal(tc, "semacquire was interrupted")
        }
        return sem
    }

    @JvmStatic
    fun semtryacquire(sem: SixModelObject?, tc: ThreadContext): Long {
        val result: Boolean
        if (sem is SemaphoreInstance)
            result = sem.sem!!.tryAcquire()
        else
            throw ExceptionHandling.dieInternal(tc, "semtryacquire requires an operand with REPR Semaphore")

        return if (result) 1 else 0
    }

    @JvmStatic
    fun semrelease(sem: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (sem is SemaphoreInstance)
            sem.sem!!.release()
        else
            throw ExceptionHandling.dieInternal(tc, "semrelease requires an operand with REPR Semaphore")
        return sem
    }

    @JvmStatic
    fun queuepoll(queue: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (queue is ConcBlockingQueueInstance)
            return queue.queue.poll()
        else
            throw ExceptionHandling.dieInternal(tc, "queuepoll requires an operand with REPR ConcBlockingQueue")
    }

    /* Atomic operations. */

    @JvmStatic
    fun cas(cont: SixModelObject?, expected: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            return cs.cas(tc, cont, decont(expected, tc)!!, decont(value, tc)!!)
        else
            throw ExceptionHandling.dieInternal(tc,
                "Cannot atomic compare and swap to an immutable value")
    }
    @JvmStatic
    fun atomicload(cont: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null)
            return cs.atomic_load(tc, cont)
        else
            throw ExceptionHandling.dieInternal(tc,
                "Cannot atomic load from an immutable value")
    }
    @JvmStatic
    fun atomicstore(cont: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val cs = cont!!.st.ContainerSpec
        if (cs != null) {
            val theValue = decont(value, tc)
            cs.atomic_store(tc, cont, theValue!!)
            return theValue
        }
        else {
            throw ExceptionHandling.dieInternal(tc,
                "Cannot atomic store to an immutable value")
        }
    }
    /* The integer atomics work on a native-int reference (lexical, attribute
     * or positional). The reference kinds store into plain long slots that a
     * VarHandle cannot uniformly cover, so a shared lock provides the
     * atomicity; only code actually using the atomic ops contends on it. */
    private val intAtomicsLock = Object()
    private fun nativeIntRef(cont: SixModelObject?, tc: ThreadContext): NativeRefInstance =
        cont as? NativeRefInstance ?: throw ExceptionHandling.dieInternal(tc,
            "Can only do an atomic integer operation on a native integer reference")
    @JvmStatic
    fun atomicload_i(cont: SixModelObject?, tc: ThreadContext): Long {
        val ref = nativeIntRef(cont, tc)
        synchronized(intAtomicsLock) { return ref.fetch_i(tc) }
    }
    @JvmStatic
    fun atomicstore_i(cont: SixModelObject?, value: Long, tc: ThreadContext): Long {
        val ref = nativeIntRef(cont, tc)
        synchronized(intAtomicsLock) { ref.store_i(tc, value) }
        return value
    }
    @JvmStatic
    fun atomicadd_i(cont: SixModelObject?, addend: Long, tc: ThreadContext): Long {
        val ref = nativeIntRef(cont, tc)
        synchronized(intAtomicsLock) {
            val orig = ref.fetch_i(tc)
            ref.store_i(tc, orig + addend)
            return orig
        }
    }
    @JvmStatic
    fun atomicinc_i(cont: SixModelObject?, tc: ThreadContext): Long =
        atomicadd_i(cont, 1L, tc)
    @JvmStatic
    fun atomicdec_i(cont: SixModelObject?, tc: ThreadContext): Long =
        atomicadd_i(cont, -1L, tc)
    @JvmStatic
    fun cas_i(cont: SixModelObject?, expected: Long, value: Long, tc: ThreadContext): Long {
        val ref = nativeIntRef(cont, tc)
        synchronized(intAtomicsLock) {
            val seen = ref.fetch_i(tc)
            if (seen == expected)
                ref.store_i(tc, value)
            return seen
        }
    }
    @JvmStatic
    fun barrierfull(tc: ThreadContext): Long {
        java.lang.invoke.VarHandle.fullFence()
        return 0L
    }
    @JvmStatic
    fun casattr(obj: SixModelObject?, classHandle: SixModelObject?,
            name: String?, expected: SixModelObject?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return obj!!.cas_attribute_boxed(tc, classHandle, name, expected, value)
    }
    @JvmStatic
    fun atomicbindattr(obj: SixModelObject?, classHandle: SixModelObject?,
            name: String?, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        obj!!.atomic_bind_attribute_boxed(tc, classHandle, name, value)
        return value
    }

    /* Asynchronousy operations. */

    private class AddToQueueTimerTask(
        private val queue: LinkedBlockingQueue<SixModelObject>,
        private val schedulee: SixModelObject?
    ) : TimerTask(), IIOCancelable {
        override fun run() {
            queue.add(schedulee!!)
        }

        override fun cancel(tc: ThreadContext) {
            cancel()
        }
    }
    @JvmStatic
    fun timer(queue: SixModelObject?, schedulee: SixModelObject?,
            timeout: Long, repeat: Long, handleType: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (queue !is ConcBlockingQueueInstance)
            throw ExceptionHandling.dieInternal(tc, "timer's first argument should have REPR ConcBlockingQueue")
        val tt = AddToQueueTimerTask(queue.queue, schedulee)
        if (repeat > 0)
            tc.gc.timer.scheduleAtFixedRate(tt, timeout, repeat)
        else
            tc.gc.timer.schedule(tt, timeout)
        /* XXX TODO: cancellation handle. */
        val handle = handleType!!.st.REPR.allocate(tc, handleType.st) as AsyncTaskInstance
        handle.handle = tt
        return handle
    }
    @JvmStatic
    fun permit(handle: SixModelObject?, channel: Long, permits: Long,
               tc: ThreadContext): SixModelObject? {
        // TODO Implement permit handling properly
        return handle
    }
    @JvmStatic
    fun cancel(handle: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val task = handle as AsyncTaskInstance
        val taskHandle = task.handle
        if (taskHandle is IIOCancelable) {
            taskHandle.cancel(tc)
        } else {
            throw ExceptionHandling.dieInternal(tc, "This handle does not support cancel")
        }
        return handle
    }
    @JvmStatic
    fun cancelnotify(handle: SixModelObject?, queue: SixModelObject?,
            schedulee: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val task = handle as AsyncTaskInstance
        if (queue !is ConcBlockingQueueInstance)
            throw ExceptionHandling.dieInternal(tc, "cancelnotify's second argument should have REPR ConcBlockingQueue")
        val taskHandle = task.handle
        if (taskHandle is IIOCancelable) {
            taskHandle.cancel(tc)
            queue.queue.add(schedulee)
        } else {
            throw ExceptionHandling.dieInternal(tc, "This handle does not support cancel")
        }
        return handle
    }

    /* Exception related. */
    @JvmStatic
    fun die_s_c(msg: String?, tc: ThreadContext) {
        // Construct exception object.
        val exType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
        val exObj = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
        exObj.message = msg
        exObj.category = ExceptionHandling.EX_CAT_CATCH.toLong()
        exObj.origin = tc.curFrame
        exObj.nativeTrace = Throwable().stackTrace
        ExceptionHandling.handlerDynamic(tc, ExceptionHandling.EX_CAT_CATCH.toLong(), true, exObj)
    }
    @JvmStatic
    fun throwcatdyn_c(category: Long, tc: ThreadContext) {
        val exType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
        val exObj = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
        exObj.origin = tc.curFrame
        exObj.category = category
        ExceptionHandling.handlerDynamic(tc, category, false, exObj)
    }
    @JvmStatic
    fun exception(tc: ThreadContext): SixModelObject? {
        val numHandlers = tc.handlers.size
        if (numHandlers > 0)
            return tc.handlers[numHandlers - 1].exObj
        else
            throw ExceptionHandling.dieInternal(tc, "Cannot get exception object outside of exception handler")
    }
    @JvmStatic
    fun getextype(obj: SixModelObject?, tc: ThreadContext): Long {
        if (obj is VMExceptionInstance)
            return obj.category
        else
            throw ExceptionHandling.dieInternal(tc, "getextype needs an object with VMException representation")
    }
    @JvmStatic
    fun setextype(obj: SixModelObject?, category: Long, tc: ThreadContext): Long {
        if (obj is VMExceptionInstance) {
            obj.category = category
            return category
        }
        else
            throw ExceptionHandling.dieInternal(tc, "setextype needs an object with VMException representation")
    }
    @JvmStatic
    fun getmessage(obj: SixModelObject?, tc: ThreadContext): String? {
        if (obj is VMExceptionInstance)
            return obj.message
        else
            throw ExceptionHandling.dieInternal(tc, "getmessage needs an object with VMException representation")
    }
    @JvmStatic
    fun setmessage(obj: SixModelObject?, msg: String?, tc: ThreadContext): String? {
        if (obj is VMExceptionInstance) {
            obj.message = msg
            return msg
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "setmessage needs an object with VMException representation")
        }
    }
    @JvmStatic
    fun getpayload(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is VMExceptionInstance)
            return obj.payload
        else
            throw ExceptionHandling.dieInternal(tc, "getpayload needs an object with VMException representation")
    }
    @JvmStatic
    fun setpayload(obj: SixModelObject?, payload: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (obj is VMExceptionInstance) {
            obj.payload = payload
            return payload
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "setpayload needs an object with VMException representation")
        }
    }
    @JvmStatic
    fun newexception(tc: ThreadContext): SixModelObject {
        val exType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
        val exObj: SixModelObject = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
        return exObj
    }
    @JvmStatic
    fun backtracestrings(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (obj is VMExceptionInstance) {
            val Array = tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType!!
            val Str = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
            val result = Array.st.REPR.allocate(tc, Array.st)

            val lines = ExceptionHandling.backtraceStrings(obj)
            for (i in lines.indices)
                result.bind_pos_boxed(tc, i.toLong(), box_s(lines[i], Str, tc))

            return result
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "backtracestring needs an object with VMException representation")
        }
    }
    @JvmStatic
    fun backtrace(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        var theObj = obj
        if (isnull(theObj) == 1L) {
            theObj = newexception(tc)
            (theObj as VMExceptionInstance).origin = tc.curFrame
            theObj.nativeTrace = Throwable().stackTrace
        }

        if (theObj is VMExceptionInstance) {
            val Array = tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType!!
            val Hash = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType!!
            val Str = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
            val Int = tc.frame.codeRef.staticInfo.compUnit.hllConfig.intBoxType
            val result = Array.st.REPR.allocate(tc, Array.st)

            for (te in ExceptionHandling.backtrace(theObj)) {
                if (te.frame!!.codeRef.staticInfo.isThunk)
                    continue
                val annots = Hash.st.REPR.allocate(tc, Hash.st)
                val file = te.mappedFile
                val line = te.mappedLine
                annots.bind_key_boxed(tc, "file", box_s(if (file == null) "" else file, Str, tc))
                annots.bind_key_boxed(tc, "line", box_i(if (line < 0) 1 else line.toLong(), Int, tc))
                val row = Hash.st.REPR.allocate(tc, Hash.st)
                row.bind_key_boxed(tc, "sub", te.frame!!.codeRef)
                row.bind_key_boxed(tc, "annotations", annots)
                result.push_boxed(tc, row)
            }

            return result
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "backtrace needs an object with VMException representation")
        }
    }
    @JvmStatic
    fun _throw_c(obj: SixModelObject?, tc: ThreadContext) {
        if (obj is VMExceptionInstance) {
            obj.origin = tc.curFrame
            obj.nativeTrace = Throwable().stackTrace
            ExceptionHandling.handlerDynamic(tc, obj.category, false, obj)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "throw needs an object with VMException representation")
        }
    }

    @JvmStatic
    fun _is_same_label(uwex: UnwindException, where: SixModelObject?, outerHandler: Long, tc: ThreadContext) {
        if ((uwex.category and ExceptionHandling.EX_CAT_LABELED.toLong()) == 0L)
            return

        if (uwex.payload!!.hashCode() == where!!.hashCode())
            return
        val vmex = newexception(tc) as VMExceptionInstance
        /* We're moving to the outside so we do not rethrow to us. */
        vmex.category = uwex.category
        vmex.payload = uwex.payload
        tc.frame.curHandler = outerHandler
        ExceptionHandling.handlerDynamic(tc, vmex.category, false, vmex)
    }
    @JvmStatic
    fun _rethrow_label(uwex: UnwindException, outerHandler: Long, tc: ThreadContext) {
        if ((uwex.category and ExceptionHandling.EX_CAT_LABELED.toLong()) == 0L)
            return

        /* We're moving to the outside so we do not rethrow to us. */
        val vmex = newexception(tc) as VMExceptionInstance
        vmex.category = uwex.category
        vmex.payload = uwex.payload
        tc.frame.curHandler = outerHandler
        ExceptionHandling.handlerDynamic(tc, vmex.category, false, vmex)
    }
    @JvmStatic
    fun rethrow_c(obj: SixModelObject?, tc: ThreadContext) {
        if (obj is VMExceptionInstance) {
            ExceptionHandling.handlerDynamic(tc, obj.category, false, obj)
        }
        else {
            throw ExceptionHandling.dieInternal(tc, "rethrow needs an object with VMException representation, got " +
                (if (obj == null) "null" else typeName(obj, tc) + " (repr " + reprname(obj, tc) + ")"))
        }
    }
    private val theResumer = ResumeException()
    @JvmStatic
    fun resume(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        throw theResumer
    }
    @JvmStatic
    fun _throwpayloadlex_c(category: Long, payload: SixModelObject?, tc: ThreadContext) {
        val exType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
        val exObj = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
        exObj.category = category
        exObj.origin = tc.curFrame
        /* On the exception, not only in the thread's slot: the handler that
         * finally takes this throw may run after other payload throws have
         * come and gone, and it is this exception's payload it wants. */
        exObj.payload = payload
        tc.lastPayload = payload
        ExceptionHandling.handlerLexical(tc, category, exObj, false)
    }
    @JvmStatic
    fun _throwpayloadlexcaller_c(category: Long, payload: SixModelObject?, tc: ThreadContext) {
        val exType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
        val exObj = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
        exObj.category = category
        exObj.origin = tc.curFrame
        exObj.payload = payload
        tc.lastPayload = payload
        ExceptionHandling.handlerLexical(tc, category, exObj, true)
    }
    @JvmStatic
    fun lastexpayload(tc: ThreadContext): SixModelObject? {
        return tc.lastPayload
    }

    /* compatibility shims for next bootstrap TODO */
    @JvmStatic
    fun die_s(msg: String?, tc: ThreadContext): String? {
        try {
            die_s_c(msg, tc)
        } catch (sse: SaveStackException) {
            ExceptionHandling.dieInternal(tc, "control operator crossed continuation barrier")
        }
        return result_s(tc.frame)
    }
    @JvmStatic
    fun throwcatdyn(category: Long, tc: ThreadContext): SixModelObject? {
        try {
            throwcatdyn_c(category, tc)
        } catch (sse: SaveStackException) {
            ExceptionHandling.dieInternal(tc, "control operator crossed continuation barrier")
        }
        return result_o(tc.frame)
    }
    @JvmStatic
    fun _throw(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        try {
            _throw_c(obj, tc)
        } catch (sse: SaveStackException) {
            ExceptionHandling.dieInternal(tc, "control operator crossed continuation barrier")
        }
        return result_o(tc.frame)
    }
    @JvmStatic
    fun rethrow(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        try {
            rethrow_c(obj, tc)
        } catch (sse: SaveStackException) {
            ExceptionHandling.dieInternal(tc, "control operator crossed continuation barrier")
        }
        return result_o(tc.frame)
    }

    /* HLL configuration and compiler related options. */
    @JvmStatic
    fun sethllconfig(language: String, configHash: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val config = tc.gc.getHLLConfigFor(language)
        if (configHash!!.exists_key(tc, "int_box") != 0L)
            config.intBoxType = configHash.at_key_boxed(tc, "int_box")
        if (configHash.exists_key(tc, "num_box") != 0L)
            config.numBoxType = configHash.at_key_boxed(tc, "num_box")
        if (configHash.exists_key(tc, "str_box") != 0L)
            config.strBoxType = configHash.at_key_boxed(tc, "str_box")
        if (configHash.exists_key(tc, "list") != 0L)
            config.listType = configHash.at_key_boxed(tc, "list")
        if (configHash.exists_key(tc, "hash") != 0L)
            config.hashType = configHash.at_key_boxed(tc, "hash")
        if (configHash.exists_key(tc, "slurpy_array") != 0L)
            config.slurpyArrayType = configHash.at_key_boxed(tc, "slurpy_array")
        if (configHash.exists_key(tc, "slurpy_hash") != 0L)
            config.slurpyHashType = configHash.at_key_boxed(tc, "slurpy_hash")
        if (configHash.exists_key(tc, "bind_error") != 0L)
            config.bindError = configHash.at_key_boxed(tc, "bind_error")
        if (configHash.exists_key(tc, "array_iter") != 0L)
            config.arrayIteratorType = configHash.at_key_boxed(tc, "array_iter")
        if (configHash.exists_key(tc, "hash_iter") != 0L)
            config.hashIteratorType = configHash.at_key_boxed(tc, "hash_iter")
        if (configHash.exists_key(tc, "foreign_type_int") != 0L)
            config.foreignTypeInt = configHash.at_key_boxed(tc, "foreign_type_int")
        if (configHash.exists_key(tc, "foreign_type_num") != 0L)
            config.foreignTypeNum = configHash.at_key_boxed(tc, "foreign_type_num")
        if (configHash.exists_key(tc, "foreign_type_str") != 0L)
            config.foreignTypeStr = configHash.at_key_boxed(tc, "foreign_type_str")
        if (configHash.exists_key(tc, "foreign_transform_int") != 0L)
            config.foreignTransformInt = configHash.at_key_boxed(tc, "foreign_transform_int")
        /* NOTE: the transform_str/num crossed key lookups below are preserved
         * from the Java original. */
        if (configHash.exists_key(tc, "foreign_transform_str") != 0L)
            config.foreignTransformNum = configHash.at_key_boxed(tc, "foreign_transform_num")
        if (configHash.exists_key(tc, "foreign_transform_num") != 0L)
            config.foreignTransformStr = configHash.at_key_boxed(tc, "foreign_transform_str")
        if (configHash.exists_key(tc, "foreign_transform_array") != 0L)
            config.foreignTransformArray = configHash.at_key_boxed(tc, "foreign_transform_array")
        if (configHash.exists_key(tc, "foreign_transform_hash") != 0L)
            config.foreignTransformHash = configHash.at_key_boxed(tc, "foreign_transform_hash")
        if (configHash.exists_key(tc, "foreign_transform_code") != 0L)
            config.foreignTransformCode = configHash.at_key_boxed(tc, "foreign_transform_code")
        if (configHash.exists_key(tc, "foreign_transform_any") != 0L)
            config.foreignTransformAny = configHash.at_key_boxed(tc, "foreign_transform_any")
        if (configHash.exists_key(tc, "null_value") != 0L)
            config.nullValue = configHash.at_key_boxed(tc, "null_value")
        if (configHash.exists_key(tc, "true_value") != 0L)
            config.trueValue = configHash.at_key_boxed(tc, "true_value")
        if (configHash.exists_key(tc, "false_value") != 0L)
            config.falseValue = configHash.at_key_boxed(tc, "false_value")
        if (configHash.exists_key(tc, "exit_handler") != 0L)
            config.exitHandler = configHash.at_key_boxed(tc, "exit_handler")
        if (configHash.exists_key(tc, "int_lex_ref") != 0L)
            config.intLexRef = configHash.at_key_boxed(tc, "int_lex_ref")
        if (configHash.exists_key(tc, "uint_lex_ref") != 0L)
            config.uintLexRef = configHash.at_key_boxed(tc, "uint_lex_ref")
        if (configHash.exists_key(tc, "num_lex_ref") != 0L)
            config.numLexRef = configHash.at_key_boxed(tc, "num_lex_ref")
        if (configHash.exists_key(tc, "str_lex_ref") != 0L)
            config.strLexRef = configHash.at_key_boxed(tc, "str_lex_ref")
        if (configHash.exists_key(tc, "int_attr_ref") != 0L)
            config.intAttrRef = configHash.at_key_boxed(tc, "int_attr_ref")
        if (configHash.exists_key(tc, "uint_attr_ref") != 0L)
            config.uintAttrRef = configHash.at_key_boxed(tc, "uint_attr_ref")
        if (configHash.exists_key(tc, "num_attr_ref") != 0L)
            config.numAttrRef = configHash.at_key_boxed(tc, "num_attr_ref")
        if (configHash.exists_key(tc, "str_attr_ref") != 0L)
            config.strAttrRef = configHash.at_key_boxed(tc, "str_attr_ref")
        if (configHash.exists_key(tc, "int_pos_ref") != 0L)
            config.intPosRef = configHash.at_key_boxed(tc, "int_pos_ref")
        if (configHash.exists_key(tc, "uint_pos_ref") != 0L)
            config.uintPosRef = configHash.at_key_boxed(tc, "uint_pos_ref")
        if (configHash.exists_key(tc, "num_pos_ref") != 0L)
            config.numPosRef = configHash.at_key_boxed(tc, "num_pos_ref")
        if (configHash.exists_key(tc, "str_pos_ref") != 0L)
            config.strPosRef = configHash.at_key_boxed(tc, "str_pos_ref")
        if (configHash.exists_key(tc, "int_multidim_ref") != 0L)
            config.intMultidimRef = configHash.at_key_boxed(tc, "int_multidim_ref")
        if (configHash.exists_key(tc, "uint_multidim_ref") != 0L)
            config.uintMultidimRef = configHash.at_key_boxed(tc, "uint_multidim_ref")
        if (configHash.exists_key(tc, "num_multidim_ref") != 0L)
            config.numMultidimRef = configHash.at_key_boxed(tc, "num_multidim_ref")
        if (configHash.exists_key(tc, "str_multidim_ref") != 0L)
            config.strMultidimRef = configHash.at_key_boxed(tc, "str_multidim_ref")
        if (configHash.exists_key(tc, "lexical_handler_not_found_error") != 0L)
            config.lexicalHandlerNotFoundError = configHash.at_key_boxed(tc, "lexical_handler_not_found_error")

        /* The dispatchers this language wants the language-sensitive boot
         * dispatchers to hand over to; named, not code objects. */
        if (configHash.exists_key(tc, "call_dispatcher") != 0L)
            config.callDispatcher = unbox_s(configHash.at_key_boxed(tc, "call_dispatcher"), tc)
        if (configHash.exists_key(tc, "method_call_dispatcher") != 0L)
            config.methodCallDispatcher = unbox_s(configHash.at_key_boxed(tc, "method_call_dispatcher"), tc)
        if (configHash.exists_key(tc, "find_method_dispatcher") != 0L)
            config.findMethodDispatcher = unbox_s(configHash.at_key_boxed(tc, "find_method_dispatcher"), tc)
        if (configHash.exists_key(tc, "hllize_dispatcher") != 0L)
            config.hllizeDispatcher = unbox_s(configHash.at_key_boxed(tc, "hllize_dispatcher"), tc)
        if (configHash.exists_key(tc, "istype_dispatcher") != 0L)
            config.istypeDispatcher = unbox_s(configHash.at_key_boxed(tc, "istype_dispatcher"), tc)
        if (configHash.exists_key(tc, "isinvokable_dispatcher") != 0L)
            config.isinvokableDispatcher = unbox_s(configHash.at_key_boxed(tc, "isinvokable_dispatcher"), tc)
        if (configHash.exists_key(tc, "resume_error_dispatcher") != 0L)
            config.resumeErrorDispatcher = unbox_s(configHash.at_key_boxed(tc, "resume_error_dispatcher"), tc)
        if (configHash.exists_key(tc, "method_not_found_error") != 0L)
            config.methodNotFoundError = configHash.at_key_boxed(tc, "method_not_found_error")
        return configHash
    }
    @JvmStatic
    fun getcomp(name: String, tc: ThreadContext): SixModelObject? {
        return tc.gc.compilerRegistry.get(name)
    }
    @JvmStatic
    fun bindcomp(name: String, comp: SixModelObject?, tc: ThreadContext): SixModelObject? {
        tc.gc.compilerRegistry.put(name, comp)
        return comp
    }
    @JvmStatic
    fun getcurhllsym(name: String, tc: ThreadContext): SixModelObject? {
        val hllName = tc.frame.codeRef.staticInfo.compUnit.hllName()
        val hllSyms = tc.gc.hllSyms.get(hllName)
        return if (hllSyms == null) null else hllSyms.get(name)
    }
    @JvmStatic
    fun bindcurhllsym(name: String, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val hllName = tc.frame.codeRef.staticInfo.compUnit.hllName()
        var hllSyms = tc.gc.hllSyms.get(hllName)
        if (hllSyms == null) {
            hllSyms = HashMap()
            tc.gc.hllSyms.put(hllName, hllSyms)
        }
        hllSyms.put(name, value)
        return value
    }
    @JvmStatic
    fun gethllsym(hllName: String?, name: String, tc: ThreadContext): SixModelObject? {
        val hllSyms = tc.gc.hllSyms.get(hllName)
        return if (hllSyms == null) null else hllSyms.get(name)
    }
    @JvmStatic
    fun bindhllsym(hllName: String?, name: String, value: SixModelObject?, tc: ThreadContext): SixModelObject? {
        var hllSyms = tc.gc.hllSyms.get(hllName)
        if (hllSyms == null) {
            hllSyms = HashMap()
            tc.gc.hllSyms.put(hllName!!, hllSyms)
        }
        hllSyms.put(name, value)
        return value
    }
    @JvmStatic
    fun loadbytecode(filename: String?, tc: ThreadContext): String? {
        LibraryLoader.load(tc, filename)
        return filename
    }
    @JvmStatic
    fun loadbytecodebuffer(buffer: SixModelObject?, tc: ThreadContext): SixModelObject? {
        if (buffer is VMArrayInstance_i8)
            LibraryLoader.load(tc, buffer.slots)
        else if (buffer is VMArrayInstance_u8)
            LibraryLoader.load(tc, buffer.slots)
        else
            throw ExceptionHandling.dieInternal(tc, "loadbytecodebuffer expects a uint8 or int8 VMArray")
        return buffer
    }
    @JvmStatic
    fun settypehll(type: SixModelObject?, language: String, tc: ThreadContext): SixModelObject? {
        type!!.st.hllOwner = tc.gc.getHLLConfigFor(language)
        return type
    }
    @JvmStatic
    fun settypehllrole(type: SixModelObject?, role: Long, tc: ThreadContext): SixModelObject? {
        type!!.st.hllRole = role
        return type
    }
    @JvmStatic
    fun hllize(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val wanted = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        if (isnull(obj) == 0L && obj!!.stInitialized && obj.st.hllOwner === wanted)
            return obj
        else
            return hllizeInternal(obj, wanted, tc)
    }
    @JvmStatic
    fun hllizefor(obj: SixModelObject?, language: String, tc: ThreadContext): SixModelObject? {
        val wanted = tc.gc.getHLLConfigFor(language)
        if (isnull(obj) == 0L && obj!!.stInitialized && obj.st.hllOwner === wanted)
            return obj
        else
            return hllizeInternal(obj, wanted, tc)
    }
    @JvmStatic
    fun hllbool(value: Long, tc: ThreadContext): SixModelObject? {
        val hllConfig = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        return if (value != 0L) hllConfig.trueValue else hllConfig.falseValue
    }
    @JvmStatic
    fun hllboolfor(value: Long, language: String, tc: ThreadContext): SixModelObject? {
        val hllConfig = tc.gc.getHLLConfigFor(language)
        return if (value != 0L) hllConfig.trueValue else hllConfig.falseValue
    }
    private fun hllizeInternal(obj: SixModelObject?, wanted: HLLConfig, tc: ThreadContext): SixModelObject? {
        /* Map nulls to the language's designated null value. */
        if (isnull(obj) == 1L)
            return wanted.nullValue

        /* An internal carrier with no STable (EvalResult and friends) has no
         * HLL identity to map; on MoarVM every object has an STable and these
         * fall through as role NONE, so pass them through here too. */
        if (!obj!!.stInitialized)
            return obj

        /* Go by what role the object plays. */
        when (obj!!.st.hllRole.toInt()) {
            /* For the boxed-native roles, a type object cannot be unboxed;
             * MoarVM's MVM_hll_map answers with the target language's foreign
             * type itself there, so mirror that. */
            HLLConfig.ROLE_INT -> {
                if (isnull(wanted.foreignTypeInt) == 0L) {
                    return if (isconcrete_nd(obj, tc) == 1L)
                        box_i(obj.get_int(tc), wanted.foreignTypeInt, tc)
                    else wanted.foreignTypeInt
                }
                else if (isnull(wanted.foreignTransformInt) == 0L) {
                    throw RuntimeException("foreign_transform_int NYI")
                }
                else {
                    return obj
                }
            }
            HLLConfig.ROLE_NUM -> {
                if (isnull(wanted.foreignTypeNum) == 0L) {
                    return if (isconcrete_nd(obj, tc) == 1L)
                        box_n(obj.get_num(tc), wanted.foreignTypeNum, tc)
                    else wanted.foreignTypeNum
                }
                else if (isnull(wanted.foreignTransformNum) == 0L) {
                    throw RuntimeException("foreign_transform_num NYI")
                }
                else {
                    return obj
                }
            }
            HLLConfig.ROLE_STR -> {
                if (isnull(wanted.foreignTypeStr) == 0L) {
                    return if (isconcrete_nd(obj, tc) == 1L)
                        box_s(obj.get_str(tc), wanted.foreignTypeStr, tc)
                    else wanted.foreignTypeStr
                }
                else if (isnull(wanted.foreignTransformStr) == 0L) {
                    throw RuntimeException("foreign_transform_str NYI")
                }
                else {
                    return obj
                }
            }
            HLLConfig.ROLE_ARRAY -> {
                if (isnull(wanted.foreignTransformArray) == 0L) {
                    invokeDirect(tc, wanted.foreignTransformArray,
                        invocantCallSite, arrayOf<Any?>(obj))
                    return result_o(tc.frame)
                }
                else {
                    return obj
                }
            }
            HLLConfig.ROLE_HASH -> {
                if (isnull(wanted.foreignTransformHash) == 0L) {
                    invokeDirect(tc, wanted.foreignTransformHash,
                        invocantCallSite, arrayOf<Any?>(obj))
                    return result_o(tc.frame)
                }
                else {
                    return obj
                }
            }
            HLLConfig.ROLE_CODE -> {
                if (isnull(wanted.foreignTransformCode) == 0L) {
                    invokeDirect(tc, wanted.foreignTransformCode,
                        invocantCallSite, arrayOf<Any?>(obj))
                    return result_o(tc.frame)
                }
                else {
                    return obj
                }
            }
            else -> {
                if (isnull(wanted.foreignTransformAny) == 0L) {
                    invokeDirect(tc, wanted.foreignTransformAny,
                        invocantCallSite, arrayOf<Any?>(obj))
                    return result_o(tc.frame)
                }
                else {
                    return obj
                }
            }
        }
    }

    /* NFA operations. */
    @JvmStatic
    fun nfafromstatelist(states: SixModelObject?, nfaType: SixModelObject?, tc: ThreadContext): SixModelObject {
        /* Create NFA object. */
        val nfa = nfaType!!.st.REPR.allocate(tc, nfaType.st) as NFAInstance

        /* The first state entry is the fates list. */
        nfa.fates = states!!.at_pos_boxed(tc, 0)

        /* Go over the rest and convert to the NFA. */
        val numStates = states.elems(tc).toInt() - 1
        nfa.numStates = numStates
        @Suppress("UNCHECKED_CAST")
        nfa.states = arrayOfNulls<Array<NFAStateInfo>?>(numStates) as Array<Array<NFAStateInfo>>
        for (i in 0 until numStates) {
            val edgeInfo = states.at_pos_boxed(tc, (i + 1).toLong())
            val elems = edgeInfo!!.elems(tc).toInt()
            val edges = elems / 3
            var curEdge = 0
            @Suppress("UNCHECKED_CAST")
            nfa.states!![i] = arrayOfNulls<NFAStateInfo>(edges) as Array<NFAStateInfo>
            var j = 0
            while (j < elems) {
                val act = smart_intify(edgeInfo.at_pos_boxed(tc, j.toLong()), tc).toInt()
                val to = smart_intify(edgeInfo.at_pos_boxed(tc, (j + 2).toLong()), tc).toInt()

                val info = NFAStateInfo()
                nfa.states!![i]!![curEdge] = info
                info.act = act
                info.to = to

                when (act and 0xff) {
                NFA.EDGE_FATE, NFA.EDGE_CODEPOINT_LL, NFA.EDGE_CODEPOINT,
                NFA.EDGE_CODEPOINT_NEG, NFA.EDGE_CHARCLASS, NFA.EDGE_CHARCLASS_NEG ->
                    info.argI = smart_intify(edgeInfo.at_pos_boxed(tc, (j + 1).toLong()), tc).toInt()
                NFA.EDGE_CHARLIST, NFA.EDGE_CHARLIST_NEG ->
                    info.argS = edgeInfo.at_pos_boxed(tc, (j + 1).toLong())!!.get_str(tc)
                NFA.EDGE_CODEPOINT_I_LL, NFA.EDGE_CODEPOINT_I, NFA.EDGE_CODEPOINT_I_NEG,
                NFA.EDGE_CHARRANGE, NFA.EDGE_CHARRANGE_NEG -> {
                    val arg = edgeInfo.at_pos_boxed(tc, (j + 1).toLong())
                    info.argLc = smart_intify(arg!!.at_pos_boxed(tc, 0), tc).toInt().toChar()
                    info.argUc = smart_intify(arg.at_pos_boxed(tc, 1), tc).toInt().toChar()
                }
                }

                curEdge++
                j += 3
            }
        }

        return nfa
    }

    @JvmStatic
    fun nfarunproto(nfa: SixModelObject?, target: String?, pos: Long, tc: ThreadContext): SixModelObject {
        /* Run the NFA. */
        val fates = runNFA(tc, nfa as NFAInstance, target!!, pos)

        /* Copy results into an RIA. */
        val BOOTIntArray = tc.gc.BOOTIntArray!!
        val fateRes = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st)
        fateRes.set_elems(tc, fates.size.toLong())
        for (i in fates.indices) {
            tc.nativeI = fates[i].toLong()
            fateRes.bind_pos_native(tc, i.toLong())
        }

        return fateRes
    }
    @JvmStatic
    fun nfarunalt(nfa: SixModelObject?, target: String?, pos: Long,
            bstack: SixModelObject?, cstack: SixModelObject?, marks: SixModelObject?, tc: ThreadContext): SixModelObject? {
        /* Run the NFA. */
        val fates = runNFA(tc, nfa as NFAInstance, target!!, pos)

        /* Push the results onto the bstack. */
        val caps = if (isnull(cstack) == 1L || cstack is TypeObject) 0L else cstack!!.elems(tc)
        val curLen = bstack!!.elems(tc)
        bstack.set_elems(tc, curLen + (4 * fates.size))
        for (i in fates.indices) {
            marks!!.at_pos_native(tc, fates[i].toLong())
            bstack.bind_pos_native(tc, curLen + (4 * i) + 0)
            tc.nativeI = pos
            bstack.bind_pos_native(tc, curLen + (4 * i) + 1)
            tc.nativeI = 0
            bstack.bind_pos_native(tc, curLen + (4 * i) + 2)
            tc.nativeI = caps
            bstack.bind_pos_native(tc, curLen + (4 * i) + 3)
        }

        return nfa
    }

    /* The NFA evaluator. */
    private fun runNFA(tc: ThreadContext, nfa: NFAInstance, target: String, pos: Long): IntArray {
        var curPos = pos
        val eos = target.length
        var gen = 1

        /* Allocate a "done states" array. */
        val numStates = nfa.numStates
        val done = IntArray(numStates + 1)
        val origPos = pos

        /* Clear out other re-used arrays. */
        val fates = tc.fates
        var curst = tc.curst
        var nextst = tc.nextst
        curst.clear()
        nextst.clear()
        fates.clear()

        val longlit = tc.curlonglit  // needs proper sizing to # of alternatives
        var usedlonglit = 0          // lazy initialization highwater

        nextst.add(1)
        while (!nextst.isEmpty && curPos <= eos) {
            /* Translation of:
             *    my @curst := @nextst;
             *    @nextst := [];
             * But avoids an extra allocation per offset. */
            val temp = curst
            curst = nextst
            temp.clear()
            nextst = temp

            /* Save how many fates we have before this position is considered. */
            var prevFates = fates.size

            while (!curst.isEmpty) {
                val st = curst.popInt()
                if (st <= numStates) {
                    if (done[st] == gen)
                        continue
                    done[st] = gen
                }

                val edgeInfo = nfa.states!![st - 1]!!
                for (i in edgeInfo.indices) {
                    var act = edgeInfo[i]!!.act
                    val to = edgeInfo[i]!!.to

                    if (act <= NFA.EDGE_EPSILON) {
                        if (act < 0) {
                            act = act and 0xff
                        }
                        else if (act == NFA.EDGE_FATE) {
                            /* Crossed a fate edge. Check if we already saw this, and
                             * if so bump the entry we already saw. */
                            var arg = edgeInfo[i]!!.argI
                            var foundFate = false
                            arg = arg and 0xffffff   // can go away after reboostrap?
                            for (j in 0 until fates.size) {
                                if (foundFate)
                                    fates.set(j - 1, fates.getInt(j))
                                if (fates.getInt(j) == arg) {
                                    foundFate = true
                                    if (j < prevFates)
                                        prevFates--
                                }
                            }
                            if (arg < usedlonglit)
                                arg = (arg - (longlit[arg] shl 24)).toInt()
                            if (foundFate)
                                fates.set(fates.size - 1, arg)
                            else
                                fates.add(arg)
                            continue
                        }
                        else if (act == NFA.EDGE_EPSILON && to <= numStates && done[to] != gen) {
                            if (to != 0)
                                curst.add(to)
                            continue
                        }
                    }

                    if (curPos >= eos) {
                        /* Can't match, so drop state. */
                        continue
                    }

                    when (act) {
                        NFA.EDGE_CODEPOINT -> {
                            val arg = edgeInfo[i]!!.argI.toChar()
                            if (target[curPos.toInt()] == arg)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CODEPOINT_LL -> {
                            val arg = edgeInfo[i]!!.argI.toChar()
                            if (target[curPos.toInt()] == arg) {
                                val fate = (edgeInfo[i]!!.act shr 8) and 0xfffff  /* act is probably signed 32 bits */
                                nextst.add(to)
                                while (usedlonglit <= fate)
                                    longlit[usedlonglit++] = 0
                                longlit[fate] = curPos - origPos + 1
                            }
                            continue
                        }
                        NFA.EDGE_CODEPOINT_NEG -> {
                            val arg = edgeInfo[i]!!.argI.toChar()
                            if (target[curPos.toInt()] != arg)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARCLASS -> {
                            if (iscclass(edgeInfo[i]!!.argI.toLong(), target, curPos) != 0L)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARCLASS_NEG -> {
                            if (iscclass(edgeInfo[i]!!.argI.toLong(), target, curPos) == 0L)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARLIST -> {
                            val arg = edgeInfo[i]!!.argS
                            if (arg!!.indexOf(target[curPos.toInt()]) >= 0)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARLIST_NEG -> {
                            val arg = edgeInfo[i]!!.argS
                            if (arg!!.indexOf(target[curPos.toInt()]) < 0)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CODEPOINT_I -> {
                            val ucArg = edgeInfo[i]!!.argUc
                            val lcArg = edgeInfo[i]!!.argLc
                            val ord = target[curPos.toInt()]
                            if (ord == lcArg || ord == ucArg)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CODEPOINT_I_LL -> {
                            val ucArg = edgeInfo[i]!!.argUc
                            val lcArg = edgeInfo[i]!!.argLc
                            val ord = target[curPos.toInt()]
                            if (ord == lcArg || ord == ucArg) {
                                val fate = (edgeInfo[i]!!.act shr 8) and 0xfffff  /* act is probably signed 32 bits */
                                nextst.add(to)
                                while (usedlonglit <= fate)
                                    longlit[usedlonglit++] = 0
                                longlit[fate] = curPos - origPos + 1
                            }
                            continue
                        }
                        NFA.EDGE_CODEPOINT_I_NEG -> {
                            val ucArg = edgeInfo[i]!!.argUc
                            val lcArg = edgeInfo[i]!!.argLc
                            val ord = target[curPos.toInt()]
                            if (ord != lcArg && ord != ucArg)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARRANGE -> {
                            val ucArg = edgeInfo[i]!!.argUc
                            val lcArg = edgeInfo[i]!!.argLc
                            val ord = target[curPos.toInt()]
                            if (ord >= lcArg && ord <= ucArg)
                                nextst.add(to)
                            continue
                        }
                        NFA.EDGE_CHARRANGE_NEG -> {
                            val ucArg = edgeInfo[i]!!.argUc
                            val lcArg = edgeInfo[i]!!.argLc
                            val ord = target[curPos.toInt()]
                            if (ord < lcArg || ord > ucArg)
                                nextst.add(to)
                            continue
                        }
                    }
                }
            }

            /* Move to next character and generation. */
            curPos++
            gen++

            /* If we got multiple fates at this offset, sort them by the
             * declaration order (represented by the fate number). In the
             * future, we'll want to factor in longest literal prefix too. */
            val charFates = fates.size - prevFates
            if (charFates > 1) {
                if (charFates == 2) {
                    val a = fates.getInt(prevFates)
                    val b = fates.getInt(prevFates + 1)
                    if (b > a) {
                        fates.set(prevFates, b)
                        fates.set(prevFates + 1, a)
                    }
                }
                else {
                    val charFateList: IntList = fates.subList(prevFates, fates.size)
                    charFateList.sort(IntComparators.OPPOSITE_COMPARATOR)
                }
            }
        }

        /* strip any literal lengths, leaving only fates */
        val result = IntArray(fates.size)
        if (usedlonglit > 0) {
            for (i in 0 until fates.size)
                result[i] = fates.getInt(i) and 0xffffff
        }
        else {
            fates.getElements(0, result, 0, fates.size)
        }
        return result
    }

    /* Regex engine mark stack operations. */
    @JvmStatic
    fun rxmark(bstack: SixModelObject?, mark: Long, pos: Long, rep: Long, tc: ThreadContext) {
        val elems = bstack!!.elems(tc)

        val caps: Long
        if (elems > 0) {
            bstack.at_pos_native(tc, elems - 1)
            caps = tc.nativeI
        }
        else {
            caps = 0
        }

        tc.nativeI = mark
        bstack.push_native(tc)
        tc.nativeI = pos
        bstack.push_native(tc)
        tc.nativeI = rep
        bstack.push_native(tc)
        tc.nativeI = caps
        bstack.push_native(tc)
    }

    @JvmStatic
    fun rxpeek(bstack: SixModelObject?, mark: Long, tc: ThreadContext): Long {
        var ptr = bstack!!.elems(tc)
        while (ptr >= 0) {
            bstack.at_pos_native(tc, ptr)
            if (tc.nativeI == mark)
                break
            ptr -= 4
        }
        return ptr
    }

    @JvmStatic
    fun rxcommit(bstack: SixModelObject?, mark: Long, tc: ThreadContext) {
        var ptr = bstack!!.elems(tc)
        val caps: Long
        if (ptr > 0) {
            bstack.at_pos_native(tc, ptr - 1)
            caps = tc.nativeI
        }
        else {
            caps = 0
        }

        while (ptr >= 0) {
            bstack.at_pos_native(tc, ptr)
            if (tc.nativeI == mark)
                break
            ptr -= 4
        }

        bstack.set_elems(tc, ptr)

        if (caps > 0) {
            if (ptr > 0) {
                /* top mark frame is an autofail frame, reuse it to hold captures */
                bstack.at_pos_native(tc, ptr - 3)
                if (tc.nativeI < 0) {
                    tc.nativeI = caps
                    bstack.bind_pos_native(tc, ptr - 1)
                }
            }

            /* push a new autofail frame onto bstack to hold the captures */
            tc.nativeI = 0
            bstack.push_native(tc)
            tc.nativeI = -1
            bstack.push_native(tc)
            tc.nativeI = 0
            bstack.push_native(tc)
            tc.nativeI = caps
            bstack.push_native(tc)
        }
    }

    /* Coercions. */
    @JvmStatic
    fun coerce_s2i(`in`: String?): Long {
        try {
            return java.lang.Long.parseLong(`in`)
        }
        catch (e: NumberFormatException) {
            return 0
        }
    }
    @JvmStatic
    fun coerce_s2n(`in`: String?): Double {
        var str = `in`!!
        try {
            // remove valid underscores
            str = str.replace(Regex("(?<=\\d)_+(?=\\d)"), "")
            // replace unicode minus U+2212 with ascii version
            str = str.replace(Regex("−"), "-")
            return java.lang.Double.parseDouble(str)
        }
        catch (e: NumberFormatException) {
            if (str == "Inf")
                return Double.POSITIVE_INFINITY
            if (str == "+Inf")
                return Double.POSITIVE_INFINITY
            if (str == "-Inf")
                return Double.NEGATIVE_INFINITY
            if (str == "NaN")
                return Double.NaN
            return 0.0
        }
    }
    @JvmStatic
    fun coerce_i2s(`in`: Long): String {
        return `in`.toString()
    }
    @JvmStatic
    fun coerce_u2s(`in`: Long): String {
        return java.lang.Long.toUnsignedString(`in`)
    }
    @JvmStatic
    fun coerce_n2s(`in`: Double): String {
        if (`in` == `in`.toLong().toDouble()) {
            if (`in` == 0.0 && `in`.toString() == "-0.0") {
                return "-0"
            }
            return `in`.toLong().toString()
        }
        else {
            if (`in` == Double.POSITIVE_INFINITY)
                return "Inf"
            if (`in` == Double.NEGATIVE_INFINITY)
                return "-Inf"
            if (`in` != `in`)
                return "NaN"
            return `in`.toString()
        }
    }

    @JvmStatic
    fun coerce_n2i(`in`: Double): Long {
        if (`in` == Double.POSITIVE_INFINITY ||
            `in` == Double.NEGATIVE_INFINITY ||
            `in` != `in`) {
            return Long.MIN_VALUE
        }
        else {
            return `in`.toLong()
        }
    }

    @JvmStatic
    fun coerce_i2n(`in`: Long): Double {
        return `in`.toDouble()
    }

    /* Long literal workaround. */
    @JvmStatic
    fun join_literal(parts: Array<String>): String {
        val retval = StringBuilder(parts.size * 65535)
        for (i in parts.indices)
            retval.append(parts[i])
        return retval.toString()
    }

    /* Big integer operations. */
    private fun getBI(tc: ThreadContext, obj: SixModelObject?): BigInteger {
        if (obj is P6bigintInstance)
            return obj.value!!
        return getBI(tc, obj, obj!!.st.WHAT)
    }

    private fun getBI(tc: ThreadContext, obj: SixModelObject?, type: SixModelObject?): BigInteger {
        if (obj is P6bigintInstance)
            return obj.value!!

        var hint = 0
        if (obj!!.st.REPRData != null) {
            hint = (obj.st.REPRData as P6OpaqueREPRData).unboxIntSlot
        }
        if (hint < 0 && type!!.st.REPRData != null) {
            hint = (type.st.REPRData as P6OpaqueREPRData).unboxIntSlot
        }

        hint = if (hint < 0) 0 else hint

        try {
            obj.get_attribute_native(tc, null, null, hint.toLong())
        } catch (rte: RuntimeException) {
            // we couldn't get native, let's just getBI for the slot hinted at, with the type for that hint
            // XXX: type.st.REPRData could theoretically be null here - it shouldn't be, because if it was
            // we should already have handled a P6bigint successfully in the try above.
            val innerType = (type!!.st.REPRData as P6OpaqueREPRData).flattenedSTables!![hint]!!.WHAT
            tc.nativeJ = getBI(tc, obj.get_attribute_boxed(tc, null, null, hint.toLong()), innerType)
        }
        return tc.nativeJ as BigInteger
    }

    private fun makeBI(tc: ThreadContext, type: SixModelObject?, value: BigInteger): SixModelObject {
        val res = type!!.st.REPR.allocate(tc, type.st)
        if (res is P6bigintInstance) {
            res.value = value
        }
        else {
            var hint = (type.st.REPRData as P6OpaqueREPRData).unboxIntSlot
            hint = if (hint < 0) 0 else hint
            tc.nativeJ = value
            try {
                res.bind_attribute_native(tc, null, null, hint.toLong())
            } catch (rte: RuntimeException) {
                // we couldn't bind native, let's just makeBI for the slot hinted at, with the type for that hint
                val innerType = (type.st.REPRData as P6OpaqueREPRData).flattenedSTables!![hint]!!.WHAT
                res.bind_attribute_boxed(tc, null, null, hint.toLong(), makeBI(tc, innerType, value))
            }
        }
        return res
    }

    @JvmStatic
    fun fromstr_I(str: String?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, BigInteger(str))
    }

    @JvmStatic
    fun fromI_I(value: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, BigInteger(getBI(tc, value).toByteArray()))
    }

    @JvmStatic
    fun tostr_I(value: SixModelObject?, tc: ThreadContext): String {
        return getBI(tc, value).toString()
    }

    @JvmStatic
    fun base_I(value: SixModelObject?, radix: Long, tc: ThreadContext): String {
        return getBI(tc, value).toString(radix.toInt()).uppercase()
    }

    @JvmStatic
    fun isbig_I(value: SixModelObject?, tc: ThreadContext): Long {
        /* Check if it needs more bits than a long can offer; note that
         * bitLength excludes sign considerations, thus 31 rather than
         * 32. */
        return if (getBI(tc, value).bitLength() > 31) 1 else 0
    }

    @JvmStatic
    fun fromnum_I(num: Double, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, BigDecimal.valueOf(num).toBigInteger())
    }

    @JvmStatic
    fun tonum_I(value: SixModelObject?, tc: ThreadContext): Double {
        return getBI(tc, value).toDouble()
    }

    @JvmStatic
    fun bool_I(a: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(BigInteger.ZERO) == 0) 0 else 1
    }

    @JvmStatic
    fun cmp_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return getBI(tc, a).compareTo(getBI(tc, b)).toLong()
    }

    @JvmStatic
    fun iseq_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) == 0) 1 else 0
    }

    @JvmStatic
    fun isne_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) == 0) 0 else 1
    }

    @JvmStatic
    fun islt_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) < 0) 1 else 0
    }

    @JvmStatic
    fun isle_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) <= 0) 1 else 0
    }

    @JvmStatic
    fun isgt_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) > 0) 1 else 0
    }

    @JvmStatic
    fun isge_I(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Long {
        return if (getBI(tc, a).compareTo(getBI(tc, b)) >= 0) 1 else 0
    }

    @JvmStatic
    fun add_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a, type).add(getBI(tc, b, type)))
    }

    @JvmStatic
    fun sub_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a, type).subtract(getBI(tc, b, type)))
    }

    @JvmStatic
    fun mul_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a, type).multiply(getBI(tc, b, type)))
    }

    @JvmStatic
    fun div_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val dividend = getBI(tc, a, type)
        val divisor = getBI(tc, b, type)
        val dividendSign = dividend.signum().toLong()
        val divisorSign = divisor.signum().toLong()
        if (dividendSign * divisorSign == -1L) {
            if (dividend.mod(divisor.abs()).compareTo(BigInteger.ZERO) != 0) {
                return makeBI(tc, type, dividend.divide(divisor).subtract(BigInteger.ONE))
            }
        }
        return makeBI(tc, type, dividend.divide(divisor))
    }

    @JvmStatic
    fun div_In(a: SixModelObject?, b: SixModelObject?, tc: ThreadContext): Double {
        val divisor = getBI(tc, b)
        // Use IEEE 754-2008 semantics for division by zero
        return if (divisor.compareTo(BigInteger.ZERO) == 0)
            getBI(tc, a).toDouble() / 0
        else
            BigDecimal(getBI(tc, a)).divide(BigDecimal(divisor), 309, RoundingMode.HALF_UP).toDouble()
    }

    @JvmStatic
    fun div_i(a: Long, b: Long, tc: ThreadContext): Long {
        if (b == 0L) {
            die_s("Division by zero", tc)
        }
        return Math.floor(a.toDouble() / b).toLong()
    }

    @JvmStatic
    fun mod_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val divisor = getBI(tc, b, type)
        if (divisor.compareTo(BigInteger.ZERO) < 0) {
            val negDivisor = divisor.negate()
            val res = getBI(tc, a, type).mod(negDivisor)
            return makeBI(tc, type, if (res == BigInteger.ZERO) res else divisor.add(res))
        }
        else {
            return makeBI(tc, type, getBI(tc, a).mod(divisor))
        }
    }

    @JvmStatic
    fun expmod_I(a: SixModelObject?, b: SixModelObject?, c: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val base = getBI(tc, a, type)
        val exponent = getBI(tc, b, type)
        val modulus = getBI(tc, c, type)
        val result = base.modPow(exponent, modulus)
        return makeBI(tc, type, result)
    }

    @JvmStatic
    fun isprime_I(a: SixModelObject?, tc: ThreadContext): Long {
        val bi = getBI(tc, a)
        if (bi.compareTo(BigInteger.valueOf(1)) <= 0) {
            return 0
        }
        return if (bi.isProbablePrime(40)) 1 else 0
    }

    @JvmStatic
    fun rand_I(a: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        val size = getBI(tc, a, type)
        var random = BigInteger(size.bitLength(), tc.random)
        while (random.compareTo(size) != -1) {
            random = BigInteger(size.bitLength(), tc.random)
        }
        return makeBI(tc, type, random)
    }

    @JvmStatic
    fun pow_i(a: Long, b: Long): Long {
        return Math.pow(a.toDouble(), b.toDouble()).toLong()
    }

    @JvmStatic
    fun pow_n(a: Double, b: Double): Double {
        if (a == 1.0) {
            return 1.0
        }
        return Math.pow(a, b)
    }

    @JvmStatic
    fun mod_n(a: Double, b: Double): Double {
        return a - Math.floor(a / b) * b
    }

    @JvmStatic
    fun pow_I(a: SixModelObject?, b: SixModelObject?, nType: SixModelObject?, biType: SixModelObject?, tc: ThreadContext): SixModelObject {
        val base = getBI(tc, a)
        val exponent = getBI(tc, b)
        var cmp = exponent.compareTo(BigInteger.ZERO)
        if (cmp == 0 || base.compareTo(BigInteger.ONE) == 0) {
            return makeBI(tc, biType, BigInteger.ONE)
        }
        else if (cmp > 0) {
            if (exponent.bitLength() > 31) {
                /* Overflows integer. Terrifyingly huge, but try to cope somehow. */
                cmp = base.compareTo(BigInteger.ZERO)
                if (cmp == 0 || base.compareTo(BigInteger.ONE) == 0) {
                    /* 0 ** $big_number and 1 ** big_number are easy to do: */
                    return makeBI(tc, biType, base)
                } else if (base.compareTo(BigInteger.ONE.negate()) == 0) {
                    /* -1 ** exponent depends on whether b is odd or even */
                    /* NOTE: the reference (===-style) comparisons of mod results
                     * against BigInteger.ONE/ZERO below are preserved from the
                     * Java original (which used ==). */
                    return makeBI(tc, biType, if (exponent.mod(BigInteger.valueOf(2)) === BigInteger.ZERO)
                                                BigInteger.ONE
                                              else
                                                BigInteger.ONE.negate())
                } else {
                    /* Otherwise, do floating point infinity of the right sign. */
                    val result = nType!!.st.REPR.allocate(tc, nType.st)
                    result.set_num(tc, if (cmp > 0 || exponent.mod(BigInteger.valueOf(2)) === BigInteger.ZERO)
                                        Double.POSITIVE_INFINITY
                                       else
                                        Double.NEGATIVE_INFINITY)
                    return result
                }
            }
            else {
                /* Can safely take its integer value. */
                return makeBI(tc, biType, base.pow(exponent.toInt()))
            }
        }
        else {
            val fBase = base.toDouble()
            val fExponent = exponent.toDouble()
            val result = nType!!.st.REPR.allocate(tc, nType.st)
            result.set_num(tc, Math.pow(fBase, fExponent))
            return result
        }
    }

    @JvmStatic
    fun neg_I(a: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).negate())
    }

    @JvmStatic
    fun abs_I(a: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).abs())
    }

    @JvmStatic
    fun radix_I(radixL: Long, str: String?, zpos: Long, flags: Long, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        var zvalue = BigInteger.ZERO
        var charsConverted = 0
        var thePos = zpos
        val chars = str!!.length
        var value = zvalue
        var charsReallyConverted = charsConverted
        var pos: Long = -1
        var ch: Char
        var charValue: Int
        var neg = false
        val radix = BigInteger.valueOf(radixL)

        if (radixL > 36) {
            throw ExceptionHandling.dieInternal(tc, "Cannot convert radix of " + radixL + " (max 36)")
        }

        ch = if (thePos < chars) str[thePos.toInt()] else '\u0000'

        /* flag 0x02 asks for parsing a leading +/-.
         * We allow both, "HYPHEN-MINUS" and "MINUS SIGN", for negation. */
        if ((flags and 0x02L) != 0L && (ch == '+' || ch == '-' || ch == '−')) {
            neg = (ch == '-' || ch == '−')
            thePos++
            ch = if (thePos < chars) str[thePos.toInt()] else '\u0000'
        }

        while (thePos < chars) {
            charValue = Character.digit(ch, radixL.toInt())
            if (charValue == -1) break
            zvalue = zvalue.multiply(radix).add(BigInteger.valueOf(charValue.toLong()))
            charsConverted++
            thePos++; pos = thePos
            if (charValue != 0 || (flags and 0x04L) == 0L) { value = zvalue; charsReallyConverted = charsConverted }
            if (thePos >= chars) break
            ch = str[thePos.toInt()]
            if (ch != '_') continue
            thePos++
            if (thePos >= chars) break
            ch = str[thePos.toInt()]
        }

        if (neg || (flags and 0x01L) != 0L) { value = value.negate() }

        val hllConfig = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        val result = hllConfig.slurpyArrayType!!.st.REPR.allocate(tc,
                hllConfig.slurpyArrayType!!.st)

        result.push_boxed(tc, makeBI(tc, type, value))
        result.push_boxed(tc, makeBI(tc, type, BigInteger.valueOf(charsReallyConverted.toLong())))
        result.push_boxed(tc, makeBI(tc, type, BigInteger.valueOf(pos)))

        return result
    }

    @JvmStatic
    fun bitor_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).or(getBI(tc, b)))
    }

    @JvmStatic
    fun bitxor_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).xor(getBI(tc, b)))
    }

    @JvmStatic
    fun bitand_I(a: SixModelObject?, b: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).and(getBI(tc, b)))
    }

    @JvmStatic
    fun bitneg_I(a: SixModelObject?, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).not())
    }

    @JvmStatic
    fun bitshiftl_I(a: SixModelObject?, b: Long, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).shiftLeft(b.toInt()))
    }

    @JvmStatic
    fun bitshiftr_I(a: SixModelObject?, b: Long, type: SixModelObject?, tc: ThreadContext): SixModelObject {
        return makeBI(tc, type, getBI(tc, a).shiftRight(b.toInt()))
    }

    /* Evaluation of code; JVM-specific ops. */
    @JvmStatic
    fun compilejast(jast: SixModelObject?, jastNodes: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = EvalResult()
        res.jc = JASTCompiler.buildClass(jast!!, jastNodes!!, false, tc)
        return res
    }
    /** The class an in-memory compiled block belongs to, when one was
     * retained for nested-unit persistence; empty string otherwise. */
    @JvmStatic
    fun jvmclassofcuid(cuid: String?, tc: ThreadContext): String =
        if (cuid == null) "" else tc.gc.inMemoryUnitOfCuid[cuid] ?: ""

    /**
     * Loads a nested compilation unit embedded in the current unit's jar
     * and installs its code refs into the current unit's qbid table at the
     * given slots, matched by cuid, so deserialization finds the code refs
     * the serialized graph points into. The MoarVM backend has no need of
     * this: it assembles nested units' frames into the enclosing bytecode.
     */
    @JvmStatic
    fun jvmclaimnested(className: String?, idxs: SixModelObject?, cuids: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val cu = tc.frame.codeRef.staticInfo.compUnit
        try {
            val klass = Class.forName(className, true, cu.javaClass.classLoader)
            val nested = klass.getDeclaredConstructor().newInstance() as CompilationUnit
            nested.shared = tc.gc.sharingHint
            /* The enclosing SC is still empty at this point; the nested
             * unit's own deserialization code (static block lexical values
             * and the like) runs via jvm-finish-nested afterwards. */
            nested.initializeCompilationUnit(tc, false)
            tc.gc.claimedNestedUnits[className!!] = nested
            val byCuid = HashMap<String, CodeRef>()
            nested.codeRefs?.let { crs ->
                for (cr in crs) {
                    val cuid = cr.staticInfo.uniqueId
                    if (!cuid.isNullOrEmpty())
                        byCuid[cuid] = cr
                }
            }
            val n = idxs!!.elems(tc).toInt()
            var table = cu.qbidToCodeRef!!
            for (k in 0 until n) {
                idxs.at_pos_native(tc, k.toLong())
                val idx = tc.nativeI.toInt()
                cuids!!.at_pos_native(tc, k.toLong())
                val cuid = tc.nativeS!!
                val cr = byCuid[cuid]
                    ?: throw ExceptionHandling.dieInternal(tc,
                        "Nested unit $className carries no block with cuid '$cuid'")
                if (idx >= table.size) {
                    table = table.copyOf(idx + 1)
                    cu.qbidToCodeRef = table
                }
                table[idx] = cr
            }
        }
        catch (e: ReflectiveOperationException) {
            throw ExceptionHandling.dieInternal(tc,
                "Could not load nested compilation unit $className: $e")
        }
        return null
    }

    /** Runs a claimed nested unit's deserialization code, once the
     * enclosing unit's own deserialization has populated the SC. */
    @JvmStatic
    fun jvmfinishnested(className: String?, tc: ThreadContext): SixModelObject? {
        val nested = tc.gc.claimedNestedUnits.remove(className)
            ?: throw ExceptionHandling.dieInternal(tc,
                "No claimed nested compilation unit named $className")
        nested.runDeserializeIfAvailable(tc)
        return null
    }

    @JvmStatic
    fun compilejasttofile(jast: SixModelObject?, jastNodes: SixModelObject?, filename: String?, tc: ThreadContext): SixModelObject? {
        JASTCompiler.writeClass(jast!!, jastNodes!!, filename!!, tc)
        return jast
    }
    @JvmStatic
    fun loadcompunit(obj: SixModelObject?, compileeHLL: Long, tc: ThreadContext): SixModelObject? {
        try {
            val res = obj as EvalResult
            val cuClass = tc.gc.byteClassLoader.defineClass(res.jc!!.name, res.jc!!.bytes!!)
            res.cu = cuClass.newInstance() as CompilationUnit
            if (compileeHLL != 0L)
                usecompileehllconfig(tc)
            res.cu!!.initializeCompilationUnit(tc)
            if (compileeHLL != 0L)
                usecompilerhllconfig(tc)
            /* A unit compiled while a compilation is under way may be a
             * nested unit whose code refs the enclosing serialization
             * points into; retain what embedding it later needs. */
            if (!tc.compilingSCs.isNullOrEmpty()) {
                val unitName = res.jc!!.name!!
                tc.gc.inMemoryUnitBytes[unitName] = res.jc!!.bytes!!
                res.cu!!.codeRefs?.let { crs ->
                    for (cr in crs) {
                        val cuid = cr.staticInfo.uniqueId
                        if (!cuid.isNullOrEmpty())
                            tc.gc.inMemoryUnitOfCuid[cuid] = unitName
                    }
                }
            }
            res.jc = null
            return obj
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
    @JvmStatic
    fun iscompunit(obj: SixModelObject?, tc: ThreadContext): Long {
        return if (obj is EvalResult) 1 else 0
    }
    @JvmStatic
    fun compunitmainline(obj: SixModelObject?, tc: ThreadContext): SixModelObject? {
        val res = obj as EvalResult
        return res.cu!!.lookupCodeRef(res.cu!!.mainlineQbid())
    }
    @JvmStatic
    fun compunitcodes(obj: SixModelObject?, tc: ThreadContext): SixModelObject {
        val res = obj as EvalResult
        val Array = tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType!!
        val result = Array.st.REPR.allocate(tc, Array.st)
        for (i in res.cu!!.codeRefs!!.indices)
            result.bind_pos_boxed(tc, i.toLong(), res.cu!!.codeRefs!![i])
        return result
    }
    @JvmStatic
    fun jvmclasspaths(tc: ThreadContext): SixModelObject {
        val Array = tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType!!
        val Str = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
        val result = Array.st.REPR.allocate(tc, Array.st)
        val cpStr = System.getProperty("java.class.path")
        val cps = java.util.regex.Pattern.compile("[:;]").split(cpStr)
        for (i in cps.indices)
            result.push_boxed(tc, box_s(cps[i], Str, tc))
        return result
    }

    @JvmStatic
    fun usecompileehllconfig(tc: ThreadContext): Long {
        if (tc.gc.compileeDepth == 0)
            tc.gc.useCompileeHLLConfig()
        tc.gc.compileeDepth++
        return 1
    }
    @JvmStatic
    fun usecompilerhllconfig(tc: ThreadContext): Long {
        tc.gc.compileeDepth--
        if (tc.gc.compileeDepth == 0)
            tc.gc.useCompilerHLLConfig()
        return 1
    }

    private val resetReenter: MethodHandle = try {
        MethodHandles.insertArguments(
                MethodHandles.publicLookup().findStatic(Ops::class.java, "continuationreset",
                    MethodType.methodType(Void.TYPE, SixModelObject::class.java, SixModelObject::class.java, ThreadContext::class.java, ResumeStatus.Frame::class.java)),
                0, null, null, null)
    } catch (e: Exception) {
        throw RuntimeException(e)
    }
    // this is the most complicated one because it's not doing a tailcall, so we need to actually use the resumeframe
    @JvmStatic
    @Throws(Throwable::class)
    fun continuationreset(key: SixModelObject?, run: SixModelObject?, tc: ThreadContext) {
        continuationreset(key, run, tc, null)
    }
    @JvmStatic
    @Throws(Throwable::class)
    fun continuationreset(key: SixModelObject?, run: SixModelObject?, tc: ThreadContext?, resume: ResumeStatus.Frame?) {
        /* tc is nullable here: the resetReenter MethodHandle binds null
         * for key/run/tc and the real tc is reloaded from the resume
         * frame below. */
        var theKey = key
        var theRun = run
        var theTc = tc
        var theResume = resume
        var cont: SixModelObject? = null

        if (theResume != null) {
            // reload stuff here, then don't goto because java source doesn't have that
            val bits = theResume.saveSpace
            theKey = bits[0] as SixModelObject?
            theTc = theResume.tc
        }

        while (true) {
            try {
                if (theResume != null) {
                    theResume.resumeNext()
                } else if (isnull(cont) == 0L) {
                    invokeDirect(theTc!!, theRun, invocantCallSite, false, arrayOf<Any?>(cont))
                } else {
                    if (theRun is ResumeStatus) {
                        /* Got a continuation to invoke immediately (done by
                         * Rakudo to cope with lack of tail calls). */
                        val root = theRun.top
                        fixupContinuation(theTc!!, root, null)
                        root.resume()
                    }
                    else {
                        /* Code a normal code ref to invoke. */
                        invokeDirect(theTc!!, theRun, emptyCallSite, false, emptyArgList)
                    }
                }
                // If we get here, the reset argument or something placed using control returned normally
                // so we should just return.
                return
            } catch (sse: SaveStackException) {
                if (isnull(sse.key) == 0L && sse.key !== theKey) {
                    if (System.getenv("NQP_DEBUG_CONT") != null)
                        System.err.println("reset key mismatch: have " +
                            (if (theKey == null) "null" else theKey.javaClass.simpleName + "@" +
                                Integer.toHexString(System.identityHashCode(theKey))) +
                            " want " + sse)
                    // This is intended for an outer scope, so just append ourself
                    throw sse.pushFrame(0, resetReenter, arrayOf<Any?>(theKey), null)
                }
                // Ooo!  This is ours!
                theResume = null
                val contType = theTc!!.gc.Continuation!!.st
                cont = contType.REPR.allocate(theTc!!, contType)
                (cont as ResumeStatus).top = sse.top
                theRun = sse.handler
                if (!sse.protect) break
            }
        }
        // now, if we get HERE, it means we saw an unprotected control operator
        // so run it without protection

        invokeDirect(theTc!!, theRun, invocantCallSite, false, arrayOf<Any?>(cont))
    }

    @JvmStatic
    fun continuationclone(`in`: SixModelObject?, tc: ThreadContext): SixModelObject {
        if (`in` !is ResumeStatus)
            ExceptionHandling.dieInternal(tc, "applied continuationclone to non-continuation")

        var read = (`in` as ResumeStatus).top
        var nroot: ResumeStatus.Frame? = null
        var ntail: ResumeStatus.Frame? = null
        var nnew: ResumeStatus.Frame

        while (read != null) {
            val cf = if (read.callFrame == null) null else read.callFrame.cloneContinuation()
            nnew = ResumeStatus.Frame(read.method, read.resumePoint, read.saveSpace, cf, null)
            if (ntail != null) {
                ntail.next = nnew
            } else {
                nroot = nnew
            }
            ntail = nnew
            read = read.next
        }

        val contType = tc.gc.Continuation!!.st
        val cont = contType.REPR.allocate(tc, contType)
        (cont as ResumeStatus).top = nroot
        return cont
    }

    @JvmStatic
    fun continuationcontrol(protect: Long, key: SixModelObject?, run: SixModelObject?, tc: ThreadContext) {
        if (System.getenv("NQP_DEBUG_CONT") != null)
            Throwable("continuationcontrol on " + Thread.currentThread().name).printStackTrace()
        throw SaveStackException(key, protect != 0L, run)
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun continuationinvoke(cont: SixModelObject?, arg: SixModelObject?, tc: ThreadContext) {
        if (cont !is ResumeStatus)
            ExceptionHandling.dieInternal(tc, "applied continuationinvoke to non-continuation")
        val root = (cont as ResumeStatus).top
        fixupContinuation(tc, root, arg)
        root.resume()
    }
    private fun fixupContinuation(tc: ThreadContext, csr: ResumeStatus.Frame?, arg: SixModelObject?) {
        // fixups: safe to do more than once, but not concurrently
        // these are why continuationclone is needed...
        var cur = csr
        while (cur != null) {
            cur.tc = tc // csr.callFrame.{csr,tc} will be set on resume
            if (cur.next == null) cur.thunk = arg
            cur = cur.next
        }
    }

    /* noop, exists only so you can set a breakpoint in it */
    @JvmStatic
    fun debugnoop(`in`: SixModelObject?, tc: ThreadContext): SixModelObject? {
        return `in`
    }

    @JvmStatic
    fun jvmgetconfig(tc: ThreadContext): SixModelObject {
        val hashType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.hashType!!
        val strType = tc.frame.codeRef.staticInfo.compUnit.hllConfig.strBoxType
        val res = hashType.st.REPR.allocate(tc, hashType.st)

        try {
            val stream = Ops::class.java.getResourceAsStream("/jvmconfig.properties")
            val config = Properties()
            config.load(stream)
            for (name in config.stringPropertyNames())
                res.bind_key_boxed(tc, name, box_s(config.getProperty(name), strType, tc))
        } catch (e: Throwable) {
            die_s("Failed to load config.properties", tc)
        }

        return res
    }

    /* Compute supported unicode version. Please update for new versions.
     * Cmp. https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/lang/Character.html */
    @JvmStatic
    fun jvmgetunicodeversion(tc: ThreadContext): String {
        when (System.getProperties().getProperty("java.specification.version")) {
            "1.9", "9", "10" ->
                return "8.0"
            "11" ->
                return "10.0"
            "12" ->
                return "11.0"
            "13", "14" ->
                return "12.1"
            "15", "16", "17", "18" ->
                return "13.0"
            "19" ->
                return "14.0"
            /* Unicode version for Java 1.8, which we don't support anymore. */
            else ->
                return "6.2"
        }
    }

    @JvmStatic
    fun getuniname(codePoint: Long, tc: ThreadContext): String {
        var name: String?
        val cp = codePoint.toInt()
        try {
            if (codePoint < 0) {
                name = "<illegal>"
            }
            /* Return <control-XXXX> for control characters */
            else if (codePoint <= 0x1F || (codePoint in 0x7F..0x9F)) {
                name = String.format("<control-%04X>", codePoint)
            }
            else if ((codePoint in 0xFDD0..0xFDEF)
                   || (0xFFFE and cp) == 0xFFFE) {
                name = String.format("<noncharacter-%04X>", codePoint)
            }
            /* Surrogates */
            else if (codePoint in 0xD800..0xDFFF) {
                name = String.format("<surrogate-%04X>", codePoint)
            }
            /* Private Use Area */
            else if ((codePoint in 0xE000..0xF8FF)
                 || (codePoint in 0xF0000..0x10FFFF)) {
                name = String.format("<private-use-%04X>", codePoint)
            }
            else {
                name = Character.getName(cp)
                if (name == null) {
                    if (0x10FFFF < codePoint) {
                        name = "<unassigned>"
                    }
                    else {
                        name = String.format("<reserved-%04X>", codePoint)
                    }
                }
            }
        } catch (iae: IllegalArgumentException) {
            if (0x10FFFF < codePoint) {
                name = "<unassigned>"
            }
            else {
                name = String.format("<reserved-%04X>", codePoint)
            }
        }
        return name!!
    }

    /* Property codes, numbered as MoarVM numbers them in
     * src/strings/unicode_gen.h, so that the table here reads as a subset
     * of the real one. A code is opaque to everything above, but it is
     * baked into precompiled code (the setting takes nqp::unipropcode at
     * BEGIN time), so a name must keep its number across a build. */
    private const val UNIPROP_BLOCK                     = 6
    private const val UNIPROP_SCRIPT                    = 9
    private const val UNIPROP_NUMERIC_VALUE_NUMERATOR   = 10
    private const val UNIPROP_GENERAL_CATEGORY          = 20
    private const val UNIPROP_NUMERIC_VALUE_DENOMINATOR = 21

    /* Character.getType constants indexed to their Unicode two-letter
     * general category names. */
    private val GENERAL_CATEGORY_NAMES = HashMap<Int, String>().apply {
        put(Character.UPPERCASE_LETTER.toInt(), "Lu")
        put(Character.LOWERCASE_LETTER.toInt(), "Ll")
        put(Character.TITLECASE_LETTER.toInt(), "Lt")
        put(Character.MODIFIER_LETTER.toInt(), "Lm")
        put(Character.OTHER_LETTER.toInt(), "Lo")
        put(Character.NON_SPACING_MARK.toInt(), "Mn")
        put(Character.ENCLOSING_MARK.toInt(), "Me")
        put(Character.COMBINING_SPACING_MARK.toInt(), "Mc")
        put(Character.DECIMAL_DIGIT_NUMBER.toInt(), "Nd")
        put(Character.LETTER_NUMBER.toInt(), "Nl")
        put(Character.OTHER_NUMBER.toInt(), "No")
        put(Character.SPACE_SEPARATOR.toInt(), "Zs")
        put(Character.LINE_SEPARATOR.toInt(), "Zl")
        put(Character.PARAGRAPH_SEPARATOR.toInt(), "Zp")
        put(Character.CONTROL.toInt(), "Cc")
        put(Character.FORMAT.toInt(), "Cf")
        put(Character.PRIVATE_USE.toInt(), "Co")
        put(Character.SURROGATE.toInt(), "Cs")
        put(Character.UNASSIGNED.toInt(), "Cn")
        put(Character.DASH_PUNCTUATION.toInt(), "Pd")
        put(Character.START_PUNCTUATION.toInt(), "Ps")
        put(Character.END_PUNCTUATION.toInt(), "Pe")
        put(Character.CONNECTOR_PUNCTUATION.toInt(), "Pc")
        put(Character.OTHER_PUNCTUATION.toInt(), "Po")
        put(Character.INITIAL_QUOTE_PUNCTUATION.toInt(), "Pi")
        put(Character.FINAL_QUOTE_PUNCTUATION.toInt(), "Pf")
        put(Character.MATH_SYMBOL.toInt(), "Sm")
        put(Character.CURRENCY_SYMBOL.toInt(), "Sc")
        put(Character.MODIFIER_SYMBOL.toInt(), "Sk")
        put(Character.OTHER_SYMBOL.toInt(), "So")
    }

    /* Java names a block or script by its identifier form (BASIC_LATIN,
     * LATIN), where Unicode names it in prose (Basic Latin, Latin), and
     * that prose name is what a property value is matched against. Spell
     * the identifier back out: each part title-cased, a part that is a
     * lone letter or a run of digits hyphenated onto the part before it
     * (LATIN_EXTENDED_A is Latin Extended-A, LATIN_1_SUPPLEMENT is
     * Latin-1 Supplement), and the joining words left lowercase.
     * A handful of blocks whose Unicode name is not a respelling of the
     * Java identifier at all still come out wrong; Java exposes no way to
     * ask for the Unicode name itself. */
    private val UNIPROP_JOINING_WORDS = setOf("and", "or", "of", "for", "with", "the")

    private fun unicodeNameOf(identifier: String): String {
        val out = StringBuilder()
        var first = true
        for (part in identifier.split("_")) {
            if (part.isEmpty())
                continue
            val attaches = !first
                && (part.length == 1 && part[0].isLetter() || part.all { it.isDigit() })
            val lower = part.lowercase()
            if (!first)
                out.append(if (attaches) '-' else ' ')
            if (!first && !attaches && UNIPROP_JOINING_WORDS.contains(lower))
                out.append(lower)
            else
                out.append(part[0].uppercaseChar()).append(lower.substring(1))
            first = false
        }
        return out.toString()
    }

    /* TODO: Make this handle more properties. */
    @JvmStatic
    fun getuniprop_str(codepoint: Long, property: Long, tc: ThreadContext): String {
        var res = ""
        if (property == UNIPROP_GENERAL_CATEGORY.toLong()) {
            res = GENERAL_CATEGORY_NAMES[Character.getType(codepoint.toInt())] ?: "Cn"
        }
        else if (property == UNIPROP_BLOCK.toLong()) {
            val block = Character.UnicodeBlock.of(codepoint.toInt())
            res = if (block == null) "" else unicodeNameOf(block.toString())
        }
        else if (property == UNIPROP_SCRIPT.toLong()) {
            val script = Character.UnicodeScript.of(codepoint.toInt())
            res = if (script == null) "" else unicodeNameOf(script.name)
        }
        else if (property == UNIPROP_NUMERIC_VALUE_NUMERATOR.toLong() || property == UNIPROP_NUMERIC_VALUE_DENOMINATOR.toLong()) {
            /* NFKD will decompose fractions into numerator and denominator,
             * separated by "FRACTION SLASH" (⁄). */
            val fraction = java.util.regex.Pattern.compile("⁄").split(
                    Normalizer.normalize(codepoint.toInt().toChar().toString(), Normalizer.Form.NFKD))
            if (property == UNIPROP_NUMERIC_VALUE_DENOMINATOR.toLong()) {
                res = if (fraction.size == 2) fraction[1] else "1"
            } else {
                res = fraction[0]
            }
        }
        return res
    }

    /* TODO: Make this handle more properties. */
    @JvmStatic
    fun unipropcode(prop: String, tc: ThreadContext): Long {
        when (prop) {
            "Numeric_Value_Numerator" ->
                return UNIPROP_NUMERIC_VALUE_NUMERATOR.toLong()
            "Numeric_Value_Denominator" ->
                return UNIPROP_NUMERIC_VALUE_DENOMINATOR.toLong()
            "General_Category", "gc" ->
                return UNIPROP_GENERAL_CATEGORY.toLong()
            "Block", "blk" ->
                return UNIPROP_BLOCK.toLong()
            "Script", "sc" ->
                return UNIPROP_SCRIPT.toLong()
            else ->
                return -1
        }
    }

    /* The properties in the table above all carry string values, so an
     * integer reading of one is the numeric value it spells, if it spells
     * one at all. */
    @JvmStatic
    fun getuniprop_int(codepoint: Long, property: Long, tc: ThreadContext): Long {
        val str = getuniprop_str(codepoint, property, tc)
        return try { java.lang.Long.parseLong(str) } catch (e: NumberFormatException) { 0L }
    }

    /* No binary property has a code yet, so nothing is ever true. */
    @JvmStatic
    fun getuniprop_bool(codepoint: Long, property: Long, tc: ThreadContext): Long {
        return 0L
    }

    @JvmStatic
    fun force_gc(tc: ThreadContext): SixModelObject? {
        System.gc()
        return null
    }

    @JvmStatic
    fun coerce_si(s: String?, tc: ThreadContext): Long {
        return java.lang.Long.parseLong(s)
    }

    /* int<->num and int<->uint coercions, present on MoarVM since long
     * before the JVM backend stalled; added here because rakudo's CORE
     * uses nqp::coerce_in. Semantics mirror the *_2* helpers above. */
    @JvmStatic
    fun coerce_in(l: Long, tc: ThreadContext): Double {
        return l.toDouble()
    }

    @JvmStatic
    fun coerce_ni(d: Double, tc: ThreadContext): Long {
        return coerce_n2i(d)
    }

    @JvmStatic
    fun coerce_ui(l: Long, tc: ThreadContext): Long {
        return l
    }

    @JvmStatic
    fun coerce_iu(l: Long, tc: ThreadContext): Long {
        return l
    }

    @JvmStatic
    fun coerce_is(l: Long, tc: ThreadContext): String {
        return l.toString()
    }

    @JvmStatic
    fun coerce_us(l: Long, tc: ThreadContext): String {
        return java.lang.Long.toUnsignedString(l)
    }

    @JvmStatic
    fun coerce_ns(d: Double, tc: ThreadContext): String {
        return coerce_n2s(d)
    }

    @JvmStatic
    fun decodelocaltime(sinceEpoch: Long, tc: ThreadContext): SixModelObject {
        // Get calendar for current local host's timezone.
        val c = Calendar.getInstance()
        c.setTimeInMillis(sinceEpoch * 1000)

        // Populate result int array.
        val BOOTIntArray = tc.gc.BOOTIntArray!!
        val result = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st)
        tc.nativeI = c.get(Calendar.SECOND).toLong()
        result.bind_pos_native(tc, 0)
        tc.nativeI = c.get(Calendar.MINUTE).toLong()
        result.bind_pos_native(tc, 1)
        tc.nativeI = c.get(Calendar.HOUR_OF_DAY).toLong()
        result.bind_pos_native(tc, 2)
        tc.nativeI = c.get(Calendar.DAY_OF_MONTH).toLong()
        result.bind_pos_native(tc, 3)
        tc.nativeI = (c.get(Calendar.MONTH) + 1).toLong()
        result.bind_pos_native(tc, 4)
        tc.nativeI = c.get(Calendar.YEAR).toLong()
        result.bind_pos_native(tc, 5)

        return result
    }

}
