package org.raku.nqp.runtime

import java.io.InputStream
import java.io.PrintStream
import java.io.UnsupportedEncodingException
import java.lang.ref.WeakReference
import java.util.HashMap
import java.util.Timer
import java.util.WeakHashMap

import org.raku.nqp.dispatch.DispatchRegistry
import org.raku.nqp.sixmodel.CodePairContainerConfigurer
import org.raku.nqp.sixmodel.ContainerConfigurer
import org.raku.nqp.sixmodel.KnowHOWBootstrapper
import org.raku.nqp.sixmodel.NativeRefContainerConfigurer
import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance

class GlobalContext {
    /* The BOOT* type objects (and friends) are created by
     * KnowHOWBootstrapper part-way through the constructor below, so all
     * of them are honestly nullable: CompilationUnit, for one, checks
     * BOOTCode against null for compilation units initialized before the
     * MOP exists. */

    /**
     * The KnowHOW.
     */
    @JvmField var KnowHOW: SixModelObject? = null

    /**
     * The KnowHOWAttribute.
     */
    @JvmField var KnowHOWAttribute: SixModelObject? = null

    /**
     * BOOTArray type; a basic, method-less type with the VMArray REPR.
     */
    @JvmField var BOOTArray: SixModelObject? = null

    /**
     * BOOTHash type; a basic, method-less type with the VMHash REPR.
     */
    @JvmField var BOOTHash: SixModelObject? = null

    /**
     * BOOTIter type; a basic, method-less type with the VMIter REPR.
     */
    @JvmField var BOOTIter: SixModelObject? = null

    /**
     * BOOTInt type; a basic, method-less type with the P6int REPR.
     */
    @JvmField var BOOTInt: SixModelObject? = null

    /**
     * BOOTNum type; a basic, method-less type with the P6num REPR.
     */
    @JvmField var BOOTNum: SixModelObject? = null

    /**
     * BOOTStr type; a basic, method-less type with the P6str REPR.
     */
    @JvmField var BOOTStr: SixModelObject? = null

    /**
     * BOOTCode type; a basic, method-less type with the CodeRef REPR.
     */
    @JvmField var BOOTCode: SixModelObject? = null

    /**
     * SCRef type; a basic, method-less type with the SCRef REPR.
     */
    @JvmField var SCRef: SixModelObject? = null

    /**
     * Continuation type; a basic, method-less type with the Continuation REPR.
     */
    @JvmField var Continuation: SixModelObject? = null

    /**
     * ContextRef type; a basic, method-less type with the ContextRef REPR.
     */
    @JvmField var ContextRef: SixModelObject? = null

    /**
     * CallCapture type; a basic, method-less type with the CallContext REPR.
     */
    @JvmField var CallCapture: SixModelObject? = null

    /**
     * Tracked type; a basic, method-less type with the Tracked REPR, used for
     * the values a dispatcher tracks while recording a dispatch program.
     */
    @JvmField var Tracked: SixModelObject? = null

    /**
     * The dispatchers this program can reach; the boot dispatchers are always
     * in there and a language registers its own.
     */
    @JvmField val dispatchers = DispatchRegistry()

    /**
     * VMNull type; a basic, method-less type with the VMNull REPR.
     */
    @JvmField var VMNull: SixModelObject? = null

    /**
     * Thread type; a basic, method-less type with the Thread REPR.
     */
    @JvmField var Thread: SixModelObject? = null

    /**
     * BOOTException type; a basic, method-less type with the VMException REPR.
     */
    @JvmField var BOOTException: SixModelObject? = null

    /**
     * BOOTIO type; a basic, method-less type with the IOHandle REPR.
     */
    @JvmField var BOOTIO: SixModelObject? = null

    /**
     * BOOTJava type; a basic, method-less type with the JavaWrap REPR.
     */
    @JvmField var BOOTJava: SixModelObject? = null

    /**
     * Typed VMArrays.
     */
    @JvmField var BOOTIntArray: SixModelObject? = null
    @JvmField var BOOTNumArray: SixModelObject? = null
    @JvmField var BOOTStrArray: SixModelObject? = null

    /**
     * Multi-dispatch cache type.
     */
    @JvmField var MultiCache: SixModelObject? = null

    /**
     * The main, startup thread's ThreadContext. (Nulled by exit().)
     */
    @JvmField var mainThread: ThreadContext? = null

    /**
     * Timer object, used by nqp::timer.
     */
    @JvmField var timer: Timer

    /**
     * Active HLL configuration (maps HLL name to the configuration).
     */
    private var hllConfiguration: HashMap<String, HLLConfig>

    /**
     * HLL configuration of the compiler. We need to distinguish it from
     * the HLL configuration of the code being compiled in bootstrap.
     */
    private var compilerHLLConfiguration: HashMap<String, HLLConfig>

    /**
     * HLL configuration of the compilee (see above).
     */
    private var compileeHLLConfiguration: HashMap<String, HLLConfig>

    /**
     * HLL global symbols.
     */
    @JvmField var hllSyms: HashMap<String, HashMap<String, SixModelObject?>>

    /**
     * Compiler registry.
     */
    @JvmField var compilerRegistry: HashMap<String, SixModelObject?>

    /**
     * Container configurer registry.
     */
    @JvmField var contConfigs: HashMap<String, ContainerConfigurer>

    /**
     * Serialization context lookup hash.
     */
    @JvmField var scs: HashMap<String, SerializationContext>

    /**
     * Serialization context wrapper object hash.
     */
    @JvmField var scRefs: HashMap<String, SixModelObject>

    /**
     * Whether to dump VM-level stack traces for all exceptions.
     */
    @JvmField var noisyExceptions = false

    /**
     * The global ByteClassLoader instance, used to load classes generated at
     * runtime.
     */
    @JvmField var byteClassLoader: ByteClassLoader

    /** Redirected output for eval-server. */
    @JvmField var out: PrintStream
    /** Redirected error for eval-server. */
    @JvmField var err: PrintStream
    /** Redirected input for eval-server. */
    @JvmField var `in`: InputStream = System.`in`
    /** Whether to disallow exit. */
    @JvmField var interceptExit = false
    /** If true, we're killing all threads, so disable exception handling. */
    @Volatile @JvmField var shuttingDown = false
    /** Exit status or -1. */
    @JvmField var exitStatus = -1

    // odds and ends nqp wants (package-private in Java; Kotlin has no
    // package visibility)
    @JvmField var compileeDepth = 0

    /** If true, libraries will be loaded in shared mode by default. */
    @JvmField var sharingHint = false
    /** Interop object used for jvmbootinterop. */
    @JvmField var bootInterop: BootJavaInterop

    @JvmField var hllGlobalAll: HashMap<ContextKey<*, *>, Any>
    @JvmField var hllGlobalAllLock: Any

    /* In-memory compiled units retained for nested-unit persistence: an
     * EVAL compiled during a precompilation may need its classfile embedded
     * in the enclosing unit's output, so a precompiled module can restore
     * the code refs its serialized graph points into. Keyed by class name,
     * with a cuid-to-class index alongside. Only populated while a
     * compiling SC is on the stack, so ordinary runtime EVALs cost nothing. */
    @JvmField val inMemoryUnitBytes: java.util.concurrent.ConcurrentHashMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap()
    @JvmField val inMemoryUnitOfCuid: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /* The record road's twin of inMemoryUnitBytes: a unit compiled in
     * memory under NQP_UNIT while a compilation is under way, kept as its
     * record so the enclosing unit's writer can embed it under nested/
     * and a record parent can claim it. Keyed by unit id (the JAST class
     * name); inMemoryUnitOfCuid indexes into it on either road. */
    @JvmField val inMemoryUnitRecords: java.util.concurrent.ConcurrentHashMap<String, org.raku.nqp.runtime.unit.UnitRecord> = java.util.concurrent.ConcurrentHashMap()
    /* Nested units claimed mid-deserialization, awaiting their own
     * deserialization code run (jvm-finish-nested). */
    @JvmField val claimedNestedUnits: java.util.concurrent.ConcurrentHashMap<String, CompilationUnit> = java.util.concurrent.ConcurrentHashMap()

    @JvmField var currentThreadCtxRef: ThreadLocal<WeakReference<ThreadContext>>?
    @JvmField var allThreads: WeakHashMap<java.lang.Thread, ThreadContext>

    /** Objects we will never repossess. */
    @JvmField var neverRepossess: WeakHashMap<SixModelObject, Any>

    /**
     * Initializes the runtime environment.
     */
    init {
        try {
            out = PrintStream(System.out, true, "UTF-8")
            err = PrintStream(System.err, true, "UTF-8")
        }
        catch (e: UnsupportedEncodingException) {
            throw RuntimeException(e)
        }

        compileeHLLConfiguration = HashMap<String, HLLConfig>()
        hllConfiguration = compileeHLLConfiguration
        getHLLConfigFor("")
        compilerHLLConfiguration = HashMap<String, HLLConfig>()
        hllConfiguration = compilerHLLConfiguration
        getHLLConfigFor("")

        scs = HashMap<String, SerializationContext>()
        scRefs = HashMap<String, SixModelObject>()
        compilerRegistry = HashMap<String, SixModelObject?>()
        hllSyms = HashMap<String, HashMap<String, SixModelObject?>>()

        contConfigs = HashMap<String, ContainerConfigurer>()
        contConfigs.put("code_pair", CodePairContainerConfigurer())
        contConfigs.put("native_ref", NativeRefContainerConfigurer())

        currentThreadCtxRef = ThreadLocal<WeakReference<ThreadContext>>()
        allThreads = WeakHashMap<java.lang.Thread, ThreadContext>()

        mainThread = getCurrentThreadContext()
        timer = Timer(true)
        KnowHOWBootstrapper.bootstrap(mainThread!!)
        bootInterop = BootJavaInterop(this)

        // BOOT* not available earlier; fixup some stuff.
        setupConfig(compileeHLLConfiguration.get("")!!)
        setupConfig(compilerHLLConfiguration.get("")!!)
        mainThread!!.savedCC = CallCapture!!.st.REPR.allocate(mainThread!!, CallCapture!!.st) as CallCaptureInstance
        noisyExceptions = System.getenv("NQP_VERBOSE_EXCEPTIONS") != null

        hllGlobalAll = HashMap<ContextKey<*, *>, Any>()
        hllGlobalAllLock = Any()

        neverRepossess = WeakHashMap<SixModelObject, Any>()

        byteClassLoader = ByteClassLoader(javaClass.getClassLoader())
    }

    /**
     * Gets HLL configuration object for the specified language.
     */
    fun getHLLConfigFor(language: String): HLLConfig {
        synchronized(hllConfiguration) {
            var config = hllConfiguration.get(language)
            if (config == null) {
                config = HLLConfig()
                config.name = language
                setupConfig(config)
                hllConfiguration.put(language, config)
            }
            return config
        }
    }

    private fun setupConfig(config: HLLConfig) {
        config.intBoxType = BOOTInt
        config.numBoxType = BOOTNum
        config.strBoxType = BOOTStr
        config.listType = BOOTArray
        config.hashType = BOOTHash
        config.slurpyArrayType = BOOTArray
        config.slurpyHashType = BOOTHash
        config.arrayIteratorType = BOOTIter
        config.hashIteratorType = BOOTIter
        config.exceptionType = BOOTException
        config.ioType = BOOTIO
    }

    fun useCompileeHLLConfig() {
        this.hllConfiguration = this.compileeHLLConfiguration
    }

    fun useCompilerHLLConfig() {
        this.hllConfiguration = this.compilerHLLConfiguration
    }

    fun exit(status: Int) {
        if (!interceptExit) System.exit(status)

        if (exitStatus < 0) exitStatus = status
        shuttingDown = true

        for (th in allThreads.keys) {
            if (java.lang.Thread.currentThread() === th)
                continue
            th.interrupt()
        }
        /* The timer owns a live thread, and a live thread is a GC root: left
         * running it keeps this context, and so the whole setting it loaded,
         * reachable for as long as the process lives. That costs nothing when
         * the process is about to end, but a server that runs many programs in
         * turn accumulates one per run -- 45 of them after 45 runs, with the
         * heap pinned at its ceiling and runs quietly producing no output. */
        timer.cancel()

        mainThread = null
        currentThreadCtxRef = null
        @Suppress("DEPRECATION")
        throw ThreadDeath()
    }

    /** Gets the context object for the current thread, creating one if needed. */
    fun getCurrentThreadContext(): ThreadContext? {
        // The implementation here is complicated by GC concerns.  A simple
        // ThreadLocal<ThreadContext> would, with the current (1.7)
        // implementation of ThreadLocal, indefinitely retain a strong
        // reference to the ThreadContext - and anything that can leak
        // references that retain a GlobalContext is potentially very bad.
        // OTOH, we do want to reuse threadcontexts (they include important
        // states like srand seeds), so we can't have them garbage collected at
        // random just because no NQP code is running.

        // Note that this implementation *does* retain strong references from
        // the GlobalContext to the ThreadContext longer than strictly
        // necessary.  This is judged to be a minor issue, because
        // ThreadContexts retain much less than the full GlobalContext.

        val tcRef = currentThreadCtxRef!!.get()

        // the ref cannot be cleared while the thread is alive because the object
        // is retained by the allThreads map
        if (tcRef != null) return tcRef.get()

        val tc = ThreadContext(this)
        synchronized(this) { allThreads.put(java.lang.Thread.currentThread(), tc) }
        currentThreadCtxRef!!.set(WeakReference(tc))
        return tc
    }
}
