package org.raku.nqp.sixmodel.reprs

import java.util.ArrayList

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject

class P6OpaqueREPRData {
    /**
     * The JVM class that will be used to represent state storage for this
     * type.
     */
    @JvmField var jvmClass: Class<*>? = null

    /**
     * Instance of jvmClass, cloned per allocation.
     */
    @JvmField var instance: P6OpaqueBaseInstance? = null

    /**
     * List of class handles that have attributes in this type.
     */
    @JvmField var classHandles: Array<SixModelObject?>? = null

    /**
     * Array of attribute name to hint mappings.
     */
    @JvmField var nameToHintMap: ArrayList<Object2IntOpenHashMap<String>>? = null

    /**
     * Auto-viv container types.
     */
    @JvmField var autoVivContainers: Array<SixModelObject?>? = null

    /**
     * Flattened STables for attributes (null if reference attribute).
     */
    @JvmField var flattenedSTables: Array<STable?>? = null

    /**
     * Is the type multiply inheriting?
     */
    @JvmField var mi = false

    /**
     * Unbox and delegation slots; -1 if no such unbox slot.
     */
    @JvmField var unboxIntSlot = -1

    /**
     * jesp: shared boxed instances of this type for small integer values
     * (MoarVM's intcache), filled on demand by the engine's bigint
     * arithmetic site when JESP_INTCACHE is set; null otherwise. Only ever
     * used for a type whose box target is a flattened bigint.
     */
    @JvmField var intCache: Array<SixModelObject?>? = null
    @JvmField var unboxNumSlot = -1
    @JvmField var unboxStrSlot = -1
    @JvmField var unboxObjSlot = -1
    @JvmField var posDelSlot = -1
    @JvmField var assDelSlot = -1
}
