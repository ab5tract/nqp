package org.raku.nqp.jast2bc

import org.objectweb.asm.Type

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class JastField @Throws(Exception::class) constructor(jast: SixModelObject, jastField: SixModelObject, tc: ThreadContext) {
    @JvmField var name: String?
    @JvmField var type: Type
    @JvmField var isStatic: Boolean

    init {
        if (Ops.istype(jast, jastField, tc) == 0L)
            throw Exception("JAST node isn't a JAST::Field")

        name = Ops.getattr_s(jast, jastField, "$!name", nameHint, tc)
        type = JASTCompiler.processType(Ops.getattr_s(jast, jastField, "$!type", typeHint, tc)!!)
        isStatic = Ops.getattr_i(jast, jastField, "$!static", staticHint, tc) != 0L
    }

    companion object {
        private var nameHint = 0L
        private var typeHint = 0L
        private var staticHint = 0L

        @JvmStatic
        fun setup(jastField: SixModelObject, tc: ThreadContext) {
            nameHint   = jastField.st.REPR.hint_for(tc, jastField.st, jastField, "$!name")
            typeHint   = jastField.st.REPR.hint_for(tc, jastField.st, jastField, "$!type")
            staticHint = jastField.st.REPR.hint_for(tc, jastField.st, jastField, "$!static")
        }
    }
}
