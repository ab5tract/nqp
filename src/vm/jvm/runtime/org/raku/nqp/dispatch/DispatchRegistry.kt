package org.raku.nqp.dispatch

import java.util.concurrent.ConcurrentHashMap

import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * The callback that a dispatcher runs. Dispatchers provided by the runtime run
 * as Kotlin code; those a language registers run as bytecode.
 */
sealed interface DispatchCallback {
    class Builtin(val name: String,
                  val run: (DispatchRecord, SixModelObject) -> Unit) : DispatchCallback

    class Code(val code: SixModelObject) : DispatchCallback
}

/**
 * A registered dispatcher: the callback run when there is nothing usable in the
 * inline cache at a callsite, plus the callback used when a dispatch it set up
 * is resumed.
 */
class Dispatcher(val id: String, val dispatch: DispatchCallback, val resume: DispatchCallback?) {
    val isResumable: Boolean
        get() = resume != null

    override fun toString() = id
}

/**
 * The set of dispatchers a program can reach. Lookups are lock free; the boot
 * dispatchers are installed when the registry is created and a language adds
 * its own with the dispatcher-register syscall.
 */
class DispatchRegistry {
    private val dispatchers = ConcurrentHashMap<String, Dispatcher>()

    init {
        for (dispatcher in BootDispatchers.all)
            dispatchers.put(dispatcher.id, dispatcher)
    }

    fun find(tc: ThreadContext, id: String?): Dispatcher =
        dispatchers.get(id)
            ?: throw ExceptionHandling.dieInternal(tc, "No dispatcher registered for '$id'")

    fun findOrNull(id: String?): Dispatcher? = dispatchers.get(id)

    /**
     * Registers a dispatcher, replacing any dispatcher of that name. A resume
     * callback that is only a type object counts as not having one, so that
     * a caller can pass one unconditionally.
     */
    fun register(tc: ThreadContext, id: String, dispatch: SixModelObject?, resume: SixModelObject?) {
        if (!isInvokable(dispatch))
            throw ExceptionHandling.dieInternal(tc,
                "The dispatch callback of '$id' must be an invokable object")
        if (resume != null && Guard.isConcrete(resume) && !isInvokable(resume))
            throw ExceptionHandling.dieInternal(tc,
                "The resume callback of '$id' must be an invokable object")
        dispatchers.put(id, Dispatcher(id, DispatchCallback.Code(dispatch!!),
            if (resume != null && Guard.isConcrete(resume)) DispatchCallback.Code(resume)
            else null))
    }

    private fun isInvokable(obj: SixModelObject?): Boolean =
        obj is CodeRef || (obj != null && obj.stInitialized && obj.st.InvocationSpec != null)
}
