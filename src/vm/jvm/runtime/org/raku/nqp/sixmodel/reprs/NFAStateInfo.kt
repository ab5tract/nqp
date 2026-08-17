package org.raku.nqp.sixmodel.reprs

class NFAStateInfo {
    @JvmField var act = 0
    @JvmField var to = 0
    @JvmField var argI = 0
    @JvmField var argS: String? = null
    @JvmField var argUc = '\u0000'
    @JvmField var argLc = '\u0000'
}
