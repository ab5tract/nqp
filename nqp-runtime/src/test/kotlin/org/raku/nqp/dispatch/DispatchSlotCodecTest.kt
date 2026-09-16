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
        val bytes = UnitCodec.encode(DispatchSlot.serializer(), DispatchSlot(DispatchSlot.SCHEMA, listOf(persisted)))
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

    /**
     * Every persisted form in one program. The codec is a pair of total
     * functions over the tree, so the risk is not logic but COVERAGE: a
     * P-type nothing round-trips is a shape whose first test is a build.
     * This one carries a resume init arg, an attribute, a `how`, an unbox,
     * a lookup, resume state, a not-literal guard, a syscall outcome, a
     * resumption, a resume level, a bind control and its failure program, a
     * NUM literal, a named descriptor and a resume kind. Nothing evaluates
     * it -- realise only rebuilds -- so a program no dispatcher would ever
     * record is still a faithful test of the road.
     */
    @Test fun everyPersistedFormRoundTrips() {
        val tc = ProgramUnitTestSupport.tc()
        val knowhow = tc.gc.KnowHOW!!
        fun dispatcher(id: String) = assertNotNull(tc.gc.dispatchers.findOrNull(id), "dispatcher $id")
        /* An obj positional and a named num: PDescriptor carries names. */
        val named = CallSiteDescriptor(
            byteArrayOf(CallSiteDescriptor.ARG_OBJ,
                        (CallSiteDescriptor.ARG_NUM + CallSiteDescriptor.ARG_NAMED).toByte()),
            arrayOf("epsilon"))
        val everySource = listOf(
            ValueSource.Arg(0),
            ValueSource.ResumeInitArg(1, 2),
            ValueSource.Literal(ArgKind.NUM, 2.5),
            ValueSource.Literal(ArgKind.OBJ, knowhow),
            ValueSource.Attribute(ValueSource.Arg(0), knowhow, "\$!count", ArgKind.INT),
            ValueSource.How(ValueSource.Arg(0)),
            ValueSource.Unbox(ValueSource.Arg(0), ArgKind.STR),
            ValueSource.Lookup(ValueSource.Literal(ArgKind.OBJ, knowhow), ValueSource.ResumeState(0)))
        val argShape = CaptureShape(everySource,
            CallSiteDescriptor(ByteArray(everySource.size) { CallSiteDescriptor.ARG_OBJ }, null))
        val onFailure = DispatchProgram(named,
            listOf(Guard.NotLiteralObj(ValueSource.Arg(0), knowhow)),
            Outcome.Value(ValueSource.ResumeState(1)),
            emptyList(), ResumeKind.BIND_FAILURE, emptyList(), null)
        val p = DispatchProgram(named,
            listOf(Guard.OfType(ValueSource.Arg(0), knowhow.st),
                   Guard.Concreteness(ValueSource.Arg(0), true),
                   Guard.Literal(ValueSource.Arg(1), DispatchValue(ArgKind.NUM, 2.5)),
                   Guard.NotLiteralObj(ValueSource.Arg(0), knowhow),
                   Guard.OfHll(ValueSource.Arg(0), tc.gc.getHLLConfigFor("nqp"))),
            Outcome.InvokeSyscall(Syscalls.find(tc, "dispatcher-drop-arg"), argShape),
            listOf(ResumptionSpec(dispatcher("boot-value"), argShape)),
            ResumeKind.CALLER,
            listOf(ResumptionLevel(dispatcher("lang-call"), named,
                listOf(Guard.Concreteness(ValueSource.ResumeInitArg(0, 0), false)),
                ValueSource.ResumeState(0), true)),
            BindControl(3L, 5L, true))
        p.bindFailureProgram = onFailure

        val realised = roundTrip(tc, p)
        assertEquals(DispatchDump.describe(p), DispatchDump.describe(realised))
        /* The text names a syscall, a dispatcher and an HLL config by name;
         * all three must come back as the objects themselves. */
        assertSame((p.outcome as Outcome.InvokeSyscall).syscall,
                   (realised.outcome as Outcome.InvokeSyscall).syscall)
        assertSame(p.resumptions.single().dispatcher, realised.resumptions.single().dispatcher)
        assertSame(p.resumeLevels.single().dispatcher, realised.resumeLevels.single().dispatcher)
        assertSame(p.guards.filterIsInstance<Guard.OfHll>().single().hll,
                   realised.guards.filterIsInstance<Guard.OfHll>().single().hll)
        assertNotNull(realised.bindFailureProgram)
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
