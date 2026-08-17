package org.raku.nqp.sixmodel

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.reprs.*

object REPRRegistry {
    private val reprIdMap = Object2IntOpenHashMap<String>()
    private val reprs = ArrayList<REPR>()

    @JvmStatic
    fun getByName(name: String): REPR {
        val idx = reprIdMap.getOrDefault(name, -1)
        if (idx == -1)
            throw RuntimeException("No REPR " + name)
        return getById(idx)
    }

    @JvmStatic
    fun getById(id: Int): REPR {
        if (id < reprs.size)
            return reprs[id]
        else
            throw RuntimeException("No REPR " + id)
    }

    private fun addREPR(name: String, repr: REPR) {
        var name = name
        repr.ID = reprs.size
        reprIdMap.put(name, reprs.size)
        if (name.startsWith("VMArray")) {
            /* To detect native VMArrays during deserialization we use an extra
             * field. We can set the correct name (VMArray) at this point, since
             * lookup will be done from reprIdMap, which knows the long name.
             */
            repr.subtypeName = name
            name = "VMArray"
        }
        repr.name = name
        reprs.add(repr)
    }

    /* Registration order is load-bearing: REPR ids are indices into this
     * list and are baked into serialized data. Never reorder. */
    init {
        addREPR("KnowHOWREPR", KnowHOWREPR())
        addREPR("KnowHOWAttribute", KnowHOWAttribute())
        addREPR("P6opaque", P6Opaque())
        addREPR("VMHash", VMHash())
        addREPR("VMArray", VMArray())
        addREPR("VMArray_i8", VMArray())
        addREPR("VMArray_u8", VMArray())
        addREPR("VMArray_i16", VMArray())
        addREPR("VMArray_u16", VMArray())
        addREPR("VMArray_i32", VMArray())
        addREPR("VMArray_u32", VMArray())
        addREPR("VMArray_i", VMArray())
        addREPR("VMArray_n", VMArray())
        addREPR("VMArray_s", VMArray())
        addREPR("VMIter", VMIter())
        addREPR("P6str", P6str())
        addREPR("P6int", P6int())
        addREPR("P6num", P6num())
        addREPR("Uninstantiable", Uninstantiable())
        addREPR("SCRef", SCRef())
        addREPR("JavaWrap", JavaWrap())
        addREPR("ContextRef", ContextRef())
        addREPR("Continuation", Continuation())
        addREPR("CodeRef", CodeRefREPR())
        addREPR("CallCapture", CallCapture())
        addREPR("NFA", NFA())
        addREPR("VMException", VMException())
        addREPR("IOHandle", IOHandle())
        addREPR("P6bigint", P6bigint())
        addREPR("MultiCache", MultiCache())
        addREPR("NativeCall", NativeCall())
        addREPR("CPointer", CPointer())
        addREPR("CArray", CArray())
        addREPR("CStr", CStr())
        addREPR("CStruct", CStruct())
        addREPR("CPPStruct", CPPStruct())
        addREPR("CUnion", CUnion())
        addREPR("VMNull", VMNull())
        addREPR("VMThread", VMThread())
        addREPR("ReentrantMutex", ReentrantMutex())
        addREPR("Semaphore", Semaphore())
        addREPR("ConcBlockingQueue", ConcBlockingQueue())
        addREPR("ConditionVariable", ConditionVariable())
        addREPR("AsyncTask", AsyncTask())
        addREPR("NativeRef", NativeRef())
        addREPR("MultiDimArray", MultiDimArray())
        addREPR("Decoder", Decoder())
        addREPR("Tracked", Tracked())
    }
}
