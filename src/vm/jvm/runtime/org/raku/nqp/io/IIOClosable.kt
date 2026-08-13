package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOClosable {
    fun close(tc: ThreadContext)
}
