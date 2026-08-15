package org.raku.nqp.runtime

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Represents a call frame that is currently executing, and holds state
 * relating to it. Call frames are created by the callee after arguments are
 * passed in but before argument checking.
 */
class CallFrame : Cloneable {
    companion object {
        const val RET_OBJ = 0
        const val RET_INT = 1
        const val RET_NUM = 2
        const val RET_STR = 3
        const val RET_UINT = 10

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

    // Empty constructor for things that want to fake one up.
    constructor()

    // Normal constructor.
    constructor(tc: ThreadContext, cr: CodeRef) {
        this.tc = tc
        this.codeRef = cr
        this.caller = tc.curFrame

        // Set outer; if it's explicitly in the code ref, use that. If not,
        // go hunting for one. Fall back to outer's prior invocation.
        val sci = cr.staticInfo
        if (cr.outer != null) {
            this.outer = cr.outer
        }
        else {
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
                    1 ->
                        oLex[i] = oLexStatic[i]!!.clone(tc)
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

    fun autoClose(wanted: StaticCodeInfo) {
        val closed = CallFrame(tc, wanted)
        this.outer = closed
        wanted.priorInvocation = closed
    }

    fun leave() {
        val sci = this.codeRef.staticInfo
        sci.priorInvocation = this
        if (sci.hasExitHandler) {
            val origUnwinder = tc.unwinder
            tc.unwinder = UnwindException()
            val hll = sci.compUnit.hllConfig
            Ops.invokeDirect(tc, hll.exitHandler, exitHandlerCallSite,
                arrayOf<Any?>(this.codeRef, Ops.result_o(this.caller!!)))
            tc.unwinder = origUnwinder
        }
        this.tc.curFrame = this.caller
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
