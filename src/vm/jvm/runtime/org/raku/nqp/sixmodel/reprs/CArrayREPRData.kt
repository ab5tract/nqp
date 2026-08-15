package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class CArrayREPRData {
    @JvmField var elem_size: Short = 0
    @JvmField var elem_type: SixModelObject? = null
    @JvmField var elem_kind: ElemKind? = null
    /* Width of one element in C memory, in bytes. */
    @JvmField var elem_bytes: Long = 0

    enum class ElemKind { INTEGER, NUMERIC, STRING, CPOINTER, CARRAY, CSTRUCT, CPPSTRUCT, CUNION }
}
