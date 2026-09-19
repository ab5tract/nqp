package org.raku.nqp.sixmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drain's rollback contract (milestone 8, Phase C), against a test double
 * of the reader side: a stub lives in a pending table until the drain
 * publishes it into a root slot; a drain whose finish throws publishes
 * nothing and unstubs every entry it made, and a retry on a fresh drain
 * rebuilds from scratch and publishes.
 */
class DrainTest {
    private class FakeReader : Drain.Participant {
        val pending = HashMap<Int, Any>()
        val root = HashMap<Int, Any>()
        var throwOnce = false
        var finishes = 0
        private var serial = 0

        /** A stub on [d]: pending, then queued (an object) or finished (a type object). */
        fun stub(i: Int, d: Drain, queued: Boolean = true): Any {
            val s = Any().also { serial++ }
            pending[i] = s
            val e = Drain.Entry(this, Drain.OBJECT, i)
            if (queued) d.queue.add(e) else d.finished.add(e)
            return s
        }

        override fun finish(e: Drain.Entry) {
            finishes++
            if (throwOnce) { throwOnce = false; throw RuntimeException("finish failed") }
        }

        override fun publish(e: Drain.Entry) {
            root[e.index] = pending.remove(e.index)!!
        }

        override fun unstub(e: Drain.Entry) {
            pending.remove(e.index)
        }
    }

    /** topLevel's shape: run, publish; on a throw, rollback and rethrow. */
    private fun drain(d: Drain) {
        try {
            d.run()
            d.publish()
        } catch (e: Throwable) {
            d.rollback()
            throw e
        }
    }

    @Test
    fun `a finish that throws publishes nothing and unstubs every entry, and a retry publishes`() {
        val r = FakeReader()
        val d1 = Drain()
        r.stub(0, d1)
        r.stub(1, d1, queued = false)          /* already finished (a type object) */
        r.stub(2, d1)                          /* queued behind the failing one */
        r.throwOnce = true
        assertFailsWith<RuntimeException> { drain(d1) }
        assertTrue(r.root.isEmpty(), "nothing reached a root slot")
        assertTrue(r.pending.isEmpty(), "every stub was dropped, the failing one included")
        assertEquals(1, r.finishes, "the drain stopped at the throw")

        val d2 = Drain()
        val s0 = r.stub(0, d2)
        val s1 = r.stub(1, d2, queued = false)
        val s2 = r.stub(2, d2)
        drain(d2)
        assertSame(s0, r.root[0], "the retry publishes its own fresh stub")
        assertSame(s1, r.root[1])
        assertSame(s2, r.root[2])
        assertTrue(r.pending.isEmpty(), "a publish clears the pending slot")
        assertEquals(3, r.finishes)
    }

    @Test
    fun `a held entry is rolled back, and released onto the queue it is finished and published`() {
        val r = FakeReader()
        val d1 = Drain()
        r.pending[5] = Any()
        d1.held.add(Drain.Entry(r, Drain.OBJECT, 5))
        d1.rollback()
        assertNull(r.pending[5], "a held stub is unstubbed on rollback")

        val d2 = Drain()
        val s = Any()
        r.pending[5] = s
        d2.held.add(Drain.Entry(r, Drain.OBJECT, 5))
        d2.run()
        assertEquals(0, r.finishes, "a held entry is not run")
        d2.release()
        drain(d2)
        assertEquals(1, r.finishes)
        assertSame(s, r.root[5])
        assertTrue(d2.held.isEmpty())
    }
}
