package org.raku.nqp.runtime.unit

import java.nio.ByteBuffer
import org.raku.nqp.runtime.GlobalContext
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SerializationContext

/**
 * The one unit fixture the runtime tests share: a v2 UnitImage encoded by
 * UnitImageWriter and opened as a UnitStore, so a ProgramUnit over it takes
 * exactly the road a loaded artifact takes. Tests that only need a
 * CompilationUnit to hang a code ref on (StaticCodeInfoLazyTest,
 * SerializationContextTest) call unit(); ProgramUnitTest exercises the whole
 * shape.
 */
object ProgramUnitTestSupport {
    /** Code points spelled out, so this source stays ASCII while the values
     *  are not: LOAD_NAME is "load" with an o-umlaut plus a camel (astral),
     *  PROG2 ends in a u-umlaut. */
    private fun cp(vararg codePoints: Int) = buildString { for (c in codePoints) appendCodePoint(c) }

    val LOAD_NAME: String = "l" + cp(0xF6) + "ad " + cp(0x1F42A)
    val PROG2: String = "PROG2 " + cp(0xFC)
    const val SC_HANDLE = "sc-x"
    const val SC_IDX = 7

    private fun block(name: String, outer: Int, program: Int, olex: List<String> = emptyList(),
                      lex: List<StaticLexValue> = emptyList(), handlers: LongArray = longArrayOf(0),
                      cuid: String? = null) =
        BlockEntry(name, cuid, outer, program, BlockRecord(
            olex, emptyList(), emptyList(), emptyList(), handlers, false, false,
            "t.nqp", 3, 0, null, null, null, lex))

    /**
     * qbids 0, 1 and 3 live, 2 a gap; programs 0..2 map to them; program 0
     * has 2 dispatch slots and program 2 has 1, all of them empty (Phase B
     * persists none). Blocks 0 and 3 each carry one static lexical value out
     * of SC_HANDLE, so a fill before the drain queues and a fill after it
     * applies at once. deserializeQbid is -1: the fixture has no deserialize
     * program, so runDeserializeIfAvailable runs the drain alone.
     */
    fun image(nested: Map<String, UnitStore> = emptyMap()): UnitImage = UnitImage(
        "unit-x", "nqp", SC_HANDLE, "desc", 2, 0, -1, -1, 3,
        listOf(
            block("main", -1, 0, olex = listOf("\$x", "\$y"),
                  lex = listOf(StaticLexValue("\$y", SC_HANDLE, SC_IDX, 1))),
            block("deser", 0, 1, handlers = longArrayOf(1, 2, 5, 6)),
            null,
            block(LOAD_NAME, 0, 2, olex = listOf("\$z"),
                  lex = listOf(StaticLexValue("\$z", SC_HANDLE, SC_IDX, 2)), cuid = "cuid-3")),
        listOf("PROG0", "PROG1", PROG2),
        intArrayOf(2, 0, 1), byteArrayOf(9, 8, 7), nested)

    /** [storeName] is what UnitLoader passes: a file path for an artifact,
     *  a "<...>"-wrapped label for a unit built in this process. It decides
     *  ProgramUnit.identityNamespace, so a test that cares names it. */
    fun unit(storeName: String = "<test>", nested: Map<String, UnitStore> = emptyMap()): ProgramUnit =
        ProgramUnit(UnitStore.open(ByteBuffer.wrap(UnitImageWriter.bytes(image(nested))), storeName))

    /** A fresh runtime: a GlobalContext bootstraps the MOP and hands back its
     *  main thread's context. */
    fun tc(): ThreadContext = GlobalContext().mainThread!!

    /** The fixture plus a thread context in which SC_HANDLE is installed and
     *  carries an object at SC_IDX -- what the static lexical rows of blocks
     *  0 and 3 point at. */
    fun unitWithSc(): Pair<ProgramUnit, ThreadContext> {
        val tc = tc()
        val sc = SerializationContext(SC_HANDLE)
        sc.initObjectList(SC_IDX + 1)
        sc.addObject(tc.gc.BOOTStr, SC_IDX)
        tc.gc.scs[SC_HANDLE] = sc
        return unit() to tc
    }
}
