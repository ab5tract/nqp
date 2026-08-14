package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject

class MultiDimArrayInstance : MultiDimArrayInstanceBase() {
    @JvmField var slots: Array<SixModelObject?>? = null

    override fun set_dimensions(tc: ThreadContext, dims: LongArray) {
        super.set_dimensions(tc, dims)
        if (slots == null)
            slots = arrayOfNulls(numSlots())
        else
            duplicateSetDimensions(tc)
    }

    override fun at_pos_multidim_boxed(tc: ThreadContext, indices: LongArray): SixModelObject? {
        return slots!![indicesToFlatIndex(tc, indices)]
    }

    override fun bind_pos_multidim_boxed(tc: ThreadContext, indices: LongArray, value: SixModelObject?) {
        slots!![indicesToFlatIndex(tc, indices)] = value
    }

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val clone = this.clone() as MultiDimArrayInstance
            clone.sc = null
            clone.dimensions = this.dimensions.clone()
            if (this.slots != null)
                clone.slots = this.slots!!.clone()
            return clone
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }

    override fun serializeValues(tc: ThreadContext, writer: SerializationWriter) {
        val slots = this.slots!!
        for (i in slots.indices)
            writer.writeRef(slots[i])
    }

    override fun deserializeValues(tc: ThreadContext, reader: SerializationReader) {
        val slots = arrayOfNulls<SixModelObject>(numSlots())
        this.slots = slots
        for (i in slots.indices)
            slots[i] = reader.readRef()
    }
}
