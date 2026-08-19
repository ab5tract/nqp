import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The Truffle grammar engine. Kept in its own subproject because Truffle
 * has to be resolved as MODULES on the module path for the optimizing
 * runtime to bind to the JDK's compiler; the jars nqp-runtime publishes
 * through THIRDPARTY_JARS all land on the classpath, where Truffle would
 * silently fall back to its interpreter (Truffle.getRuntime() answering
 * "Interpreted" rather than "Oracle GraalVM").
 */
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of((property("javaLanguageVersion") as String).toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Must match the GraalVM the JDK is: a mismatched pair fails either as
    // the fallback interpreter or an InternalError out of libgraal init.
    val truffle = property("truffleVersion") as String
    implementation("org.graalvm.truffle:truffle-api:$truffle")
    implementation("org.graalvm.polyglot:polyglot:$truffle")
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:$truffle")

    // Only needed to run: the optimizing runtime and what it pulls in.
    runtimeOnly("org.graalvm.truffle:truffle-runtime:$truffle")

    testImplementation("org.graalvm.truffle:truffle-api:$truffle")
    testImplementation("org.graalvm.polyglot:polyglot:$truffle")
}

tasks.compileJava {
    options.release = 25
    options.encoding = "UTF8"
}

/*
 * Running needs the Truffle jars as MODULES, with the engine's own classes
 * on the classpath. On the classpath Truffle finds no compiler and falls
 * back to its interpreter, which measures as "Truffle is slow" rather than
 * failing outright, so the harness also asserts the runtime name.
 */
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Measures the regex engine against java.util.regex."
    mainClass = "org.raku.nqp.truffle.RxBench"
    classpath = sourceSets["main"].output
    val truffleModules = configurations.runtimeClasspath
    doFirst {
        jvmArgs(
            "--module-path", truffleModules.get().asPath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime",
        )
    }
}
