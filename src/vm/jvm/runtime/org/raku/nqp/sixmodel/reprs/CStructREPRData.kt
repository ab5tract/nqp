package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.sixmodel.SixModelObject

class CStructREPRData {
    /* The Class object for the subclass of com.sun.jna.Structure we use to
     * represent our structs in JNA space. */
    @JvmField var structureClass: Class<*>? = null

    @JvmField var fieldTypes = HashMap<String, AttrInfo>()

    class AttrInfo {
        @JvmField var name: String? = null
        @JvmField var type: SixModelObject? = null
        @JvmField var argType: NativeCall.ArgType? = null
        @JvmField var inlined: Short = 0
        @JvmField var bits: Short = 0
    }
}
