package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class JavaObjectWrapper : SixModelObject() {
    @JvmField var theObject: Any? = null
}
