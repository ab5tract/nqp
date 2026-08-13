package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOSeekable {
    fun seek(tc: ThreadContext, offset: Long, whence: Long)
    fun tell(tc: ThreadContext): Long
}
