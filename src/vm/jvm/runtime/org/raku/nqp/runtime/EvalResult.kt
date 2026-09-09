package org.raku.nqp.runtime

import org.raku.nqp.jast2bc.JavaClass
import org.raku.nqp.runtime.unit.UnitRecord
import org.raku.nqp.sixmodel.SixModelObject

/** A runtime compile's output before and after loadcompunit: the class
 *  road hands over class bytes, the record road (NQP_UNIT) a unit record;
 *  loadcompunit turns either into cu and clears the input it consumed. */
class EvalResult : SixModelObject() {
    @JvmField var jc: JavaClass? = null
    @JvmField var record: UnitRecord? = null
    @JvmField var cu: CompilationUnit? = null
}
