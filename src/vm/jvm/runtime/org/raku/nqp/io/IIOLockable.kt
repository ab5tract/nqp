package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOLockable {
    fun lock(tc: ThreadContext, flag: Long)
    fun unlock(tc: ThreadContext)
}
