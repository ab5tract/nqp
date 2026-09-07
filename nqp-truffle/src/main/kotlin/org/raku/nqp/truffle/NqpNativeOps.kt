package org.raku.nqp.truffle

/**
 * The native int and num ops as PE-visible arithmetic (jesp diamond 3).
 *
 * The generic table road ran `add_i`, `islt_i` and friends through a
 * `@TruffleBoundary` switch over a boxed `Object[]`, so a `while $i < $n`
 * loop paid a call and two allocations per compare. Here the op id is a
 * constant operand of an operation that specializes on `long`/`double`,
 * and the `when` over the constant folds to the one arithmetic
 * instruction. Division and modulus stay on the generic road: they need
 * the thread context (division by zero) and its exception conversion.
 */
object NqpNativeOps {

    @JvmStatic
    fun intBin(kind: Int, a: Long, b: Long): Long = when (kind) {
        NqpOps.OP_ADD_I -> a + b
        NqpOps.OP_SUB_I -> a - b
        NqpOps.OP_MUL_I -> a * b
        NqpOps.OP_BITAND_I -> a and b
        NqpOps.OP_BITOR_I -> a or b
        NqpOps.OP_BITXOR_I -> a xor b
        NqpOps.OP_BITSHIFTL_I -> a shl b.toInt()
        NqpOps.OP_BITSHIFTR_I -> a shr b.toInt()
        NqpOps.OP_ISEQ_I -> if (a == b) 1L else 0L
        NqpOps.OP_ISNE_I -> if (a != b) 1L else 0L
        NqpOps.OP_ISLT_I -> if (a < b) 1L else 0L
        NqpOps.OP_ISLE_I -> if (a <= b) 1L else 0L
        NqpOps.OP_ISGT_I -> if (a > b) 1L else 0L
        NqpOps.OP_ISGE_I -> if (a >= b) 1L else 0L
        else -> throw IllegalStateException("nqpp: not an int binary op: $kind")
    }

    @JvmStatic
    fun intUn(kind: Int, a: Long): Long = when (kind) {
        NqpOps.OP_NEG_I -> -a
        NqpOps.OP_ABS_I -> if (a < 0) -a else a
        NqpOps.OP_BITNEG_I -> a.inv()
        NqpOps.OP_NOT_I -> if (a == 0L) 1L else 0L
        else -> throw IllegalStateException("nqpp: not an int unary op: $kind")
    }

    @JvmStatic
    fun numBin(kind: Int, a: Double, b: Double): Double = when (kind) {
        NqpOps.OP_ADD_N -> a + b
        NqpOps.OP_SUB_N -> a - b
        NqpOps.OP_MUL_N -> a * b
        NqpOps.OP_DIV_N -> a / b
        else -> throw IllegalStateException("nqpp: not a num binary op: $kind")
    }

    @JvmStatic
    fun numCmp(kind: Int, a: Double, b: Double): Long = when (kind) {
        NqpOps.OP_ISEQ_N -> if (a == b) 1L else 0L
        NqpOps.OP_ISNE_N -> if (a != b) 1L else 0L
        NqpOps.OP_ISLT_N -> if (a < b) 1L else 0L
        NqpOps.OP_ISLE_N -> if (a <= b) 1L else 0L
        NqpOps.OP_ISGT_N -> if (a > b) 1L else 0L
        NqpOps.OP_ISGE_N -> if (a >= b) 1L else 0L
        else -> throw IllegalStateException("nqpp: not a num compare: $kind")
    }

    /** Whether a table op id is served by one of the operations above, and by which. */
    @JvmStatic
    fun kindOf(id: Int, nargs: Int): Int = when {
        nargs == 2 && (id == NqpOps.OP_ADD_I || id == NqpOps.OP_SUB_I || id == NqpOps.OP_MUL_I
            || id == NqpOps.OP_BITAND_I || id == NqpOps.OP_BITOR_I || id == NqpOps.OP_BITXOR_I
            || id == NqpOps.OP_BITSHIFTL_I || id == NqpOps.OP_BITSHIFTR_I
            || id == NqpOps.OP_ISEQ_I || id == NqpOps.OP_ISNE_I || id == NqpOps.OP_ISLT_I
            || id == NqpOps.OP_ISLE_I || id == NqpOps.OP_ISGT_I || id == NqpOps.OP_ISGE_I) -> INT_BIN
        nargs == 1 && (id == NqpOps.OP_NEG_I || id == NqpOps.OP_ABS_I || id == NqpOps.OP_BITNEG_I
            || id == NqpOps.OP_NOT_I) -> INT_UN
        nargs == 2 && (id == NqpOps.OP_ADD_N || id == NqpOps.OP_SUB_N || id == NqpOps.OP_MUL_N
            || id == NqpOps.OP_DIV_N) -> NUM_BIN
        nargs == 2 && (id == NqpOps.OP_ISEQ_N || id == NqpOps.OP_ISNE_N || id == NqpOps.OP_ISLT_N
            || id == NqpOps.OP_ISLE_N || id == NqpOps.OP_ISGT_N || id == NqpOps.OP_ISGE_N) -> NUM_CMP
        nargs == 1 && id == NqpOps.OP_NEG_N -> NUM_NEG
        else -> NONE
    }

    const val NONE = 0
    const val INT_BIN = 1
    const val INT_UN = 2
    const val NUM_BIN = 3
    const val NUM_CMP = 4
    const val NUM_NEG = 5
}
