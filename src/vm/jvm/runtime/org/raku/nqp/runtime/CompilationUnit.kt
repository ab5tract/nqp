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
        /**
         * When a compilation unit is serving as the main entry point, its main
         * method will just delegate to here. Thus this needs to trigger some
         * initialization work and then invoke the required main code.
         */
        @JvmStatic
        @Throws(Exception::class)
        fun enterFromMain(cuType: Class<*>, entryCodeRefIdx: Int, argv: Array<String>) {
            val tc = GlobalContext().mainThread!!
            val cu = setupCompilationUnit(tc, cuType, false)
            Ops.invokeMain(tc, cu.qbidToCodeRef!![entryCodeRefIdx], cuType.getName(), argv)
        }

        /**
         * Takes the class object for some compilation unit and sets it up.
         */
        @JvmStatic
        @Throws(InstantiationException::class, IllegalAccessException::class)
        fun setupCompilationUnit(tc: ThreadContext, cuType: Class<*>, shared: Boolean): CompilationUnit {
            @Suppress("DEPRECATION")
            val cu = cuType.newInstance() as CompilationUnit
            cu.shared = shared
            cu.initializeCompilationUnit(tc)
            return cu
        }

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
    @JvmField var hllConfig: HLLConfig? = null

    /**
     * If true, the class corresponding to this CompilationUnit is shared between GlobalContexts.
     */
    @JvmField var shared = false

    /**
     * Does initialization work for the compilation unit.
     */
    open fun initializeCompilationUnit(tc: ThreadContext) {
        /* Look through methods for code refs. */
        val BOOTCodeSTable: STable? = tc.gc.BOOTCode?.st
        val codeRefList = ArrayList<CodeRef>()
        val outerCuid = ArrayList<CodeRefAnnotation>()
        var codeRefsFound = false

        val mlist = if (shared) codeInfoStash.get(javaClass) else getCodeInfo(javaClass)
        val qbidToCodeRef = arrayOfNulls<CodeRef>(mlist.size)
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
            codeRefs = getCodeRefs()!!
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

        /* Run any deserialization code. */
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
    open fun lookupCodeRef(uniqueId: String): CodeRef? { /*FOR_STAGE0*/
        return cuidToCodeRef.get(uniqueId)
    }

    /**
     * Turns a local integer ID into the matching code-ref.
     */
    open fun lookupCodeRef(localId: Int): CodeRef? {
        return qbidToCodeRef!![localId]
    }

    /**
     * Parses a bunch of info on static lexical values for a block and
     * installs each of them. TODO: lazify so we don't do it for blocks we
     * never execute.
     */
    open fun setLexValues(tc: ThreadContext, localId: Int, toParse: String) {
        setLexValues(tc, qbidToCodeRef!![localId]!!, toParse)
    }

    private fun setLexValues(tc: ThreadContext, cr: CodeRef, toParse: String) {
        val bits = toParse.split("\u0000")
        var i = 0
        while (i < bits.size) {
            val lexName = bits[i]
            val handle = bits[i + 1]
            val scIdx = Integer.parseInt(bits[i + 2])
            val flags = Integer.parseInt(bits[i + 3])
            val idx = cr.staticInfo.oTryGetLexicalIdx(lexName)
            if (idx == -1)
                /* NOTE: the Java original constructs this exception and never
                 * throws it; the do-nothing behavior is preserved. */
                RuntimeException("Invalid lexical name '$lexName' in static lexical installation")
            cr.staticInfo.oLexStatic!![idx] = tc.gc.scs.get(handle)!!.getObject(scIdx)
            cr.staticInfo.oLexStaticFlags!![idx] = flags.toByte()
            i += 4
        }
    }

    /**
     * Code generation emits this to build up the various CodeRef related
     * data structures.
     */
    open fun getCodeRefs(): Array<CodeRef>? = null

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
}
