package org.raku.nqp.sixmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport

class TypeStateTest {
    private fun freshSTable() = STable(ProgramUnitTestSupport.tc().gc.BOOTArray!!.st.REPR, null)

    @Test fun aFreshSTableStartsWithAValidEmptyState() {
        val st = freshSTable()
        val s = st.state
        assertTrue(s.assumption.isValid)
        assertNull(s.methodCache)
        assertNull(s.typeCheckCache)
        assertEquals(0, s.modeFlags)
        assertNull(s.containerSpec)
        assertNull(s.boolificationSpec)
        assertNull(s.invocationSpec)
        assertNull(s.hllOwner)
        assertEquals(0L, s.hllRole)
        assertFalse(s.methodCacheAuthoritative)
    }

    @Test fun statesAreNeverShared() {
        assertNotSame(freshSTable().state, freshSTable().state)
    }

    @Test fun publishInstallsTheNextStateAndInvalidatesTheOld() {
        val st = freshSTable()
        val old = st.state
        val before = STable.PUBLISHES.sum()
        val next = old.withFacts(modeFlags = STable.METHOD_CACHE_AUTHORITATIVE)
        st.publish(next)
        assertSame(next, st.state)
        assertFalse(old.assumption.isValid)
        assertTrue(next.assumption.isValid)
        assertTrue(st.state.methodCacheAuthoritative)
        assertEquals(before + 1, STable.PUBLISHES.sum())
    }

    @Test fun withFactsKeepsEveryOtherFact() {
        val cache = HashMap<String, SixModelObject?>()
        val base = TypeState.initial().withFacts(methodCache = cache, hllRole = 4L, modeFlags = 2)
        val next = base.withFacts(typeCheckCache = arrayOfNulls(0))
        assertSame(cache, next.methodCache)
        assertEquals(4L, next.hllRole)
        assertEquals(2, next.modeFlags)
        assertNotSame(base.assumption, next.assumption)
        assertTrue(base.assumption.isValid, "withFacts does not publish")
    }

    @Test fun republishKeepsTheFactsUnderAFreshAssumption() {
        val st = freshSTable()
        st.publish(st.state.withFacts(hllRole = 3L))
        val old = st.state
        st.republish()
        assertNotSame(old, st.state)
        assertFalse(old.assumption.isValid)
        assertEquals(3L, st.state.hllRole)
    }

    @Test fun theWriterOpsPublish() {
        val tc = ProgramUnitTestSupport.tc()
        /* A type no other test touches: a fresh type object off BOOTHash's
         * REPR and HOW, rather than the shared bootstrap type itself. */
        val type = tc.gc.BOOTHash!!.st.REPR.type_object_for(tc, tc.gc.BOOTHash!!.st.HOW)
        val st = type.st
        val s0 = st.state
        Ops.setmethcacheauth(type, 1L, tc)
        val s1 = st.state
        assertNotSame(s0, s1); assertFalse(s0.assumption.isValid); assertTrue(s1.methodCacheAuthoritative)
        Ops.settypecheckmode(type, STable.TYPE_CHECK_CACHE_THEN_METHOD.toLong(), tc)
        val s2 = st.state
        assertNotSame(s1, s2); assertFalse(s1.assumption.isValid)
        assertEquals(STable.TYPE_CHECK_CACHE_THEN_METHOD, s2.typeCheckMode)
        assertTrue(s2.methodCacheAuthoritative, "the mode write keeps the authority bit")
        Ops.setboolspec(type, BoolificationSpec.MODE_HAS_ELEMS.toLong(), null, tc)
        val s3 = st.state
        assertNotSame(s2, s3); assertFalse(s2.assumption.isValid)
        assertEquals(BoolificationSpec.MODE_HAS_ELEMS, s3.boolificationSpec!!.Mode)
        Ops.settypehllrole(type, 5L, tc)
        assertEquals(5L, st.state.hllRole)
        assertFalse(s3.assumption.isValid)
        assertTrue(st.state.assumption.isValid)
    }
}
