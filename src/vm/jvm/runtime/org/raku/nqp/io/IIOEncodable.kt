package org.raku.nqp.io

import java.nio.charset.Charset
import org.raku.nqp.runtime.ThreadContext

interface IIOEncodable {
    fun setEncoding(tc: ThreadContext, cs: Charset)
}
