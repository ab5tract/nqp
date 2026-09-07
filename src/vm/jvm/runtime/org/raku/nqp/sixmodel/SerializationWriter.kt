package org.raku.nqp.sixmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.reprs.CallCapture
import org.raku.nqp.sixmodel.reprs.IOHandle
import org.raku.nqp.sixmodel.reprs.MultiCache
import org.raku.nqp.sixmodel.reprs.VMArrayREPRData

class SerializationWriter(
    private val tc: ThreadContext,
    private val sc: SerializationContext,
    private val sh: ArrayList<String?>,
) {
    companion object {
        /* The current version of the serialization format. */
        private const val CURRENT_VERSION = 11

        /* Various sizes (in bytes). */
        private const val HEADER_SIZE               = 4 * 18
        private const val STABLES_TABLE_ENTRY_SIZE  = 12
        private const val OBJECTS_TABLE_ENTRY_SIZE  = 16
        private const val CLOSURES_TABLE_ENTRY_SIZE = 24
        private const val CONTEXTS_TABLE_ENTRY_SIZE = 16
        private const val REPOS_TABLE_ENTRY_SIZE    = 16

        /* Possible reference types we can serialize. */
        private const val REFVAR_NULL: Short               = 1
        private const val REFVAR_OBJECT: Short             = 2
        private const val REFVAR_VM_NULL: Short            = 3
        private const val REFVAR_VM_INT: Short             = 4
        private const val REFVAR_VM_NUM: Short             = 5
        private const val REFVAR_VM_STR: Short             = 6
        private const val REFVAR_VM_ARR_VAR: Short         = 7
        private const val REFVAR_VM_ARR_STR: Short         = 8
        private const val REFVAR_VM_ARR_INT: Short         = 9
        private const val REFVAR_VM_HASH_STR_VAR: Short    = 10
        private const val REFVAR_STATIC_CODEREF: Short     = 11
        private const val REFVAR_CLONED_CODEREF: Short     = 12

        private const val DEPS = 0
        private const val STABLES = 1
        private const val STABLE_DATA = 2
        private const val OBJECTS = 3
        private const val OBJECT_DATA = 4
        private const val CLOSURES = 5
        private const val CONTEXTS = 6
        private const val CONTEXT_DATA = 7
        private const val REPOS = 8
        private const val STRINGS = 9
    }

    private val stringMap = Object2IntOpenHashMap<String>()

    private val dependentSCs = ArrayList<SerializationContext>()
    private val contexts = ArrayList<CallFrame>()

    private val outputs = arrayOf(
        ByteBuffer.allocate(128),   /* DEPS */
        ByteBuffer.allocate(512),   /* STABLES */
        ByteBuffer.allocate(1024),  /* STABLE_DATA */
        ByteBuffer.allocate(2048),  /* OBJECTS */
        ByteBuffer.allocate(8912),  /* OBJECT_DATA */
        ByteBuffer.allocate(128),   /* CLOSURES */
        ByteBuffer.allocate(128),   /* CONTEXTS */
        ByteBuffer.allocate(1024),  /* CONTEXT_DATA */
        ByteBuffer.allocate(64),    /* REPOS */
        ByteBuffer.allocate(2048),  /* STRINGS */
    )
    private var currentBuffer = 0

    private var numClosures = 0
    private var sTablesListPos = 0
    private var objectsListPos = 0
    private var contextsListPos = 0

    init {
        for (buffer in outputs)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

        /* Upstream's instance-initializer here registered a shutdown hook
         * per writer to print Accumulator timings -- debug leftover, and
         * never active (Accumulator.start has no callers). Each hook parks
         * a Thread in ApplicationShutdownHooks for the life of the JVM,
         * which in the eval server meant one per compilation, forever;
         * dropped rather than ported. */
    }

    fun serialize(): ByteBuffer {
        /* Initialize string heap so first entry is the NULL string. */
        sh.add(null)

        /* Start serializing. */
        serializationLoop()

        /* Build a single result string out of the serialized data. */
        return concatenateOutputs()
    }

    private fun addStringToHeap(s: String?): Int {
        /* We ensured that the first entry in the heap represents the null string,
         * so can just hand back 0 here. */
        if (s == null)
            return 0

        /* Did we already see it? */
        val idx = stringMap.getOrDefault(s, -1)
        if (idx != -1)
            return idx

        /* Otherwise, need to add it to the heap. */
        val newIdx = stringMap.size + 1
        stringMap.put(s, newIdx)

        val bytes = s.toByteArray(Charsets.UTF_8)
        growToHold(STRINGS, 4 + bytes.size)
        outputs[STRINGS].putInt(bytes.size)
        outputs[STRINGS].put(bytes)

        return newIdx
    }

    /* Gets the ID of a serialization context. Returns 0 if it's the current
     * one, or its dependency table offset (base-1) otherwise. Note that if
     * it is not yet in the dependency table, it will be added. */
    private fun getSCId(sc: SerializationContext): Int {
        /* Easy if it's in the current SC. */
        if (sc === this.sc)
            return 0

        /* If not, try to find it in our dependencies list. */
        val found = dependentSCs.indexOf(sc)
        if (found >= 0)
            return found + 1

        /* Otherwise, need to add it to our dependencies list. */
        dependentSCs.add(sc)
        growToHold(DEPS, 8)
        outputs[DEPS].putInt(addStringToHeap(sc.handle))
        outputs[DEPS].putInt(addStringToHeap(sc.description))
        return dependentSCs.size /* Deliberately index + 1. */
    }

    /* Takes an STable. If it's already in an SC, returns information on how
     * to reference it. Otherwise, adds it to the current SC, effectively
     * placing it onto the work list. */
    private fun getSTableRefInfo(st: STable): IntArray {
        /* Add to this SC if needed. */
        if (st.sc == null) {
            st.sc = this.sc
            this.sc.addSTable(st)
        }

        /* Work out SC reference. */
        val stSC = st.sc!!
        return intArrayOf(getSCId(stSC), stSC.getSTableIndex(st))
    }

    /* Writing function for native integers. */
    fun writeInt(value: Long) {
        growToHold(currentBuffer, 8)
        outputs[currentBuffer].putLong(value)
    }

    /* Writing function for 32-bit native integers. */
    fun writeInt32(value: Int) {
        growToHold(currentBuffer, 4)
        outputs[currentBuffer].putInt(value)
    }

    /* Writing function for native numbers. */
    fun writeNum(value: Double) {
        growToHold(currentBuffer, 8)
        outputs[currentBuffer].putDouble(value)
    }

    /* Writing function for native strings. */
    fun writeStr(value: String?) {
        val heapLoc = addStringToHeap(value)
        growToHold(currentBuffer, 4)
        outputs[currentBuffer].putInt(heapLoc)
    }

    /* Writes an object reference. */
    fun writeObjRef(ref: SixModelObject) {
        if (ref.sc == null) {
            /* This object doesn't belong to an SC yet, so it must be serialized as part of
             * this compilation unit. Add it to the work list. */
            ref.sc = this.sc
            this.sc.addObject(ref)
        }

        /* Write SC index, then object index. */
        growToHold(currentBuffer, 8)
        val refSC = ref.sc!!
        outputs[currentBuffer].putInt(getSCId(refSC))
        outputs[currentBuffer].putInt(refSC.getObjectIndex(ref))
    }

    fun writeList(list: List<SixModelObject?>) {
        growToHold(currentBuffer, 6)
        outputs[currentBuffer].putShort(REFVAR_VM_ARR_VAR)
        outputs[currentBuffer].putInt(list.size)
        for (item in list)
            writeRef(item)
    }

    fun writeHash(hash: Map<String, SixModelObject?>) {
        growToHold(currentBuffer, 6)
        outputs[currentBuffer].putShort(REFVAR_VM_HASH_STR_VAR)
        outputs[currentBuffer].putInt(hash.size)
        for (key in hash.keys) {
            writeStr(key)
            writeRef(hash[key])
        }
    }

    fun writeIntHash(hash: Object2IntOpenHashMap<String>) {
        growToHold(currentBuffer, 6)
        outputs[currentBuffer].putShort(REFVAR_VM_HASH_STR_VAR)
        outputs[currentBuffer].putInt(hash.size)
        for (key in hash.keys) {
            writeStr(key)
            growToHold(currentBuffer, 10)
            outputs[currentBuffer].putShort(REFVAR_VM_INT)
            outputs[currentBuffer].putLong(hash.getInt(key).toLong())
        }
    }

    private fun writeCodeRef(ref: SixModelObject) {
        val codeSC = ref.sc!!
        val scId = getSCId(codeSC)
        val idx = codeSC.getCodeIndex(ref)
        growToHold(currentBuffer, 8)
        outputs[currentBuffer].putInt(scId)
        outputs[currentBuffer].putInt(idx)
    }

    /* NOTE: debug leftover preserved from upstream; nothing calls start(),
     * so the shutdown hook registered in init has nothing to report. */
    private class Accumulator {
        var totalTime = 0L
        var count = 0L
        var startTime = 0L

        fun stop() {
            totalTime += System.currentTimeMillis() - startTime
        }

        companion object {
            val all: MutableMap<String, Accumulator> = HashMap()

            fun start(name: String): Accumulator {
                var a = all[name]
                if (a == null) {
                    a = Accumulator()
                    all[name] = a
                }
                a.count++
                a.startTime = System.currentTimeMillis()
                return a
            }
        }
    }

    /* Writing function for references to things. */
    fun writeRef(ref: SixModelObject?) {
        /* Work out what kind of thing we have and determine the discriminator. */
        val discrim: Short = if (ref == null) {
            REFVAR_NULL
        } else if (Ops.isnull(ref) == 1L) {
            /* A real VMNull. */
            REFVAR_VM_NULL
        } else if (ref.st.REPR is IOHandle) {
            /* Can't serialize handles. */
            REFVAR_NULL
        } else if (ref.st.REPR is CallCapture) {
            /* This is a hack for Rakudo's sake; it keeps a CallCapture around in
             * the lexpad, for no really good reason. */
            REFVAR_NULL
        } else if (ref.st.REPR is MultiCache) {
            /* These are re-computed each time. */
            REFVAR_NULL
        } else if (ref.st.WHAT === tc.gc.BOOTInt) {
            REFVAR_VM_INT
        } else if (ref.st.WHAT === tc.gc.BOOTNum) {
            REFVAR_VM_NUM
        } else if (ref.st.WHAT === tc.gc.BOOTStr) {
            REFVAR_VM_STR
        } else if (ref.st.WHAT === tc.gc.BOOTArray) {
            REFVAR_VM_ARR_VAR
        } else if (ref.st.WHAT === tc.gc.BOOTIntArray) {
            REFVAR_VM_ARR_INT
        } else if (ref.st.WHAT === tc.gc.BOOTStrArray) {
            REFVAR_VM_ARR_STR
        } else if (ref.st.WHAT === tc.gc.BOOTHash) {
            REFVAR_VM_HASH_STR_VAR
        } else if (ref is CodeRef) {
            if (ref.sc != null && ref.isStaticCodeRef) {
                /* Static code reference. */
                REFVAR_STATIC_CODEREF
            } else if (ref.sc != null) {
                /* Closure, but already seen and serialization already handled. */
                REFVAR_CLONED_CODEREF
            } else {
                /* Closure but didn't see it yet. Take care of it serialization, which
                 * gets it marked with this SC. Then it's just a normal code ref that
                 * needs serializing. */
                serializeClosure(ref)
                REFVAR_CLONED_CODEREF
            }
        } else {
            /* Just a normal object, with no special serialization needs. */
            REFVAR_OBJECT
        }

        /* Write the discriminator. */
        growToHold(currentBuffer, 2)
        outputs[currentBuffer].putShort(discrim)

        /* Now take appropriate action. */
        when (discrim) {
            REFVAR_NULL, REFVAR_VM_NULL -> {
                /* Nothing to do for these. */
            }
            REFVAR_OBJECT -> writeObjRef(ref!!)
            REFVAR_VM_INT -> writeInt(ref!!.get_int(tc))
            REFVAR_VM_NUM -> writeNum(ref!!.get_num(tc))
            REFVAR_VM_STR -> writeStr(ref!!.get_str(tc))
            REFVAR_VM_ARR_VAR, REFVAR_VM_ARR_INT, REFVAR_VM_ARR_STR, REFVAR_VM_HASH_STR_VAR ->
                /* These all delegate to the REPR. */
                ref!!.st.REPR.serialize(tc, this, ref)
            REFVAR_STATIC_CODEREF, REFVAR_CLONED_CODEREF -> writeCodeRef(ref!!)
            else -> throw RuntimeException("Serialization Error: Unimplemented object type writeRef")
        }
    }

    /* Writing function for references to STables. */
    fun writeSTableRef(st: STable) {
        val idxs = getSTableRefInfo(st)
        growToHold(currentBuffer, 8)
        outputs[currentBuffer].putInt(idxs[0])
        outputs[currentBuffer].putInt(idxs[1])
    }

    /* Concatenates the various output segments into a single binary string. */
    private fun concatenateOutputs(): ByteBuffer {
        var outputSize = 0
        var offset = 0

        /* Calculate total size. */
        outputSize += HEADER_SIZE
        outputSize += outputs[STRINGS].position()
        outputSize += outputs[DEPS].position()
        outputSize += outputs[STABLES].position()
        outputSize += outputs[STABLE_DATA].position()
        outputSize += outputs[OBJECTS].position()
        outputSize += outputs[OBJECT_DATA].position()
        outputSize += outputs[CLOSURES].position()
        outputSize += outputs[CONTEXTS].position()
        outputSize += outputs[CONTEXT_DATA].position()
        outputSize += outputs[REPOS].position()

        /* Allocate a buffer that size. */
        val output = ByteBuffer.allocate(outputSize)
        output.order(ByteOrder.LITTLE_ENDIAN)

        /* Write version into header. */
        output.putInt(CURRENT_VERSION)
        offset += HEADER_SIZE

        /* Put dependencies table in place and set location/rows in header. */
        output.position(4)
        output.putInt(offset)
        output.putInt(dependentSCs.size)
        output.position(offset)
        outputs[DEPS].flip()
        output.put(outputs[DEPS])
        offset += outputs[DEPS].position()

        /* Put STables table in place, and set location/rows in header. */
        output.position(12)
        output.putInt(offset)
        output.putInt(sc.stableCount())
        output.position(offset)
        outputs[STABLES].flip()
        output.put(outputs[STABLES])
        offset += outputs[STABLES].position()

        /* Put STables data in place. */
        output.position(20)
        output.putInt(offset)
        output.position(offset)
        outputs[STABLE_DATA].flip()
        output.put(outputs[STABLE_DATA])
        offset += outputs[STABLE_DATA].position()

        /* Put objects table in place, and set location/rows in header. */
        output.position(24)
        output.putInt(offset)
        output.putInt(sc.objectCount())
        output.position(offset)
        outputs[OBJECTS].flip()
        output.put(outputs[OBJECTS])
        offset += outputs[OBJECTS].position()

        /* Put objects data in place. */
        output.position(32)
        output.putInt(offset)
        output.position(offset)
        outputs[OBJECT_DATA].flip()
        output.put(outputs[OBJECT_DATA])
        offset += outputs[OBJECT_DATA].position()

        /* Put closures table in place, and set location/rows in header. */
        output.position(36)
        output.putInt(offset)
        output.putInt(numClosures)
        output.position(offset)
        outputs[CLOSURES].flip()
        output.put(outputs[CLOSURES])
        offset += outputs[CLOSURES].position()

        /* Put contexts table in place, and set location/rows in header. */
        output.position(44)
        output.putInt(offset)
        output.putInt(contexts.size)
        output.position(offset)
        outputs[CONTEXTS].flip()
        output.put(outputs[CONTEXTS])
        offset += outputs[CONTEXTS].position()

        /* Put contexts data in place. */
        output.position(52)
        output.putInt(offset)
        output.position(offset)
        outputs[CONTEXT_DATA].flip()
        output.put(outputs[CONTEXT_DATA])
        offset += outputs[CONTEXT_DATA].position()

        /* Put repossessions table in place, and set location/rows in header. */
        output.position(56)
        output.putInt(offset)
        output.putInt(sc.repScs.size)
        output.position(offset)
        outputs[REPOS].flip()
        output.put(outputs[REPOS])
        offset += outputs[REPOS].position()

        /* Put strings data in place */
        output.position(64)
        output.putInt(offset)
        output.putInt(stringMap.size)
        output.position(offset)
        outputs[STRINGS].flip()
        output.put(outputs[STRINGS])
        offset += outputs[STRINGS].position()

        /* Sanity check. */
        if (offset != outputSize)
            throw RuntimeException("Serialization sanity check failed: offset != output_size")

        return output
    }

    /* This handles the serialization of an object, which largely involves a
     * delegation to its representation. */
    private fun serializeObject(obj: SixModelObject) {
        /* Get index of SC that holds the STable and its index. */
        val ref = getSTableRefInfo(obj.st)

        /* Ensure there's space in the objects table; grow if not. */
        growToHold(OBJECTS, OBJECTS_TABLE_ENTRY_SIZE)

        /* Make objects table entry. */
        outputs[OBJECTS].putInt(ref[0])
        outputs[OBJECTS].putInt(ref[1])
        outputs[OBJECTS].putInt(outputs[OBJECT_DATA].position())
        outputs[OBJECTS].putInt(if (obj is TypeObject) 0 else 1)

        /* Make sure we're going to write to the correct place. */
        currentBuffer = OBJECT_DATA

        /* Delegate to its serialization REPR function. */
        if (obj !is TypeObject)
            obj.st.REPR.serialize(tc, this, obj)
    }

    private fun serializeStable(st: STable) {
        /* Ensure there's space in the STables table. */
        growToHold(STABLES, STABLES_TABLE_ENTRY_SIZE)

        /* Make STables table entry. */
        var reprNameForSerialization = st.REPR.name
        val reprData = st.REPRData
        if (reprData is VMArrayREPRData) {
            /* Workaround for native arrays. If they end up as VMArray in the
             * string heap, a plain VMArray will be created in deserialize_stub.
             * So we cheat and add a suffix to the real REPR name. */
            val ss = reprData.ss!!
            when (ss.boxedPrimitive) {
                BoxedPrimitive.INT, BoxedPrimitive.UINT ->
                    reprNameForSerialization = when (ss.bits.toInt()) {
                        64 -> "VMArray_i"
                        8 -> if (!ss.isUnsigned) "VMArray_i8" else "VMArray_u8"
                        16 -> if (!ss.isUnsigned) "VMArray_i16" else "VMArray_u16"
                        32 -> if (!ss.isUnsigned) "VMArray_i32" else "VMArray_u32"
                        else -> "VMArray_i"
                    }
                BoxedPrimitive.NUM ->
                    reprNameForSerialization = "VMArray_n"
                BoxedPrimitive.STR ->
                    reprNameForSerialization = "VMArray_s"
                else ->
                    throw ExceptionHandling.dieInternal(tc, "Invalid REPR data for VMArray")
            }
        }
        outputs[STABLES].putInt(addStringToHeap(reprNameForSerialization))
        outputs[STABLES].putInt(outputs[STABLE_DATA].position())

        /* Make sure we're going to write to the correct place. */
        currentBuffer = STABLE_DATA

        /* Write HOW, WHAT and WHO. */
        writeObjRef(st.HOW!!)
        writeObjRef(st.WHAT)
        writeRef(st.WHO)

        /* Method cache and v-table. */
        growToHold(currentBuffer, 2)
        val methodCache = st.MethodCache
        if (methodCache != null) {
            writeHash(methodCache)
        } else {
            outputs[currentBuffer].putShort(REFVAR_NULL)
        }
        val vTable = st.VTable
        val vtl = vTable?.size ?: 0
        writeInt(vtl.toLong())
        for (i in 0 until vtl)
            writeRef(vTable!![i])

        /* Type check cache. */
        val typeCheckCache = st.TypeCheckCache
        val tcl = typeCheckCache?.size ?: 0
        writeInt(tcl.toLong())
        for (i in 0 until tcl)
            writeRef(typeCheckCache!![i])

        /* Mode flags. */
        writeInt(st.ModeFlags.toLong())

        /* Boolification spec. */
        val boolSpec = st.BoolificationSpec
        writeInt(if (boolSpec == null) 0L else 1L)
        if (boolSpec != null) {
            writeInt(boolSpec.Mode.toLong())
            writeRef(boolSpec.Method)
        }

        /* Container spec. */
        val contSpec = st.ContainerSpec
        writeInt(if (contSpec == null) 0L else 1L)
        if (contSpec != null) {
            writeStr(contSpec.name())
            contSpec.serialize(tc, st, this)
        }

        /* Invocation spec. */
        val invSpec = st.InvocationSpec
        writeInt(if (invSpec == null) 0L else 1L)
        if (invSpec != null) {
            writeRef(invSpec.ClassHandle)
            writeStr(invSpec.AttrName)
            writeInt(invSpec.Hint)
            writeRef(invSpec.InvocationHandler)
        }

        /* HLL info. */
        writeStr(st.hllOwner?.name ?: "")
        writeInt(st.hllRole)

        /* Parametricity. */
        val parametricity = st.parametricity
        if (parametricity is ParametricType) {
            /* If it's a parametric type, save parameterizer. */
            writeInt(1L)
            writeRef(parametricity.parameterizer)
        } else if (parametricity is ParameterizedType) {
            /* If it's a parameterized type, save parametric type and parameters. */
            writeInt(2L)
            writeObjRef(parametricity.parametricType!!)
            val parameters = parametricity.parameters!!
            parameters.st.REPR.serialize(tc, this, parameters)
        } else {
            /* Otherwise it's neither. */
            writeInt(0L)
        }

        /* Location of REPR data. */
        outputs[STABLES].putInt(outputs[STABLE_DATA].position())

        /* If the REPR has a function to serialize representation data, call it. */
        st.REPR.serialize_repr_data(tc, st, this)
    }


    private fun closureToStaticCodeRef(closure: CodeRef, fatal: Boolean): SixModelObject? {
        val staticCode: SixModelObject? = closure.staticInfo.staticCode
        if (Ops.isnull(staticCode) == 1L) {
            if (fatal)
                throw ExceptionHandling.dieInternal(tc,
                    "Serialization Error: missing static code ref for closure")
            else
                return null
        }
        if (staticCode!!.sc == null) {
            if (fatal) {
                val cr = staticCode as CodeRef
                throw ExceptionHandling.dieInternal(tc,
                    "Serialization Error: could not locate static code ref for closure '" +
                    cr.name + "' (cuid " + cr.staticInfo.uniqueId +
                    ", from " + (cr.staticInfo.sourceFile ?: "unknown") +
                    ":" + cr.staticInfo.sourceLine + ")")
            }
            else
                return null
        }
        return staticCode
    }

    private fun serializeClosure(closure: CodeRef) {
        /* Locate the static code object. */
        val staticCodeRef = closureToStaticCodeRef(closure, true)!!
        val staticCodeSC = staticCodeRef.sc!!

        /* Ensure there's space in the closures table; grow if not. */
        growToHold(CLOSURES, CLOSURES_TABLE_ENTRY_SIZE)

        /* Get the index of the context (which will add it to the todo list if
         * needed). */
        val contextIdx = getSerializedOuterContextIdx(closure)

        /* Add an entry to the closures table. */
        val staticSCId = getSCId(staticCodeSC)
        val staticIdx = staticCodeSC.getCodeIndex(staticCodeRef)
        outputs[CLOSURES].putInt(staticSCId)
        outputs[CLOSURES].putInt(staticIdx)
        outputs[CLOSURES].putInt(contextIdx)

        /* Check if it has a static code object. */
        val codeObject = closure.codeObject
        if (codeObject != null) {
            outputs[CLOSURES].putInt(1)
            if (codeObject.sc == null) {
                codeObject.sc = this.sc
                this.sc.addObject(codeObject)
            }
            val codeObjectSC = codeObject.sc!!
            outputs[CLOSURES].putInt(getSCId(codeObjectSC))
            outputs[CLOSURES].putInt(codeObjectSC.getObjectIndex(codeObject))
        } else {
            outputs[CLOSURES].putInt(0)
            outputs[CLOSURES].putInt(0) // pad
            outputs[CLOSURES].putInt(0) // pad
        }

        /* Increment count of closures in the table. */
        numClosures++

        /* Add the closure to this SC, and mark it as as being in it. */
        this.sc.addCodeRef(closure)
        closure.sc = this.sc
    }

    private fun getSerializedOuterContextIdx(closure: CodeRef): Int {
        if (closure.isCompilerStub)
            return 0
        val outer = closure.outer ?: return 0
        return getSerializedContextIdx(outer)
    }

    private fun getSerializedContextIdx(cf: CallFrame): Int {
        if (cf.sc == null) {
            /* Make sure we should chase a level down. */
            if (Ops.isnull(closureToStaticCodeRef(cf.codeRef, false)) == 1L) {
                return 0
            } else {
                contexts.add(cf)
                cf.sc = this.sc
                return contexts.size
            }
        } else {
            if (cf.sc !== this.sc)
                ExceptionHandling.dieInternal(tc,
                    "Serialization Error: reference to context outside of SC")
            val idx = contexts.indexOf(cf)
            if (idx < 0)
                ExceptionHandling.dieInternal(tc,
                    "Serialization Error: could not locate outer context in current SC")
            return idx + 1
        }
    }

    private fun serializeContext(cf: CallFrame) {
        /* Locate the static code ref this context points to. */
        val staticCodeRef = closureToStaticCodeRef(cf.codeRef, true)!!
        val staticCodeSC = staticCodeRef.sc
        if (staticCodeSC == null)
            ExceptionHandling.dieInternal(tc,
                "Serialization Error: closure outer is a code object not in an SC")
        val staticSCId = getSCId(staticCodeSC!!)
        val staticIdx = staticCodeSC.getCodeIndex(staticCodeRef)

        /* Ensure there's space in the contexts table; grow if not. */
        growToHold(CONTEXTS, CONTEXTS_TABLE_ENTRY_SIZE)

        /* Make contexts table entry. */
        outputs[CONTEXTS].putInt(staticSCId)
        outputs[CONTEXTS].putInt(staticIdx)
        outputs[CONTEXTS].putInt(outputs[CONTEXT_DATA].position())

        /* See if there's any relevant outer context, and if so set it up to
         * be serialized. */
        val outerFrame = cf.outer
        if (outerFrame != null)
            outputs[CONTEXTS].putInt(getSerializedContextIdx(outerFrame))
        else
            outputs[CONTEXTS].putInt(0)

        /* Set up writer. */
        currentBuffer = CONTEXT_DATA

        /* Serialize lexicals. */
        val oLex = cf.oLex
        val iLex = cf.iLex
        val nLex = cf.nLex
        val sLex = cf.sLex
        var numLexicals = 0
        numLexicals += oLex?.size ?: 0
        numLexicals += iLex?.size ?: 0
        numLexicals += nLex?.size ?: 0
        numLexicals += sLex?.size ?: 0
        writeInt(numLexicals.toLong())
        if (oLex != null) {
            val names = cf.codeRef.staticInfo.oLexicalNames!!
            for (i in oLex.indices) {
                writeStr(names[i])
                /* Vivify before writing: a clone-flagged lexical the frame
                 * never read must serialize as this frame's own clone, not
                 * as the vivification marker. */
                writeRef(cf.oLexOrVivify(i))
            }
        }
        if (iLex != null) {
            val names = cf.codeRef.staticInfo.iLexicalNames!!
            for (i in iLex.indices) {
                writeStr(names[i])
                writeInt(iLex[i])
            }
        }
        if (nLex != null) {
            val names = cf.codeRef.staticInfo.nLexicalNames!!
            for (i in nLex.indices) {
                writeStr(names[i])
                writeNum(nLex[i])
            }
        }
        if (sLex != null) {
            val names = cf.codeRef.staticInfo.sLexicalNames!!
            for (i in sLex.indices) {
                writeStr(names[i])
                writeStr(sLex[i])
            }
        }
    }

    /* Grows a buffer as needed to hold more data. */
    private fun growToHold(idx: Int, required: Int) {
        val check = outputs[idx]
        val position = check.position()
        if (position + required >= check.capacity()) {
            val replacement = ByteBuffer.allocate(
                Math.max(check.capacity() * 2, position + required))
            replacement.order(ByteOrder.LITTLE_ENDIAN)
            check.position(0)
            replacement.put(check)
            replacement.position(position)
            outputs[idx] = replacement
        }
    }

    /* Goes through the list of repossessions and serializes them all. */
    private fun serializeRepossessions() {
        /* Allocate table space, provided we've actually something to do. */
        val numRepos = sc.repIndexes.size
        if (numRepos == 0)
            return
        growToHold(REPOS, numRepos * REPOS_TABLE_ENTRY_SIZE)

        /* Make entries. */
        for (i in 0 until numRepos) {
            val objIdx = sc.repIndexes.getInt(i) shr 1
            val isST = sc.repIndexes.getInt(i) and 1
            val origSC = sc.repScs[i]

            /* Work out original object's SC location. */
            val origSCIdx = getSCId(origSC)
            val origIdx = if (isST != 0)
                origSC.getSTableIndex(sc.getSTable(objIdx)!!)
            else
                origSC.getObjectIndex(sc.getObject(objIdx)!!)
            if (origIdx < 0)
                throw RuntimeException(
                    "Could not find object when writing repossessions; " +
                    (if (isST != 0)
                        "STable"
                    else
                        "REPR = " + sc.getObject(objIdx)!!.st.REPR.name))

            /* Write table row. */
            outputs[REPOS].putInt(isST)
            outputs[REPOS].putInt(objIdx)
            outputs[REPOS].putInt(origSCIdx)
            outputs[REPOS].putInt(origIdx)
        }
    }

    /* This is the overall serialization loop. It keeps an index into the list of
     * STables and objects in the SC. As we discover new ones, they get added. We
     * finished when we've serialized everything. */
    private fun serializationLoop() {
        var workTodo = true
        while (workTodo) {
            /* Current work list sizes. */
            val sTablesTodo = sc.stableCount()
            val objectsTodo = sc.objectCount()
            val contextsTodo = contexts.size

            /* Reset todo flag - if we do some work we'll go round again as it
             * may have generated more. */
            workTodo = false

            /* Serialize any STables on the todo list. */
            while (sTablesListPos < sTablesTodo) {
                serializeStable(sc.getSTable(sTablesListPos)!!)
                sTablesListPos++
                workTodo = true
            }

            /* Serialize any objects on the todo list. */
            while (objectsListPos < objectsTodo) {
                serializeObject(sc.getObject(objectsListPos)!!)
                objectsListPos++
                workTodo = true
            }

            /* Serialize any contexts on the todo list. */
            while (contextsListPos < contextsTodo) {
                serializeContext(contexts.get(contextsListPos))
                contextsListPos++
                workTodo = true
            }
        }

        /* Finally, serialize repossessions table (this can't make any more
         * work, so is done as a separate step here at the end). */
        serializeRepossessions()
    }
}
