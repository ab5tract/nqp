package org.raku.nqp.runtime

import java.util.Random

import it.unimi.dsi.fastutil.ints.IntArrayList

import org.raku.nqp.dispatch.DispatchRecord
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance
import org.raku.nqp.sixmodel.reprs.SCRefInstance

/**
 * State of a currently running thread.
 */
class ThreadContext(
    /**
     * The global context for the NQP runtime support.
     */
    @JvmField var gc: GlobalContext,
) {
    companion object {
        const val NATIVE_INT = 1
        const val NATIVE_NUM = 2
        const val NATIVE_STR = 3
        const val NATIVE_JVM_OBJ = 4
    }

    /**
     * The current call frame. Null before this thread enters its first frame
     * and again after the outermost one returns, since a frame's return puts
     * this back to its own caller.
     */
    @JvmField var curFrame: CallFrame? = null

    /**
     * The current call frame, for the ops that only ever run inside one.
     */
    val frame: CallFrame
        get() = curFrame ?: throw IllegalStateException("No call frame is running on this thread")

    /**
     * When we wish to access optional parameters, we need to convey
     * if there was a value as well as to supply it. However, the JVM
     * gives no good way to do that (no ref parameters, for example)
     * short of allocating an object, which is overkill. So we use
     * this field to convey if the last optional parameter fetched is
     * valid or not.
     */
    @JvmField var lastParameterExisted = 0

    /**
     * Holds just-processed args in the case we have a flattening.
     */
    @JvmField var flatArgs: Array<Any?>? = null

    /**
     * When we wish to look up or bind native or inlined things in an
     * object, we need a way to pass around some native value. The
     * following set of slots, along with a flag indicating value
     * type, provide a way to do that.
     */
    @JvmField var nativeI = 0L
    @JvmField var nativeN = 0.0
    @JvmField var nativeS: String? = null
    @JvmField var nativeJ: Any? = null
    @JvmField var nativeType = 0

    /**
     * The current unwind exception.
     */
    @JvmField var unwinder: UnwindException = UnwindException()

    /**
     * The last exception payload.
     */
    @JvmField var lastPayload: SixModelObject? = null

    /**
     * Stack of handlers we're currently in.
     */
    @JvmField var handlers = ArrayList<HandlerInfo>()

    /**
     * The currently saved capture for custom processing.
     */
    @JvmField var savedCC: CallCaptureInstance? = null

    /**
     * The dispatches in progress on this thread, innermost last. A dispatch
     * stays here while whatever it invoked is running, which is how a callee
     * finds the dispatch to resume.
     */
    @JvmField val dispatchRecords = ArrayList<DispatchRecord>()

    /**
     * Set around an invocation made on behalf of a dispatch, so that the frame
     * the invocation creates can note which dispatch it came from.
     */
    @JvmField var pendingDispatch: DispatchRecord? = null

    /**
     * The currently set dispatcher, for the next interested call (or the
     * one matching currentDispatcherFor, if set) to take.
     */
    @JvmField var currentDispatcher: SixModelObject? = null
    @JvmField var currentDispatcherFor: SixModelObject? = null

    /**
     * Dispatcher to return control to when current dispatcher exhausts.
     */
    @JvmField var nextDispatcher: SixModelObject? = null
    @JvmField var nextDispatcherFor: SixModelObject? = null

    /**
     * Serialization context write barrier disabled depth (anything non-zero
     * means disabled).
     */
    @JvmField var scwbDisableDepth = 0

    /**
     * Any serialization contexts we are compiling; null if none.
     */
    @JvmField var compilingSCs: ArrayList<SCRefInstance>? = null

    /**
     * A dummy frame into which return values are set when there is no real caller.
     */
    @JvmField var dummyCaller: CallFrame

    /**
     * Object with VMThread REPR used to represent this thread. May be null if we
     * never got around to setting it up yet.
     */
    @JvmField var VMThread: SixModelObject? = null

    /* These were package-private in the Java original; Kotlin has no
     * package visibility, so they are public fields (Ops, ContextKey and
     * the NFA evaluator poke at them directly). */
    @JvmField var hllThreadData: Any? = null
    @JvmField var hllThreadKey: ContextKey<*, *>? = null
    @JvmField var hllThreadAll: HashMap<ContextKey<*, *>, Any?>

    @JvmField var hllGlobalData: Any? = null
    @JvmField var hllGlobalKey: ContextKey<*, *>? = null
    @JvmField var hllGlobalAllCache: HashMap<ContextKey<*, *>, Any?>

    @JvmField var random = Random()

    // odds and ends for nqp
    @JvmField var fates = IntArrayList()
    @JvmField var curst = IntArrayList()
    @JvmField var nextst = IntArrayList()
    @JvmField var curlonglit = LongArray(200)

    init {
        hllThreadAll = HashMap()
        hllGlobalAllCache = HashMap()
        val callCapture = gc.CallCapture
        if (callCapture != null) {
            savedCC = callCapture.st.REPR.allocate(this, callCapture.st) as CallCaptureInstance
        }
        dummyCaller = CallFrame()
    }

    fun resultFrame(): CallFrame {
        return curFrame ?: dummyCaller
    }
}
