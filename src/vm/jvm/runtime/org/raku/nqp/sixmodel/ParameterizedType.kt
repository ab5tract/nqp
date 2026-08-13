package org.raku.nqp.sixmodel

class ParameterizedType : AbstractParametricity() {
    /* The type that we are a parameterization of. */
    @JvmField var parametricType: SixModelObject? = null

    /* Our type parameters. */
    @JvmField var parameters: SixModelObject? = null
}
