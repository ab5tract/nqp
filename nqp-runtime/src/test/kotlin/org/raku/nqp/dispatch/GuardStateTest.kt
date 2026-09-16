package org.raku.nqp.dispatch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.TypeObject

/**
 * Milestone 8, the type state: a type guard is a state guard, and the state
 * it names is the one the dispatcher had in hand when it ASKED for the
 * guard -- before it read the type's facts. A publish between that reading
 * and the moment the Guard object is built at compile() must still leave the
 * program stale, or the program's constants (folded from the old facts)
 * would ride under a guard naming the new ones and nothing would reject it.
 */
class GuardStateTest {
    private val csd = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null)

    /** A type object of its own STable, so a publish here disturbs nothing else. */
    private fun freshType(tc: ThreadContext): TypeObject {
        val st = STable(tc.gc.BOOTArray!!.st.REPR, null)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return obj
    }

    @Test fun aTypeGuardGoesStaleWhenItsTypeRepublishes() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val guard = Guard.OfType(ValueSource.Arg(0), obj.st)
        assertSame(obj.st.state, guard.state, "the guard captures the state it was made under")
        assertTrue(guard.isFresh)
        obj.st.publish(obj.st.state.withFacts(hllRole = 9L))
        assertFalse(guard.isFresh, "the type republished")
    }

    @Test fun anObjectLiteralGuardGoesStaleWhenItsTypeRepublishes() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val guard = Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.OBJ, obj))
        /* An OBJ literal guard captures the expected object's state at
         * construction, so a guard built outside a recording is a state guard
         * too; a recording overwrites it with the state of the request. */
        assertSame(obj.st.state, guard.state)
        assertTrue(guard.isFresh)
        guard.state = obj.st.state
        assertTrue(guard.isFresh)
        obj.st.publish(obj.st.state.withFacts(hllRole = 9L))
        assertFalse(guard.isFresh, "the literal's type republished")
    }

    @Test fun aProgramIsStaleWhenAnyOfItsTypeOrLiteralGuardsIs() {
        val tc = ProgramUnitTestSupport.tc()
        val guarded = freshType(tc)
        val literal = freshType(tc)
        val literalGuard = Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.OBJ, literal))
        literalGuard.state = literal.st.state
        val program = DispatchProgram(csd,
            listOf(Guard.OfType(ValueSource.Arg(0), guarded.st),
                   Guard.Concreteness(ValueSource.Arg(0), false),
                   literalGuard),
            Outcome.Value(ValueSource.Arg(0)),
            emptyList(), ResumeKind.NONE, emptyList(), null)
        assertTrue(program.isFresh)
        literal.st.publish(literal.st.state.withFacts(hllRole = 9L))
        assertFalse(program.isFresh, "a stale literal guard makes the program stale")
    }

    /**
     * The window the fix closes: guardType is asked for, the type republishes
     * (as a dispatcher reading its facts and composing could make it), and
     * only then does compile() build the Guard. The guard must name the state
     * of the request, not of the build, so the program is stale on sight.
     */
    @Test fun aRecordNamesTheStateTheGuardWasAskedUnderNotTheOneAtCompile() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val asked = obj.st.state
        val record = DispatchRecord(tc, null, csd, arrayOf<Any?>(obj), null, null)
        val capture = record.derive(CaptureShape(listOf(ValueSource.Arg(0)), csd))
        val tracked = record.trackArg(capture, 0)
        record.guardType(tracked.source!!)
        obj.st.publish(obj.st.state.withFacts(hllRole = 9L))
        record.settle(Outcome.Value(ValueSource.Arg(0)))
        val program = record.compile()
        val guard = program.guards.filterIsInstance<Guard.OfType>().single()
        assertSame(asked, guard.state, "the state of the request, not of the build")
        assertFalse(program.isFresh, "the type republished between the request and the build")
    }

    /** A minimal guard-check context: guards read only tc, descriptor and args. */
    private class Ctx(
        override val tc: ThreadContext,
        override val descriptor: CallSiteDescriptor,
        override val args: Array<Any?>,
    ) : DispatchContext {
        override fun resumeInitArg(level: Int, index: Int): DispatchValue =
            throw UnsupportedOperationException()
        override fun resumeState(level: Int): SixModelObject? =
            throw UnsupportedOperationException()
    }

    /**
     * Hole (d) of the hotfix-2 review: a guard built OUTSIDE a recording --
     * DispatchSlotCodec's restore -- used to carry no state at all, so it
     * stayed "fresh" while the Folder had folded it under the state current
     * at restore time. It now captures that state at construction.
     */
    @Test fun anIdentityGuardBuiltOutsideARecordingCapturesTheStateItWasBuiltUnder() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val built = obj.st.state
        val guard = Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.OBJ, obj))
        assertSame(built, guard.state, "the state at construction")
        assertTrue(guard.isFresh)
        val args = arrayOf<Any?>(obj)
        assertTrue(guard.check(Ctx(tc, csd, args)))
        assertTrue(DispatchCompiler.testLiteralIdArg(0, obj, guard.state, tc, args))
    }

    /**
     * Hole (e): the identity guard is a state guard on every road, so a
     * republish misses in `check` and in the compiled test, not merely in
     * `isFresh`.
     */
    @Test fun anIdentityGuardMissesOnEveryRoadOnceItsTypeRepublishes() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val guard = Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.OBJ, obj))
        val args = arrayOf<Any?>(obj)
        assertTrue(guard.check(Ctx(tc, csd, args)))
        obj.st.publish(obj.st.state.withFacts(hllRole = 9L))
        assertFalse(guard.isFresh)
        assertFalse(guard.check(Ctx(tc, csd, args)), "check sees the republish")
        assertFalse(DispatchCompiler.testLiteralIdArg(0, obj, guard.state, tc, args),
            "the compiled identity test sees it too")
    }

    /** A non-object literal folds no type fact: no state, and equality as before. */
    @Test fun aNonObjectLiteralGuardKeepsNoState() {
        val tc = ProgramUnitTestSupport.tc()
        val intCsd = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_INT), null)
        val guard = Guard.Literal(ValueSource.Arg(0), DispatchValue(ArgKind.INT, 42L))
        assertNull(guard.state)
        assertTrue(guard.isFresh)
        assertTrue(guard.check(Ctx(tc, intCsd, arrayOf<Any?>(42L))))
        assertFalse(guard.check(Ctx(tc, intCsd, arrayOf<Any?>(43L))))
    }

    @Test fun aRecordNamesTheStateAnIdentityGuardWasAskedUnder() {
        val tc = ProgramUnitTestSupport.tc()
        val obj = freshType(tc)
        val asked = obj.st.state
        val record = DispatchRecord(tc, null, csd, arrayOf<Any?>(obj), null, null)
        val capture = record.derive(CaptureShape(listOf(ValueSource.Arg(0)), csd))
        val tracked = record.trackArg(capture, 0)
        record.guardLiteral(tracked.source!!)
        obj.st.publish(obj.st.state.withFacts(hllRole = 9L))
        record.settle(Outcome.Value(ValueSource.Arg(0)))
        val program = record.compile()
        val guard = program.guards.filterIsInstance<Guard.Literal>().single()
        assertSame(asked, guard.state, "the state of the request, not of the build")
        assertFalse(program.isFresh)
    }
}
