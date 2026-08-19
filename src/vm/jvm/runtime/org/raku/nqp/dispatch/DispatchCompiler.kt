package org.raku.nqp.dispatch

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject

/**
 * Compiles the programs installed at a dispatch callsite into a MethodHandle
 * guard chain and makes that the callsite's invokedynamic target, so that a
 * settled dispatch runs as straight-line code the JIT can inline instead of
 * interpreting the program list. This is the JVM's counterpart of what
 * MoarVM's specializer does with dispatch programs.
 *
 * The chain tests each program's guards in order with the outcome as the
 * innermost handle; a guard failure falls through to the next program's chain
 * and finally to [Dispatch.fallback], which tries any uncompiled programs and
 * then records afresh. The dispatcher name and callsite descriptor are
 * per-instruction constants, so they are bound into the chain, and the
 * per-dispatch shape check the interpreted path does is settled here once.
 *
 * Only initial dispatches compile; a resuming program needs the resumption
 * levels walked against the live callstack and stays interpreted. Set
 * NQP_JVM_NO_DISPATCH_MH to keep every callsite on the interpreted path.
 */
object DispatchCompiler {
    @JvmField val disabled = System.getenv("NQP_JVM_NO_DISPATCH_MH") != null

    /**
     * How many interpreted dispatches a site must see before its programs are
     * compiled. Building a chain is not free — the combinators spin
     * LambdaForm classes, and a fresh chain runs interpreted until the JVM
     * warms to it — so a site has to prove it will earn that back. Most
     * sites never do: a setting compile records ~39k programs, nearly all at
     * sites dispatched a handful of times.
     */
    @JvmField val threshold =
        System.getenv("NQP_JVM_DISPATCH_MH_THRESHOLD")?.toIntOrNull() ?: 256

    /**
     * How many programs may go into a compiled chain. A callsite hot enough
     * to outgrow this is unstable anyway; later programs still run, through
     * the fallback's interpreted loop.
     */
    private const val MAX_COMPILED = 8

    private val lookup = MethodHandles.lookup()

    private val TC = ThreadContext::class.java
    private val ARGS = Array<Any>::class.java

    private fun test(name: String, vararg bound: Class<*>): MethodHandle =
        lookup.findStatic(DispatchCompiler::class.java, name,
            MethodType.methodType(java.lang.Boolean.TYPE, arrayOf(*bound, TC, ARGS)))

    private fun action(name: String, vararg bound: Class<*>): MethodHandle =
        lookup.findStatic(DispatchCompiler::class.java, name,
            MethodType.methodType(Void.TYPE, arrayOf(*bound, TC, ARGS)))

    private val TEST_TYPE_ARG = test("testTypeArg", Integer.TYPE, STable::class.java)
    private val TEST_CONCRETENESS_ARG = test("testConcretenessArg", Integer.TYPE,
        java.lang.Boolean.TYPE)
    private val TEST_LITERAL_ID_ARG = test("testLiteralIdArg", Integer.TYPE, Any::class.java)
    private val TEST_LITERAL_EQ_ARG = test("testLiteralEqArg", Integer.TYPE, Any::class.java)
    private val TEST_GUARD = test("testGuard", Guard::class.java)

    private val VALUE_OBJ_LITERAL = action("valueObjLiteral", SixModelObject::class.java)
    private val VALUE_OBJ_ARG = action("valueObjArg", Integer.TYPE)
    private val VALUE_GENERIC = action("valueGeneric", ValueSource::class.java,
        ArgKind::class.java)
    private val SYSCALL_OUTCOME = action("syscallOutcome", Syscall::class.java,
        Array<ValueSource>::class.java, CallSiteDescriptor::class.java)
    private val INVOKE_MAPPED = action("invokeMapped", SixModelObject::class.java,
        IntArray::class.java, CallSiteDescriptor::class.java)
    private val INVOKE_SIMPLE = action("invokeSimple", ValueSource::class.java,
        Array<ValueSource>::class.java, CallSiteDescriptor::class.java)
    private val INVOKE_RESUMABLE = action("invokeResumable", DispatchProgram::class.java,
        DispatchCallSite::class.java, ValueSource::class.java,
        Array<ValueSource>::class.java, CallSiteDescriptor::class.java)

    private val FALLBACK = lookup.findStatic(Dispatch::class.java, "fallback",
        MethodType.methodType(Void.TYPE, DispatchCallSite::class.java, String::class.java,
            CallSiteDescriptor::class.java, Integer.TYPE, TC, ARGS))

    /* ----- building the chain ----- */

    /**
     * Compiles the site's installed programs into a (ThreadContext, Object[])
     * guard chain, or returns null when there is nothing to compile: the site
     * has not been dispatched through yet, its callsite has flattening (so
     * the shape varies per invocation), or its first program is not
     * compilable.
     */
    fun compileChain(site: DispatchCallSite): MethodHandle? {
        val name = site.linkedName ?: return null
        val descriptor = site.staticDescriptor ?: return null
        if (descriptor.hasFlattening) return null
        val programs = site.programs
        var prefix = 0
        while (prefix < programs.size && prefix < MAX_COMPILED &&
                compilable(programs[prefix], descriptor))
            prefix++
        if (prefix == 0) return null

        var chain: MethodHandle =
            MethodHandles.insertArguments(FALLBACK, 0, site, name, descriptor, prefix)
        for (i in prefix - 1 downTo 0) {
            val program = programs[i]
            var handle = outcomeHandle(program, descriptor, site)
            /* Innermost-out, so the guards are checked in recorded order; that
             * matters because a guard on a derived value (an attribute read)
             * is only safe once the guards that made the read safe have
             * passed. A failing test falls through to the next program. */
            for (guard in program.guards.asReversed())
                handle = MethodHandles.guardWithTest(guardTest(guard), handle, chain)
            chain = handle
        }
        return chain
    }

    /**
     * Adapts a compiled (ThreadContext, Object[])void chain to an indy
     * instruction's (name, csIdx, ThreadContext, args...) shape, the same
     * way the generic target is adapted at bootstrap.
     */
    fun adaptToIndy(chain: MethodHandle, siteType: MethodType): MethodHandle =
        MethodHandles.dropArguments(
                chain.asCollector(ARGS, siteType.parameterCount() - 3),
                0, String::class.java, Integer.TYPE)
            .asType(siteType)

    /**
     * An initial dispatch whose shape matches the instruction's compiles; a
     * resuming program stays interpreted, since it must walk the resumption
     * levels against the live callstack.
     */
    private fun compilable(program: DispatchProgram, descriptor: CallSiteDescriptor): Boolean =
        !program.isResuming && Captures.sameShape(program.descriptor, descriptor)

    private fun guardTest(guard: Guard): MethodHandle {
        val on = guard.on
        if (on is ValueSource.Arg) {
            val index = on.index
            when (guard) {
                is Guard.OfType ->
                    return MethodHandles.insertArguments(TEST_TYPE_ARG, 0, index, guard.type)
                is Guard.Concreteness ->
                    return MethodHandles.insertArguments(TEST_CONCRETENESS_ARG, 0, index,
                        guard.concrete)
                is Guard.Literal ->
                    return if (guard.expected.kind == ArgKind.OBJ)
                        MethodHandles.insertArguments(TEST_LITERAL_ID_ARG, 0, index,
                            guard.expected.value)
                    else
                        MethodHandles.insertArguments(TEST_LITERAL_EQ_ARG, 0, index,
                            guard.expected.value)
                else -> {}
            }
        }
        return MethodHandles.insertArguments(TEST_GUARD, 0, guard)
    }

    private fun outcomeHandle(program: DispatchProgram, descriptor: CallSiteDescriptor,
                              site: DispatchCallSite): MethodHandle =
        when (val outcome = program.outcome) {
            is Outcome.Value -> {
                val source = outcome.source
                val kind = kindOf(source, descriptor)
                when {
                    source is ValueSource.Literal && kind == ArgKind.OBJ ->
                        MethodHandles.insertArguments(VALUE_OBJ_LITERAL, 0, source.value)
                    source is ValueSource.Arg && kind == ArgKind.OBJ ->
                        MethodHandles.insertArguments(VALUE_OBJ_ARG, 0, source.index)
                    else ->
                        MethodHandles.insertArguments(VALUE_GENERIC, 0, source, kind)
                }
            }
            is Outcome.InvokeSyscall ->
                MethodHandles.insertArguments(SYSCALL_OUTCOME, 0, outcome.syscall,
                    outcome.args.sources.toTypedArray(), outcome.args.descriptor)
            is Outcome.InvokeCode -> {
                val plan = outcome.args.sources.toTypedArray()
                val callee = outcome.callee
                if (program.resumptions.isEmpty() && program.bindControl == null) {
                    val map = if (callee is ValueSource.Literal) argMap(plan) else null
                    if (map != null)
                        MethodHandles.insertArguments(INVOKE_MAPPED, 0,
                            (callee as ValueSource.Literal).value, map, outcome.args.descriptor)
                    else
                        MethodHandles.insertArguments(INVOKE_SIMPLE, 0, callee, plan,
                            outcome.args.descriptor)
                }
                else
                    MethodHandles.insertArguments(INVOKE_RESUMABLE, 0, program, site,
                        callee, plan, outcome.args.descriptor)
            }
        }

    /** An arguments-only plan as bare indices, so building the outgoing
     * argument array is a straight copy. */
    private fun argMap(plan: Array<ValueSource>): IntArray? {
        val map = IntArray(plan.size)
        for (i in plan.indices) {
            val source = plan[i]
            if (source !is ValueSource.Arg) return null
            map[i] = source.index
        }
        return map
    }

    /** The kind a source produces, which the callsite shape fixes. */
    private fun kindOf(source: ValueSource, descriptor: CallSiteDescriptor): ArgKind =
        when (source) {
            is ValueSource.Arg -> ArgKind.ofFlag(descriptor.argFlags[source.index])
            is ValueSource.Literal -> source.kind
            is ValueSource.Attribute -> source.kind
            is ValueSource.Unbox -> source.kind
            else -> ArgKind.OBJ
        }

    /* ----- evaluating value sources without a dispatch record ----- */

    /**
     * Evaluates a value source against the incoming arguments alone. The
     * resumption sources cannot appear in a compilable program, which is what
     * lets a compiled chain run without any per-dispatch context object.
     */
    @JvmStatic
    fun evalRaw(source: ValueSource, tc: ThreadContext, args: Array<Any?>): Any? =
        when (source) {
            is ValueSource.Arg -> args[source.index]
            is ValueSource.Literal -> source.value
            is ValueSource.How ->
                (evalRaw(source.from, tc, args) as SixModelObject?)?.st?.HOW
            is ValueSource.Attribute -> {
                val obj = evalRaw(source.from, tc, args) as SixModelObject?
                    ?: throw ExceptionHandling.dieInternal(tc,
                        "Dispatch program read an attribute of a null value")
                ValueSource.readAttribute(tc, obj, source.classHandle, source.name, source.kind)
            }
            is ValueSource.Unbox ->
                ValueSource.unbox(tc, evalRaw(source.from, tc, args) as SixModelObject?,
                    source.kind)
            is ValueSource.Lookup -> {
                val hash = evalRaw(source.table, tc, args) as SixModelObject?
                val key = evalRaw(source.key, tc, args) as String?
                if (hash == null || key == null) null else hash.at_key_boxed(tc, key)
            }
            is ValueSource.ResumeInitArg, is ValueSource.ResumeState ->
                throw ExceptionHandling.dieInternal(tc,
                    "Resumption state is not available in a compiled dispatch chain")
        }

    private fun evalPlan(plan: Array<ValueSource>, tc: ThreadContext,
                         args: Array<Any?>): Array<Any?> {
        val out = arrayOfNulls<Any>(plan.size)
        for (i in plan.indices)
            out[i] = evalRaw(plan[i], tc, args)
        return out
    }

    /* ----- guard tests ----- */

    @JvmStatic
    fun testTypeArg(index: Int, type: STable?, tc: ThreadContext, args: Array<Any?>): Boolean =
        (args[index] as? SixModelObject)?.st === type

    @JvmStatic
    fun testConcretenessArg(index: Int, concrete: Boolean, tc: ThreadContext,
                            args: Array<Any?>): Boolean =
        Guard.isConcrete(args[index]) == concrete

    @JvmStatic
    fun testLiteralIdArg(index: Int, expected: Any?, tc: ThreadContext,
                         args: Array<Any?>): Boolean =
        args[index] === expected

    @JvmStatic
    fun testLiteralEqArg(index: Int, expected: Any?, tc: ThreadContext,
                         args: Array<Any?>): Boolean =
        args[index] == expected

    @JvmStatic
    fun testGuard(guard: Guard, tc: ThreadContext, args: Array<Any?>): Boolean =
        when (guard) {
            is Guard.OfType ->
                (evalRaw(guard.on, tc, args) as? SixModelObject)?.st === guard.type
            is Guard.Concreteness ->
                Guard.isConcrete(evalRaw(guard.on, tc, args)) == guard.concrete
            is Guard.Literal -> {
                val got = evalRaw(guard.on, tc, args)
                if (guard.expected.kind == ArgKind.OBJ) got === guard.expected.value
                else got == guard.expected.value
            }
            is Guard.NotLiteralObj -> evalRaw(guard.on, tc, args) !== guard.rejected
            is Guard.OfHll ->
                (evalRaw(guard.on, tc, args) as? SixModelObject)?.st?.hllOwner === guard.hll
        }

    /* ----- outcomes ----- */

    @JvmStatic
    fun valueObjLiteral(value: SixModelObject?, tc: ThreadContext, args: Array<Any?>) {
        val frame = tc.curFrame!!
        frame.oRet = value
        frame.retType = CallFrame.RET_OBJ.toByte()
    }

    @JvmStatic
    fun valueObjArg(index: Int, tc: ThreadContext, args: Array<Any?>) {
        val frame = tc.curFrame!!
        frame.oRet = args[index] as SixModelObject?
        frame.retType = CallFrame.RET_OBJ.toByte()
    }

    @JvmStatic
    fun valueGeneric(source: ValueSource, kind: ArgKind, tc: ThreadContext, args: Array<Any?>) {
        val frame = tc.curFrame!!
        when (kind) {
            ArgKind.OBJ -> {
                frame.oRet = evalRaw(source, tc, args) as SixModelObject?
                frame.retType = CallFrame.RET_OBJ.toByte()
            }
            ArgKind.INT -> {
                frame.iRet = evalRaw(source, tc, args) as Long
                frame.retType = CallFrame.RET_INT.toByte()
            }
            ArgKind.UINT -> {
                frame.iRet = evalRaw(source, tc, args) as Long
                frame.retType = CallFrame.RET_UINT.toByte()
            }
            ArgKind.NUM -> {
                frame.nRet = evalRaw(source, tc, args) as Double
                frame.retType = CallFrame.RET_NUM.toByte()
            }
            ArgKind.STR -> {
                frame.sRet = evalRaw(source, tc, args) as String?
                frame.retType = CallFrame.RET_STR.toByte()
            }
        }
    }

    @JvmStatic
    fun syscallOutcome(syscall: Syscall, plan: Array<ValueSource>, descriptor: CallSiteDescriptor,
                       tc: ThreadContext, args: Array<Any?>) {
        val out = evalPlan(plan, tc, args)
        Dispatch.setFrameResult(tc.curFrame!!, syscall.call(tc, descriptor, out))
    }

    /**
     * Invokes for a program with no resumptions and no bind control. Such a
     * dispatch needs no record at all: the resumption search deliberately
     * looks past records with nothing to resume, and the bind-failure checks
     * treat a frame with no dispatch record the same as one whose dispatch
     * set no bind control.
     */
    @JvmStatic
    fun invokeMapped(callee: SixModelObject?, map: IntArray, descriptor: CallSiteDescriptor,
                     tc: ThreadContext, args: Array<Any?>) {
        val out = arrayOfNulls<Any>(map.size)
        for (i in map.indices)
            out[i] = args[map[i]]
        Ops.invokeDirect(tc, callee, descriptor, out)
    }

    @JvmStatic
    fun invokeSimple(callee: ValueSource, plan: Array<ValueSource>,
                     descriptor: CallSiteDescriptor, tc: ThreadContext, args: Array<Any?>) {
        val calleeObj = evalRaw(callee, tc, args) as SixModelObject?
        Ops.invokeDirect(tc, calleeObj, descriptor, evalPlan(plan, tc, args))
    }

    /**
     * Invokes for a program that set up resumptions or bind control: the
     * dispatch record has to be live for as long as the callee runs, so that
     * the callee can resume this dispatch or fail its bind back into it.
     */
    @JvmStatic
    fun invokeResumable(program: DispatchProgram, site: DispatchCallSite?, callee: ValueSource,
                        plan: Array<ValueSource>, descriptor: CallSiteDescriptor,
                        tc: ThreadContext, args: Array<Any?>) {
        val calleeObj = evalRaw(callee, tc, args) as SixModelObject?
        val out = evalPlan(plan, tc, args)
        val record = DispatchRecord(tc, null, program.descriptor, args, tc.curFrame, site)
        record.program = program
        record.endRecording()
        val records = tc.dispatchRecords
        records.add(record)
        try {
            tc.pendingDispatch = record
            try {
                Ops.invokeDirect(tc, calleeObj, descriptor, out)
            }
            catch (failure: BindFailureException) {
                if (failure.record !== record) throw failure
                Dispatch.resumeAfterBindFailure(tc, record, failure.flag)
            }
            finally {
                tc.pendingDispatch = null
            }
        }
        finally {
            records.removeAt(records.size - 1)
        }
    }
}
