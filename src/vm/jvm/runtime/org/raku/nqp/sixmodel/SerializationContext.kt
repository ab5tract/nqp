package org.raku.nqp.sixmodel

import it.unimi.dsi.fastutil.ints.IntArrayList

import org.raku.nqp.runtime.CodeRef

/**
 * A serialization context holds a list of objects and code references that live
 * within a serialization boundary.
 */
class SerializationContext(@JvmField var handle: String) {
    /* Description (probably the file name) if any. */
    @JvmField var description: String? = null

    /* The artifact stamp (UnitStore.serializedCrc) this SC was deserialized
     * under; 0 for an SC built in this process (the bootstrap, a compile in
     * progress). DispatchSlot persists it per referenced handle and restore
     * drops a slot whose stamp disagrees. */
    @JvmField var stamp: Int = 0

    /* The three root sets. */
    private val objects = RootSet<SixModelObject>()
    private val stables = RootSet<STable>()
    private val codes = RootSet<CodeRef>()

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
     * reposession entry. The caller (Ops.scwbObject) moves obj.sc to this
     * SC afterwards; the index field already points at the new slot. */
    fun repossessObject(origSC: SerializationContext, obj: SixModelObject) {
        /* Check the object really lives in the SC root set. */
        if (obj.sc!!.getObjectIndex(obj) < 0)
            throw RuntimeException("Attempt to repossess object not in this context")

        /* Add to root set. */
        val newSlot = addObject(obj)

        /* Add repossession entry. */
        repIndexes.add(newSlot shl 1)
        repScs.add(origSC)
    }

    /* Takes an STable and adds it to this SC's root set, and installs a
     * reposession entry. */
    fun repossessSTable(origSC: SerializationContext, st: STable) {
        val newSlot = addSTable(st)
        repIndexes.add((newSlot shl 1) or 1)
        repScs.add(origSC)
    }

    /* Objects. The index lives on the object (scIdx); a lookup validates it
     * by reading the slot back, so a stale field answers -1, never a wrong
     * index. */
    fun addObject(obj: SixModelObject?): Int {
        val i = objects.add(obj)
        obj?.scIdx = i
        return i
    }

    fun addObject(obj: SixModelObject?, index: Int) {
        if (index == objects.size) objects.add(obj) else objects.set(index, obj)
        obj?.scIdx = index
    }

    fun getObjectIndex(obj: SixModelObject?): Int {
        val i = obj?.scIdx ?: return -1
        return if (i >= 0 && i < objects.size && objects.get(i) === obj) i else -1
    }

    fun getObject(index: Int): SixModelObject? = objects.get(index)
    fun objectCount(): Int = objects.size
    fun initObjectList(entries: Int) = objects.init(entries)

    /* STables. */
    fun addSTable(stable: STable?): Int {
        val i = stables.add(stable)
        stable?.scIdx = i
        return i
    }

    fun setSTable(index: Int, stable: STable?) {
        stables.set(index, stable)
        stable?.scIdx = index
    }

    fun getSTableIndex(stable: STable?): Int {
        val i = stable?.scIdx ?: return -1
        return if (i >= 0 && i < stables.size && stables.get(i) === stable) i else -1
    }

    fun getSTable(index: Int): STable? = stables.get(index)
    fun stableCount(): Int = stables.size
    fun initSTableList(entries: Int) = stables.init(entries)

    /* Code refs. */
    fun initCodeRefList(entries: Int) = codes.ensureCapacity(entries)

    fun addCodeRef(coderef: CodeRef?): Int {
        val i = codes.add(coderef)
        coderef?.scCodeIdx = i
        return i
    }

    fun addCodeRef(obj: CodeRef?, index: Int) {
        if (index == codes.size) codes.add(obj) else codes.set(index, obj)
        obj?.scCodeIdx = index
    }

    fun getCodeIndex(coderef: SixModelObject?): Int {
        val cr = coderef as? CodeRef ?: return -1
        val i = cr.scCodeIdx
        return if (i >= 0 && i < codes.size && codes.get(i) === cr) i else -1
    }

    fun getCodeRef(index: Int): CodeRef? = codes.get(index)
    fun coderefCount(): Int = codes.size

    fun disclaimObjects() {
        for (i in 0 until objects.size) objects.get(i)?.let { it.sc = null; it.scIdx = -1 }
        objects.clear()
    }

    fun disclaimSTables() {
        for (i in 0 until stables.size) stables.get(i)?.let { it.sc = null; it.scIdx = -1 }
        stables.clear()
    }

    fun disclaimCodes() {
        for (i in 0 until codes.size) codes.get(i)?.let { it.sc = null; it.scCodeIdx = -1 }
        codes.clear()
    }
}
