package org.raku.nqp.runtime

import java.util.HashMap

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.reprs.VMHashInstance

/**
 * Contains the statically known details of a call site. These are shared rather
 * than being one for every single callsite in the code.
 */
class CallSiteDescriptor(flags: ByteArray, names: Array<String>?) {
    companion object {
        /* The various flags that can be set. */
        const val ARG_OBJ: Byte = 0
        const val ARG_INT: Byte = 1
        const val ARG_NUM: Byte = 2
        const val ARG_STR: Byte = 4
        const val ARG_UINT: Byte = 32
        const val ARG_NAMED: Byte = 8
        const val ARG_FLAT: Byte = 16

        /* Singleton empty name map. */
        private val emptyNameMap = Object2IntOpenHashMap<String>()
    }

    /* Flags, one per argument that is being passed. */
    @JvmField var argFlags: ByteArray

    /* Maps string names for named params to an int that has
     * arg index << 6 + type flag. */
    @JvmField var nameMap: Object2IntOpenHashMap<String>

    /* Number of normal positional arguments. */
    @JvmField var numPositionals = 0

    /* Are the any flattening things? */
    @JvmField var hasFlattening = false

    /* Original names list. */
    @JvmField var names: Array<String>?

    init {
        argFlags = flags
        nameMap = if (names != null)
            Object2IntOpenHashMap<String>()
        else
            emptyNameMap
        this.names = names

        var pos = 0
        var name = 0
        for (af in argFlags) {
            when (af.toInt()) {
                ARG_OBJ.toInt(),
                ARG_INT.toInt(),
                ARG_UINT.toInt(),
                ARG_NUM.toInt(),
                ARG_STR.toInt() -> {
                    pos++
                    numPositionals++
                }
                ARG_OBJ.toInt() or ARG_NAMED.toInt() ->
                    nameMap.put(names!![name++], (pos++ shl 6) or ARG_OBJ.toInt())
                ARG_INT.toInt() or ARG_NAMED.toInt() ->
                    nameMap.put(names!![name++], (pos++ shl 6) or ARG_INT.toInt())
                ARG_UINT.toInt() or ARG_NAMED.toInt() ->
                    nameMap.put(names!![name++], (pos++ shl 6) or ARG_UINT.toInt())
                ARG_NUM.toInt() or ARG_NAMED.toInt() ->
                    nameMap.put(names!![name++], (pos++ shl 6) or ARG_NUM.toInt())
                ARG_STR.toInt() or ARG_NAMED.toInt() ->
                    nameMap.put(names!![name++], (pos++ shl 6) or ARG_STR.toInt())
                ARG_OBJ.toInt() or ARG_FLAT.toInt() -> {
                    pos++
                    hasFlattening = true
                }
                ARG_OBJ.toInt() or ARG_FLAT.toInt() or ARG_NAMED.toInt() -> {
                    pos++
                    hasFlattening = true
                }
                else ->
                    /* NOTE: the Java original constructs this exception and
                     * never throws it; the do-nothing behavior is preserved. */
                    RuntimeException("Unhandled argument flag: $af")
            }
        }
    }

    /* Explodes any flattening parts. Creates and puts in place a new callsite
     * and enlarged-as-needed argument arrays.
     */
    fun explodeFlattening(cf: CallFrame, oldArgs: Array<Any?>): CallSiteDescriptor {
        val newFlags = ArrayList<Byte>()
        val newArgs = ArrayList<Any?>()
        val newNames = ArrayList<String>()
        var oldArgsIdx = 0
        var oldNameIdx = 0

        for (af in argFlags) {
            when (af.toInt()) {
                ARG_OBJ.toInt() or ARG_FLAT.toInt() -> {
                    val flatArray = oldArgs[oldArgsIdx++] as SixModelObject
                    val prim = flatArray.st.REPR.get_value_storage_spec(cf.tc, flatArray.st)!!.boxedPrimitive
                    val elems = flatArray.elems(cf.tc)
                    for (i in 0 until elems) {
                        if (prim == BoxedPrimitive.NONE) {
                            newArgs.add(flatArray.at_pos_boxed(cf.tc, i))
                            newFlags.add(ARG_OBJ)
                        } else {
                            flatArray.at_pos_native(cf.tc, i)
                            when (prim) {
                                BoxedPrimitive.INT -> {
                                    newArgs.add(cf.tc.native_i)
                                    newFlags.add(ARG_INT)
                                }
                                BoxedPrimitive.UINT -> {
                                    newArgs.add(cf.tc.native_i)
                                    newFlags.add(ARG_UINT)
                                }
                                BoxedPrimitive.NUM -> {
                                    newArgs.add(cf.tc.native_n)
                                    newFlags.add(ARG_NUM)
                                }
                                BoxedPrimitive.STR -> {
                                    newArgs.add(cf.tc.native_s)
                                    newFlags.add(ARG_STR)
                                }
                                else ->
                                    throw ExceptionHandling.dieInternal(cf.tc, "Unknown boxed primitive")
                            }
                        }
                    }
                }
                ARG_OBJ.toInt() or ARG_FLAT.toInt() or ARG_NAMED.toInt() -> {
                    val flatHash = oldArgs[oldArgsIdx++] as SixModelObject
                    if (flatHash is VMHashInstance) {
                        val storage: HashMap<String, SixModelObject?> = flatHash.storage
                        for (key in storage.keys) {
                            newNames.add(key)
                            newArgs.add(storage[key])
                            newFlags.add((ARG_OBJ.toInt() or ARG_NAMED.toInt()).toByte())
                        }
                    }
                    else {
                        throw ExceptionHandling.dieInternal(cf.tc, "Flattening named argument must have VMHash REPR instead of " + flatHash.st.REPR.name)
                    }
                }
                ARG_OBJ.toInt() or ARG_NAMED.toInt(),
                ARG_INT.toInt() or ARG_NAMED.toInt(),
                ARG_UINT.toInt() or ARG_NAMED.toInt(),
                ARG_NUM.toInt() or ARG_NAMED.toInt(),
                ARG_STR.toInt() or ARG_NAMED.toInt() -> {
                    newArgs.add(oldArgs[oldArgsIdx++])
                    newNames.add(names!![oldNameIdx++])
                    newFlags.add(af)
                }
                else -> {
                    newArgs.add(oldArgs[oldArgsIdx++])
                    newFlags.add(af)
                }
            }
        }

        val newFlagsArr = ByteArray(newFlags.size)
        for (i in newFlagsArr.indices)
            newFlagsArr[i] = newFlags[i]
        val newNamesArr = Array(newNames.size) { newNames[it] }
        val exploded = CallSiteDescriptor(newFlagsArr, newNamesArr)

        val args = arrayOfNulls<Any>(newArgs.size)
        for (i in 0 until newArgs.size)
            args[i] = newArgs[i]
        cf.tc.flatArgs = args

        return exploded
    }

    /** Create a new callframe and arg list to add an invokee argument at the front. */
    fun injectInvokee(tc: ThreadContext, oldArgs: Array<Any?>, invokee: SixModelObject?): CallSiteDescriptor {
        val newArgs = arrayOfNulls<Any>(oldArgs.size + 1)
        System.arraycopy(oldArgs, 0, newArgs, 1, oldArgs.size)
        newArgs[0] = invokee

        val newFlags = ByteArray(argFlags.size + 1)
        System.arraycopy(argFlags, 0, newFlags, 1, argFlags.size)
        newFlags[0] = ARG_OBJ

        tc.flatArgs = newArgs
        return CallSiteDescriptor(newFlags, names)
    }
}
