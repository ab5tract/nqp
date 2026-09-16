package org.raku.nqp.dispatch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport
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
        /* Null until something records one: a literal guard on its own folds
         * no type fact, so it is fresh forever. */
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
