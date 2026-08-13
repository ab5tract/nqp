package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class NFAInstance : SixModelObject() {
    @JvmField var fates: SixModelObject? = null
    @JvmField var numStates = 0
    @JvmField var states: Array<Array<NFAStateInfo>>? = null
}
