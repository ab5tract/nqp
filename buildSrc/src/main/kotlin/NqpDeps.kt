/**
 * Third-party runtime dependencies of the JVM backend — the same versions as
 * the jars vendored in 3rdparty/ (see tools/lib/NQP/Config/NQP.pm,
 * configure_jars), resolved from Maven Central. fastutil resolves to the full
 * artifact rather than the vendored minimized build; it is a superset.
 *
 * Order is the THIRDPARTY_JARS classpath order from
 * tools/templates/jvm/Makefile.in and must be preserved.
 */
object NqpDeps {
    val thirdParty = listOf(
        "org.ow2.asm:asm:4.1",
        "org.ow2.asm:asm-tree:4.1",
        "it.unimi.dsi:fastutil:8.5.13",
        "jline:jline:1.0",
        "net.java.dev.jna:jna:4.5.0",
        "net.java.dev.jna:jna-platform:4.5.0",
        "org.lz4:lz4-java:1.8.0",
        // Kotlin runtime for the incrementally converted sources
        // (kotlin-prototype); brings org.jetbrains:annotations transitively.
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
    )

    /** Module-name order for sorting resolved jar files back into
     *  THIRDPARTY_JARS order (Gradle resolution orders dependencies first). */
    val moduleOrder = listOf(
        "asm", "asm-tree", "fastutil", "jline", "jna", "jna-platform", "lz4-java",
        "kotlin-stdlib", "annotations",
    )

    fun orderKey(fileName: String): Int {
        val idx = moduleOrder.indexOfFirst {
            fileName.matches(Regex(Regex.escape(it) + """-\d.*"""))
        }
        require(idx >= 0) { "Unexpected runtime jar: $fileName" }
        return idx
    }

    /** The runner bootclasspath omits asm-tree, mirroring
     *  tools/templates/jvm/nqp-j.in. */
    fun runnerJars(fileNames: List<String>): List<String> =
        fileNames.sortedBy(::orderKey).filterNot { it.startsWith("asm-tree-") }
}
