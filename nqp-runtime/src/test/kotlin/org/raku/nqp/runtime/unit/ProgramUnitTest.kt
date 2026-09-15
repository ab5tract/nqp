package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import org.raku.nqp.runtime.ArgsExpectation
import org.raku.nqp.runtime.CodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ProgramUnit over a UnitStore: the fixture is written by UnitImageWriter and
 * opened like a file, so these tests run the same decode the loader runs.
 * ProgramUnitTestSupport holds it; there is deliberately only the one.
 */
class ProgramUnitTest {
    @Test
    fun tableFollowsTheBlockTable() {
        val u = ProgramUnitTestSupport.unit()
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

    /**
     * A qbid gap (a block that registered static lexical values but was
     * never compiled into the unit) must stay a GAP in the qbid-indexed
     * table and must not shift the blocks after it -- that table is what
     * the serializer's code-ref slots and every BVal/CODEREF resolve
     * through (`CompilationUnit.lookupCodeRef(Int)`), so a shift there is
     * a code-ref identity fault. `codeRefs` is the DENSE list, as it is on
     * the class road, where it is the reflection-ordered method list; the
     * two are deliberately different shapes.
     */
    @Test
    fun aQbidGapStaysAGapAndShiftsNothingAfterIt() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        assertNull(t[2])
        assertEquals(ProgramUnitTestSupport.LOAD_NAME, t[3]!!.name)
        assertEquals(2, t[3]!!.staticInfo.programIndex)
        assertNull(u.lookupCodeRef(2))
        assertSame(t[3], u.lookupCodeRef(3))
        assertSame(t[0], u.lookupCodeRef(0))
        // The dense list holds the live blocks only, in qbid order.
        assertEquals(listOf("main", "deser", ProgramUnitTestSupport.LOAD_NAME), u.codeRefs!!.map { it.name })
    }

    @Test
    fun blocksAreRawArgsEngineBlocksWithDistinctHandles() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        assertEquals(ArgsExpectation.USE_BINDER, t[0]!!.staticInfo.argsExpectation)
        assertEquals(1, t[1]!!.staticInfo.programIndex)
        assertTrue(t[0]!!.staticInfo.mh !== t[1]!!.staticInfo.mh)
        assertNotNull(t[0]!!.staticInfo.mhResume)
        assertEquals(4, t[0]!!.staticInfo.mh.type().parameterCount())   // (tc, cr, csd, args)
    }

    /**
     * `unitEntry` plus `USE_BINDER` is the precondition the fast paths key
     * on: `Ops.invokeDirect` takes the direct road only for a code ref
     * carrying both, and `Dispatch.invokeCallback` does the same. So every
     * block `buildTable` produces must carry the pair -- not just the
     * mainline -- and a code ref built any other way must NOT claim
     * `unitEntry`, or those fast paths would enter a body that has no
     * engine program behind it.
     */
    @Test
    fun everyTableCodeRefIsAUnitEntry() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        for (qbid in t.indices) {
            val cr = t[qbid] ?: continue
            assertTrue(cr.staticInfo.unitEntry, "buildTable marks qbid $qbid as a unit entry")
            assertEquals(ArgsExpectation.USE_BINDER, cr.staticInfo.argsExpectation,
                "qbid $qbid takes its args through the binder")
        }
    }

    @Test
    fun aCodeRefNotFromBuildTableIsNotAUnitEntry() {
        val u = ProgramUnitTestSupport.unit()
        // The shape AdaptorUnit uses: a hand-built code ref over some handle,
        // never routed through `buildTable`.
        val cr = CodeRef(u, ProgramEntry.ENTER, "hand-built", null,
            null, null, null, null, null, ArgsExpectation.USE_BINDER)
        assertFalse(cr.staticInfo.unitEntry, "only buildTable may claim the unit-entry body")
    }

    @Test
    fun handlersUnflatten() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val h = u.qbidToCodeRef!![1]!!.staticInfo.handlers!!
        assertEquals(1, h.size)
        assertEquals(listOf(5L, 6L), h[0].toList())
    }

    @Test
    fun hooksAnswerFromTheMeta() {
        val u = ProgramUnitTestSupport.unit()
        assertEquals("nqp", u.hllName())
        assertEquals(0, u.mainlineQbid()); assertEquals(-1, u.entryQbid())
        assertEquals(-1, u.deserializeQbid()); assertEquals(3, u.loadQbid())
        assertEquals(2, u.serializedCodeRefCount())
        assertEquals("unit-x", u.unitId())
        assertEquals(ProgramUnitTestSupport.PROG2, u.engineProgram(2))
        // The v1 call-site table was never written: the engine builds its
        // own descriptors, so a unit carries none.
        assertEquals(0, u.getCallSites().size)
    }

    @Test
    fun cuidLookupFollowsTheBlockTable() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        val t = u.qbidToCodeRef!!
        assertSame(t[3], u.lookupCodeRef("cuid-3"))
        assertEquals("cuid-3", t[3]!!.staticInfo.uniqueId)
        // A block the compiler gave no cuid is findable by qbid only.
        assertNull(t[0]!!.staticInfo.uniqueId)
        assertNull(u.lookupCodeRef("cuid-9"))
        assertNull(u.lookupCodeRef(""))
    }

    @Test
    fun shellsAreBuiltForEveryLiveBlockWithoutDecodingARecord() {
        val u = ProgramUnitTestSupport.unit()
        u.buildTable(null)
        assertEquals(3, u.codeRefs!!.size)
        val sci = u.lookupCodeRef(0)!!.staticInfo
        assertEquals(0, sci.programIndex); assertTrue(sci.unitEntry); assertEquals("qb_0", sci.methodName)
        assertSame(u.lookupCodeRef(0)!!.staticInfo, u.lookupCodeRef(3)!!.staticInfo.outerStaticInfo)
        assertEquals(2, sci.oLexicalNames!!.size)        // fills now
        assertEquals("t.nqp", sci.sourceFile)
    }

    @Test
    fun staticLexValuesWaitForTheSc() {
        // block 0 has a static lexical "$y" from sc-x index 7: forced before the
        // deserialize program has run, the row is queued; the drain applies it.
        val (u, tc) = ProgramUnitTestSupport.unitWithSc()
        u.initializeCompilationUnit(tc, false)
        val sci = u.lookupCodeRef(0)!!.staticInfo
        assertNull(sci.oLexStatic!![1])
        u.runDeserializeIfAvailable(tc)                  // deserializeQbid = -1: only the drain runs
        assertNotNull(sci.oLexStatic!![1])
        assertEquals(1, sci.oLexStaticFlags!![1].toInt())
        // a body filled after the drain applies its rows at once
        val later = u.lookupCodeRef(3)!!.staticInfo
        assertEquals(ProgramUnitTestSupport.LOAD_NAME, u.lookupCodeRef(3)!!.name); later.oLexicalNames
        assertNotNull(later.oLexStatic!![0])
        assertEquals(2, later.oLexStaticFlags!![0].toInt())
    }

    @Test
    fun dispatchSlotsReadThroughTheUnit() {
        val u = ProgramUnitTestSupport.unit()
        assertNull(u.dispatchSlot(0, 0)); assertNull(u.dispatchSlot(7, 0))
    }

    /**
     * The namespace a program identity is keyed by. Neither half alone is
     * enough: a unit id is author-supplied and repeats across artifacts
     * (Rakudo's build names five of them "perl6"), and a nested unit
     * inherits its parent's store name -- so keying on either alone let one
     * unit's program answer for another's (milestone 7 Task 7).
     */
    @Test
    fun identityNamespaceNamesBothTheStoreAndTheUnit() {
        // a unit built in this process has none: its id is a fresh sha1 per
        // compile, so its programs go on sharing a root by text
        assertNull(ProgramUnitTestSupport.unit().identityNamespace())
        assertEquals("x.jar!unit-x", ProgramUnitTestSupport.unit("x.jar").identityNamespace())

        // a nested unit rides in the parent's zip and so carries the parent's
        // store name; only its unit id tells the two apart
        val inner = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(UnitImage(
            "inner", "nqp", null, null, 0, 0, -1, -1, -1,
            listOf(BlockEntry("m", null, -1, 0, BlockRecord(
                emptyList(), emptyList(), emptyList(), emptyList(), longArrayOf(0),
                false, false, "t.nqp", 3, 0, null, null, null, emptyList()))),
            listOf("P"), intArrayOf(0), null, emptyMap()))), "<inner>")
        val parent = ProgramUnitTestSupport.unit("x.jar", mapOf("inner" to inner))
        val nested = ProgramUnit(parent.store.nested("inner")!!)
        assertEquals("x.jar!inner", nested.identityNamespace())
        assertNotEquals(parent.identityNamespace(), nested.identityNamespace())
    }
}
