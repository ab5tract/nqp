package org.raku.nqp.sixmodel

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.HLLConfig
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
        private const val CURRENT_VERSION = 12

        /* The minimum version of the serialization format. Every artifact
         * in the tree, stage0 included, is written by the format-12 writer,
         * so 12 is the only format this reads. */
        private const val MIN_VERSION = 12

        /* Various sizes (in bytes). */
        private const val HEADER_SIZE               = 4 * 18
        private const val DEP_TABLE_ENTRY_SIZE      = 8
        private const val STABLES_TABLE_ENTRY_SIZE  = 12
        private const val OBJECTS_TABLE_ENTRY_SIZE  = 8
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

        /* How far along an STable of this SC is; ST_READING is the cycle
         * marker a demand from inside this STable's own REPR data meets. */
        private const val ST_UNREAD  = 0
        private const val ST_READING = 1
        private const val ST_READ    = 2

        /** One lock for every drain in the process: a worklist crosses SCs. */
        @JvmField val LOCK = java.util.concurrent.locks.ReentrantLock()
        /** The drain in progress on the thread holding LOCK; null outside one.
         *  Volatile because it is written by whichever thread holds the lock
         *  and read on the way into one. */
        @JvmStatic @Volatile var current: Drain? = null
        @JvmField val EAGER = System.getenv("NQP_SC_EAGER") != null
        @JvmField val VERIFY = System.getenv("NQP_SC_VERIFY") != null
        /** Every reader alive under the stats knob, for the exit line (Task 7). */
        @JvmField val LIVE = java.util.concurrent.ConcurrentLinkedQueue<SerializationReader>()

        /** One line per reader alive under NQP_UNIT_LOAD_STATS=1, at exit. */
        @JvmStatic
        fun reportAll() {
            for (r in LIVE) {
                System.err.println("sc-demand ${r.sc.handle} stables=${r.stablesRead}/${r.stTableEntries}" +
                    " objects=${r.objectsRead}/${r.objTableEntries} closures=${r.closuresRead}/${r.closureTableEntries}" +
                    " contexts=${r.contextsRead}/${r.contextTableEntries} drains=${r.drains}" +
                    " ms=${"%.2f".format(r.demandNanos / 1_000_000.0)}")
            }
        }
    }

    /* Per-STable progress (ST_UNREAD / ST_READING / ST_READ) and the stubs
     * not yet published, one table per kind; a root slot holds finished
     * entries only. */
    private lateinit var stableState: IntArray
    private lateinit var pendingSTable: Array<STable?>
    private lateinit var pendingObj: Array<SixModelObject?>
    private lateinit var pendingCode: Array<CodeRef?>
    private lateinit var contexts: Array<CallFrame?>

    /* The stats knob's counters (Task 7 prints them). */
    @JvmField var stablesRead = 0
    @JvmField var objectsRead = 0
    @JvmField var closuresRead = 0
    @JvmField var contextsRead = 0
    @JvmField var drains = 0
    @JvmField var demandNanos = 0L

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

    /* Format 12's string heap: the offset table, then the bytes. */
    private var stringOffsetsPos = 0
    private var stringDataPos = 0

    /* Serialization contexts we depend on. */
    private lateinit var dependentSCs: Array<SerializationContext?>

    /* The object we're currently deserializing. */
    private var curObject: SixModelObject? = null

    fun deserialize() {
        val stats = org.raku.nqp.runtime.unit.UnitLoadStats.ON
        val t0 = if (stats) System.nanoTime() else 0L
        /* The whole load runs under the drain lock, because repossess() and
         * drainAll() below open drains of their own and a drain owns the
         * process-global `current` for its whole life. With the lock in hand
         * the check below is about THIS thread: `current` is only ever set by
         * the thread holding the lock, so a non-null drain here is our own --
         * a deserialize reached from inside a demand, which is the bug the
         * check is for. Nothing between here and the unlock can block on
         * another thread. */
        LOCK.lock()
        try {
            if (current != null) throw RuntimeException("deserialize called inside a demand drain")
            // Serialized data is always little endian.
            orig.order(ByteOrder.LITTLE_ENDIAN)

            // Split the input into the various segments.
            checkAndDisectInput()

            deserializeStringHeap()

            resolveDependencies()

            /* The static code refs, in place; the closure slots after them stay
             * null until demanded. */
            sc.initCodeRefList(crCount + closureTableEntries)
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
            sc.extendCodeRefList(closureTableEntries)

            /* Root arrays to size, every slot null: nothing is stubbed up front. */
            sc.initSTableList(stTableEntries)
            sc.initObjectList(objTableEntries)
            stableState = IntArray(stTableEntries)
            pendingSTable = arrayOfNulls(stTableEntries)
            pendingObj = arrayOfNulls(objTableEntries)
            pendingCode = arrayOfNulls(closureTableEntries)
            contexts = arrayOfNulls(contextTableEntries)

            /* From here the barrier is live: the repossessions below demand
             * through it, and so does everything after us. */
            sc.reader = this
            if (stats) LIVE.add(this)
            if (reposTableEntries > 0) repossess()
            if (EAGER) drainAll()
            if (stats)
                org.raku.nqp.runtime.unit.UnitLoadStats.report(sc.handle, "sc-load", System.nanoTime() - t0,
                    "stables=$stTableEntries objects=$objTableEntries coderefs=$crCount closures=$closureTableEntries contexts=$contextTableEntries")
        } finally {
            LOCK.unlock()
        }
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
        val headerSize = HEADER_SIZE
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

        /* Get size and location of string heap. */
        stringHeapOffset = orig.getInt()
        stringHeapEntries = orig.getInt()
        if (stringHeapOffset < provPos)
            throw RuntimeException("Corruption detected (string table starts before repossessions tabke ends)")
        provPos = stringHeapOffset
        if (provPos > dataLen)
            throw RuntimeException("Corruption detected (string table starts after end of data)")
        if (stringHeapOffset + 4 * (stringHeapEntries + 1) > dataLen)
            throw RuntimeException("Corruption detected (string offset table overruns end of data)")
    }

    private fun deserializeStringHeap() {
        sh = arrayOfNulls(stringHeapEntries + 1)
        sh[0] = null
        /* An offset table, then the bytes; a string decodes on its first
         * lookup (lookupString). Nothing is read here. */
        stringOffsetsPos = stringHeapOffset
        stringDataPos = stringHeapOffset + 4 * (stringHeapEntries + 1)
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

    /* A repossessed entry replaces an object or STable other SCs already
     * hold, so it cannot wait: it is finished before deserialize() returns,
     * as MoarVM's repossess does. STables first (an object row names its
     * STable), then the objects in one drain. */
    private fun repossess() {
        for (pass in 1 downTo 0) {
            topLevel { d ->
                /* Two loops over the table, because the second one demands:
                 * every repossessed slot of this pass is registered in the
                 * pending tables first, so a demand that reaches one finds
                 * the repossessed entry -- not a fresh stub of its own, which
                 * would be both the wrong entry and a second one to publish. */
                val slots = ArrayList<Int>()
                for (i in 0 until reposTableEntries) {
                    orig.position(reposTableOffset + i * REPOS_TABLE_ENTRY_SIZE)
                    val repoType = orig.getInt()
                    if (repoType != pass) continue
                    val slot = orig.getInt()
                    val origSC = locateSC(orig.getInt())
                    val origIdx = orig.getInt()
                    if (repoType == 1) {
                        /* Already published by an earlier drain of this load:
                         * re-registering it would hand the slot a second
                         * identity (a fresh entry published over the live one). */
                        if (sc.peekSTable(slot) != null) continue
                        val origST = origSC.getSTable(origIdx)!!
                        origST.sc = sc
                        origST.scIdx = slot
                        pendingSTable[slot] = origST
                        stableState[slot] = ST_UNREAD
                        d.finished.add(Drain.Entry(this, Drain.STABLE, slot))
                    } else if (repoType == 0) {
                        /* Same second-identity hazard as the STable pass. */
                        if (sc.peekObject(slot) != null) continue
                        val origObj = origSC.getObject(origIdx)!!
                        origObj.sc = sc
                        origObj.scIdx = slot
                        pendingObj[slot] = origObj
                        if (objRowDataOffset(slot) < 0) d.finished.add(Drain.Entry(this, Drain.OBJECT, slot))
                        else d.queue.add(Drain.Entry(this, Drain.OBJECT, slot))
                    } else {
                        throw RuntimeException("Unknown repossession type")
                    }
                    slots.add(slot)
                }
                for (slot in slots) {
                    /* A repossessed STable is read here and not queued: an
                     * object row names its STable. A repossessed object's
                     * STable may have changed (a mixin), so take the row's;
                     * the object itself waits on the drain like any other. */
                    if (pass == 1) { if (stableState[slot] == ST_UNREAD) finishSTable(slot) }
                    else pendingObj[slot]!!.st = objRowSTable(slot)
                }
            }
        }
    }

    /* One outermost demand: a drain, run to empty, then published. Inside
     * a drain (current != null, same thread -- the lock is held for the
     * drain's whole life) a demand only stubs and queues. */
    private inline fun <T> topLevel(body: (Drain) -> T): T {
        val t0 = System.nanoTime()
        val d = Drain()
        current = d
        try {
            val r = body(d)
            d.run()
            d.publish()
            return r
        } catch (e: Throwable) {
            /* Nothing was published -- the root slots are untouched -- but the
             * stubs are in the pending tables, where a later demand would find
             * them half built and hand them straight to guest code (NQP's
             * `try require` makes this a live path, not a fatal one). Drop
             * them, so the next demand rebuilds from the wire. */
            d.rollback()
            throw e
        } finally {
            current = null
            drains++
            demandNanos += System.nanoTime() - t0
        }
    }

    fun demandObject(index: Int): SixModelObject? {
        if (index < 0 || index >= objTableEntries) throw RuntimeException("Invalid SC object index $index")
        LOCK.lock()
        try {
            sc.peekObject(index)?.let { return it }
            val d = current
            if (d != null) return stubObject(index, d)
            val o = topLevel { stubObject(index, it) }
            if (VERIFY && sc.peekObject(index) !== o)
                throw RuntimeException("sc-verify: object $index of ${sc.handle} left a top-level demand unpublished")
            return o
        } finally {
            LOCK.unlock()
        }
    }

    fun demandSTable(index: Int): STable? {
        if (index < 0 || index >= stTableEntries) throw RuntimeException("Invalid STable index $index")
        LOCK.lock()
        try {
            sc.peekSTable(index)?.let { return it }
            val d = current
            if (d != null) return stubAndFinishSTable(index, d)
            return topLevel { stubAndFinishSTable(index, it) }
        } finally {
            LOCK.unlock()
        }
    }

    fun demandCodeRef(index: Int): CodeRef? {
        if (index < crCount) return null          /* a static ref is installed at load or absent for good */
        val j = index - crCount
        if (j >= closureTableEntries) throw RuntimeException("Invalid SC code index $index")
        LOCK.lock()
        try {
            sc.peekCodeRef(index)?.let { return it }
            val d = current
            if (d != null) return stubCode(j, d)
            return topLevel { stubCode(j, it) }
        } finally {
            LOCK.unlock()
        }
    }

    /* Every road below that seeks the buffer saves and restores the
     * position: a demand arrives from the middle of another entry's read of
     * the same buffer. */

    private fun stubAndFinishSTable(i: Int, d: Drain): STable {
        /* Defensive: the barrier peeks before it demands, so a published slot
         * does not reach here -- but stubbing one again would build a second
         * identity for it and publish that over the first. */
        sc.peekSTable(i)?.let { return it }
        var st = pendingSTable[i]
        if (st == null) {
            val saved = orig.position()
            try {
                orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE)
                val repr = REPRRegistry.getByName(lookupString(orig.getInt())!!)
                st = STable(repr, null)
            } finally {
                orig.position(saved)
            }
            st.sc = sc
            st.scIdx = i
            pendingSTable[i] = st
            d.finished.add(Drain.Entry(this, Drain.STABLE, i))
        }
        /* READING: a cycle through this STable's REPR data; the partly built
         * STable is the best on offer, as before. */
        if (stableState[i] == ST_UNREAD) finishSTable(i)
        return st
    }

    private fun finishSTable(i: Int) {
        stableState[i] = ST_READING
        val savedPos = orig.position()
        val savedCur = curObject
        curObject = null          /* an owned array read below belongs to no object */
        try {
            deserializeSTableInner(i)
        } finally {
            stableState[i] = ST_READ
            orig.position(savedPos)
            curObject = savedCur
        }
    }

    private fun stubObject(i: Int, d: Drain): SixModelObject {
        sc.peekObject(i)?.let { return it }
        pendingObj[i]?.let { return it }
        val saved = orig.position()
        val obj: SixModelObject
        val concrete: Boolean
        try {
            val st = objRowSTable(i)          /* demands the STable: finished on return */
            /* Finishing that STable may have demanded this very object -- its
             * WHAT is the type object of this row -- which stubbed it and put
             * it on the drain; that stub is the one, not a second one. */
            pendingObj[i]?.let { return it }
            concrete = objRowDataOffset(i) >= 0
            obj = if (concrete) st.REPR.deserialize_stub(tc, st, this)!!
                  else TypeObject().also { it.st = st }
        } finally {
            orig.position(saved)
        }
        obj.sc = sc
        obj.scIdx = i
        pendingObj[i] = obj
        val e = Drain.Entry(this, Drain.OBJECT, i)
        if (concrete) d.queue.add(e) else d.finished.add(e)
        return obj
    }

    private fun stubCode(j: Int, d: Drain): CodeRef {
        sc.peekCodeRef(crCount + j)?.let { return it }
        pendingCode[j]?.let { return it }
        val saved = orig.position()
        try {
            orig.position(closureTableOffset + j * CLOSURES_TABLE_ENTRY_SIZE)
            val staticCode = codeRefOf(rowRef())
            val closure = staticCode.clone(tc) as CodeRef
            closure.sc = sc
            closure.scCodeIdx = crCount + j
            pendingCode[j] = closure
            /* On the drain with the memo: the reads below can throw or demand,
             * and an entry that is only in the memo would never be rolled back. */
            d.finished.add(Drain.Entry(this, Drain.CODE, j))
            val ctxIdx = orig.getInt()
            val hasCodeObject = orig.getInt() != 0
            if (hasCodeObject) closure.codeObject = objRef(rowRef())
            if (ctxIdx > 0) closure.outer = contextAt(ctxIdx - 1, d)
            return closure
        } finally {
            orig.position(saved)
        }
    }

    private fun contextAt(k: Int, d: Drain): CallFrame {
        contexts[k]?.let { return it }
        val saved = orig.position()
        try {
            orig.position(contextTableOffset + k * CONTEXTS_TABLE_ENTRY_SIZE)
            val staticCode = codeRefOf(rowRef())
            val ctx = CallFrame()
            ctx.tc = tc
            ctx.codeRef = staticCode
            val sci = staticCode.staticInfo
            if (sci.oLexicalNames != null) ctx.oLex = sci.oLexStatic!!.clone()
            if (sci.iLexicalNames != null) ctx.iLex = LongArray(sci.iLexicalNames!!.size)
            if (sci.nLexicalNames != null) ctx.nLex = DoubleArray(sci.nLexicalNames!!.size)
            if (sci.sLexicalNames != null) ctx.sLex = arrayOfNulls(sci.sLexicalNames!!.size)
            contexts[k] = ctx
            d.queue.add(Drain.Entry(this, Drain.CONTEXT, k))
            return ctx
        } finally {
            orig.position(saved)
        }
    }

    /** For a RakuObject stub whose STable is mid-read (ST_READING: the
     *  STable's own HOW/WHAT/WHO or method cache names an instance of
     *  itself, and its REPR data comes after them, so the layout cannot be
     *  in hand): [references, longs] counted from the serialized REPR-data
     *  header -- attribute count, then per attribute a flag and, when
     *  flattened, an STable ref whose REPR names the kind. Null when the
     *  STable is not this SC's (already whole) or its REPR data is read
     *  (the layout is the better answer). Cached per STable. */
    fun peekAttributeShape(st: STable): IntArray? {
        if (st.sc !== sc) return null
        val idx = st.scIdx
        if (idx < 0 || idx >= stTableEntries) return null
        if (stableState[idx] == ST_READ) return null
        shapeCache[idx]?.let { return it }
        val saved = orig.position()
        try {
            orig.position(stTableOffset + idx * STABLES_TABLE_ENTRY_SIZE + 8)
            orig.position(stDataOffset + orig.getInt())
            val n = readLong().toInt()
            var refs = 0; var longs = 0
            for (i in 0 until n) {
                if (readLong() != 0L) {
                    val flattened = readSTableRef()
                    val k = flattened.REPR.inlinedKind()
                    if (k == org.raku.nqp.sixmodel.reprs.SlotKind.INT || k == org.raku.nqp.sixmodel.reprs.SlotKind.NUM) longs++ else refs++
                }
                else refs++
            }
            val shape = intArrayOf(refs, longs)
            shapeCache[idx] = shape
            return shape
        }
        finally {
            orig.position(saved)
        }
    }
    private val shapeCache = HashMap<Int, IntArray>()

    /* Drain.run's callback: finish one queued entry. */
    fun finish(e: Drain.Entry) {
        when (e.kind) {
            Drain.OBJECT -> {
                val obj = pendingObj[e.index]!!
                orig.position(objDataOffset + objRowDataOffset(e.index))
                curObject = obj
                try { obj.st.REPR.deserialize_finish(tc, obj.st, this, obj) }
                finally { curObject = null }
            }
            Drain.CONTEXT -> finishContext(e.index)
            else -> throw IllegalStateException("only objects and contexts are queued")
        }
    }

    private fun finishContext(k: Int) {
        val ctx = contexts[k]!!
        val sci = ctx.codeRef.staticInfo
        orig.position(contextTableOffset + k * CONTEXTS_TABLE_ENTRY_SIZE + 8)
        val dataOffset = orig.getInt()
        val outerIdx = orig.getInt()
        orig.position(contextDataOffset + dataOffset)

        /* Deserialize lexicals. */
        val syms = readLong()
        for (j in 0 until syms) {
            val sym = readStr()!!
            var idx = sci.oTryGetLexicalIdx(sym)
            if (idx != -1) {
                ctx.oLex!![idx] = readRef()
            } else {
                idx = sci.iTryGetLexicalIdx(sym)
                if (idx != -1) {
                    ctx.iLex!![idx] = readLong()
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
        if (outerIdx > 0) ctx.outer = contextAt(outerIdx - 1, current!!)
        else ctx.resolveDeserializedOuter()
    }

    /* Drain.rollback's callback: drop a stub the drain never published. */
    fun unstub(e: Drain.Entry) {
        when (e.kind) {
            /* Only what is still pending: a publish that failed partway
             * already cleared what it stored, and resetting that STable's
             * state would contradict the root slot. */
            Drain.STABLE -> if (pendingSTable[e.index] != null) { pendingSTable[e.index] = null; stableState[e.index] = ST_UNREAD }
            Drain.OBJECT -> pendingObj[e.index] = null
            Drain.CODE -> pendingCode[e.index] = null
            Drain.CONTEXT -> contexts[e.index] = null
        }
    }

    /* Drain.publish's callback: the release store into the root slot. */
    fun publish(e: Drain.Entry) {
        when (e.kind) {
            Drain.STABLE -> { sc.publishSTable(e.index, pendingSTable[e.index]!!); pendingSTable[e.index] = null; stablesRead++ }
            Drain.OBJECT -> { sc.publishObject(e.index, pendingObj[e.index]!!); pendingObj[e.index] = null; objectsRead++ }
            Drain.CODE -> { sc.publishCodeRef(crCount + e.index, pendingCode[e.index]!!); pendingCode[e.index] = null; closuresRead++ }
            Drain.CONTEXT -> contextsRead++
        }
    }

    /* NQP_SC_EAGER=1: everything at load, the pre-Phase-C order, for
     * bisecting a demand-order bug. */
    private fun drainAll() {
        topLevel { d ->
            /* A published slot is skipped, exactly as the demand roads skip it:
             * repossess() ran its own drains before us and published what they
             * finished, and stubbing such a slot again would build a SECOND
             * identity for it and publish that over the first (the layout of
             * one type then holds a class handle the guest never sees again).
             * Contexts have no root slot; contextAt's own table is the memo. */
            for (i in 0 until stTableEntries) if (sc.peekSTable(i) == null) stubAndFinishSTable(i, d)
            for (i in 0 until objTableEntries) if (sc.peekObject(i) == null) stubObject(i, d)
            for (j in 0 until closureTableEntries) if (sc.peekCodeRef(crCount + j) == null) stubCode(j, d)
            for (k in 0 until contextTableEntries) if (contexts[k] == null) contextAt(k, d)
        }
    }

    /* The STable of object row i. */
    private fun objRowSTable(i: Int): STable {
        orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE)
        val packed = orig.getInt()
        return lookupSTable(packed and 0xFFF, packed ushr 12)
    }

    /* Object row i's data offset, or -1 for a type object. */
    private fun objRowDataOffset(i: Int): Int {
        orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE + 4)
        val off = orig.getInt()
        return if (off < 0) -1 else off
    }

    private fun deserializeSTableInner(i: Int) {
        // Seek to the right position in the data chunk.
        orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE + 4)
        orig.position(stDataOffset + orig.getInt())

        // Get the STable we need to deserialize into.
        val st = pendingSTable[i]!!

        /* HOW and WHO stay pending (Phase C): the reference is kept, the
         * object demanded on first read. WHAT is the type object itself,
         * a stub with no data, so it is read now. */
        val how = readPackedRef()
        st.setPendingHow(locateSC((how ushr 32).toInt()), how.toInt())
        st.WHAT = readObjRef()
        val whoTag = readTag()
        if (whoTag == REFVAR_OBJECT) {
            val who = readPackedRef()
            st.setPendingWho(locateSC((who ushr 32).toInt()), who.toInt())
        } else {
            /* readTag() consumed exactly one byte (format 12), so stepping
             * back by one hands the whole reference to readRef(). */
            orig.position(orig.position() - 1)
            st.WHO = readRef()
        }

        /* Method cache and v-table. */
        val methodCacheRef = readRef()
        /* A COPY of the deserialized hash: the guest keeps the hash object and
         * may mutate it, and a mutation must never change a published fact
         * behind the assumption's back -- facts change by publishing only. */
        val methodCache: Map<String, SixModelObject?>? =
            if (Ops.isnull(methodCacheRef) == 0L) HashMap((methodCacheRef as VMHashInstance).storage) else null
        val vTable = arrayOfNulls<SixModelObject>(readLong().toInt())
        for (j in vTable.indices)
            vTable[j] = readRef()

        /* Type check cache. */
        val tcCacheSize = readLong().toInt()
        var typeCheckCache: Array<SixModelObject?>? = null
        if (tcCacheSize > 0) {
            val cache = arrayOfNulls<SixModelObject>(tcCacheSize)
            for (j in cache.indices)
                cache[j] = readRef()
            typeCheckCache = cache
        }

        /* Mode flags. */
        val modeFlags = readLong().toInt()

        /* Boolification spec. */
        var boolSpec: BoolificationSpec? = null
        if (readLong() != 0L) {
            val mode = readLong().toInt()
            boolSpec = BoolificationSpec(mode, readRef())
        }

        /* Container spec: built complete, then published with the rest. */
        var contSpec: ContainerSpec? = null
        if (readLong() != 0L) {
            val ccName = readStr()
            val cc = tc.gc.contConfigs[ccName]
                ?: throw RuntimeException("Unknown container config $ccName")
            val cs = cc.newContainerSpec(tc, st)
            cs.deserialize(tc, st, this)
            contSpec = cs
        }

        /* Invocation spec. */
        var invSpec: InvocationSpec? = null
        if (readLong() != 0L) {
            val classHandle = readRef()
            val attrName = readStr()
            val hint = readLong().toInt().toLong()
            invSpec = InvocationSpec(classHandle, attrName, hint, readRef())
        }

        /* HLL stuff. */
        val hllOwner: HLLConfig? = tc.gc.getHLLConfigFor(readStr()!!)
        val hllRole = readLong()

        /* One publish, here: the point at which every fact field used to be
         * assigned, so an object deserialized from inside the parametricity
         * or REPR-data reads below sees the same facts it saw before (ledger
         * ruling 1). The REPR data that follows gets its own republish: the
         * `create` and bigint sites do trust the state for it (ruling 1 as
         * amended by the final review). */
        st.publish(TypeState(methodCache, vTable, typeCheckCache, modeFlags, contSpec, invSpec,
            boolSpec, hllOwner, hllRole, st.debugName))

        /* Type parametricity. */
        val paraFlag = readLong()
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
            /* The element count is VMArray.serialize's writeInt32. */
            val elems = readInt32()
            for (j in 0 until elems)
                parameters.bind_pos_boxed(tc, j.toLong(), readRef())
            st.parametricity = pt
        } else if (paraFlag != 0L) {
            throw RuntimeException("Unknown STable parametricity flag")
        }

        /* If the REPR has a function to deserialize representation data, call it. */
        st.REPR.deserialize_repr_data(tc, st, this)
        /* REPRData is outside the state, so it needs a publish of its own. */
        st.republish()
    }

    fun readRef(): SixModelObject? {
        val discrim = readTag()
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
            iResult.set_int(tc, readLong())
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
            sResult.set_str(tc, readStr())
            return sResult
        }
        REFVAR_VM_ARR_VAR -> {
            val BOOTArray = tc.gc.BOOTArray!!
            val resArray = BOOTArray.st.REPR.allocate(tc, BOOTArray.st)
            val elems = readCount()
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
            val elems = readCount()
            for (i in 0 until elems) {
                tc.nativeS = readStr()
                resArray.bind_pos_native(tc, i.toLong())
            }
            return resArray
        }
        REFVAR_VM_ARR_INT -> {
            val BOOTIntArray = tc.gc.BOOTIntArray!!
            val resArray = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st)
            val elems = readCount()
            for (i in 0 until elems) {
                tc.nativeI = readLong()
                resArray.bind_pos_native(tc, i.toLong())
            }
            return resArray
        }
        REFVAR_VM_HASH_STR_VAR -> {
            val BOOTHash = tc.gc.BOOTHash!!
            val resHash = BOOTHash.st.REPR.allocate(tc, BOOTHash.st)
            val elems = readCount()
            for (i in 0 until elems) {
                val key = readStr()
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

    /* A packed reference, as (scIdx shl 32) or idx. */
    private fun readPackedRef(): Long {
        val p = Varint.readUnsigned(orig)
        val idx = p ushr 1
        val scIdx = if ((p and 1L) == 0L) 0 else Varint.readUnsignedInt(orig)
        return (scIdx.toLong() shl 32) or idx
    }

    /* A reference in a fixed-width table row: always two raw ints. */
    private fun rowRef(): Long {
        val scIdx = orig.getInt()
        val idx = orig.getInt()
        return (scIdx.toLong() shl 32) or (idx.toLong() and 0xFFFFFFFFL)
    }

    private fun objRef(r: Long): SixModelObject {
        val objSC = locateSC((r ushr 32).toInt())
        val idx = r.toInt()
        if (idx < 0 || idx >= objSC.objectCount())
            throw RuntimeException("Invalid SC object index $idx")
        return objSC.getObject(idx)!!
    }

    private fun codeRefOf(r: Long): CodeRef {
        val codeSC = locateSC((r ushr 32).toInt())
        val idx = r.toInt()
        if (idx < 0 || idx >= codeSC.coderefCount())
            throw RuntimeException("Invalid SC code index $idx")
        return codeSC.getCodeRef(idx)!!
    }

    fun readObjRef(): SixModelObject = objRef(readPackedRef())

    fun readCodeRef(): CodeRef = codeRefOf(readPackedRef())

    fun readSTableRef(): STable { val r = readPackedRef(); return lookupSTable((r ushr 32).toInt(), r.toInt()) }

    fun readLong(): Long = Varint.readSigned(orig)

    fun readInt32(): Int = Varint.readSigned(orig).toInt()

    fun readDouble(): Double = orig.getDouble()

    fun readStr(): String? = lookupString(Varint.readUnsignedInt(orig))

    /* An element count: the writer's writeCount and writeInt32 are one codec,
     * so this is readInt32 under another name. */
    private fun readCount(): Int = readInt32()

    /* One tag byte. */
    private fun readTag(): Short = orig.get().toShort()

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
        if (idx < 0 || idx >= sh.size)
            throw RuntimeException("Attempt to read past end of string heap (index $idx)")
        if (idx == 0) return null
        val s = sh[idx]
        if (s != null) return s
        return decodeString(idx)
    }

    /* Format 12: bytes offsets[idx-1] until offsets[idx] of the string data.
     * Absolute gets, so the caller's position is untouched. String is
     * immutable and safely published; two threads decoding the same index
     * produce equal strings, so the plain array write is benign. */
    private fun decodeString(idx: Int): String {
        val start = orig.getInt(stringOffsetsPos + 4 * (idx - 1))
        val end = orig.getInt(stringOffsetsPos + 4 * idx)
        if (start < 0 || end < start || stringDataPos + end > orig.limit())
            throw RuntimeException("Corruption detected (string $idx offsets $start..$end)")
        val bytes = ByteArray(end - start)
        orig.get(stringDataPos + start, bytes)
        val s = String(bytes, Charsets.UTF_8)
        sh[idx] = s
        return s
    }
}
