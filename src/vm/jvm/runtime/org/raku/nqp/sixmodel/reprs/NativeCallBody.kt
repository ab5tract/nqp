package org.raku.nqp.sixmodel.reprs

import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle

import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

/* Holds a description of a native call site. */
class NativeCallBody {
    companion object {
        /* Flag for whether we should free a string after passing it or not. These
         * are going away once the array handling is refactored. */
        const val ARG_NO_FREE_STR: Byte = 0
        const val ARG_FREE_STR: Byte = 1
        const val ARG_FREE_STR_MASK: Byte = 1
    }

    @JvmField var entryPoint: MemorySegment? = null
    @JvmField var handle: MethodHandle? = null
    /* A void-returning handle onto the same symbol, for the C++ constructor
     * case, where the invocant we allocated is the result rather than
     * whatever the function nominally returns. */
    @JvmField var ctorHandle: MethodHandle? = null
    @JvmField var argTypes: Array<ArgType>? = null
    /* Handles for the variadic shapes this site has been called with,
     * keyed by the appended argument layouts. */
    @JvmField var variadicHandles: java.util.concurrent.ConcurrentHashMap<String, MethodHandle>? = null
    @JvmField var argInfo: Array<SixModelObject?>? = null
    @JvmField var retType: ArgType? = null
}
