package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.HLLConfig
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.reprs.KnowHOWREPRInstance

object KnowHOWBootstrapper {
    @JvmStatic
    fun bootstrap(tc: ThreadContext) {
        val knowhowUnit: CompilationUnit = KnowHOWMethods()
        knowhowUnit.initializeCompilationUnit(tc)
        bootstrapKnowHOW(tc, knowhowUnit)
        bootstrapKnowHOWAttribute(tc, knowhowUnit)

        tc.gc.BOOTArray = bootType(tc, "BOOTArray", "VMArray")
        tc.gc.BOOTHash = bootType(tc, "BOOTHash", "VMHash")
        tc.gc.BOOTIter = bootType(tc, "BOOTIter", "VMIter")
        tc.gc.BOOTInt = bootType(tc, "BOOTInt", "P6int")
        tc.gc.BOOTNum = bootType(tc, "BOOTNum", "P6num")
        tc.gc.BOOTStr = bootType(tc, "BOOTStr", "P6str")
        tc.gc.BOOTCode = bootType(tc, "BOOTCode", "CodeRef")
        tc.gc.SCRef = bootType(tc, "SCRef", "SCRef")
        tc.gc.ContextRef = bootType(tc, "ContextRef", "ContextRef")
        tc.gc.CallCapture = bootType(tc, "CallCapture", "CallCapture")
        tc.gc.Tracked = bootType(tc, "Tracked", "Tracked")
        tc.gc.BOOTException = bootType(tc, "BOOTException", "VMException")
        tc.gc.BOOTIO = bootType(tc, "BOOTIO", "IOHandle")
        tc.gc.VMNull = bootType(tc, "VMNull", "VMNull")
        tc.gc.Thread = bootType(tc, "Thread", "VMThread")

        tc.gc.BOOTArray!!.st.hllRole = HLLConfig.ROLE_ARRAY.toLong()
        tc.gc.BOOTHash!!.st.hllRole = HLLConfig.ROLE_HASH.toLong()
        tc.gc.BOOTInt!!.st.hllRole = HLLConfig.ROLE_INT.toLong()
        tc.gc.BOOTNum!!.st.hllRole = HLLConfig.ROLE_NUM.toLong()
        tc.gc.BOOTStr!!.st.hllRole = HLLConfig.ROLE_STR.toLong()
        tc.gc.BOOTCode!!.st.hllRole = HLLConfig.ROLE_CODE.toLong()

        tc.gc.BOOTIntArray = bootTypedArray(tc, "BOOTIntArray", tc.gc.BOOTInt!!)
        tc.gc.BOOTNumArray = bootTypedArray(tc, "BOOTNumArray", tc.gc.BOOTNum!!)
        tc.gc.BOOTStrArray = bootTypedArray(tc, "BOOTStrArray", tc.gc.BOOTStr!!)
        tc.gc.MultiCache = bootType(tc, "MultiCache", "MultiCache")
        tc.gc.Continuation = bootType(tc, "Continuation", "Continuation")
        tc.gc.BOOTJava = bootType(tc, "BOOTJavaObject", "JavaWrap")

        // fixup missing STable for knowhowHow and KnowHOWAttribute methods;
        // the dispatch program recorder reads a callee's STable, so every
        // code ref reachable through a method table needs one
        for (cr in (tc.gc.KnowHOW!!.st.HOW as KnowHOWREPRInstance).methods!!.entries) {
            cr.value!!.st = tc.gc.BOOTCode!!.st
        }
        for (cr in (tc.gc.KnowHOWAttribute!!.st.HOW as KnowHOWREPRInstance).methods!!.entries) {
            cr.value!!.st = tc.gc.BOOTCode!!.st
        }

        Ops.setboolspec(tc.gc.BOOTIter, BoolificationSpec.MODE_ITER.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTInt, BoolificationSpec.MODE_UNBOX_INT.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTNum, BoolificationSpec.MODE_UNBOX_NUM.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTStr, BoolificationSpec.MODE_UNBOX_STR_NOT_EMPTY.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTArray, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTIntArray, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTNumArray, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTStrArray, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
        Ops.setboolspec(tc.gc.BOOTHash, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
    }

    private fun bootstrapKnowHOW(tc: ThreadContext, knowhowUnit: CompilationUnit) {
        /* Create our KnowHOW type object. Note we don't have a HOW just yet, so
         * pass in NULL. */
        val repr = REPRRegistry.getByName("KnowHOWREPR")
        val knowhow = repr.type_object_for(tc, null)

        /* We create a KnowHOW instance that can describe itself. This means
         * (once we tie the knot) that .HOW.HOW.HOW.HOW etc will always return
         * that, which closes the model up. */
        val st = STable(repr, null)
        st.WHAT = knowhow
        val knowhowHow = repr.allocate(tc, st) as KnowHOWREPRInstance
        st.HOW = knowhowHow
        knowhowHow.st = st

        /* Add various methods to the KnowHOW's HOW. */
        val methods = knowhowHow.methods!!
        methods["new_type"] = knowhowUnit.lookupCodeRef("new_type")!!
        methods["add_method"] = knowhowUnit.lookupCodeRef("add_method")!!
        methods["find_method"] = knowhowUnit.lookupCodeRef("find_method")!!
        methods["add_attribute"] = knowhowUnit.lookupCodeRef("add_attribute")!!
        methods["compose"] = knowhowUnit.lookupCodeRef("compose")!!
        methods["attributes"] = knowhowUnit.lookupCodeRef("attributes")!!
        methods["methods"] = knowhowUnit.lookupCodeRef("methods")!!
        methods["name"] = knowhowUnit.lookupCodeRef("name")!!

        /* Set name KnowHOW for the KnowHOW's HOW. */
        knowhowHow.name = "KnowHOW"

        /* Set this built up HOW as the KnowHOW's HOW. */
        knowhow.st.HOW = knowhowHow

        /* Give it an authoritative method cache; this in turn will make the
         * method dispatch bottom out. */
        knowhow.st.MethodCache = methods
        knowhow.st.ModeFlags = STable.METHOD_CACHE_AUTHORITATIVE
        knowhowHow.st.MethodCache = methods
        knowhowHow.st.ModeFlags = STable.METHOD_CACHE_AUTHORITATIVE

        /* Associate the created objects with the initial core serialization
         * context. */
        val sc = SerializationContext("__6MODEL_CORE__")
        tc.gc.scs["__6MODEL_CORE__"] = sc
        sc.addObject(knowhow)
        knowhow.sc = sc
        sc.addObject(knowhowHow)
        knowhowHow.sc = sc
        sc.addSTable(knowhow.st)
        knowhow.st.sc = sc
        sc.addSTable(knowhowHow.st)
        knowhowHow.st.sc = sc

        /* Stash the created KnowHOW. */
        tc.gc.KnowHOW = knowhow
    }

    private fun bootstrapKnowHOWAttribute(tc: ThreadContext, knowhowUnit: CompilationUnit) {
        /* Create meta-object. */
        val knowhowHow = tc.gc.KnowHOW!!.st.HOW!!
        val metaObj = knowhowHow.st.REPR.allocate(tc, knowhowHow.st) as KnowHOWREPRInstance

        /* Add methods. */
        val methods = metaObj.methods!!
        methods["new"] = knowhowUnit.lookupCodeRef("attr_new")!!
        methods["compose"] = knowhowUnit.lookupCodeRef("attr_compose")!!
        methods["name"] = knowhowUnit.lookupCodeRef("attr_name")!!
        methods["type"] = knowhowUnit.lookupCodeRef("attr_type")!!
        methods["box_target"] = knowhowUnit.lookupCodeRef("attr_box_target")!!

        /* Set name. */
        metaObj.name = "KnowHOWAttribute"

        /* Create a new type object with the correct REPR. */
        val repr = REPRRegistry.getByName("KnowHOWAttribute")
        val typeObj = repr.type_object_for(tc, metaObj)

        /* Set up method dispatch cache. */
        typeObj.st.MethodCache = methods
        typeObj.st.ModeFlags = STable.METHOD_CACHE_AUTHORITATIVE

        /* Associate the created object with the intial core serialization
         * context. */
        val sc = tc.gc.scs["__6MODEL_CORE__"]!!
        sc.addObject(typeObj)
        typeObj.sc = sc
        sc.addSTable(typeObj.st)
        typeObj.st.sc = sc

        /* Stash the created type object. */
        tc.gc.KnowHOWAttribute = typeObj
    }

    private fun bootType(tc: ThreadContext, typeName: String, reprName: String): SixModelObject {
        val knowhowHow = tc.gc.KnowHOW!!.st.HOW!!
        val metaObj = knowhowHow.st.REPR.allocate(tc, knowhowHow.st) as KnowHOWREPRInstance
        metaObj.name = typeName
        val repr = REPRRegistry.getByName(reprName)
        val typeObj = repr.type_object_for(tc, metaObj)
        typeObj.st.MethodCache = metaObj.methods
        typeObj.st.ModeFlags = STable.METHOD_CACHE_AUTHORITATIVE
        val sc = tc.gc.scs["__6MODEL_CORE__"]!!
        sc.addObject(typeObj)
        typeObj.sc = sc
        sc.addObject(typeObj.st.HOW)
        typeObj.st.HOW!!.sc = sc
        sc.addSTable(typeObj.st)
        typeObj.st.sc = sc
        return typeObj
    }

    private fun bootTypedArray(tc: ThreadContext, name: String, type: SixModelObject): SixModelObject {
        val booted = bootType(tc, name, "VMArray")
        val BOOTHash = tc.gc.BOOTHash!!
        val reprInfo = BOOTHash.st.REPR.allocate(tc, BOOTHash.st)
        val reprArrayInfo = BOOTHash.st.REPR.allocate(tc, BOOTHash.st)
        reprArrayInfo.bind_key_boxed(tc, "type", type)
        reprInfo.bind_key_boxed(tc, "array", reprArrayInfo)
        booted.st.REPR.compose(tc, booted.st, reprInfo)
        return booted
    }
}
