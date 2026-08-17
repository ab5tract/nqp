package org.raku.nqp.sixmodel

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.runtime.CodeRef

/**
 * A serialization context holds a list of objects and code references that live
 * within a serialization boundary.
 */
class SerializationContext(@JvmField var handle: String) {
    /* Description (probably the file name) if any. */
    @JvmField var description: String? = null

    /* The root set of objects that live in this SC. */
    private var rootObjects = ArrayList<SixModelObject?>()

    /* The root set of STables that live in this SC. */
    private var rootStables = ArrayList<STable?>()

    /* The root set of code refs that live in this SC. */
    private var rootCodes = ArrayList<CodeRef?>()

    /* Repossession info. The following lists have matching indexes, each
     * representing the integer of an object in our root set along with the SC
     * that the object was originally from. */
    @JvmField var repIndexes = IntArrayList()
    @JvmField var repScs = ArrayList<SerializationContext>()

    /* Some things we deserialize are not directly in an SC, root set, but
     * rather are owned by others. This is mostly thanks to Parrot legacy,
     * where not everything was a 6model object. This maps such owned
     * objects to their owner. It is used to determine what object should
     * be repossessed in the case a write barrier is hit. */
    @JvmField var ownedObjects = HashMap<SixModelObject, SixModelObject>()

    /* Takes an object and adds it to this SC's root set, and installs a
     * reposession entry. */
    fun repossessObject(origSC: SerializationContext, obj: SixModelObject) {
        /* Check the object really lives in the SC root set. */
        if (obj.sc!!.rootObjects.indexOf(obj) < 0)
            throw RuntimeException("Attempt to repossess object not in this context")

        /* Add to root set. */
        val newSlot = rootObjects.size
        addObject(obj)

        /* Add repossession entry. */
        repIndexes.add(newSlot shl 1)
        repScs.add(origSC)
    }

    /* Takes an STable and adds it to this SC's root set, and installs a
     * reposession entry. */
    fun repossessSTable(origSC: SerializationContext, st: STable) {
        /* Add to root set. */
        val newSlot = rootStables.size
        addSTable(st)

        /* Add repossession entry. */
        repIndexes.add((newSlot shl 1) or 1)
        repScs.add(origSC)
    }

    private val objectIndexCache = Object2IntOpenHashMap<SixModelObject>()

    fun addObject(obj: SixModelObject?) {
        val newIndex = rootObjects.size
        rootObjects.add(obj)
        objectIndexCache.put(obj, newIndex)
    }

    fun addObject(obj: SixModelObject?, index: Int) {
        if (index == rootObjects.size) {
            rootObjects.add(obj)
        } else {
            rootObjects[index] = obj
        }
        objectIndexCache.put(obj, index)
    }

    fun getObjectIndex(obj: SixModelObject?): Int = objectIndexCache.getInt(obj)

    fun getObject(index: Int): SixModelObject? = rootObjects[index]

    fun objectCount(): Int = rootObjects.size

    fun initObjectList(entries: Int) {
        rootObjects.ensureCapacity(entries)
        for (i in 0 until entries)
            rootObjects.add(null)
    }

    private val stableIndexCache = Object2IntOpenHashMap<STable>()

    fun addSTable(stable: STable?) {
        val newIndex = rootStables.size
        rootStables.add(stable)
        stableIndexCache.put(stable, newIndex)
    }

    fun setSTable(index: Int, stable: STable?) {
        rootStables[index] = stable
        stableIndexCache.put(stable, index)
    }

    fun getSTableIndex(stable: STable?): Int = stableIndexCache.get(stable)!!

    fun getSTable(index: Int): STable? = rootStables[index]

    fun stableCount(): Int = rootStables.size

    fun initSTableList(entries: Int) {
        rootStables.ensureCapacity(entries)
        for (i in 0 until entries)
            rootStables.add(null)
    }

    private val codeIndexCache = Object2IntOpenHashMap<CodeRef>()

    fun addCodeRef(coderef: CodeRef?) {
        val newIndex = rootCodes.size
        rootCodes.add(coderef)
        codeIndexCache.put(coderef, newIndex)
    }

    fun addCodeRef(obj: CodeRef?, index: Int) {
        if (index == rootCodes.size) {
            rootCodes.add(obj)
        } else {
            rootCodes[index] = obj
        }
        codeIndexCache.put(obj, index)
    }

    fun getCodeIndex(coderef: SixModelObject?): Int = codeIndexCache.get(coderef)!!

    fun getCodeRef(index: Int): CodeRef? = rootCodes[index]

    fun coderefCount(): Int = rootCodes.size

    fun disclaimObjects() {
        for (obj in rootObjects)
            obj?.sc = null
        rootObjects = ArrayList()
    }

    fun disclaimSTables() {
        for (stable in rootStables)
            stable?.sc = null
        rootStables = ArrayList()
    }

    fun disclaimCodes() {
        for (obj in rootCodes)
            obj?.sc = null
        rootCodes = ArrayList()
    }
}
