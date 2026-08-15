package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.BoxedPrimitive

/**
 * What a native reference refers to.
 *
 * The numbers go into the serialization stream, so they are fixed. Zero is
 * what a type that never got composed serializes as, and NONE is how it
 * comes back.
 */
enum class RefKind(val spec: Int) {
    NONE(0),
    LEXICAL(1),
    ATTRIBUTE(2),
    POSITIONAL(3),
    MULTIDIM(4);

    companion object {
        private val bySpec = entries.associateBy { it.spec }
        private val byName = mapOf(
            "lexical" to LEXICAL,
            "attribute" to ATTRIBUTE,
            "positional" to POSITIONAL,
            "multidim" to MULTIDIM,
        )

        /** The kind a persisted spec number names. */
        @JvmStatic
        fun ofSpec(spec: Int): RefKind = bySpec[spec] ?: NONE

        /** The kind the nativeref protocol's "refkind" key names, if any. */
        @JvmStatic
        fun named(name: String?): RefKind? = byName[name]
    }
}

/** What a NativeRef type refers to and in what, worked out when composed. */
data class NativeRefREPRData(
    val primitiveType: BoxedPrimitive,
    val refKind: RefKind,
)
