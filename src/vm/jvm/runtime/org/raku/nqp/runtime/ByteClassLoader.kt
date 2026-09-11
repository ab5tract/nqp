package org.raku.nqp.runtime

import java.lang.ref.SoftReference
import java.util.Hashtable

/** The loader for the plain classes generated at runtime (the C-struct
 *  bodies, the interop adaptors). Units are artifacts and do not come
 *  through here. */
class ByteClassLoader : ClassLoader {
    private val made: MutableMap<String, SoftReference<Class<*>>> = Hashtable()

    constructor() : super()

    /* parent is legitimately null when the runtime lives on the boot
     * classpath: getClassLoader() returns null for bootstrap-loaded
     * classes, and ClassLoader's constructor treats null as "use the
     * bootstrap loader". */
    constructor(parent: ClassLoader?) : super(parent)

    fun getMade(name: String?): Class<*>? {
        if (name != null && made.containsKey(name))
            return made[name]!!.get()
        return null
    }

    fun setMade(name: String?, klass: Class<*>): Class<*> {
        made[name ?: klass.name] = SoftReference(klass)
        return klass
    }

    @Throws(ClassFormatError::class)
    fun defineClass(name: String?, bytes: ByteArray): Class<*> {
        val existing = getMade(name)
        return existing ?: setMade(name, defineClass(name, bytes, 0, bytes.size))
    }
}
