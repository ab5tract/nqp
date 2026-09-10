package org.raku.nqp.runtime.unit

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/** Loads unit artifacts -- the only road there is. A shared load (eval
 *  server) caches the parsed, immutable record by path and builds a fresh
 *  ProgramUnit per load, so a request pays for parsing once. */
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

    /** Caches the sniff by path for a shared load (the eval server), so a
     *  request pays one map lookup instead of re-reading the file's
     *  central directory every time. */
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

    /** nqp::loadbytecode: a unit by path, once per GlobalContext. The
     *  ModuleLoader.class name is special-cased as it always was: the
     *  first unit is probed for on the classpath as ModuleLoader.class,
     *  then ModuleLoader.jar (an artifact since milestone 1). */
    @JvmStatic
    fun load(tc: ThreadContext, filename0: String) {
        var filename = filename0
        if (!tc.gc.loadedUnits.add(filename)) return
        try {
            var file = File(filename)
            if (!file.isFile && filename == "ModuleLoader.class") {
                for (cp in System.getProperty("java.class.path").split(Regex("[:;]"))) {
                    file = File("$cp/$filename")
                    if (file.isFile) { filename = "$cp/$filename"; break }
                    file = File("$cp/ModuleLoader.jar")
                    if (file.isFile) { filename = "$cp/ModuleLoader.jar"; break }
                }
            }
            if (!isUnitFile(filename))
                throw ExceptionHandling.dieInternal(tc, "loadbytecode: $filename is not a unit artifact")
            loadAndRun(tc, filename, tc.gc.sharingHint)
        } catch (e: ControlException) {
            throw e
        } catch (e: Exception) {
            if (e is RuntimeException && e.javaClass.name.startsWith("org.raku.nqp")) throw e
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    /** nqp::loadbytecodebuffer: a unit artifact already in memory. */
    @JvmStatic
    fun load(tc: ThreadContext, buffer: ByteArray) {
        if (!UnitZip.isUnit(ByteBuffer.wrap(buffer)))
            throw ExceptionHandling.dieInternal(tc, "loadbytecodebuffer: the buffer is not a unit artifact")
        try {
            loadAndRun(tc, buffer)
        } catch (e: ControlException) {
            throw e
        } catch (e: Exception) {
            if (e is RuntimeException && e.javaClass.name.startsWith("org.raku.nqp")) throw e
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    fun load(tc: ThreadContext, buffer: ByteBuffer) {
        val bytes = if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0
                        && buffer.array().size == buffer.remaining()) buffer.array()
                    else ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        load(tc, bytes)
    }

    /** Road-agnostic app load for entry points (runner main, eval server):
     *  initialized (deserialized) but its load block not run; an entry
     *  block does that itself. */
    @JvmStatic
    fun loadApp(tc: ThreadContext, path: String, shared: Boolean): CompilationUnit {
        if (!isUnitFile(path, shared))
            throw ExceptionHandling.dieInternal(tc, "$path is not a unit artifact")
        return try {
            loadUnit(tc, path, shared)
        } catch (e: ControlException) {
            throw e
        } catch (e: Exception) {
            if (e is RuntimeException && e.javaClass.name.startsWith("org.raku.nqp")) throw e
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    /** Warms the shared record cache, so a server pays for parsing once. */
    @JvmStatic
    @Throws(IOException::class)
    fun prime(path: String) { record(path, true) }
}
