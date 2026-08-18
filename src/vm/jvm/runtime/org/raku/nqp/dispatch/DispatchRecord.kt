package org.raku.nqp.dispatch

import java.util.IdentityHashMap

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance
import org.raku.nqp.sixmodel.reprs.TrackedInstance

/** The mutable state of one resumption of a dispatch, held by the record that set it up. */
class ResumeState(val dispatcher: Dispatcher) {
    var state: SixModelObject? = null
}

/** A resumption found by walking the callstack: what to resume and where its state lives. */
class FoundResumption(val owner: DispatchRecord, val spec: ResumptionSpec, val state: ResumeState)

/**
 * One level of resumption that a dispatch is working through. A resumption may
 * run out of things to do and fall back to a resumption of an enclosing
 * dispatch, so these stack up; the innermost is level zero.
 */
class ResumptionLevelState(
    val found: FoundResumption,
    /** The initialization arguments, read out of the resumed dispatch. */
    val initArgs: Array<Any?>,
    val initDescriptor: CallSiteDescriptor,
    /** The capture handed back by dispatcher-get-resume-init-args. */
    val initCapture: CallCaptureInstance,
) {
    /** The resume state the recording set, if any. */
    var newState: ValueSource? = null

    /** The recording asked for a further resumption and there was none. */
    var noNextResumption = false

    val dispatcher: Dispatcher
        get() = found.spec.dispatcher
}

/** What a dispatch callback settled on doing, as the recording sees it. */
sealed interface RecordedOutcome {
    /** Hand over to another dispatcher. */
    class Delegate(val dispatcher: Dispatcher, val capture: SixModelObject) : RecordedOutcome

    /** Resume a dispatch found on the callstack. */
    class Resume(val capture: SixModelObject) : RecordedOutcome

    /** Fall back to the next resumption out. */
    class NextResumption(val capture: SixModelObject?) : RecordedOutcome

    /** Produce a result or invoke something; the dispatch is settled. */
    class Settled(val outcome: Outcome) : RecordedOutcome
}

/**
 * A dispatch in progress. One of these is live for as long as a dispatch is
 * being recorded and for as long as whatever it invoked is running, which is
 * what lets a callee resume the dispatch that called it.
 *
 * The same record serves a dispatch that is running an already-compiled
 * program: it still has to hold the arguments and the resumption state so that
 * the dispatch can be resumed.
 */
class DispatchRecord(
    override val tc: ThreadContext,
    /** The dispatcher the dispatch started with; unset when replaying a program. */
    val initialDispatcher: Dispatcher?,
    override val descriptor: CallSiteDescriptor,
    override val args: Array<Any?>,
    /** The frame the dispatch instruction is in. */
    val callerFrame: CallFrame?,
    /** The inline cache at the callsite, if the dispatch came from one. */
    val callSite: DispatchCallSite?,
) : DispatchContext {
    /** Is a dispatch callback still building this up? */
    var recording = true
        private set

    /** The program this dispatch produced, or the one it is running. */
    var program: DispatchProgram? = null

    /** The state of each resumption our program set up; created on first use. */
    private var states: MutableList<ResumeState>? = null

    /** The resumption levels we are working through, innermost first. */
    val levels = ArrayList<ResumptionLevelState>()

    /** How we found the dispatch we are resuming, if we are resuming one. */
    var resumeKind = ResumeKind.NONE
        private set

    /* ----- recording state ----- */

    /** The dispatcher whose callback is currently running. */
    var currentDispatcher: Dispatcher? = initialDispatcher

    /** The capture that callback was handed. */
    var currentCapture: SixModelObject? = null

    /** What the current callback decided to do. */
    var outcome: RecordedOutcome? = null

    /** Every capture we handed out, and how to rebuild it. */
    private val captures = IdentityHashMap<SixModelObject, CaptureShape>()

    /** The tracked value for each value source we were asked about. */
    private val tracked = HashMap<ValueSource, TrackedInstance>()

    /** The properties each value was relied upon to have, in the order first tracked. */
    private val guardSets = LinkedHashMap<ValueSource, ValueGuards>()

    /** Resume init arguments saved by dispatchers that want to be resumable. */
    private val resumeInits = ArrayList<ResumeInit>()

    /**
     * Arguments a dispatcher saved for its own future resumption, noting which
     * resumption level was current at the time; that decides where in the
     * program's resumption list it ends up.
     */
    private class ResumeInit(val dispatcher: Dispatcher, val capture: SixModelObject,
                             val level: Int)

    /** How a bind failure of an invocation should map to a resumption. */
    var bindControl: BindControl? = null
        private set

    /** Should the program be kept out of the inline cache? */
    var doNotInstall = false
        private set

    /**
     * The initial capture, as handed to the first dispatch callback. Only a
     * dispatch that is being recorded needs one, so it is made on demand.
     */
    val initialCapture: CallCaptureInstance by lazy(LazyThreadSafetyMode.NONE) {
        val capture = Captures.create(tc, descriptor, args)
        captures.put(capture, CaptureShape.ofArgs(descriptor))
        capture
    }

    /**
     * The properties one value was relied upon to have. The resumption level
     * that was current when the value was first tracked decides which part of
     * a resuming program the guards belong to; -1 means they always apply.
     */
    private class ValueGuards(val level: Int) {
        var type = false
        var concreteness = false
        var literal = false
        var hll = false
        val notLiteral = ArrayList<SixModelObject?>()
    }

    /* ----- reading values ----- */

    override fun resumeInitArg(level: Int, index: Int): DispatchValue {
        val state = levels[level]
        return DispatchValue(ArgKind.ofFlag(state.initDescriptor.argFlags[index]),
            state.initArgs[index])
    }

    override fun resumeState(level: Int): SixModelObject? = levels[level].found.state.state

    /** The state cells for the resumptions of our program, made on first demand. */
    fun ensureResumeStates(): List<ResumeState> {
        var current = states
        if (current == null) {
            current = ArrayList()
            for (resumption in program!!.resumptions)
                current.add(ResumeState(resumption.dispatcher))
            states = current
        }
        return current
    }

    /* ----- capture derivation ----- */

    /** The shape of a capture we handed out; dies if it is not one of ours. */
    fun shapeOf(capture: SixModelObject?): CaptureShape =
        captures.get(capture)
            ?: throw ExceptionHandling.dieInternal(tc,
                "Dispatch operation received a capture that is not part of this dispatch")

    /** Notes a derived capture and builds the matching capture object. */
    fun derive(shape: CaptureShape): CallCaptureInstance {
        val capture = Captures.create(tc, shape.descriptor, shape.evaluate(this))
        captures.put(capture, shape)
        return capture
    }

    /** Registers a capture whose shape we already know (a resume init state). */
    fun noteCapture(capture: CallCaptureInstance, shape: CaptureShape) {
        captures.put(capture, shape)
    }

    /* ----- tracking and guarding ----- */

    /** The tracked value for a source, creating it the first time it is asked for. */
    fun trackedFor(source: ValueSource, value: DispatchValue): TrackedInstance =
        tracked.getOrPut(source) {
            guardSets.getOrPut(source) { ValueGuards(levels.size - 1) }
            TrackedInstance.create(tc, source, value)
        }

    /** Checks that a tracked value belongs to this dispatch and hands back its source. */
    fun sourceOf(trackedValue: SixModelObject?): ValueSource {
        if (trackedValue !is TrackedInstance)
            throw ExceptionHandling.dieInternal(tc,
                "Dispatch operation expected a tracked value")
        val source = trackedValue.source
        if (source == null || tracked.get(source) !== trackedValue)
            throw ExceptionHandling.dieInternal(tc,
                "Dispatch operation received a tracked value from another dispatch")
        return source
    }

    fun valueOf(trackedValue: SixModelObject?): DispatchValue {
        sourceOf(trackedValue)
        return (trackedValue as TrackedInstance).dispatchValue
    }

    /** Starts tracking an argument of one of our captures. */
    fun trackArg(capture: SixModelObject?, index: Int): TrackedInstance {
        val shape = shapeOf(capture)
        if (index < 0 || index >= shape.sources.size)
            throw ExceptionHandling.dieInternal(tc,
                "Capture argument index $index out of range (have ${shape.sources.size})")
        val value = Captures.argValue(tc, Captures.asCapture(tc, capture), index)
        return trackedFor(shape.sources[index], value)
    }

    /**
     * Starts tracking an attribute of an already tracked object. Reading an
     * attribute is only safe for a particular type and concreteness, so those
     * are guarded as a consequence.
     */
    fun trackAttr(trackedValue: SixModelObject?, classHandle: SixModelObject?,
                  name: String): TrackedInstance {
        val base = sourceOf(trackedValue)
        val baseValue = (trackedValue as TrackedInstance).dispatchValue
        if (baseValue.kind != ArgKind.OBJ)
            throw ExceptionHandling.dieInternal(tc,
                "Can only track an attribute of a tracked object")
        val obj = baseValue.obj
        if (obj == null || !Guard.isConcrete(obj))
            throw ExceptionHandling.dieInternal(tc,
                "Can only track an attribute of a concrete object")
        guardType(base)
        guardConcreteness(base)
        val handle = Ops.decont(classHandle, tc)
        val kind = ValueSource.attributeKind(tc, obj, handle, name)
        val source = ValueSource.Attribute(base, handle, name, kind)
        return trackedFor(source,
            DispatchValue(kind, ValueSource.readAttribute(tc, obj, handle, name, kind)))
    }

    /** Starts tracking the meta-object of an already tracked object. */
    fun trackHow(trackedValue: SixModelObject?): TrackedInstance {
        val base = sourceOf(trackedValue)
        val baseValue = (trackedValue as TrackedInstance).dispatchValue
        if (baseValue.kind != ArgKind.OBJ)
            throw ExceptionHandling.dieInternal(tc,
                "Can only track the meta-object of a tracked object")
        val source = ValueSource.How(base)
        return trackedFor(source,
            DispatchValue(ArgKind.OBJ, baseValue.obj?.st?.HOW))
    }

    /** Starts tracking a native value unboxed out of an already tracked object. */
    fun trackUnbox(trackedValue: SixModelObject?, kind: ArgKind): TrackedInstance {
        val base = sourceOf(trackedValue)
        val baseValue = (trackedValue as TrackedInstance).dispatchValue
        if (baseValue.kind != ArgKind.OBJ)
            throw ExceptionHandling.dieInternal(tc, "Can only unbox a tracked object")
        val obj = baseValue.obj
        if (obj == null || !Guard.isConcrete(obj))
            throw ExceptionHandling.dieInternal(tc, "Can only unbox a concrete object")
        guardType(base)
        guardConcreteness(base)
        val source = ValueSource.Unbox(base, kind)
        return trackedFor(source, DispatchValue(kind, ValueSource.unbox(tc, obj, kind)))
    }

    /**
     * Starts tracking the result of looking a tracked string key up in a hash.
     * The hash itself may be a constant or tracked in its own right.
     */
    fun trackLookup(table: ValueSource, key: SixModelObject?): TrackedInstance {
        val keySource = sourceOf(key)
        val keyValue = (key as TrackedInstance).dispatchValue
        if (keyValue.kind != ArgKind.STR)
            throw ExceptionHandling.dieInternal(tc,
                "A dispatch program lookup key must be a tracked string")
        val source = ValueSource.Lookup(table, keySource)
        return trackedFor(source, source.evaluate(this))
    }

    private fun guardsFor(source: ValueSource): ValueGuards =
        guardSets.get(source)
            ?: throw ExceptionHandling.dieInternal(tc, "Guarding an untracked value")

    fun guardType(source: ValueSource) { guardsFor(source).type = true }

    fun guardConcreteness(source: ValueSource) { guardsFor(source).concreteness = true }

    fun guardLiteral(source: ValueSource) { guardsFor(source).literal = true }

    fun guardHll(source: ValueSource) { guardsFor(source).hll = true }

    fun guardNotLiteralObj(source: ValueSource, rejected: SixModelObject?) {
        guardsFor(source).notLiteral.add(rejected)
    }

    /* ----- decisions a dispatch callback makes ----- */

    fun delegate(dispatcher: Dispatcher, capture: SixModelObject?) {
        if (outcome is RecordedOutcome.Delegate)
            throw ExceptionHandling.dieInternal(tc,
                "Can only call dispatcher-delegate once in a dispatch callback")
        shapeOf(capture)
        outcome = RecordedOutcome.Delegate(dispatcher, capture!!)
    }

    fun settle(outcome: Outcome) {
        this.outcome = RecordedOutcome.Settled(outcome)
    }

    fun setBindControl(control: BindControl) {
        if (bindControl != null)
            throw ExceptionHandling.dieInternal(tc,
                "Already configured bind control for this dispatch")
        bindControl = control
    }

    fun setDoNotInstall() { doNotInstall = true }

    /** Saves the arguments a dispatcher wants back when it is resumed. */
    fun setResumeInitArgs(capture: SixModelObject?) {
        if (currentDispatcher?.isResumable != true)
            throw ExceptionHandling.dieInternal(tc,
                "Can only use dispatcher-set-resume-init-args in a resumable dispatcher")
        shapeOf(capture)
        for (init in resumeInits)
            if (init.dispatcher === currentDispatcher)
                throw ExceptionHandling.dieInternal(tc,
                    "Already set resume init args for this dispatcher")
        resumeInits.add(ResumeInit(currentDispatcher!!, capture!!, levels.size - 1))
    }

    /* ----- resumption ----- */

    /** The level whose resume callback is currently running. */
    fun currentLevel(): ResumptionLevelState {
        if (levels.isEmpty())
            throw ExceptionHandling.dieInternal(tc,
                "Can only use this dispatcher operation in a resume callback")
        return levels[levels.size - 1]
    }

    /** Begins a resumption, having found the dispatch to resume. */
    fun startResume(found: FoundResumption, kind: ResumeKind) {
        if (resumeKind != ResumeKind.NONE)
            throw ExceptionHandling.dieInternal(tc, "Can only enter a resumption once in a dispatch")
        resumeKind = kind
        pushLevel(found)
    }

    /** Adds a further, outer resumption level to work through. */
    fun pushLevel(found: FoundResumption) {
        val descriptor = found.spec.initArgs.descriptor
        val initArgs = found.spec.initArgs.evaluate(found.owner)
        val capture = Captures.create(tc, descriptor, initArgs)
        levels.add(ResumptionLevelState(found, initArgs, descriptor, capture))
        val sources = (0 until descriptor.argFlags.size)
            .map { ValueSource.ResumeInitArg(levels.size - 1, it) }
        noteCapture(capture, CaptureShape(sources, descriptor))
    }

    fun setResumeState(source: ValueSource, value: SixModelObject?) {
        val level = currentLevel()
        level.newState = source
        level.found.state.state = value
    }

    fun setResumeStateLiteral(value: SixModelObject?) {
        val level = currentLevel()
        level.newState = ValueSource.Literal(ArgKind.OBJ, value)
        level.found.state.state = value
    }

    fun trackResumeState(): TrackedInstance {
        val level = levels.size - 1
        if (level < 0)
            throw ExceptionHandling.dieInternal(tc,
                "Can only use dispatcher-track-resume-state in a resume callback")
        val source = ValueSource.ResumeState(level)
        return trackedFor(source, DispatchValue(ArgKind.OBJ, resumeState(level)))
    }

    /* ----- compiling ----- */

    /** Marks the recording as finished; further dispatch operations see the outer one. */
    fun endRecording() { recording = false }

    /** How many programs were installed at our callsite when we started. */
    val cacheSize: Int = callSite?.programs?.size ?: 0

    /** Where a fallback to the next resumption should look; only valid in a resume. */
    fun resumeKindForNext(): ResumeKind {
        if (resumeKind == ResumeKind.NONE)
            throw ExceptionHandling.dieInternal(tc,
                "Can only use dispatcher-next-resumption in a resume callback")
        return resumeKind
    }

    /**
     * Turns the recording into a program that can be replayed. The guards are
     * emitted in the order the values were first tracked, so that a value read
     * out of another is only reached once the guards that made that read safe
     * have been checked.
     */
    fun compile(): DispatchProgram {
        val settled = outcome as? RecordedOutcome.Settled
            ?: throw ExceptionHandling.dieInternal(tc, "Dispatch did not reach an outcome")

        /* Guards recorded while a resumption level was current only make sense
         * once a run has that level in hand, so they travel with it. */
        fun guardsAtLevel(level: Int): List<Guard> {
            val out = ArrayList<Guard>()
            for ((source, guards) in guardSets)
                if (guards.level == level) emitGuards(source, guards, out)
            return out
        }

        /* Resumptions are listed innermost first, which is the reverse of the
         * order the dispatchers registered them in, grouped by the resumption
         * level that was current at the time. */
        val resumptions = ArrayList<ResumptionSpec>()
        for (level in -1 until levels.size)
            for (init in resumeInits.filter { it.level == level }.asReversed())
                resumptions.add(ResumptionSpec(init.dispatcher, shapeOf(init.capture)))

        val resumeLevels = levels.mapIndexed { index, level ->
            ResumptionLevel(level.dispatcher, level.initDescriptor, guardsAtLevel(index),
                level.newState, level.noNextResumption)
        }

        return DispatchProgram(descriptor, guardsAtLevel(-1), settled.outcome, resumptions,
            resumeKind, resumeLevels, bindControl)
    }

    private fun emitGuards(source: ValueSource, guards: ValueGuards, into: MutableList<Guard>) {
        val value = tracked.get(source)!!.dispatchValue
        if (guards.literal) {
            /* A literal guard says everything a type or concreteness guard
             * would have said. */
            into.add(Guard.Literal(source, value))
        }
        else {
            if (guards.type)
                into.add(Guard.OfType(source, Guard.typeOf(value)))
            if (guards.concreteness)
                into.add(Guard.Concreteness(source, Guard.isConcrete(value.value)))
            if (guards.hll)
                into.add(Guard.OfHll(source, Guard.hllOf(value)))
        }
        for (rejected in guards.notLiteral)
            into.add(Guard.NotLiteralObj(source, rejected))
    }
}
