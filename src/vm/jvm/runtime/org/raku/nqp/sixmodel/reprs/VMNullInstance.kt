package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class VMNullInstance private constructor(tc: ThreadContext) : SixModelObject() {
    init {
        this.st = tc.gc.VMNull!!.st
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
                        theVMNull = VMNullInstance(tc)
                }
            }

            return theVMNull!!
        }
    }
}
