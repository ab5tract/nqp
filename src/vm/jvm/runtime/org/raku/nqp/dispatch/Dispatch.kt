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

    /* ----- entry points ----- */

    /**
     * A dispatch from a callsite with an inline cache. The result is left in the
     * frame the dispatch instruction is in, the same way a call leaves it.
     */
    @JvmStatic
    fun dispatch(site: DispatchCallSite, name: String, csIdx: Int, tc: ThreadContext,
                 args: Array<Any?>) {
        val descriptor = descriptorFor(tc, csIdx)
        for (program in site.programs)
            if (run(tc, program, descriptor, args, site)) return
        record(tc, tc.gc.dispatchers.find(tc, name), descriptor, args, site)
    }

    /** A dispatch with no callsite to install anything at. */
    @JvmStatic
    fun dispatchUncached(tc: ThreadContext, name: String, descriptor: CallSiteDescriptor,
                        args: Array<Any?>) {
        record(tc, tc.gc.dispatchers.find(tc, name), descriptor, args, null)
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

    private fun record(tc: ThreadContext, dispatcher: Dispatcher, descriptor: CallSiteDescriptor,
                       args: Array<Any?>, site: DispatchCallSite?) {
        val record = DispatchRecord(tc, dispatcher, descriptor, args, tc.curFrame, site)
        tc.dispatchRecords.add(record)
        try {
            var callback = dispatcher.dispatch
            var capture: SixModelObject = record.initialCapture
            while (true) {
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
            if (site != null && !record.doNotInstall) site.install(program)
            realize(tc, record, program.outcome)
        }
        finally {
            tc.dispatchRecords.removeAt(tc.dispatchRecords.size - 1)
        }
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
                    args: Array<Any?>, site: DispatchCallSite?): Boolean {
        val record = DispatchRecord(tc, null, descriptor, args, tc.curFrame, site)
        record.program = program
        record.endRecording()

        if (!program.guardsMatch(record)) return false
        if (program.isResuming && !enterResumptions(tc, record, program)) return false

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
                                 program: DispatchProgram): Boolean {
        for ((index, level) in program.resumeLevels.withIndex()) {
            val found = findResumption(tc, program.resumeKind, index) ?: return false
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
                /* NOTE: a program may ask for a bind failure of this call to be
                 * mapped to a resumption (dispatcher-resume-on-bind-failure).
                 * The request is recorded on the program, but this backend has
                 * no assertparamcheck to raise it, so nothing acts on it yet. */
                tc.pendingDispatch = record
                try {
                    Ops.invokeDirect(tc, callee, outcome.args.descriptor, args)
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
     * Looks down the callstack for a dispatch that can be resumed, skipping the
     * given number of resumptions that have already been used up.
     *
     * The stack we walk is the frames together with the dispatches that invoked
     * them: a frame reached as the outcome of a dispatch has that dispatch
     * immediately below it. We never consider the frame we are in (nor, for a
     * caller resumption, the one below that).
     */
    @JvmStatic
    fun findResumption(tc: ThreadContext, kind: ResumeKind, exhausted: Int): FoundResumption? {
        var toSkip = if (kind == ResumeKind.CALLER) 2 else 1
        var remaining = exhausted
        var frame = tc.curFrame
        while (frame != null) {
            if (toSkip > 0) toSkip--
            val record = frame.dispatchRecord
            if (record != null && toSkip == 0) {
                val program = record.program
                if (program != null) {
                    if (program.resumptions.size > remaining) {
                        val states = record.ensureResumeStates()
                        return FoundResumption(record, program.resumptions[remaining],
                            states[remaining])
                    }
                    remaining -= program.resumptions.size
                }
                /* A dispatch with resumptions that we did not use is as far as
                 * we look; only ones with none of their own, or ones that are
                 * themselves resuming something, are looked past. */
                if (!(program == null || program.resumptions.isEmpty() || program.isResuming))
                    return null
            }
            frame = frame.caller
        }
        return null
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
