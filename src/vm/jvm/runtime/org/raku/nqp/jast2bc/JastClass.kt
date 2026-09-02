package org.raku.nqp.jast2bc

import org.raku.nqp.runtime.Base64
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class JastClass @Throws(Exception::class) constructor(jast: SixModelObject, jastClass: SixModelObject, tc: ThreadContext) {
    @JvmField var className: String?
    @JvmField var superName: String?
    @JvmField var filename: String?
    @JvmField var serialized: ByteArray? = null
    @JvmField var codePrograms: String? = null
    @JvmField var methods: SixModelObject?
    @JvmField var fields: SixModelObject?
    /* Names of nested in-memory units whose classfiles ride along in this
     * class's jar; empty for nearly every class. */
    @JvmField val nestedClasses: MutableList<String> = ArrayList()

    init {
        if (Ops.istype(jast, jastClass, tc) == 0L)
            throw Exception("JAST node isn't a JAST::Class")

        className = Ops.getattr_s(jast, jastClass, "$!name", nameHint, tc)
        superName = Ops.getattr_s(jast, jastClass, "$!super", superHint, tc)
        filename  = Ops.getattr_s(jast, jastClass, "$!filename", filenameHint, tc)
        methods   = jast.get_attribute_boxed(tc, jastClass, "@!methods", methodsHint)
        fields    = jast.get_attribute_boxed(tc, jastClass, "@!fields", fieldsHint)

        val serializedString = Ops.getattr_s(jast, jastClass, "$!serialized", serializedHint, tc)
        if (serializedString != null) {
            val sbuf = Base64.decode(serializedString)
            val bytes = ByteArray(sbuf.remaining())
            sbuf.get(bytes)
            serialized = bytes
        }

        try {
            codePrograms = Ops.getattr_s(jast, jastClass, "$!codeprograms", codeProgramsHint, tc)
        }
        catch (t: Throwable) {
            /* A version of the node without the field. */
        }

        try {
            val nested = jast.get_attribute_boxed(tc, jastClass, "@!nested_classes", nestedClassesHint)
            if (nested != null) {
                val iter = Ops.iter(nested, tc)
                while (Ops.istrue(iter, tc) != 0L)
                    iter.shift_boxed(tc)!!.get_str(tc)?.let { nestedClasses.add(it) }
            }
        }
        catch (t: Throwable) {
            /* Most likely a version of the node without the field. */
        }

        if (className == null)
            throw Exception("Missing class name")
        if (superName == null)
            throw Exception("Missing superclass name")
    }

    companion object {
        private var nameHint = 0L
        private var superHint = 0L
        private var filenameHint = 0L
        private var serializedHint = 0L
        private var codeProgramsHint = 0L
        private var methodsHint = 0L
        private var fieldsHint = 0L
        private var nestedClassesHint = 0L

        @JvmStatic
        fun setup(jastClass: SixModelObject, tc: ThreadContext) {
            nameHint       = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!name")
            superHint      = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!super")
            filenameHint   = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!filename")
            serializedHint = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!serialized")
            codeProgramsHint = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!codeprograms")
            methodsHint    = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!methods")
            fieldsHint     = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!fields")
            nestedClassesHint = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!nested_classes")
        }
    }
}
