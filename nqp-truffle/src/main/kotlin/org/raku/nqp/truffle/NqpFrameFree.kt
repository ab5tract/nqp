package org.raku.nqp.truffle

/**
 * Runtime overrides for frame-free blocks (jesp diamond 5), applied the
 * first time a block runs -- through the bytecode stub, with a frame --
 * before any direct road adopts its target. A block the wire marks
 * frame-free can be run framed instead, by name, without rebuilding
 * anything: the encoding is otherwise identical, so this bisects a
 * frame-free misbehaviour in seconds against one set of jars.
 *
 *   NQP_FRAMEFREE=0          every block runs framed
 *   NQP_FRAMEFREE_ONLY=a,b   only the named blocks run frame-free
 *   NQP_FRAMEFREE_SKIP=a,b   the named blocks run framed
 *   NQP_FRAMEFREE_TRACE=1    narrate each block's decision on stderr
 */
object NqpFrameFree {
    /**
     * The wire's exit-handler invariant, checked once per root where a
     * CodeRef first meets its program (NqpCodeEngine.runProgram), not on
     * every frame-free entry: a block with an exit handler must leave
     * through CallFrame.leave(), so the encoder forces needsFrame for it.
     * A frame-free root here means encoder and runtime disagree and the
     * handler would silently never run.
     */
    @JvmStatic
    fun checkExitHandler(root: NqpRootNode, cr: org.raku.nqp.runtime.CodeRef?) {
        val sci = cr?.staticInfo ?: return
        if (sci.hasExitHandler && !root.needsFrame)
            throw IllegalStateException(
                "exit-handler block encoded frame-free: " +
                (if (cr.name.isNullOrEmpty()) "<anon>" else cr.name))
    }

    private val allFramed: Boolean = System.getenv("NQP_FRAMEFREE") == "0"
    private val only: Set<String>? = System.getenv("NQP_FRAMEFREE_ONLY")?.split(',')?.toSet()
    private val skip: Set<String>? = System.getenv("NQP_FRAMEFREE_SKIP")?.split(',')?.toSet()
    private val trace: Boolean = System.getenv("NQP_FRAMEFREE_TRACE") != null

    /** Applies the overrides to a root about to run for the first time. */
    @JvmStatic
    fun apply(root: NqpRootNode, name: String) {
        if (root.needsFrame) return
        val forceFramed = allFramed
            || (only != null && name !in only)
            || (skip != null && name in skip)
        if (forceFramed) root.needsFrame = true
        if (trace) System.err.println("framefree> " + (if (forceFramed) "framed " else "free   ") + name)
    }
}
