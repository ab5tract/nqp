package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class MultiCacheInstance : SixModelObject() {
    companion object {
        private const val MD_CACHE_MAX_ARITY = 4
        private const val MD_CACHE_MAX_ENTRIES = 32
        private const val MD_CACHE_NULL = 0L
        private const val MD_CACHE_INT = 1L
        private const val MD_CACHE_NUM = 2L
        private const val MD_CACHE_STR = 3L
        private const val MD_CACHE_UINT = 10L
    }

    private var zeroArity: SixModelObject? = null
    private val arityCaches = arrayOfNulls<ArityCache>(MD_CACHE_MAX_ARITY)

    private class ArityCache {
        /* The number of entries we have in the cache. */
        var numEntries = 0

        /* This is a bunch of ST hashes, with natives special-cased. We allocate
         * it arity * MAX_ENTRIES big and go through it in arity sized chunks. */
        var typeIds: LongArray? = null

        /* Whether the entry is allowed to have named arguments. Doesn't say
         * anything about which ones, though. Something that is ambivalent
         * about named arguments to the degree it doesn't care about them
         * even tie-breaking (like NQP) can just throw such entries into the
         * cache. Things that do care should not make such cache entries. */
        var namedOK: BooleanArray? = null

        /* The results we return from the cache. */
        var results: Array<SixModelObject?>? = null
    }

    fun add(capture: CallCaptureInstance, result: SixModelObject?, tc: ThreadContext) {
        /* If there's flattenings, we can't cache. */
        if (capture.descriptor!!.hasFlattening)
            return

        val args = capture.args!!
        /* If it's zero arity, just stick it in that slot. */
        if (args.isEmpty()) {
            this.zeroArity = result
            return
        }

        /* Count number of positional args and build type tuple. */
        var numArgs = 0
        val argFlags = capture.descriptor!!.argFlags
        val argTup = LongArray(MD_CACHE_MAX_ARITY)
        var hasNamed = false
        for (i in argFlags.indices) {
            if (denotesPositionalArgument(argFlags[i])) {
                if (numArgs >= MD_CACHE_MAX_ARITY)
                    return
                argTup[numArgs++] = getTypeId(argFlags[i], args[i], tc)
            }
            else {
                if ((argFlags[i].toInt() and CallSiteDescriptor.ARG_FLAT.toInt()) != 0)
                    return
                hasNamed = true
            }
        }

        /* Again, if it's zero arity, just stick it in that slot. */
        if (numArgs == 0) {
            this.zeroArity = result
            return
        }

        /* If number of positional args exceeds arity limit, don't do anything. */
        if (numArgs >= MD_CACHE_MAX_ARITY)
            return

        /* The zero arity case was handled above.
         * At index 0 we have the cache for arity 1 */
        var ac = this.arityCaches[numArgs - 1]

        /* If the cache is saturated, don't do anything (we could instead do a random
         * replacement). */
        if (ac != null && ac.numEntries == MD_CACHE_MAX_ENTRIES)
            return

        /* If there's no entries yet, need to do some allocation. */
        if (ac == null) {
            ac = ArityCache()
            ac.typeIds = LongArray(numArgs * MD_CACHE_MAX_ENTRIES)
            ac.namedOK = BooleanArray(MD_CACHE_MAX_ENTRIES)
            ac.results = arrayOfNulls(MD_CACHE_MAX_ENTRIES)
            this.arityCaches[numArgs - 1] = ac
        }

        /* Add entry. */
        val insType = ac.numEntries * numArgs
        for (i in 0 until numArgs)
            ac.typeIds!![insType + i] = argTup[i]
        ac.results!![ac.numEntries] = result
        ac.namedOK!![ac.numEntries] = hasNamed
        ac.numEntries++
    }

    fun lookup(capture: CallCaptureInstance, tc: ThreadContext): SixModelObject? {
        /* If there's flattenings, we can't use the cache. */
        if (capture.descriptor!!.hasFlattening)
            return null

        /* Count number of positional args and build type tuple. */
        var numArgs = 0
        val args = capture.args!!
        val argFlags = capture.descriptor!!.argFlags
        val argTup = LongArray(MD_CACHE_MAX_ARITY)
        var hasNamed = false
        for (i in argFlags.indices) {
            if (denotesPositionalArgument(argFlags[i])) {
                if (numArgs >= MD_CACHE_MAX_ARITY)
                    return null
                argTup[numArgs++] = getTypeId(argFlags[i], args[i], tc)
            }
            else {
                if ((argFlags[i].toInt() and CallSiteDescriptor.ARG_FLAT.toInt()) != 0)
                    return null
                hasNamed = true
            }
        }

        /* If it's zero-arity, return result right off. */
        if (numArgs == 0)
            return if (hasNamed) null else this.zeroArity

        /* Look through entries. */
        val ac = this.arityCaches[numArgs - 1] ?: return null
        var tPos = 0
        for (i in 0 until ac.numEntries) {
            var match = true
            for (j in 0 until numArgs) {
                if (ac.typeIds!![tPos + j] != argTup[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                if (hasNamed == ac.namedOK!![i])
                    return ac.results!![i]
            }
            tPos += numArgs
        }

        return null
    }

    private fun denotesPositionalArgument(argFlag: Byte): Boolean {
        return argFlag == CallSiteDescriptor.ARG_INT || argFlag == CallSiteDescriptor.ARG_UINT
            || argFlag == CallSiteDescriptor.ARG_NUM
            || argFlag == CallSiteDescriptor.ARG_STR || argFlag == CallSiteDescriptor.ARG_OBJ
    }

    private fun getTypeId(flag: Byte, arg: Any?, tc: ThreadContext): Long {
        when (flag) {
            CallSiteDescriptor.ARG_INT ->
                return MD_CACHE_INT
            CallSiteDescriptor.ARG_UINT ->
                return MD_CACHE_UINT
            CallSiteDescriptor.ARG_NUM ->
                return MD_CACHE_NUM
            CallSiteDescriptor.ARG_STR ->
                return MD_CACHE_STR
            CallSiteDescriptor.ARG_OBJ -> {
                if (Ops.isnull(arg as SixModelObject?) == 1L)
                    return MD_CACHE_NULL
                val cont = arg as SixModelObject
                val decont = Ops.decont(cont, tc)
                var typeId = decont.st.hashCode().toLong() shl 4
                if (Ops.iscont_i(cont) == 1L || Ops.iscont_u(cont) == 1L || Ops.iscont_n(cont) == 1L || Ops.iscont_s(cont) == 1L) {
                    typeId = typeId or 4    /* Native ref vs. non-native ref */
                    typeId = typeId or 2    /* Native refs are always writable. */
                }
                else if (Ops.isrwcont(cont, tc) == 1L)
                    typeId = typeId or 2
                if (decont !is TypeObject)
                    typeId = typeId or 1
                if (Ops.iscont_u(cont) == 1L) /* Unsigned */
                    typeId = typeId or 8
                return typeId
            }
            else ->
                return -1
        }
    }
}
