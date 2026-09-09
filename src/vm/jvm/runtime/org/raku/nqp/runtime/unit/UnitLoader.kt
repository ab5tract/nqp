package org.raku.nqp.runtime.unit

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.raku.nqp.runtime.ThreadContext

/** Loads unit artifacts. A shared load (eval server) caches the parsed,
 *  immutable record by path and builds a fresh ProgramUnit per load,
 *  the way the class road shares the Class and instantiates per context. */
object UnitLoader {
    private val sharedRecords = ConcurrentHashMap<String, UnitRecord>()
    private val sharedRoads = ConcurrentHashMap<String, Boolean>()

    /** A central-directory lookup, not a full read: cheap enough to call
     *  on every load. */
    @JvmStatic
    fun isUnitFile(fn: String): Boolean {
        val f = File(fn)
        if (!f.isFile) return false
        return try { java.util.zip.ZipFile(f).use { it.getEntry(UnitZip.META) != null } } catch (t: Exception) { false }
    }

    /** Caches the road decision by path for a shared load (the eval
     *  server), so a request pays one map lookup instead of re-sniffing
     *  the file every time. */
    @JvmStatic
    fun isUnitFile(fn: String, shared: Boolean): Boolean =
        if (shared) sharedRoads.computeIfAbsent(fn) { isUnitFile(it) } else isUnitFile(fn)

    @JvmStatic
    @Throws(IOException::class)
    fun record(fn: String, shared: Boolean): UnitRecord =
        if (shared) sharedRecords.computeIfAbsent(fn) { UnitZip.read(File(it).readBytes()) }
        else UnitZip.read(File(fn).readBytes())

    @JvmStatic
    @Throws(IOException::class)
    fun loadUnit(tc: ThreadContext, fn: String, shared: Boolean): ProgramUnit {
        val u = ProgramUnit(record(fn, shared))
        u.shared = shared
        u.initializeCompilationUnit(tc)
        return u
    }

    @JvmStatic
    @Throws(IOException::class)
    fun loadAndRun(tc: ThreadContext, fn: String, shared: Boolean) {
        loadUnit(tc, fn, shared).runLoadIfAvailable(tc)
    }

    @JvmStatic
    fun loadAndRun(tc: ThreadContext, bytes: ByteArray) {
        val u = ProgramUnit(UnitZip.read(bytes))
        u.shared = tc.gc.sharingHint
        u.initializeCompilationUnit(tc)
        u.runLoadIfAvailable(tc)
    }
}
