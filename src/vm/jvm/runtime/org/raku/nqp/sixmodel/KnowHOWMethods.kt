package org.raku.nqp.sixmodel

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.CompilationUnit
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.reprs.KnowHOWAttributeInstance
import org.raku.nqp.sixmodel.reprs.KnowHOWREPR
import org.raku.nqp.sixmodel.reprs.KnowHOWREPRInstance

/**
 * This class contains methods that belong on the KnowHOW meta-object. It
 * pretends to be a compilation unit, so as to fit with the expected API
 * for code reference like things.
 */
class KnowHOWMethods : CompilationUnit() {
    fun new_type(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        /* Get arguments. */
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            val reprArg = Ops.namedparam_opt_s(cf, csd, args, "repr")
            val nameArg = Ops.namedparam_opt_s(cf, csd, args, "name")
            if (Ops.isnull(self) == 1L || self!!.st.REPR !is KnowHOWREPR)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object with REPR KnowHOWREPR")

            /* We first create a new HOW instance. */
            val HOW = self!!.st.REPR.allocate(tc, self.st)

            /* See if we have a representation name; if not default to P6opaque. */
            val reprName = reprArg ?: "P6opaque"

            /* Create a new type object of the desired REPR. (Note that we can't
             * default to KnowHOWREPR here, since it doesn't know how to actually
             * store attributes, it's just for bootstrapping knowhow's. */
            val reprToUse = REPRRegistry.getByName(reprName)
            val typeObject = reprToUse.type_object_for(tc, HOW)

            /* See if we were given a name; put it into the meta-object if so. */
            if (nameArg != null)
                (HOW as KnowHOWREPRInstance).name = nameArg

            /* Set .WHO to an empty hash. */
            val hash = tc.gc.BOOTHash!!
            typeObject.st.WHO = hash.st.REPR.allocate(tc, hash.st)

            /* Return the type object. */
            Ops.return_o(typeObject, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun add_method(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 4, 4)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            val name = Ops.posparam_s(cf, csd, args, 2)
            val method = Ops.posparam_o(cf, csd, args, 3)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")
            self.methods!![name!!] = method
            Ops.return_o(method, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun add_attribute(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 3, 3)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            val attribute = Ops.posparam_o(cf, csd, args, 2)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")
            if (Ops.isnull(attribute) == 1L || attribute !is KnowHOWAttributeInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW attributes must use KnowHOWAttributeREPR")
            self.attributes!!.add(attribute)
            Ops.return_o(attribute, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun compose(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 2, 2)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            val typeObj = Ops.posparam_o(cf, csd, args, 1)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")

            /* Set method cache. */
            typeObj!!.st.MethodCache = self.methods
            typeObj!!.st.ModeFlags = STable.METHOD_CACHE_AUTHORITATIVE

            /* Set type check cache. */
            typeObj!!.st.TypeCheckCache = arrayOf(typeObj)

            /* Use any attribute information to produce attribute protocol
             * data. The protocol consists of an array... */
            val reprInfo = tc.gc.BOOTArray!!.st.REPR.allocate(tc, tc.gc.BOOTArray!!.st)

            /* ...which contains an array per MRO entry... */
            val typeInfo = tc.gc.BOOTArray!!.st.REPR.allocate(tc, tc.gc.BOOTArray!!.st)
            reprInfo.push_boxed(tc, typeInfo)

            /* ...which in turn contains this type... */
            typeInfo.push_boxed(tc, typeObj)

            /* ...then an array of hashes per attribute... */
            val attrInfoList = tc.gc.BOOTArray!!.st.REPR.allocate(tc, tc.gc.BOOTArray!!.st)
            typeInfo.push_boxed(tc, attrInfoList)
            val attributes = self.attributes!!
            for (i in attributes.indices) {
                val attribute = attributes[i] as KnowHOWAttributeInstance
                val attrInfo = tc.gc.BOOTHash!!.st.REPR.allocate(tc, tc.gc.BOOTHash!!.st)
                val nameObj = tc.gc.BOOTStr!!.st.REPR.allocate(tc, tc.gc.BOOTStr!!.st)
                nameObj.set_str(tc, attribute.name)
                attrInfo.bind_key_boxed(tc, "name", nameObj)
                attrInfo.bind_key_boxed(tc, "type", attribute.type)
                if (attribute.box_target != 0) {
                    /* Merely having the key serves as a "yes". */
                    attrInfo.bind_key_boxed(tc, "box_target", attrInfo)
                }
                attrInfoList.push_boxed(tc, attrInfo)
            }

            /* ...followed by a list of parents (none). */
            val parentInfo = tc.gc.BOOTArray!!.st.REPR.allocate(tc, tc.gc.BOOTArray!!.st)
            typeInfo.push_boxed(tc, parentInfo)

            /* All of this goes in a hash. */
            val reprInfoHash = tc.gc.BOOTHash!!.st.REPR.allocate(tc, tc.gc.BOOTHash!!.st)
            reprInfoHash.bind_key_boxed(tc, "attribute", reprInfo)

            /* Compose the representation using it. */
            typeObj!!.st.REPR.compose(tc, typeObj.st, reprInfoHash)

            Ops.return_o(typeObj, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attributes(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 2, 2)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")
            val bootArray = tc.gc.BOOTArray!!
            val result = bootArray.st.REPR.allocate(tc, bootArray.st)
            for (attr in self.attributes!!)
                result.push_boxed(tc, attr)
            Ops.return_o(result, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun methods(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 2, 2)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")
            val bootHash = tc.gc.BOOTHash!!
            val result = bootHash.st.REPR.allocate(tc, bootHash.st)
            val methods = self.methods!!
            for (name in methods.keys)
                result.bind_key_boxed(tc, name, methods[name])
            Ops.return_o(result, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun name(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 2, 2)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            if (Ops.isnull(self) == 1L || self !is KnowHOWREPRInstance)
                throw ExceptionHandling.dieInternal(tc, "KnowHOW methods must be called on object instance with REPR KnowHOWREPR")
            Ops.return_s(self.name, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attr_new(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            /* Process arguments. */
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            val nameArg = Ops.namedparam_s(cf, csd, args, "name")
            val typeArg = Ops.namedparam_opt_o(cf, csd, args, "type")
            val btArg = Ops.namedparam_opt_i(cf, csd, args, "box_target")

            /* Allocate attribute object. */
            val repr = REPRRegistry.getByName("KnowHOWAttribute")
            val obj = repr.allocate(tc, self!!.st) as KnowHOWAttributeInstance

            /* Populate it. */
            obj.name = nameArg
            obj.type = if (Ops.isnull(typeArg) == 0L) typeArg else tc.gc.KnowHOW
            obj.box_target = if (btArg == 0L) 0 else 1

            /* Return produced object. */
            Ops.return_o(obj, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attr_compose(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            Ops.return_o(self, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attr_name(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            Ops.return_s((self as KnowHOWAttributeInstance).name, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attr_type(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            Ops.return_o((self as KnowHOWAttributeInstance).type, cf)
        }
        finally {
            cf.leave()
        }
    }

    fun attr_box_target(tc: ThreadContext, cr: CodeRef, csd0: CallSiteDescriptor, args0: Array<Any?>) {
        val cf = CallFrame(tc, cr)
        try {
            val csd = Ops.checkarity(cf, csd0, args0, 1, 1)
            val args = tc.flatArgs!!
            val self = Ops.posparam_o(cf, csd, args, 0)
            Ops.return_i((self as KnowHOWAttributeInstance).box_target.toLong(), cf)
        }
        finally {
            cf.leave()
        }
    }

    override fun getCodeRefs(): Array<CodeRef> {
        val refs = arrayOfNulls<CodeRef>(12)  // every slot is filled below
        val snull: Array<String>? = null
        val hnull = arrayOf<LongArray>()
        val mt = MethodType.methodType(Void.TYPE, ThreadContext::class.java,
            CodeRef::class.java, CallSiteDescriptor::class.java, Array<Any?>::class.java)
        val l = MethodHandles.lookup()
        try {
            refs[0] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "new_type", mt).bindTo(this),
                "new_type", "new_type", snull, snull, snull, snull, hnull, 0.toShort())
            refs[1] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "add_method", mt).bindTo(this),
                "add_method", "add_method", snull, snull, snull, snull, hnull, 0.toShort())
            refs[2] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "add_attribute", mt).bindTo(this),
                "add_attribute", "add_attribute", snull, snull, snull, snull, hnull, 0.toShort())
            refs[3] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "compose", mt).bindTo(this),
                "compose", "compose", snull, snull, snull, snull, hnull, 0.toShort())
            refs[4] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attributes", mt).bindTo(this),
                "attributes", "attributes", snull, snull, snull, snull, hnull, 0.toShort())
            refs[5] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "methods", mt).bindTo(this),
                "methods", "methods", snull, snull, snull, snull, hnull, 0.toShort())
            refs[6] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "name", mt).bindTo(this),
                "name", "name", snull, snull, snull, snull, hnull, 0.toShort())
            refs[7] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attr_new", mt).bindTo(this),
                "new", "attr_new", snull, snull, snull, snull, hnull, 0.toShort())
            refs[8] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attr_compose", mt).bindTo(this),
                "compose", "attr_compose", snull, snull, snull, snull, hnull, 0.toShort())
            refs[9] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attr_name", mt).bindTo(this),
                "name", "attr_name", snull, snull, snull, snull, hnull, 0.toShort())
            refs[10] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attr_type", mt).bindTo(this),
                "type", "attr_type", snull, snull, snull, snull, hnull, 0.toShort())
            refs[11] = CodeRef(this, l.findVirtual(KnowHOWMethods::class.java, "attr_box_target", mt).bindTo(this),
                "box_target", "attr_box_target", snull, snull, snull, snull, hnull, 0.toShort())
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
        @Suppress("UNCHECKED_CAST")
        return refs as Array<CodeRef>
    }

    /* Vestigial: CompilationUnit declares no such method (same in the Java). */
    fun getOuterMap(): IntArray = IntArray(0)

    override fun getCallSites(): Array<CallSiteDescriptor> = emptyArray()

    override fun hllName(): String = ""
}
