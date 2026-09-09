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
        /* A qbid with no method in this unit has nothing to set up. */
        val cr = qbidToCodeRef!!.getOrNull(localId) ?: return
        setLexValues(tc, cr, toParse)
    }

    /**
     * Static lexical setup for many blocks in one string. A string constant
     * per block costs two constant-pool entries each, which the core setting
     * cannot afford, so code generation batches them and calls this instead.
     * Layout, all NUL-separated: per block a qbid, a count of lexicals, and
     * then that many groups of (name, sc handle, sc index, flags).
     */
    open fun setLexValuesBulk(tc: ThreadContext, toParse: String) {
        val bits = toParse.split("\u0000")
        var i = 0
        while (i < bits.size) {
            val cr = qbidToCodeRef!!.getOrNull(Integer.parseInt(bits[i]))
            val n = Integer.parseInt(bits[i + 1])
            i += 2
            if (cr == null) {
                /* No method for this qbid in this unit; skip its records. */
                i += 4 * n
                continue
            }
            var j = 0
            while (j < n) {
                val lexName = bits[i]
                val handle = bits[i + 1]
                val scIdx = Integer.parseInt(bits[i + 2])
                val flags = Integer.parseInt(bits[i + 3])
                i += 4
                j++
                val idx = cr.staticInfo.oTryGetLexicalIdx(lexName)
                /* Matches setLexValues: an unknown name is skipped. */
                if (idx == -1)
                    continue
                cr.staticInfo.oLexStatic!![idx] = tc.gc.scs.get(handle)!!.getObject(scIdx)
                cr.staticInfo.oLexStaticFlags!![idx] = flags.toByte()
            }
        }
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

    /** The unit's identity string: the class's simple name on the class
     *  road, the artifact's unit id on the artifact road. Replaces the
     *  Class object wherever a unit was named. */
    open fun unitId(): String = javaClass.simpleName

    /** The serialized context, decompressed, or null when the unit has
     *  none. The class road reads it as a class resource; the artifact
     *  road holds it. */
    open fun serializedBlob(): java.nio.ByteBuffer? {
        val cuName = javaClass.simpleName
        var stream = javaClass.getResourceAsStream("$cuName.serialized.lz4")
        if (stream != null)
            return stream.use { LibraryLoader.readToHeapBufferLz4(it) }
        stream = javaClass.getResourceAsStream("$cuName.serialized") ?: return null
        return stream.use { LibraryLoader.readToHeapBuffer(it) }
    }

    /** Instantiates and initializes (without deserializing) the nested
     *  unit of the given name that rides in this unit. The class road
     *  loads it by class name through this unit's class loader. */
    open fun claimNested(tc: ThreadContext, name: String): CompilationUnit {
        val klass = Class.forName(name, true, javaClass.classLoader)
        @Suppress("DEPRECATION")
        val nested = klass.getDeclaredConstructor().newInstance() as CompilationUnit
        nested.shared = tc.gc.sharingHint
        nested.initializeCompilationUnit(tc, false)
        return nested
    }

    /**
     * The unit's engine programs, loaded from the jar's .codeprograms.lz4
     * sidecar on first use. Emitted bodies reference them by index
     * through [CodeEngines.codeRunIdx]; the sidecar exists because one
     * string constant per program overflowed CORE.c's constant pool.
     * Holds only strings, so it pins nothing run-owned.
     */
    @Volatile
    private var enginePrograms: Array<String>? = null

    open fun engineProgram(idx: Int): String {
        var progs = enginePrograms
        if (progs == null) {
            synchronized(this) {
                progs = enginePrograms
                if (progs == null) {
                    progs = loadEnginePrograms()
                    enginePrograms = progs
                }
            }
        }
        return progs!![idx]
    }

    private fun loadEnginePrograms(): Array<String> {
        val name = javaClass.simpleName + ".codeprograms.lz4"
        val stream = javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException(
                "this unit's code was compiled against an engine-program sidecar," +
                " but $name is missing from its jar")
        val text = stream.use {
            String(LibraryLoader.readToHeapBufferLz4(it).let { bb ->
                val bytes = ByteArray(bb.remaining()); bb.get(bytes); bytes
            }, Charsets.UTF_8)
        }
        /* Format, written by the QAST compiler: "N" then per program
         * " len:content", len a grapheme count (the compiler's nqp::chars) --
         * NFG graphemes >= UTF-16 units, so a program carrying a multi-unit
         * grapheme (astral char / NFG cluster) cannot be split by a plain
         * UTF-16 offset (that under-reads and misaligns every following
         * program -- the "229" bug). Split with ONE BreakIterator over the
         * whole text, set once and walked strictly forward: the programs
         * partition the text, so this is O(text). setText is itself O(text)
         * and MUST run once, not per program -- a per-program graphemeEnd
         * (setText each call) was O(n^2) and dominated the CORE.c parse
         * stage (145s -> 588s). */
        /* jesp diamond 8: even O(text), the iterator was 16% of a process's
         * visible start-up samples -- setText builds the whole boundary
         * table and every following() is a binary search into it, once per
         * grapheme of every program of every unit. Program text is source-
         * derived and almost all ASCII, where a grapheme is one UTF-16 unit
         * unless the unit is a CR (CR LF is one cluster) or the NEXT unit
         * extends it (combining marks, ZWJ, variation selectors and every
         * other extender sit at or above U+0300, as do surrogates, jamo and
         * prepend characters). So walk the text linearly, count such units
         * as one grapheme each, and ask the iterator -- created on first
         * need, so an all-simple unit never pays setText -- only for the
         * rest. NQP_SIDECAR_CHECK=1 re-derives every program the old way
         * and dies on a difference; NQP_SIDECAR_STATS=1 reports on stderr
         * how many graphemes took the iterator. */
        var at = text.indexOf(' ')
        val count = text.substring(0, if (at < 0) text.length else at).toInt()
        val out = arrayOfNulls<String>(count)
        val end = text.length
        var bi: java.text.BreakIterator? = null
        var slow = 0L
        var total = 0L
        var i = 0
        while (i < count) {
            at += 1                        // the leading space
            val colon = text.indexOf(':', at)
            val len = text.substring(at, colon).toInt()
            val start = colon + 1
            var pos = start
            var n = len
            total += len
            while (n > 0 && pos < end) {
                val c = text[pos]
                if (c < '\u0300' && c != '\r' && (pos + 1 >= end || text[pos + 1] < '\u0300')) {
                    pos += 1
                } else {
                    if (bi == null) {
                        bi = java.text.BreakIterator.getCharacterInstance()
                        bi.setText(text)
                    }
                    slow += 1
                    val next = bi.following(pos)
                    if (next == java.text.BreakIterator.DONE) { pos = end; break }
                    pos = next
                }
                n--
            }
            out[i] = text.substring(start, pos)
            at = pos
            i += 1
        }
        if (System.getenv("NQP_SIDECAR_STATS") != null)
            System.err.println("sidecar $name: $count programs, $total graphemes, $slow via the iterator")
        if (System.getenv("NQP_SIDECAR_CHECK") != null)
            checkEnginePrograms(text, count, out)
        @Suppress("UNCHECKED_CAST")
        return out as Array<String>
    }

    /** The pre-diamond-8 split -- one iterator over the whole text, every
     *  grapheme through following() -- re-run to verify the linear walk. */
    private fun checkEnginePrograms(text: String, count: Int, got: Array<String?>) {
        var at = text.indexOf(' ')
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(text)
        var i = 0
        while (i < count) {
            at += 1
            val colon = text.indexOf(':', at)
            val len = text.substring(at, colon).toInt()
            val start = colon + 1
            var pos = start
            var n = len
            while (n > 0) {
                val next = bi.following(pos)
                if (next == java.text.BreakIterator.DONE) { pos = text.length; break }
                pos = next
                n--
            }
            val want = text.substring(start, pos)
            if (want != got[i])
                throw IllegalStateException("sidecar split differs at program $i of ${javaClass.simpleName}:" +
                    " old ${want.length} units, new ${got[i]?.length} units")
            at = pos
            i += 1
        }
    }
}
