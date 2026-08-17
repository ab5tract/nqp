package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/**
 * Thrown to abandon a frame whose signature binding failed, when the dispatch
 * that invoked it asked for that to become a resumption. Being a control
 * exception means each frame it passes through leaves cleanly and rethrows.
 */
class BindFailureException(val record: DispatchRecord, val flag: Long) : ControlException()

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
        val record = frame.dispatchRecord
        val control = record?.program?.bindControl
        if (record == null || control == null)
            throw ExceptionHandling.dieInternal(tc, "Bind check failed")

        /* NOTE: MoarVM returns from the frame without running its exit
         * handlers here. On this backend the frames are left by unwinding a
         * control exception, which does run them. */
        throw BindFailureException(record, control.failureFlag)
    }
}
