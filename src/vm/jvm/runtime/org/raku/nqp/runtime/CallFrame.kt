package org.raku.nqp.runtime

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.dispatch.DispatchCallSite
import org.raku.nqp.dispatch.DispatchProgram
import org.raku.nqp.dispatch.DispatchRecord
import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Represents a call frame that is currently executing, and holds state
 * relating to it. Call frames are created by the callee after arguments are
 * passed in but before argument checking.
 */
class CallFrame : Cloneable {
    companion object {
        /**
         * The frame a code ref's block reads its outer lexicals from: the
         * captured outer when the code ref has one, else -- while the outer
         * block has a live invocation somewhere -- the nearest such frame on
         * the caller chain, else the outer block's prior invocation. The
         * constructor uses this (and auto-closes when it answers null); a
         * frame-free block's outer lexical read uses it directly, having no
         * frame of its own to walk from.
         *
         * The caller-chain search can only succeed while the outer block has
         * a live invocation; see StaticCodeInfo.liveInvocations. Measured
         * over a module compile: ~1.1 million searches, 77 callers deep on
         * average, zero successes -- the code refs are methods of precompiled
         * classes whose outer mainline exited long ago.
         */
        @JvmStatic
        fun outerFor(tc: ThreadContext, cr: CodeRef): CallFrame? {
            cr.outer?.let { return it }
            val wanted = cr.staticInfo.outerStaticInfo ?: return null
            if (wanted.liveInvocations.get() > 0) {
                var checkFrame = tc.curFrame
                while (checkFrame != null) {
                    if (checkFrame.codeRef.staticInfo.mh === wanted.mh &&
                            checkFrame.codeRef.staticInfo.compUnit === wanted.compUnit)
                        return checkFrame
                    checkFrame = checkFrame.caller
                }
            }
            return wanted.priorInvocation
        }

        /**
         * A frame that holds a scope but was never invoked, whose outer is
         * the nearest live instance of the static frame it belongs inside
         * (auto-closing one if there is none). This is what a phaser needs
         * when it may run without its enclosing block ever being entered.
         */
        @JvmStatic
        fun contextOnly(tc: ThreadContext, sci: StaticCodeInfo): CallFrame =
            CallFrame(tc, sci)

        const val RET_OBJ = 0
        const val RET_INT = 1
        const val RET_NUM = 2
        const val RET_STR = 3
        const val RET_UINT = 10

        /**
         * Marks a clone-flagged lexical slot whose static value has not
         * been cloned into the frame yet. A distinct marker rather than
         * null because null is also a value: a lexical explicitly bound
         * to null (a cache-miss result, say) must read back as null, not
         * resurrect the static clone. Binds overwrite the marker without
         * having to know it exists.
         */
        @JvmField val UNVIVIFIED: SixModelObject = object : SixModelObject() {}

        // Does work needed to leave this callframe.
        private val exitHandlerCallSite = CallSiteDescriptor(
            byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
    }

    /**
     * The thread context that created this call frame. (lateinit for the
     * fake-up empty constructor; every real path assigns it.)
     */
    lateinit var tc: ThreadContext

    /**
     * The next entry in the static (lexical) chain. Generated code reads this
     * field directly when it walks the chain, so it has to stay a field.
     */
    @JvmField var outer: CallFrame? = null

    /**
     * The next entry in the dynamic (caller) chain.
     */
    @JvmField var caller: CallFrame? = null

    /**
     * The code reference for this frame.
     */
    lateinit var codeRef: CodeRef

    /**
     * Lexical storage, by type.
     */
    @JvmField var oLex: Array<SixModelObject?>? = null
    @JvmField var iLex: LongArray? = null
    @JvmField var nLex: DoubleArray? = null
    @JvmField var sLex: Array<String?>? = null

    /**
     * Return value storage. Note that all the basic types are available and
     * the returning function picks the one it has.
     */
    @JvmField var oRet: SixModelObject? = null
    @JvmField var iRet: Long = 0
    @JvmField var nRet: Double = 0.0
    @JvmField var sRet: String? = null

    /**
     * Flag for what return type we have.
     */
    @JvmField var retType: Byte = 0

    /**
     * The current handler we're in, in this block. 0 if none.
     */
    @JvmField var curHandler: Long = 0

    /**
     * Current working copy of the named arguments data.
     */
    @JvmField var workingNameMap: Object2IntOpenHashMap<String>? = null

    /**
     * Serialization context this frame is associated with, if any.
     */
    @JvmField var sc: SerializationContext? = null

    /**
     * This this invocation do the initial setup of state vars?
     */
    @JvmField var stateInit = false

    /**
     * Flags that this frame should leave immediately upon unwinding from the
     * current exception handler.
     */
    @JvmField var exitAfterUnwind = false

    /**
     * The call site descriptor of the callsite that invoked us,
     * possibly exploded. Not set by default, only for things that
     * do custom binding and want to keep this around.
     */
    @JvmField var csd: CallSiteDescriptor? = null

    /**
     * The arguments passed to this call. Not set by default, only
     * for things that do custom binding and want to keep this around.
     */
    @JvmField var args: Array<Any?>? = null

    /**
     * The dispatch that invoked this frame, if a dispatch did. Walking the
     * caller chain and reading this at each step gives the interleaving of
     * frames and dispatches that a resumption is looked for in.
     */
    @JvmField var dispatchRecord: DispatchRecord? = null

    /**
     * The same dispatch, carried lazily: a settled program replayed by the
     * engine or the compiled chain leaves these on ThreadContext for the
     * frame it invokes, and no DispatchRecord exists until invokingDispatch()
     * is asked for one. See ThreadContext.pendingProgram.
     */
    @JvmField var dispatchProgram: DispatchProgram? = null
    @JvmField var dispatchArgs: Array<Any?>? = null
    @JvmField var dispatchSite: DispatchCallSite? = null

    /**
     * The program of the dispatch that invoked this frame, if a settled one
     * did; null for no dispatch and for one still recording. Answers what
     * the bind-control questions ask without materializing a record.
     */
    fun invokingProgram(): DispatchProgram? {
        val record = dispatchRecord
        return if (record != null) record.program else dispatchProgram
    }

    /**
     * The dispatch that invoked this frame as a record, materialized from
     * the carried program on first need and kept, so that the resume states
     * a resumption creates on it survive for the next resumption.
     */
    fun invokingDispatch(): DispatchRecord? {
        var record = dispatchRecord
        if (record == null) {
            val program = dispatchProgram ?: return null
            record = DispatchRecord(tc, null, program.descriptor, dispatchArgs!!, caller, dispatchSite)
            record.program = program
            record.endRecording()
            dispatchRecord = record
        }
        return record
    }

    // Empty constructor for things that want to fake one up.
    constructor()

    // Normal constructor.
    constructor(tc: ThreadContext, cr: CodeRef) {
        this.tc = tc
        this.codeRef = cr
        this.caller = tc.curFrame

        // Claim the dispatch that is invoking us, if one is.
        val pendingDispatch = tc.pendingDispatch
        if (pendingDispatch != null) {
            this.dispatchRecord = pendingDispatch
            tc.pendingDispatch = null
        }
        else {
            val pendingProgram = tc.pendingProgram
            if (pendingProgram != null) {
                this.dispatchProgram = pendingProgram
                this.dispatchArgs = tc.pendingArgs
                this.dispatchSite = tc.pendingSite
                tc.pendingProgram = null
                tc.pendingArgs = null
                tc.pendingSite = null
            }
        }

        // Set outer; if it's explicitly in the code ref, use that. If not,
        // go hunting for one. Fall back to outer's prior invocation.
        val sci = cr.staticInfo
        this.outer = outerFor(tc, cr)
        if (this.outer == null) {
            val wanted = sci.outerStaticInfo
            if (wanted != null) this.autoClose(wanted)
        }

        // Set up lexical storage.
        if (sci.oLexicalNames != null) {
            val oLexStatic = sci.oLexStatic!!
            val numoLex = oLexStatic.size
            val oLex = arrayOfNulls<SixModelObject>(numoLex)
            this.oLex = oLex
            for (i in 0 until numoLex) {
                when (sci.oLexStaticFlags!![i].toInt()) {
                    0 ->
                        oLex[i] = oLexStatic[i]
                    1 -> {
                        /* Cloned on first read (oLexOrVivify). The lazy
                         * timing is load-bearing, not an optimization:
                         * see that method. */
                        if (oLexStatic[i] != null)
                            oLex[i] = UNVIVIFIED
                    }
                    2 -> {
                        var oLexState = cr.oLexState
                        if (oLexState == null) {
                            oLexState = arrayOfNulls(oLexStatic.size)
                            cr.oLexState = oLexState
                            this.stateInit = true
                        }
                        if (oLexState[i] == null) {
                            val cloned = oLexStatic[i]!!.clone(tc)
                            oLexState[i] = cloned
                            oLex[i] = cloned
                        }
                        else
                            oLex[i] = oLexState[i]
                    }
                }
            }
        }
        sci.iLexicalNames?.let { this.iLex = LongArray(it.size) }
        sci.nLexicalNames?.let { this.nLex = DoubleArray(it.size) }
        sci.sLexicalNames?.let { this.sLex = arrayOfNulls(it.size) }

        if (sci.contextsAwaitingOuter != null)
            adoptWaitingContexts(sci)

        /* Note this invocation while it is still live, not only in leave():
         * another thread resolving the outer of a closure declared in this
         * scope (an END phaser run by a dying thread, say) walks its own
         * caller chain, misses, and would otherwise auto-close a fresh empty
         * frame while the real one is still running here. */
        sci.priorInvocation = this
        sci.liveInvocations.incrementAndGet()

        // Current call frame becomes this new one.
        tc.curFrame = this
    }

    // Constructor supporting auto-close.
    private constructor(tc: ThreadContext, sci: StaticCodeInfo) {
        this.tc = tc

        // Figure out a code ref.
        val staticCode = sci.staticCode
        if (staticCode is CodeRef) {
            this.codeRef = staticCode
        }
        else {
            val ispec = staticCode!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                this.codeRef = staticCode.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else
                this.codeRef = ispec.InvocationHandler as CodeRef
        }

        // Set outer.
        val wanted = sci.outerStaticInfo
        if (wanted != null) {
            var checkFrame = tc.curFrame
            while (checkFrame != null) {
                if (checkFrame.codeRef.staticInfo.mh === wanted.mh &&
                        checkFrame.codeRef.staticInfo.compUnit === wanted.compUnit) {
                    this.outer = checkFrame
                    break
                }
                checkFrame = checkFrame.caller
            }
            if (this.outer == null)
                this.outer = wanted.priorInvocation
            if (this.outer == null)
                this.autoClose(wanted)
        }

        // Set up lexical storage.
        if (sci.oLexicalNames != null)
            this.oLex = sci.oLexStatic!!.clone()
        sci.iLexicalNames?.let { this.iLex = LongArray(it.size) }
        sci.nLexicalNames?.let { this.nLex = DoubleArray(it.size) }
        sci.sLexicalNames?.let { this.sLex = arrayOfNulls(it.size) }

        if (sci.contextsAwaitingOuter != null)
            adoptWaitingContexts(sci)

        /* As above: visible to other threads while live. */
        sci.priorInvocation = this
    }

    /**
     * Sorts out the outer of a context that came back from deserialization
     * with nothing serialized as its outer. The frame it belongs inside is
     * usually the mainline of the compilation unit being loaded, which has
     * not run yet - the serialization context is read on the way in - so in
     * that case the context is parked on the enclosing code and picked up
     * when a frame for it is created.
     */
    fun resolveDeserializedOuter() {
        if (outer != null)
            return

        val sci = codeRef.staticInfo

        // An earlier live invocation of this same code already knows.
        val priorOuter = sci.priorInvocation?.outer
        if (priorOuter != null) {
            outer = priorOuter
            return
        }

        val wanted = sci.outerStaticInfo ?: return

        val alreadyRun = wanted.priorInvocation
        if (alreadyRun != null) {
            outer = alreadyRun
            return
        }

        val waiting = wanted.contextsAwaitingOuter
            ?: ArrayList<CallFrame>().also { wanted.contextsAwaitingOuter = it }
        waiting.add(this)
    }

    /**
     * Hands this frame to any deserialized contexts that have been waiting
     * for the scope it runs to show up.
     */
    private fun adoptWaitingContexts(sci: StaticCodeInfo) {
        val waiting = sci.contextsAwaitingOuter ?: return
        sci.contextsAwaitingOuter = null
        for (ctx in waiting) {
            if (ctx.outer == null)
                ctx.outer = this
        }
    }

    /**
     * The object lexical at idx, vivifying a clone-flagged static value on
     * its first read, as MoarVM's MVM_frame_vivify_lexical does. The lazy
     * timing is semantics, not thrift: a P6opaque clone is shallow, so a
     * clone taken after a BEGIN-compiled closure has written through the
     * master container shares the master's storage, while an entry-time
     * clone of the still-empty master shares nothing. Rakudo's traited
     * variables (Variable.willdo phasers) depend on the former. Every
     * reader of oLex slots must come through here (or bind first).
     */
    fun oLexOrVivify(idx: Int): SixModelObject? {
        val oLex = this.oLex ?: return null
        val existing = oLex[idx]
        if (existing !== UNVIVIFIED)
            return existing
        val vivified = codeRef.staticInfo.oLexStatic!![idx]!!.clone(tc)
        oLex[idx] = vivified
        return vivified
    }

    fun autoClose(wanted: StaticCodeInfo) {
        val closed = CallFrame(tc, wanted)
        this.outer = closed
        wanted.priorInvocation = closed
    }

    /** Set by leave(), leaveTorn() or leaveSuspended(): the live-invocation
     *  count is given back exactly once per frame, by whichever road gets
     *  there first. NOT the exit-handler flag -- a frame packed into a
     *  continuation gives its count back at the save but has not exited, so
     *  its handler is still owed; see exitHandlerRun. */
    @JvmField var left = false

    /** Set by leave() or leaveTorn(): the exit handler runs exactly once,
     *  at the frame's REAL exit (normal or torn), never on the save road. */
    private var exitHandlerRun = false

    /**
     * The unwinder tears this frame past without running its postlude (an
     * exception's target is a handler further out). Raku runs LEAVE, UNDO
     * and POST on an exceptional exit, so the exit handler runs here with
     * the result ABSENT -- the HLL's null value, which is what MoarVM's
     * unwind hands it (VMNull, hllized) -- and then the live-invocation
     * count is given back (so CallFrame.<init>'s outer-resolution search
     * does not keep hunting for outers that have already exited).
     * `exitHandlerRun` makes the handler one-shot with leave(): a frame the
     * engine road also leaves as the unwind passes through it runs its
     * handler once, here, with the absent result, rather than twice or with
     * the caller's stale return register. `left` separately keeps the count
     * one-shot, so a frame whose count was already given back on the save
     * road (leaveSuspended) still gets its handler here. tc.curFrame is
     * restored after the handler: the unwind continues to its target. An
     * exception the handler throws replaces the in-flight one (the phaser's
     * exception wins, as on MoarVM).
     */
    fun leaveTorn() {
        if (exitHandlerRun) return
        exitHandlerRun = true
        val sci = codeRef.staticInfo
        if (!left) {
            left = true
            sci.liveInvocations.decrementAndGet()
        }
        if (sci.hasExitHandler) {
            /* The unwind is still in flight and continues to its target
             * once the handler has run, so this frame is put back. */
            val origCur = tc.curFrame
            tc.curFrame = this
            try {
                runExitHandler(sci, sci.compUnit.hllConfig.nullValue)
            } finally {
                tc.curFrame = origCur
            }
        }
    }

    fun leave() {
        val sci = this.codeRef.staticInfo
        sci.priorInvocation = this
        /* Read before the block below mutates it: true means the torn walk
         * already ran this frame's exit handler, with the absent result. */
        val alreadyRun = exitHandlerRun
        exitHandlerRun = true
        if (!left) {
            left = true
            sci.liveInvocations.decrementAndGet()
        }
        if (sci.hasExitHandler && !alreadyRun)
            runExitHandler(sci, Ops.result_o(this.caller!!))
        this.tc.curFrame = this.caller
    }

    /**
     * The frame is being packed into a continuation, not exited: give the
     * live-invocation count back (once -- a frame can be saved, resumed and
     * saved again) and restore tc.curFrame, exactly as leave() does, but do
     * NOT run the exit handler. The save road is not an exit: Raku's LEAVE,
     * KEEP and UNDO belong to the frame's real exit, with the real result,
     * which comes later on the resume road (leave()) or when the unwinder
     * tears the frame past (leaveTorn()). Calling leave() here instead --
     * as the engine's save sites did -- consumed the frame's single handler
     * run at the first `take`, with the caller's stale return register as
     * the resultish, and left the real exit silent.
     */
    fun leaveSuspended() {
        val sci = this.codeRef.staticInfo
        sci.priorInvocation = this
        if (!left) {
            left = true
            sci.liveInvocations.decrementAndGet()
        }
        this.tc.curFrame = this.caller
    }

    /**
     * A control exception is passing out through this frame: leave it the
     * way that exception means. A SaveStackException is a continuation
     * capture -- the frame is being packed away, not exited, and every
     * road that packs a frame throws one -- so it takes the save road;
     * any other control exception really does leave the frame. Named once
     * here because all four sites that leave a frame on a control throw
     * (the two engine entries, the artifact block's entry, the resume
     * road) have to agree.
     */
    fun leaveThrough(ce: ControlException) {
        if (ce is SaveStackException) leaveSuspended() else leave()
    }

    /**
     * Run this frame's HLL exit handler (Raku's LEAVE/KEEP/UNDO/POST) with
     * [result] as the frame's resultish -- the real return value on a normal
     * exit, the HLL's null value when the unwinder tore the frame past. A
     * fresh unwinder for the handler's own control flow, and the in-flight
     * one back afterwards even if the handler throws.
     */
    private fun runExitHandler(sci: StaticCodeInfo, result: SixModelObject?) {
        val origUnwinder = tc.unwinder
        try {
            tc.unwinder = UnwindException()
            Ops.invokeDirect(tc, sci.compUnit.hllConfig.exitHandler, exitHandlerCallSite,
                arrayOf<Any?>(this.codeRef, result))
        } finally {
            tc.unwinder = origUnwinder
        }
    }

    /* Package-private in Java; Kotlin has no package visibility, and
     * Ops.continuationclone calls it. */
    fun cloneContinuation(): CallFrame? {
        return try {
            clone() as CallFrame
        } catch (e: CloneNotSupportedException) {
            null
        }
    }
}
