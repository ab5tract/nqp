package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

/**
 * A P6opaque-REPR object: inline reference and long fields on one of six
 * concrete classes, overflow arrays past them, and the layout that says
 * which slot lives where. No delegate: a mixin grows the object in place.
 * Never overrides hashCode/equals (nqp::objectid is Object.hashCode).
 */
abstract class RakuObject : SixModelObject() {
    /** Read raw by the fast sites; null only on a deserialization stub
     *  before its finish. */
    @JvmField var layout: RakuObjectLayout? = null
    @JvmField var oExt: Array<Any?>? = null
    @JvmField var lExt: LongArray? = null

    abstract fun refAt(i: Int): Any?
    abstract fun refSet(i: Int, v: Any?)
    abstract fun longAt(i: Int): Long
    abstract fun longSet(i: Int, v: Long)

    private fun lay(tc: ThreadContext): RakuObjectLayout =
        layout ?: throw ExceptionHandling.dieInternal(tc, "RakuObject of " + st.debugName + " has no layout (a deserialization stub before its finish?)")

    /** The slot for an access: the hint when the caller trusts it, else by name. */
    private fun slot(tc: ThreadContext, l: RakuObjectLayout, classHandle: SixModelObject?, name: String?, hint: Long): Int =
        if (hint >= 0L && hint < l.kinds.size) hint.toInt() else l.resolve(classHandle, name)

    fun autoViv(l: RakuObjectLayout, slot: Int, tc: ThreadContext): SixModelObject? {
        val av = l.autoViv[slot]
        if (av == null || Ops.isnull(av) == 1L) return null
        val v: SixModelObject = if (av is TypeObject) av else av.clone(tc)
        l.setRef(this, slot, v)
        return v
    }

    /* ----- the attribute API ----- */

    override fun get_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long): SixModelObject? {
        val l = lay(tc)
        val s = slot(tc, l, classHandle, name, hint)
        if (l.kinds[s] != SlotKind.REF)
            throw ExceptionHandling.dieInternal(tc, "Cannot access a native attribute as a reference attribute")
        return (l.getRef(this, s) as SixModelObject?) ?: autoViv(l, s, tc)
    }

    override fun get_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        val l = lay(tc)
        val s = slot(tc, l, classHandle, name, hint)
        when (l.kinds[s]) {
            SlotKind.INT -> { tc.nativeType = ThreadContext.NATIVE_INT; tc.nativeI = l.getLong(this, s) }
            SlotKind.NUM -> { tc.nativeType = ThreadContext.NATIVE_NUM; tc.nativeN = java.lang.Double.longBitsToDouble(l.getLong(this, s)) }
            SlotKind.STR -> { tc.nativeType = ThreadContext.NATIVE_STR; tc.nativeS = l.getRef(this, s) as String? }
            SlotKind.BIGINT, SlotKind.NCBODY -> { tc.nativeType = ThreadContext.NATIVE_JVM_OBJ; tc.nativeJ = l.getRef(this, s) }
            SlotKind.REF -> throw ExceptionHandling.dieInternal(tc, "Cannot access a reference attribute as a native attribute")
        }
    }

    override fun bind_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long, value: SixModelObject?) {
        val l = lay(tc)
        val s = slot(tc, l, classHandle, name, hint)
        if (l.kinds[s] != SlotKind.REF)
            throw ExceptionHandling.dieInternal(tc, "Cannot access a native attribute as a reference attribute")
        l.setRef(this, s, value)
    }

    override fun bind_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        val l = lay(tc)
        val s = slot(tc, l, classHandle, name, hint)
        when (l.kinds[s]) {
            SlotKind.INT -> { tc.nativeType = ThreadContext.NATIVE_INT; l.setLong(this, s, P6int.sizedValue(l.specs[s], tc.nativeI)) }
            SlotKind.NUM -> { tc.nativeType = ThreadContext.NATIVE_NUM; l.setLong(this, s, java.lang.Double.doubleToRawLongBits(P6num.sizedValue(l.specs[s], tc.nativeN))) }
            SlotKind.STR -> { tc.nativeType = ThreadContext.NATIVE_STR; l.setRef(this, s, tc.nativeS) }
            SlotKind.BIGINT -> { tc.nativeType = ThreadContext.NATIVE_JVM_OBJ; l.setRef(this, s, tc.nativeJ as java.math.BigInteger?) }
            SlotKind.NCBODY -> { tc.nativeType = ThreadContext.NATIVE_JVM_OBJ; l.setRef(this, s, tc.nativeJ as NativeCallBody?) }
            SlotKind.REF -> throw ExceptionHandling.dieInternal(tc, "Cannot access a reference attribute as a native attribute")
        }
    }

    override fun is_attribute_initialized(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long): Long {
        val l = lay(tc)
        val s = slot(tc, l, classHandle, name, hint)
        /* Reads the slot without auto-vivifying it, as the generated method
         * did: an unset reference attribute answers 0, and stays unset. */
        return if (l.kinds[s] == SlotKind.REF) (if (l.getRef(this, s) != null) 1L else 0L) else 1L
    }

    /* ----- atomics ----- */

    override fun cas_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?,
                                     expected: SixModelObject?, value: SixModelObject?): SixModelObject? {
        val l = lay(tc)
        val s = l.resolve(classHandle, name)
        return if (l.compareAndSet(this, s, expected, value)) expected else l.getVolatile(this, s) as SixModelObject?
    }

    override fun atomic_bind_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?, name: String?, value: SixModelObject?) {
        val l = lay(tc)
        l.setVolatile(this, l.resolve(classHandle, name), value)
    }

    /* ----- boxing through the unbox slots (what generateBoxingMethods emitted) ----- */

    private fun unboxSlot(tc: ThreadContext, slot: Int, what: String): Int {
        if (slot < 0) throw ExceptionHandling.dieInternal(tc, "This type (" + st.debugName + ") cannot box a native " + what)
        return slot
    }

    override fun set_int(tc: ThreadContext, value: Long) {
        val l = lay(tc); val s = unboxSlot(tc, l.unboxIntSlot, "integer")
        if (l.kinds[s] == SlotKind.BIGINT) l.setRef(this, s, java.math.BigInteger.valueOf(value))
        else l.setLong(this, s, P6int.sizedValue(l.specs[s], value))
    }
    override fun set_uint(tc: ThreadContext, value: Long) {
        val l = lay(tc); val s = unboxSlot(tc, l.unboxIntSlot, "integer")
        if (l.kinds[s] == SlotKind.BIGINT) l.setRef(this, s, P6bigint.unsignedValueOf(value))
        else l.setLong(this, s, P6int.sizedValue(l.specs[s], value))
    }
    override fun get_int(tc: ThreadContext): Long {
        val l = lay(tc); val s = unboxSlot(tc, l.unboxIntSlot, "integer")
        return if (l.kinds[s] == SlotKind.BIGINT) P6bigint.checkedLongValue(l.getRef(this, s) as java.math.BigInteger)
               else l.getLong(this, s)
    }
    override fun get_uint(tc: ThreadContext): Long {
        val l = lay(tc); val s = unboxSlot(tc, l.unboxIntSlot, "integer")
        return if (l.kinds[s] == SlotKind.BIGINT) P6bigint.uncheckedLongValue(l.getRef(this, s) as java.math.BigInteger)
               else l.getLong(this, s)
    }
    override fun set_num(tc: ThreadContext, value: Double) {
        val l = lay(tc); l.setLong(this, unboxSlot(tc, l.unboxNumSlot, "number"), java.lang.Double.doubleToRawLongBits(value))
    }
    override fun get_num(tc: ThreadContext): Double {
        val l = lay(tc); return java.lang.Double.longBitsToDouble(l.getLong(this, unboxSlot(tc, l.unboxNumSlot, "number")))
    }
    override fun set_str(tc: ThreadContext, value: String?) {
        val l = lay(tc); l.setRef(this, unboxSlot(tc, l.unboxStrSlot, "string"), value)
    }
    override fun get_str(tc: ThreadContext): String? {
        val l = lay(tc); return l.getRef(this, unboxSlot(tc, l.unboxStrSlot, "string")) as String?
    }
    /* The REPR id goes unchecked, as the generated boxing methods left it:
     * the cast below is what catches a mismatch. */
    override fun set_boxing_of(tc: ThreadContext, reprId: Long, value: Any?) {
        val l = lay(tc)
        if (l.unboxObjSlot < 0 || l.kinds[l.unboxObjSlot] != SlotKind.NCBODY) super.set_boxing_of(tc, reprId, value)
        else l.setRef(this, l.unboxObjSlot, value as NativeCallBody?)
    }
    override fun get_boxing_of(tc: ThreadContext, reprId: Long): Any? {
        val l = lay(tc)
        if (l.unboxObjSlot < 0 || l.kinds[l.unboxObjSlot] != SlotKind.NCBODY) return super.get_boxing_of(tc, reprId)
        return l.getRef(this, l.unboxObjSlot)
    }

    /* ----- clone ----- */

    override fun clone(tc: ThreadContext): SixModelObject {
        val c = super.clone() as RakuObject
        c.sc = null
        c.oExt = oExt?.clone()
        c.lExt = lExt?.clone()
        return c
    }

    /* ----- positional / associative delegation ----- */

    fun posDelegate(): SixModelObject {
        val l = layout
        if (l != null && l.posDelSlot >= 0) return l.getRef(this, l.posDelSlot) as SixModelObject
        throw RuntimeException("This type does not support positional operations")
    }
    fun assDelegate(): SixModelObject {
        val l = layout
        if (l != null && l.assDelSlot >= 0) return l.getRef(this, l.assDelSlot) as SixModelObject
        throw RuntimeException("This type does not support associative operations")
    }
    override fun elems(tc: ThreadContext): Long = posDelegate().elems(tc)
    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? = posDelegate().at_pos_boxed(tc, index)
    override fun at_pos_native(tc: ThreadContext, index: Long) = posDelegate().at_pos_native(tc, index)
    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) = posDelegate().bind_pos_boxed(tc, index, value)
    override fun bind_pos_native(tc: ThreadContext, index: Long) = posDelegate().bind_pos_native(tc, index)
    override fun set_elems(tc: ThreadContext, count: Long) = posDelegate().set_elems(tc, count)
    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) = posDelegate().push_boxed(tc, value)
    override fun push_native(tc: ThreadContext) = posDelegate().push_native(tc)
    override fun pop_boxed(tc: ThreadContext): SixModelObject? = posDelegate().pop_boxed(tc)
    override fun pop_native(tc: ThreadContext) = posDelegate().pop_native(tc)
    override fun unshift_boxed(tc: ThreadContext, value: SixModelObject?) = posDelegate().unshift_boxed(tc, value)
    override fun unshift_native(tc: ThreadContext) = posDelegate().unshift_native(tc)
    override fun shift_boxed(tc: ThreadContext): SixModelObject? = posDelegate().shift_boxed(tc)
    override fun shift_native(tc: ThreadContext) = posDelegate().shift_native(tc)
    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject? = posDelegate().slice(tc, dest, beginning, end)
    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) = posDelegate().splice(tc, from, offset, count)
    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject? = assDelegate().at_key_boxed(tc, key)
    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) = assDelegate().bind_key_boxed(tc, key, value)
    override fun exists_key(tc: ThreadContext, key: String?): Long = assDelegate().exists_key(tc, key)
    override fun delete_key(tc: ThreadContext, key: String?) = assDelegate().delete_key(tc, key)
}

/* The six storage classes. Field declarations only; the when-switches are
 * the slow road (the sites read the fields through constant handles). */

class RakuObject4 : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    override fun refAt(i: Int): Any? = when (i) { 0 -> o0; 1 -> o1; 2 -> o2; else -> o3 }
    override fun refSet(i: Int, v: Any?) { when (i) { 0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; else -> o3 = v } }
    override fun longAt(i: Int): Long = throw IllegalStateException("RakuObject4 has no long slots")
    override fun longSet(i: Int, v: Long) = throw IllegalStateException("RakuObject4 has no long slots")
}

class RakuObject4L : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    @JvmField var l0: Long = 0L; @JvmField var l1: Long = 0L
    override fun refAt(i: Int): Any? = when (i) { 0 -> o0; 1 -> o1; 2 -> o2; else -> o3 }
    override fun refSet(i: Int, v: Any?) { when (i) { 0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; else -> o3 = v } }
    override fun longAt(i: Int): Long = if (i == 0) l0 else l1
    override fun longSet(i: Int, v: Long) { if (i == 0) l0 = v else l1 = v }
}

class RakuObject8 : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    @JvmField var o4: Any? = null; @JvmField var o5: Any? = null; @JvmField var o6: Any? = null; @JvmField var o7: Any? = null
    override fun refAt(i: Int): Any? = when (i) { 0 -> o0; 1 -> o1; 2 -> o2; 3 -> o3; 4 -> o4; 5 -> o5; 6 -> o6; else -> o7 }
    override fun refSet(i: Int, v: Any?) { when (i) { 0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; 3 -> o3 = v; 4 -> o4 = v; 5 -> o5 = v; 6 -> o6 = v; else -> o7 = v } }
    override fun longAt(i: Int): Long = throw IllegalStateException("RakuObject8 has no long slots")
    override fun longSet(i: Int, v: Long) = throw IllegalStateException("RakuObject8 has no long slots")
}

class RakuObject8L : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    @JvmField var o4: Any? = null; @JvmField var o5: Any? = null; @JvmField var o6: Any? = null; @JvmField var o7: Any? = null
    @JvmField var l0: Long = 0L; @JvmField var l1: Long = 0L
    override fun refAt(i: Int): Any? = when (i) { 0 -> o0; 1 -> o1; 2 -> o2; 3 -> o3; 4 -> o4; 5 -> o5; 6 -> o6; else -> o7 }
    override fun refSet(i: Int, v: Any?) { when (i) { 0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; 3 -> o3 = v; 4 -> o4 = v; 5 -> o5 = v; 6 -> o6 = v; else -> o7 = v } }
    override fun longAt(i: Int): Long = if (i == 0) l0 else l1
    override fun longSet(i: Int, v: Long) { if (i == 0) l0 = v else l1 = v }
}

class RakuObject16 : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    @JvmField var o4: Any? = null; @JvmField var o5: Any? = null; @JvmField var o6: Any? = null; @JvmField var o7: Any? = null
    @JvmField var o8: Any? = null; @JvmField var o9: Any? = null; @JvmField var o10: Any? = null; @JvmField var o11: Any? = null
    @JvmField var o12: Any? = null; @JvmField var o13: Any? = null; @JvmField var o14: Any? = null; @JvmField var o15: Any? = null
    override fun refAt(i: Int): Any? = when (i) {
        0 -> o0; 1 -> o1; 2 -> o2; 3 -> o3; 4 -> o4; 5 -> o5; 6 -> o6; 7 -> o7
        8 -> o8; 9 -> o9; 10 -> o10; 11 -> o11; 12 -> o12; 13 -> o13; 14 -> o14; else -> o15 }
    override fun refSet(i: Int, v: Any?) { when (i) {
        0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; 3 -> o3 = v; 4 -> o4 = v; 5 -> o5 = v; 6 -> o6 = v; 7 -> o7 = v
        8 -> o8 = v; 9 -> o9 = v; 10 -> o10 = v; 11 -> o11 = v; 12 -> o12 = v; 13 -> o13 = v; 14 -> o14 = v; else -> o15 = v } }
    override fun longAt(i: Int): Long = throw IllegalStateException("RakuObject16 has no long slots")
    override fun longSet(i: Int, v: Long) = throw IllegalStateException("RakuObject16 has no long slots")
}

class RakuObject16L : RakuObject() {
    @JvmField var o0: Any? = null; @JvmField var o1: Any? = null; @JvmField var o2: Any? = null; @JvmField var o3: Any? = null
    @JvmField var o4: Any? = null; @JvmField var o5: Any? = null; @JvmField var o6: Any? = null; @JvmField var o7: Any? = null
    @JvmField var o8: Any? = null; @JvmField var o9: Any? = null; @JvmField var o10: Any? = null; @JvmField var o11: Any? = null
    @JvmField var o12: Any? = null; @JvmField var o13: Any? = null; @JvmField var o14: Any? = null; @JvmField var o15: Any? = null
    @JvmField var l0: Long = 0L; @JvmField var l1: Long = 0L
    override fun refAt(i: Int): Any? = when (i) {
        0 -> o0; 1 -> o1; 2 -> o2; 3 -> o3; 4 -> o4; 5 -> o5; 6 -> o6; 7 -> o7
        8 -> o8; 9 -> o9; 10 -> o10; 11 -> o11; 12 -> o12; 13 -> o13; 14 -> o14; else -> o15 }
    override fun refSet(i: Int, v: Any?) { when (i) {
        0 -> o0 = v; 1 -> o1 = v; 2 -> o2 = v; 3 -> o3 = v; 4 -> o4 = v; 5 -> o5 = v; 6 -> o6 = v; 7 -> o7 = v
        8 -> o8 = v; 9 -> o9 = v; 10 -> o10 = v; 11 -> o11 = v; 12 -> o12 = v; 13 -> o13 = v; 14 -> o14 = v; else -> o15 = v } }
    override fun longAt(i: Int): Long = if (i == 0) l0 else l1
    override fun longSet(i: Int, v: Long) { if (i == 0) l0 = v else l1 = v }
}
