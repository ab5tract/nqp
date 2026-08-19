package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.HLLConfig
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.KnowHOWREPRInstance

/**
 * The dispatchers the runtime provides. Everything a language builds is
 * expressed by delegating, eventually, to one of these.
 */
object BootDispatchers {
    /* ----- the value producing dispatchers ----- */

    /** Produces the first argument, and does not fixate it. */
    private fun bootValue(record: DispatchRecord, capture: SixModelObject) {
        record.settle(Outcome.Value(record.trackArg(capture, 0).source!!))
    }

    /** Produces the first argument, treating it as a constant from now on. */
    private fun bootConstant(record: DispatchRecord, capture: SixModelObject) {
        val value = Captures.argValue(record.tc, Captures.asCapture(record.tc, capture), 0)
        record.settle(Outcome.Value(ValueSource.Literal(value.kind, value.value)))
    }

    /* ----- the invoking dispatchers ----- */

    /** Invokes the first argument with the rest, treating the callee as constant. */
    private fun bootCodeConstant(record: DispatchRecord, capture: SixModelObject) {
        val callee = calleeOf(record, capture, "boot-code-constant")
        record.settle(Outcome.InvokeCode(ValueSource.Literal(ArgKind.OBJ, callee),
            argsShape(record, capture)))
    }

    /** Invokes the first argument with the rest, guarding what the callee is. */
    private fun bootCode(record: DispatchRecord, capture: SixModelObject) {
        calleeOf(record, capture, "boot-code")
        val tracked = record.trackArg(capture, 0)
        val source = tracked.source!!
        record.guardType(source)
        record.guardConcreteness(source)
        record.settle(Outcome.InvokeCode(source, argsShape(record, capture)))
    }

    /** Invokes a function the VM provides, named by the first argument. */
    private fun bootSyscall(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        val name = Captures.argValue(tc, Captures.asCapture(tc, capture), 0)
        if (name.kind != ArgKind.STR)
            throw ExceptionHandling.dieInternal(tc,
                "The first argument to boot-syscall must be a string naming the syscall")
        val syscall = Syscalls.find(tc, name.value as String?)
        val args = argsShape(record, capture)
        syscall.checkArgs(tc, args.descriptor)
        record.settle(Outcome.InvokeSyscall(syscall, args))
    }

    /** The callee of an invoking dispatcher: argument zero, which must be code. */
    private fun calleeOf(record: DispatchRecord, capture: SixModelObject,
                         what: String): SixModelObject {
        val tc = record.tc
        val callee = Captures.argValue(tc, Captures.asCapture(tc, capture), 0)
        val code = callee.obj
        if (callee.kind != ArgKind.OBJ || code !is CodeRef)
            throw ExceptionHandling.dieInternal(tc,
                "The $what dispatcher only works with a VM code handle")
        return code
    }

    /** The capture of an invoking dispatcher, minus the callee. */
    private fun argsShape(record: DispatchRecord, capture: SixModelObject): CaptureShape =
        record.shapeOf(capture).drop(record.tc, 0)

    /* ----- resumption ----- */

    private fun bootResume(record: DispatchRecord, capture: SixModelObject) {
        Dispatch.recordResume(record.tc, record, capture, ResumeKind.TOPMOST)
    }

    private fun bootResumeCaller(record: DispatchRecord, capture: SixModelObject) {
        Dispatch.recordResume(record.tc, record, capture, ResumeKind.CALLER)
    }

    /* ----- the language sensitive dispatchers ----- */

    /**
     * Invokes the first argument with the rest. A VM code handle is invoked
     * directly; anything else is handed to the call dispatcher of the language
     * the object belongs to.
     */
    private fun langCall(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        val tracked = record.trackArg(capture, 0)
        val source = tracked.source!!
        record.guardType(source)

        val invokee = tracked.value as SixModelObject?
        val delegate: String
        if (invokee is CodeRef) {
            if (!Guard.isConcrete(invokee))
                throw ExceptionHandling.dieInternal(tc, "lang-call code handle must be concrete")
            record.guardConcreteness(source)
            delegate = "boot-code"
        }
        else {
            val hll = hllOf(tc, invokee)
                ?: throw ExceptionHandling.dieInternal(tc,
                    "lang-call cannot invoke an object belonging to no language (a " +
                    (if (invokee == null) "null" else invokee.javaClass.simpleName +
                        if (invokee.stInitialized) " of type " +
                            invokee.st.debugName else " with no STable") + ")")
            delegate = hll.callDispatcher
                ?: throw ExceptionHandling.dieInternal(tc,
                    "No language call dispatcher registered for '${hll.name}'")
        }
        record.delegate(tc.gc.dispatchers.find(tc, delegate), capture)
    }

    /**
     * Calls a method on the first argument, named by the second. Languages take
     * this over; where none does, the invocant's meta-object must be a KnowHOW
     * and the method comes from its table, which is where method dispatch
     * bottoms out.
     */
    private fun langMethCall(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        val tracked = record.trackArg(capture, 0)
        val source = tracked.source!!
        val invocant = tracked.value as SixModelObject?

        val hll = hllOf(tc, invocant)
        val languageDispatcher = hll?.methodCallDispatcher
        if (languageDispatcher != null) {
            record.guardHll(source)
            record.delegate(tc.gc.dispatchers.find(tc, languageDispatcher), capture)
            return
        }

        /* The method table hangs off the meta-object, so the type decides it.
         * A type with no KnowHOW meta-object may still carry a published
         * method cache -- the Java interop wrappers do -- and that serves
         * the same way. */
        record.guardType(source)
        val methods = knowHowMethods(tc, invocant)
            ?: (if (invocant != null && invocant.stInitialized) invocant.st.MethodCache else null)
            ?: throw ExceptionHandling.dieInternal(tc,
                "lang-meth-call cannot work out how to dispatch on this type" +
                " ('${Ops.typeName(invocant, tc)}' calling" +
                " '${methodName(tc, capture, 1, "lang-meth-call")}')")
        val name = methodName(tc, capture, 1, "lang-meth-call")
        val method = methods.get(name)
        if (method == null || !Guard.isConcrete(method)) {
            record.delegate(tc.gc.dispatchers.find(tc, "lang-meth-not-found"), capture)
            return
        }

        /* Guard the name, drop the invocant and name, and call the method.
         * The capture carries the invocant again after the name, so dropping
         * both still leaves it as the method's first argument. */
        record.guardLiteral(record.trackArg(capture, 1).source!!)
        val shape = record.shapeOf(capture).drop(tc, 0).drop(tc, 0)
            .insert(tc, 0, ValueSource.Literal(ArgKind.OBJ, method), ArgKind.OBJ)
        record.delegate(tc.gc.dispatchers.find(tc, "lang-call"), record.derive(shape))
    }

    /**
     * Resolves a method: the invocant, the name, and a flag saying whether not
     * finding it is an error. Produces the method or a null.
     */
    private fun langFindMeth(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        val tracked = record.trackArg(capture, 0)
        val invocant = tracked.value as SixModelObject?

        val hll = hllOf(tc, invocant)
        val languageDispatcher = hll?.findMethodDispatcher
        if (languageDispatcher != null) {
            record.guardHll(tracked.source!!)
            record.delegate(tc.gc.dispatchers.find(tc, languageDispatcher), capture)
            return
        }

        record.guardType(tracked.source!!)
        record.guardLiteral(record.trackArg(capture, 1).source!!)
        record.guardLiteral(record.trackArg(capture, 2).source!!)

        val exceptional = Captures.argValue(tc, Captures.asCapture(tc, capture), 2).value as Long
        val methods = knowHowMethods(tc, invocant)
        val method = if (methods == null) null
                     else methods.get(methodName(tc, capture, 1, "lang-find-meth"))
        if (methods != null && method != null && Guard.isConcrete(method)) {
            produceConstant(record, capture, ArgKind.OBJ, method)
        }
        else if (exceptional != 0L) {
            if (methods == null)
                throw ExceptionHandling.dieInternal(tc,
                    "lang-find-meth cannot work out how to look for a method on this type")
            /* Drop the exception flag; the not found dispatcher takes the same
             * arguments a method call does. */
            record.delegate(tc.gc.dispatchers.find(tc, "lang-meth-not-found"),
                record.derive(record.shapeOf(capture).drop(tc, 2)))
        }
        else {
            produceConstant(record, capture, ArgKind.OBJ, null)
        }
    }

    /** Reports a method that could not be found, the way the language wants it. */
    private fun langMethNotFound(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        /* The language whose error to raise is the dispatch site's, not the
         * dispatcher's own: read the config off the frame the dispatch
         * instruction is in. */
        val siteFrame = record.callerFrame ?: tc.frame
        val handler = siteFrame.codeRef.staticInfo.compUnit.hllConfig.methodNotFoundError
        if (handler != null) {
            val shape = record.shapeOf(capture)
                .insert(tc, 0, ValueSource.Literal(ArgKind.OBJ, handler), ArgKind.OBJ)
            record.delegate(tc.gc.dispatchers.find(tc, "lang-call"), record.derive(shape))
            return
        }
        val invocant = Captures.argValue(tc, Captures.asCapture(tc, capture), 0).obj
        val name = methodName(tc, capture, 1, "lang-meth-not-found")
        throw ExceptionHandling.dieInternal(tc,
            "Cannot find method '$name' on object of type ${Ops.typeName(invocant, tc)}")
    }

    /** Is the first argument something that can be invoked? */
    private fun langIsInvokable(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        val tracked = record.trackArg(capture, 0)
        record.guardType(tracked.source!!)

        if (tracked.kind == ArgKind.OBJ) {
            val obj = tracked.value as SixModelObject?
            if (obj is CodeRef || (obj != null && obj.stInitialized && obj.st.InvocationSpec != null)) {
                produceConstant(record, capture, ArgKind.INT, 1L)
                return
            }
            val languageDispatcher = hllOf(tc, obj)?.isinvokableDispatcher
            if (languageDispatcher != null) {
                record.delegate(tc.gc.dispatchers.find(tc, languageDispatcher), capture)
                return
            }
        }
        produceConstant(record, capture, ArgKind.INT, 0L)
    }

    /**
     * Maps a value into the current language, or into a named one if a second
     * argument says so.
     */
    private fun langHllize(record: DispatchRecord, capture: SixModelObject) {
        val tc = record.tc
        var shape = record.shapeOf(capture)
        var target: SixModelObject? = capture
        record.guardHll(record.trackArg(capture, 0).source!!)

        val hll: HLLConfig?
        if (shape.descriptor.numPositionals == 1) {
            hll = tc.frame.codeRef.staticInfo.compUnit.hllConfig
        }
        else {
            record.guardLiteral(record.trackArg(capture, 1).source!!)
            val name = Captures.argValue(tc, Captures.asCapture(tc, capture), 1)
            hll = tc.gc.getHLLConfigFor(if (name.kind == ArgKind.STR) name.value as String
                                        else Ops.unbox_s(name.obj, tc)!!)
            shape = shape.drop(tc, 1)
            target = record.derive(shape)
        }

        val value = Captures.argValue(tc, Captures.asCapture(tc, target), 0)
        val already = value.kind == ArgKind.OBJ && value.obj?.st?.hllOwner === hll
        val languageDispatcher = hll?.hllizeDispatcher
        if (languageDispatcher != null && !already)
            record.delegate(tc.gc.dispatchers.find(tc, languageDispatcher), target!!)
        else
            record.delegate(valueDispatcher, target!!)
    }

    /* ----- shared helpers ----- */

    /** Prepends a constant to the capture and hands it to boot-constant. */
    private fun produceConstant(record: DispatchRecord, capture: SixModelObject,
                                kind: ArgKind, value: Any?) {
        val shape = record.shapeOf(capture)
            .insert(record.tc, 0, ValueSource.Literal(kind, value), kind)
        record.delegate(constantDispatcher, record.derive(shape))
    }

    private fun hllOf(tc: ThreadContext, obj: SixModelObject?): HLLConfig? =
        if (obj == null || !obj.stInitialized) null else obj.st.hllOwner

    /** The method table of an object's meta-object, if it is a KnowHOW. */
    private fun knowHowMethods(tc: ThreadContext,
                               obj: SixModelObject?): HashMap<String, SixModelObject?>? {
        if (obj == null || !obj.stInitialized) return null
        val how = obj.st.HOW
        return if (how is KnowHOWREPRInstance) how.methods else null
    }

    private fun methodName(tc: ThreadContext, capture: SixModelObject, index: Int,
                           what: String): String {
        val name = Captures.argValue(tc, Captures.asCapture(tc, capture), index)
        return when (name.kind) {
            ArgKind.STR -> name.value as String
            ArgKind.OBJ -> Ops.unbox_s(name.obj, tc)!!
            else -> throw ExceptionHandling.dieInternal(tc,
                "The method name argument to $what must be a string")
        }
    }

    /* ----- the registry ----- */

    private fun builtin(id: String, run: (DispatchRecord, SixModelObject) -> Unit): Dispatcher =
        Dispatcher(id, DispatchCallback.Builtin(id, run), null)

    @JvmField val valueDispatcher = builtin("boot-value", ::bootValue)
    @JvmField val constantDispatcher = builtin("boot-constant", ::bootConstant)

    @JvmField val all: List<Dispatcher> = listOf(
        valueDispatcher,
        constantDispatcher,
        builtin("boot-code", ::bootCode),
        builtin("boot-code-constant", ::bootCodeConstant),
        builtin("boot-syscall", ::bootSyscall),
        builtin("boot-resume", ::bootResume),
        builtin("boot-resume-caller", ::bootResumeCaller),
        builtin("lang-call", ::langCall),
        builtin("lang-meth-call", ::langMethCall),
        builtin("lang-find-meth", ::langFindMeth),
        builtin("lang-meth-not-found", ::langMethNotFound),
        builtin("lang-isinvokable", ::langIsInvokable),
        builtin("lang-hllize", ::langHllize),
    )
}
