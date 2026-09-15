package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.SixModelObject

class StaticCodeInfo private constructor(
    /**
     * The compilation unit where the code lives.
     */
    @JvmField var compUnit: CompilationUnit,
    mh: MethodHandle,
    /**
     * The compilation-unit unique ID of the routine (from QAST cuuid).
     */
    @JvmField var uniqueId: String?,
    /**
     * Static code object (base of any clones).
     */
    @JvmField var staticCode: SixModelObject?,
    /**
     * The expected arguments needed to invoke the method handle. Read on
     * the dispatch path before any body is needed, so never lazy.
     */
    @JvmField var argsExpectation: Short,
    private val bodySource: StaticBodySource?,
) : Cloneable {
    /**
     * The eager constructor: the adaptor and KnowHOW blocks, and the clone
     * road. Everything is present at construction.
     */
    constructor(compUnit: CompilationUnit, mh: MethodHandle, uniqueId: String?,
                oLexicalNames: Array<String>?, iLexicalNames: Array<String>?,
                nLexicalNames: Array<String>?, sLexicalNames: Array<String>?,
                handlers: Array<LongArray>?, staticCode: SixModelObject?, argsExpectation: Short)
        : this(compUnit, mh, uniqueId, staticCode, argsExpectation, null) {
        _oLexicalNames = oLexicalNames; _iLexicalNames = iLexicalNames
        _nLexicalNames = nLexicalNames; _sLexicalNames = sLexicalNames
        _handlers = handlers
        finishBody()
        bodyReady = true
    }

    /**
     * The shell constructor (the artifact road): identity now, the body
     * from [source] on first need.
     */
    constructor(compUnit: CompilationUnit, mh: MethodHandle, uniqueId: String?,
                argsExpectation: Short, staticCode: SixModelObject?, source: StaticBodySource)
        : this(compUnit, mh, uniqueId, staticCode, argsExpectation, source)

    // ---- the body: filled once, read through ensureBody() ----

    @Volatile private var bodyReady = false

    /**
     * One volatile read on the fast path; the slow path fills under the
     * monitor. Every body getter calls this, so no caller can see a shell
     * by mistake -- the failure mode lazy-loading task 1.1 surveyed.
     */
    fun ensureBody() { if (!bodyReady) fillBody() }

    @Synchronized private fun fillBody() {
        if (bodyReady) return
        val src = bodySource
            ?: throw IllegalStateException("StaticCodeInfo ${uniqueId ?: methodName}: no body and no source")
        src.fill(this)
        bodyReady = true
    }

    /**
     * Allocates the static-lexical arrays from the lexical names and spins
     * the two bound handles from the base handle; the tail of the old init
     * block. A source calls it after setting the name arrays.
     */
    internal fun finishBody() {
        _oLexicalNames?.let {
            _oLexStatic = arrayOfNulls(it.size)
            _oLexStaticFlags = ByteArray(it.size)
        }
        val base = _mh
        val t = base.type()
        if (t.parameterCount() == 5 && t.parameterType(4) == ResumeStatus.Frame::class.java) {
            /* Old way; goes away after bootstrap. */
            _mhResume = MethodHandles.insertArguments(base, 0, null, null, null, null)
            _mh = MethodHandles.insertArguments(base, 4, null as Any?)
        }
        else if (t.parameterCount() >= 4 && t.parameterType(3) == ResumeStatus.Frame::class.java) {
            var resume = MethodHandles.insertArguments(base, 0, null, null, null)
            when (argsExpectation) {
                ArgsExpectation.USE_BINDER ->
                    resume = MethodHandles.insertArguments(resume, 1, null as Any?)
                ArgsExpectation.NO_ARGS -> {
                    /* Nothing to insert. */
                }
                ArgsExpectation.OBJ ->
                    resume = MethodHandles.insertArguments(resume, 1, null as SixModelObject?)
                ArgsExpectation.OBJ_OBJ ->
                    resume = MethodHandles.insertArguments(resume, 1,
                        null as SixModelObject?, null as SixModelObject?)
                else ->
                    throw RuntimeException("Unhandled ArgsExpectation in StaticCodeInfo")
            }
            _mhResume = resume
            _mh = MethodHandles.insertArguments(base, 3, null as Any?)
        }
    }

    private var _mh: MethodHandle = mh
    /**
     * Method handle for the code ref (bound; the body). (Package-private in
     * Java; Kotlin has no package visibility, and ArgsExpectation/CallFrame
     * read it.)
     */
    var mh: MethodHandle
        get() { ensureBody(); return _mh }
        set(v) { _mh = v }

    private var _mhResume: MethodHandle? = null
    /**
     * Curried method handle for resuming. (Package-private in Java.)
     */
    var mhResume: MethodHandle?
        get() { ensureBody(); return _mhResume }
        set(v) { _mhResume = v }

    /**
     * Names of the lexicals we have of each of the base types.
     */
    private var _oLexicalNames: Array<String>? = null
    var oLexicalNames: Array<String>?
        get() { ensureBody(); return _oLexicalNames }
        set(v) { _oLexicalNames = v }
    private var _iLexicalNames: Array<String>? = null
    var iLexicalNames: Array<String>?
        get() { ensureBody(); return _iLexicalNames }
        set(v) { _iLexicalNames = v }
    private var _nLexicalNames: Array<String>? = null
    var nLexicalNames: Array<String>?
        get() { ensureBody(); return _nLexicalNames }
        set(v) { _nLexicalNames = v }
    private var _sLexicalNames: Array<String>? = null
    var sLexicalNames: Array<String>?
        get() { ensureBody(); return _sLexicalNames }
        set(v) { _sLexicalNames = v }

    /**
     * Map of handlers.
     */
    private var _handlers: Array<LongArray>? = null
    var handlers: Array<LongArray>?
        get() { ensureBody(); return _handlers }
        set(v) { _handlers = v }

    /**
     * Static lexicals.
     */
    private var _oLexStatic: Array<SixModelObject?>? = null
    var oLexStatic: Array<SixModelObject?>?
        get() { ensureBody(); return _oLexStatic }
        set(v) { _oLexStatic = v }

    /**
     * Flags for each static lexical usage.
     */
    private var _oLexStaticFlags: ByteArray? = null
    var oLexStaticFlags: ByteArray?
        get() { ensureBody(); return _oLexStaticFlags }
        set(v) { _oLexStaticFlags = v }

    /**
     * Does this code object have a block exit handler?
     */
    private var _hasExitHandler = false
    var hasExitHandler: Boolean
        get() { ensureBody(); return _hasExitHandler }
        set(v) { _hasExitHandler = v }

    /**
     * Is this code object marked as a thunk?
     */
    private var _isThunk = false
    var isThunk: Boolean
        get() { ensureBody(); return _isThunk }
        set(v) { _isThunk = v }

    /** Source location of the block's declaration, from the QAST node it was
     * compiled from; null/-1 when the compiler had none to give. */
    private var _sourceFile: String? = null
    var sourceFile: String?
        get() { ensureBody(); return _sourceFile }
        set(v) { _sourceFile = v }
    private var _sourceLine = -1
    var sourceLine: Int
        get() { ensureBody(); return _sourceLine }
        set(v) { _sourceLine = v }

    /** rawLine - sourceLine of the block's declaration: the constant shift
     * a #line directive puts between the raw compiled source (which the
     * LineNumberTable rows use) and sourceFile's numbering. */
    private var _sourceLineDelta = 0
    var sourceLineDelta: Int
        get() { ensureBody(); return _sourceLineDelta }
        set(v) { _sourceLineDelta = v }

    /** Intra-body #line directive sections, sorted by raw line: from
     * sourceSectionRaw[i] onward the code reads as sourceSectionFile[i]
     * starting at line sourceSectionLine[i]. Null for the common body
     * with no directive of its own. */
    private var _sourceSectionRaw: IntArray? = null
    var sourceSectionRaw: IntArray?
        get() { ensureBody(); return _sourceSectionRaw }
        set(v) { _sourceSectionRaw = v }
    private var _sourceSectionLine: IntArray? = null
    var sourceSectionLine: IntArray?
        get() { ensureBody(); return _sourceSectionLine }
        set(v) { _sourceSectionLine = v }
    private var _sourceSectionFile: Array<String>? = null
    var sourceSectionFile: Array<String>?
        get() { ensureBody(); return _sourceSectionFile }
        set(v) { _sourceSectionFile = v }

    // ---- the shell: identity and run-time state, plain fields ----

    /**
     * How many invocations of this block are live right now (entered and
     * not yet left), across all threads. A code ref with no outer searches
     * the caller chain for a live invocation of its outer block on every
     * frame construction; when that block has no live invocation the search
     * can only fail, and this count lets it be skipped -- a JFR profile of
     * the CORE.c compile put the always-failing search at 14% of all
     * samples. Only ever an overestimate: a frame that leaves through
     * neither leave() nor leaveTorn() (the dieInternal-in-the-catch-arm
     * road) stays counted and keeps the search -- never a wrong skip. A
     * frame the unwinder tears past does give its count back, through
     * leaveTorn(), which also runs its exit handler.
     */
    @JvmField val liveInvocations = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The code engine's compiled program for this block, once it has run
     * once through [CodeEngines.codeRun]; null for a bytecode body or a
     * block not yet entered. Typed loosely because nqp-runtime cannot see
     * the engine's classes; it is a Truffle CallTarget. An engine-side
     * dispatch that resolves to this code enters the target directly,
     * building the frame the emitted stub would have built.
     */
    @JvmField @Volatile var engineTarget: Any? = null

    /** On the artifact road: the block's program index in its unit, so
     *  the target can be materialized on demand (see CodeEngines.materialize)
     *  instead of waiting for a first run through a stub. -1 on the class road. */
    @JvmField var programIndex: Int = -1

    /** True when the code ref's body is ProgramEntry.enter (the artifact
     *  road): callers may enter it directly instead of through the handle. */
    @JvmField var unitEntry: Boolean = false

    /**
     * Method name for correlation with stack traces.
     */
    @JvmField var methodName: String? = null

    /**
     * Static outer.
     */
    @JvmField var outerStaticInfo: StaticCodeInfo? = null

    /**
     * Most recent invocation, if any.
     */
    @JvmField var priorInvocation: CallFrame? = null

    /**
     * Deserialized contexts waiting for a frame of this code to become their
     * outer. A compilation unit's serialization context is read before its
     * mainline runs, so contexts that had no outer serialized cannot be
     * hooked up at the time they are created; the frame adopts them when it
     * finally shows up. Null whenever there is nothing waiting, which is the
     * overwhelmingly common case.
     */
    @JvmField var contextsAwaitingOuter: ArrayList<CallFrame>? = null

    /**
     * Lexical name maps (produced lazily on first use). Note they are only
     * used when we do lexical lookup by name.
     */
    private var oLexicalMap: Object2IntOpenHashMap<String>? = null
    private var iLexicalMap: Object2IntOpenHashMap<String>? = null
    private var nLexicalMap: Object2IntOpenHashMap<String>? = null
    private var sLexicalMap: Object2IntOpenHashMap<String>? = null

    /** The directive section a raw line falls in, or -1 for the part of
     * the body before any section (covered by the method-level mapping). */
    fun sourceSectionFor(rawLine: Int): Int {
        ensureBody()
        val raws = _sourceSectionRaw ?: return -1
        var i = raws.size - 1
        while (i >= 0 && raws[i] > rawLine)
            i--
        return i
    }

    fun oTryGetLexicalIdx(name: String): Int {
        ensureBody()
        val names = _oLexicalNames
        if (names != null) {
            var map = oLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                oLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun iTryGetLexicalIdx(name: String): Int {
        ensureBody()
        val names = _iLexicalNames
        if (names != null) {
            var map = iLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                iLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun uTryGetLexicalIdx(name: String): Int {
        ensureBody()
        val names = _iLexicalNames
        if (names != null) {
            var map = iLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                iLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun nTryGetLexicalIdx(name: String): Int {
        ensureBody()
        val names = _nLexicalNames
        if (names != null) {
            var map = nLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                nLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun sTryGetLexicalIdx(name: String): Int {
        ensureBody()
        val names = _sLexicalNames
        if (names != null) {
            var map = sLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                sLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    public override fun clone(): StaticCodeInfo {
        ensureBody()   // a clone is always ready: it must never fill again from a source it does not own
        try {
            val result = super.clone() as StaticCodeInfo
            result._oLexStatic?.let {
                result._oLexStatic = it.clone()
                result._oLexStaticFlags = result._oLexStaticFlags!!.clone()
            }
            return result
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
