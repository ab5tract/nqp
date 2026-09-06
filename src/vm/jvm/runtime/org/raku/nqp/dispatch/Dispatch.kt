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
    fun dispatch(site: DispatchCallSite, siteClass: Class<*>, name: String, csIdx: Int,
                 tc: ThreadContext, args: Array<Any?>) {
        val d = descriptorForClass(siteClass, csIdx, tc)
        /* Does the descriptor describe THESE arguments? Arity says the
         * descriptor is the wrong one; a type clash with matching arity says
         * the arguments are wrong. Distinguishing those is the whole
         * question. */
        if (!d.hasFlattening) {
            var why: String? = null
            if (args.size < d.numPositionals) why = "ARITY"
            else for (i in 0 until d.numPositionals) {
                val a = args[i]
                val f = d.argFlags[i]
                if (f == CallSiteDescriptor.ARG_STR && a != null && a !is String) { why = "TYPE@" + i; break }
                if (f == CallSiteDescriptor.ARG_OBJ && a is DispatchCallSite) { why = "SITE@" + i; break }
            }
            if (why != null && badReports.incrementAndGet() <= 5) {
                System.err.println("DISPATCH ENTRY BAD[" + why + "] name=" + name +
                    " csIdx=" + csIdx + " numPos=" + d.numPositionals +
                    " flags=" + d.argFlags.joinToString(",") + " nargs=" + args.size +
                    " types=" + args.joinToString(",") { x -> x?.javaClass?.simpleName ?: "null" } +
                    " cu=" + tc.frame.codeRef.staticInfo.compUnit.javaClass.name)
                val emitter = Throwable().stackTrace.getOrNull(1)
                System.err.println("  emitter=" + emitter?.className + "." + emitter?.methodName +
                    " tcFrame=" + (tc.frame.codeRef?.name ?: "<anon>") +
                    "/" + tc.frame.codeRef?.staticInfo?.compUnit?.javaClass?.simpleName?.take(8))
                /* The frame register vs the real Java stack: descriptorFor
                 * trusts tc.curFrame, so when these disagree, whoever left
                 * curFrame stale is the actual bug. */
                var f = tc.curFrame
                var i = 0
                val chain = StringBuilder("curFrame chain:")
                while (f != null && i < 10) {
                    chain.append(' ').append(f.codeRef?.name ?: "<anon>")
                        .append('[').append(f.codeRef?.staticInfo?.compUnit?.javaClass?.simpleName?.take(8) ?: "?")
                        .append(']')
                    f = f.caller
                    i += 1
                }
                System.err.println(chain)
                Throwable("dispatch entry bad").printStackTrace()
            }
        }
        dispatchWithDescriptor(site, name, d, tc, args)
    }

    /**
     * The tail of a compiled callsite target: every compiled program's guards
     * failed, so try whatever programs the chain does not cover and then
     * record afresh. The compiled prefix is skipped, not retried. Only called
     * for callsites whose shape has no flattening, so the descriptor needs no
     * exploding here.
     */
    @JvmStatic
    fun fallback(site: DispatchCallSite, name: String, descriptor: CallSiteDescriptor,
                 compiled: Int, tc: ThreadContext, args: Array<Any?>) {
        val programs = site.programs
        if (programs.size > compiled) {
            val ctx = GuardCheckContext(tc, descriptor, args)
            for (i in compiled until programs.size)
                if (run(tc, ctx, programs[i], site)) return
        }
        record(tc, tc.gc.dispatchers.find(tc, name), descriptor, args, site)
    }

    /** A dispatch at a callsite whose descriptor the caller supplies; used by
     * runtime helpers that keep their own callsite as an inline cache. */
    @JvmStatic
    fun dispatchWithDescriptor(site: DispatchCallSite, name: String,
                               descriptor0: CallSiteDescriptor, tc: ThreadContext,
                               args: Array<Any?>) {
        /* A hot site has a compiled chain; run it directly. Helper-made sites
         * only ever get here (they never invoke the indy target), and some
         * rebuild their descriptor per call, so shape equality has to stand
         * in when identity fails. A chain only exists for a non-flattening
         * shape, so no exploding is needed on this path. */
        val chain = site.chain
        if (chain != null) {
            val static = site.staticDescriptor
            if (descriptor0 === static ||
                    (static != null && Captures.sameShape(descriptor0, static))) {
                chain.invoke(tc, args)
                return
            }
        }
        else if (site.linkedName == null) {
            /* Note the site's constants, so that crossing the heat threshold
             * can compile a chain; see DispatchCompiler. */
            site.staticDescriptor = descriptor0
            site.linkedName = name
        }
        if (++site.heat == DispatchCompiler.threshold)
            site.recompile()
        var descriptor = descriptor0
        var theArgs = args
        /* Flattening is exploded before the dispatch runs, as MoarVM does at
         * its dispatch instructions: the program inspects arguments by
         * position, which only means anything on the flattened form. */
        if (descriptor.hasFlattening) {
            descriptor = descriptor.explodeFlattening(tc, theArgs)
            theArgs = tc.flatArgs!!
        }
        val programs = site.programs
        if (programs.isNotEmpty()) {
            val ctx = GuardCheckContext(tc, descriptor, theArgs)
            for (program in programs)
                if (run(tc, ctx, program, site)) return
        }
        record(tc, tc.gc.dispatchers.find(tc, name), descriptor, theArgs, site)
    }

    /** A dispatch from a callsite too wide for an invokedynamic MethodType
     * (the JVM caps a method descriptor at 255 parameter slots): the compiled
     * code builds the argument array itself, and with no per-instruction
     * callsite every dispatch records. Sites this wide are giant literal
     * argument lists that run once, so the missing cache costs nothing. */
    @JvmStatic
    fun dispatchWide(name: String, csIdx: Int, tc: ThreadContext, args: Array<Any?>) {
        dispatchUncached(tc, name, descriptorFor(tc, csIdx), args)
    }

    /** The wide road with the emitting class passed explicitly (trailing,
     *  so emission appends one ldc); see [descriptorForClass]. The
     *  (tc-frame)-trusting overload above stays for classfiles emitted
     *  before the class argument existed (the bootstrap stage jars). */
    @JvmStatic
    fun dispatchWide(name: String, csIdx: Int, tc: ThreadContext, args: Array<Any?>,
                     siteClass: Class<*>) {
        dispatchUncached(tc, name, descriptorForClass(siteClass, csIdx, tc), args)
    }

    /** A dispatch with no callsite to install anything at. */
    @JvmStatic
    fun dispatchUncached(tc: ThreadContext, name: String, descriptor: CallSiteDescriptor,
                        args: Array<Any?>) {
        var theCsd = descriptor
        var theArgs = args
        if (theCsd.hasFlattening) {
            theCsd = theCsd.explodeFlattening(tc, theArgs)
            theArgs = tc.flatArgs!!
        }
        record(tc, tc.gc.dispatchers.find(tc, name), theCsd, theArgs, null)
    }

    private val badReports = java.util.concurrent.atomic.AtomicInteger()

    private fun descriptorFor(tc: ThreadContext, csIdx: Int): CallSiteDescriptor =
        if (csIdx >= 0)
            tc.frame.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite

    /**
     * The callsite-descriptor table of each compilation-unit class,
     * resolved from the class itself rather than from tc.curFrame: the
     * frame register can be stale at a dispatch (a frame that exited
     * through dieInternal-in-the-catch-arm, or one packed into a
     * continuation), and a csIdx resolved against the wrong unit's table
     * yields an unrelated descriptor -- seen as impossible arity/type
     * skew under race/hyper loads. Descriptors are pure static shape
     * (flags and names), and getCallSites() builds them from constants
     * on a bare instance, so caching per Class pins nothing run-owned.
     */
    private val siteTables = object : ClassValue<Array<org.raku.nqp.runtime.CallSiteDescriptor>>() {
        override fun computeValue(type: Class<*>): Array<org.raku.nqp.runtime.CallSiteDescriptor> =
            (type.getDeclaredConstructor().newInstance()
                as org.raku.nqp.runtime.CompilationUnit).getCallSites()
    }

    private val oldDescRoad = System.getenv("NQP_DISPATCH_OLDDESC") != null

    private fun descriptorForClass(siteClass: Class<*>, csIdx: Int, tc: ThreadContext): CallSiteDescriptor =
        if (csIdx < 0)
            Ops.emptyCallSite
        else if (oldDescRoad)
            tc.frame.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            siteTables.get(siteClass)[csIdx]

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
            } catch (sse: org.raku.nqp.runtime.SaveStackException) {
                /* A continuation capture is crossing this recording. The
                 * recording cannot survive it -- this very Java frame is
                 * not part of the continuation -- so a resumed callback
                 * would hold captures of a dead recording and die far
                 * away in a dispatcher syscall. Refuse here, loudly, the
                 * way MoarVM refuses captures across a dispatch. */
                if (System.getenv("NQP_DISPATCH_DEBUG") != null) {
                    System.err.println("CAPTURE ACROSS RECORDING of " +
                        (record.currentDispatcher?.id ?: dispatcher?.id ?: "?") +
                        " on " + Thread.currentThread().name)
                    Throwable("capture across recording").printStackTrace()
                }
                throw ExceptionHandling.dieInternal(tc,
                    "Cannot capture a continuation across a dispatch recording (" +
                    (record.currentDispatcher?.id ?: dispatcher?.id ?: "?") + ")")
            }
            /* The outcome invocation runs outside the refusal region: the
             * recording has ended, so a capture through the invoked code
             * crosses nothing that validation depends on. */
            realize(tc, record, record.program!!.outcome)
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
                /* Save and restore rather than clear: a dispatcher callback
                 * can itself dispatch, and clearing on the way out of the
                 * INNER one would leave the outer recording with no pending
                 * dispatch -- which is what "Not currently recording a
                 * dispatch program" is. */
                val outer = tc.pendingDispatch
                tc.pendingDispatch = record
                try {
                    Ops.invokeDirect(tc, callback.code, Ops.invocantCallSite,
                        arrayOf<Any?>(capture))
                }
                finally {
                    tc.pendingDispatch = outer
                }
            }
        }
    }

    /* ----- running an installed program ----- */

    /**
     * The arguments of a dispatch, packaged so that a program's applicability
     * can be checked before anything is allocated for actually running it. Only
     * the guards recorded before any resumption level was entered are checked
     * this way, and those cannot reach resumption state.
     */
    private class GuardCheckContext(
        override val tc: ThreadContext,
        override val descriptor: CallSiteDescriptor,
        override val args: Array<Any?>,
    ) : DispatchContext {
        override fun resumeInitArg(level: Int, index: Int): DispatchValue =
            throw ExceptionHandling.dieInternal(tc,
                "Resumption state is not available while checking dispatch guards")

        override fun resumeState(level: Int): SixModelObject? =
            throw ExceptionHandling.dieInternal(tc,
                "Resumption state is not available while checking dispatch guards")
    }

    /**
     * Tries to run a program: checks that it applies to these arguments and, if
     * it does, carries out its outcome. Returns false if a guard failed, in
     * which case nothing has been done. The dispatch record — needed for as
     * long as the outcome runs, so that the dispatch can be resumed — is only
     * made once the program's shape and guards have matched, so an attempt
     * that fails allocates nothing.
     */
    private fun run(tc: ThreadContext, ctx: GuardCheckContext, program: DispatchProgram,
                    site: DispatchCallSite?, bindFailureOf: DispatchRecord? = null): Boolean {
        if (!Captures.sameShape(program.descriptor, ctx.descriptor)) return false
        if (!program.guardsMatch(ctx)) return false

        val record = DispatchRecord(tc, null, ctx.descriptor, ctx.args, tc.curFrame, site)
        record.program = program
        record.endRecording()
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
                found.state.state = newState.evaluateRaw(record) as SixModelObject?
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
                val callee = outcome.callee.evaluateRaw(record) as SixModelObject?
                val args = outcome.args.evaluate(record)
                /* Save and restore, as invokeCallback does: the callee can
                 * dispatch, and clearing on the way out of the INNER dispatch
                 * would leave this one with no pending record -- either
                 * "Not currently recording a dispatch program", or worse, a
                 * later dispatch tracking against the wrong capture. */
                val outer = tc.pendingDispatch
                tc.pendingDispatch = record
                try {
                    Ops.invokeDirect(tc, callee, outcome.args.descriptor, args)
                }
                catch (failure: BindFailureException) {
                    if (failure.record !== record) throw failure
                    resumeAfterBindFailure(tc, record, failure.flag)
                }
                finally {
                    tc.pendingDispatch = outer
                }
            }
        }
    }

    /** Leaves a value where the code after the dispatch instruction will find it. */
    @JvmStatic
    fun setResult(tc: ThreadContext, record: DispatchRecord, value: DispatchValue) {
        setFrameResult(record.callerFrame ?: tc.frame, value)
    }

    /** As setResult, for callers that already have the frame in hand. */
    @JvmStatic
    fun setFrameResult(frame: CallFrame, value: DispatchValue) {
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
                run(tc, GuardCheckContext(tc, BindFailure.flagCallSite, args), cached,
                    null, failed))
            return
        record(tc, null, BindFailure.flagCallSite, args, null, failed)
    }

    /** The first resumption a dispatch set up, which is the one to resume. */
    private fun innermostResumption(tc: ThreadContext, record: DispatchRecord): FoundResumption {
        val program = record.program
        if (program != null && program.resumptions.isNotEmpty())
            return FoundResumption(record, program.resumptions[0], record.ensureResumeStates()[0])
        /* A dispatch that is itself a resumption registers no resumptions of
         * its own: a bind failure of what it invoked (the next candidate
         * failing to bind, after an earlier one already did) re-resumes what
         * it was already resuming, whose state it has been updating. */
        if (record.levels.isNotEmpty())
            return record.levels[0].found
        throw ExceptionHandling.dieInternal(tc,
            "A dispatch that asked to resume on bind failure set up no resumption")
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
