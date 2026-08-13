package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOBindable {
    fun bind(tc: ThreadContext, host: String, port: Int, backlog: Int)
}
