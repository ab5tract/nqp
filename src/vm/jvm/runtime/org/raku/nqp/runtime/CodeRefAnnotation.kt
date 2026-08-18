package org.raku.nqp.runtime

/**
 * Attached to every code-ref method of a generated compilation unit by the
 * JAST compiler and read reflectively by CompilationUnit.initializeCompilationUnit.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CodeRefAnnotation(
    val name: String = "",
    val cuid: String = "",
    val outerQbid: Int = -1,
    val oLexicalNames: Array<String> = [],
    val iLexicalNames: Array<String> = [],
    val nLexicalNames: Array<String> = [],
    val sLexicalNames: Array<String> = [],
    val handlers: LongArray = [0],
    val hasExitHandler: Boolean = false,
    val argsExpectation: Short = 0,
    val isThunk: Boolean = false,
    val sourceFile: String = "",
    val sourceLine: Int = -1,
    val sourceLineDelta: Int = 0,
)
