package org.raku.nqp.runtime.unit

import org.raku.nqp.runtime.CompilationUnit

/**
 * A throwaway CompilationUnit for tests that only need a unit reference to
 * hang a code ref on -- the shape ProgramUnitTest builds in its own fixture.
 * Task 5 rewrites it over a UnitStore; until then it is a v1 ProgramUnit
 * over a UnitRecord.
 */
object ProgramUnitTestSupport {
    private fun block(name: String, prog: Int, outer: Int) = BlockRec(
        name, null, outer, arrayOf("\$a", "\$b"), arrayOf(), arrayOf(), arrayOf(),
        longArrayOf(0), false, false, "u.nqp", 1, 0, null, null, null, prog)

    fun unit(): CompilationUnit {
        val meta = UnitMeta("U1", "nqp", "h", "d", 2, 0, -1, 1, 1,
            listOf(CallSiteRec(byteArrayOf(1), null)),
            arrayOf(block("<mainline>", 0, -1), block("deser", 1, 0)),
            listOf(), listOf())
        return ProgramUnit(UnitRecord(meta, arrayOf("p0", "p1"), byteArrayOf(), mapOf()))
    }
}
