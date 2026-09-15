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
}
