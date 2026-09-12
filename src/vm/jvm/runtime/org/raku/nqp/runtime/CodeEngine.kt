package org.raku.nqp.runtime

/**
 * The bytecode side of the Truffle code engine -- the general-code analog
 * of [GrammarEngine], with the same classloader split and the same
 * find-by-name bridge (see that file for why the two halves cannot see
 * each other directly).
 *
 * A block the encoder covers compiles to a method whose body is one call
 * to [CodeEngines.codeRun]: the same prologue (CallFrame creation) and
 * postlude (leave, handler dispatch) as any compiled block, with the
 * statements -- parameter binding included -- executed by the engine's
 * program instead of emitted bytecode. As with regexes, the choice is
 * made at compile time and there is no bytecode body to fall back to.
 */
interface CodeEngine {
    /**
     * Turns one encoded block program into something runnable. Called once
     * per program; the result is opaque here and handed back to [run].
     * [name] is the block's name -- what the program's Source is called in
     * compilation traces and statistics.
     */
    fun compile(encoded: String, name: String): Any

    /**
     * Runs one compiled block body inside the frame the caller built.
     * Everything the emitted body would have done happens here, the
     * return-register store included, so the calling method has nothing
     * left to do but its ordinary postlude.
     */
    fun run(
        program: Any,
        cu: CompilationUnit,
        tc: ThreadContext,
        cf: CallFrame,
        csd: CallSiteDescriptor,
        args: Array<Any?>,
    )
}

/**
 * Finds the code engine, or establishes that there isn't one. The same
 * contract as [GrammarEngines]: absence is supported only for a build
 * without the truffle module, anything else is reported loudly, and
 * `NQP_JVM_TRUFFLE=require` turns absence into failure.
 */
object CodeEngines {
    private const val IMPL = "org.raku.nqp.truffle.NqpCodeEngine"

    private val required: Boolean = System.getenv("NQP_JVM_TRUFFLE") == "require"

    private val engine: CodeEngine? by lazy { load() }

    /**
     * The compiled program per encoded string. Programs resolve their
     * run-owned objects (WVals, callees) through the frame at run time
     * rather than holding them, but the cache is still cleared per
     * eval-server run with the other caches -- pinning a finished run is
     * the thrice-learned failure mode, not a risk worth a lookup saved.
     */
    private val programs = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun clearProgramCache() = programs.clear()

    /** Traces each engine-run block entry as `code> <name>`. */
    private val trace: Boolean = System.getenv("NQP_CODE_TRACE") != null


    @JvmStatic
    fun get(): CodeEngine? = engine

    /**
     * The entry point for jar-bound bodies: the program travels in the
     * unit artifact's program table and is referenced by index.
     */
    @JvmStatic
    fun codeRunIdx(
        idx: Int,
        cu: CompilationUnit,
        tc: ThreadContext,
        cf: CallFrame,
        csd: CallSiteDescriptor,
        args: Array<Any?>?,
    ) = codeRun(cu.engineProgram(idx), cu, tc, cf, csd, args)

    /** Reached only through codeRunIdx: no compiler emits a string-constant
     *  program since milestone 3, and no stage0 body ever called this
     *  directly (javap over stage0's jars, 2026-09-09). */
    private fun codeRun(
        encoded: String,
        cu: CompilationUnit,
        tc: ThreadContext,
        cf: CallFrame,
        csd: CallSiteDescriptor,
        args: Array<Any?>?,
    ) {
        val engine = engine ?: throw IllegalStateException(
            "this code was compiled with the code engine, which is not available at run time:" +
            " the truffle module is missing from the class path.")
        val program = programs.computeIfAbsent(encoded) { engine.compile(it, cf.codeRef?.name ?: "<anon>") }
        if (trace) System.err.println("code> " + (cf.codeRef?.name ?: "<anon>"))
        /* Let a dispatch that resolves to this block skip the stub next
         * time (see StaticCodeInfo.engineTarget). One program per encoded
         * string, so a later run can only write the same value. */
        val sci = cf.codeRef?.staticInfo
        if (sci != null && sci.engineTarget == null) sci.engineTarget = program
        engine.run(program, cu, tc, cf, csd, args ?: emptyArray())
    }

    /**
     * The block's engine target, compiling its program from the unit on
     * first need. Null for a class-road block that has not run yet (no
     * program index) or when there is no engine. Synchronized on the
     * static info so two threads racing on the first call agree on one
     * target. Compiles through the same per-string cache as codeRun, so a
     * program reached first by a stub and later by the direct road (or the
     * reverse) compiles once.
     */
    @JvmStatic
    fun materialize(sci: StaticCodeInfo): Any? {
        sci.engineTarget?.let { return it }
        if (sci.programIndex < 0) return null
        val engine = engine ?: return null
        synchronized(sci) {
            sci.engineTarget?.let { return it }
            val program = programs.computeIfAbsent(sci.compUnit.engineProgram(sci.programIndex)) {
                engine.compile(it, sci.methodName ?: "<anon>")
            }
            sci.engineTarget = program
            return program
        }
    }

    /** The artifact road's block body: what codeRunIdx is for a stub. */
    @JvmStatic
    fun codeRunUnit(
        sci: StaticCodeInfo,
        cu: CompilationUnit,
        tc: ThreadContext,
        cf: CallFrame,
        csd: CallSiteDescriptor,
        args: Array<Any?>?,
    ) {
        val engine = engine ?: throw IllegalStateException(
            "this unit was compiled with the code engine, which is not available at run time:" +
            " the truffle module is missing from the class path.")
        val program = materialize(sci) ?: throw IllegalStateException(
            "block ${cf.codeRef?.name ?: "<anon>"} of unit ${cu.unitId()} has no program")
        if (trace) System.err.println("code> " + (cf.codeRef?.name ?: "<anon>"))
        engine.run(program, cu, tc, cf, csd, args ?: emptyArray())
    }

    private fun load(): CodeEngine? {
        try {
            val cls = Class.forName(IMPL, true, ClassLoader.getSystemClassLoader())
            return cls.getDeclaredConstructor().newInstance() as CodeEngine
        } catch (e: ClassNotFoundException) {
            check(!required) { "NQP_JVM_TRUFFLE=require, but $IMPL is not on the class path" }
            return null
        } catch (e: ReflectiveOperationException) {
            return unavailable(e)
        } catch (e: LinkageError) {
            return unavailable(e)
        }
    }

    private fun unavailable(cause: Throwable): CodeEngine? {
        if (required) throw IllegalStateException("NQP_JVM_TRUFFLE=require, but $IMPL did not load", cause)
        System.err.println("nqp: code engine unavailable ($cause)")
        return null
    }
}
