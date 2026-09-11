package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class VMThreadInstance : SixModelObject() {
    @JvmField var thread: Thread? = null
    @JvmField var lockCount = 0L
}
