package org.raku.nqp.sixmodel

/* How do we invoke this thing? Specifies either an attribute to look at for
 * an invokable thing, or alternatively a method to call. Immutable: it is
 * published as part of a TypeState, never edited in place. */
class InvocationSpec(
    /** Class handle where we find the attribute to invoke. */
    @JvmField val ClassHandle: SixModelObject?,

    /** Attribute name where we find the attribute to invoke. */
    @JvmField val AttrName: String?,

    /** Attribute lookup hint used in gradual typing. */
    @JvmField val Hint: Long,

    /** Thing that handles invocation. */
    @JvmField val InvocationHandler: SixModelObject?,
)
