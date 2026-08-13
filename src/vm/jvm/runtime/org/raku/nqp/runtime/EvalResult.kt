package org.raku.nqp.runtime

import org.raku.nqp.jast2bc.JavaClass
import org.raku.nqp.sixmodel.SixModelObject

class EvalResult : SixModelObject() {
    @JvmField var jc: JavaClass? = null
    @JvmField var cu: CompilationUnit? = null
}
