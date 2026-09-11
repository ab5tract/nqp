package org.raku.nqp.sixmodel.reprs

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.VarHandle
import java.util.ArrayList
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec

/** What one attribute slot holds. INT and NUM live in long slots (a num as
 *  its raw bits); the rest are references. */
enum class SlotKind { REF, INT, NUM, STR, BIGINT, NCBODY }

/**
 * The layout of a RakuObject type: for every abstract slot (the hint, per
 * STable, parents first) its kind and its placement on one of the six
 * storage classes -- an inline field when the slot number within its
 * family is below the class's capacity, an overflow array element after.
 * Immutable; one per STable from its single REPR composition or from its
 * serialized REPR data, plus a variant per storage class an object was
 * reblessed on while too small for the canonical class. A fast site guards
 * on the layout's identity and reads through a constant handle from the
 * static tables below.
 */
class RakuObjectLayout(
    @JvmField val st: STable,
    @JvmField val classId: Int,
    @JvmField val kinds: Array<SlotKind>,
    @JvmField val specs: Array<StorageSpec?>,
    @JvmField val slotSTables: Array<STable?>,
    @JvmField val autoViv: Array<SixModelObject?>,
    @JvmField val classHandles: Array<SixModelObject?>,
    @JvmField val nameToSlot: ArrayList<Object2IntOpenHashMap<String>>,
    @JvmField val mi: Boolean,
    @JvmField val unboxIntSlot: Int,
    @JvmField val unboxNumSlot: Int,
    @JvmField val unboxStrSlot: Int,
    @JvmField val unboxObjSlot: Int,
    @JvmField val posDelSlot: Int,
    @JvmField val assDelSlot: Int,
    @JvmField val variant: Boolean,
) {
    @JvmField val storage: Class<out RakuObject> = CLASSES[classId]
    @JvmField val refCap: Int = REF_CAPS[classId]
    @JvmField val longCap: Int = LONG_CAPS[classId]
    /** Per slot: the index within its family (reference or long). */
    @JvmField val index: IntArray = IntArray(kinds.size)
    @JvmField val refCount: Int
    @JvmField val longCount: Int
    @JvmField val oExtSize: Int
    @JvmField val lExtSize: Int

    init {
        var r = 0; var l = 0
        for (i in kinds.indices) {
            if (isLongKind(i)) { index[i] = l++ } else { index[i] = r++ }
        }
        refCount = r; longCount = l
        oExtSize = maxOf(0, r - refCap)
        lExtSize = maxOf(0, l - longCap)
        if (unboxIntSlot >= 0 && kinds[unboxIntSlot] != SlotKind.INT && kinds[unboxIntSlot] != SlotKind.BIGINT)
            throw IllegalStateException("RakuObject layout of ${st.debugName}: int box target slot $unboxIntSlot is ${kinds[unboxIntSlot]}")
        if (unboxNumSlot >= 0 && kinds[unboxNumSlot] != SlotKind.NUM)
            throw IllegalStateException("RakuObject layout of ${st.debugName}: num box target slot $unboxNumSlot is ${kinds[unboxNumSlot]}")
        if (unboxStrSlot >= 0 && kinds[unboxStrSlot] != SlotKind.STR)
            throw IllegalStateException("RakuObject layout of ${st.debugName}: str box target slot $unboxStrSlot is ${kinds[unboxStrSlot]}")
        if (STATS) { if (variant) VARIANTS.incrementAndGet() else LAYOUTS.incrementAndGet() }
        if (TRACE) System.err.println("layout: " + (if (variant) "variant " else "") + st.debugName
            + " class=" + storage.simpleName + " slots=" + kinds.joinToString(",") + " refs=$r longs=$l")
    }

    fun kindOf(slot: Int): SlotKind = kinds[slot]
    fun isLongKind(slot: Int): Boolean { val k = kinds[slot]; return k == SlotKind.INT || k == SlotKind.NUM }

    /** The slot of an attribute, or -1. */
    fun slotFor(classHandle: SixModelObject?, name: String?): Int {
        for (i in classHandles.indices) {
            if (classHandles[i] === classHandle)
                return nameToSlot[i].getOrDefault(name, -1)
        }
        return -1
    }

    /** The slot by name alone, searching every class handle; -1 if none. */
    fun slotForName(name: String): Int {
        for (m in nameToSlot) { val s = m.getOrDefault(name, -1); if (s != -1) return s }
        return -1
    }

    /** The slot, or the old "No such attribute" error with the known-attribute dump. */
    fun resolve(classHandle: SixModelObject?, name: String?): Int {
        val slot = slotFor(classHandle, name)
        if (slot >= 0) return slot
        val known = StringBuilder()
        for (i in classHandles.indices) {
            known.append(if (i == 0) " [" else "; ")
            known.append(classHandles[i]?.st?.debugName)
            known.append(": ")
            known.append(nameToSlot[i].keys.joinToString(","))
        }
        if (classHandles.isNotEmpty()) known.append("]")
        throw RuntimeException("No such attribute '$name' for this object" +
            " (looked in ${classHandle?.st?.debugName}; has$known)")
    }

    /* ----- slot access by placement (the slow road; the sites use handles) ----- */

    fun getRef(o: RakuObject, slot: Int): Any? {
        val i = index[slot]
        return if (i < refCap) o.refAt(i) else o.oExt!![i - refCap]
    }
    fun setRef(o: RakuObject, slot: Int, v: Any?) {
        val i = index[slot]
        if (i < refCap) o.refSet(i, v) else o.oExt!![i - refCap] = v
    }
    fun getLong(o: RakuObject, slot: Int): Long {
        val i = index[slot]
        return if (i < longCap) o.longAt(i) else o.lExt!![i - longCap]
    }
    fun setLong(o: RakuObject, slot: Int, v: Long) {
        val i = index[slot]
        if (i < longCap) o.longSet(i, v) else o.lExt!![i - longCap] = v
    }

    /* ----- atomics: a VarHandle per placement ----- */

    /* A long slot's index counts within the long family, so the reference
     * tables would answer for a different attribute; refuse instead. */
    fun getVolatile(o: RakuObject, slot: Int): Any? {
        if (isLongKind(slot)) throw IllegalStateException("atomic access to a native slot $slot of ${st.debugName}")
        val i = index[slot]
        return if (i < refCap) REF_VH[classId][i].getVolatile(o) else ARRAY_VH.getVolatile(o.oExt, i - refCap)
    }
    fun setVolatile(o: RakuObject, slot: Int, v: Any?) {
        if (isLongKind(slot)) throw IllegalStateException("atomic access to a native slot $slot of ${st.debugName}")
        val i = index[slot]
        if (i < refCap) REF_VH[classId][i].setVolatile(o, v) else ARRAY_VH.setVolatile(o.oExt, i - refCap, v)
    }
    fun compareAndSet(o: RakuObject, slot: Int, expected: Any?, v: Any?): Boolean {
        if (isLongKind(slot)) throw IllegalStateException("atomic access to a native slot $slot of ${st.debugName}")
        val i = index[slot]
        return if (i < refCap) REF_VH[classId][i].compareAndSet(o, expected, v)
               else ARRAY_VH.compareAndSet(o.oExt, i - refCap, expected, v)
    }

    /* ----- constant handles for the fast sites ----- */

    /** (SixModelObject)Object: the reference slot's field or overflow element. */
    fun refGetter(slot: Int): MethodHandle {
        val i = index[slot]
        return if (i < refCap) REF_GET[classId][i]
               else MethodHandles.filterArguments(MethodHandles.insertArguments(ARRAY_GET, 1, i - refCap), 0, OEXT_GET)
                        .asType(MethodType.methodType(Any::class.java, SixModelObject::class.java))
    }
    /** (SixModelObject,Object)void. */
    fun refSetter(slot: Int): MethodHandle {
        val i = index[slot]
        return if (i < refCap) REF_SET[classId][i]
               else MethodHandles.filterArguments(MethodHandles.insertArguments(ARRAY_SET, 1, i - refCap), 0, OEXT_GET)
                        .asType(MethodType.methodType(Void.TYPE, SixModelObject::class.java, Any::class.java))
    }
    /** (SixModelObject)long. */
    fun longGetter(slot: Int): MethodHandle {
        val i = index[slot]
        return if (i < longCap) LONG_GET[classId][i]
               else MethodHandles.filterArguments(MethodHandles.insertArguments(LARRAY_GET, 1, i - longCap), 0, LEXT_GET)
                        .asType(MethodType.methodType(java.lang.Long.TYPE, SixModelObject::class.java))
    }
    /** (SixModelObject,long)void. */
    fun longSetter(slot: Int): MethodHandle {
        val i = index[slot]
        return if (i < longCap) LONG_SET[classId][i]
               else MethodHandles.filterArguments(MethodHandles.insertArguments(LARRAY_SET, 1, i - longCap), 0, LEXT_GET)
                        .asType(MethodType.methodType(Void.TYPE, SixModelObject::class.java, java.lang.Long.TYPE))
    }

    /* ----- allocation ----- */

    /** A fresh instance of this layout's class, with its layout set and its
     *  overflow arrays sized. A `when` on a constant classId folds under PE
     *  to one allocation. */
    fun newInstance(): RakuObject {
        val o: RakuObject = when (classId) {
            0 -> RakuObject4()
            1 -> RakuObject8()
            2 -> RakuObject16()
            3 -> RakuObject4L()
            4 -> RakuObject8L()
            else -> RakuObject16L()
        }
        o.st = st
        o.layout = this
        if (oExtSize > 0) o.oExt = arrayOfNulls(oExtSize)
        if (lExtSize > 0) o.lExt = LongArray(lExtSize)
        return o
    }

    /** Grow an existing object's overflow arrays to this layout's sizes,
     *  keeping every value in place (rebless). */
    fun growExt(o: RakuObject) {
        if (oExtSize > 0) {
            val old = o.oExt
            if (old == null) o.oExt = arrayOfNulls(oExtSize)
            else if (old.size < oExtSize) o.oExt = old.copyOf(oExtSize)
        }
        if (lExtSize > 0) {
            val old = o.lExt
            if (old == null) o.lExt = LongArray(lExtSize)
            else if (old.size < lExtSize) o.lExt = old.copyOf(lExtSize)
        }
    }

    companion object {
        @JvmField val CLASSES: Array<Class<out RakuObject>> = arrayOf(
            RakuObject4::class.java, RakuObject8::class.java, RakuObject16::class.java,
            RakuObject4L::class.java, RakuObject8L::class.java, RakuObject16L::class.java)
        @JvmField val REF_CAPS = intArrayOf(4, 8, 16, 4, 8, 16)
        @JvmField val LONG_CAPS = intArrayOf(0, 0, 0, 2, 2, 2)

        /** The smallest class holding the counts inline; 16 refs / 2 longs
         *  when nothing does (the rest overflows). */
        @JvmStatic
        fun chooseClass(refs: Int, longs: Int): Int {
            val r = if (refs <= 4) 0 else if (refs <= 8) 1 else 2
            return if (longs == 0) r else r + 3
        }

        @JvmStatic
        fun classIdOf(cls: Class<*>): Int {
            for (i in CLASSES.indices) if (CLASSES[i] == cls) return i
            throw IllegalStateException("not a RakuObject storage class: " + cls.name)
        }

        private val LOOKUP = MethodHandles.lookup()
        private val SMO_GET = MethodType.methodType(Any::class.java, SixModelObject::class.java)
        private val SMO_SET = MethodType.methodType(Void.TYPE, SixModelObject::class.java, Any::class.java)
        private val SMO_LGET = MethodType.methodType(java.lang.Long.TYPE, SixModelObject::class.java)
        private val SMO_LSET = MethodType.methodType(Void.TYPE, SixModelObject::class.java, java.lang.Long.TYPE)

        @JvmField val REF_GET: Array<Array<MethodHandle>> = Array(CLASSES.size) { c ->
            Array(REF_CAPS[c]) { i -> LOOKUP.findGetter(CLASSES[c], "o$i", Any::class.java).asType(SMO_GET) } }
        @JvmField val REF_SET: Array<Array<MethodHandle>> = Array(CLASSES.size) { c ->
            Array(REF_CAPS[c]) { i -> LOOKUP.findSetter(CLASSES[c], "o$i", Any::class.java).asType(SMO_SET) } }
        @JvmField val LONG_GET: Array<Array<MethodHandle>> = Array(CLASSES.size) { c ->
            Array(LONG_CAPS[c]) { i -> LOOKUP.findGetter(CLASSES[c], "l$i", java.lang.Long.TYPE).asType(SMO_LGET) } }
        @JvmField val LONG_SET: Array<Array<MethodHandle>> = Array(CLASSES.size) { c ->
            Array(LONG_CAPS[c]) { i -> LOOKUP.findSetter(CLASSES[c], "l$i", java.lang.Long.TYPE).asType(SMO_LSET) } }
        @JvmField val REF_VH: Array<Array<VarHandle>> = Array(CLASSES.size) { c ->
            Array(REF_CAPS[c]) { i -> LOOKUP.findVarHandle(CLASSES[c], "o$i", Any::class.java) } }
        @JvmField val ARRAY_VH: VarHandle = MethodHandles.arrayElementVarHandle(Array<Any?>::class.java)
        private val OEXT_GET: MethodHandle = LOOKUP.findGetter(RakuObject::class.java, "oExt", Array<Any?>::class.java)
        private val LEXT_GET: MethodHandle = LOOKUP.findGetter(RakuObject::class.java, "lExt", LongArray::class.java)
        private val ARRAY_GET: MethodHandle = MethodHandles.arrayElementGetter(Array<Any?>::class.java)
        private val ARRAY_SET: MethodHandle = MethodHandles.arrayElementSetter(Array<Any?>::class.java)
        private val LARRAY_GET: MethodHandle = MethodHandles.arrayElementGetter(LongArray::class.java)
        private val LARRAY_SET: MethodHandle = MethodHandles.arrayElementSetter(LongArray::class.java)

        /** NQP_LAYOUT_STATS=1: layouts, variants at exit. NQP_LAYOUT_TRACE=1: each creation and rebless. */
        @JvmField val STATS: Boolean = System.getenv("NQP_LAYOUT_STATS") != null
        @JvmField val TRACE: Boolean = System.getenv("NQP_LAYOUT_TRACE") != null
        @JvmField val LAYOUTS = java.util.concurrent.atomic.AtomicLong()
        @JvmField val VARIANTS = java.util.concurrent.atomic.AtomicLong()
        @JvmField val REBLESSES = java.util.concurrent.atomic.AtomicLong()

        init {
            if (STATS) Runtime.getRuntime().addShutdownHook(Thread {
                System.err.println("layout stats: layouts=" + LAYOUTS.get() + " variants=" + VARIANTS.get()
                    + " reblesses=" + REBLESSES.get())
            })
        }
    }
}
