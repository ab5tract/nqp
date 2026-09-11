package org.raku.nqp.sixmodel.reprs

class CPPStruct : CTypeREPR("CPPStruct", isUnion = false, requireAttributes = false) {
    override fun newREPRData(): CTypeREPRData = CPPStructREPRData()
    override fun newInstance(): CTypeInstance = CPPStructInstance()
}
