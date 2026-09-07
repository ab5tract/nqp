package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/**
 * A scalar container has a ContainerSpec hung off its STable. It should be a
 * subclass of this abstract base class.
 */
/**
 * A fetch that is a plain attribute read: the class handle and name of
 * the attribute. A container spec whose fetch is exactly that (Raku's
 * Scalar reads `$!value`) says so, and the engine's decont fast path
 * then reads the slot's field directly under a type guard, the way the
 * dispatch fold reads a guarded attribute source.
 */
class AttributeFetch(@JvmField val classHandle: SixModelObject, @JvmField val name: String)

abstract class ContainerSpec {
    /** The attribute a fetch reads, or null when fetching does more than read one. */
    open fun fetchAttribute(tc: ThreadContext): AttributeFetch? = null

    /* Fetches a value out of a container. Used for decontainerization. */
    abstract fun fetch(tc: ThreadContext, cont: SixModelObject): SixModelObject?

    /* Native value fetches. */
    abstract fun fetch_i(tc: ThreadContext, cont: SixModelObject): Long
    abstract fun fetch_n(tc: ThreadContext, cont: SixModelObject): Double
    abstract fun fetch_s(tc: ThreadContext, cont: SixModelObject): String?

    /* Stores a value in a container. Used for assignment. */
    abstract fun store(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject)

    /* Native container stores. */
    abstract fun store_i(tc: ThreadContext, cont: SixModelObject, value: Long)
    abstract fun store_n(tc: ThreadContext, cont: SixModelObject, value: Double)
    abstract fun store_s(tc: ThreadContext, cont: SixModelObject, value: String?)

    /* Stores a value in a container, without any checking of it (this
     * assumes an optimizer or something else already did it). Used for
     * assignment. */
    abstract fun storeUnchecked(tc: ThreadContext, cont: SixModelObject, obj: SixModelObject)

    /* Name of this container specification. */
    abstract fun name(): String

    /* Serializes the container data, if any. */
    abstract fun serialize(tc: ThreadContext, st: STable, writer: SerializationWriter)

    /* Deserializes the container data, if any. */
    abstract fun deserialize(tc: ThreadContext, st: STable, reader: SerializationReader)

    /* Can the container store values. Usually yes, so default to true. */
    open fun canStore(tc: ThreadContext, cont: SixModelObject): Boolean = true

    /* Atomic reference operations; not supported by default. */
    /* Nullable returns: rakudo's value_desc_cont spec forwards to Raku
     * code whose result can be nqp-null (the Java subclass could always
     * return null here). */
    open fun cas(tc: ThreadContext, cont: SixModelObject,
                 expected: SixModelObject, value: SixModelObject): SixModelObject? {
        throw ExceptionHandling.dieInternal(tc,
            "This kind of container does not support atomic compare and swap")
    }

    open fun atomic_load(tc: ThreadContext, cont: SixModelObject): SixModelObject? {
        throw ExceptionHandling.dieInternal(tc,
            "This kind of container does not support atomic load")
    }

    open fun atomic_store(tc: ThreadContext, cont: SixModelObject, value: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc,
            "This kind of container does not support atomic store")
    }
}
