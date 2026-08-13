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
    }

    /**
     * Any data specific to this type that the REPR wants to keep.
     */
    @JvmField var REPRData: Any? = null

    /**
     * The type-object.
     */
    @JvmField var WHAT: SixModelObject? = null

    /**
     * Info for types that are parametric or parameterized.
     */
    @JvmField var parametricity: AbstractParametricity? = null

    /**
     * By-name method dispatch cache.
     */
    @JvmField var MethodCache: MutableMap<String, SixModelObject>? = null

    /**
     * The computed v-table for static dispatch.
     */
    @JvmField var VTable: Array<SixModelObject?>? = null

    /**
     * Array of type objects. If this is set, then it is expected to contain
     * the type objects of all types that this type is equivalent to (e.g.
     * all the things it isa and all the things it does).
     */
    @JvmField var TypeCheckCache: Array<SixModelObject?>? = null

    /**
     * The type checking mode and method cache mode.
     */
    @JvmField var ModeFlags = 0

    /**
     * An ID solely for use in caches that last a VM instance. Thus it
     * should never, ever be serialized and you should NEVER make a
     * type directory based upon this ID. Otherwise you'll create memory
     * leaks for anonymous types, and other such screwups.
     */
    @JvmField var TypeCacheId = 0

    /**
     * If this is a container, then this contains information needed in
     * order to fetch the value in it. If not, it'll be null, which can
     * be taken as a "not a container" indication.
     */
    @JvmField var ContainerSpec: ContainerSpec? = null

    /**
     * If this is invokable, then this contains information needed to
     * figure out how to invoke it. If not, it'll be null.
     */
    @JvmField var InvocationSpec: InvocationSpec? = null

    /**
     * Information - if any - about how we can turn something of this type
     * into a boolean.
     */
    @JvmField var BoolificationSpec: BoolificationSpec? = null

    /**
     * The underlying package stash.
     */
    @JvmField var WHO: SixModelObject? = null

    /**
     * Serialization context that this s-table belongs to.
     */
    @JvmField var sc: SerializationContext? = null

    /**
     * The HLL that this type is owned by, if any.
     */
    @JvmField var hllOwner: HLLConfig? = null

    /**
     * The role that the type plays in the HLL, if any.
     */
    @JvmField var hllRole = 0L

    /**
     * Debug name for the type, for understanding what it is while debugging.
     */
    @JvmField var debugName: String? = null
}
