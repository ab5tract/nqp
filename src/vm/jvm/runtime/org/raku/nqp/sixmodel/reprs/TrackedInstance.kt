package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.dispatch.ArgKind
import org.raku.nqp.dispatch.DispatchValue
import org.raku.nqp.dispatch.ValueSource
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * A value that a dispatcher is tracking: the value it had when the dispatch was
 * recorded, together with a description of where it came from, so that guards
 * and result captures can be expressed in terms of it.
 *
 * Dispatchers pass these around as opaque handles, so the type has no methods
 * and does not serialize.
 */
class TrackedInstance : SixModelObject() {
    @JvmField var source: ValueSource? = null
    @JvmField var kind: ArgKind = ArgKind.OBJ
    @JvmField var value: Any? = null

    val dispatchValue: DispatchValue
        get() = DispatchValue(kind, value)

    override fun clone(tc: ThreadContext): SixModelObject {
        val clone = TrackedInstance()
        clone.st = this.st
        clone.source = this.source
        clone.kind = this.kind
        clone.value = this.value
        return clone
    }

    companion object {
        /** Makes a tracked value for a source and the value it currently has. */
        @JvmStatic
        fun create(tc: ThreadContext, source: ValueSource, value: DispatchValue): TrackedInstance {
            val type = tc.gc.Tracked!!
            val tracked = type.st.REPR.allocate(tc, type.st) as TrackedInstance
            tracked.source = source
            tracked.kind = value.kind
            tracked.value = value.value
            return tracked
        }
    }
}
