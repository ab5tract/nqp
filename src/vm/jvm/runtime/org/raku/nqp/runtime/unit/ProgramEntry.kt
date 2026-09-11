package org.raku.nqp.runtime.unit

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeEngines
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ControlException
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ResumeStatus
import org.raku.nqp.runtime.ThreadContext

/**
 * The one body every artifact block has: what the per-block stub the
 * deleted class road emitted did around codeRunIdx (its prelude and
 * postlude), as a single function. Its handle has the shape
 * StaticCodeInfo's init expects of a bound stub, (tc, cr, csd, resume,
 * args), so the invoke road and the resume surgery are untouched;
 * `resume` is unused because an engine program resumes through its own
 * handle (NqpCodeEngine.RESUME).
 */
object ProgramEntry {
    @JvmStatic
    fun enter(tc: ThreadContext, cr: CodeRef, csd: CallSiteDescriptor,
              @Suppress("UNUSED_PARAMETER") resume: ResumeStatus.Frame?, args: Array<Any?>?) {
        val sci = cr.staticInfo
        val cf = CallFrame(tc, cr)
        try {
            CodeEngines.codeRunUnit(sci, sci.compUnit, tc, cf, csd, args)
        } catch (e: ControlException) {
            cf.leaveThrough(e)
            throw e
        } catch (e: Throwable) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        cf.leave()
    }

    @JvmField
    val ENTER: MethodHandle = MethodHandles.lookup().findStatic(
        ProgramEntry::class.java, "enter",
        MethodType.methodType(Void.TYPE, ThreadContext::class.java, CodeRef::class.java,
            CallSiteDescriptor::class.java, ResumeStatus.Frame::class.java, Array<Any?>::class.java))
}
