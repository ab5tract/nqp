package org.raku.nqp.io

import java.util.concurrent.LinkedBlockingQueue
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

interface IIOAsyncReadable {
    fun slurp(tc: ThreadContext, Str: SixModelObject, done: SixModelObject, error: SixModelObject)
    fun lines(tc: ThreadContext, Str: SixModelObject, chomp: Boolean,
              queue: LinkedBlockingQueue<SixModelObject>, done: SixModelObject, error: SixModelObject)
}
