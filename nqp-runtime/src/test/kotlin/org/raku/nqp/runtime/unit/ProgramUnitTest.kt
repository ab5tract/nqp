package org.raku.nqp.runtime.unit

import org.raku.nqp.runtime.ArgsExpectation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgramUnitTest {
    private fun block(name: String, prog: Int, outer: Int, handlers: LongArray = longArrayOf(0)) = BlockRec(
        name, null, outer, arrayOf("\$a", "\$b"), arrayOf(), arrayOf(), arrayOf(),
        handlers, false, false, "u.nqp", 1, 0, null, null, null, prog)

    private fun unit(): ProgramUnit {
        val meta = UnitMeta("U1", "nqp", "h", "d", 2, 0, -1, 1, 1,
            listOf(CallSiteRec(byteArrayOf(1), null)),
            arrayOf(block("<mainline>", 0, -1), block("deser", 1, 0, longArrayOf(1, 2, 5, 6)), null, block("orphan", 2, 0)),
            listOf(LexValueRec(0, "\$a", "h", 3, 0)), listOf())
        return ProgramUnit(UnitRecord(meta, arrayOf("p0", "p1", "p2"), byteArrayOf(), mapOf()))
    }

    @Test
    fun tableFollowsTheBlockTable() {
        val u = unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        assertEquals(4, t.size)
        assertNull(t[2])
        assertEquals("deser", t[1]!!.name)
        assertSame(t[0]!!.staticInfo, t[1]!!.staticInfo.outerStaticInfo)
        assertSame(t[0]!!.staticInfo, t[3]!!.staticInfo.outerStaticInfo)
        assertNull(t[0]!!.staticInfo.outerStaticInfo)
        assertEquals(3, u.codeRefs!!.size)
    }

    @Test
    fun blocksAreRawArgsEngineBlocksWithDistinctHandles() {
        val u = unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        assertEquals(ArgsExpectation.USE_BINDER, t[0]!!.staticInfo.argsExpectation)
        assertEquals(1, t[1]!!.staticInfo.programIndex)
        assertTrue(t[0]!!.staticInfo.mh !== t[1]!!.staticInfo.mh)
        assertNotNull(t[0]!!.staticInfo.mhResume)
        assertEquals(4, t[0]!!.staticInfo.mh.type().parameterCount())   // (tc, cr, csd, args)
    }

    @Test
    fun handlersUnflatten() {
        val u = unit()
        u.buildTable(null)
        val h = u.qbidToCodeRef!![1]!!.staticInfo.handlers!!
        assertEquals(1, h.size)
        assertEquals(listOf(5L, 6L), h[0].toList())
    }

    @Test
    fun hooksAnswerFromTheMeta() {
        val u = unit()
        assertEquals("nqp", u.hllName())
        assertEquals(0, u.mainlineQbid()); assertEquals(-1, u.entryQbid())
        assertEquals(1, u.deserializeQbid()); assertEquals(1, u.loadQbid())
        assertEquals(2, u.serializedCodeRefCount())
        assertEquals("U1", u.unitId())
        assertEquals("p2", u.engineProgram(2))
        assertEquals(1, u.getCallSites().size)
    }
}
