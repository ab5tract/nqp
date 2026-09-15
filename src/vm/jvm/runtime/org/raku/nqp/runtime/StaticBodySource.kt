package org.raku.nqp.runtime

/**
 * Where a lazy StaticCodeInfo gets its body from (milestone 7 Phase B,
 * lazy-loading layer 2). ProgramUnit implements it per block over the
 * block's record slice. fill() sets the body fields through their
 * setters (which never trigger a fill), calls finishBody(), and then
 * applies or queues the block's static lexical values. It runs once,
 * under the info's monitor, on the first read of any body field.
 */
interface StaticBodySource {
    fun fill(sci: StaticCodeInfo)
}
