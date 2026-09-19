package org.raku.nqp.sixmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RootSetTest {
    @Test
    fun `add grows past the initial capacity and keeps every element`() {
        val rs = RootSet<Any>(2)
        val xs = List(40) { Any() }
        for ((i, x) in xs.withIndex()) assertEquals(i, rs.add(x))
        assertEquals(40, rs.size)
        for ((i, x) in xs.withIndex()) assertSame(x, rs.get(i))
        assertFailsWith<IndexOutOfBoundsException> { rs.get(40) }
    }

    @Test
    fun `init presizes with nulls, set fills a slot, add appends after`() {
        val rs = RootSet<Any>()
        rs.init(20)
        assertEquals(20, rs.size)
        for (i in 0 until 20) assertNull(rs.get(i))
        val a = Any()
        rs.set(7, a)
        assertSame(a, rs.get(7))
        val b = Any()
        assertEquals(20, rs.add(b))
        assertSame(b, rs.get(20))
        assertSame(a, rs.get(7))
        assertFailsWith<IndexOutOfBoundsException> { rs.set(21, a) }
    }

    @Test
    fun `extend adds null slots after the existing ones`() {
        val rs = RootSet<Any>(1)
        val a = Any()
        rs.add(a)
        rs.extend(10)
        assertEquals(11, rs.size)
        assertSame(a, rs.get(0))
        for (i in 1 until 11) assertNull(rs.get(i))
        val b = Any()
        rs.set(10, b)
        assertSame(b, rs.get(10))
    }

    @Test
    fun `clear empties the set and it grows again`() {
        val rs = RootSet<Any>()
        for (i in 0 until 30) rs.add(Any())
        rs.clear()
        assertEquals(0, rs.size)
        assertFailsWith<IndexOutOfBoundsException> { rs.get(0) }
        val a = Any()
        assertEquals(0, rs.add(a))
        assertSame(a, rs.get(0))
    }
}
