package org.raku.nqp.dispatch

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport
import org.raku.nqp.runtime.unit.UnitCodec
import org.raku.nqp.runtime.unit.UnitImage
import org.raku.nqp.runtime.unit.UnitImageWriter
import org.raku.nqp.runtime.unit.UnitStore

class DispatchPersistTest {
    /** The shared fixture's image with slot 1 (program 0, ordinal 1) filled. */
    private fun storeWith(slot: ByteArray): UnitStore {
        val base = ProgramUnitTestSupport.image()
        val img = UnitImage(base.unitId, base.hll, base.scHandle, base.scDesc, base.serializedCodeRefCount,
            base.mainlineQbid, base.entryQbid, base.deserializeQbid, base.loadQbid, base.blocks, base.programs,
            base.dispatchCounts, base.serialized, base.nested, mapOf(1 to slot))
        return UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(img)), "/x/fixture.jar")
    }

    @Test fun restoreRealisesTheSlotsProgramsAndCountsThem() {
        val tc = ProgramUnitTestSupport.tc()
        val knowhow = tc.gc.KnowHOW!!
        val csd = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
        val p = DispatchProgram(csd, listOf(Guard.OfType(ValueSource.Arg(0), knowhow.st)),
            Outcome.Value(ValueSource.Arg(0)), emptyList(), ResumeKind.NONE, emptyList(), null)
        val bytes = UnitCodec.encode(DispatchSlot.serializer(), DispatchSlot(listOf(DispatchSlotCodec.persist(p)!!)))
        val store = storeWith(bytes)
        val ns = "/x/fixture.jar!unit-x"
        DispatchPersist.register(ns, store)
        val site = DispatchCallSite(MethodType.methodType(Void.TYPE))
        site.unitNamespace = ns; site.programIndex = 0; site.ordinal = 1
        val before = DispatchPersist.restored.get()
        val got = DispatchPersist.restore(tc, site)
        assertEquals(1, got.size)
        assertEquals(DispatchDump.describe(p), DispatchDump.describe(got[0]))
        assertEquals(before + 1, DispatchPersist.restored.get())
        assertTrue(DispatchPersist.restore(tc, DispatchCallSite(MethodType.methodType(Void.TYPE))).isEmpty(),
            "an anonymous site restores nothing")
        site.ordinal = 0
        assertTrue(DispatchPersist.restore(tc, site).isEmpty(), "an empty slot restores nothing")
    }

    /** verify mode's three outcomes, which no run can reach until slots are
     *  written: a kept program that applies and reads the same as the
     *  recording (matched), one that applies and differs (mismatched, with
     *  the MISMATCH line), and one whose guards do not hold for this call at
     *  all (unseen). A verify bug looks exactly like an empty artifact --
     *  matched=0 mismatched=0 -- so the branches are covered here. */
    @Test fun verifyCountsAMatchAMismatchAndACallItDoesNotApplyTo() {
        val tc = ProgramUnitTestSupport.tc()
        val knowhow = tc.gc.KnowHOW!!
        val csd = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)
        fun program(outcome: Outcome) = DispatchProgram(csd,
            listOf(Guard.OfType(ValueSource.Arg(0), knowhow.st)), outcome,
            emptyList(), ResumeKind.NONE, emptyList(), null)
        val site = DispatchCallSite(MethodType.methodType(Void.TYPE))
        site.identity = "/x/fixture.jar!unit-x#0#1"
        site.linkedName = "nqp-call"
        site.verifyPrograms = listOf(program(Outcome.Value(ValueSource.Arg(0))))
        /* The KnowHOW type object itself: Guard.OfType(Arg(0), knowhow.st)
         * holds for it and for nothing else here. */
        val matching = arrayOf<Any?>(knowhow)

        var matched = DispatchPersist.verifyMatched.get()
        var mismatched = DispatchPersist.verifyMismatched.get()
        var unseen = DispatchPersist.verifyUnseen.get()
        DispatchPersist.verify(tc, site, program(Outcome.Value(ValueSource.Arg(0))), csd, matching)
        assertEquals(matched + 1, DispatchPersist.verifyMatched.get(), "the same program matches")
        assertEquals(mismatched, DispatchPersist.verifyMismatched.get())
        assertEquals(unseen, DispatchPersist.verifyUnseen.get())

        matched = DispatchPersist.verifyMatched.get()
        mismatched = DispatchPersist.verifyMismatched.get()
        unseen = DispatchPersist.verifyUnseen.get()
        val differing = program(Outcome.Value(ValueSource.Literal(ArgKind.INT, 1L)))
        val err = ByteArrayOutputStream()
        val saved = System.err
        try {
            System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
            DispatchPersist.verify(tc, site, differing, csd, matching)
        }
        finally { System.setErr(saved) }
        assertEquals(mismatched + 1, DispatchPersist.verifyMismatched.get(), "a different outcome mismatches")
        assertEquals(matched, DispatchPersist.verifyMatched.get())
        assertEquals(unseen, DispatchPersist.verifyUnseen.get())
        val printed = err.toString(StandardCharsets.UTF_8)
        assertTrue("dispatch-verify: MISMATCH" in printed, "the mismatch prints: $printed")
        assertTrue("persisted:" in printed && "recorded:" in printed, "both texts print: $printed")

        matched = DispatchPersist.verifyMatched.get()
        mismatched = DispatchPersist.verifyMismatched.get()
        unseen = DispatchPersist.verifyUnseen.get()
        DispatchPersist.verify(tc, site, program(Outcome.Value(ValueSource.Arg(0))), csd,
            arrayOf<Any?>(tc.gc.BOOTArray))
        assertEquals(unseen + 1, DispatchPersist.verifyUnseen.get(), "a call the guards reject is unseen")
        assertEquals(matched, DispatchPersist.verifyMatched.get())
        assertEquals(mismatched, DispatchPersist.verifyMismatched.get())
    }
}
