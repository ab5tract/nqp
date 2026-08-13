package org.raku.nqp.io

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

interface IIOAsyncWritable {
    fun spurt(tc: ThreadContext, Str: SixModelObject, data: SixModelObject, done: SixModelObject, error: SixModelObject)
}
