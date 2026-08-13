package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Provides a simple way for HLL authors to hang data off of ThreadContext and
 * GlobalContext. The implementation uses a monomorphic thread-local cache for
 * speed when a single HLL is running most of the time.
 *
 * T and G must have public constructors taking a single ThreadContext
 * argument; they are called as needed to initialize.
 */
class ContextKey<T, G>(tclass: Class<T>, gclass: Class<G>) {
    private val tcon: MethodHandle
    private val gcon: MethodHandle

    init {
        val typ = MethodType.methodType(Void.TYPE, ThreadContext::class.java)
        try {
            tcon = MethodHandles.publicLookup().findConstructor(tclass, typ)
            gcon = MethodHandles.publicLookup().findConstructor(gclass, typ)
        } catch (ex: Exception) {
            throw RuntimeException("Required constructors not found for ContextKey", ex)
        }
    }

    /** Gets the thread context extension, creating it if needed. */
    @Suppress("UNCHECKED_CAST")
    fun getTC(tc: ThreadContext): T {
        if (tc.hllThreadKey === this)
            return tc.hllThreadData as T
        return getTCHeavy(tc) as T
    }

    private fun getTCHeavy(tc: ThreadContext): Any {
        var t = tc.hllThreadAll[this]

        if (t == null) {
            t = try {
                tcon.invoke(tc)
            } catch (ex: Throwable) {
                throw RuntimeException(ex)
            }
            tc.hllThreadAll[this] = t
        }

        tc.hllThreadKey = this
        tc.hllThreadData = t

        return t
    }

    /** Gets the global context extension, creating it if needed. */
    @Suppress("UNCHECKED_CAST")
    fun getGC(tc: ThreadContext): G {
        if (tc.hllGlobalKey === this)
            return tc.hllGlobalData as G
        return getGCHeavy(tc) as G
    }

    private fun getGCHeavy(tc: ThreadContext): Any {
        var g = tc.hllGlobalAllCache[this]

        if (g == null) {
            synchronized(tc.gc.hllGlobalAllLock) {
                g = tc.gc.hllGlobalAll[this]

                if (g == null) {
                    g = try {
                        gcon.invoke(tc)
                    } catch (ex: Throwable) {
                        throw RuntimeException(ex)
                    }
                    tc.gc.hllGlobalAll[this] = g
                }
            }

            tc.hllGlobalAllCache[this] = g
        }

        tc.hllGlobalKey = this
        tc.hllGlobalData = g

        return g!!
    }
}
