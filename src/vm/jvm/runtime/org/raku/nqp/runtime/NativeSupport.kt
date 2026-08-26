package org.raku.nqp.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The java.lang.foreign plumbing behind the JVM backend's native call
 * support: this platform's C type sizes, symbol lookup, off-heap buffers
 * with a garbage-collected lifetime, and raw addresses.
 */
object NativeSupport {
    @JvmField val LINKER: Linker = Linker.nativeLinker()

    private fun canonical(name: String): ValueLayout =
        LINKER.canonicalLayouts()[name] as? ValueLayout
            ?: throw UnsupportedOperationException("This platform's C ABI has no '$name' layout")

    /** C `long`: eight bytes on LP64, four on Windows and 32-bit platforms. */
    @JvmField val C_LONG: ValueLayout = canonical("long")
    @JvmField val C_SIZE_T: ValueLayout = canonical("size_t")

    @JvmField val C_LONG_SIZE: Int = C_LONG.byteSize().toInt()
    @JvmField val SIZE_T_SIZE: Int = C_SIZE_T.byteSize().toInt()
    @JvmField val POINTER_SIZE: Int = ValueLayout.ADDRESS.byteSize().toInt()

    /** True where C `long` is narrower than a Java long and so travels as an int. */
    @JvmField val C_LONG_IS_INT: Boolean = C_LONG_SIZE == 4

    /* Enough for every C type we can lay out, so that a member's natural
     * alignment is always satisfied wherever it lands in the block. */
    private const val ALIGNMENT = 16L

    /**
     * Off-heap memory, released once it becomes unreachable. A zero-length
     * request still gets a byte, so that degenerate C types (an
     * attribute-less union, say) hand back a segment something can be
     * written to rather than one nothing can.
     */
    @JvmStatic
    fun allocate(size: Long): MemorySegment =
        Arena.ofAuto().allocate(if (size > 0) size else 1L, ALIGNMENT)

    /**
     * Wrap a raw address as a segment that can be dereferenced but whose
     * size we don't know. Only ever call this on addresses that came from
     * foreign code -- reinterpreting a segment we allocated detaches it from
     * its arena and so from the lifetime that keeps it alive.
     */
    @JvmStatic
    fun pointer(addr: Long): MemorySegment? =
        if (addr == 0L) null else MemorySegment.ofAddress(addr).reinterpret(Long.MAX_VALUE)

    /**
     * As above, for the zero-length segments foreign calls hand back. A
     * segment that already knows its own extent is left alone: reinterpreting
     * one of ours would cut it loose from the arena keeping it alive.
     */
    @JvmStatic
    fun unbounded(seg: MemorySegment?): MemorySegment? = when {
        seg == null || seg.address() == 0L -> null
        seg.byteSize() > 0L                -> seg
        else                               -> seg.reinterpret(Long.MAX_VALUE)
    }

    @JvmStatic
    fun address(seg: MemorySegment?): Long = seg?.address() ?: 0L

    /** MemorySegment.NULL for our null pointer, which is what C wants to see. */
    @JvmStatic
    fun orNull(seg: MemorySegment?): MemorySegment = seg ?: MemorySegment.NULL

    /* TODO: Handle encodings; everything here assumes UTF-8. */

    @JvmStatic
    fun toCString(value: String?): MemorySegment {
        val bytes = (value ?: "").toByteArray(StandardCharsets.UTF_8)
        val seg = Arena.ofAuto().allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
        return seg
    }

    @JvmStatic
    fun fromCString(seg: MemorySegment?): String? = unbounded(seg)?.getString(0)

    private val MALLOC by lazy {
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("malloc").orElseThrow {
                UnsatisfiedLinkError("This platform's C library exports no 'malloc'")
            },
            FunctionDescriptor.of(ValueLayout.ADDRESS, C_SIZE_T))
    }

    /**
     * A C string owned by C code. An explicitly-managed string passes out of
     * our hands entirely -- the callee is allowed to free() it, or to keep
     * the pointer for as long as it likes -- so it must come from the C
     * allocator: an arena's memory is a slice of a slab the arena will free
     * again on collection, and a foreign free() of it corrupts the process
     * heap. (The crash lands much later, wherever the allocator hands the
     * poisoned chunks next: seen as SIGSEGVs in G1CodeRootSet::add and
     * JVMCI's failed-speculation reader, and as glibc double-free aborts.)
     * Nothing on this side ever frees these; not freeing what nobody frees
     * is exactly the leak the caller signed up for.
     */
    @JvmStatic
    fun mallocCString(value: String?): MemorySegment {
        val bytes = (value ?: "").toByteArray(StandardCharsets.UTF_8)
        val size = bytes.size + 1L
        val raw = MALLOC.invokeWithArguments(size) as MemorySegment
        if (raw.address() == 0L)
            throw OutOfMemoryError("malloc of $size bytes for a C string failed")
        val seg = raw.reinterpret(size)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
        return seg
    }

    private val libraries = ConcurrentHashMap<String, SymbolLookup>()

    @JvmStatic
    fun libraryLookup(libname: String?): SymbolLookup =
        if (libname.isNullOrEmpty()) processLookup
        else libraries.computeIfAbsent(libname) { openLibrary(it) }

    /* Loaded in the global arena: a library stays mapped for the life of the
     * process, since call sites hold nothing but addresses into it. */
    private fun openLibrary(name: String): SymbolLookup {
        var failure: Throwable? = null
        for (candidate in libraryCandidates(name)) {
            try {
                return SymbolLookup.libraryLookup(candidate, Arena.global())
            }
            catch (e: IllegalArgumentException) {
                if (failure == null) failure = e
            }
        }
        throw UnsatisfiedLinkError("Cannot open library: $name").apply { initCause(failure) }
    }

    /**
     * dlopen (and its Windows equivalent) searches the platform's own library
     * path and nothing else, so the other places a library may be named from
     * have to be walked by hand: the platform's decorated spelling of a bare
     * name, and the directories in nqp.library.path -- which Rakudo's runner
     * points at the install's share directory -- and java.library.path.
     */
    private fun libraryCandidates(name: String): Collection<String> {
        val candidates = LinkedHashSet<String>()
        val bare = name.indexOf('/') < 0 && name.indexOf('\\') < 0

        candidates.add(name)
        if (bare) candidates.add(System.mapLibraryName(name))

        if (!File(name).isAbsolute) {
            val spellings = candidates.toList()
            for (property in arrayOf("nqp.library.path", "java.library.path")) {
                val path = System.getProperty(property) ?: continue
                for (dir in path.split(File.pathSeparator)) {
                    if (dir.isEmpty()) continue
                    for (spelling in spellings)
                        candidates.add(File(dir, spelling).path)
                }
            }
        }

        return candidates
    }

    /**
     * An empty library name means the process itself. dlsym against
     * RTLD_DEFAULT sees everything loaded globally, including libraries an
     * earlier native call pulled in; the linker's own default lookup only
     * covers the standard C and math libraries, so it is the fallback (and
     * the whole story on platforms without dlsym, such as Windows).
     */
    private val processLookup: SymbolLookup by lazy(::makeProcessLookup)

    private fun makeProcessLookup(): SymbolLookup {
        val fallback = LINKER.defaultLookup().or(SymbolLookup.loaderLookup())
        val dlsym = LINKER.defaultLookup().find("dlsym").orElse(null) ?: return fallback
        val handle = LINKER.downcallHandle(dlsym,
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))

        val rtldDefault = SymbolLookup { symbol ->
            val found = try {
                handle.invokeWithArguments(MemorySegment.NULL, toCString(symbol)) as MemorySegment
            }
            catch (t: Throwable) {
                MemorySegment.NULL
            }
            if (found.address() == 0L) Optional.empty() else Optional.of(found)
        }
        return rtldDefault.or(fallback)
    }
}
