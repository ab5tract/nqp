package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.LinkedBlockingQueue

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class ConcBlockingQueueInstance : SixModelObject() {
    lateinit var queue: LinkedBlockingQueue<SixModelObject>

    /* Looking at the first element counts as a peek. */
    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        if (index == 0L)
            return queue.peek()
        else
            throw ExceptionHandling.dieInternal(tc,
                "Can only request (peek) head of a concurrent blocking queue")
    }

    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) {
        queue.add(value!!)
    }

    override fun shift_boxed(tc: ThreadContext): SixModelObject? {
        try {
            return queue.take()
        }
        catch (e: InterruptedException) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
    }

    override fun elems(tc: ThreadContext): Long {
        return queue.size.toLong()
    }
}
