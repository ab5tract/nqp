package org.raku.nqp.sixmodel.reprs

class NativeRefREPRData {
    companion object {
        /* Kinds of reference. */
        const val REF_LEXICAL: Short = 1
        const val REF_ATTRIBUTE: Short = 2
        const val REF_POSITIONAL: Short = 3
        const val REF_MULTIDIM: Short = 4
    }

    /* The primitive type of native reference this is (one of the values that
     * is valid for StorageSpec.boxed_primitive). */
    @JvmField var primitive_type: Short = 0

    /* The kind of reference this is. */
    @JvmField var ref_kind: Short = 0
}
