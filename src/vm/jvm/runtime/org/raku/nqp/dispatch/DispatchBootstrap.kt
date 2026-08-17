package org.raku.nqp.dispatch

import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodHandles.Lookup
import java.lang.invoke.MethodType
import java.lang.invoke.MutableCallSite

import org.raku.nqp.runtime.ThreadContext

/**
 * A dispatch callsite, which doubles as its inline cache: the programs recorded
 * here, tried in turn. Each dispatch instruction in the bytecode gets one of
 * these, which is what gives a callsite its own cache without any bookkeeping.
 */
class DispatchCallSite(type: MethodType) : MutableCallSite(type) {
    @Volatile @JvmField var programs: Array<DispatchProgram> = emptyArray()

    /**
     * Adds a program to the cache. Past the limit the callsite is megamorphic
     * and we stop growing: dispatches still work by recording each time.
     */
    fun install(program: DispatchProgram) {
        val current = programs
        if (current.size < Dispatch.MAX_PROGRAMS)
            programs = current + program
    }
}

/** Links dispatch instructions to the dispatch machinery. */
object DispatchBootstrap {
    @JvmStatic
    fun dispatch_noa(caller: Lookup, indyName: String, type: MethodType): CallSite {
        try {
            val handlerType = MethodType.methodType(Void.TYPE, DispatchCallSite::class.java,
                String::class.java, Integer.TYPE, ThreadContext::class.java,
                Array<Any>::class.java)
            val handler = caller.findStatic(Dispatch::class.java, "dispatch", handlerType)

            /* Curry the callsite in, and gather the dispatch arguments (which
             * follow the dispatcher name, callsite index and thread context)
             * into an array. */
            val site = DispatchCallSite(type)
            site.setTarget(MethodHandles
                .insertArguments(handler, 0, site)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))
            return site
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
