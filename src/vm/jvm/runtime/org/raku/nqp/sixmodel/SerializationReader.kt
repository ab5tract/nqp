package org.raku.nqp.sixmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.reprs.VMHashInstance

class SerializationReader(
    private val tc: ThreadContext,
    private val sc: SerializationContext,
    private var sh: Array<String?>,
    private val cr: Array<CodeRef>,
    private val crCount: Int,
    private val orig: ByteBuffer,
) {
    companion object {
        /* The current version of the serialization format. */
        private const val CURRENT_VERSION = 11

        /* The minimum version of the serialization format. */
        private const val MIN_VERSION = 4

        /* Various sizes (in bytes). */
        private const val V10_HEADER_SIZE           = 4 * 16
        private const val HEADER_SIZE               = 4 * 18
        private const val DEP_TABLE_ENTRY_SIZE      = 8
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

        /* How far along an STable of this SC is, for forceSTable. */
        private const val ST_UNREAD  = 0
        private const val ST_READING = 1
        private const val ST_READ    = 2
    }

    private lateinit var contexts: Array<CallFrame?>

    /* Per-STable progress, and the way back from an STable to its index, so a
     * REPR can ask for one it depends on out of table order. */
    private var stableState = IntArray(0)
    private val stableIndex = java.util.IdentityHashMap<STable, Int>()

    /* The version of the serialization format we're currently reading. */
    @JvmField var version = 0

    /* Various table offsets and entry counts. */
    private var depTableOffset = 0
    private var depTableEntries = 0
    private var stTableOffset = 0
    private var stTableEntries = 0
    private var stDataOffset = 0
    private var objTableOffset = 0
    private var objTableEntries = 0
    private var objDataOffset = 0
    private var closureTableOffset = 0
    private var closureTableEntries = 0
    private var contextTableOffset = 0
    private var contextTableEntries = 0
    private var contextDataOffset = 0
    private var reposTableOffset = 0
    private var reposTableEntries = 0
    private var stringHeapOffset = 0
    private var stringHeapEntries = 0

    /* Serialization contexts we depend on. */
    private lateinit var dependentSCs: Array<SerializationContext?>

    /* The object we're currently deserializing. */
    private var curObject: SixModelObject? = null

    fun deserialize() {
        // Serialized data is always little endian.
        orig.order(ByteOrder.LITTLE_ENDIAN)

        // Split the input into the various segments.
        checkAndDisectInput()

        deserializeStringHeap()

        resolveDependencies()

        // Put code refs in place.
        for (i in 0 until crCount) {
            @Suppress("SENSELESS_COMPARISON")
            if (cr[i] == null) {
                var nulls = 0
                val firstFew = StringBuilder()
                for (j in cr.indices) {
                    if (cr[j] == null) {
                        nulls++
                        if (nulls <= 8) {
                            if (nulls > 1) firstFew.append(",")
                            firstFew.append(j)
                        }
                    }
                }
                throw RuntimeException(
                    "Serialized code ref " + i + " of " + crCount
                        + " has no compiled method in this compilation unit"
                        + " (code ref table has " + cr.size + " entries, "
                        + nulls + " of them empty, first at " + firstFew + ")")
            }
            cr[i].isStaticCodeRef = true
            cr[i].sc = sc
            sc.addCodeRef(cr[i])
        }

        // Handle any STable repossessions, then stub STables.
        sc.initSTableList(stTableEntries)
        if (reposTableEntries > 0)
            repossess(1)
        stubSTables()

        // Handle any object repossessions, then stub objects.
        sc.initObjectList(objTableEntries)
        if (reposTableEntries > 0)
            repossess(0)
        stubObjects()

        // Do first step of deserializing any closures.
        deserializeClosures()

        // Second passes over STables and objects.
        deserializeSTables()
        deserializeObjects()

        // Finish up contexts and closures.
        deserializeContexts()
        attachClosureOuters(crCount)
        attachContextOuters()
        fixupContextOuters()
    }

    /* Checks the header looks sane and all of the places it points to make sense.
     * Also disects the input string into the tables and data segments and populates
     * the reader data structure more fully. */
    private fun checkAndDisectInput() {
        var provPos = 0
        val dataLen = orig.limit()

        /* Ensure that we have enough space to read a version number and check it. */
        if (dataLen < 4)
            throw RuntimeException("Serialized data too short to read a version number (< 4 bytes)")
        version = orig.getInt()
        if (version < MIN_VERSION || version > CURRENT_VERSION)
            throw RuntimeException("Unknown serialization format version $version")

        /* Ensure that the data is at least as long as the header is expected to be. */
        val headerSize = if (version >= 11) HEADER_SIZE else V10_HEADER_SIZE
        if (dataLen < headerSize)
            throw RuntimeException("Serialized data shorter than header (< $headerSize bytes)")
        provPos += headerSize

        /* Get size and location of dependencies table. */
        depTableOffset = orig.getInt()
        depTableEntries = orig.getInt()
        if (depTableOffset < provPos)
            throw RuntimeException("Corruption detected (dependencies table starts before header ends)")
        provPos += depTableEntries * DEP_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (dependencies table overruns end of data)")

        /* Get size and location of STables table. */
        stTableOffset = orig.getInt()
        stTableEntries = orig.getInt()
        if (stTableOffset < provPos)
            throw RuntimeException("Corruption detected (STables table starts before dependencies table ends)")
        provPos += stTableEntries * STABLES_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (STables table overruns end of data)")

        /* Get location of STables data. */
        stDataOffset = orig.getInt()
        if (stDataOffset < provPos)
            throw RuntimeException("Corruption detected (STables data starts before STables table ends)")
        provPos = stDataOffset
        if (stDataOffset > dataLen)
            throw RuntimeException("Corruption detected (STables data starts after end of data)")

        /* Get size and location of objects table. */
        objTableOffset = orig.getInt()
        objTableEntries = orig.getInt()
        if (objTableOffset < provPos)
            throw RuntimeException("Corruption detected (objects table starts before STables data ends)")
        provPos = objTableOffset + objTableEntries * OBJECTS_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (objects table overruns end of data)")

        /* Get location of objects data. */
        objDataOffset = orig.getInt()
        if (objDataOffset < provPos)
            throw RuntimeException("Corruption detected (objects data starts before objects table ends)")
        provPos = objDataOffset
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (objects data starts after end of data)")

        /* Get size and location of closures table. */
        closureTableOffset = orig.getInt()
        closureTableEntries = orig.getInt()
        if (closureTableOffset < provPos)
            throw RuntimeException("Corruption detected (Closures table starts before objects data ends)")
        provPos = closureTableOffset + closureTableEntries * CLOSURES_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (Closures table overruns end of data)")

        /* Get size and location of contexts table. */
        contextTableOffset = orig.getInt()
        contextTableEntries = orig.getInt()
        if (contextTableOffset < provPos)
            throw RuntimeException("Corruption detected (contexts table starts before closures table ends)")
        provPos = contextTableOffset + contextTableEntries * CONTEXTS_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (contexts table overruns end of data)")

        /* Get location of contexts data. */
        contextDataOffset = orig.getInt()
        if (contextDataOffset < provPos)
            throw RuntimeException("Corruption detected (contexts data starts before contexts table ends)")
        provPos = contextDataOffset
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (contexts data starts after end of data)")

        /* Get size and location of repossessions table. */
        reposTableOffset = orig.getInt()
        reposTableEntries = orig.getInt()
        if (reposTableOffset < provPos)
            throw RuntimeException("Corruption detected (repossessions table starts before contexts data ends)")
        provPos = reposTableOffset + reposTableEntries * REPOS_TABLE_ENTRY_SIZE
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (repossessions table overruns end of data)")

        if (version >= 11) {
            /* Get size and location of string heap. */
            stringHeapOffset = orig.getInt()
            stringHeapEntries = orig.getInt()
            if (stringHeapOffset < provPos)
                throw RuntimeException("Corruption detected (string table starts before repossessions tabke ends)")
            provPos = stringHeapOffset
            if (provPos > dataLen)
                throw RuntimeException("Corruption detected (string table starts after end of data)")
        }
    }

    private fun deserializeStringHeap() {
        if (version >= 11) {
            sh = arrayOfNulls(stringHeapEntries + 1)
            sh[0] = null

            orig.position(stringHeapOffset)
            for (i in 1..stringHeapEntries) {
                val len = orig.getInt()
                val bytes = ByteArray(len)
                orig.get(bytes, 0, len)

                sh[i] = String(bytes, Charsets.UTF_8)
            }
        }
    }

    private fun resolveDependencies() {
        dependentSCs = arrayOfNulls(depTableEntries)
        orig.position(depTableOffset)
        for (i in 0 until depTableEntries) {
            val handle = lookupString(orig.getInt())
            var desc = lookupString(orig.getInt())
            val depSC = tc.gc.scs[handle]
            if (depSC == null) {
                if (desc == null)
                    desc = handle
                throw RuntimeException(
                    "Missing or wrong version of dependency '$desc'")
            }
            dependentSCs[i] = depSC
        }
    }

    /* Repossess an object or STable. */
    private fun repossess(chosenType: Int) {
        for (i in 0 until reposTableEntries) {
            /* Go to table row. */
            orig.position(reposTableOffset + i * REPOS_TABLE_ENTRY_SIZE)

            /* Do appropriate type of repossession. */
            val repoType = orig.getInt()
            if (repoType != chosenType)
                continue
            val objIdx = orig.getInt()
            val origSCIdx = orig.getInt()
            val origObjIdx = orig.getInt()
            if (repoType == 0) {
                /* Get object to repossess. */
                val origSC = locateSC(origSCIdx)
                val origObj = origSC.getObject(origObjIdx)!!

                /* Ensure we aren't already trying to repossess the object. */
                /* XXX TODO */

                /* Put it into objects root set at the appropriate slot. */
                sc.addObject(origObj, objIdx)
                origObj.sc = sc

                /* The object's STable may have changed as a result of the
                 * repossession (perhaps due to mixing in to it), so put the
                 * STable it should now have in place. */
                orig.position(objTableOffset + objIdx * OBJECTS_TABLE_ENTRY_SIZE)
                origObj.st = lookupSTable(orig.getInt(), orig.getInt())
            } else if (repoType == 1) {
                /* Get STable to repossess. */
                val origSC = locateSC(origSCIdx)
                val origST = origSC.getSTable(origObjIdx)!!

                /* Ensure we aren't already trying to repossess the STable. */
                /* XXX TODO */

                /* Put it into STables root set at the apporpriate slot. */
                sc.setSTable(objIdx, origST)
                origST.sc = sc
            } else {
                throw RuntimeException("Unknown repossession type")
            }
        }
    }

    private fun stubSTables() {
        for (i in 0 until stTableEntries) {
            // May already have it, due to repossession.
            if (sc.getSTable(i) != null)
                continue

            // Look up representation.
            orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE)
            val repr = REPRRegistry.getByName(lookupString(orig.getInt())!!)

            // Create STable stub and add it to the root STable set.
            val st = STable(repr, null)
            st.sc = sc
            sc.setSTable(i, st)
        }

        stableState = IntArray(stTableEntries)
        for (i in 0 until stTableEntries)
            stableIndex[sc.getSTable(i)!!] = i
    }

    private fun stubObjects() {
        for (i in 0 until objTableEntries) {
            // May already have it, due to repossession.
            if (sc.getObject(i) != null)
                continue

            // Look up STable.
            orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE)
            val st = lookupSTable(orig.getInt(), orig.getInt())

            // Now go by object flags.
            orig.position(orig.position() + 4)
            val flags = orig.getInt()
            val stubObj: SixModelObject?
            if (flags == 0) {
                // Type object.
                stubObj = TypeObject()
                stubObj.st = st
            } else {
                // Concrete object; defer to the REPR.
                stubObj = st.REPR.deserialize_stub(tc, st)
            }

            // Place object in SC root set.
            stubObj!!.sc = sc
            sc.addObject(stubObj, i)
        }
    }

    private fun deserializeClosures() {
        for (i in 0 until closureTableEntries) {
            /* Seek to the closure's table row. */
            orig.position(closureTableOffset + i * CLOSURES_TABLE_ENTRY_SIZE)

            /* Resolve the reference to the static code object. */
            val staticCode = readCodeRef()

            /* Clone it and add it to this SC's code refs list. */
            val closure = staticCode.clone(tc) as CodeRef
            closure.sc = sc
            sc.addCodeRef(closure)

            /* See if there's a code object we need to attach. */
            orig.position(orig.position() + 4)
            if (orig.getInt() != 0)
                closure.codeObject = readObjRef()
        }
    }

    private fun deserializeSTables() {
        for (i in 0 until stTableEntries)
            deserializeSTable(i)
    }

    /* A REPR reading its own data may need another STable of this SC to be
     * finished already - P6opaque asks each flattened attribute type for its
     * storage spec, which for P6int is repr data that its own deserialize
     * fills in. The table is not in dependency order, so let a REPR say which
     * STables it needs and deserialize those first. MoarVM calls the same
     * thing MVM_serialization_force_stable. */
    fun forceSTable(st: STable?) {
        if (st == null)
            return
        /* Not one of ours means it came from a dependency, already whole. */
        val idx = stableIndex[st] ?: return
        if (stableState[idx] != ST_UNREAD)
            return
        /* The caller is midway through reading its own data from the shared
         * buffer, so put the position back before returning to it. */
        val savedPos = orig.position()
        try {
            deserializeSTable(idx)
        }
        finally {
            orig.position(savedPos)
        }
    }

    private fun deserializeSTable(i: Int) {
        /* A cycle between two STables' repr data would come back here while
         * this one is still being read; the partly built STable is the best
         * that can be offered, which is what leaving it in progress does. */
        if (stableState[i] != ST_UNREAD)
            return
        stableState[i] = ST_READING
        try {
            deserializeSTableInner(i)
        }
        finally {
            stableState[i] = ST_READ
        }
    }

    private fun deserializeSTableInner(i: Int) {
        // Seek to the right position in the data chunk.
        orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE + 4)
        orig.position(stDataOffset + orig.getInt())

        // Get the STable we need to deserialize into.
        val st = sc.getSTable(i)!!

        // Read the HOW, WHAT and WHO.
        st.HOW = readObjRef()
        st.WHAT = readObjRef()
        st.WHO = readRef()

        /* Method cache and v-table. */
        val methodCache = readRef()
        if (Ops.isnull(methodCache) == 0L)
            st.MethodCache = (methodCache as VMHashInstance).storage
        val vTable = arrayOfNulls<SixModelObject>(orig.getLong().toInt())
        st.VTable = vTable
        for (j in vTable.indices)
            vTable[j] = readRef()

        /* Type check cache. */
        val tcCacheSize = orig.getLong().toInt()
        if (tcCacheSize > 0) {
            val typeCheckCache = arrayOfNulls<SixModelObject>(tcCacheSize)
            st.TypeCheckCache = typeCheckCache
            for (j in typeCheckCache.indices)
                typeCheckCache[j] = readRef()
        }

        /* Mode flags. */
        st.ModeFlags = orig.getLong().toInt()

        /* Boolification spec. */
        if (orig.getLong() != 0L) {
            val boolSpec = BoolificationSpec()
            st.BoolificationSpec = boolSpec
            boolSpec.Mode = orig.getLong().toInt()
            boolSpec.Method = readRef()
        }

        /* Container spec. */
        if (orig.getLong() != 0L) {
            if (version >= 5) {
                val ccName = readStr()
                val cc = tc.gc.contConfigs[ccName]
                    ?: throw RuntimeException("Unknown container config $ccName")
                cc.setContainerSpec(tc, st)
                st.ContainerSpec!!.deserialize(tc, st, this)
            } else {
                throw RuntimeException("Unable to deserialize old container spec format")
            }
        }

        /* Invocation spec. */
        if (version >= 5) {
            if (orig.getLong() != 0L) {
                val invSpec = InvocationSpec()
                st.InvocationSpec = invSpec
                invSpec.ClassHandle = readRef()
                invSpec.AttrName = lookupString(orig.getInt())
                invSpec.Hint = orig.getLong().toInt().toLong()
                invSpec.InvocationHandler = readRef()
            }
        }

        /* HLL stuff. */
        if (version >= 6) {
            st.hllOwner = tc.gc.getHLLConfigFor(readStr()!!)
            st.hllRole = orig.getLong()
        }

        /* Type parametricity. */
        if (version >= 9) {
            val paraFlag = orig.getLong()
            /* If it's a parametric type... */
            if (paraFlag == 1L) {
                val pt = ParametricType()
                pt.parameterizer = readRef()
                pt.lookup = ArrayList()
                st.parametricity = pt
            } else if (paraFlag == 2L) {
                val pt = ParameterizedType()
                pt.parametricType = readObjRef()
                val BOOTArray = tc.gc.BOOTArray!!
                val parameters = BOOTArray.st.REPR.allocate(tc, BOOTArray.st)
                pt.parameters = parameters
                val elems = orig.getInt()
                for (j in 0 until elems)
                    parameters.bind_pos_boxed(tc, j.toLong(), readRef())
                st.parametricity = pt
            } else if (paraFlag != 0L) {
                throw RuntimeException("Unknown STable parametricity flag")
            }
        }

        /* If the REPR has a function to deserialize representation data, call it. */
        st.REPR.deserialize_repr_data(tc, st, this)
    }

    private fun deserializeObjects() {
        for (i in 0 until objTableEntries) {
            // Can skip if it's a type object.
            val obj = sc.getObject(i)
            if (obj is TypeObject)
                continue

            // Seek reader to object data offset.
            orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE + 8)
            orig.position(objDataOffset + orig.getInt())

            // Complete the object's deserialization.
            this.curObject = obj
            obj!!.st.REPR.deserialize_finish(tc, obj.st, this, obj)
            this.curObject = null
        }
    }

    private fun deserializeContexts() {
        contexts = arrayOfNulls(contextTableEntries)
        for (i in 0 until contextTableEntries) {
            /* Seek to the context's table row. */
            orig.position(contextTableOffset + i * CONTEXTS_TABLE_ENTRY_SIZE)

            /* Resolve the reference to the static code object this context is for. */
            val staticCode = readCodeRef()

            /* Create a context and set it up. */
            val ctx = CallFrame()
            ctx.tc = tc
            ctx.codeRef = staticCode
            val sci = staticCode.staticInfo
            if (sci.oLexicalNames != null)
                ctx.oLex = sci.oLexStatic!!.clone()
            if (sci.iLexicalNames != null)
                ctx.iLex = LongArray(sci.iLexicalNames!!.size)
            if (sci.nLexicalNames != null)
                ctx.nLex = DoubleArray(sci.nLexicalNames!!.size)
            if (sci.sLexicalNames != null)
                ctx.sLex = arrayOfNulls(sci.sLexicalNames!!.size)

            /* Set context data read position, and set current read buffer to the correct thing. */
            orig.position(contextDataOffset + orig.getInt())

            /* Deserialize lexicals. */
            val syms = orig.getLong()
            for (j in 0 until syms) {
                val sym = readStr()!!
                var idx = sci.oTryGetLexicalIdx(sym)
                if (idx != -1) {
                    ctx.oLex!![idx] = readRef()
                } else {
                    idx = sci.iTryGetLexicalIdx(sym)
                    if (idx != -1) {
                        ctx.iLex!![idx] = orig.getLong()
                    } else {
                        idx = sci.nTryGetLexicalIdx(sym)
                        if (idx != -1) {
                            ctx.nLex!![idx] = orig.getDouble()
                        } else {
                            idx = sci.sTryGetLexicalIdx(sym)
                            if (idx != -1)
                                ctx.sLex!![idx] = readStr()
                            else
                                throw RuntimeException("Failed to deserialize lexical $sym")
                        }
                    }
                }
            }

            /* Put context in place. */
            contexts[i] = ctx
        }
    }

    private fun attachClosureOuters(closureBaseIdx: Int) {
        for (i in 0 until closureTableEntries) {
            orig.position(closureTableOffset + i * CLOSURES_TABLE_ENTRY_SIZE + 8)
            val idx = orig.getInt()
            if (idx > 0)
                sc.getCodeRef(closureBaseIdx + i)!!.outer = contexts[idx - 1]
        }
    }

    private fun attachContextOuters() {
        for (i in 0 until contextTableEntries) {
            orig.position(contextTableOffset + i * CONTEXTS_TABLE_ENTRY_SIZE + 12)
            val idx = orig.getInt()
            if (idx > 0)
                contexts[i]!!.outer = contexts[idx - 1]
        }
    }

    private fun fixupContextOuters() {
        for (i in 0 until contextTableEntries) {
            // Nothing was serialized as this context's outer, so go and find
            // the frame it should be running inside.
            contexts[i]!!.resolveDeserializedOuter()
        }
    }

    fun readRef(): SixModelObject? {
        val discrim = orig.getShort()
        when (discrim) {
        REFVAR_NULL ->
            return null
        REFVAR_VM_NULL ->
            return Ops.createNull(tc)
        REFVAR_OBJECT ->
            return readObjRef()
        REFVAR_VM_INT -> {
            val BOOTInt = tc.gc.BOOTInt!!
            val iResult = BOOTInt.st.REPR.allocate(tc, BOOTInt.st)
            iResult.set_int(tc, orig.getLong())
            return iResult
        }
        REFVAR_VM_NUM -> {
            val BOOTNum = tc.gc.BOOTNum!!
            val nResult = BOOTNum.st.REPR.allocate(tc, BOOTNum.st)
            nResult.set_num(tc, orig.getDouble())
            return nResult
        }
        REFVAR_VM_STR -> {
            val BOOTStr = tc.gc.BOOTStr!!
            val sResult = BOOTStr.st.REPR.allocate(tc, BOOTStr.st)
            sResult.set_str(tc, lookupString(orig.getInt()))
            return sResult
        }
        REFVAR_VM_ARR_VAR -> {
            val BOOTArray = tc.gc.BOOTArray!!
            val resArray = BOOTArray.st.REPR.allocate(tc, BOOTArray.st)
            val elems = orig.getInt()
            for (i in 0 until elems)
                resArray.bind_pos_boxed(tc, i.toLong(), readRef())
            if (Ops.isnull(this.curObject) == 0L) {
                resArray.sc = sc
                sc.ownedObjects[resArray] = this.curObject!!
            }
            return resArray
        }
        REFVAR_VM_ARR_STR -> {
            val BOOTStrArray = tc.gc.BOOTStrArray!!
            val resArray = BOOTStrArray.st.REPR.allocate(tc, BOOTStrArray.st)
            val elems = orig.getInt()
            for (i in 0 until elems) {
                tc.nativeS = readStr()
                resArray.bind_pos_native(tc, i.toLong())
            }
            return resArray
        }
        REFVAR_VM_ARR_INT -> {
            val BOOTIntArray = tc.gc.BOOTIntArray!!
            val resArray = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st)
            val elems = orig.getInt()
            for (i in 0 until elems) {
                tc.nativeI = readLong()
                resArray.bind_pos_native(tc, i.toLong())
            }
            return resArray
        }
        REFVAR_VM_HASH_STR_VAR -> {
            val BOOTHash = tc.gc.BOOTHash!!
            val resHash = BOOTHash.st.REPR.allocate(tc, BOOTHash.st)
            val elems = orig.getInt()
            for (i in 0 until elems) {
                val key = lookupString(orig.getInt())
                resHash.bind_key_boxed(tc, key, readRef())
            }
            if (Ops.isnull(this.curObject) == 0L) {
                resHash.sc = sc
                sc.ownedObjects[resHash] = this.curObject!!
            }
            return resHash
        }
        REFVAR_STATIC_CODEREF, REFVAR_CLONED_CODEREF ->
            return readCodeRef()
        else ->
            throw RuntimeException("Unimplemented case of read_ref")
        }
    }

    fun readObjRef(): SixModelObject {
        val objSC = locateSC(orig.getInt())
        val idx = orig.getInt()
        if (idx < 0 || idx >= objSC.objectCount())
            throw RuntimeException("Invalid SC object index $idx")
        return objSC.getObject(idx)!!
    }

    fun readSTableRef(): STable {
        return lookupSTable(orig.getInt(), orig.getInt())
    }

    fun readCodeRef(): CodeRef {
        val codeSC = locateSC(orig.getInt())
        val idx = orig.getInt()
        if (idx < 0 || idx >= codeSC.coderefCount())
            throw RuntimeException("Invalid SC code index $idx")
        return codeSC.getCodeRef(idx)!!
    }

    fun readLong(): Long {
        return orig.getLong()
    }

    fun readInt32(): Int {
        return orig.getInt()
    }

    fun readDouble(): Double {
        return orig.getDouble()
    }

    fun readStr(): String? {
        return lookupString(orig.getInt())
    }

    private fun lookupSTable(scIdx: Int, idx: Int): STable {
        val stSC = locateSC(scIdx)
        if (idx < 0 || idx >= stSC.stableCount())
            throw RuntimeException("Invalid STable index (scIdx=$scIdx,idx=$idx)")
        return stSC.getSTable(idx)!!
    }

    private fun locateSC(scIdx: Int): SerializationContext {
        if (scIdx == 0)
            return sc
        if (scIdx < 1 || scIdx > dependentSCs.size)
            throw RuntimeException("Invalid dependencies table index encountered (index $scIdx)")
        return dependentSCs[scIdx - 1]!!
    }

    private fun lookupString(idx: Int): String? {
        if (idx >= sh.size)
            throw RuntimeException("Attempt to read past end of string heap (index $idx)")
        return sh[idx]
    }
}
