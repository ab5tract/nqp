package org.raku.nqp.jast2bc

import org.objectweb.asm.Label
import org.objectweb.asm.Type

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class JastMethod @Throws(Exception::class) constructor(jast: SixModelObject, jastMethod: SixModelObject, tc: ThreadContext) {
    @JvmField var name: String?
    @JvmField var isStatic = false
    @JvmField var returns: Type
    @JvmField val arguments: MutableList<Type> = ArrayList()
    @JvmField val locals: MutableMap<String?, JASTCompiler.VariableDef> = HashMap()
    @JvmField var instructions: SixModelObject?
    @JvmField var crName: String?
    @JvmField var crCuid: String?
    @JvmField var crOuter = -2 // -1 = has no outer  -2 = not a coderef
    @JvmField val crOlex: MutableList<String?> = ArrayList()
    @JvmField val crIlex: MutableList<String?> = ArrayList()
    @JvmField val crNlex: MutableList<String?> = ArrayList()
    @JvmField val crSlex: MutableList<String?> = ArrayList()
    @JvmField var crHandlers: LongArray
    @JvmField var hasExitHandler = false
    @JvmField var argsExpectation: Short = 0
    @JvmField var isThunk = false
    @JvmField var crFile: String? = null
    @JvmField var crLine = 0
    @JvmField var crRawLine = 0
    /* Intra-method #line directive sections: parallel arrays of the raw
     * line a section starts at, the line it reads as, and the file it
     * reads as part of. Null when the body has no directive of its own. */
    @JvmField var crSectionRaw: IntArray? = null
    @JvmField var crSectionLine: IntArray? = null
    @JvmField var crSectionFile: Array<String?>? = null
    /* The unit artifact's per-block record: this block's qbid and the
     * index of its engine program in the unit's program list. -1 when the
     * unit was not compiled on the artifact road. */
    @JvmField var crQbid = -1
    @JvmField var crProgram = -1

    /* Package-private in the Java original; JASTCompiler reads them. */
    @JvmField val beginAll = Label()
    @JvmField val endAll = Label()
    @JvmField val labels: MutableMap<String?, JASTCompiler.LabelInfo> = HashMap()

    init {
        if (Ops.istype(jast, jastMethod, tc) == 0L)
            throw Exception("JAST node isn't a JAST::Method")

        var iter: SixModelObject
        var curArgIndex = 1

        name = Ops.getattr_s(jast, jastMethod, "$!name", nameHint, tc)
        returns = JASTCompiler.processType(Ops.getattr(jast, jastMethod, "$!returns", returnsHint, tc)!!.get_str(tc)!!)
        isStatic = Ops.getattr_i(jast, jastMethod, "$!static", staticHint, tc) != 0L
        if (isStatic)
            curArgIndex = 0

        iter = Ops.iter(Ops.getattr(jast, jastMethod, "@!arguments", argumentsHint, tc), tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val pair = iter.shift_boxed(tc)!!
            val argName = pair.at_pos_boxed(tc, 0)!!.get_str(tc)
            val type = JASTCompiler.processType(pair.at_pos_boxed(tc, 1)!!.get_str(tc)!!)
            arguments.add(type)
            if (locals.containsKey(argName))
                throw Exception("Duplicate local name: " + argName)
            locals.put(argName, JASTCompiler.VariableDef(curArgIndex, type.descriptor, beginAll, endAll))
            curArgIndex += if (type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE) 2 else 1
        }

        iter = Ops.iter(Ops.getattr(jast, jastMethod, "@!locals", localsHint, tc), tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val pair = iter.shift_boxed(tc)!!
            val localName = pair.at_pos_boxed(tc, 0)!!.get_str(tc)
            val type = JASTCompiler.processType(pair.at_pos_boxed(tc, 1)!!.get_str(tc)!!)
            if (locals.containsKey(localName))
                throw Exception("Duplicate local name: " + localName)
            locals.put(localName, JASTCompiler.VariableDef(curArgIndex, type.descriptor, beginAll, endAll))
            curArgIndex += if (type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE) 2 else 1
        }

        instructions = Ops.getattr(jast, jastMethod, "@!instructions", instructionsHint, tc)

        crName = Ops.getattr_s(jast, jastMethod, "$!cr_name", crNameHint, tc)
        crCuid = Ops.getattr_s(jast, jastMethod, "$!cr_cuid", crCuidHint, tc)
        crOuter = Ops.getattr_i(jast, jastMethod, "$!cr_outer", crOuterHint, tc).toInt()

        fillList(crOlex, Ops.getattr(jast, jastMethod, "@!cr_olex", crOlexHint, tc)!!, tc)
        fillList(crIlex, Ops.getattr(jast, jastMethod, "@!cr_ilex", crIlexHint, tc)!!, tc)
        fillList(crNlex, Ops.getattr(jast, jastMethod, "@!cr_nlex", crNlexHint, tc)!!, tc)
        fillList(crSlex, Ops.getattr(jast, jastMethod, "@!cr_slex", crSlexHint, tc)!!, tc)

        val handlersList = Ops.getattr(jast, jastMethod, "@!cr_handlers", crHandlersHint, tc)
        iter = Ops.iter(handlersList, tc)
        crHandlers = LongArray(Ops.elems(handlersList, tc).toInt())
        var i = 0
        while (Ops.istrue(iter, tc) != 0L) {
            crHandlers[i] = iter.shift_boxed(tc)!!.get_int(tc)
            i++
        }
        hasExitHandler = Ops.getattr_i(jast, jastMethod, "$!has_exit_handler", hasExitHandlerHint, tc) != 0L
        argsExpectation = Ops.getattr_i(jast, jastMethod, "$!args_expectation", argsExpectationHint, tc).toShort()
        isThunk = try {
            Ops.getattr_i(jast, jastMethod, "$!is_thunk", isThunkHint, tc) != 0L
        } catch (t: Throwable) {
            /* Most likely a version of the node without the field. */
            false
        }
        try {
            val file = Ops.getattr_s(jast, jastMethod, "$!cr_file", crFileHint, tc)
            if (file != null && file.isNotEmpty()) {
                crFile = file
                crLine = Ops.getattr_i(jast, jastMethod, "$!cr_line", crLineHint, tc).toInt()
                crRawLine = Ops.getattr_i(jast, jastMethod, "$!cr_rawline", crRawLineHint, tc).toInt()
                val sections = Ops.getattr(jast, jastMethod, "@!cr_sections", crSectionsHint, tc)!!
                val numSections = Ops.elems(sections, tc).toInt()
                if (numSections > 0) {
                    val raw = IntArray(numSections)
                    val mapped = IntArray(numSections)
                    val files = arrayOfNulls<String>(numSections)
                    for (j in 0 until numSections) {
                        val row = sections.at_pos_boxed(tc, j.toLong())!!
                        raw[j] = row.at_pos_boxed(tc, 0)!!.get_int(tc).toInt()
                        mapped[j] = row.at_pos_boxed(tc, 1)!!.get_int(tc).toInt()
                        files[j] = row.at_pos_boxed(tc, 2)!!.get_str(tc)
                    }
                    crSectionRaw = raw
                    crSectionLine = mapped
                    crSectionFile = files
                }
            }
        } catch (t: Throwable) {
            /* Most likely a version of the node without the fields. */
        }
        try {
            crQbid = Ops.getattr_i(jast, jastMethod, "$!cr_qbid", crQbidHint, tc).toInt()
            crProgram = Ops.getattr_i(jast, jastMethod, "$!cr_program", crProgramHint, tc).toInt()
        } catch (t: Throwable) {
            /* A version of the node without the fields. */
        }
    }

    private fun fillList(list: MutableList<String?>, smoList: SixModelObject, tc: ThreadContext) {
        val iter = Ops.iter(smoList, tc)
        while (Ops.istrue(iter, tc) != 0L) {
            val value = iter.shift_boxed(tc)!!.get_str(tc)
            list.add(value)
        }
    }

    companion object {
        private var nameHint = 0L
        private var staticHint = 0L
        private var returnsHint = 0L
        private var argumentsHint = 0L
        private var localsHint = 0L
        private var instructionsHint = 0L
        private var crNameHint = 0L
        private var crCuidHint = 0L
        private var crOuterHint = 0L
        private var crOlexHint = 0L
        private var crIlexHint = 0L
        private var crNlexHint = 0L
        private var crSlexHint = 0L
        private var crHandlersHint = 0L
        private var hasExitHandlerHint = 0L
        private var argsExpectationHint = 0L
        private var isThunkHint = 0L
        private var crFileHint = 0L
        private var crLineHint = 0L
        private var crRawLineHint = 0L
        private var crSectionsHint = 0L
        private var crQbidHint = 0L
        private var crProgramHint = 0L

        @JvmStatic
        fun setup(jastMethod: SixModelObject, tc: ThreadContext) {
            nameHint            = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!name")
            staticHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!static")
            returnsHint         = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!returns")
            argumentsHint       = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!arguments")
            localsHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!locals")
            instructionsHint    = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!instructions")
            crNameHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_name")
            crCuidHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_cuid")
            crOuterHint         = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_outer")
            crOlexHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_olex")
            crIlexHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_ilex")
            crNlexHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_nlex")
            crSlexHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_slex")
            crHandlersHint      = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_handlers")
            hasExitHandlerHint  = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!has_exit_handler")
            argsExpectationHint = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!args_expectation")
            isThunkHint         = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!is_thunk")
            crFileHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_file")
            crLineHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_line")
            crRawLineHint       = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_rawline")
            crSectionsHint      = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "@!cr_sections")
            crQbidHint          = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_qbid")
            crProgramHint       = jastMethod.st.REPR.hint_for(tc, jastMethod.st, jastMethod, "$!cr_program")
        }
    }
}
