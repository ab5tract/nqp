package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.Semaphore

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class SemaphoreInstance : SixModelObject() {
    @JvmField var sem: Semaphore? = null

    override fun set_int(tc: ThreadContext, value: Long) {
        if (sem != null) {
            throw ExceptionHandling.dieInternal(tc, "cannot change the value of a semaphore")
        }
        sem = Semaphore(value.toInt())
    }
}
