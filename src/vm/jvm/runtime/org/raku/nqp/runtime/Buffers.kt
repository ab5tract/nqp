package org.raku.nqp.runtime

import java.nio.ByteBuffer
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i8
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u8

object Buffers {
    @JvmStatic
    fun stashBytes(tc: ThreadContext, res: SixModelObject, bytes: ByteArray) {
        stashBytes(tc, res, bytes, bytes.size)
    }

    @JvmStatic
    fun stashBytes(tc: ThreadContext, res: SixModelObject, bytes: ByteArray, elems: Int) {
        when (res) {
            is VMArrayInstance_i8 -> {
                res.elems = elems
                res.start = 0
                res.slots = bytes
            }
            is VMArrayInstance_u8 -> {
                res.elems = elems
                res.start = 0
                res.slots = bytes
            }
            else -> {
                res.set_elems(tc, elems.toLong())
                for (i in 0 until elems) {
                    tc.nativeI = bytes[i].toLong()
                    res.bind_pos_native(tc, i.toLong())
                }
            }
        }
    }

    @JvmStatic
    fun unstashBytes(buf: SixModelObject, tc: ThreadContext): ByteBuffer = when {
        buf is VMArrayInstance_i8 ->
            if (buf.slots != null) ByteBuffer.wrap(buf.slots, buf.start, buf.elems)
            else ByteBuffer.allocate(0)
        buf is VMArrayInstance_u8 ->
            if (buf.slots != null) ByteBuffer.wrap(buf.slots, buf.start, buf.elems)
            else ByteBuffer.allocate(0)
        else -> {
            val n = buf.elems(tc).toInt()
            val bb = ByteBuffer.allocate(n)
            for (i in 0 until n) {
                buf.at_pos_native(tc, i.toLong())
                bb.put(tc.nativeI.toByte())
            }
            bb.rewind()
            bb
        }
    }
}
