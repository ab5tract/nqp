package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOInteractive {
    fun readlineInteractive(tc: ThreadContext, prompt: String): String
}
