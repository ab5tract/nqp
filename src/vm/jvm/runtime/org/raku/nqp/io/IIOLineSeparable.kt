package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOLineSeparable {
    fun setInputLineSeparator(tc: ThreadContext, sep: String)
}
