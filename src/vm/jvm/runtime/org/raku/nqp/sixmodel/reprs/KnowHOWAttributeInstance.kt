package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class KnowHOWAttributeInstance : SixModelObject() {
    @JvmField var name: String? = null
    @JvmField var type: SixModelObject? = null
    @JvmField var box_target = 0
}
