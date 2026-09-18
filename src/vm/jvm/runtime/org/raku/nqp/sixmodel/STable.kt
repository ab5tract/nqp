package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.HLLConfig

/**
 * An STable (Shared Table) represents a given HOW/REPR pairing, and thus a type.
 */
class STable(
    /**
     * The representation operation table.
     */
    @JvmField var REPR: REPR,

    /**
     * The meta-object. Null while the KnowHOW bootstrap is creating the
     * very first types and for freshly stubbed STables during
     * deserialization.
     */
    @JvmField var HOW: SixModelObject?,
) {
    companion object {
        /**
         * Controls the way that type checks are performed. By default, if there is
         * a type check cache we treat it as definitive. However, it's possible to
         * declare that in the case the type check cache has no entry we should fall
         * back to asking the .HOW.type_check method (set TYPE_CHECK_CACHE_THEN_METHOD).
         * While a normal type check asks a value if it supports another type, the
         * TYPE_CHECK_NEEDS_ACCEPTS flag results in a call to .accepts_type on the
         * HOW of the thing we're checking the value against, giving it a chance to
         * decide answer. */
        const val TYPE_CHECK_CACHE_DEFINITIVE = 0
        const val TYPE_CHECK_CACHE_THEN_METHOD = 1
        const val TYPE_CHECK_NEEDS_ACCEPTS = 2
        const val TYPE_CHECK_CACHE_FLAG_MASK = 3

        /**
         * This flag is set if we consider the method cache authoritative.
         */
        const val METHOD_CACHE_AUTHORITATIVE = 4

        /**
         * HLL type roles.
         */
        @Suppress("unused") private const val HLL_ROLE_NONE = 0
        @Suppress("unused") private const val HLL_ROLE_INT = 1
        @Suppress("unused") private const val HLL_ROLE_NUM = 2
        @Suppress("unused") private const val HLL_ROLE_STR = 3
        @Suppress("unused") private const val HLL_ROLE_ARRAY = 4
        @Suppress("unused") private const val HLL_ROLE_HASH = 5
        @Suppress("unused") private const val HLL_ROLE_CODE = 6

        /**
         * Indicates that there's no attribute access hint.
         */
        const val NO_HINT = -1L

        /** Every publish in the process; printed as `publishes=` in the dispatch stats line. */
        @JvmField val PUBLISHES = java.util.concurrent.atomic.LongAdder()
    }

    /** REPR-specific data (a RakuObjectREPRData for P6opaque). REPR-owned; a change republishes [state]. */
    @JvmField var REPRData: Any? = null

    /** The type object. */
    lateinit var WHAT: SixModelObject

    /** Parametric/parameterized type data; a growing lookup table, so a cache, not a fact. */
    @JvmField var parametricity: AbstractParametricity? = null

    /**
     * The type's published facts. Every reader goes through here; the only
     * writer is [publish]. Volatile: a publish on one thread is seen whole
     * by every other (the state object itself is immutable).
     */
    @Volatile @JvmField var state: TypeState = TypeState.initial()

    /** The stash / package. */
    @JvmField var WHO: SixModelObject? = null

    /** The serialization context this STable belongs to, if any. */
    @JvmField var sc: SerializationContext? = null

    /** This STable's index in [sc]'s STable root set; -1 while in none. */
    @JvmField var scIdx: Int = -1

    /** The HLL owner's debug name for the type, if it set one. */
    @JvmField var debugName: String? = null

    /**
     * Installs the next state, then invalidates the previous one's assumption
     * -- in that order, so a thread that read a valid assumption and then reads
     * the state sees a state at least as new as the assumption (the ordering
     * NqpDispatch.Cache.publish uses).
     */
    @Synchronized fun publish(next: TypeState) {
        val old = state
        state = next
        old.assumption.invalidate()
        PUBLISHES.increment()
    }

    /**
     * The read-modify-write every fact writer wants: [f] is applied to the
     * current state and its result published, all under [publish]'s lock, so
     * no writer ever builds a successor from a state it no longer owns (two
     * concurrent plain publishes could otherwise leave a state installed whose
     * assumption is never invalidated).
     */
    @Synchronized fun update(f: (TypeState) -> TypeState) { publish(f(state)) }

    /** The same facts under a fresh assumption: for a change outside the state (REPR data). */
    fun republish() = update { it.withFacts() }
}
