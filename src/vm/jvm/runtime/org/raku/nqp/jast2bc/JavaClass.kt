package org.raku.nqp.jast2bc

class JavaClass {
    @JvmField var name: String? = null
    @JvmField var bytes: ByteArray? = null
    @JvmField var serialized: ByteArray? = null
    /* The unit's engine programs, joined length-prefixed, UTF-8; written
     * as a jar sidecar like the serialized SC (see JAST::Class). */
    @JvmField var codePrograms: ByteArray? = null
    @JvmField var hasMain = false
    @JvmField var nestedClassNames: List<String> = emptyList()
}
