package org.raku.nqp.runtime.unit

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/** Loads unit artifacts -- the only road there is. A shared load (eval
 *  server) keeps one open store per path and builds a fresh ProgramUnit per
 *  load, so a request pays for the open once. */
object UnitLoader {
    /** One store per path, process-wide, for shared loads (the eval
     *  server): immutable, so every run's ProgramUnit slices the same
     *  mapping. Replaces the parsed-record cache. */
    private val stores = ConcurrentHashMap<String, UnitStore>()

    /** Sniffs the first local file header only: v2's unit.index. */
    @JvmStatic
    fun isUnitFile(fn: String): Boolean {
        val f = File(fn)
        if (!f.isFile) return false
        return try {
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
                val head = ByteBuffer.allocate(64)
                ch.read(head); head.flip()
                UnitStore.isUnit(head)
            }
        } catch (t: Exception) { false }
    }

    @JvmStatic
    fun store(fn: String, shared: Boolean): UnitStore =
        if (shared) stores.computeIfAbsent(fn) { openStore(it) } else openStore(fn)

    private fun openStore(fn: String): UnitStore {
        val name = File(fn).name
        return UnitLoadStats.time(name, "open-store", { "bytes=${File(fn).length()}" }) {
            FileChannel.open(Path.of(fn), StandardOpenOption.READ).use { ch ->
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                UnitStore.open(mapped, fn)
            }
        }
    }

    private fun openStore(bytes: ByteArray, name: String): UnitStore =
        UnitStore.open(ByteBuffer.wrap(bytes), name)

    @JvmStatic
    @Throws(IOException::class)
    fun loadUnit(tc: ThreadContext, fn: String, shared: Boolean): ProgramUnit {
        val u = ProgramUnit(store(fn, shared))
        u.shared = shared
        u.initializeCompilationUnit(tc)
        return u
    }

    @JvmStatic
    @Throws(IOException::class)
    fun loadAndRun(tc: ThreadContext, fn: String, shared: Boolean) {
        UnitLoadStats.load(File(fn).name) {
            val u = loadUnit(tc, fn, shared)
            UnitLoadStats.time(File(fn).name, "load-block") { u.runLoadIfAvailable(tc) }
        }
    }

    @JvmStatic
    fun loadAndRun(tc: ThreadContext, bytes: ByteArray) {
        val u = ProgramUnit(openStore(bytes, "<buffer>"))
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
        /* Keyed by the name as given, before the ModuleLoader.class rewrite
         * below: the dedupe is per requested path, as it always was.
         * Recorded BEFORE the load so a unit whose load block loads itself
         * again does not recurse, and taken back out again if the load
         * throws -- otherwise a missing or corrupt artifact makes every
         * later nqp::loadbytecode of the same path a silent no-op, and the
         * real failure surfaces much later as an unrelated missing symbol. */
        if (!tc.gc.loadedUnits.add(filename)) return
        var loaded = false
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
            loaded = true
        } catch (e: ControlException) {
            /* A continuation capture crossing the load block is not a
             * failed load: the unit's mainline is packed away and resumes,
             * so the unit stays recorded -- un-marking it here made a later
             * loadbytecode of the same path load it a second time. */
            loaded = true
            throw e
        } catch (e: Exception) {
            if (e is RuntimeException && e.javaClass.name.startsWith("org.raku.nqp")) throw e
            throw ExceptionHandling.dieInternal(tc, e)
        } finally {
            if (!loaded) tc.gc.loadedUnits.remove(filename0)
        }
    }

    /** nqp::loadbytecodebuffer: a unit artifact already in memory. */
    @JvmStatic
    fun load(tc: ThreadContext, buffer: ByteArray) {
        if (!UnitStore.isUnit(ByteBuffer.wrap(buffer)))
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
        if (!isUnitFile(path))
            throw ExceptionHandling.dieInternal(tc, "$path is not a unit artifact")
        return try {
            UnitLoadStats.load(File(path).name) { loadUnit(tc, path, shared) }
        } catch (e: ControlException) {
            throw e
        } catch (e: Exception) {
            if (e is RuntimeException && e.javaClass.name.startsWith("org.raku.nqp")) throw e
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    /** Warms the store cache, so a server pays for the open once. */
    @JvmStatic
    @Throws(IOException::class)
    fun prime(path: String) { store(path, true) }
}
