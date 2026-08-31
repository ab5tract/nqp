package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject

class VMNullInstance private constructor() : SixModelObject() {
    init {
        /* The null is a process-wide singleton, but a GlobalContext is not:
         * borrowing the first run's VMNull type STable (as this used to do)
         * tethered that run's entire universe to a static for the life of
         * the process -- ~200MB pinned per eval-server. Nothing looks
         * through this STable: the serializer and isnull() go by object
         * identity, so a self-contained one referencing nothing run-owned
         * is enough. Dispatching on null was an error before and still is.
         */
        val st = STable(VMNull(), null)
        st.WHAT = this
        this.st = st
    }

    companion object {
        // singleton object
        @Volatile private var theVMNull: VMNullInstance? = null

        @JvmStatic
        @Synchronized
        fun getInstance(tc: ThreadContext): VMNullInstance {
            // double check to make singleton thread safe
            if (theVMNull == null) {
                synchronized(VMNullInstance::class.java) {
                    if (theVMNull == null)
                        theVMNull = VMNullInstance()
                }
            }

            return theVMNull!!
        }
    }
}
