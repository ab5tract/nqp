package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOExitable {
    @Throws(IllegalThreadStateException::class)
    fun exitValue(tc: ThreadContext): Int
}
