package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext

interface IIOSyncWritable {
    fun print(tc: ThreadContext, s: String): Long
    fun say(tc: ThreadContext, s: String): Long
    fun write(tc: ThreadContext, bytes: ByteArray): Long
    fun flush(tc: ThreadContext)
    fun setBufferSize(tc: ThreadContext, size: Long)
}
