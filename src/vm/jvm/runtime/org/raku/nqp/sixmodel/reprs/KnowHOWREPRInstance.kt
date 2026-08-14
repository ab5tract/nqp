package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class KnowHOWREPRInstance : SixModelObject() {
    @JvmField var name: String? = null
    @JvmField var attributes: MutableList<SixModelObject>? = null
    @JvmField var methods: HashMap<String, SixModelObject?>? = null
}
