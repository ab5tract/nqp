package org.raku.nqp.sixmodel

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * One root set of a SerializationContext: a growable array whose reads
 * are acquire and whose writes are release, so an entry a demand drain
 * publishes (milestone 8, Phase C) is whole to every thread that reads
 * the slot non-null. A compile's SC grows one by one through [add]; a
 * deserialized SC is presized by [init] and filled by [set].
 */
class RootSet<T : Any>(capacity: Int = 16) {
    private var slots = AtomicReferenceArray<T?>(maxOf(capacity, 1))
    var size: Int = 0
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

    fun ensureCapacity(n: Int) {
        if (n <= slots.length()) return
        val grown = AtomicReferenceArray<T?>(maxOf(n, slots.length() * 2))
        for (i in 0 until size) grown.lazySet(i, slots.get(i))
        slots = grown
    }

    fun clear() {
        slots = AtomicReferenceArray(16)
        size = 0
    }
}
