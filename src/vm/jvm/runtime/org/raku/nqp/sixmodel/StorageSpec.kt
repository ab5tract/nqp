package org.raku.nqp.sixmodel

/** Whether a representation wants to be a pointer or to sit inline. */
enum class Inlining { REFERENCE, INLINED }

/**
 * The primitive that an inlined representation unboxes to.
 *
 * The numbers are not ours to pick: nqp::objprimspec hands them to NQP
 * code, which indexes a table of runtime types with them, and the other
 * backends answer with the same ones. Hence the gap before UINT.
 */
enum class BoxedPrimitive(val spec: Int) {
    NONE(0),
    INT(1),
    NUM(2),
    STR(3),
    UINT(10);

    companion object {
        private val bySpec = entries.associateBy { it.spec }

        /** The primitive a persisted spec number names. */
        @JvmStatic
        fun ofSpec(spec: Int): BoxedPrimitive = bySpec[spec] ?: NONE
    }
}

/** A primitive that a representation can box and unbox. */
enum class Boxable { INT, NUM, STR }

/**
 * What storage a given representation needs if something of that
 * representation is to be embedded in another place. For any representation
 * that expects to be used as a kind of reference type, it will just want to
 * be a pointer. But for other things, they would prefer to be "inlined" into
 * the object.
 */
data class StorageSpec(
    /** Whether this is to be referenced or inlined. */
    val inlining: Inlining = Inlining.REFERENCE,

    /** For things that want to be inlined, the number of bits of storage
     *  they need. Ignored otherwise. */
    val bits: Short = 0,

    /** For things that are inlined, if they are just storage of a primitive
     *  type and can unbox, the primitive type they unbox to. */
    val boxedPrimitive: BoxedPrimitive = BoxedPrimitive.NONE,

    /** The types that this one can box and unbox. */
    val canBox: Set<Boxable> = emptySet(),

    /** For ints, whether it's an unsigned value. */
    val isUnsigned: Boolean = false,
) {
    companion object {
        @JvmField val BOXED = StorageSpec()

        /** An integer of the given width, signed or not. */
        @JvmStatic
        fun integer(bits: Short, unsigned: Boolean = false) = StorageSpec(
            inlining = Inlining.INLINED,
            bits = bits,
            boxedPrimitive = if (unsigned) BoxedPrimitive.UINT else BoxedPrimitive.INT,
            canBox = setOf(Boxable.INT),
            isUnsigned = unsigned,
        )

        /** A floating point number of the given width. */
        @JvmStatic
        fun number(bits: Short) = StorageSpec(
            inlining = Inlining.INLINED,
            bits = bits,
            boxedPrimitive = BoxedPrimitive.NUM,
            canBox = setOf(Boxable.NUM),
        )

        /** A string. */
        @JvmStatic
        fun string() = StorageSpec(
            inlining = Inlining.INLINED,
            boxedPrimitive = BoxedPrimitive.STR,
            canBox = setOf(Boxable.STR),
        )
    }
}
