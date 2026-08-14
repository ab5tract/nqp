package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.VMExceptionInstance

/**
 * Handler lookup and unwinding for the NQP exception model. Generated
 * bytecode invokes dieInternal(ThreadContext, Throwable) by descriptor
 * ($TYPE_EH in QAST/Compiler.nqp); everything else is called from the
 * runtime and rakudo's Java layer.
 */
object ExceptionHandling {
    /* Exception handler categories. */
    const val EX_CAT_CATCH = 1
    const val EX_CAT_ANY = 2
    const val EX_CAT_NEXT = 4
    const val EX_CAT_REDO = 8
    const val EX_CAT_LAST = 16
    const val EX_CAT_RETURN = 32
    const val EX_CAT_TAKE = 128
    const val EX_CAT_WARN = 256
    const val EX_CAT_SUCCEED = 512
    const val EX_CAT_PROCEED = 1024
    const val EX_CAT_LABELED = 4096
    const val EX_CAT_AWAIT = 8192
    const val EX_CAT_EMIT = 16384
    const val EX_CAT_DONE = 32768

    /* Exception handler kinds. */
    const val EX_UNWIND_SIMPLE = 0
    const val EX_UNWIND_OBJECT = 1
    const val EX_BLOCK = 2

    /* Throws a simple string exception for some internal error, using our own
     * handler model. Note the exception is not resumable. */
    private val stooge = RuntimeException("Stooge exception leaked")
    @Suppress("DEPRECATION", "removal")
    private val death = ThreadDeath()

    private fun dieInternal(tc: ThreadContext, msg: String, t: Throwable?): RuntimeException {
        val exObj: VMExceptionInstance
        if (tc.gc.noisyExceptions) {
            (t ?: Throwable(msg)).printStackTrace()
        }
        try {
            val exType = tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig.exceptionType!!
            exObj = exType.st.REPR.allocate(tc, exType.st) as VMExceptionInstance
            exObj.message = msg
            exObj.category = EX_CAT_CATCH.toLong()
            exObj.origin = tc.curFrame
            exObj.nativeTrace = Throwable().stackTrace
        }
        catch (e: Exception) {
            throw RuntimeException(msg)
        }
        try {
            handlerDynamic(tc, EX_CAT_CATCH.toLong(), false, exObj)
        } catch (sse: SaveStackException) {
            // maaaaybe should be a panic instead
            dieInternal(tc, "control operator crossed continuation barrier")
        }
        return stooge
    }

    @JvmStatic
    fun dieInternal(tc: ThreadContext, e: Throwable): RuntimeException =
        dieInternal(tc, e.toString(), e)

    @JvmStatic
    fun dieInternal(tc: ThreadContext, msg: String): RuntimeException =
        dieInternal(tc, msg, null)

    /* Finds and executes a handler, using dynamic scope to find it. */
    /* die_s_return causes handlerDynamic to return the exception message instead of the exception object. */
    @JvmStatic
    fun handlerDynamic(tc: ThreadContext, category: Long,
                       die_s_return: Boolean, exObj: VMExceptionInstance?) {
        if (tc.gc.shuttingDown)
            throw death

        var f = tc.curFrame
        var handler: LongArray? = null
        all@
        while (f != null) {
            if (f.curHandler != 0L) {
                var tryHandler = f.curHandler
                val handlers = f.codeRef.staticInfo.handlers!!
                while (tryHandler != 0L) {
                    for (i in handlers.indices) {
                        if (handlers[i][0] == tryHandler) {
                            // Found an active one, but is it the right category?
                            if ((handlers[i][2] and category) != 0L) {
                                // Correct category, but ensure we aren't already in it.
                                var valid = true
                                for (j in 0 until tc.handlers.size) {
                                    if (tc.handlers[j].handlerInfo === handlers[i]) {
                                        valid = false
                                        break
                                    }
                                }
                                if (valid) {
                                    handler = handlers[i]
                                    break@all
                                }
                            }

                            // If not, try outer one.
                            tryHandler = handlers[i][1]
                            break
                        }
                    }
                }
            }
            f = f.caller
        }
        if (handler != null)
            invokeHandler(tc, handler, category, f, die_s_return, exObj, null)
        else
            panic(tc, category, exObj)
    }

    /* Finds and executes a handler, using lexical scope to find it. */
    @JvmStatic
    fun handlerLexical(tc: ThreadContext, category: Long,
                       exObj: VMExceptionInstance?, skipCaller: Boolean) {
        if (tc.gc.shuttingDown)
            throw death

        var f = tc.curFrame
        if (skipCaller) {
            f = f!!.caller
            while (f != null && (f.codeRef.staticInfo.isThunk || f.codeRef.isCompilerStub))
                f = f.caller
        }
        var handler: LongArray? = null
        all@
        while (f != null) {
            if (f.curHandler != 0L) {
                var tryHandler = f.curHandler
                val handlers = f.codeRef.staticInfo.handlers!!
                while (tryHandler != 0L) {
                    for (i in handlers.indices) {
                        if (handlers[i][0] == tryHandler) {
                            // Found an active one, but is it the right category?
                            if ((handlers[i][2] and category) != 0L) {
                                // Correct category, but ensure we aren't already in it.
                                var valid = true
                                for (j in 0 until tc.handlers.size) {
                                    if (tc.handlers[j].handlerInfo === handlers[i]) {
                                        valid = false
                                        break
                                    }
                                }
                                if (valid) {
                                    handler = handlers[i]
                                    break@all
                                }
                            }

                            // If not, try outer one.
                            tryHandler = handlers[i][1]
                            break
                        }
                    }
                }
            }
            f = f.outer
        }
        if (handler != null)
            invokeHandler(tc, handler, category, f, false, exObj, null)
        else if (tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig.lexicalHandlerNotFoundError != null) {
            Ops.invokeDirect(tc, tc.curFrame!!.codeRef.staticInfo.compUnit.hllConfig.lexicalHandlerNotFoundError,
                Ops.intIntCallSite, false, arrayOf<Any>(category, 0L))
        }
        else
            panic(tc, category, exObj)
    }

    /* Invokes the handler. */
    private val invokeHandlerReenter: MethodHandle = try {
        MethodHandles.insertArguments(
            MethodHandles.lookup().findStatic(ExceptionHandling::class.java, "invokeHandler",
                MethodType.methodType(Void.TYPE, ThreadContext::class.java, LongArray::class.java,
                    java.lang.Long.TYPE, CallFrame::class.java, java.lang.Boolean.TYPE,
                    VMExceptionInstance::class.java, ResumeStatus.Frame::class.java)),
            0, null, null, 0L, null, false, null)
    } catch (e: Exception) {
        throw RuntimeException(e)
    }

    @JvmStatic
    private fun invokeHandler(tc0: ThreadContext, handlerInfo0: LongArray?,
                              category: Long, handlerFrame0: CallFrame?, die_s_return0: Boolean,
                              exObj0: VMExceptionInstance?, resume: ResumeStatus.Frame?) {
        var tc = tc0
        var handlerInfo = handlerInfo0
        var handlerFrame = handlerFrame0
        var die_s_return = die_s_return0
        var exObj = exObj0
        if (resume != null) {
            val bits = resume.saveSpace
            tc = resume.tc
            handlerInfo = bits[0] as LongArray
            handlerFrame = bits[1] as CallFrame
            die_s_return = bits[2] as Boolean
            exObj = bits[3] as VMExceptionInstance?
        }

        if (tc.gc.noisyExceptions) tc.unwinder = UnwindException() // capture stack
        when (handlerInfo!![3].toInt()) {
            EX_UNWIND_SIMPLE -> {
                tc.unwinder.unwindTarget = handlerInfo[0]
                tc.unwinder.unwindCompUnit = handlerFrame!!.codeRef.staticInfo.compUnit
                tc.unwinder.category = category
                tc.unwinder.payload = null
                throw tc.unwinder
            }
            EX_UNWIND_OBJECT -> {
                tc.unwinder.unwindTarget = handlerInfo[0]
                tc.unwinder.unwindCompUnit = handlerFrame!!.codeRef.staticInfo.compUnit
                tc.unwinder.category = category
                tc.unwinder.payload =
                    if (Ops.isnull(exObj) == 0L) exObj!!.payload as SixModelObject?
                    else null
                throw tc.unwinder
            }
            EX_BLOCK -> {
                try {
                    tc.handlers.add(HandlerInfo(exObj, handlerInfo))
                    if (resume != null)
                        resume.resumeNext()
                    else
                        Ops.invokeDirect(tc, Ops.getlex_o(handlerFrame, handlerInfo[4].toInt()),
                            Ops.emptyCallSite, false, Ops.emptyArgList)
                }
                catch (e: ResumeException) {
                    tc.curFrame!!.retType = (if (die_s_return) CallFrame.RET_STR else CallFrame.RET_OBJ).toByte()
                    if (die_s_return)
                        tc.curFrame!!.sRet = exObj!!.message
                    else
                        tc.curFrame!!.oRet = exObj
                    return
                }
                catch (sse: SaveStackException) {
                    throw sse.pushFrame(0, invokeHandlerReenter,
                        arrayOf<Any?>(handlerInfo, handlerFrame, die_s_return, exObj), null)
                }
                catch (re: RuntimeException) {
                    throw re
                }
                catch (t: Throwable) {
                    throw RuntimeException(t)
                }
                finally {
                    tc.handlers.removeAt(tc.handlers.size - 1)
                }
                tc.unwinder.category = category
                tc.unwinder.unwindTarget = handlerInfo[0]
                tc.unwinder.unwindCompUnit = handlerFrame!!.codeRef.staticInfo.compUnit
                tc.unwinder.result = Ops.result_o(tc.curFrame)
                if (Ops.isnull(exObj) == 0L)
                    tc.unwinder.payload = exObj!!.payload as SixModelObject?
                throw tc.unwinder
            }
            else -> throw dieInternal(tc, "Unknown exception kind")
        }
    }

    /* Unhandled exception. Exit with stack trace. */
    private fun panic(tc: ThreadContext, category: Long,
                      exObj0: VMExceptionInstance?): SixModelObject {
        val message = StringBuilder()
        if (Ops.isnull(exObj0) == 0L && exObj0!!.message != null)
            message.append("Unhandled exception: " + exObj0.message + "\n")
        else
            message.append("Unhandled exception; category = $category\n")

        val exObj = VMExceptionInstance()
        exObj.origin = tc.curFrame
        exObj.nativeTrace = Throwable().stackTrace

        for (line in backtraceStrings(exObj)) {
            message.append(line)
            message.append("\n")
        }

        tc.gc.err.println(message.toString())
        tc.gc.exit(1)
        return exObj
    }

    @JvmStatic
    fun backtraceStrings(ex: VMExceptionInstance): List<String> {
        val result = ArrayList<String>()
        for (e in backtrace(ex)) {
            var name = e.frame.codeRef.name
            if (name == null || name == "")
                name = "<anon>"

            result.add("  in " + name +
                (if (e.file == null) ""
                 else " (" + e.file + (if (e.line >= 0) ":" + e.line else "") + ")"))
        }
        return result
    }

    class TraceElement(
        @JvmField val frame: CallFrame,
        @JvmField val file: String?,
        @JvmField val line: Int,
    )

    @JvmStatic
    fun backtrace(ex: VMExceptionInstance): List<TraceElement> {
        val result = ArrayList<TraceElement>()
        // Each CallFrame which is actually on the stack corresponds, except in exceptional circumstances, to a native frame
        // We probably ought to use a Levenshteiny thing eventually, but this should be good enough for now.

        var jcursor = 0
        var ncursor: CallFrame? = ex.origin

        val nativeTrace = ex.nativeTrace
        while (ncursor != null) {
            val info = ncursor.codeRef.staticInfo
            val kls = info.compUnit.javaClass.name
            val method: String? = info.methodName

            while (nativeTrace != null && jcursor < nativeTrace.size &&
                    kls != nativeTrace[jcursor].className &&
                    (method == null || method != nativeTrace[jcursor].methodName))
                jcursor++

            val el = if (nativeTrace != null && jcursor < nativeTrace.size) nativeTrace[jcursor++] else null

            result.add(TraceElement(ncursor, el?.fileName, el?.lineNumber ?: -1))
            ncursor = ncursor.caller
        }
        return result
    }
}
