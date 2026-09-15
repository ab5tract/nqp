package org.raku.nqp.runtime.unit

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals

class UnitStoreTest {
    // The two non-ASCII literals below are written as Kotlin escapes so this
    // source file stays ASCII: LOAD_NAME is "load" with an o-umlaut plus a
    // camel (an astral codepoint, hence the surrogate pair), PROG2 ends in a
    // u-umlaut. The values, not the source bytes, are what the store round
    // trips.
    private val LOAD_NAME = "l\u00F6ad \uD83D\uDC2A"
    private val PROG2 = "PROG2 \u00FC"

    private fun block(name: String, outer: Int, program: Int, olex: List<String> = emptyList(),
                      lex: List<StaticLexValue> = emptyList(), cuid: String? = null) =
        BlockEntry(name, cuid, outer, program, BlockRecord(
            olex, emptyList(), emptyList(), emptyList(), longArrayOf(0), false, false,
            "t.nqp", 3, 0, null, null, null, lex))

    /** qbids 0, 1, 3 live; 2 is a gap; programs 0..2 map to them; program 0 has 2 dispatch slots, 2 has 1. */
    private fun image(nested: Map<String, UnitStore> = emptyMap(), slots: Map<Int, ByteArray> = emptyMap()) = UnitImage(
        "unit-x", "nqp", "sc-x", "desc", 2, 0, -1, 1, 3,
        listOf(block("main", -1, 0, listOf("\$x", "\$y"), listOf(StaticLexValue("\$y", "sc-x", 7, 1))),
               block("deser", 0, 1), null, block(LOAD_NAME, 0, 2, cuid = "cuid-3")),
        listOf("PROG0 ${"x".repeat(70000)}", "PROG1", PROG2),
        intArrayOf(2, 0, 1), byteArrayOf(9, 8, 7), nested, slots)

    @Test fun roundTripsThroughAHeapImage() {
        val s = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(image())), "<test>")
        assertEquals("unit-x", s.header.unitId)
        assertEquals(4, s.blockCount); assertEquals(3, s.programCount)
        assertEquals(listOf("main", "deser", "", LOAD_NAME), s.header.names)
        assertEquals(listOf(null, null, null, "cuid-3"), s.header.cuids)
        assertEquals(0, s.programIndex(0)); assertEquals(-1, s.programIndex(2)); assertEquals(2, s.programIndex(3))
        assertEquals(-1, s.outerQbid(0)); assertEquals(0, s.outerQbid(3)); assertEquals(-1, s.outerQbid(2))
        assertNull(s.blockRecord(2))
        val r0 = s.blockRecord(0)!!
        assertEquals(listOf("\$x", "\$y"), r0.oLex)
        assertEquals(1, r0.staticLex.size); assertEquals(7, r0.staticLex[0].scIdx)
        assertEquals("t.nqp", s.blockRecord(3)!!.sourceFile)
        assertTrue(s.program(0).startsWith("PROG0 ") && s.program(0).length == 70006)
        assertEquals(PROG2, s.program(2))
        assertContentEquals(byteArrayOf(9, 8, 7), ByteArray(3).also { s.serialized!!.duplicate().get(it) })
    }

    @Test fun dispatchSlotsAreAddressedByProgramAndOrdinalAndEmptyByDefault() {
        val s = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(image())), "<test>")
        assertEquals(3, s.header.dispatchSlotCount)
        assertEquals(2, s.dispatchSlotCount(0)); assertEquals(0, s.dispatchSlotCount(1)); assertEquals(1, s.dispatchSlotCount(2))
        assertNull(s.dispatchSlot(0, 0)); assertNull(s.dispatchSlot(0, 1)); assertNull(s.dispatchSlot(2, 0))
        assertNull(s.dispatchSlot(0, 2)); assertNull(s.dispatchSlot(1, 0)); assertNull(s.dispatchSlot(9, 0))
    }

    @Test fun aFilledSlotComesBackAsItsBytes() {
        // absolute slot 2 = program 2, ordinal 0
        val s = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(image(slots = mapOf(2 to byteArrayOf(4, 2))))), "<test>")
        assertNull(s.dispatchSlot(0, 0))
        val slot = s.dispatchSlot(2, 0)!!
        assertContentEquals(byteArrayOf(4, 2), ByteArray(slot.remaining()).also { slot.get(it) })
    }

    @Test fun nestedUnitsAreCopiedEntryForEntry() {
        val inner = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(UnitImage(
            "inner", "nqp", null, null, 0, 0, -1, -1, -1,
            listOf(block("m", -1, 0)), listOf("P"), intArrayOf(0), null, emptyMap()))), "<inner>")
        val s = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(image(nested = mapOf("inner" to inner)))), "<test>")
        assertEquals(listOf("inner"), s.header.nestedIds)
        val n = s.nested("inner")!!
        assertEquals("inner", n.header.unitId); assertEquals("P", n.program(0)); assertNull(n.serialized)
        assertTrue(n === s.nested("inner"))
        assertNull(s.nested("nope"))
    }

    @Test fun mapsAFile() {
        val f = File.createTempFile("unit-store", ".jar"); f.deleteOnExit()
        f.outputStream().use { UnitImageWriter.write(image(), it) }
        val s = UnitStore.open(f.path)
        assertEquals("unit-x", s.header.unitId); assertEquals("PROG1", s.program(1))
        assertEquals(f.path, s.name)
        // every entry is stored, and unit.index comes first
        java.util.zip.ZipFile(f).use { z ->
            val entries = z.entries().toList()
            assertEquals(UnitStore.INDEX, entries[0].name)
            entries.forEach { assertEquals(java.util.zip.ZipEntry.STORED, it.method, it.name) }
        }
    }

    @Test fun sniffsTheFirstEntryName() {
        val bytes = UnitImageWriter.bytes(image())
        assertTrue(UnitStore.isUnit(ByteBuffer.wrap(bytes)))
        assertFalse(UnitStore.isUnit(ByteBuffer.wrap(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))))
        assertFalse(UnitStore.isUnit(ByteBuffer.wrap(ByteArray(10))))
    }

    @Test fun errorsNameTheUnitEntryAndIndex() {
        val bytes = UnitImageWriter.bytes(image())
        val s = UnitStore.open(ByteBuffer.wrap(bytes), "<test>")
        val e1 = assertFailsWith<IllegalStateException> { s.program(3) }
        assertTrue(e1.message!!.contains("unit-x") && e1.message!!.contains("unit.programs") && e1.message!!.contains("3"), e1.message)
        val e2 = assertFailsWith<IllegalStateException> { s.blockRecord(4) }
        assertTrue(e2.message!!.contains("unit.records") && e2.message!!.contains("4"), e2.message)
        val truncated = ByteBuffer.wrap(bytes.copyOf(bytes.size - 40))
        val e3 = assertFailsWith<IllegalStateException> { UnitStore.open(truncated, "<cut>") }
        assertTrue(e3.message!!.contains("<cut>"), e3.message)
        val wrongVersion = bytes.copyOf()
        // the version int follows the magic in unit.index; the index data starts after the 30-byte local header + name
        val dataStart = 30 + UnitStore.INDEX.length
        wrongVersion[dataStart + 4] = 9
        val e4 = assertFailsWith<IllegalStateException> { UnitStore.open(ByteBuffer.wrap(wrongVersion), "<v9>") }
        assertTrue(e4.message!!.contains("version 9"), e4.message)
    }
}
