package org.raku.nqp.sixmodel

/**
 * Specification of how we turn something into a boolean.
 */
class BoolificationSpec {
    companion object {
        /** Boolification mode flags. */
        const val MODE_CALL_METHOD = 0
        const val MODE_UNBOX_INT = 1
        const val MODE_UNBOX_NUM = 2
        const val MODE_UNBOX_STR_NOT_EMPTY = 3
        const val MODE_UNBOX_STR_NOT_EMPTY_OR_ZERO = 4
        const val MODE_NOT_TYPE_OBJECT = 5
        const val MODE_BIGINT = 6
        const val MODE_ITER = 7
        const val MODE_HAS_ELEMS = 8
    }

    /** Boolification mode. */
    @JvmField var Mode = 0

    /** A method to call to boolify, if applicable. */
    @JvmField var Method: SixModelObject? = null
}
