import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The Truffle grammar engine. Kept in its own subproject because Truffle
 * has to be resolved as MODULES on the module path for the optimizing
 * runtime to bind to the JDK's compiler; the jars nqp-runtime publishes
 * through THIRDPARTY_JARS all land on the classpath, where Truffle would
 * silently fall back to its interpreter (Truffle.getRuntime() answering
 * "Interpreted" rather than "Oracle GraalVM").
 *
 * Kotlin as well as Java, and the split between them is deliberate rather
 * than historical. The engine proper is hand-written Kotlin: its nodes do
 * their own specialization with explicit state and `@CompilationFinal`, so
 * Truffle's `@Specialization` DSL -- which is an annotation processor that
 * GENERATES a Java subclass of your node -- buys nothing here and would cost
 * a kapt round trip on every build plus `allopen` to defeat Kotlin's
 * final-by-default.
 *
 * What stays Java is `RxLanguage`, and only because
 * `@TruffleLanguage.Registration` and `@ExportLibrary` genuinely are
 * annotation-processed: the polyglot machinery finds a language through a
 * generated provider, so that one file earns its processor.
 */
plugins {
    java
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of((property("javaLanguageVersion") as String).toInt())
    }
}

kotlin {
    compilerOptions {
        // Matches javac --release 25, as nqp-runtime does.
        jvmTarget = JvmTarget.JVM_25
        // As nqp-runtime: no null assertions on parameters, calls and
        // receivers. Their failure paths inline in full under partial
        // evaluation (NqpDispatch is on the compiled road of every
        // dispatch instruction), which was a "too deep inlining" bailout.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

repositories {
    mavenCentral()
}

/*
 * Just the Truffle artifacts, resolved apart from everything else. The
 * runner puts these -- and only these -- on the module path: nqp-runtime
 * is already on the boot classpath, and a jar that is on both becomes a
 * second, unrelated copy of every class it holds.
 */
val truffleModules: Configuration = configurations.create("truffleModules") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    /* The engine calls back into the existing runtime for anything that is
     * not the match itself: subrules are NQP CodeRefs, and the cursor whose
     * position and captures a match updates lives there too. */
    implementation(project(":nqp-runtime"))

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

    truffleModules("org.graalvm.truffle:truffle-api:$truffle")
    truffleModules("org.graalvm.truffle:truffle-runtime:$truffle")
    truffleModules("org.graalvm.polyglot:polyglot:$truffle")
}

/*
 * Stages the Truffle modules where the runner can name them. Without this
 * they exist only inside the Gradle cache, whose layout is a hash per
 * artifact and no use to a generated shell script.
 */
val truffleModuleDir = layout.buildDirectory.dir("truffle-modules")

val syncTruffleModules = tasks.register<Sync>("syncTruffleModules") {
    group = "build"
    description = "Stages the Truffle module jars for the runner's --module-path."
    from(truffleModules)
    into(truffleModuleDir)
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
 *
 * The engine is Kotlin, so running any of the harnesses below needs the
 * Kotlin runtime. It cannot come from `runtimeClasspath` wholesale: that
 * carries the Truffle jars, which have to resolve as MODULES and become a
 * second unrelated copy of every class if they are also on the class path.
 * In a real run the stdlib is already on nqp's boot classpath, next to
 * nqp-runtime, which is Kotlin too.
 */
val kotlinRuntime: FileCollection = configurations.runtimeClasspath.get().filter {
    it.name.startsWith("kotlin-stdlib") || it.name.startsWith("annotations-")
}

fun harnessClasspath(): FileCollection = sourceSets["main"].output + kotlinRuntime

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Measures the regex engine against java.util.regex."
    mainClass = "org.raku.nqp.truffle.RxBench"
    classpath = harnessClasspath()
    val truffleModules = configurations.runtimeClasspath
    doFirst {
        jvmArgs(
            "--module-path", truffleModules.get().asPath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime",
        )
    }
}

tasks.register<JavaExec>("rxcheck") {
    group = "verification"
    description = "Checks the engine's answers against java.util.regex."
    mainClass = "org.raku.nqp.truffle.RxCheck"
    classpath = harnessClasspath()
    val truffleModules = configurations.runtimeClasspath
    doFirst {
        jvmArgs(
            "--module-path", truffleModules.get().asPath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime",
        )
    }
}

tasks.register<JavaExec>("nqpcheck") {
    group = "verification"
    description = "Smoke-checks the Bytecode DSL interpreter for general NQP code."
    mainClass = "org.raku.nqp.truffle.NqpCheck"
    classpath = harnessClasspath()
    val truffleModules = configurations.runtimeClasspath
    doFirst {
        jvmArgs(
            "--module-path", truffleModules.get().asPath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime",
        )
    }
}

tasks.register<JavaExec>("rxdesc") {
    group = "verification"
    description = "Checks the QAST::Regex descriptor decodes to the same engine."
    mainClass = "org.raku.nqp.truffle.RxDescriptorCheck"
    classpath = harnessClasspath()
    val truffleModules = configurations.runtimeClasspath
    doFirst {
        jvmArgs(
            "--module-path", truffleModules.get().asPath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime",
        )
    }
}
