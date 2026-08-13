package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext

interface Refreshable {
    fun refresh(tc: ThreadContext)
}
