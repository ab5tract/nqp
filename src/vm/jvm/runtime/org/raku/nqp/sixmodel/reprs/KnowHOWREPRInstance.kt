package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class KnowHOWREPRInstance : SixModelObject() {
    @JvmField var name: String? = null
    @JvmField var attributes: MutableList<SixModelObject>? = null
    @JvmField var methods: HashMap<String, SixModelObject?>? = null

    /**
     * The LAST type this meta-object composed (or was bootstrapped as the HOW
     * of), whose STable holds a published COPY of [methods]; add_method after
     * that republishes THAT one. A meta-object composed more than once leaves
     * the earlier types holding their own copies, which no longer follow it --
     * as before the type state, where they held their own aliases.
     * In-process only, like the aliasing it replaces.
     */
    @JvmField var composedType: SixModelObject? = null
}
