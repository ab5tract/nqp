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
    private var root_objects = ArrayList<SixModelObject?>()

    /* The root set of STables that live in this SC. */
    private var root_stables = ArrayList<STable?>()

    /* The root set of code refs that live in this SC. */
    private var root_codes = ArrayList<CodeRef?>()

    /* Repossession info. The following lists have matching indexes, each
     * representing the integer of an object in our root set along with the SC
     * that the object was originally from. */
    @JvmField var rep_indexes = IntArrayList()
    @JvmField var rep_scs = ArrayList<SerializationContext>()

    /* Some things we deserialize are not directly in an SC, root set, but
     * rather are owned by others. This is mostly thanks to Parrot legacy,
     * where not everything was a 6model object. This maps such owned
     * objects to their owner. It is used to determine what object should
     * be repossessed in the case a write barrier is hit. */
    @JvmField var owned_objects = HashMap<SixModelObject, SixModelObject>()

    /* Takes an object and adds it to this SC's root set, and installs a
     * reposession entry. */
    fun repossessObject(origSC: SerializationContext, obj: SixModelObject) {
        /* Check the object really lives in the SC root set. */
        if (obj.sc!!.root_objects.indexOf(obj) < 0)
            throw RuntimeException("Attempt to repossess object not in this context")

        /* Add to root set. */
        val newSlot = root_objects.size
        addObject(obj)

        /* Add repossession entry. */
        rep_indexes.add(newSlot shl 1)
        rep_scs.add(origSC)
    }

    /* Takes an STable and adds it to this SC's root set, and installs a
     * reposession entry. */
    fun repossessSTable(origSC: SerializationContext, st: STable) {
        /* Add to root set. */
        val newSlot = root_stables.size
        addSTable(st)

        /* Add repossession entry. */
        rep_indexes.add((newSlot shl 1) or 1)
        rep_scs.add(origSC)
    }

    private val objectIndexCache = Object2IntOpenHashMap<SixModelObject>()

    fun addObject(obj: SixModelObject?) {
        val newIndex = root_objects.size
        root_objects.add(obj)
        objectIndexCache.put(obj, newIndex)
    }

    fun addObject(obj: SixModelObject?, index: Int) {
        if (index == root_objects.size) {
            root_objects.add(obj)
        } else {
            root_objects[index] = obj
        }
        objectIndexCache.put(obj, index)
    }

    fun getObjectIndex(obj: SixModelObject?): Int = objectIndexCache.getInt(obj)

    fun getObject(index: Int): SixModelObject? = root_objects[index]

    fun objectCount(): Int = root_objects.size

    fun initObjectList(entries: Int) {
        root_objects.ensureCapacity(entries)
        for (i in 0 until entries)
            root_objects.add(null)
    }

    private val stableIndexCache = Object2IntOpenHashMap<STable>()

    fun addSTable(stable: STable?) {
        val newIndex = root_stables.size
        root_stables.add(stable)
        stableIndexCache.put(stable, newIndex)
    }

    fun setSTable(index: Int, stable: STable?) {
        root_stables[index] = stable
        stableIndexCache.put(stable, index)
    }

    fun getSTableIndex(stable: STable?): Int = stableIndexCache.get(stable)!!

    fun getSTable(index: Int): STable? = root_stables[index]

    fun stableCount(): Int = root_stables.size

    fun initSTableList(entries: Int) {
        root_stables.ensureCapacity(entries)
        for (i in 0 until entries)
            root_stables.add(null)
    }

    private val codeIndexCache = Object2IntOpenHashMap<CodeRef>()

    fun addCodeRef(coderef: CodeRef?) {
        val newIndex = root_codes.size
        root_codes.add(coderef)
        codeIndexCache.put(coderef, newIndex)
    }

    fun addCodeRef(obj: CodeRef?, index: Int) {
        if (index == root_codes.size) {
            root_codes.add(obj)
        } else {
            root_codes[index] = obj
        }
        codeIndexCache.put(obj, index)
    }

    fun getCodeIndex(coderef: SixModelObject?): Int = codeIndexCache.get(coderef)!!

    fun getCodeRef(index: Int): CodeRef? = root_codes[index]

    fun coderefCount(): Int = root_codes.size

    fun disclaimObjects() {
        for (obj in root_objects)
            obj?.sc = null
        root_objects = ArrayList()
    }

    fun disclaimSTables() {
        for (stable in root_stables)
            stable?.sc = null
        root_stables = ArrayList()
    }

    fun disclaimCodes() {
        for (obj in root_codes)
            obj?.sc = null
        root_codes = ArrayList()
    }
}
