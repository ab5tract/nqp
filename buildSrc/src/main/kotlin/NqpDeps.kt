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
        // Kotlin runtime for the incrementally converted sources
        // (kotlin-prototype); brings org.jetbrains:annotations transitively.
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
        // Records of the unit artifact (v2, milestone 7 Phase B) are
        // kotlinx-serialization records behind a binary codec of our own.
        "org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0",
    )

    /** Module-name order for sorting resolved jar files back into
     *  THIRDPARTY_JARS order (Gradle resolution orders dependencies first). */
    val moduleOrder = listOf(
        "fastutil", "jline",
        "kotlin-stdlib", "kotlinx-serialization-core-jvm", "annotations",
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
