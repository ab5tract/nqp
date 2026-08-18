package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.SixModelObject

class StaticCodeInfo(
    /**
     * The compilation unit where the code lives.
     */
    @JvmField var compUnit: CompilationUnit,
    mh: MethodHandle,
    /**
     * The compilation-unit unique ID of the routine (from QAST cuuid).
     */
    @JvmField var uniqueId: String?,
    /**
     * Names of the lexicals we have of each of the base types.
     */
    @JvmField var oLexicalNames: Array<String>?,
    @JvmField var iLexicalNames: Array<String>?,
    @JvmField var nLexicalNames: Array<String>?,
    @JvmField var sLexicalNames: Array<String>?,
    /**
     * Map of handlers.
     */
    @JvmField var handlers: Array<LongArray>?,
    /**
     * Static code object (base of any clones).
     */
    @JvmField var staticCode: SixModelObject?,
    /**
     * The expected arguments needed to invoke the method handle.
     */
    @JvmField var argsExpectation: Short,
) : Cloneable {
    /**
     * Method handle for the code ref. (Package-private in Java; Kotlin has
     * no package visibility, and ArgsExpectation/CallFrame read it.)
     */
    @JvmField var mh: MethodHandle = mh

    /**
     * Curried method handle for resuming. (Package-private in Java.)
     */
    @JvmField var mhResume: MethodHandle? = null

    /**
     * Method name for correlation with stack traces.
     */
    @JvmField var methodName: String? = null

    /**
     * Static outer.
     */
    @JvmField var outerStaticInfo: StaticCodeInfo? = null

    /**
     * Most recent invocation, if any.
     */
    @JvmField var priorInvocation: CallFrame? = null

    /**
     * Deserialized contexts waiting for a frame of this code to become their
     * outer. A compilation unit's serialization context is read before its
     * mainline runs, so contexts that had no outer serialized cannot be
     * hooked up at the time they are created; the frame adopts them when it
     * finally shows up. Null whenever there is nothing waiting, which is the
     * overwhelmingly common case.
     */
    @JvmField var contextsAwaitingOuter: ArrayList<CallFrame>? = null

    /**
     * Static lexicals.
     */
    @JvmField var oLexStatic: Array<SixModelObject?>? = null

    /**
     * Flags for each static lexical usage.
     */
    @JvmField var oLexStaticFlags: ByteArray? = null

    /**
     * Lexical name maps (produced lazily on first use). Note they are only
     * used when we do lexical lookup by name.
     */
    @JvmField var oLexicalMap: Object2IntOpenHashMap<String>? = null
    @JvmField var iLexicalMap: Object2IntOpenHashMap<String>? = null
    @JvmField var nLexicalMap: Object2IntOpenHashMap<String>? = null
    @JvmField var sLexicalMap: Object2IntOpenHashMap<String>? = null

    /**
     * Does this code object have a block exit handler?
     */
    @JvmField var hasExitHandler = false

    /**
     * Is this code object marked as a thunk?
     */
    @JvmField var isThunk = false

    /** Source location of the block's declaration, from the QAST node it was
     * compiled from; null/-1 when the compiler had none to give. */
    @JvmField var sourceFile: String? = null
    @JvmField var sourceLine = -1

    /** rawLine - sourceLine of the block's declaration: the constant shift
     * a #line directive puts between the raw compiled source (which the
     * LineNumberTable rows use) and sourceFile's numbering. */
    @JvmField var sourceLineDelta = 0

    fun oTryGetLexicalIdx(name: String): Int {
        val names = oLexicalNames
        if (names != null) {
            var map = oLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                oLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun iTryGetLexicalIdx(name: String): Int {
        val names = iLexicalNames
        if (names != null) {
            var map = iLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                iLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun uTryGetLexicalIdx(name: String): Int {
        val names = iLexicalNames
        if (names != null) {
            var map = iLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                iLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun nTryGetLexicalIdx(name: String): Int {
        val names = nLexicalNames
        if (names != null) {
            var map = nLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                nLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    fun sTryGetLexicalIdx(name: String): Int {
        val names = sLexicalNames
        if (names != null) {
            var map = sLexicalMap
            if (map == null) {
                map = Object2IntOpenHashMap<String>(names.size)
                for (i in names.indices)
                    map.put(names[i], i)
                sLexicalMap = map
            }
            return map.getOrDefault(name, -1)
        } else {
            return -1
        }
    }

    /**
     * Initializes the static code info data structure.
     */
    init {
        oLexicalNames?.let {
            oLexStatic = arrayOfNulls(it.size)
            oLexStaticFlags = ByteArray(it.size)
        }
        val t = mh.type()
        if (t.parameterCount() == 5 && t.parameterType(4) == ResumeStatus.Frame::class.java) {
            /* Old way; goes away after bootstrap. */
            mhResume = MethodHandles.insertArguments(mh, 0, null, null, null, null)
            this.mh = MethodHandles.insertArguments(mh, 4, null as Any?)
        }
        else if (t.parameterCount() >= 4 && t.parameterType(3) == ResumeStatus.Frame::class.java) {
            var resume = MethodHandles.insertArguments(mh, 0, null, null, null)
            when (argsExpectation) {
                ArgsExpectation.USE_BINDER ->
                    resume = MethodHandles.insertArguments(resume, 1, null as Any?)
                ArgsExpectation.NO_ARGS -> {
                    /* Nothing to insert. */
                }
                ArgsExpectation.OBJ ->
                    resume = MethodHandles.insertArguments(resume, 1, null as SixModelObject?)
                ArgsExpectation.OBJ_OBJ ->
                    resume = MethodHandles.insertArguments(resume, 1,
                        null as SixModelObject?, null as SixModelObject?)
                else ->
                    throw RuntimeException("Unhandled ArgsExpectation in StaticCodeInfo")
            }
            mhResume = resume
            this.mh = MethodHandles.insertArguments(mh, 3, null as Any?)
        }
    }

    public override fun clone(): StaticCodeInfo {
        try {
            val result = super.clone() as StaticCodeInfo
            result.oLexStatic?.let {
                result.oLexStatic = it.clone()
                result.oLexStaticFlags = result.oLexStaticFlags!!.clone()
            }
            return result
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
