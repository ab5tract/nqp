package org.raku.nqp.sixmodel.reprs

import java.util.concurrent.locks.Condition

import org.raku.nqp.sixmodel.SixModelObject

class ConditionVariableInstance : SixModelObject() {
    @JvmField var condvar: Condition? = null
}
