package org.raku.nqp.runtime

import java.util.HashMap

/**
 * All compilation units inherit from this class. A compilation unit holds the
 * code that came from a single QAST::CompUnit. Every unit is hand-written
 * Kotlin: ProgramUnit, where a QAST::Block is a unit record plus an engine
 * program; KnowHOWMethods; and AdaptorUnit, the unit behind a generated
 * Java-interop adaptor class. The latter two hand their code refs to
 * initializeCompilationUnit through getCodeRefs(); no generated subclass of
 * this class exists any more, so nothing here is reflected over.
 */
abstract class CompilationUnit {
    /**
     * Mapping of compilation unit unqiue IDs to matching code reference.
     */
    private val cuidToCodeRef = HashMap<String, CodeRef>()

    /**
     * Mapping of local integer IDs to matching code reference.
     */
    @JvmField var qbidToCodeRef: Array<CodeRef?>? = null

    /**
     * Array of all code references.
     */
    @JvmField var codeRefs: Array<CodeRef>? = null

    /**
     * Call site descriptors used in this compilation unit.
     */
    @JvmField var callSites: Array<CallSiteDescriptor>? = null

    /**
     * HLL configuration for this compilation unit.
     */
    lateinit var hllConfig: HLLConfig

    /**
     * If true, the class corresponding to this CompilationUnit is shared between GlobalContexts.
     */
    @JvmField var shared = false

    /**
     * Fills the code-ref tables from getCodeRefs(), which a hand-written unit
     * (KnowHOWMethods, AdaptorUnit) supplies; a ProgramUnit overrides this
     * with its block table.
     */
    open fun initializeCompilationUnit(tc: ThreadContext, runDeserialize: Boolean) {
        val bootSt = tc.gc.BOOTCode?.st
        val refs = getCodeRefs()
        codeRefs = refs
        qbidToCodeRef = arrayOfNulls<CodeRef>(refs.size).also { t ->
            for (i in refs.indices) t[i] = refs[i]
        }
        for (c in refs) {
            if (bootSt != null) c.st = bootSt
            c.staticInfo.uniqueId?.let { cuidToCodeRef.put(it, c) }
        }

        /* Build callsite descriptors. */
        callSites = getCallSites()

        /* Get HLL configuration object. */
        hllConfig = tc.gc.getHLLConfigFor(this.hllName())

        /* Run any deserialization code, unless the caller wants to run it
         * later itself: a nested unit claimed while its enclosing unit is
         * mid-deserialization must not touch the still-empty SC. */
        if (runDeserialize)
            runDeserializeIfAvailable(tc)
    }

    fun initializeCompilationUnit(tc: ThreadContext) {
        initializeCompilationUnit(tc, true)
    }

    open fun runDeserializeIfAvailable(tc: ThreadContext) {
        var desCodeRef: CodeRef? = null
        if (deserializeQbid() >= 0)
            desCodeRef = lookupCodeRef(deserializeQbid())
        if (desCodeRef != null)
            try {
                Ops.invokeArgless(tc, desCodeRef)
            }
            catch (e: ControlException) {
                throw e
            }
            catch (e: Exception) {
                throw ExceptionHandling.dieInternal(tc, e.toString())
            }
    }

    /**
     * Runs code in the on-load hook, if one is available.
     */
    open fun runLoadIfAvailable(tc: ThreadContext) {
        var loadCodeRef: CodeRef? = null
        if (loadQbid() >= 0)
            loadCodeRef = lookupCodeRef(loadQbid())
        if (loadCodeRef != null)
            try {
                Ops.invokeArgless(tc, loadCodeRef)
            }
            catch (e: ControlException) {
                throw e
            }
            catch (e: Exception) {
                throw ExceptionHandling.dieInternal(tc, e.toString())
            }
    }

    /**
     * Turns a compilation unit unique ID into the matching code-ref.
     */
    open fun lookupCodeRef(uniqueId: String): CodeRef? {
        return cuidToCodeRef.get(uniqueId)
    }

    /**
     * Turns a local integer ID into the matching code-ref.
     */
    open fun lookupCodeRef(localId: Int): CodeRef? {
        return qbidToCodeRef!![localId]
    }

    /**
     * The non-reflective code-ref hook: a unit that builds its own code
     * refs (ProgramUnit, KnowHOWMethods, the interop adaptors) overrides
     * this and initializeCompilationUnit fills the tables from it.
     */
    open fun getCodeRefs(): Array<CodeRef> = arrayOf()

    /**
     * Code generation emits this to build up all the callsite descriptors
     * that are used by this compilation unit.
     */
    abstract fun getCallSites(): Array<CallSiteDescriptor>

    /**
     * Code generation emits this to supply the HLL name from QAST::CompUnit.
     */
    abstract fun hllName(): String

    /**
     * Code generation overrides this if there's an SC to deserialize.
     */
    open fun deserializeQbid(): Int = -1

    /**
     * Code generation overrides this if there's an SC to deserialize.
     */
    open fun loadQbid(): Int = -1

    /**
     * Code generation overrides this with the mainline blcok.
     */
    open fun mainlineQbid(): Int = -1

    /**
     * Code generation overrides this with the entry-point block, if any.
     */
    open fun entryQbid(): Int = -1

    open fun serializedCodeRefCount(): Int = -1

    /** The unit's identity string: the artifact's unit id on the unit
     *  road, the class's simple name for a unit that is not an artifact
     *  (KnowHOWMethods, the interop adaptors). Replaces the Class object
     *  wherever a unit was named. */
    open fun unitId(): String = javaClass.simpleName

    /** The serialized context, or null when the unit has none. Only a
     *  unit artifact carries one. */
    open fun serializedBlob(): java.nio.ByteBuffer? =
        throw IllegalStateException("serializedBlob has no meaning on a ${javaClass.simpleName} unit")

    /** Instantiates and initializes (without deserializing) the nested
     *  unit of the given name that rides in this unit. Only a unit
     *  artifact carries nested units. */
    open fun claimNested(tc: ThreadContext, name: String): CompilationUnit =
        throw IllegalStateException("claimNested has no meaning on a ${javaClass.simpleName} unit")

    /**
     * The unit's engine program at the given index; emitted bodies
     * reference them through [CodeEngine.codeRunIdx]. Only a unit
     * artifact carries programs.
     */
    open fun engineProgram(idx: Int): String =
        throw IllegalStateException("engineProgram has no meaning on a ${javaClass.simpleName} unit")
}
