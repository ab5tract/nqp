package org.raku.nqp.runtime

/** Throw this to propagate a specific Java exception into Javaland. */
class JavaCallinException(raw: Throwable) : ControlException() {
    init {
        initCause(raw)
    }
}
