package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

class NFA : REPR() {
    companion object {
        /* NFA constants. */
        const val EDGE_FATE = 0
        const val EDGE_EPSILON = 1
        const val EDGE_CODEPOINT = 2
        const val EDGE_CODEPOINT_NEG = 3
        const val EDGE_CHARCLASS = 4
        const val EDGE_CHARCLASS_NEG = 5
        const val EDGE_CHARLIST = 6
        const val EDGE_CHARLIST_NEG = 7
        const val EDGE_CODEPOINT_I = 9
        const val EDGE_CODEPOINT_I_NEG = 10
        const val EDGE_GENERIC_VAR = 11
        const val EDGE_CHARRANGE = 12
        const val EDGE_CHARRANGE_NEG = 13
        const val EDGE_CODEPOINT_LL = 14
        const val EDGE_CODEPOINT_I_LL = 15
        const val EDGE_CODEPOINT_M = 16
        const val EDGE_CODEPOINT_M_NEG = 17
        const val EDGE_CODEPOINT_M_LL = 18
        const val EDGE_CODEPOINT_IM = 19
        const val EDGE_CODEPOINT_IM_NEG = 20
        const val EDGE_CODEPOINT_IM_LL = 21
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT!!
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val obj = NFAInstance()
        obj.st = st
        return obj
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val stub = NFAInstance()
        stub.st = st
        return stub
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        val body = obj as NFAInstance
        /* Read fates. */
        body.fates = reader.readRef()
        /* Read number of states. */
        body.numStates = reader.readLong().toInt()
        if (body.numStates > 0) {
            /* Read state edge list counts. */
            val numStateEdges = IntArray(body.numStates)
            for (i in 0 until body.numStates)
                numStateEdges[i] = reader.readLong().toInt()
            /* Read state graph. Row and edge initializers run in index
             * order, matching the serialized layout. */
            body.states = Array(body.numStates) { i ->
                Array(numStateEdges[i]) {
                    val s = NFAStateInfo()
                    s.act = reader.readLong().toInt()
                    s.to = reader.readLong().toInt()
                    when (s.act and 0xff) {
                        EDGE_FATE, EDGE_CODEPOINT_LL, EDGE_CODEPOINT, EDGE_CODEPOINT_NEG,
                        EDGE_CHARCLASS, EDGE_CHARCLASS_NEG ->
                            s.arg_i = reader.readLong().toInt()
                        EDGE_CHARLIST, EDGE_CHARLIST_NEG ->
                            s.arg_s = reader.readStr()
                        EDGE_CODEPOINT_I_LL, EDGE_CODEPOINT_I, EDGE_CODEPOINT_I_NEG,
                        EDGE_CHARRANGE, EDGE_CHARRANGE_NEG -> {
                            s.arg_lc = reader.readLong().toInt().toChar()
                            s.arg_uc = reader.readLong().toInt().toChar()
                        }
                    }
                    s
                }
            }
        }
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val body = obj as NFAInstance
        /* Write fates. */
        writer.writeRef(body.fates)
        /* Write number of states. */
        writer.writeInt(body.numStates.toLong())
        val states = body.states!!
        /* Write state edge list counts. */
        for (i in 0 until body.numStates)
            writer.writeInt(states[i].size.toLong())
        /* Write state graph. */
        for (i in 0 until body.numStates) {
            for (j in states[i].indices) {
                val s = states[i][j]
                writer.writeInt(s.act.toLong())
                writer.writeInt(s.to.toLong())
                when (s.act and 0xff) {
                    EDGE_FATE, EDGE_CODEPOINT_LL, EDGE_CODEPOINT, EDGE_CODEPOINT_NEG,
                    EDGE_CHARCLASS, EDGE_CHARCLASS_NEG ->
                        writer.writeInt(s.arg_i.toLong())
                    EDGE_CHARLIST, EDGE_CHARLIST_NEG ->
                        writer.writeStr(s.arg_s)
                    EDGE_CODEPOINT_I_LL, EDGE_CODEPOINT_I, EDGE_CODEPOINT_I_NEG,
                    EDGE_CHARRANGE, EDGE_CHARRANGE_NEG -> {
                        writer.writeInt(s.arg_lc.code.toLong())
                        writer.writeInt(s.arg_uc.code.toLong())
                    }
                }
            }
        }
    }
}
