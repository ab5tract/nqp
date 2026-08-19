/**
 * NQP source lists for the JVM backend build, transcribed from
 * tools/templates/Makefile-common.in (+ the JVM extras added by
 * tools/templates/jvm/Makefile.in). The checkSourceLists task guards
 * against drift between this file and the Makefile templates.
 *
 * Order matters: it is the concatenation order used by gen-cat.
 */
object NqpSources {
    val NQP_MO = listOf(
        "src/how/Helpers.nqp",
        "src/how/NQPHOWLock.nqp",
        "src/how/Archetypes.nqp",
        "src/how/RoleToRoleApplier.nqp",
        "src/how/NQPConcreteRoleHOW.nqp",
        "src/how/RoleToClassApplier.nqp",
        "src/how/NQPCurriedRoleHOW.nqp",
        "src/how/NQPParametricRoleHOW.nqp",
        "src/how/NQPClassHOW.nqp",
        "src/how/NQPNativeHOW.nqp",
        "src/how/NQPAttribute.nqp",
        "src/how/NQPModuleHOW.nqp",
        "src/how/EXPORTHOW.nqp",
    )

    val CORE_SETTING = listOf(
        "src/core/NativeTypes.nqp",
        "src/core/NQPRoutine.nqp",
        "src/core/dispatchers.nqp",
        "src/core/NQPMu.nqp",
        "src/core/NQPCapture.nqp",
        "src/core/IO.nqp",
        "src/core/Regex.nqp",
        "src/core/Hash.nqp",
        "src/core/NQPLock.nqp",
        "src/core/testing.nqp",
        "src/core/YOUAREHERE.nqp",
    )

    val QASTNODE = listOf(
        "src/QAST/CompileTimeValue.nqp",
        "src/QAST/SpecialArg.nqp",
        "src/QAST/Children.nqp",
        "src/QAST/Node.nqp",
        "src/QAST/NodeList.nqp",
        "src/QAST/Regex.nqp",
        "src/QAST/IVal.nqp",
        "src/QAST/NVal.nqp",
        "src/QAST/SVal.nqp",
        "src/QAST/BVal.nqp",
        "src/QAST/WVal.nqp",
        "src/QAST/Want.nqp",
        "src/QAST/Var.nqp",
        "src/QAST/VarWithFallback.nqp",
        "src/QAST/ParamTypeCheck.nqp",
        "src/QAST/Op.nqp",
        "src/QAST/VM.nqp",
        "src/QAST/Stmts.nqp",
        "src/QAST/Stmt.nqp",
        "src/QAST/Block.nqp",
        "src/QAST/Unquote.nqp",
        "src/QAST/CompUnit.nqp",
        "src/QAST/InlinePlaceholder.nqp",
    )

    val QREGEX = listOf(
        "src/QRegex/NFA.nqp",
        "src/QRegex/Cursor.nqp",
    )

    /** src/vm/jvm/HLL/Backend.nqp + COMMON_HLL_SOURCES; the per-stage
     *  generated nqp-config.nqp is appended by the build. */
    val HLL = listOf(
        "src/vm/jvm/HLL/Backend.nqp",
        "src/HLL/Grammar.nqp",
        "src/HLL/Actions.nqp",
        "src/HLL/Compiler.nqp",
        "src/HLL/SysConfig.nqp",
        "src/HLL/CommandLine.nqp",
        "src/HLL/World.nqp",
        "src/HLL/sprintf.nqp",
    )

    val JASTNODES = listOf("src/vm/jvm/QAST/JASTNodes.nqp")

    /** tools/templates/jvm/qast_sources */
    val QAST = listOf(
        // Before the compiler, which asks it to flatten each regex.
        "src/vm/jvm/QAST/RxDescriptor.nqp",
        "src/vm/jvm/QAST/Compiler.nqp",
    )

    val P6QREGEX = listOf(
        "src/QRegex/P6Regex/Grammar.nqp",
        "src/QRegex/P6Regex/Actions.nqp",
        "src/QRegex/P6Regex/Compiler.nqp",
        "src/QRegex/P6Regex/Optimizer.nqp",
    )

    val P5QREGEX = listOf(
        "src/QRegex/P5Regex/Grammar.nqp",
        "src/QRegex/P5Regex/Actions.nqp",
        "src/QRegex/P5Regex/Compiler.nqp",
    )

    /** NQP_SOURCES_EXTRA (src/vm/jvm/NQP/Ops.nqp) + COMMON_NQP_SOURCES. */
    val NQP = listOf(
        "src/vm/jvm/NQP/Ops.nqp",
        "src/NQP/World.nqp",
        "src/NQP/Grammar.nqp",
        "src/NQP/Optimizer.nqp",
        "src/NQP/Actions.nqp",
        "src/NQP/Compiler.nqp",
    )

    val MODULE_LOADER = listOf("src/vm/jvm/ModuleLoader.nqp")

    /** Makefile-common.in variable name -> the list transcribed from it
     *  (only the lists that come verbatim from Makefile-common.in). */
    val fromMakefileCommon = mapOf(
        "NQP_MO_SOURCES" to NQP_MO,
        "CORE_SETTING_SOURCES" to CORE_SETTING,
        "QASTNODE_SOURCES" to QASTNODE,
        "QREGEX_SOURCES" to QREGEX,
        "COMMON_HLL_SOURCES" to HLL.drop(1),
        "P6QREGEX_SOURCES" to P6QREGEX,
        "P5QREGEX_SOURCES" to P5QREGEX,
        "COMMON_NQP_SOURCES" to NQP.drop(1),
    )
}
