package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject

class SCRefInstance : SixModelObject() {
    @JvmField var referencedSC: SerializationContext? = null
}
