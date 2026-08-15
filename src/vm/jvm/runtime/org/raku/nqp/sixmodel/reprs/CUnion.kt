package org.raku.nqp.sixmodel.reprs

class CUnion : CTypeREPR("CUnion", isUnion = true, requireAttributes = false) {
    override fun newREPRData(): CTypeREPRData = CUnionREPRData()
    override fun newInstance(): CTypeInstance = CUnionInstance()
}
