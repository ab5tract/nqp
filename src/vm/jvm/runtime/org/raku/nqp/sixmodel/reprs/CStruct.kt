package org.raku.nqp.sixmodel.reprs

class CStruct : CTypeREPR("CStruct", isUnion = false, requireAttributes = true) {
    override fun newREPRData(): CTypeREPRData = CStructREPRData()
    override fun newInstance(): CTypeInstance = CStructInstance()
}
