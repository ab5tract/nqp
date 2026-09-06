package org.raku.nqp.sixmodel.reprs

import java.util.HashMap

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class VMHashInstance : SixModelObject() {
    lateinit var storage: HashMap<String, SixModelObject?>

    // NOTE: keys are raw here. VMHash is the low-level hash the COMPILER itself
    // uses for exact-string bookkeeping; NFC-folding keys here broke the build.
    // Canonical-equivalence for Raku hashes belongs at the Raku Hash level.
    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject? {
        return storage[key!!]
    }

    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) {
        storage.put(key!!, value)
    }

    override fun exists_key(tc: ThreadContext, key: String?): Long {
        return if (storage.containsKey(key)) 1 else 0
    }

    override fun delete_key(tc: ThreadContext, key: String?) {
        storage.remove(key)
    }

    override fun elems(tc: ThreadContext): Long {
        return storage.size.toLong()
    }

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val copy = this.clone() as VMHashInstance
            copy.sc = null
            @Suppress("UNCHECKED_CAST")
            copy.storage = storage.clone() as HashMap<String, SixModelObject?>
            return copy
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }
}
