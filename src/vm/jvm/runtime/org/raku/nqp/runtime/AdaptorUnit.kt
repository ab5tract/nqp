package org.raku.nqp.runtime

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * The unit behind a generated Java-interop adaptor class: one code ref per
 * static qb_N callout, built from a method handle bound to this unit (the
 * callouts take the unit as their first argument, as every block entry
 * does). No reflection over annotations, no generated subclass of
 * CompilationUnit: the same road KnowHOWMethods takes.
 */
class AdaptorUnit(
    private val cls: Class<*>,
    private val descriptors: List<String>,
    private val target: String,
) : CompilationUnit() {
    override fun getCodeRefs(): Array<CodeRef> {
        val l = MethodHandles.lookup()
        val snull: Array<String>? = null
        val hnull = arrayOf<LongArray>()
        val mt = MethodType.methodType(Void.TYPE, CompilationUnit::class.java,
            ThreadContext::class.java, CodeRef::class.java,
            CallSiteDescriptor::class.java, Array<Any?>::class.java)
        return Array(descriptors.size) { i ->
            val name = "callout $target ${descriptors[i]}"
            val mh = try {
                l.findStatic(cls, "qb_$i", mt).bindTo(this)
            } catch (e: ReflectiveOperationException) {
                throw RuntimeException(e)
            }
            CodeRef(this, mh, name, name, snull, snull, snull, snull, hnull, 0.toShort())
        }
    }

    override fun getCallSites(): Array<CallSiteDescriptor> = emptyArray()

    override fun hllName(): String = ""

    override fun unitId(): String = cls.name
}
