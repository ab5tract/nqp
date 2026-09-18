package org.raku.nqp.sixmodel

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * One root set of a SerializationContext: a growable array whose reads
 * are acquire and whose writes are release, so an entry a demand drain
 * publishes (milestone 8, Phase C) is whole to every thread that reads
 * the slot non-null. A compile's SC grows one by one through [add]; a
 * deserialized SC is presized by [init] and filled by [set].
 *
 * Both fields are volatile, so a reader that sees a grown array or a raised
 * size sees every element the writer published into it first: the volatile read
 * of [slots] pairs with the volatile write that installs it, and the array's own
 * acquire read pairs with the release write of the slot.
 */
class RootSet<T : Any>(capacity: Int = 16) {
    @Volatile private var slots = AtomicReferenceArray<T?>(maxOf(capacity, 1))
    @Volatile var size: Int = 0
        private set

    fun get(i: Int): T? {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("root index $i of $size")
        return slots.get(i)
    }

    fun set(i: Int, v: T?) {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("root index $i of $size")
        slots.lazySet(i, v)
    }

    fun add(v: T?): Int {
        ensureCapacity(size + 1)
        val i = size
        slots.lazySet(i, v)
        size = i + 1
        return i
    }

    /** size becomes [n], every slot null; the reader's presize. */
    fun init(n: Int) {
        ensureCapacity(n)
        for (i in 0 until n) slots.lazySet(i, null)
        size = n
    }

    /** size grows by [n] null slots (the closure slots after the static code refs). */
    fun extend(n: Int) {
        ensureCapacity(size + n)
        for (i in size until size + n) slots.lazySet(i, null)
        size += n
    }

    fun ensureCapacity(n: Int) {
        if (n <= slots.length()) return
        val grown = AtomicReferenceArray<T?>(maxOf(n, slots.length() * 2))
        for (i in 0 until size) grown.lazySet(i, slots.get(i))
        slots = grown
    }

    fun clear() {
        /* Size first: a reader that saw the old size against the fresh
         * sixteen-slot array would get an ArrayIndexOutOfBounds from the
         * array instead of this class's own bounds check. */
        size = 0
        slots = AtomicReferenceArray(16)
    }
}
