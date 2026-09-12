/**
 * Third-party runtime dependencies of the JVM backend, resolved from Maven
 * Central. Originally pinned to the versions vendored in 3rdparty/ (see
 * tools/lib/NQP/Config/NQP.pm, configure_jars); since the dependency sweep
 * they track current releases instead, so the Makefile/vendored path lags
 * behind this build. fastutil resolves to the full artifact rather than the
 * vendored minimized build; it is a superset.
 *
 * Order is the THIRDPARTY_JARS classpath order from
 * tools/templates/jvm/Makefile.in and must be preserved.
 */
object NqpDeps {
    val thirdParty = listOf(
        "it.unimi.dsi:fastutil:8.5.19",
        "org.jline:jline:4.3.1",
        "org.lz4:lz4-java:1.8.0",
        // Kotlin runtime for the incrementally converted sources
        // (kotlin-prototype); brings org.jetbrains:annotations transitively.
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
    )

    /** Module-name order for sorting resolved jar files back into
     *  THIRDPARTY_JARS order (Gradle resolution orders dependencies first). */
    val moduleOrder = listOf(
        "fastutil", "jline", "lz4-java",
        "kotlin-stdlib", "annotations",
    )

    fun orderKey(fileName: String): Int {
        val idx = moduleOrder.indexOfFirst {
            fileName.matches(Regex(Regex.escape(it) + """-\d.*"""))
        }
        require(idx >= 0) { "Unexpected runtime jar: $fileName" }
        return idx
    }

    /** The runner bootclasspath, in THIRDPARTY_JARS order; it mirrors
     *  tools/templates/jvm/nqp-j.in. */
    fun runnerJars(fileNames: List<String>): List<String> =
        fileNames.sortedBy(::orderKey)
}
