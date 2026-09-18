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
}
