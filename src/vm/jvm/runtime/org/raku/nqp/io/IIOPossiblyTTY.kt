package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOPossiblyTTY {
    fun isTTY(tc: ThreadContext): Boolean
}
