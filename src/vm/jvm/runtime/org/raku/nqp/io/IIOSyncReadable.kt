package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOSyncReadable {
    fun slurp(tc: ThreadContext): String
    fun readline(tc: ThreadContext): String
    fun readchars(tc: ThreadContext, chars: Int): String
    fun read(tc: ThreadContext, bytes: Int): ByteArray
    fun eof(tc: ThreadContext): Boolean
}
