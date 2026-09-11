package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.HLLConfig
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance

/**
 * The kind of a value flowing through a dispatch. These are the callsite
 * argument flags with the named and flattening bits taken off, so that we
 * can match on them.
 */
enum class ArgKind(val flag: Byte) {
    OBJ(CallSiteDescriptor.ARG_OBJ),
    INT(CallSiteDescriptor.ARG_INT),
    UINT(CallSiteDescriptor.ARG_UINT),
    NUM(CallSiteDescriptor.ARG_NUM),
    STR(CallSiteDescriptor.ARG_STR);

    companion object {
        /* OBJ | INT | NUM | STR | UINT; excludes ARG_NAMED and ARG_FLAT. */
        private const val TYPE_MASK = 39

        fun ofFlag(flag: Byte): ArgKind = when (flag.toInt() and TYPE_MASK) {
            0 -> OBJ
            1 -> INT
            2 -> NUM
            4 -> STR
            32 -> UINT
            else -> throw IllegalArgumentException("Unhandled callsite argument flag $flag")
        }
    }
}

/** A value together with the kind of register it would live in. */
data class DispatchValue(val kind: ArgKind, val value: Any?) {
    val obj: SixModelObject?
        get() = value as SixModelObject?
}

/**
 * What a dispatch program needs in order to read the values it works with.
 * Implemented both by a recording (where the resumption data comes from the
 * recording state) and by a run of an already-compiled program.
 */
interface DispatchContext {
    val tc: ThreadContext

    /** The callsite descriptor of the arguments the dispatch was invoked with. */
    val descriptor: CallSiteDescriptor

    /** The arguments the dispatch was invoked with. */
    val args: Array<Any?>

    /** An argument of the resume initialization state at the given nesting level. */
    fun resumeInitArg(level: Int, index: Int): DispatchValue

    /** As resumeInitArg, but just the value; the kind is fixed by the level's descriptor. */
    fun resumeInitArgRaw(level: Int, index: Int): Any? = resumeInitArg(level, index).value

    /** The mutable resume state at the given nesting level. */
    fun resumeState(level: Int): SixModelObject?
}

/**
 * Where a value used by a dispatch program comes from. A dispatch program is
 * expressed entirely in terms of these: guards check them and the outcome is
 * assembled out of them, so running a program is a matter of evaluating the
 * sources against the incoming arguments.
 *
 * Structural equality is the deduplication mechanism: MoarVM hunts through a
 * values table for an existing matching entry, while here two equal sources
 * are the same value by construction.
 */
sealed interface ValueSource {
    fun evaluate(ctx: DispatchContext): DispatchValue

    /**
     * The bare value, without the DispatchValue box. This is the replay hot
     * path: guards and outcome arguments only need the value, and the kind a
     * source produces is fixed once the callsite shape has been checked, so
     * nothing is lost by not carrying it.
     */
    fun evaluateRaw(ctx: DispatchContext): Any?

    /** An argument of the capture the dispatch was invoked with. */
    data class Arg(val index: Int) : ValueSource {
        override fun evaluate(ctx: DispatchContext) = DispatchValue(
            ArgKind.ofFlag(ctx.descriptor.argFlags[index]), ctx.args[index])

        override fun evaluateRaw(ctx: DispatchContext): Any? = ctx.args[index]
    }

    /** An argument of the resume initialization state of a resumption. */
    data class ResumeInitArg(val level: Int, val index: Int) : ValueSource {
        override fun evaluate(ctx: DispatchContext) = ctx.resumeInitArg(level, index)

        override fun evaluateRaw(ctx: DispatchContext): Any? = ctx.resumeInitArgRaw(level, index)
    }

    /** A constant, which the recording fixed in place. */
    data class Literal(val kind: ArgKind, val value: Any?) : ValueSource {
        override fun evaluate(ctx: DispatchContext) = DispatchValue(kind, value)

        override fun evaluateRaw(ctx: DispatchContext): Any? = value
    }

    /** An attribute read from another value. */
    data class Attribute(val from: ValueSource, val classHandle: SixModelObject?,
                         val name: String, val kind: ArgKind) : ValueSource {
        override fun evaluate(ctx: DispatchContext) = DispatchValue(kind, evaluateRaw(ctx))

        override fun evaluateRaw(ctx: DispatchContext): Any? {
            val obj = from.evaluateRaw(ctx) as SixModelObject?
                ?: throw ExceptionHandling.dieInternal(ctx.tc,
                    "Dispatch program read an attribute of a null value")
            return readAttribute(ctx.tc, obj, classHandle, name, kind)
        }
    }

    /** The meta-object of another value. */
    data class How(val from: ValueSource) : ValueSource {
        override fun evaluate(ctx: DispatchContext) =
            DispatchValue(ArgKind.OBJ, evaluateRaw(ctx))

        override fun evaluateRaw(ctx: DispatchContext): Any? =
            (from.evaluateRaw(ctx) as SixModelObject?)?.st?.HOW
    }

    /** A native value unboxed out of another value. */
    data class Unbox(val from: ValueSource, val kind: ArgKind) : ValueSource {
        override fun evaluate(ctx: DispatchContext) = DispatchValue(kind, evaluateRaw(ctx))

        override fun evaluateRaw(ctx: DispatchContext): Any? =
            unbox(ctx.tc, from.evaluateRaw(ctx) as SixModelObject?, kind)
    }

    /** The result of looking a tracked string key up in a hash. */
    data class Lookup(val table: ValueSource, val key: ValueSource) : ValueSource {
        override fun evaluate(ctx: DispatchContext) =
            DispatchValue(ArgKind.OBJ, evaluateRaw(ctx))

        override fun evaluateRaw(ctx: DispatchContext): Any? {
            val hash = table.evaluateRaw(ctx) as SixModelObject?
            val name = key.evaluateRaw(ctx) as String?
            return if (hash == null || name == null) null
                   else hash.at_key_boxed(ctx.tc, name)
        }
    }

    /** The mutable resume state of a resumption. */
    data class ResumeState(val level: Int) : ValueSource {
        override fun evaluate(ctx: DispatchContext) =
            DispatchValue(ArgKind.OBJ, ctx.resumeState(level))

        override fun evaluateRaw(ctx: DispatchContext): Any? = ctx.resumeState(level)
    }

    companion object {
        /**
         * Reads an attribute of the given kind. Boxed reads that hit a
         * natively stored attribute report themselves by throwing, which is
         * also how Ops.getattr sorts out the kind of a read.
         */
        fun readAttribute(tc: ThreadContext, obj: SixModelObject, classHandle: SixModelObject?,
                          name: String, kind: ArgKind): Any? {
            if (kind == ArgKind.OBJ)
                return obj.get_attribute_boxed(tc, classHandle, name, STable.NO_HINT)
            obj.get_attribute_native(tc, classHandle, name, STable.NO_HINT)
            return when (kind) {
                ArgKind.INT, ArgKind.UINT -> tc.nativeI
                ArgKind.NUM -> tc.nativeN
                ArgKind.STR -> tc.nativeS
                else -> throw IllegalStateException("unreachable")
            }
        }

        /** Works out which kind of value an attribute holds, by reading it. */
        fun attributeKind(tc: ThreadContext, obj: SixModelObject,
                          classHandle: SixModelObject?, name: String): ArgKind {
            try {
                obj.get_attribute_boxed(tc, classHandle, name, STable.NO_HINT)
                return ArgKind.OBJ
            }
            catch (badRef: P6OpaqueBaseInstance.BadReferenceRuntimeException) {
                obj.get_attribute_native(tc, classHandle, name, STable.NO_HINT)
                return when (tc.nativeType) {
                    ThreadContext.NATIVE_INT -> ArgKind.INT
                    ThreadContext.NATIVE_NUM -> ArgKind.NUM
                    ThreadContext.NATIVE_STR -> ArgKind.STR
                    else -> throw ExceptionHandling.dieInternal(tc,
                        "Cannot track an attribute of this kind")
                }
            }
        }

        fun unbox(tc: ThreadContext, obj: SixModelObject?, kind: ArgKind): Any? = when (kind) {
            ArgKind.INT, ArgKind.UINT -> Ops.unbox_i(obj, tc)
            ArgKind.NUM -> Ops.unbox_n(obj, tc)
            ArgKind.STR -> Ops.unbox_s(obj, tc)
            ArgKind.OBJ -> throw ExceptionHandling.dieInternal(tc, "Cannot unbox to an object")
        }
    }
}

/**
 * A condition that the incoming arguments must meet for a dispatch program to
 * apply. Recording a dispatch notes the properties the dispatcher relied on;
 * these are what is left of them.
 */
sealed interface Guard {
    val on: ValueSource

    fun check(ctx: DispatchContext): Boolean

    /* The checks read values through evaluateRaw: the kind a source produces
     * is fixed once the program's callsite shape has matched, so the kind
     * comparisons the boxed helpers do are settled at recording time and only
     * the value itself needs looking at. */

    /** The value has exactly this type. */
    data class OfType(override val on: ValueSource, val type: STable?) : Guard {
        override fun check(ctx: DispatchContext) =
            (on.evaluateRaw(ctx) as? SixModelObject)?.st === type
    }

    /** The value is (or is not) a concrete object rather than a type object. */
    data class Concreteness(override val on: ValueSource, val concrete: Boolean) : Guard {
        override fun check(ctx: DispatchContext) = isConcrete(on.evaluateRaw(ctx)) == concrete
    }

    /** The value is this exact value. */
    data class Literal(override val on: ValueSource, val expected: DispatchValue) : Guard {
        override fun check(ctx: DispatchContext): Boolean {
            val got = on.evaluateRaw(ctx)
            return when (expected.kind) {
                ArgKind.OBJ -> got === expected.value
                else -> got == expected.value
            }
        }
    }

    /** The value is anything but this object. */
    data class NotLiteralObj(override val on: ValueSource, val rejected: SixModelObject?) : Guard {
        override fun check(ctx: DispatchContext) = on.evaluateRaw(ctx) !== rejected
    }

    /** The value belongs to this language. */
    data class OfHll(override val on: ValueSource, val hll: HLLConfig?) : Guard {
        override fun check(ctx: DispatchContext) =
            (on.evaluateRaw(ctx) as? SixModelObject)?.st?.hllOwner === hll
    }

    companion object {
        fun typeOf(value: DispatchValue): STable? {
            if (value.kind != ArgKind.OBJ) return null
            val obj = value.obj ?: return null
            return obj.st
        }

        fun hllOf(value: DispatchValue): HLLConfig? {
            if (value.kind != ArgKind.OBJ) return null
            return value.obj?.st?.hllOwner
        }

        fun isConcrete(value: Any?): Boolean =
            value != null && value !is TypeObject
    }
}

/**
 * How to build an argument list out of the values a dispatch program has to
 * hand. Captures derived during a recording are tracked in this form, so that
 * the capture the dispatcher settled on can be reproduced later.
 */
class CaptureShape(val sources: List<ValueSource>, val descriptor: CallSiteDescriptor) {
    /** Derives a shape with the argument at the given index removed. */
    fun drop(tc: ThreadContext, index: Int): CaptureShape {
        val kept = sources.toMutableList()
        kept.removeAt(index)
        return CaptureShape(kept, dropFlag(tc, descriptor, index))
    }

    /** Derives a shape with an extra argument at the given index. */
    fun insert(tc: ThreadContext, index: Int, source: ValueSource, kind: ArgKind): CaptureShape {
        val grown = sources.toMutableList()
        grown.add(index, source)
        return CaptureShape(grown, insertFlag(tc, descriptor, index, kind))
    }

    /** Derives a shape with the argument at the given index replaced. */
    fun replace(tc: ThreadContext, index: Int, source: ValueSource, kind: ArgKind): CaptureShape =
        drop(tc, index).insert(tc, index, source, kind)

    /** Evaluates the shape into an argument array. */
    fun evaluate(ctx: DispatchContext): Array<Any?> {
        val out = arrayOfNulls<Any>(sources.size)
        for (i in sources.indices)
            out[i] = sources[i].evaluateRaw(ctx)
        return out
    }

    companion object {
        /** The shape of the capture a dispatch was invoked with. */
        fun ofArgs(descriptor: CallSiteDescriptor): CaptureShape =
            CaptureShape((0 until descriptor.argFlags.size).map { ValueSource.Arg(it) }, descriptor)

        fun dropFlag(tc: ThreadContext, csd: CallSiteDescriptor, index: Int): CallSiteDescriptor {
            checkPositional(tc, csd, index, "drop")
            val flags = ByteArray(csd.argFlags.size - 1)
            for (i in flags.indices)
                flags[i] = csd.argFlags[if (i < index) i else i + 1]
            return CallSiteDescriptor(flags, csd.names)
        }

        fun insertFlag(tc: ThreadContext, csd: CallSiteDescriptor, index: Int,
                       kind: ArgKind): CallSiteDescriptor {
            if (index > csd.numPositionals)
                throw ExceptionHandling.dieInternal(tc,
                    "Can only insert an argument at a positional index")
            val flags = ByteArray(csd.argFlags.size + 1)
            for (i in flags.indices)
                flags[i] = when {
                    i < index -> csd.argFlags[i]
                    i == index -> kind.flag
                    else -> csd.argFlags[i - 1]
                }
            return CallSiteDescriptor(flags, csd.names)
        }

        private fun checkPositional(tc: ThreadContext, csd: CallSiteDescriptor, index: Int,
                                    what: String) {
            if (index >= csd.numPositionals)
                throw ExceptionHandling.dieInternal(tc,
                    "Can only $what a positional argument (index $index of ${csd.numPositionals})")
        }
    }
}

/**
 * What a dispatch ends up doing. The recording settles on one of these, and it
 * is also what a compiled program replays.
 */
sealed interface Outcome {
    /** Produce a value. */
    data class Value(val source: ValueSource) : Outcome

    /** Invoke bytecode with the given arguments. */
    data class InvokeCode(val callee: ValueSource, val args: CaptureShape) : Outcome

    /** Invoke a VM-provided function with the given arguments. */
    data class InvokeSyscall(val syscall: Syscall, val args: CaptureShape) : Outcome
}

/** How a signature bind outcome of an invocation maps back to a resumption. */
data class BindControl(val failureFlag: Long, val successFlag: Long?, val onSuccessToo: Boolean)

/**
 * A resumption that a dispatch program sets up: a dispatcher that can later be
 * resumed, along with where its initialization arguments come from.
 */
class ResumptionSpec(val dispatcher: Dispatcher, val initArgs: CaptureShape)

/**
 * One level of an already-in-progress resumption that a resuming dispatch
 * program works through. A resuming program starts by finding the dispatch to
 * resume and then walks out through as many levels as the recording did.
 */
class ResumptionLevel(
    /** The dispatcher we expect to find at this level. */
    val dispatcher: Dispatcher,
    /** The shape the resume initialization arguments are expected to have. */
    val initDescriptor: CallSiteDescriptor,
    /** The guards that were established while this level was current. */
    val guards: List<Guard>,
    /** The new resume state to write, if the recording set one. */
    val newState: ValueSource?,
    /** The recording looked for a further resumption and did not find one. */
    val requireNoFurther: Boolean,
)

/**
 * Where a resuming dispatch looks for the dispatch it resumes, expressed as how
 * many frames of the callstack to pass over first. We never resume a dispatch
 * made by the frame we are in; asking for the caller's passes over one more.
 */
enum class ResumeKind(val framesToSkip: Int) {
    NONE(0),
    TOPMOST(1),
    CALLER(2),

    /**
     * A resumption entered because a frame the dispatch invoked failed to bind
     * its signature. That frame is already gone, so there is nothing to pass
     * over: the dispatch to resume is the innermost one from here.
     */
    BIND_FAILURE(0),
}

/**
 * A compiled dispatch program: the conditions under which it applies and what
 * it does. MoarVM compiles a recording down to a linear list of guard and load
 * operations over a bank of temporaries; here the value sources are kept as
 * trees and evaluated on demand, which needs no temporaries and makes the
 * program a plain description of the dispatch.
 */
class DispatchProgram(
    /**
     * The (post-flattening) callsite the program was recorded against. A
     * callsite whose static descriptor has flattening produces a different
     * flattened shape per invocation, so a program only applies when the
     * shapes agree; the value sources index arguments by position. MoarVM
     * gets the same check by interning flattened callsites and comparing
     * them by pointer.
     */
    val descriptor: CallSiteDescriptor,
    val guards: List<Guard>,
    val outcome: Outcome,
    val resumptions: List<ResumptionSpec>,
    val resumeKind: ResumeKind,
    val resumeLevels: List<ResumptionLevel>,
    /** How a bind failure of an invocation maps to a resumption, if it does. */
    val bindControl: BindControl?,
) {
    /**
     * The program recorded for the resumption that a bind failure of this
     * program's invocation led to, once one has been. Kept here rather than at
     * the callsite of the bind check, so that it is keyed by the dispatch.
     */
    @Volatile var bindFailureProgram: DispatchProgram? = null

    /** Does this program apply to the arguments in the given context? */
    fun guardsMatch(ctx: DispatchContext): Boolean {
        for (guard in guards)
            if (!guard.check(ctx)) return false
        return true
    }

    /** Is this a program recorded for the resumption of another dispatch? */
    val isResuming: Boolean
        get() = resumeKind != ResumeKind.NONE
}
