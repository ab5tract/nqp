package org.raku.nqp.runtime.unit

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.raku.nqp.runtime.ThreadContext

/** Loads unit artifacts. A shared load (eval server) caches the parsed,
 *  immutable record by path and builds a fresh ProgramUnit per load,
 *  the way the class road shares the Class and instantiates per context. */
object UnitLoader {
    private val sharedRecords = ConcurrentHashMap<String, UnitRecord>()

    @JvmStatic
    fun isUnitFile(fn: String): Boolean {
        val f = File(fn)
        if (!f.isFile) return false
        return try { UnitZip.isUnit(f.readBytes()) } catch (t: Exception) { false }
    }

    @JvmStatic
    fun record(fn: String, shared: Boolean): UnitRecord =
        if (shared) sharedRecords.computeIfAbsent(fn) { UnitZip.read(File(it).readBytes()) }
        else UnitZip.read(File(fn).readBytes())

    @JvmStatic
    fun loadUnit(tc: ThreadContext, fn: String, shared: Boolean): ProgramUnit {
        val u = ProgramUnit(record(fn, shared))
        u.shared = shared
        u.initializeCompilationUnit(tc)
        return u
    }

    @JvmStatic
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
