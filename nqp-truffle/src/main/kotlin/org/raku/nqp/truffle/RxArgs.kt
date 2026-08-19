package org.raku.nqp.truffle

/**
 * The literal arguments of a subrule call.
 *
 * Most of a grammar's subrule calls take none, but the ones that report
 * errors nearly all do: `<.panic('...')>`, `<.obs('x', 'y')>`, and the
 * `<.FAILGOAL(')', 'argument list')>` that every `~` construct ends in.
 * Those arguments are written in the grammar's source and never computed, so
 * they can travel in the descriptor and the call can stay on the engine; an
 * argument that is a variable, an expression or a block cannot, and keeps the
 * whole rule on the bytecode path.
 *
 * The call site is derived from the kinds once and kept here rather than
 * rebuilt per call: there is one of these per subrule call site in the
 * program, so the derived descriptor is as constant as the call is. It is
 * held as [Any] so that nothing but [NqpCursor] needs the runtime's types --
 * the engine proper stays free of them.
 */
class RxArgs(
    /** One character per argument: [STR] for a string, [INT] for an int. */
    @JvmField val kinds: String,
    /** Strings and Longs, matching [kinds] position for position. */
    @JvmField val values: Array<Any?>,
) {
    init {
        require(kinds.length == values.size) {
            "an argument kind per value: $kinds vs ${values.size}"
        }
    }

    /** The runtime call site for [kinds], built on first use. */
    @JvmField var callSite: Any? = null

    fun kinds(): String = kinds

    fun count(): Int = values.size

    companion object {
        const val STR = 'S'
        const val INT = 'I'
    }
}
