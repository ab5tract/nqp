package org.raku.nqp.dispatch

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport
import org.raku.nqp.runtime.unit.UnitCodec

class DispatchSlotCodecTest {
    private val csd = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_STR), null)

    /** persist -> encode -> decode -> realise, the whole road a slot takes. */
    private fun roundTrip(tc: ThreadContext, p: DispatchProgram): DispatchProgram {
        val persisted = assertNotNull(DispatchSlotCodec.persist(p))
        val bytes = UnitCodec.encode(DispatchSlot.serializer(), DispatchSlot(listOf(persisted)))
        val back = UnitCodec.decode(DispatchSlot.serializer(), ByteBuffer.wrap(bytes))
        return assertNotNull(DispatchSlotCodec.realise(tc, back.programs.single()))
    }

    /** A program guarding arg 0 by the bootstrap's KnowHOW type and a
     *  string literal on arg 1, invoking the KnowHOW type object itself as
     *  a stand-in callee: every reference is in __6MODEL_CORE__, so it
     *  persists. The HLL guard names a config of its own rather than
     *  knowhow.st.hllOwner, which the bootstrap leaves null. */
    private fun program(tc: ThreadContext): DispatchProgram {
        val knowhow = tc.gc.KnowHOW!!
        return DispatchProgram(csd,
            listOf(Guard.OfType(ValueSource.Arg(0), knowhow.st),
                   Guard.Concreteness(ValueSource.Arg(0), false),
                   Guard.Literal(ValueSource.Arg(1), DispatchValue(ArgKind.STR, "new_type")),
                   Guard.OfHll(ValueSource.Arg(0), tc.gc.getHLLConfigFor("nqp"))),
            Outcome.InvokeCode(ValueSource.Literal(ArgKind.OBJ, knowhow),
                CaptureShape(listOf(ValueSource.Arg(0), ValueSource.Literal(ArgKind.INT, 3L)),
                    CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT), null))),
            emptyList(), ResumeKind.NONE, emptyList(), null)
    }

    @Test fun aProgramOverScObjectsRoundTripsToTheSameText() {
        val tc = ProgramUnitTestSupport.tc()
        val p = program(tc)
        val realised = roundTrip(tc, p)
        assertEquals(DispatchDump.describe(p), DispatchDump.describe(realised))
        /* The dump prints an HLL config by name, but the guard compares by
         * identity, so the text agreeing is not enough. */
        assertSame(p.guards.filterIsInstance<Guard.OfHll>().single().hll,
                   realised.guards.filterIsInstance<Guard.OfHll>().single().hll)
    }

    @Test fun anHllGuardRealisesInTheRegistryItWasRecordedIn() {
        val tc = ProgramUnitTestSupport.tc()
        tc.gc.useCompileeHLLConfig()
        val compilee = tc.gc.getHLLConfigFor("nqp")
        tc.gc.useCompilerHLLConfig()
        val compiler = tc.gc.getHLLConfigFor("nqp")
        assertNotSame(compilee, compiler)
        /* Recorded against the compilee-side config while the compiler-side
         * registry is the current one -- which is the state a unit is loaded
         * in (Ops.loadcompunit switches to the compiler config). */
        val realised = roundTrip(tc, DispatchProgram(csd,
            listOf(Guard.OfHll(ValueSource.Arg(0), compilee)),
            Outcome.Value(ValueSource.Arg(0)), emptyList(), ResumeKind.NONE, emptyList(), null))
        assertSame(compilee, realised.guards.filterIsInstance<Guard.OfHll>().single().hll)
    }

    @Test fun anObjectInNoScMakesTheProgramUnpersistable() {
        val tc = ProgramUnitTestSupport.tc()
        val orphan = tc.gc.KnowHOW!!.st.REPR.type_object_for(tc, null)   // a fresh type object, in no SC
        val p = DispatchProgram(csd, listOf(Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.OBJ, orphan))),
            Outcome.Value(ValueSource.Arg(0)), emptyList(), ResumeKind.NONE, emptyList(), null)
        assertNull(DispatchSlotCodec.persist(p))
    }

    @Test fun aReferenceThatDoesNotResolveDropsTheProgram() {
        val tc = ProgramUnitTestSupport.tc()
        val ghost = PProgram(PDescriptor(byteArrayOf(0), null),
            listOf(PGuardType(PArg(0), PRef("no-such-sc", 0, PRef.STABLE))),
            POutcomeValue(PArg(0)), emptyList(), ResumeKind.NONE, emptyList(), null, null)
        assertNull(DispatchSlotCodec.realise(tc, ghost))

        val ghostHll = PProgram(PDescriptor(byteArrayOf(0), null),
            listOf(PGuardHll(PArg(0), "no-such-hll", false)),
            POutcomeValue(PArg(0)), emptyList(), ResumeKind.NONE, emptyList(), null, null)
        assertNull(DispatchSlotCodec.realise(tc, ghostHll))
    }
}
