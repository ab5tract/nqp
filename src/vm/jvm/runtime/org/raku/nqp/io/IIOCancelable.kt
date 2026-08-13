package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOCancelable {
    fun cancel(tc: ThreadContext)
}
