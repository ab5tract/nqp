package org.raku.nqp.sixmodel.reprs

import java.util.HashMap

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeCallOps
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.SixModelObject

import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

class CPPStructInstance : SixModelObject(), Refreshable {
    @JvmField var storage: Structure? = null
    /* XXX: Using a hash to store members is probably not an optimal solution.
     * Dynamically generating subclasses that have the appropriate members and
     * such is probably better, but that's harder to implement. */
    @JvmField var memberCache = HashMap<String, SixModelObject>()

    override fun bind_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long, value: SixModelObject?) {
        val data = class_handle!!.st.REPRData as CPPStructREPRData
        val info = data.fieldTypes[name]!!
        /* XXX: This'll break if we try to set a callback member. OTOH, it's
         * broken on Parrot too, so it's not a NativeCall regression as
         * such... */
        var type = info.argType
        var o: Any? = NativeCallOps.toJNAType(tc, value, type, null)
        if (info.inlined.toInt() == 0) {
            if (type == ArgType.CSTRUCT || type == ArgType.CPPSTRUCT) {
                type = ArgType.CPOINTER
                o = (o as Structure).getPointer()
            }
            else if (type == ArgType.CUNION) {
                type = ArgType.CPOINTER
                o = (o as Union).getPointer()
            }
        }
        storage!!.writeField(name, o)
        memberCache.put(name!!, value!!)
    }

    override fun bind_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        val data = class_handle!!.st.REPRData as CPPStructREPRData
        val info = data.fieldTypes[name]!!
        val value: Any?
        when (info.argType) {
            ArgType.CHAR, ArgType.UCHAR -> {
                tc.native_type = ThreadContext.NATIVE_INT
                value = tc.native_i.toByte()
            }
            ArgType.SHORT, ArgType.USHORT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                value = tc.native_i.toShort()
            }
            ArgType.INT, ArgType.UINT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                value = tc.native_i.toInt()
            }
            ArgType.LONG, ArgType.ULONG -> {
                tc.native_type = ThreadContext.NATIVE_INT
                value = tc.native_i
            }
            ArgType.FLOAT -> {
                tc.native_type = ThreadContext.NATIVE_NUM
                value = tc.native_n.toFloat()
            }
            ArgType.DOUBLE -> {
                tc.native_type = ThreadContext.NATIVE_NUM
                value = tc.native_n
            }
            else -> {
                ExceptionHandling.dieInternal(tc, String.format("CPPStruct.bind_attribute_native: Can't handle %s", info.argType))
                value = null
            }
        }
        storage!!.writeField(name, value)
    }

    override fun get_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long): SixModelObject? {
        var member = memberCache[name!!]
        if (Ops.isnull(member) == 0L) return member

        val data = class_handle!!.st.REPRData as CPPStructREPRData
        val info = data.fieldTypes[name]!!

        var o: Any? = storage!!.readField(name)
        if (info.inlined.toInt() == 0) {
            if (info.argType == ArgType.CSTRUCT) {
                val structClass = (info.type!!.st.REPRData as CStructREPRData).structureClass!!
                o = Structure.newInstance(structClass.asSubclass(Structure::class.java), o as Pointer)
            }
            else if (info.argType == ArgType.CPPSTRUCT) {
                val structClass = (info.type!!.st.REPRData as CPPStructREPRData).structureClass!!
                o = Structure.newInstance(structClass.asSubclass(Structure::class.java), o as Pointer)
            }
            else if (info.argType == ArgType.CUNION) {
                val structClass = (info.type!!.st.REPRData as CUnionREPRData).structureClass!!
                o = Union.newInstance(structClass.asSubclass(Union::class.java), o as Pointer)
            }
        }

        member = NativeCallOps.toNQPType(tc, info.argType, info.type, o)
        memberCache.put(name, member!!)
        return member
    }

    override fun get_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        val data = class_handle!!.st.REPRData as CPPStructREPRData
        val info = data.fieldTypes[name]!!

        val o: Any? = storage!!.readField(name)
        when (info.argType) {
            ArgType.CHAR -> {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = (o as Byte).toLong()
            }
            ArgType.SHORT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = (o as Short).toLong()
            }
            ArgType.INT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = (o as Int).toLong()
            }
            ArgType.LONG -> {
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = o as Long
            }
            ArgType.UCHAR -> {
                tc.native_type = ThreadContext.NATIVE_INT
                var value = (o as Byte).toLong()
                if (value < 0)
                    value += 0x100
                tc.native_i = value
            }
            ArgType.USHORT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                var value = (o as Short).toLong()
                if (value < 0)
                    value += 0x10000
                tc.native_i = value
            }
            ArgType.UINT -> {
                tc.native_type = ThreadContext.NATIVE_INT
                var value = (o as Int).toLong()
                if (value < 0)
                    value += 0x100000000L
                tc.native_i = value
            }
            ArgType.ULONG -> {
                /* TODO: handle unsignedness properly. */
                tc.native_type = ThreadContext.NATIVE_INT
                tc.native_i = o as Long
            }
            ArgType.FLOAT -> {
                tc.native_type = ThreadContext.NATIVE_NUM
                tc.native_n = (o as Float).toDouble()
            }
            ArgType.DOUBLE -> {
                tc.native_type = ThreadContext.NATIVE_NUM
                tc.native_n = o as Double
            }
            else ->
                ExceptionHandling.dieInternal(tc, String.format("CPPStruct.get_attribute_native: Can't handle %s", info.argType))
        }
    }

    override fun refresh(tc: ThreadContext) {
        val repr_data = st.REPRData as CPPStructREPRData

        // Recursively refresh our members.
        for ((key, child) in memberCache) {
            val argType = repr_data.fieldTypes[key]!!.argType
            if (argType == ArgType.CARRAY
             || argType == ArgType.CSTRUCT
             || argType == ArgType.CPPSTRUCT
             || argType == ArgType.CUNION) {
                NativeCallOps.refresh(child, tc)
            }
        }

        /* This is the take a hammer to it approach. Just dump the entire
         * cache and rebuild everything on next read. Future versions should
         * compare the pointer in C memory and the one in the object in the
         * loop above and only dump the entries where the two are different.
         */
        memberCache.clear()
    }
}
