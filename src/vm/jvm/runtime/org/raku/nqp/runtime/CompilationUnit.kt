package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.HashMap

import org.raku.nqp.sixmodel.STable

/**
 * All compilation units inherit from this class. A compilation unit contains
 * code generated from a single QAST::CompUnit, with each QAST::Block turning
 * into a method in the compilation unit. (Generated subclasses override the
 * open methods below, so they must stay open.)
 */
abstract class CompilationUnit {
    companion object {
        private fun getCodeInfo(cls: Class<*>): Array<ReflectiveCodeInfo> {
            val ret = ArrayList<ReflectiveCodeInfo>()
            val l = MethodHandles.lookup()
            for (m in cls.getDeclaredMethods()) {
                val cra = m.getAnnotation(CodeRefAnnotation::class.java)
                if (cra != null) ret.add(ReflectiveCodeInfo(l, m, cra))
            }
            return ret.toTypedArray()
        }

        private val codeInfoStash = object : ClassValue<Array<ReflectiveCodeInfo>>() {
            override fun computeValue(c: Class<*>): Array<ReflectiveCodeInfo> = getCodeInfo(c)
        }
    }

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
     * Does initialization work for the compilation unit.
     */
    open fun initializeCompilationUnit(tc: ThreadContext, runDeserialize: Boolean) {
        /* Look through methods for code refs. */
        val BOOTCodeSTable: STable? = tc.gc.BOOTCode?.st
        val codeRefList = ArrayList<CodeRef>()
        val outerCuid = ArrayList<CodeRefAnnotation>()
        var codeRefsFound = false

        val mlist = if (shared) codeInfoStash.get(javaClass) else getCodeInfo(javaClass)
        /* Sized by the highest qbid, not by the number of methods: a qbid is
         * also handed out for a block that registered static lexical values
         * but was not compiled into this unit, so the ids are sparse and the
         * highest one can exceed the method count. */
        var maxQbid = -1
        for (m in mlist)
            if (m.qbid > maxQbid) maxQbid = m.qbid
        val qbidToCodeRef = arrayOfNulls<CodeRef>(
            if (maxQbid + 1 > mlist.size) maxQbid + 1 else mlist.size)
        this.qbidToCodeRef = qbidToCodeRef

        for (m in mlist) {
            val ann = m.annotation

            val cuid = ann.cuid
            val cr = CodeRef(this, m.mh.bindTo(this), ann.name, cuid,
                if (ann.oLexicalNames.isEmpty()) null else ann.oLexicalNames,
                if (ann.iLexicalNames.isEmpty()) null else ann.iLexicalNames,
                if (ann.nLexicalNames.isEmpty()) null else ann.nLexicalNames,
                if (ann.sLexicalNames.isEmpty()) null else ann.sLexicalNames,
                m.handlers, ann.argsExpectation)
            cr.staticInfo.methodName = m.methodName
            cr.staticInfo.hasExitHandler = ann.hasExitHandler
            cr.staticInfo.isThunk = ann.isThunk
            if (ann.sourceFile.isNotEmpty()) {
                cr.staticInfo.sourceFile = ann.sourceFile
                cr.staticInfo.sourceLine = ann.sourceLine
                cr.staticInfo.sourceLineDelta = ann.sourceLineDelta
                if (ann.sourceSectionRaw.isNotEmpty()) {
                    cr.staticInfo.sourceSectionRaw = ann.sourceSectionRaw
                    cr.staticInfo.sourceSectionLine = ann.sourceSectionLine
                    cr.staticInfo.sourceSectionFile = ann.sourceSectionFile
                }
            }
            if (BOOTCodeSTable != null)
                cr.st = BOOTCodeSTable
            codeRefList.add(cr)

            if (m.qbid >= 0 && m.qbid < qbidToCodeRef.size) qbidToCodeRef[m.qbid] = cr

            /* Stash outer, for later resolution. */
            outerCuid.add(ann)
            codeRefsFound = true
        }

        /* Resolve outers. */
        var codeRefs = codeRefList.toTypedArray()
        this.codeRefs = codeRefs
        for (i in codeRefs.indices) {
            val cra = outerCuid[i]
            val qbid = cra.outerQbid

            val outer = if (qbid >= 0) qbidToCodeRef[qbid] else null
            if (outer != null)
                codeRefs[i].staticInfo.outerStaticInfo = outer.staticInfo
        }

        /* If we didn't find any by annotations, this is the fallback. */
        if (!codeRefsFound) {
            codeRefs = getCodeRefs()
            this.codeRefs = codeRefs
            for (c in codeRefs) {
                if (BOOTCodeSTable != null)
                    c.st = BOOTCodeSTable
                cuidToCodeRef.put(c.staticInfo.uniqueId!!, c)
            }
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

    private class ReflectiveCodeInfo(l: MethodHandles.Lookup, m: Method, cra: CodeRefAnnotation) {
        @JvmField val mh: MethodHandle
        @JvmField val handlers: Array<LongArray>
        @JvmField val annotation: CodeRefAnnotation = cra
        @JvmField val methodName: String
        @JvmField val qbid: Int

        init {
            /* Got a code ref annotation. Turn to method handle. */
            mh = try {
                l.unreflect(m)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }

            /* Munge handlers. */
            val flatHandlers = cra.handlers
            var hptr = 0
            val numHandlers = flatHandlers[hptr++].toInt()
            handlers = Array(numHandlers) { LongArray(0) }
            for (i in 0 until numHandlers) {
                val handlerThings = flatHandlers[hptr++].toInt()
                val handler = LongArray(handlerThings)
                handlers[i] = handler
                for (j in 0 until handlerThings)
                    handler[j] = flatHandlers[hptr++]
            }

            methodName = m.getName()

            var acc = 0
            var foundQbid = -1
            if (methodName.startsWith("qb_")) {
                var i = 3
                val imax = methodName.length
                while (i < imax) acc = acc * 10 + (methodName[i++].code - '0'.code)
                if (acc >= 0) foundQbid = acc
            }
            qbid = foundQbid
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
