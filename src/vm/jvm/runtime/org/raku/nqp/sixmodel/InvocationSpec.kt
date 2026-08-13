package org.raku.nqp.sixmodel

/* How do we invoke this thing? Specifies either an attribute to look at for
 * an invokable thing, or alternatively a method to call. */
class InvocationSpec {
    /** Class handle where we find the attribute to invoke. */
    @JvmField var ClassHandle: SixModelObject? = null

    /** Attribute name where we find the attribute to invoke. */
    @JvmField var AttrName: String? = null

    /** Attribute lookup hint used in gradual typing. */
    @JvmField var Hint = 0L

    /** Thing that handles invocation. */
    @JvmField var InvocationHandler: SixModelObject? = null
}
