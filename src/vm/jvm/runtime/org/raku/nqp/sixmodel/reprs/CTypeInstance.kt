package org.raku.nqp.sixmodel.reprs

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

import java.util.HashMap

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.NativeCallOps
import org.raku.nqp.runtime.NativeSupport
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

import org.raku.nqp.sixmodel.SixModelObject

import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

/**
 * An instance of a CStruct, CPPStruct or CUnion: a block of off-heap memory
 * whose members are read and written at the offsets the REPR worked out when
 * the type was composed.
 */
abstract class CTypeInstance(private val kind: String) : SixModelObject(), Refreshable {
    @JvmField var storage: MemorySegment? = null
    /* XXX: Using a hash to store members is probably not an optimal solution.
     * Dynamically generating subclasses that have the appropriate members and
     * such is probably better, but that's harder to implement. */
    @JvmField var memberCache = HashMap<String, SixModelObject?>()
    /* Buffers we allocated on a member's behalf -- C strings, mostly. The
     * struct holds nothing but their address, so something has to keep them
     * from being collected while it does. */
    private val pinned = HashMap<String, MemorySegment>()

    override fun bind_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long, value: SixModelObject?) {
        val data = classHandle!!.st.REPRData as CTypeREPRData
        val info = data.fieldTypes[name]!!
        /* XXX: This'll break if we try to set a callback member. OTOH, it's
         * broken on Parrot too, so it's not a NativeCall regression as
         * such... */
        val o = NativeCallOps.toNativeType(tc, value, info.argType, null)
        val seg = storage!!

        if (isAggregate(info.argType)) {
            if (info.inlined.toInt() == 0)
                seg.set(ValueLayout.ADDRESS, info.offset, NativeSupport.orNull(o as MemorySegment?))
            else if (o != null)
                MemorySegment.copy(o as MemorySegment, 0L, seg, info.offset, info.size)
        }
        else {
            writeMember(tc, seg, info, name!!, o)
        }

        memberCache.put(name!!, value)
    }

    override fun bind_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        val data = classHandle!!.st.REPRData as CTypeREPRData
        val info = data.fieldTypes[name]!!
        val seg = storage!!
        when (info.argType) {
            ArgType.CHAR, ArgType.UCHAR -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                seg.set(ValueLayout.JAVA_BYTE, info.offset, tc.nativeI.toByte())
            }
            ArgType.SHORT, ArgType.USHORT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                seg.set(ValueLayout.JAVA_SHORT, info.offset, tc.nativeI.toShort())
            }
            ArgType.INT, ArgType.UINT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                seg.set(ValueLayout.JAVA_INT, info.offset, tc.nativeI.toInt())
            }
            ArgType.LONG, ArgType.ULONG -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                seg.set(ValueLayout.JAVA_LONG, info.offset, tc.nativeI)
            }
            ArgType.FLOAT -> {
                tc.nativeType = ThreadContext.NATIVE_NUM
                seg.set(ValueLayout.JAVA_FLOAT, info.offset, tc.nativeN.toFloat())
            }
            ArgType.DOUBLE -> {
                tc.nativeType = ThreadContext.NATIVE_NUM
                seg.set(ValueLayout.JAVA_DOUBLE, info.offset, tc.nativeN)
            }
            else ->
                ExceptionHandling.dieInternal(tc, String.format("%s.bind_attribute_native: Can't handle %s", kind, info.argType))
        }
    }

    override fun get_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long): SixModelObject? {
        var member = memberCache[name!!]
        if (Ops.isnull(member) == 0L) return member

        val data = classHandle!!.st.REPRData as CTypeREPRData
        val info = data.fieldTypes[name]!!

        member = NativeCallOps.toNQPType(tc, info.argType, info.type, readMember(tc, storage!!, info))
        memberCache.put(name, member)
        return member
    }

    override fun get_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        val data = classHandle!!.st.REPRData as CTypeREPRData
        val info = data.fieldTypes[name]!!
        val seg = storage!!

        when (info.argType) {
            ArgType.CHAR -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = seg.get(ValueLayout.JAVA_BYTE, info.offset).toLong()
            }
            ArgType.SHORT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = seg.get(ValueLayout.JAVA_SHORT, info.offset).toLong()
            }
            ArgType.INT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = seg.get(ValueLayout.JAVA_INT, info.offset).toLong()
            }
            ArgType.LONG -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = seg.get(ValueLayout.JAVA_LONG, info.offset)
            }
            ArgType.UCHAR -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                var value = seg.get(ValueLayout.JAVA_BYTE, info.offset).toLong()
                if (value < 0)
                    value += 0x100
                tc.nativeI = value
            }
            ArgType.USHORT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                var value = seg.get(ValueLayout.JAVA_SHORT, info.offset).toLong()
                if (value < 0)
                    value += 0x10000
                tc.nativeI = value
            }
            ArgType.UINT -> {
                tc.nativeType = ThreadContext.NATIVE_INT
                var value = seg.get(ValueLayout.JAVA_INT, info.offset).toLong()
                if (value < 0)
                    value += 0x100000000L
                tc.nativeI = value
            }
            ArgType.ULONG -> {
                /* TODO: handle unsignedness properly. */
                tc.nativeType = ThreadContext.NATIVE_INT
                tc.nativeI = seg.get(ValueLayout.JAVA_LONG, info.offset)
            }
            ArgType.FLOAT -> {
                tc.nativeType = ThreadContext.NATIVE_NUM
                tc.nativeN = seg.get(ValueLayout.JAVA_FLOAT, info.offset).toDouble()
            }
            ArgType.DOUBLE -> {
                tc.nativeType = ThreadContext.NATIVE_NUM
                tc.nativeN = seg.get(ValueLayout.JAVA_DOUBLE, info.offset)
            }
            else ->
                ExceptionHandling.dieInternal(tc, String.format("%s.get_attribute_native: Can't handle %s", kind, info.argType))
        }
    }

    override fun refresh(tc: ThreadContext) {
        val reprData = st.REPRData as CTypeREPRData

        // Recursively refresh our members.
        for ((key, child) in memberCache) {
            val argType = reprData.fieldTypes[key]!!.argType
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

    private fun isAggregate(argType: ArgType?): Boolean =
        argType == ArgType.CSTRUCT || argType == ArgType.CPPSTRUCT || argType == ArgType.CUNION

    private fun writeMember(tc: ThreadContext, seg: MemorySegment, info: CAttrInfo, name: String, o: Any?) {
        when (info.argType) {
            ArgType.CHAR, ArgType.UCHAR ->
                seg.set(ValueLayout.JAVA_BYTE, info.offset, (o as Number).toByte())
            ArgType.SHORT, ArgType.USHORT ->
                seg.set(ValueLayout.JAVA_SHORT, info.offset, (o as Number).toShort())
            ArgType.INT, ArgType.UINT ->
                seg.set(ValueLayout.JAVA_INT, info.offset, (o as Number).toInt())
            ArgType.LONG, ArgType.ULONG ->
                seg.set(ValueLayout.JAVA_LONG, info.offset, (o as Number).toLong())
            ArgType.FLOAT ->
                seg.set(ValueLayout.JAVA_FLOAT, info.offset, (o as Number).toFloat())
            ArgType.DOUBLE ->
                seg.set(ValueLayout.JAVA_DOUBLE, info.offset, (o as Number).toDouble())
            ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR -> {
                val cstr = o as MemorySegment?
                if (cstr == null) pinned.remove(name) else pinned.put(name, cstr)
                seg.set(ValueLayout.ADDRESS, info.offset, NativeSupport.orNull(cstr))
            }
            ArgType.CARRAY, ArgType.CPOINTER ->
                seg.set(ValueLayout.ADDRESS, info.offset, NativeSupport.orNull(o as MemorySegment?))
            else ->
                ExceptionHandling.dieInternal(tc, String.format("%s.bind_attribute_boxed: Can't handle %s", kind, info.argType))
        }
    }

    private fun readMember(tc: ThreadContext, seg: MemorySegment, info: CAttrInfo): Any? =
        when (info.argType) {
            ArgType.CHAR, ArgType.UCHAR     -> seg.get(ValueLayout.JAVA_BYTE, info.offset)
            ArgType.SHORT, ArgType.USHORT   -> seg.get(ValueLayout.JAVA_SHORT, info.offset)
            ArgType.INT, ArgType.UINT       -> seg.get(ValueLayout.JAVA_INT, info.offset)
            ArgType.LONG, ArgType.ULONG     -> seg.get(ValueLayout.JAVA_LONG, info.offset)
            ArgType.FLOAT                   -> seg.get(ValueLayout.JAVA_FLOAT, info.offset)
            ArgType.DOUBLE                  -> seg.get(ValueLayout.JAVA_DOUBLE, info.offset)
            /* TODO: Handle encodings. */
            ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR ->
                NativeSupport.fromCString(seg.get(ValueLayout.ADDRESS, info.offset))
            ArgType.CARRAY, ArgType.CPOINTER ->
                NativeSupport.unbounded(seg.get(ValueLayout.ADDRESS, info.offset))
            ArgType.CSTRUCT, ArgType.CPPSTRUCT, ArgType.CUNION ->
                if (info.inlined.toInt() == 0)
                    nested(info, seg.get(ValueLayout.ADDRESS, info.offset))
                else
                    seg.asSlice(info.offset, info.size)
            else -> {
                ExceptionHandling.dieInternal(tc, String.format("%s.get_attribute_boxed: Can't handle %s", kind, info.argType))
                null
            }
        }

    /* A nested type held by reference: give the pointer the extent of the
     * type it points at, so the members of the nested value are reachable. */
    private fun nested(info: CAttrInfo, p: MemorySegment): MemorySegment? {
        if (p.address() == 0L) return null
        val data = info.type!!.st.REPRData as? CTypeREPRData
        return if (data != null && data.size > 0) p.reinterpret(data.size) else NativeSupport.unbounded(p)
    }
}
