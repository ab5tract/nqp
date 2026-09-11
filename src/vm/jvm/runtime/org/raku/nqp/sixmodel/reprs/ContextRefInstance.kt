package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.CallFrame
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class ContextRefInstance : SixModelObject() {
    lateinit var context: CallFrame

    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject? {
        val idx = context.codeRef.staticInfo.oTryGetLexicalIdx(key!!)
        return if (idx == -1) Ops.createNull(tc) else context.oLexOrVivify(idx)
    }

    override fun at_key_native(tc: ThreadContext, key: String?) {
        var idx = context.codeRef.staticInfo.iTryGetLexicalIdx(key!!)
        if (idx != -1) {
            tc.nativeI = context.iLex!![idx]
            tc.nativeType = ThreadContext.NATIVE_INT
            return
        }
        idx = context.codeRef.staticInfo.nTryGetLexicalIdx(key)
        if (idx != -1) {
            tc.nativeN = context.nLex!![idx]
            tc.nativeType = ThreadContext.NATIVE_NUM
            return
        }
        idx = context.codeRef.staticInfo.sTryGetLexicalIdx(key)
        if (idx != -1) {
            tc.nativeS = context.sLex!![idx]
            tc.nativeType = ThreadContext.NATIVE_STR
            return
        }
        throw ExceptionHandling.dieInternal(tc, "No lexical $key in this lexpad")
    }

    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) {
        val idx = context.codeRef.staticInfo.oTryGetLexicalIdx(key!!)
        if (idx == -1)
            throw ExceptionHandling.dieInternal(tc, "No lexical $key in this lexpad")
        context.oLex!![idx] = value
    }

    override fun bind_key_native(tc: ThreadContext, key: String?) {
        var idx = context.codeRef.staticInfo.iTryGetLexicalIdx(key!!)
        if (idx != -1) {
            context.iLex!![idx] = tc.nativeI
            tc.nativeType = ThreadContext.NATIVE_INT
            return
        }
        idx = context.codeRef.staticInfo.nTryGetLexicalIdx(key)
        if (idx != -1) {
            context.nLex!![idx] = tc.nativeN
            tc.nativeType = ThreadContext.NATIVE_NUM
            return
        }
        idx = context.codeRef.staticInfo.sTryGetLexicalIdx(key)
        if (idx != -1) {
            context.sLex!![idx] = tc.nativeS
            tc.nativeType = ThreadContext.NATIVE_STR
            return
        }
        throw ExceptionHandling.dieInternal(tc, "No lexical $key in this lexpad")
    }

    override fun elems(tc: ThreadContext): Long {
        val info = context.codeRef.staticInfo
        return ((info.oLexicalNames?.size ?: 0) +
                (info.iLexicalNames?.size ?: 0) +
                (info.nLexicalNames?.size ?: 0) +
                (info.sLexicalNames?.size ?: 0)).toLong()
    }

    override fun exists_key(tc: ThreadContext, key: String?): Long {
        val sci = context.codeRef.staticInfo
        return if (sci.oTryGetLexicalIdx(key!!) != -1 ||
                   sci.iTryGetLexicalIdx(key) != -1 ||
                   sci.nTryGetLexicalIdx(key) != -1 ||
                   sci.sTryGetLexicalIdx(key) != -1)
            1 else 0
    }
}
