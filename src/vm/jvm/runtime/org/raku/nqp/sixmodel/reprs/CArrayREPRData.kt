package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

/** What a CArray holds, worked out the first time one is allocated. */
data class CArrayREPRData(
    val elemType: SixModelObject,
    val elemKind: ElemKind,
    /** Width of the element in bits, for the numeric kinds. */
    val elemSize: Short,
    /** Width of one element in C memory, in bytes. */
    val elemBytes: Long,
) {
    enum class ElemKind { INTEGER, NUMERIC, STRING, CPOINTER, CARRAY, CSTRUCT, CPPSTRUCT, CUNION }
}
