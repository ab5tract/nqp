package org.raku.nqp.runtime.unit

import org.raku.nqp.runtime.GlobalContext
import org.raku.nqp.runtime.LibraryLoader
import org.raku.nqp.runtime.Ops

/** The runner scripts' main class: `UnitMain <unit.jar> args...`. Either
 *  road: the loader decides. Replaces the generated `nqp`/`perl6` class
 *  main, which called CompilationUnit.enterFromMain on its own Class. */
object UnitMain {
    @JvmStatic
    fun main(argv: Array<String>) {
        require(argv.isNotEmpty()) { "usage: UnitMain <unit jar> [args...]" }
        val tc = GlobalContext().mainThread!!
        val cu = LibraryLoader.loadApp(tc, argv[0], false)
        val entry = cu.entryQbid()
        check(entry >= 0) { "${argv[0]} is not an entry point (no entry block)" }
        Ops.invokeMain(tc, cu.lookupCodeRef(entry), cu.unitId(), argv.copyOfRange(1, argv.size))
    }
}
