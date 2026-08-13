package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.locks.ReentrantLock

import org.raku.nqp.sixmodel.SixModelObject

class ReentrantMutexInstance : SixModelObject() {
    @JvmField var lock: ReentrantLock? = null
}
