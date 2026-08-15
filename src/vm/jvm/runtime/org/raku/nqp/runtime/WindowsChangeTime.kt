package org.raku.nqp.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets

/**
 * A file's NTFS change time, which no JDK attribute view exposes on Windows.
 * This used to come from jna-platform's Kernel32 binding; it is now a direct
 * downcall into kernel32, resolved on first use so that nothing is loaded on
 * the platforms that answer "unix:ctime" for themselves.
 */
object WindowsChangeTime {
    private const val GENERIC_READ = 0x80000000.toInt()
    private const val FILE_SHARE_READ = 0x00000001
    private const val OPEN_EXISTING = 3
    private const val FILE_ATTRIBUTE_NORMAL = 0x00000080
    private const val FileBasicInfo = 0
    private const val INVALID_HANDLE_VALUE = -1L

    /* FILE_BASIC_INFO is four LARGE_INTEGERs followed by a DWORD, tail-padded
     * out to the eight-byte alignment the LARGE_INTEGERs ask for. */
    private const val FILE_BASIC_INFO_SIZE = 40L
    private const val CHANGE_TIME_OFFSET = 24L

    /* Milliseconds between the FILETIME epoch (1601-01-01) and the Unix one. */
    private const val EPOCH_DIFFERENCE_MS = 11644473600000L

    private class Kernel32 {
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())

        val createFileW: MethodHandle = downcall("CreateFileW", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

        val getFileInformationByHandleEx: MethodHandle = downcall("GetFileInformationByHandleEx",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT))

        val closeHandle: MethodHandle = downcall("CloseHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))

        private fun downcall(symbol: String, descriptor: FunctionDescriptor): MethodHandle =
            NativeSupport.LINKER.downcallHandle(
                lookup.find(symbol).orElseThrow { UnsatisfiedLinkError("kernel32!$symbol") },
                descriptor)
    }

    private val kernel32: Kernel32? by lazy {
        try { Kernel32() } catch (t: Throwable) { null }
    }

    /** Seconds since the Unix epoch, or -1 if we can't find out. */
    @JvmStatic
    fun of(filename: String?): Long {
        val k = kernel32
        if (k == null || filename == null) return -1

        try {
            Arena.ofConfined().use { arena ->
                val path = arena.allocateFrom(filename, StandardCharsets.UTF_16LE)
                /* NOTE: the JNA original tested GetLastError() after opening,
                 * which can report a leftover success code from an unrelated
                 * call; the handle itself is the reliable answer. */
                val handle = k.createFileW.invokeWithArguments(path, GENERIC_READ, FILE_SHARE_READ,
                    MemorySegment.NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL) as MemorySegment
                if (handle.address() == 0L || handle.address() == INVALID_HANDLE_VALUE)
                    return -1

                try {
                    val info = arena.allocate(FILE_BASIC_INFO_SIZE)
                    val ok = k.getFileInformationByHandleEx.invokeWithArguments(handle, FileBasicInfo,
                        info, FILE_BASIC_INFO_SIZE.toInt()) as Int
                    if (ok == 0) return -1

                    val ticks = info.get(ValueLayout.JAVA_LONG, CHANGE_TIME_OFFSET)
                    return (ticks / 10000 - EPOCH_DIFFERENCE_MS) / 1000
                }
                finally {
                    k.closeHandle.invokeWithArguments(handle)
                }
            }
        }
        catch (t: Throwable) {
            return -1
        }
    }
}
