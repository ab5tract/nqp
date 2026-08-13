package org.raku.nqp.sixmodel

class ParametricType : AbstractParametricity() {
    /* The code object to use to produce a new parameterization. */
    @JvmField var parameterizer: SixModelObject? = null

    /* Lookup table of existing parameterizations. */
    @JvmField var lookup: MutableList<MutableMap.MutableEntry<SixModelObject, SixModelObject>>? = null
}
