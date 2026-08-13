package org.raku.nqp.sixmodel.reprs

import java.util.Arrays

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class VMArrayInstance_i : VMArrayInstanceBase() {
    // Raw public fields: read/written directly by Ops, VMArray, NativeCallOps.
    @JvmField var elems = 0
    @JvmField var start = 0
    @JvmField var slots: LongArray? = null

    override fun at_pos_native(tc: ThreadContext, index: Long) {
        var index = index
        if (index < 0) {
            index += elems
            if (index < 0)
                throw ExceptionHandling.dieInternal(tc, "VMArray: Index out of bounds")
        }
        else if (index >= elems) {
            tc.native_type = ThreadContext.NATIVE_INT
            tc.native_i = 0
            return
        }

        tc.native_type = ThreadContext.NATIVE_INT
        tc.native_i = slots!![start + index.toInt()]
    }

    override fun exists_pos(tc: ThreadContext, key: Long): Long {
        var key = key
        if (key < 0) {
            key += this.elems
        }
        if (key >= 0 && key < this.elems) {
            return 1
        }
        return 0
    }

    private fun set_size_internal(tc: ThreadContext, n: Long) {
        var elems = this.elems.toLong()
        val start = this.start.toLong()
        var ssize = (this.slots?.size ?: 0).toLong()
        val slots = this.slots

        if (n < 0)
            throw ExceptionHandling.dieInternal(tc, "VMArray: Can't resize to negative elements")

        if (n == elems)
            return

        if (start > 0 && n + start > ssize) {
            /* if there aren't enough slots at the end, shift off empty slots
             * from the beginning first */
            if (elems > 0)
                memmove(slots!!, 0, start, elems)
            this.start = 0
            /* fill out any unused slots with zeros */
            while (elems < ssize) {
                slots!![elems.toInt()] = 0
                elems++
            }
        }
        else if (n < elems) {
            /* we're downsizing; clear off extra slots */
            while (n < elems) {
                elems--
                slots!![(start + elems).toInt()] = 0
            }
        }

        this.elems = n.toInt()
        if (n <= ssize) {
            /* we already have n slots available, we can just return */
            return
        }

        /* We need more slots.  If the current slot size is less
         * than 8K, use the larger of twice the current slot size
         * or the actual number of elements needed.  Otherwise,
         * grow the slots to the next multiple of 4096 (0x1000). */
        if (ssize < 8192) {
            ssize *= 2
            if (n > ssize) ssize = n
            if (ssize < 8) ssize = 8
        }
        else {
            ssize = (n + 0x1000) and 0xfffL.inv()
        }

        /* now allocate the new slot buffer */
        this.slots = if (slots == null) LongArray(ssize.toInt()) else Arrays.copyOf(slots, ssize.toInt())
    }

    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        var index = index
        if (index < 0) {
            index += elems
            if (index < 0)
                throw ExceptionHandling.dieInternal(tc, "VMArray: Index out of bounds")
        }
        else if (index >= elems)
            set_size_internal(tc, index + 1)

        tc.native_type = ThreadContext.NATIVE_INT
        slots!![start + index.toInt()] = tc.native_i
    }

    override fun elems(tc: ThreadContext): Long = elems.toLong()

    override fun set_elems(tc: ThreadContext, count: Long) {
        set_size_internal(tc, count)
    }

    override fun push_native(tc: ThreadContext) {
        set_size_internal(tc, (elems + 1).toLong())
        tc.native_type = ThreadContext.NATIVE_INT
        slots!![start + elems - 1] = tc.native_i
    }

    override fun pop_native(tc: ThreadContext) {
        if (elems < 1)
            throw ExceptionHandling.dieInternal(tc, "VMArray: Can't pop from an empty array")
        elems--
        tc.native_type = ThreadContext.NATIVE_INT
        tc.native_i = slots!![start + elems]
    }

    override fun unshift_native(tc: ThreadContext) {
        /* If we don't have room at the beginning of the slots,
         * make some room (8 slots) for unshifting */
        if (start < 1) {
            val n = 8

            /* grow the array */
            val origElems = elems
            set_size_internal(tc, (elems + n).toLong())

            /* move elements and set start */
            memmove(slots!!, n.toLong(), 0, origElems.toLong())
            start = n
            elems = origElems

            /* clear out beginning elements */
            for (i in 0 until n)
                slots!![i] = 0
        }

        /* Now do the unshift */
        start--
        tc.native_type = ThreadContext.NATIVE_INT
        slots!![start] = tc.native_i
        elems++
    }

    override fun shift_native(tc: ThreadContext) {
        if (elems < 1)
            throw ExceptionHandling.dieInternal(tc, "VMArray: Can't shift from an empty array")

        tc.native_type = ThreadContext.NATIVE_INT
        tc.native_i = slots!![start]
        start++
        elems--
    }

    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject {
        var beginning = beginning
        var end = end
        beginning = if (beginning < 0) this.elems + beginning else beginning
        end       = if (end       < 0) this.elems + end       else end
        if (end < beginning || beginning < 0 || end < 0
            || this.elems <= beginning || this.elems <= end) {
            throw ExceptionHandling.dieInternal(tc, "VMArray: Slice index out of bounds")
        }

        val numWanted = end - beginning + 1
        if (0 < numWanted) {
            for (i in 0 until numWanted) {
                this.at_pos_native(tc, beginning + i)
                dest.bind_pos_native(tc, i)
            }
        }
        return dest
    }

    /* This can be optimized for the case we have two VMArray representation objects. */
    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) {
        var offset = offset
        var count = count
        var elems0 = elems.toLong()
        val elems1 = from.elems(tc)
        var start: Long
        var tail: Long
        var slots: LongArray?

        /* start from end? */
        if (offset < 0) {
            offset += elems0

            if (offset < 0)
                throw ExceptionHandling.dieInternal(tc, "VMArray: Illegal splice offset")
        }

        /* When offset == 0, then we may be able to reduce the memmove
         * calls and reallocs by adjusting SELF's start, elems0, and
         * count to better match the incoming splice.  In particular,
         * we're seeking to adjust C<count> to as close to C<elems1>
         * as we can. */
        if (offset == 0L) {
            var n = elems1 - count
            start = this.start.toLong()
            if (n > start)
                n = start
            if (n <= -elems0) {
                elems0 = 0
                count = 0
                this.start = 0
                this.elems = elems0.toInt()
            }
            else if (n != 0L) {
                elems0 += n
                count += n
                this.start = (start - n).toInt()
                this.elems = elems0.toInt()
            }
        }

        /* if count == 0 and elems1 == 0, there's nothing left
         * to copy or remove, so the splice is done! */
        if (count == 0L && elems1 == 0L)
            return

        /* number of elements to right of splice (the "tail") */
        tail = elems0 - offset - count
        if (tail < 0)
            tail = 0

        else if (tail > 0 && count > elems1) {
            /* We're shrinking the array, so first move the tail left */
            slots = this.slots
            start = this.start.toLong()
            memmove(slots!!, start + offset + elems1, start + offset + count, tail)
        }

        /* now resize the array */
        set_size_internal(tc, offset + elems1 + tail)

        slots = this.slots
        start = this.start.toLong()
        if (tail > 0 && count < elems1) {
            /* The array grew, so move the tail to the right */
            memmove(slots!!, start + offset + elems1, start + offset + count, tail)
        }

        /* now copy C<from>'s elements into SELF */
        if (elems1 > 0) {
            val fromPos = (start + offset).toInt()
            for (i in 0 until elems1.toInt()) {
                from.at_pos_native(tc, i.toLong())
                slots!![fromPos + i] = tc.native_i
            }
        }
    }

    private fun memmove(slots: LongArray, destStart: Long, srcStart: Long, n: Long) {
        System.arraycopy(slots, srcStart.toInt(), slots, destStart.toInt(), n.toInt())
    }

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val clone = super.clone() as VMArrayInstance_i
            clone.sc = null
            if (clone.slots != null)
                clone.slots = this.slots!!.clone()
            return clone
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }
}
