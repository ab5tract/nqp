package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance

/** Making and reading the capture objects that dispatchers work with. */
object Captures {
    @JvmStatic
    fun create(tc: ThreadContext, descriptor: CallSiteDescriptor,
               args: Array<Any?>): CallCaptureInstance {
        val type = tc.gc.CallCapture!!
        val capture = type.st.REPR.allocate(tc, type.st) as CallCaptureInstance
        capture.descriptor = descriptor
        capture.args = args
        return capture
    }

    /** Do two callsites take the same arguments in the same way? */
    @JvmStatic
    fun sameShape(a: CallSiteDescriptor, b: CallSiteDescriptor): Boolean {
        if (a === b) return true
        if (!a.argFlags.contentEquals(b.argFlags)) return false
        val aNames = a.names
        val bNames = b.names
        if (aNames == null || bNames == null) return aNames == null && bNames == null
        return aNames.contentEquals(bNames)
    }

    @JvmStatic
    fun asCapture(tc: ThreadContext, obj: SixModelObject?): CallCaptureInstance =
        obj as? CallCaptureInstance
            ?: throw ExceptionHandling.dieInternal(tc, "Expected a capture argument")

    /** The value of a positional or named argument of a capture. */
    @JvmStatic
    fun argValue(tc: ThreadContext, capture: CallCaptureInstance, index: Int): DispatchValue {
        val flags = capture.descriptor!!.argFlags
        if (index < 0 || index >= flags.size)
            throw ExceptionHandling.dieInternal(tc,
                "Capture argument index $index out of range (have ${flags.size})")
        return DispatchValue(ArgKind.ofFlag(flags[index]), capture.args!![index])
    }
}

/**
 * Runs dispatches: either by replaying a program that was recorded at the
 * callsite before, or by running the dispatcher callbacks and recording what
 * they do.
 */
object Dispatch {
    /**
     * How many programs we will keep at one callsite. Past that the callsite is
     * megamorphic: dispatches still work, by recording afresh each time, but
     * nothing more is installed. Dispatchers can see this coming with the
     * dispatcher-inline-cache-size syscall and arrange something better.
     */
    const val MAX_PROGRAMS = 32

    /**
     * Set NQP_DISPATCH_TRACE to have each recorded dispatch reported, as the
     * chain of dispatchers it went through and the outcome it reached. Only
     * recordings are reported, so a callsite that has settled down goes quiet.
     */
    private val trace = System.getenv("NQP_DISPATCH_TRACE") != null

    /* ----- entry points ----- */

    /**
     * A dispatch from a callsite with an inline cache. The result is left in the
     * frame the dispatch instruction is in, the same way a call leaves it.
     */
    @JvmStatic
    fun dispatch(site: DispatchCallSite, name: String, csIdx: Int, tc: ThreadContext,
                 args: Array<Any?>) {
        var descriptor = descriptorFor(tc, csIdx)
        var theArgs = args
        /* Flattening is exploded before the dispatch runs, as MoarVM does at
         * its dispatch instructions: the program inspects arguments by
         * position, which only means anything on the flattened form. */
        if (descriptor.hasFlattening) {
            descriptor = descriptor.explodeFlattening(tc.curFrame!!, theArgs)
            theArgs = tc.flatArgs!!
        }
        for (program in site.programs)
            if (run(tc, program, descriptor, theArgs, site)) return
        record(tc, tc.gc.dispatchers.find(tc, name), descriptor, theArgs, site)
    }

    /** A dispatch with no callsite to install anything at. */
    @JvmStatic
    fun dispatchUncached(tc: ThreadContext, name: String, descriptor: CallSiteDescriptor,
                        args: Array<Any?>) {
        var theCsd = descriptor
        var theArgs = args
        if (theCsd.hasFlattening) {
            theCsd = theCsd.explodeFlattening(tc.curFrame!!, theArgs)
            theArgs = tc.flatArgs!!
        }
        record(tc, tc.gc.dispatchers.find(tc, name), theCsd, theArgs, null)
    }

    private fun descriptorFor(tc: ThreadContext, csIdx: Int): CallSiteDescriptor =
        if (csIdx >= 0)
            tc.frame.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite

    /** The dispatch whose callbacks are currently running. */
    @JvmStatic
    fun currentRecording(tc: ThreadContext): DispatchRecord {
        val records = tc.dispatchRecords
        for (i in records.indices.reversed())
            if (records[i].recording) return records[i]
        throw ExceptionHandling.dieInternal(tc, "Not currently recording a dispatch program")
    }

    /* ----- recording ----- */

    private fun record(tc: ThreadContext, dispatcher: Dispatcher?, descriptor: CallSiteDescriptor,
                       args: Array<Any?>, site: DispatchCallSite?,
                       bindFailureOf: DispatchRecord? = null) {
        val record = DispatchRecord(tc, dispatcher, descriptor, args, tc.curFrame, site)
        val chain = if (trace) ArrayList<String>() else null
        tc.dispatchRecords.add(record)
        try {
            var callback: DispatchCallback
            var capture: SixModelObject = record.initialCapture
            if (bindFailureOf != null) {
                /* We are here because what the dispatch invoked failed to bind
                 * its signature; resume that dispatch, passing the flag it
                 * asked to be resumed with. */
                val resumed = innermostResumption(tc, bindFailureOf)
                record.startResume(resumed, ResumeKind.BIND_FAILURE)
                record.currentDispatcher = resumed.spec.dispatcher
                callback = resumeCallback(tc, resumed.spec.dispatcher)
            }
            else {
                callback = dispatcher!!.dispatch
            }
            while (true) {
                chain?.add(record.currentDispatcher?.id ?: "?")
                record.currentCapture = capture
                record.outcome = null
                invokeCallback(tc, record, callback, capture)
                val outcome = record.outcome
                    ?: throw ExceptionHandling.dieInternal(tc,
                        "Dispatch callback for '${record.currentDispatcher}' failed to delegate " +
                        "to a dispatcher or produce an outcome")
                when (outcome) {
                    is RecordedOutcome.Settled -> break
                    is RecordedOutcome.Delegate -> {
                        record.currentDispatcher = outcome.dispatcher
                        callback = outcome.dispatcher.dispatch
                        capture = outcome.capture
                    }
                    is RecordedOutcome.Resume -> {
                        val dispatcher = record.currentLevel().dispatcher
                        record.currentDispatcher = dispatcher
                        callback = resumeCallback(tc, dispatcher)
                        capture = outcome.capture
                    }
                    is RecordedOutcome.NextResumption -> {
                        val found = findResumption(tc, record.resumeKind, record.levels.size)
                            ?: throw ExceptionHandling.dieInternal(tc,
                                "Call stack inconsistency detected when moving to the next " +
                                "dispatch resumption")
                        record.pushLevel(found)
                        val dispatcher = found.spec.dispatcher
                        record.currentDispatcher = dispatcher
                        callback = resumeCallback(tc, dispatcher)
                        capture = outcome.capture ?: record.initialCapture
                    }
                }
            }

            record.endRecording()
            val program = record.compile()
            record.program = program
            if (chain != null) report(chain, program)
            if (bindFailureOf != null)
                bindFailureOf.program!!.bindFailureProgram = program
            else if (site != null && !record.doNotInstall)
                site.install(program)
            realize(tc, record, program.outcome)
        }
        finally {
            tc.dispatchRecords.removeAt(tc.dispatchRecords.size - 1)
        }
    }

    private fun report(chain: List<String>, program: DispatchProgram) {
        val outcome = when (val o = program.outcome) {
            is Outcome.Value -> "value"
            is Outcome.InvokeCode -> "invoke"
            is Outcome.InvokeSyscall -> "syscall ${o.syscall.name}"
        }
        val guards = if (program.guards.isEmpty()) ""
                     else " (${program.guards.size} guards)"
        System.err.println("[dispatch] " + chain.joinToString(" -> ") + " => " +
            outcome + guards)
    }

    private fun resumeCallback(tc: ThreadContext, dispatcher: Dispatcher): DispatchCallback =
        dispatcher.resume
            ?: throw ExceptionHandling.dieInternal(tc,
                "Dispatcher '$dispatcher' has no resume callback")

    private fun invokeCallback(tc: ThreadContext, record: DispatchRecord,
                               callback: DispatchCallback, capture: SixModelObject) {
        when (callback) {
            is DispatchCallback.Builtin -> callback.run(record, capture)
            is DispatchCallback.Code -> {
                tc.pendingDispatch = record
                try {
                    Ops.invokeDirect(tc, callback.code, Ops.invocantCallSite,
                        arrayOf<Any?>(capture))
                }
                finally {
                    tc.pendingDispatch = null
                }
            }
        }
    }

    /* ----- running an installed program ----- */

    /**
     * Tries to run a program: checks that it applies to these arguments and, if
     * it does, carries out its outcome. Returns false if a guard failed, in
     * which case nothing has been done.
     */
    private fun run(tc: ThreadContext, program: DispatchProgram, descriptor: CallSiteDescriptor,
                    args: Array<Any?>, site: DispatchCallSite?,
                    bindFailureOf: DispatchRecord? = null): Boolean {
        val record = DispatchRecord(tc, null, descriptor, args, tc.curFrame, site)
        record.program = program
        record.endRecording()

        if (!Captures.sameShape(program.descriptor, descriptor)) return false
        if (!program.guardsMatch(record)) return false
        if (program.isResuming && !enterResumptions(tc, record, program, bindFailureOf))
            return false

        tc.dispatchRecords.add(record)
        try {
            realize(tc, record, program.outcome)
        }
        finally {
            tc.dispatchRecords.removeAt(tc.dispatchRecords.size - 1)
        }
        return true
    }

    /**
     * Walks a resuming program through the resumptions it was recorded against,
     * checking at each level that we found the dispatcher it expects and that
     * the guards it recorded there hold.
     */
    private fun enterResumptions(tc: ThreadContext, record: DispatchRecord,
                                 program: DispatchProgram,
                                 bindFailureOf: DispatchRecord?): Boolean {
        for ((index, level) in program.resumeLevels.withIndex()) {
            val found = if (index == 0 && bindFailureOf != null)
                    innermostResumption(tc, bindFailureOf)
                else
                    findResumption(tc, program.resumeKind, index)
            if (found == null) return false
            if (found.spec.dispatcher !== level.dispatcher) return false
            if (!Captures.sameShape(found.spec.initArgs.descriptor, level.initDescriptor))
                return false
            record.pushLevel(found)
            for (guard in level.guards)
                if (!guard.check(record)) return false
            val newState = level.newState
            if (newState != null)
                found.state.state = newState.evaluate(record).obj
            if (level.requireNoFurther && findResumption(tc, program.resumeKind, index + 1) != null)
                return false
        }
        return true
    }

    /* ----- carrying out an outcome ----- */

    private fun realize(tc: ThreadContext, record: DispatchRecord, outcome: Outcome) {
        when (outcome) {
            is Outcome.Value ->
                setResult(tc, record, outcome.source.evaluate(record))
            is Outcome.InvokeSyscall -> {
                val args = outcome.args.evaluate(record)
                setResult(tc, record,
                    outcome.syscall.call(tc, outcome.args.descriptor, args))
            }
            is Outcome.InvokeCode -> {
                val callee = outcome.callee.evaluate(record).obj
                val args = outcome.args.evaluate(record)
                tc.pendingDispatch = record
                try {
                    Ops.invokeDirect(tc, callee, outcome.args.descriptor, args)
                }
                catch (failure: BindFailureException) {
                    if (failure.record !== record) throw failure
                    resumeAfterBindFailure(tc, record, failure.flag)
                }
                finally {
                    tc.pendingDispatch = null
                }
            }
        }
    }

    /** Leaves a value where the code after the dispatch instruction will find it. */
    @JvmStatic
    fun setResult(tc: ThreadContext, record: DispatchRecord, value: DispatchValue) {
        val frame = record.callerFrame ?: tc.frame
        when (value.kind) {
            ArgKind.OBJ -> {
                frame.oRet = value.obj
                frame.retType = CallFrame.RET_OBJ.toByte()
            }
            ArgKind.INT -> {
                frame.iRet = value.value as Long
                frame.retType = CallFrame.RET_INT.toByte()
            }
            ArgKind.UINT -> {
                frame.iRet = value.value as Long
                frame.retType = CallFrame.RET_UINT.toByte()
            }
            ArgKind.NUM -> {
                frame.nRet = value.value as Double
                frame.retType = CallFrame.RET_NUM.toByte()
            }
            ArgKind.STR -> {
                frame.sRet = value.value as String?
                frame.retType = CallFrame.RET_STR.toByte()
            }
        }
    }

    /* ----- finding a dispatch to resume ----- */

    /**
     * Looks down the callstack for a dispatch that can be resumed, passing over
     * the given number of resumptions that have already been used up.
     *
     * MoarVM walks one stack with frames and dispatch records interleaved on
     * it. Here the two live apart, so the interleaving is reconstructed: a
     * dispatch sits immediately above the frame its instruction is in, so
     * before visiting a frame we visit the dispatches whose instruction is in
     * it, innermost first. That puts a dispatch between the frame it invoked
     * and the frame it was made from, which is the order MoarVM sees.
     *
     * The frame we are in is never a place to resume from, and asking for the
     * caller's resumption passes over one frame more.
     */
    @JvmStatic
    fun findResumption(tc: ThreadContext, kind: ResumeKind, exhausted: Int): FoundResumption? {
        val records = tc.dispatchRecords
        var next = records.size - 1
        var toSkip = kind.framesToSkip
        var remaining = exhausted
        var frame = tc.curFrame
        while (frame != null) {
            while (next >= 0 && records[next].callerFrame === frame) {
                val record = records[next--]
                if (toSkip == 0) {
                    val program = record.program
                    if (program != null) {
                        if (program.resumptions.size > remaining)
                            return FoundResumption(record, program.resumptions[remaining],
                                record.ensureResumeStates()[remaining])
                        remaining -= program.resumptions.size
                    }
                    /* A dispatch with resumptions of its own that we did not
                     * use is as far as we look. Ones with none, and ones that
                     * are themselves resuming something, are looked past. */
                    if (!(program == null || program.resumptions.isEmpty() ||
                            program.isResuming))
                        return null
                }
            }
            if (toSkip > 0) toSkip--
            frame = frame.caller
        }
        return null
    }

    /**
     * A frame a dispatch invoked failed to bind its signature, and the dispatch
     * asked for that to become a resumption. Resume it with the flag it named.
     */
    @JvmStatic
    fun resumeAfterBindFailure(tc: ThreadContext, failed: DispatchRecord, flag: Long) {
        val args = arrayOf<Any?>(flag)
        val cached = failed.program!!.bindFailureProgram
        if (cached != null &&
                run(tc, cached, BindFailure.flagCallSite, args, null, failed))
            return
        record(tc, null, BindFailure.flagCallSite, args, null, failed)
    }

    /** The first resumption a dispatch set up, which is the one to resume. */
    private fun innermostResumption(tc: ThreadContext, record: DispatchRecord): FoundResumption {
        val program = record.program
        if (program == null || program.resumptions.isEmpty())
            throw ExceptionHandling.dieInternal(tc,
                "A dispatch that asked to resume on bind failure set up no resumption")
        return FoundResumption(record, program.resumptions[0], record.ensureResumeStates()[0])
    }

    /** Starts a resumption of the innermost resumable dispatch out from here. */
    @JvmStatic
    fun recordResume(tc: ThreadContext, record: DispatchRecord, capture: SixModelObject,
                     kind: ResumeKind) {
        val found = findResumption(tc, kind, 0)
        if (found == null) {
            /* No resumable dispatch; the language may have something to say
             * about that, otherwise it is an error. */
            val handler = tc.frame.codeRef.staticInfo.compUnit.hllConfig.resumeErrorDispatcher
            if (handler != null)
                record.delegate(tc.gc.dispatchers.find(tc, handler), capture)
            else
                throw ExceptionHandling.dieInternal(tc, "No resumable dispatch in dynamic scope")
            return
        }
        record.startResume(found, kind)
        record.outcome = RecordedOutcome.Resume(capture)
    }
}
