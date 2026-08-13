package org.raku.nqp.sixmodel.reprs

class NFAStateInfo {
    @JvmField var act = 0
    @JvmField var to = 0
    @JvmField var arg_i = 0
    @JvmField var arg_s: String? = null
    @JvmField var arg_uc = '\u0000'
    @JvmField var arg_lc = '\u0000'
}
