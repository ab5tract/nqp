package org.raku.nqp.runtime.unit

/** One code-ref block of a unit, everything CodeRefAnnotation + the qb_<n>
 *  method name used to carry, plus the index of the block's program. */
class BlockRec(
    @JvmField val name: String,
    @JvmField val cuid: String?,          // written for a nested or a runtime-compiled unit; null on a jar-bound comp-mode block
    @JvmField val outerQbid: Int,         // -1 = no outer
    @JvmField val oLex: Array<String>,
    @JvmField val iLex: Array<String>,
    @JvmField val nLex: Array<String>,
    @JvmField val sLex: Array<String>,
    @JvmField val handlers: LongArray,    // flat: [count, (len, fields...)*]
    @JvmField val hasExitHandler: Boolean,
    @JvmField val isThunk: Boolean,
    @JvmField val sourceFile: String?,
    @JvmField val sourceLine: Int,
    @JvmField val sourceLineDelta: Int,
    @JvmField val sectionRaw: IntArray?,
    @JvmField val sectionLine: IntArray?,
    @JvmField val sectionFile: Array<String>?,
    @JvmField val programIndex: Int,      // into UnitRecord.programs
)

class CallSiteRec(@JvmField val flags: ByteArray, @JvmField val names: Array<String>?)

class LexValueRec(
    @JvmField val qbid: Int,
    @JvmField val name: String,
    @JvmField val scHandle: String,
    @JvmField val scIdx: Int,
    @JvmField val flags: Int,
)

class UnitMeta(
    @JvmField val unitId: String,
    @JvmField val hll: String,
    @JvmField val scHandle: String?,
    @JvmField val scDesc: String?,
    @JvmField val serializedCodeRefCount: Int,
    @JvmField val mainlineQbid: Int,
    @JvmField val entryQbid: Int,
    @JvmField val deserializeQbid: Int,
    @JvmField val loadQbid: Int,
    @JvmField val callSites: List<CallSiteRec>,
    @JvmField val blocks: Array<BlockRec?>,   // indexed by qbid; null = gap
    @JvmField val staticLexValues: List<LexValueRec>,
    @JvmField val nestedIds: List<String>,
)

/** A whole unit as loaded: meta, programs (UTF-8 text each), the serialized
 *  context (decompressed; null for a nested unit), nested units by id. */
class UnitRecord(
    @JvmField val meta: UnitMeta,
    @JvmField val programs: Array<String>,
    @JvmField val serialized: ByteArray?,
    @JvmField val nested: Map<String, UnitRecord>,
)
