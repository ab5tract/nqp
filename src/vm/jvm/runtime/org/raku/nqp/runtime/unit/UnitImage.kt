package org.raku.nqp.runtime.unit

import kotlinx.serialization.Serializable

/**
 * The unit artifact's records (v2, milestone 7 Phase B). UnitHeader is
 * decoded once at open; a BlockRecord is decoded from its own slice
 * when the block's body is first needed (StaticCodeInfo.ensureBody);
 * the block's identity (name, cuid, outer, program index) lives in the
 * header's per-qbid lists and the index's block table, so a shell costs
 * no record decode. UnitImage is what the writer takes: the compiler's
 * record (RecordReader) or a transcoded v1 record, in memory.
 */
@Serializable
class UnitHeader(
    val unitId: String, val hll: String, val scHandle: String?, val scDesc: String?,
    val serializedCodeRefCount: Int, val mainlineQbid: Int, val entryQbid: Int,
    val deserializeQbid: Int, val loadQbid: Int,
    val blockCount: Int, val programCount: Int, val dispatchSlotCount: Int,
    val names: List<String>,     // per qbid, "" for a gap
    val cuids: List<String?>,    // per qbid, null when the block carries none
    val nestedIds: List<String>,
)

@Serializable
class StaticLexValue(val name: String, val scHandle: String, val scIdx: Int, val flags: Int)

@Serializable
class BlockRecord(
    val oLex: List<String>, val iLex: List<String>, val nLex: List<String>, val sLex: List<String>,
    val handlers: LongArray,                 // flat: [count, (len, fields...)*], as v1
    val hasExitHandler: Boolean, val isThunk: Boolean,
    val sourceFile: String?, val sourceLine: Int, val sourceLineDelta: Int,
    val sectionRaw: IntArray?, val sectionLine: IntArray?, val sectionFile: List<String>?,
    val staticLex: List<StaticLexValue>,     // this block's rows (v1 kept one global list)
)

class BlockEntry(val name: String, val cuid: String?, val outerQbid: Int, val programIndex: Int, val record: BlockRecord)

class UnitImage(
    val unitId: String, val hll: String, val scHandle: String?, val scDesc: String?,
    val serializedCodeRefCount: Int, val mainlineQbid: Int, val entryQbid: Int,
    val deserializeQbid: Int, val loadQbid: Int,
    val blocks: List<BlockEntry?>,           // per qbid; null = gap
    val programs: List<String>,
    val dispatchCounts: IntArray,            // per program index: its slot count
    val serialized: ByteArray?,              // raw SC bytes; null for a nested unit
    val nested: Map<String, UnitStore>,      // already-encoded nested units, copied entry for entry
    val dispatchSlots: Map<Int, ByteArray> = emptyMap(),   // absolute slot index -> bytes; empty in Phase B
)
