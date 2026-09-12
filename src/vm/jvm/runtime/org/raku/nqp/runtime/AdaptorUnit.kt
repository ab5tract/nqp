package org.raku.nqp.runtime

import java.lang.invoke.MethodHandles

/**
 * The unit behind a Java-interop adaptor: one code ref per plan, built from
 * JavaCallout.INVOKE with the plan bound in. No generated class, no
 * reflection over annotations: the same road KnowHOWMethods takes.
 */
class AdaptorUnit(
    private val plans: List<CalloutPlan>,
    private val target: String,
) : CompilationUnit() {
    override fun getCodeRefs(): Array<CodeRef> {
        val snull: Array<String>? = null
        val hnull = arrayOf<LongArray>()
        return Array(plans.size) { i ->
            val name = "callout $target ${plans[i].descriptor}"
            val mh = MethodHandles.insertArguments(JavaCallout.INVOKE, 0, plans[i])
            CodeRef(this, mh, name, name, snull, snull, snull, snull, hnull, 0.toShort())
        }
    }

    override fun getCallSites(): Array<CallSiteDescriptor> = emptyArray()

    override fun hllName(): String = ""

    override fun unitId(): String = "adaptor:$target"
}
