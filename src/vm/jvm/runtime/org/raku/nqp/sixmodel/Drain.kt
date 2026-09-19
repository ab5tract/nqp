package org.raku.nqp.sixmodel

/**
 * The worklist of one outermost demand (milestone 8, Phase C). It belongs
 * to the drain, not to a reader: entries of any SC's reader queue on it,
 * and everything it finished is published into the root slots when the
 * outermost demand completes, in one batch (an object finished early may
 * hold a stub finished later in the same drain; publishing per drain is
 * what keeps a concurrent reader from seeing it). STables finish the
 * moment they are demanded -- an object stub needs its STable's REPR data
 * -- and closures at their stub; objects and contexts wait on the queue.
 * A drain that throws leaves nothing behind: its entries are unstubbed so
 * the next demand starts over. [tc] is the context of the thread that
 * opened the drain, which every reader's roads use for its duration (null
 * only in the unit tests, which have no reader).
 */
class Drain(@JvmField val tc: org.raku.nqp.runtime.ThreadContext? = null) {
    /** The reader side of an entry: SerializationReader, or a test double. */
    interface Participant {
        fun finish(e: Entry)
        fun publish(e: Entry)
        fun unstub(e: Entry)
    }

    class Entry(@JvmField val reader: Participant, @JvmField val kind: Int, @JvmField val index: Int)

    val queue = ArrayDeque<Entry>()
    val finished = ArrayList<Entry>()
    /** Stubbed but not yet to be finished (repossess's objects until their
     *  STables are swapped): rolled back like the rest, never run or published
     *  until [release] moves them onto the queue. */
    val held = ArrayList<Entry>()

    fun release() {
        queue.addAll(held)
        held.clear()
    }

    fun run() {
        while (true) {
            val e = queue.removeFirstOrNull() ?: return
            /* On the list BEFORE the finish: an entry whose finish throws is
             * on neither list otherwise, and rollback would leave its memo
             * holding a half-built object. publish() runs only after run()
             * returns, so the order it sees is unaffected. */
            finished.add(e)
            e.reader.finish(e)
        }
    }

    fun publish() {
        for (e in finished) e.reader.publish(e)
    }

    /** The exception path: nothing reached a root slot, so drop every stub
     *  this drain made, finished and queued alike. */
    fun rollback() {
        for (e in finished) e.reader.unstub(e)
        for (e in queue) e.reader.unstub(e)
        for (e in held) e.reader.unstub(e)
    }

    companion object {
        const val STABLE = 0
        const val OBJECT = 1
        const val CODE = 2
        const val CONTEXT = 3
    }
}
