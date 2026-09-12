package org.raku.nqp.truffle

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode

/**
 * Runs one pattern against one target; the unit partial evaluation compiles.
 *
 * The tree built for a source is the pattern, so each distinct pattern gets
 * its own call target and its own compiled code -- a matcher for a grammar
 * that only exists at run time, which is the thing a compile-time approach
 * cannot reach.
 *
 * Arguments: the target string, the start position, the cursor, and --
 * for resumability -- a one-slot array the match leaves its live choice
 * state in, and a saved state to resume from instead of starting fresh.
 */
class RxMatchRootNode(language: TruffleLanguage<*>, program: RxProgram) : RootNode(language) {

    @Child private var vm: RxVmNode = RxVmNode(program)

    override fun execute(frame: VirtualFrame): Any {
        val args = frame.arguments
        val target = args[0] as String
        val pos = if (args.size > 1) args[1] as Int else 0
        val cursor = if (args.size > 2) args[2] as RxCursor else RxCursor.OfString(target)
        @Suppress("UNCHECKED_CAST")
        val stateOut = if (args.size > 3) args[3] as Array<RxVmNode.EngineState?>? else null
        val resume = if (args.size > 4) args[4] as RxVmNode.EngineState? else null
        if (resume != null) return vm.resume(cursor, resume, stateOut!!)
        if (stateOut != null) return vm.match(cursor, pos, stateOut)
        return vm.match(cursor, pos)
    }

    companion object {
        /**
         * A source is either a descriptor the backend already flattened or
         * a pattern to parse. The first is how a grammar arrives: by the
         * time the JVM backend emits code it holds the rule as QAST::Regex,
         * so parsing text again would be both wasted work and a second
         * chance to disagree about what the rule means. The second is the
         * harnesses' road (RxCheck, RxBench).
         */
        @JvmStatic
        fun compileSource(source: String): RxProgram {
            if (RxWire.isDescriptor(source)) {
                val d = RxWire.decode(source)
                return RxDescriptor.compile(d.code, d.pool)
            }
            return RxProgram.compile(RxParser.parse(source))
        }

        /** The matcher call target for [source]. */
        @JvmStatic
        fun create(language: TruffleLanguage<*>, source: String): CallTarget =
            RxMatchRootNode(language, compileSource(source)).callTarget
    }
}

/** A root that answers one constant: how parse hands an executable back to eval. */
class ConstantRootNode(language: TruffleLanguage<*>, private val value: Any) : RootNode(language) {
    override fun execute(frame: VirtualFrame): Any = value
}
