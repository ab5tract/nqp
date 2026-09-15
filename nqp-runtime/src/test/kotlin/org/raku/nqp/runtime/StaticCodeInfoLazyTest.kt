package org.raku.nqp.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.raku.nqp.runtime.unit.ProgramEntry
import org.raku.nqp.runtime.unit.ProgramUnitTestSupport

class StaticCodeInfoLazyTest {
    private class Source(val names: Array<String>?) : StaticBodySource {
        val fills = AtomicInteger()
        override fun fill(sci: StaticCodeInfo) {
            fills.incrementAndGet()
            sci.oLexicalNames = names
            sci.handlers = arrayOf(longArrayOf(1, 2, 3))
            sci.hasExitHandler = true
            sci.sourceFile = "lazy.nqp"; sci.sourceLine = 42
            sci.finishBody()
        }
    }

    private fun shell(src: Source): CodeRef =
        CodeRef(ProgramUnitTestSupport.unit(), ProgramEntry.ENTER, "f", "cuid-f", ArgsExpectation.USE_BINDER, src)

    @Test fun shellFieldsDoNotFill() {
        val src = Source(arrayOf("\$a"))
        val sci = shell(src).staticInfo
        sci.programIndex = 3; sci.methodName = "qb_3"
        assertEquals(3, sci.programIndex); assertEquals("qb_3", sci.methodName)
        assertEquals(ArgsExpectation.USE_BINDER, sci.argsExpectation)
        assertEquals("cuid-f", sci.uniqueId)
        assertNull(sci.outerStaticInfo); assertNull(sci.engineTarget)
        assertEquals(0, sci.liveInvocations.get())
        assertEquals(0, src.fills.get())
    }

    @Test fun firstBodyReadFillsOnceAndSpinsTheHandles() {
        val src = Source(arrayOf("\$a", "\$b"))
        val sci = shell(src).staticInfo
        assertEquals(0, src.fills.get())
        assertEquals(1, sci.oTryGetLexicalIdx("\$b"))
        assertEquals(1, src.fills.get())
        assertEquals(2, sci.oLexStatic!!.size); assertEquals(2, sci.oLexStaticFlags!!.size)
        assertTrue(sci.hasExitHandler); assertEquals("lazy.nqp", sci.sourceFile); assertEquals(42, sci.sourceLine)
        assertEquals(4, sci.mh.type().parameterCount())      // ENTER minus the resume slot
        assertNotNull(sci.mhResume)
        assertEquals(1, src.fills.get())
        sci.sourceLine; sci.handlers; sci.mh
        assertEquals(1, src.fills.get())
    }

    @Test fun racingReadersFillOnce() {
        val src = Source(arrayOf("\$a"))
        val sci = shell(src).staticInfo
        val go = CountDownLatch(1)
        val threads = (1..8).map { Thread { go.await(); sci.oLexicalNames } }
        threads.forEach { it.start() }; go.countDown(); threads.forEach { it.join() }
        assertEquals(1, src.fills.get())
    }

    @Test fun eagerConstructorIsReadyAtOnce() {
        val cr = CodeRef(ProgramUnitTestSupport.unit(), ProgramEntry.ENTER, "g", "cuid-g",
            arrayOf("\$x"), null, null, null, null, ArgsExpectation.USE_BINDER)
        assertEquals(1, cr.staticInfo.oLexStatic!!.size)
        assertEquals(4, cr.staticInfo.mh.type().parameterCount())
    }

    @Test fun cloneFillsFirst() {
        val src = Source(arrayOf("\$a"))
        val sci = shell(src).staticInfo
        val c = sci.clone()
        assertEquals(1, src.fills.get())
        assertEquals(1, c.oLexStatic!!.size)
        assertTrue(c.oLexStatic !== sci.oLexStatic)
        c.oLexicalNames; assertEquals(1, src.fills.get())
    }
}
