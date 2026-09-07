package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

/**
 * Thrown to abandon a frame whose signature binding failed, when the dispatch
 * that invoked it asked for that to become a resumption. Being a control
 * exception means each frame it passes through leaves cleanly and rethrows.
 */
class BindFailureException(val record: DispatchRecord, val flag: Long) : ControlException()

/**
 * Thrown to abandon a frame whose signature binding failed, when the
 * language's bind_error handler produced a result value in its place --
 * the way it does when the failure was a Junction argument and the call
 * was autothreaded. The nearest invoker (Ops.invokeDirect) catches this
 * and makes the value the call's result, standing in for MoarVM's
 * special-return from MVM_args_bind_failed.
 */
class BindReturnException(val value: org.raku.nqp.sixmodel.SixModelObject?) : ControlException()

/**
 * Signature binding failure, as reported by nqp::assertparamcheck.
 *
 * A dispatch can ask (with dispatcher-resume-on-bind-failure) for a bind
 * failure of what it invoked to come back to it as a resumption instead of
 * being an error. That is how a multiple dispatch tries a candidate and moves
 * on to the next one when the arguments do not fit.
 */
object BindFailure {
    /** The callsite a bind failure resumption is entered with: just the flag. */
    @JvmField val flagCallSite = CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_INT), null)

    /** Called when a signature bind check failed. */
    @JvmStatic
    fun failed(tc: ThreadContext) {
        val frame = tc.frame
        val record = frame.invokingDispatch()
        val control = record?.program?.bindControl
        if (record == null || control == null)
            reportToHLL(tc)

        /* NOTE: MoarVM returns from the frame without running its exit
         * handlers here. On this backend the frames are left by unwinding a
         * control exception, which does run them. */
        throw BindFailureException(record, control.failureFlag)
    }

    /**
     * Called by the bindcomplete op when a frame's signature binding has
     * succeeded. A no-op unless the dispatch that invoked the frame asked
     * (with dispatcher-resume-after-bind) for bind success to become a
     * resumption too, in which case the frame is abandoned -- its body never
     * runs -- and the dispatch resumes with the success flag, the same road
     * a bind failure travels with the failure flag.
     */
    @JvmStatic
    fun complete(tc: ThreadContext) {
        val frame = tc.frame
        val control = frame.invokingProgram()?.bindControl ?: return
        if (control.onSuccessToo)
            throw BindFailureException(frame.invokingDispatch()!!, control.successFlag!!)
    }

    /**
     * No dispatch wanted the failure, so it is an error. Hand the arguments
     * the frame was entered with to the language's bind_error handler, which
     * re-runs the binder to say which parameter did not match and why. Only
     * frames that take an args array keep their arguments, which is why a
     * parameter that can fail a check forces that route.
     */
    /**
     * As reportToHLL, for a frame-free callee: it has no frame to read the
     * callsite, arguments and code object from, so the direct road that
     * entered it passes them (jesp diamond 5). Answers the value the
     * handler produced in place of the call (a Junction autothread), which
     * the caller stores as the call's result; throws otherwise.
     */
    @JvmStatic
    fun reportFrameFree(tc: ThreadContext, cr: org.raku.nqp.runtime.CodeRef,
                        csd: CallSiteDescriptor?, args: Array<Any?>?): org.raku.nqp.sixmodel.SixModelObject? {
        val config = cr.staticInfo.compUnit.hllConfig
        val handler = config.bindError
        val code = cr.codeObject
        if (handler != null && csd != null && args != null && code != null) {
            Ops.invokeDirect(tc, handler, captureCallSite,
                arrayOf<Any?>(Ops.savecapture(tc, csd, args), code))
            val produced = Ops.result_o(tc.frame)
            if (produced != null)
                return produced
        }
        throw ExceptionHandling.dieInternal(tc, "Bind check failed")
    }

    private fun reportToHLL(tc: ThreadContext): Nothing {
        val frame = tc.frame
        val config = frame.codeRef.staticInfo.compUnit.hllConfig
        val handler = config.bindError
        val csd = frame.csd
        val args = frame.args
        val code = frame.codeRef.codeObject
        if (handler != null && csd != null && args != null && code != null) {
            Ops.invokeDirect(tc, handler, captureCallSite,
                arrayOf<Any?>(Ops.savecapture(tc, csd, args), code))
            /* The handler throwing is the error case. Returning a value means
             * it stood in for the call -- a Junction argument was autothreaded
             * -- and that value is the failed call's result. */
            val produced = Ops.result_o(frame)
            if (produced != null)
                throw BindReturnException(produced)
        }
        throw ExceptionHandling.dieInternal(tc, "Bind check failed")
    }

    /**
     * The bind_error handler takes the capture and the code object whose
     * binding failed. MoarVM passes only the capture and has the handler dig
     * the routine out of the caller; handing it over is less to go wrong.
     */
    private val captureCallSite = CallSiteDescriptor(
        byteArrayOf(CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ), null)
}
