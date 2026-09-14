package org.raku.nqp.runtime

import org.raku.nqp.runtime.unit.BlockRec
import org.raku.nqp.runtime.unit.CallSiteRec
import org.raku.nqp.runtime.unit.LexValueRec
import org.raku.nqp.runtime.unit.ProgramUnit
import org.raku.nqp.runtime.unit.UnitMeta
import org.raku.nqp.runtime.unit.UnitRecord
import org.raku.nqp.sixmodel.SerializationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SerializationContextTest {
    /* The same one-block unit ProgramUnitTest builds: a real code ref needs
     * a real compilation unit. */
    private fun unit(): ProgramUnit {
        val block = BlockRec("<mainline>", null, -1, arrayOf("\$a"), arrayOf(), arrayOf(), arrayOf(),
            longArrayOf(0), false, false, "u.nqp", 1, 0, null, null, null, 0)
        val meta = UnitMeta("U1", "nqp", "h", "d", 1, 0, -1, 0, 0,
            listOf(CallSiteRec(byteArrayOf(1), null)), arrayOf(block),
            listOf(LexValueRec(0, "\$a", "h", 3, 0)), listOf())
        return ProgramUnit(UnitRecord(meta, arrayOf("p0"), byteArrayOf(), mapOf()))
    }

    @Test
    fun `initCodeRefList reserves the code ref slots without adding any`() {
        val u = unit()
        u.buildTable(null)
        val cr = u.qbidToCodeRef!![0]!!
        val sc = SerializationContext("test-sc")
        sc.initCodeRefList(4)
        assertEquals(0, sc.coderefCount())
        sc.addCodeRef(cr)
        assertEquals(1, sc.coderefCount())
        assertSame(cr, sc.getCodeRef(0))
    }
}
