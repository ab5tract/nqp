package org.raku.nqp.runtime

import org.raku.nqp.runtime.unit.UnitStore
import org.raku.nqp.sixmodel.SixModelObject

/** A runtime compile's unit before and after loadcompunit: the compile
 *  hands over an opened store, loadcompunit turns it into cu and clears the
 *  store reference it consumed. */
class EvalResult : SixModelObject() {
    @JvmField var record: UnitStore? = null
    @JvmField var cu: CompilationUnit? = null
}
