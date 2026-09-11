package org.raku.nqp.runtime

import java.lang.ref.SoftReference
import java.util.Hashtable
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet

class ByteClassLoader : ClassLoader {
    private val refs = CopyOnWriteArraySet<String>()

    private val read: MutableMap<ClassLoader, String> = WeakHashMap()

    private val made: MutableMap<String, SoftReference<Class<*>>> = Hashtable()

    constructor() : super()

    /* parent is legitimately null when the runtime lives on the boot
     * classpath: getClassLoader() returns null for bootstrap-loaded
     * classes, and ClassLoader's constructor treats null as "use the
     * bootstrap loader". */
    constructor(parent: ClassLoader?) : super(parent)

    fun addRef(name: String): Boolean = refs.add(name)

    fun getMade(name: String?): Class<*>? {
        if (name != null && made.containsKey(name))
            return made[name]!!.get()
        return null
    }

    fun setMade(name: String?, klass: Class<*>): Class<*> {
        made[name ?: klass.name] = SoftReference(klass)
        return klass
    }

    fun getRead(child: ClassLoader?, name: String?): Class<*>? {
        if (name != null && made.containsKey(name))
            return made[name]!!.get()
        if (child != null && read.containsKey(child)) {
            val readName = read[child]
            if (made.containsKey(readName))
                return made[readName]!!.get()
        }
        return null
    }

    fun setRead(child: ClassLoader?, name: String?, klass: Class<*>): Class<*> {
        val actualName = name ?: klass.name
        if (child != null)
            read[child] = actualName
        made[actualName] = SoftReference(klass)
        return klass
    }

    @Throws(ClassFormatError::class)
    fun defineClass(name: String?, bytes: ByteArray): Class<*> {
        val existing = getMade(name)
        return existing ?: setMade(name, defineClass(name, bytes, 0, bytes.size))
    }
}
