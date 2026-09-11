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
    /* The unit artifact's record (docs/superpowers/specs/2026-09-09-jvm-unit-artifact-design.md):
     * what UnitWriter reads off the class instead of assembling bytecode.
     * Absent on a node from an older compiler, hence the guarded read. */
    @JvmField var hll: String? = null
    @JvmField var mainlineQbid = -1
    @JvmField var entryQbid = -1
    @JvmField var deserializeQbid = -1
    @JvmField var loadQbid = -1
    @JvmField var serializedCount = -1
    @JvmField var scHandle: String? = null
    @JvmField var scDesc: String? = null
    @JvmField var programs: SixModelObject? = null
    @JvmField var callsites: SixModelObject? = null
    @JvmField var blockvalues: SixModelObject? = null

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

        try {
            hll = Ops.getattr_s(jast, jastClass, "$!hll", hllHint, tc)
            mainlineQbid = Ops.getattr_i(jast, jastClass, "$!mainline_qbid", mainlineQbidHint, tc).toInt()
            entryQbid = Ops.getattr_i(jast, jastClass, "$!entry_qbid", entryQbidHint, tc).toInt()
            deserializeQbid = Ops.getattr_i(jast, jastClass, "$!deserialize_qbid", deserializeQbidHint, tc).toInt()
            loadQbid = Ops.getattr_i(jast, jastClass, "$!load_qbid", loadQbidHint, tc).toInt()
            serializedCount = Ops.getattr_i(jast, jastClass, "$!serialized_count", serializedCountHint, tc).toInt()
            scHandle = Ops.getattr_s(jast, jastClass, "$!sc_handle", scHandleHint, tc)
            scDesc = Ops.getattr_s(jast, jastClass, "$!sc_desc", scDescHint, tc)
            programs = jast.get_attribute_boxed(tc, jastClass, "@!programs", programsHint)
            callsites = jast.get_attribute_boxed(tc, jastClass, "@!callsites", callsitesHint)
            blockvalues = jast.get_attribute_boxed(tc, jastClass, "@!blockvalues", blockvaluesHint)
        }
        catch (t: Throwable) {
            /* A version of the node without the unit fields. */
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
        private var hllHint = 0L
        private var mainlineQbidHint = 0L
        private var entryQbidHint = 0L
        private var deserializeQbidHint = 0L
        private var loadQbidHint = 0L
        private var serializedCountHint = 0L
        private var scHandleHint = 0L
        private var scDescHint = 0L
        private var programsHint = 0L
        private var callsitesHint = 0L
        private var blockvaluesHint = 0L

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
            hllHint             = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!hll")
            mainlineQbidHint    = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!mainline_qbid")
            entryQbidHint       = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!entry_qbid")
            deserializeQbidHint = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!deserialize_qbid")
            loadQbidHint        = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!load_qbid")
            serializedCountHint = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!serialized_count")
            scHandleHint        = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!sc_handle")
            scDescHint          = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "$!sc_desc")
            programsHint        = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!programs")
            callsitesHint       = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!callsites")
            blockvaluesHint     = jastClass.st.REPR.hint_for(tc, jastClass.st, jastClass, "@!blockvalues")
        }
    }
}
