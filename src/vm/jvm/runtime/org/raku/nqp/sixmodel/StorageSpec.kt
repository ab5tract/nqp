package org.raku.nqp.sixmodel

/* This data structure describes what storage a given representation
 * needs if something of that representation is to be embedded in
 * another place. For any representation that expects to be used
 * as a kind of reference type, it will just want to be a pointer.
 * But for other things, they would prefer to be "inlined" into
 * the object. */
class StorageSpec {
    companion object {
        /* Inlined or not. */
        const val REFERENCE: Short = 0
        const val INLINED: Short = 1

        /* Possible options for boxed primitives. */
        const val BP_NONE: Short = 0
        const val BP_INT: Short = 1
        const val BP_NUM: Short = 2
        const val BP_STR: Short = 3
        const val BP_UINT: Short = 10

        /* can_box bit field values. */
        const val CAN_BOX_INT: Short = 1
        const val CAN_BOX_NUM: Short = 2
        const val CAN_BOX_STR: Short = 4

        @JvmField val BOXED = StorageSpec()
    }

    /* 0 if this is to be referenced, anything else otherwise. */
    @JvmField var inlineable: Short = 0

    /* For things that want to be inlined, the number of bits of
     * storage they need. Ignored otherwise. */
    @JvmField var bits: Short = 0

    /* For things that are inlined, if they are just storage of a
     * primitive type and can unbox, this says what primitive type
     * that they unbox to. */
    @JvmField var boxed_primitive: Short = 0

    /* The types that this one can box/unbox to. */
    @JvmField var can_box: Short = 0

    /* For ints, whether it's an unsigned value. */
    @JvmField var is_unsigned: Short = 0
}
