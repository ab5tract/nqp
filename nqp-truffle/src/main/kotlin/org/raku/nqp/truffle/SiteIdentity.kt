package org.raku.nqp.truffle

/** A program of a store-backed unit: the compile key CodeEngines.materialize
 *  hands the engine as the Source name, "<namespace>#<program index>". The
 *  namespace is the store's name and the unit id together (ProgramUnit
 *  .identityNamespace); neither alone tells two stores apart. An in-memory
 *  unit's programs are named "qb_N" and have no identity. */
data class ProgramIdentity(val namespace: String, val programIndex: Int) {
    fun site(ordinal: Int): SiteIdentity = SiteIdentity(siteKey(ordinal))

    /** [site]'s key, for Java callers: a Kotlin value class is its underlying
     *  type there, so SiteIdentity has no getKey() to call from Java. */
    fun siteKey(ordinal: Int): String = "$namespace#$programIndex#$ordinal"

    override fun toString() = "$namespace#$programIndex"

    companion object {
        /** Null unless [sourceName] has the "<namespace>#<index>" shape. The
         *  split is at the LAST '#', so a namespace that carries one of its
         *  own -- a store name is a file path -- still parses. */
        @JvmStatic
        fun parse(sourceName: String): ProgramIdentity? {
            val hash = sourceName.lastIndexOf('#')
            if (hash <= 0 || hash == sourceName.length - 1) return null
            val idx = sourceName.substring(hash + 1).toIntOrNull() ?: return null
            return ProgramIdentity(sourceName.substring(0, hash), idx)
        }
    }
}

/** (namespace, program index, ordinal) as one key for a dispatch instruction.
 *  The namespace is THIS process's path to the store, so the string is not a
 *  cross-process key: Phase C resolves a slot through the site's own unit,
 *  program index and ordinal, and the key only tells two live sites apart. */
@JvmInline
value class SiteIdentity(val key: String)
