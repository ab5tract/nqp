package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle

import org.raku.nqp.sixmodel.SixModelObject

/** Thrown by continuationcontrol operations to cause all currently executing frames to save their state. */
class SaveStackException(
    /** Tag identifying a specific instance of reset. */
    @JvmField var key: SixModelObject?,
    /** If true, the reset should reinstate itself while running the handler. */
    @JvmField var protect: Boolean,
    /** Handler function passed to control. */
    @JvmField var handler: SixModelObject?,
) : ControlException() {
    /** Topmost frame saved so far. */
    @JvmField var top: ResumeStatus.Frame? = null

    override fun toString(): String =
        "SaveStackException(key=" + (if (key == null) "null" else
            key!!.javaClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(key))) +
        ", protect=" + protect + ", frames=" + run {
            val names = StringBuilder(); var f = top
            while (f != null) {
                names.append(f.callFrame?.codeRef?.name ?: "?").append('<')
                f = f.next
            }
            names.toString()
        } + ")"

    fun pushFrame(resumePoint: Int, method: MethodHandle?, saveSpace: Array<Any?>?, callFrame: CallFrame?): SaveStackException {
        val resolvedMethod = method ?: callFrame!!.codeRef.staticInfo.mhResume
        top = ResumeStatus.Frame(resolvedMethod, resumePoint, saveSpace, callFrame, top)
        if (callFrame != null) callFrame.tc.curFrame = callFrame.caller
        return this
    }
}
