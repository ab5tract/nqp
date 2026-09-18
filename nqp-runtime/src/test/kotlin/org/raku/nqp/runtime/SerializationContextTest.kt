package org.raku.nqp.runtime

import org.raku.nqp.runtime.unit.ProgramUnitTestSupport
import org.raku.nqp.sixmodel.SerializationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SerializationContextTest {
    @Test
    fun `initCodeRefList reserves the code ref slots without adding any`() {
        /* A real code ref needs a real compilation unit: the shared fixture. */
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val cr = u.qbidToCodeRef!![0]!!
        val sc = SerializationContext("test-sc")
        sc.initCodeRefList(4)
        assertEquals(0, sc.coderefCount())
        sc.addCodeRef(cr)
        assertEquals(1, sc.coderefCount())
        assertSame(cr, sc.getCodeRef(0))
    }

    @Test
    fun `addObject stamps the index on the object and the index reads back`() {
        val sc = SerializationContext("test-sc-idx")
        val a = org.raku.nqp.sixmodel.TypeObject()
        val b = org.raku.nqp.sixmodel.TypeObject()
        assertEquals(-1, a.scIdx)
        sc.addObject(a)
        sc.addObject(b)
        assertEquals(0, a.scIdx)
        assertEquals(1, b.scIdx)
        assertEquals(1, sc.getObjectIndex(b))
        assertSame(b, sc.getObject(1))
    }

    @Test
    fun `a foreign object answers -1, not 0`() {
        val sc = SerializationContext("test-sc-foreign")
        val other = SerializationContext("test-sc-other")
        val a = org.raku.nqp.sixmodel.TypeObject()
        other.addObject(a)
        assertEquals(-1, sc.getObjectIndex(a))
        assertEquals(-1, sc.getSTableIndex(null))
    }

    @Test
    fun `initObjectList presizes with nulls and addObject at an index fills a slot`() {
        val sc = SerializationContext("test-sc-init")
        sc.initObjectList(3)
        assertEquals(3, sc.objectCount())
        assertEquals(null, sc.getObject(2))
        val a = org.raku.nqp.sixmodel.TypeObject()
        sc.addObject(a, 2)
        assertEquals(2, a.scIdx)
        assertSame(a, sc.getObject(2))
        sc.disclaimObjects()
        assertEquals(-1, a.scIdx)
        assertEquals(0, sc.objectCount())
    }

    @Test
    fun `repossession records the index the object had in its original SC`() {
        val a = SerializationContext("test-sc-repo-a")
        val b = SerializationContext("test-sc-repo-b")
        val obj = org.raku.nqp.sixmodel.TypeObject()
        a.initObjectList(3)
        a.addObject(obj, 2)
        obj.sc = a

        /* Ops.scwbObject repossesses first and moves obj.sc afterwards. */
        b.repossessObject(a, obj)
        obj.sc = b

        val newSlot = b.getObjectIndex(obj)
        assertEquals(0, newSlot)
        assertEquals(1, b.repOrigIndexes.size)
        assertEquals(2, b.repOrigIndexes.getInt(0))
        assertEquals(newSlot shl 1, b.repIndexes.getInt(0))
        assertSame(a, b.repScs[0])
        assertEquals(newSlot, obj.scIdx)
        assertSame(obj, b.getObject(newSlot))
    }
}
