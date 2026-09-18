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
 */
class Drain {
    class Entry(@JvmField val reader: SerializationReader, @JvmField val kind: Int, @JvmField val index: Int)

    val queue = ArrayDeque<Entry>()
    val finished = ArrayList<Entry>()

    fun run() {
        while (true) {
            val e = queue.removeFirstOrNull() ?: return
            e.reader.finish(e)
            finished.add(e)
        }
    }

    fun publish() {
        for (e in finished) e.reader.publish(e)
    }

    companion object {
        const val STABLE = 0
        const val OBJECT = 1
        const val CODE = 2
        const val CONTEXT = 3
    }
}
