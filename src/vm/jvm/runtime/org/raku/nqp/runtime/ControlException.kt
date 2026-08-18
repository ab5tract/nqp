package org.raku.nqp.runtime

abstract class ControlException @JvmOverloads constructor(cause: Throwable? = null) : RuntimeException(null, cause, false, false)
