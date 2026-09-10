package org.raku.nqp.runtime

import org.raku.nqp.runtime.unit.UnitRecord
import org.raku.nqp.sixmodel.SixModelObject

/** A runtime compile's record before and after loadcompunit: the compile
 *  hands over a unit record, loadcompunit turns it into cu and clears the
 *  record it consumed. */
class EvalResult : SixModelObject() {
    @JvmField var record: UnitRecord? = null
    @JvmField var cu: CompilationUnit? = null
}
