package org.raku.nqp.runtime.unit

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnitDispatchWriterTest {
    private fun bytesOf(b: ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    @Test fun fillsTheNamedSlotsKeepsTheRestAndCopiesEveryOtherEntry() {
        val nestedStore = UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(ProgramUnitTestSupport.image())), "<n>")
        val image = ProgramUnitTestSupport.image(nested = mapOf("n1" to nestedStore))
        val f = File.createTempFile("unit-", ".jar"); f.deleteOnExit()
        f.writeBytes(UnitImageWriter.bytes(UnitImage(image.unitId, image.hll, image.scHandle, image.scDesc,
            image.serializedCodeRefCount, image.mainlineQbid, image.entryQbid, image.deserializeQbid, image.loadQbid,
            image.blocks, image.programs, image.dispatchCounts, image.serialized, image.nested, mapOf(0 to byteArrayOf(1, 2, 3)))))
        val before = UnitStore.open(f.path)
        val recordsBefore = bytesOf(before.entry(UnitStore.RECORDS)!!)
        val serializedBefore = bytesOf(before.entry(UnitStore.SERIALIZED)!!)

        UnitDispatchWriter.rewrite(f.path, mapOf(
            "unit" to mapOf(2 to byteArrayOf(9, 9)),          // program 2, ordinal 0
            "nested/n1" to mapOf(1 to byteArrayOf(4, 5, 6))))   // program 0, ordinal 1 of the nested unit

        val after = UnitStore.open(f.path)
        assertContentEquals(byteArrayOf(1, 2, 3), bytesOf(assertNotNull(after.dispatchSlot(0, 0))), "an unnamed slot keeps its bytes")
        assertNull(after.dispatchSlot(0, 1), "an unnamed empty slot stays empty")
        assertContentEquals(byteArrayOf(9, 9), bytesOf(assertNotNull(after.dispatchSlot(2, 0))))
        assertContentEquals(byteArrayOf(4, 5, 6), bytesOf(assertNotNull(after.nested("n1")!!.dispatchSlot(0, 1))))
        assertContentEquals(recordsBefore, bytesOf(after.entry(UnitStore.RECORDS)!!))
        assertContentEquals(serializedBefore, bytesOf(after.entry(UnitStore.SERIALIZED)!!))
        assertEquals(before.header.dispatchSlotCount, after.header.dispatchSlotCount)
        assertEquals(PROG2_TEXT, after.program(2))
    }

    @Test fun refusesASlotOutsideTheTable() {
        val f = File.createTempFile("unit-", ".jar"); f.deleteOnExit()
        f.writeBytes(UnitImageWriter.bytes(ProgramUnitTestSupport.image()))
        assertFailsWith<IllegalArgumentException> { UnitDispatchWriter.rewrite(f.path, mapOf("unit" to mapOf(3 to byteArrayOf(1)))) }
    }

    companion object { val PROG2_TEXT = ProgramUnitTestSupport.PROG2 }
}
