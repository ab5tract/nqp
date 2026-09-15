package org.raku.nqp.truffle

/** A program of a store-backed unit: the compile key CodeEngines.materialize
 *  hands the engine as the Source name, "<unit id>#<program index>". An
 *  in-memory unit's programs are named "qb_N" and have no identity. */
data class ProgramIdentity(val unitId: String, val programIndex: Int) {
    fun site(ordinal: Int): SiteIdentity = SiteIdentity(siteKey(ordinal))

    /** [site]'s key, for Java callers: a Kotlin value class is its underlying
     *  type there, so SiteIdentity has no getKey() to call from Java. */
    fun siteKey(ordinal: Int): String = "$unitId#$programIndex#$ordinal"

    override fun toString() = "$unitId#$programIndex"

    companion object {
        /** Null unless [sourceName] has the "<unit id>#<index>" shape. A unit id
         *  never contains '#': it is a class-like name or a sha1. */
        @JvmStatic
        fun parse(sourceName: String): ProgramIdentity? {
            val hash = sourceName.lastIndexOf('#')
            if (hash <= 0 || hash == sourceName.length - 1) return null
            val idx = sourceName.substring(hash + 1).toIntOrNull() ?: return null
            return ProgramIdentity(sourceName.substring(0, hash), idx)
        }
    }
}

/** (unit id, program index, ordinal) as one key: what a dispatch instruction
 *  is known by across processes (spec, Phase B "Site identity"). */
@JvmInline
value class SiteIdentity(val key: String)
