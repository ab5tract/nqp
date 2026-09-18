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

    how: SixModelObject?,
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

    /**
     * The meta-object. Null while the KnowHOW bootstrap is creating the
     * very first types. Under the demand reader (milestone 8, Phase C) a
     * deserialized STable holds only its HOW's SC and index until the
     * first read: a type reached by a type check never pulls its metaclass
     * and method tables. Every Kotlin reader keeps `st.HOW`; the setter
     * clears the pending pair.
     */
    @Volatile private var howField: SixModelObject? = how
    @Volatile private var howSC: SerializationContext? = null
    private var howIdx = -1
    var HOW: SixModelObject?
        get() = howField ?: resolvePendingHow()
        /* Field first, then the SC: a concurrent getter that sees the pair
         * cleared has already seen the value it was cleared for. */
        set(v) { howField = v; howSC = null }

    /**
     * Resolves the pending HOW, or re-reads the field when another thread
     * resolved it first (a null SC means "no longer pending", never "no HOW").
     * A resolution from INSIDE a drain gets a raw stub the drain may still
     * roll back, and a rolled-back stub would stay here forever with its
     * pending pair gone, so it is returned but not cached: the pair survives
     * and the first read outside a drain caches the published object.
     */
    private fun resolvePendingHow(): SixModelObject? {
        val s = howSC ?: return howField
        val v = s.getObject(howIdx)
        if (SerializationReader.LOCK.isHeldByCurrentThread() && SerializationReader.current != null) return v
        howField = v
        howSC = null
        return v
    }

    /* The index before the SC: the volatile write of the SC publishes it. */
    fun setPendingHow(sc: SerializationContext, idx: Int) { howField = null; howIdx = idx; howSC = sc }

    /**
     * The stash / package. Pending like HOW: a stash's hash reaches every
     * symbol under it, a large share of a setting's SC.
     */
    @Volatile private var whoField: SixModelObject? = null
    @Volatile private var whoSC: SerializationContext? = null
    private var whoIdx = -1
    var WHO: SixModelObject?
        get() = whoField ?: resolvePendingWho()
        /* Field first, then the SC, as for HOW. */
        set(v) { whoField = v; whoSC = null }

    /** Exactly [resolvePendingHow]'s shape, for the same two reasons. */
    private fun resolvePendingWho(): SixModelObject? {
        val s = whoSC ?: return whoField
        val v = s.getObject(whoIdx)
        if (SerializationReader.LOCK.isHeldByCurrentThread() && SerializationReader.current != null) return v
        whoField = v
        whoSC = null
        return v
    }

    /* The index before the SC: the volatile write of the SC publishes it. */
    fun setPendingWho(sc: SerializationContext, idx: Int) { whoField = null; whoIdx = idx; whoSC = sc }

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
