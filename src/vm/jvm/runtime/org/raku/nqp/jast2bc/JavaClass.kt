package org.raku.nqp.jast2bc

class JavaClass {
    @JvmField var name: String? = null
    @JvmField var bytes: ByteArray? = null
    @JvmField var serialized: ByteArray? = null
    @JvmField var hasMain = false
}
