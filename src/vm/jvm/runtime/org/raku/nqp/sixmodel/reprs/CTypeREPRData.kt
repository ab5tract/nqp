package org.raku.nqp.sixmodel.reprs

/* The C layout of an aggregate type, computed when the type is composed. */
open class CTypeREPRData {
    @JvmField var fieldTypes = HashMap<String, CAttrInfo>()
    @JvmField var attrs: List<CAttrInfo> = emptyList()
    @JvmField var size: Long = 0
    @JvmField var alignment: Long = 1

    /* Composition fills in the layout; until then a nested type can't be
     * inlined into another one, because we don't know how big it is. */
    val composed: Boolean
        get() = alignment > 0 && attrs.isNotEmpty()
}
