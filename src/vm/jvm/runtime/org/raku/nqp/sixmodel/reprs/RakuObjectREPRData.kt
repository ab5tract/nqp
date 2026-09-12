package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.ConcurrentHashMap
import org.raku.nqp.sixmodel.SixModelObject

class RakuObjectREPRData {
    /** The canonical layout, from compose or from the serialized REPR data;
     *  null until then (an uncomposed type answers NO_HINT). */
    @JvmField var layout: RakuObjectLayout? = null

    /** Layouts for objects reblessed into this type while on a smaller
     *  storage class than the canonical one, by that class. Concurrent and
     *  eagerly built: two threads reblessing into this type at once must
     *  agree on one variant layout per storage class (the sited attribute
     *  caches key on layout identity, so two layouts for one class would
     *  make every such site miss, and one of the two maps would be lost). */
    @JvmField val variants: ConcurrentHashMap<Class<*>, RakuObjectLayout> = ConcurrentHashMap()

    /** jesp: shared boxed instances of this type for small integer values,
     *  filled on demand by the engine's bigint arithmetic site when
     *  JESP_INTCACHE is set; null otherwise. */
    @JvmField var intCache: Array<SixModelObject?>? = null

    /** The layout for an object of the given storage class: the canonical
     *  one when the classes match, else the variant for that class (built
     *  once). */
    fun layoutFor(cls: Class<*>): RakuObjectLayout {
        val canonical = layout ?: throw IllegalStateException("type not composed")
        if (canonical.storage == cls) return canonical
        return variants.computeIfAbsent(cls) {
            RakuObjectLayout(canonical.st, RakuObjectLayout.classIdOf(cls), canonical.kinds, canonical.specs,
                canonical.slotSTables, canonical.autoViv, canonical.classHandles, canonical.nameToSlot, canonical.mi,
                canonical.unboxIntSlot, canonical.unboxNumSlot, canonical.unboxStrSlot, canonical.unboxObjSlot,
                canonical.posDelSlot, canonical.assDelSlot, variant = true)
        }
    }
}
