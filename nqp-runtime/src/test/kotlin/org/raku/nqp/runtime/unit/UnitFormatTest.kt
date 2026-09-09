package org.raku.nqp.runtime.unit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitFormatTest {
    private fun block(name: String, prog: Int, outer: Int = -1, cuid: String? = null) = BlockRec(
        name, cuid, outer,
        arrayOf("\$x", "naïve"), arrayOf("\$i"), arrayOf(), arrayOf("\$s"),
        longArrayOf(1, 3, 7, 8, 9), hasExitHandler = false, isThunk = true,
        sourceFile = "t/é.nqp", sourceLine = 12, sourceLineDelta = 3,
        sectionRaw = intArrayOf(20), sectionLine = intArrayOf(1), sectionFile = arrayOf("inc.nqp"),
        programIndex = prog,
    )

    private fun sample(): UnitRecord {
        val big = "x".repeat(70000) + "é"          // over a class-file constant's cap
        val nestedMeta = UnitMeta("nested1", "nqp", null, null, 0, 0, -1, -1, -1,
            listOf(), arrayOf(block("inner", 0, cuid = "cuid_inner")), listOf(), listOf())
        val nested = UnitRecord(nestedMeta, arrayOf("nqpp 1 0 0 0"), null, mapOf())
        val meta = UnitMeta(
            "ABC123", "nqp", "sc-handle", "desc 🎉", 3, 0, 2, 1, -1,
            listOf(CallSiteRec(byteArrayOf(1, 1), arrayOf("named")), CallSiteRec(byteArrayOf(), null)),
            arrayOf(block("<mainline>", 0), block("deser", 1, outer = 0), null, block("main", 2, outer = 0)),
            listOf(LexValueRec(0, "\$x", "sc-handle", 5, 1)),
            listOf("nested1"),
        )
        return UnitRecord(meta, arrayOf("nqpp 0", big, "nqpp 2  "), byteArrayOf(1, 2, 3, 255.toByte()), mapOf("nested1" to nested))
    }

    @Test
    fun metaRoundTrips() {
        val m = sample().meta
        val back = UnitFormat.readMeta(ByteBuffer.wrap(UnitFormat.writeMeta(m)))
        assertEquals("ABC123", back.unitId)
        assertEquals("desc 🎉", back.scDesc)
        assertEquals(4, back.blocks.size)
        assertNull(back.blocks[2])
        val b1 = back.blocks[1]!!
        assertEquals("deser", b1.name)
        assertEquals(0, b1.outerQbid)
        assertContentEquals(arrayOf("\$x", "naïve"), b1.oLex)
        assertContentEquals(longArrayOf(1, 3, 7, 8, 9), b1.handlers)
        assertTrue(b1.isThunk)
        assertEquals("t/é.nqp", b1.sourceFile)
        assertContentEquals(intArrayOf(20), b1.sectionRaw)
        assertEquals(1, b1.programIndex)
        assertEquals(2, back.callSites.size)
        assertContentEquals(arrayOf("named"), back.callSites[0].names)
        assertNull(back.callSites[1].names)
        assertEquals(5, back.staticLexValues[0].scIdx)
        assertEquals(listOf("nested1"), back.nestedIds)
    }

    @Test
    fun programsRoundTripByByte() {
        val progs = sample().programs
        val back = UnitFormat.readPrograms(ByteBuffer.wrap(UnitFormat.writePrograms(progs)))
        assertContentEquals(progs, back)
    }

    @Test
    fun zipRoundTrips() {
        val r = sample()
        val out = ByteArrayOutputStream()
        UnitZip.write(r, out)
        val bytes = out.toByteArray()
        assertTrue(UnitZip.isUnit(bytes))
        val back = UnitZip.read(bytes)
        assertEquals("ABC123", back.meta.unitId)
        assertContentEquals(r.programs, back.programs)
        assertContentEquals(r.serialized, back.serialized)
        assertEquals(1, back.nested.size)
        assertEquals("inner", back.nested["nested1"]!!.meta.blocks[0]!!.name)
        assertEquals("cuid_inner", back.nested["nested1"]!!.meta.blocks[0]!!.cuid)
        assertNull(back.nested["nested1"]!!.serialized)
    }

    @Test
    fun unknownVersionIsAHardError() {
        val bytes = UnitFormat.writeMeta(sample().meta)
        bytes[4] = 99   // the version int's low byte, right after the magic
        val e = runCatching { UnitFormat.readMeta(ByteBuffer.wrap(bytes)) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("version"))
    }

    @Test
    fun nonUnitBytesAreNotAUnit() {
        assertFalse(UnitZip.isUnit(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())))
    }
}
