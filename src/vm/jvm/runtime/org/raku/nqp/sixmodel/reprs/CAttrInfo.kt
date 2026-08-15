package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType

/* One attribute of a CStruct, CPPStruct or CUnion, together with where it
 * sits in the type's C layout. Offset, size and alignment are filled in
 * when the type is composed. */
class CAttrInfo {
    @JvmField var name: String? = null
    @JvmField var type: SixModelObject? = null
    @JvmField var argType: ArgType? = null
    @JvmField var inlined: Short = 0
    @JvmField var bits: Short = 0
    @JvmField var offset: Long = 0
    @JvmField var size: Long = 0
    @JvmField var alignment: Long = 1
}
