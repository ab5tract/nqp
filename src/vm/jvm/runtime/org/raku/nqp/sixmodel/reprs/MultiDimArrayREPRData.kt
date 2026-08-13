package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec

class MultiDimArrayREPRData {
    @JvmField var ss: StorageSpec? = null
    @JvmField var type: SixModelObject? = null
    @JvmField var numDimensions = 0
}
