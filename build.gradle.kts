import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.StringWriter

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10" apply false
}
// Root project: orchestrates the JVM backend build (runtime jar, bootstrap
// stages, runner generation). The Makefile build path remains authoritative
// until parity is proven; see docs/gradle-jvm-build.md.
repositories {
    mavenCentral()
}
dependencies {
    testImplementation(kotlin("test"))
}
kotlin {
    jvmToolchain(8)
}

// ---------------------------------------------------------------------------
// Verification: guard the buildSrc ports against drift from the Makefile
// templates and the perl helpers they replace.
// ---------------------------------------------------------------------------

val nqpRootDir: File = layout.projectDirectory.asFile

tasks.register("checkSourceLists") {
    group = "verification"
    description = "Fails if NqpSources.kt drifts from tools/templates/Makefile-common.in"
    doLast {
        val text = File(nqpRootDir, "tools/templates/Makefile-common.in").readText()
        val nfpRe = Regex("""@nfp\(([^)]*)\)@""")
        for ((varName, expected) in NqpSources.fromMakefileCommon) {
            val listRe = Regex("(?m)^" + Regex.escape(varName) + """\s*=\s*\\\n((?:[^\n]*\\\n)*)""")
            val block = listRe.find(text)?.groupValues?.get(1)
                ?: error("$varName not found in Makefile-common.in")
            val paths = nfpRe.findAll(block).map { it.groupValues[1] }.toList()
            check(paths == expected) {
                "Source list drift for $varName:\n  Makefile: $paths\n  Gradle:   $expected"
            }
        }
        println("checkSourceLists: OK (${NqpSources.fromMakefileCommon.size} lists)")
    }
}

tasks.register("genCatParityCheck") {
    group = "verification"
    description = "Byte-compares GenCat output against perl tools/build/gen-cat.pl"
    doLast {
        val root = nqpRootDir
        val lists = mapOf(
            "nqpmo" to NqpSources.NQP_MO,
            "NQPCORE" to NqpSources.CORE_SETTING,
            "QASTNode" to NqpSources.QASTNODE,
            "QRegex" to NqpSources.QREGEX,
            "NQPHLL" to NqpSources.HLL,
            "QAST" to NqpSources.QAST,
            "NQPP6QRegex" to NqpSources.P6QREGEX,
            "NQPP5QRegex" to NqpSources.P5QREGEX,
            "NQP" to NqpSources.NQP,
        )
        var checked = 0
        for (stage in listOf("stage1", "stage2")) {
            for ((name, baseFiles) in lists) {
                var files = baseFiles
                if (name == "NQPHLL") {
                    // The build appends the generated per-stage nqp-config.nqp.
                    val cfg = "gen/jvm/$stage/nqp-config.nqp"
                    if (File(root, cfg).exists()) files = files + cfg
                }
                val proc = ProcessBuilder(
                    listOf("perl", "tools/build/gen-cat.pl", "jvm", stage) + files
                ).directory(root).start()
                val perlBytes = proc.inputStream.readBytes()
                check(proc.waitFor() == 0) { "perl gen-cat.pl failed for $name $stage" }
                val sw = StringWriter()
                GenCat.concat("jvm", stage, files.map { it to File(root, it) }, sw)
                val ourBytes = sw.toString().toByteArray(Charsets.UTF_8)
                check(perlBytes.contentEquals(ourBytes)) {
                    "gen-cat parity failure for $name $stage " +
                        "(perl ${perlBytes.size} bytes, kotlin ${ourBytes.size} bytes)"
                }
                checked++
            }
        }
        println("genCatParityCheck: OK ($checked target/stage combinations)")
    }
}

// ---------------------------------------------------------------------------
// JVM backend build: runtime jar sync, stage1/stage2 bootstrap, runner, tests.
// Command lines replicate `make j-all` (captured via `make -n j-all`).
// ---------------------------------------------------------------------------

val nqpPrefix = providers.gradleProperty("nqpPrefix").get()
val toolchainVersion = (property("javaLanguageVersion") as String).toInt()
// Hard heap ceiling for the stage-compile JVMs. The Makefile build uses
// -XX:+AggressiveHeap, which sizes the heap at roughly half of physical
// RAM per JVM — on a swapless machine several such JVMs stall the whole
// OS in reclaim before the OOM killer acts. An explicit cap fails as a
// contained OutOfMemoryError instead.
val nqpStageMaxHeap = providers.gradleProperty("nqpStageMaxHeap").getOrElse("8g")
val javaToolchains = extensions.getByType<JavaToolchainService>()

val jvmDir: Directory = layout.buildDirectory.dir("jvm").get()
val shareRuntimeDir = jvmDir.dir("share/runtime")
val shareLibDir = jvmDir.dir("share/lib")
// The Truffle modules keep a directory of their own: everything in
// share/runtime is a candidate for the runner's class path, and these
// have to be on the module path instead.
val shareTruffleDir = jvmDir.dir("share/truffle")
val runtimeJarFile = File(projectDir, "nqp-runtime/build/libs/nqp-runtime.jar")
val engineJarFile = File(projectDir, "nqp-truffle/build/libs/nqp-truffle.jar")

val nqpThirdParty: Configuration = configurations.create("nqpThirdParty")

dependencies {
    NqpDeps.thirdParty.forEach { nqpThirdParty(it) }
}

fun thirdPartySorted(): List<File> =
    nqpThirdParty.files.sortedBy { NqpDeps.orderKey(it.name) }

data class StageTarget(
    val name: String,
    val jar: String,
    val sources: List<String>,
    val combined: String?,          // null => source file passed directly, no gen-cat
    val setting: String,
    val settingPath: Boolean,
    val modulePath: Boolean,
    val deps: List<String>,
    val isNqp: Boolean = false,
    val needsConfig: Boolean = false,
)

val stageTargets = listOf(
    StageTarget("Nqpmo", "nqpmo.jar", NqpSources.NQP_MO, "nqpmo.nqp",
        setting = "NULL", settingPath = false, modulePath = false, deps = emptyList()),
    StageTarget("ModuleLoader", "ModuleLoader.jar", NqpSources.MODULE_LOADER, null,
        setting = "NULL", settingPath = false, modulePath = false, deps = emptyList()),
    StageTarget("CoreSetting", "NQPCORE.setting.jar", NqpSources.CORE_SETTING, "NQPCORE.setting",
        setting = "NULL", settingPath = false, modulePath = true, deps = listOf("Nqpmo", "ModuleLoader")),
    StageTarget("QastNode", "QASTNode.jar", NqpSources.QASTNODE, "QASTNode.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true, deps = listOf("CoreSetting")),
    StageTarget("Qregex", "QRegex.jar", NqpSources.QREGEX, "QRegex.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true, deps = listOf("QastNode")),
    StageTarget("Hll", "NQPHLL.jar", NqpSources.HLL, "NQPHLL.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true,
        deps = listOf("Qregex"), needsConfig = true),
    StageTarget("Qast", "QAST.jar", NqpSources.QAST, "QAST.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true,
        deps = listOf("Hll", "Qregex", "QastNode")),
    StageTarget("P6qregex", "NQPP6QRegex.jar", NqpSources.P6QREGEX, "NQPP6QRegex.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true,
        deps = listOf("Hll", "Qregex", "Qast", "QastNode")),
    StageTarget("Nqp", "nqp.jar", NqpSources.NQP, "NQP.nqp",
        setting = "NQPCORE", settingPath = true, modulePath = true,
        deps = listOf("Qast", "P6qregex"), isNqp = true),
)

/** Registers gen-version + gen-cat + JavaExec tasks for one bootstrap stage.
 *  Returns the compile tasks keyed by target name. */
fun registerStage(
    stage: Int,
    compilerDir: File,
    compilerDeps: Collection<TaskProvider<out Task>>,
): Map<String, TaskProvider<out Task>> {
    val stageDir = jvmDir.dir("stage$stage")
    val stageDirPath = stageDir.asFile.absolutePath

    val genVersion = tasks.register("stage${stage}GenVersion") {
        val outFile = stageDir.file("nqp-config.nqp").asFile
        inputs.file("VERSION")
        outputs.file(outFile)
        doLast {
            outFile.parentFile.mkdirs()
            val proc = ProcessBuilder(
                "perl", "tools/build/gen-version.pl",
                nqpPrefix, "$nqpPrefix/share/nqp", "$nqpPrefix/share/nqp/lib", "jvm",
            ).directory(projectDir).start()
            val bytes = proc.inputStream.readBytes()
            check(proc.waitFor() == 0) { "gen-version.pl failed" }
            outFile.writeBytes(bytes)
        }
    }

    val compileTasks = mutableMapOf<String, TaskProvider<out Task>>()
    for (t in stageTargets) {
        val inputFile: File
        var catTask: TaskProvider<GenCatTask>? = null
        if (t.combined != null) {
            inputFile = stageDir.file(t.combined).asFile
            catTask = tasks.register<GenCatTask>("stage${stage}Cat${t.name}") {
                backend = "jvm"
                this.stage = "stage$stage"
                rootDir = projectDir.absolutePath
                val paths = if (t.needsConfig)
                    t.sources + "build/jvm/stage$stage/nqp-config.nqp"
                else t.sources
                sourcePaths = paths
                sourceFiles.from(paths.map { File(projectDir, it) })
                output = inputFile
                if (t.needsConfig) dependsOn(genVersion)
            }
        } else {
            inputFile = File(projectDir, t.sources.single())
        }

        compileTasks[t.name] = tasks.register<JavaExec>("stage${stage}Compile${t.name}") {
            group = "nqp jvm"
            description = "Compiles ${t.jar} with the stage${stage - 1} compiler"
            catTask?.let { dependsOn(it) }
            t.deps.forEach { dependsOn(compileTasks.getValue(it)) }
            compilerDeps.forEach { dependsOn(it) }
            dependsOn(":nqp-runtime:jar")
            // A stage compiled by a compiler that emits descriptors contains
            // calls into the engine, and the next stage runs that code, so
            // every stage needs the engine reachable -- not just the runner.
            dependsOn(":nqp-truffle:jar", "syncTruffleModules")

            val outputJar = stageDir.file(t.jar).asFile
            inputs.file(inputFile)
            inputs.file(runtimeJarFile)
            outputs.file(outputJar)

            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(toolchainVersion)
            }
            workingDir = projectDir
            mainClass = "org.raku.nqp.runtime.unit.UnitMain"
            // The task's class-path input snapshot only; the real class path is set in doFirst below.
            classpath = files(compilerDir, engineJarFile)

            doFirst {
                // Everything on the class path, in the generated runner's
                // order (GenerateRunnerTask): the compiler's own units resolve
                // against compilerDir, then nqp-runtime, the third-party jars
                // and the engine. Not the boot class path: a @TruffleBoundary
                // in the runtime tree is invisible to the compiler when the
                // runtime is loaded by the boot loader (milestone 7, A7/7b).
                // Set here, not at configuration time: thirdPartySorted()
                // resolves the nqpThirdParty configuration, which stays lazy.
                classpath = files(compilerDir, runtimeJarFile) + files(thirdPartySorted()) + files(engineJarFile)
                jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx$nqpStageMaxHeap", "-XX:+AllowParallelDefineClass")
                jvmArgs("--module-path", shareTruffleDir.asFile.absolutePath,
                    "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime")
            }

            val compilerUnit = listOf("${compilerDir.absolutePath}/nqp.jar")
            val stableSc = if (stage == 1) listOf("--stable-sc=stage1") else emptyList()
            args = if (t.isNqp) {
                compilerUnit + listOf("--bootstrap", "--module-path=$stageDirPath", "--setting-path=$stageDirPath",
                    "--setting=${t.setting}", "--target=jar", "--no-regex-lib") +
                    stableSc + listOf("--javaclass=nqp", "--output=$outputJar", inputFile.absolutePath)
            } else {
                compilerUnit + listOf("--bootstrap") +
                    (if (t.settingPath) listOf("--setting-path=$stageDirPath") else emptyList()) +
                    (if (t.modulePath) listOf("--module-path=$stageDirPath") else emptyList()) +
                    listOf("--no-regex-lib", "--target=jar", "--setting=${t.setting}") +
                    stableSc + listOf("--output=$outputJar", inputFile.absolutePath)
            }
        }
    }
    return compileTasks
}

val stage1 = registerStage(1, File(projectDir, "src/vm/jvm/stage0"), emptyList())
val stage2 = registerStage(2, jvmDir.dir("stage1").asFile, stage1.values)

/* Milestone 7 Phase C: the stage2 jars are trained on the trivial
 * program before they become the lib jars. A COPY is trained, because
 * rewriting the compile tasks' outputs would make every later build
 * recompile stage2; jBootstrapFiles keeps copying the untrained jars,
 * so stage0 stays empty-tabled. */
val stage2TrainedDir = jvmDir.dir("stage2-trained")
val stage2Trained = tasks.register<Sync>("stage2Trained") {
    group = "nqp jvm"
    description = "Copies the stage2 jars for dispatch training"
    stageTargets.forEach { from(jvmDir.dir("stage2").file(it.jar)) }
    into(stage2TrainedDir)
    stage2.values.forEach { dependsOn(it) }
    // Always out of date, for the same reason trainDispatch is: the task
    // that follows this one REWRITES these copies, so up-to-date checking
    // cannot describe the pair. The copy has to be restored from stage2
    // every build, or each training run would start from the last one's
    // output and compound onto it instead of training fresh jars. (Until
    // the marker file moved out of this directory, the stray file made the
    // Sync out of date by accident; that is now said rather than inherited.)
    outputs.upToDateWhen { false }
}

// Tees the training run's stderr: the build log goes on showing it while
// doLast turns the same text into the marker file. (commons-io's
// TeeOutputStream is not on the build script's class path here.)
val trainDispatchLog = ByteArrayOutputStream()
val trainDispatchTee = object : OutputStream() {
    override fun write(b: Int) {
        System.err.write(b)
        trainDispatchLog.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        System.err.write(b, off, len)
        trainDispatchLog.write(b, off, len)
    }

    override fun flush() {
        System.err.flush()
        trainDispatchLog.flush()
    }
}

val trainDispatch = tasks.register<JavaExec>("trainDispatch") {
    group = "nqp jvm"
    description = "Runs the trivial program with NQP_DISPATCH_RECORD=all against the stage2 copy, filling its dispatch slots"
    dependsOn(stage2Trained, ":nqp-runtime:jar", ":nqp-truffle:jar", "syncTruffleModules")
    // Outside the synced directory: the marker is this task's only declared
    // output, and the jars it rewrites are its INPUTS. A marker inside
    // stage2TrainedDir would overlap stage2Trained's output directory, which
    // is Gradle's business, not ours.
    val marker = jvmDir.file("dispatch-trained.txt").asFile
    inputs.files(stageTargets.map { stage2TrainedDir.file(it.jar) })
    inputs.file(runtimeJarFile)
    outputs.file(marker)
    // INTENTIONALLY always out of date. The task rewrites the very jars it
    // declares as inputs, so up-to-date checking cannot describe it: every
    // build's Sync restores the untrained jars and this run trains them
    // again, which is what we want -- each build trains from FRESH jars
    // rather than compounding one training run onto the last.
    outputs.upToDateWhen { false }
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolchainVersion) }
    workingDir = projectDir
    mainClass = "org.raku.nqp.runtime.unit.UnitMain"
    // The engine jar alone here: this is the configuration-time class path,
    // whose only job is the input snapshot, and it must NOT name
    // stage2TrainedDir -- the directory this task modifies. The real class
    // path is built in doFirst.
    classpath = files(engineJarFile)
    environment("NQP_DISPATCH_RECORD", "all")
    errorOutput = trainDispatchTee
    doFirst {
        classpath = files(stage2TrainedDir, runtimeJarFile) + files(thirdPartySorted()) + files(engineJarFile)
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx$nqpStageMaxHeap", "-XX:+AllowParallelDefineClass")
        jvmArgs("--module-path", shareTruffleDir.asFile.absolutePath,
            "--add-modules", "org.graalvm.truffle,org.graalvm.truffle.runtime")
        args = listOf("${stage2TrainedDir.asFile.absolutePath}/nqp.jar",
            "--module-path=${stage2TrainedDir.asFile.absolutePath}",
            "--setting-path=${stage2TrainedDir.asFile.absolutePath}", "-e", "")
    }
    doLast {
        val text = trainDispatchLog.toString(Charsets.UTF_8)
        // The recorder ends every run with `done`, and contains its own
        // failures as FAILED lines rather than dying: a run that rewrote
        // some artifacts and then threw would otherwise satisfy a
        // per-artifact marker and leave a half-trained build behind, green.
        check(text.contains("dispatch-record: done") && !text.contains("dispatch-record: FAILED")) {
            "trainDispatch: the training run did not finish cleanly -- no 'dispatch-record: done' line, or a 'dispatch-record: FAILED' one"
        }
        marker.writeText(text.lines().filter { it.startsWith("dispatch-record:") }.joinToString("\n") + "\n")
    }
}

val syncRuntimeJars = tasks.register<Sync>("syncRuntimeJars") {
    from(nqpThirdParty)
    from(runtimeJarFile)
    // The engine sits here with the rest of the runtime, but the runner puts
    // it on the class path: runnerJars decides the class path by name, so
    // an extra jar in this directory is not picked up by accident.
    from(engineJarFile)
    into(shareRuntimeDir)
    dependsOn(":nqp-runtime:jar", ":nqp-truffle:jar")
}

val syncTruffleModules = tasks.register<Sync>("syncTruffleModules") {
    from(project(":nqp-truffle").layout.buildDirectory.dir("truffle-modules"))
    into(shareTruffleDir)
    dependsOn(":nqp-truffle:syncTruffleModules")
}

// Local jvmconfig.properties served from the lib dir: the lib dir precedes
// nqp-runtime.jar on the runner class path, so this shadows the jar's
// baked (install-prefix) copy for build-tree runs — the same mechanism the
// Makefile build uses with its repo-root jvmconfig.properties.
val generateLocalJvmConfig = tasks.register<JvmConfigPropertiesTask>("generateLocalJvmConfig") {
    prefix = projectDir.absolutePath
    nqpHome = jvmDir.dir("share").asFile.absolutePath
    thirdPartyJars.set(provider { thirdPartySorted().map { it.absolutePath } })
    generatorLabel = layout.projectDirectory.file("tools/build/gen-jvm-properties.pl").asFile.absolutePath
    output = shareLibDir.file("jvmconfig.properties")
}

val syncLib = tasks.register<Sync>("syncLib") {
    // The trained copy, not stage2's own output: the lib jars carry the
    // recorded dispatch programs (milestone 7 Phase C).
    stageTargets.forEach { from(stage2TrainedDir.file(it.jar)) }
    into(shareLibDir)
    dependsOn(trainDispatch)
    // jvmconfig.properties is produced into this directory by its own task;
    // don't delete it.
    preserve {
        include("jvmconfig.properties")
    }
}

val generateRunner = tasks.register<GenerateRunnerTask>("generateRunner") {
    jarDir = shareRuntimeDir.asFile.absolutePath
    libDir = shareLibDir.asFile.absolutePath
    runnerJarNames.set(provider { NqpDeps.runnerJars(thirdPartySorted().map { it.name }) })
    truffleModuleDir = shareTruffleDir.asFile.absolutePath
    engineJar = shareRuntimeDir.file(engineJarFile.name).asFile.absolutePath
    output = layout.projectDirectory.file("nqp-j-gradle")
    evalServerOutput = layout.projectDirectory.file("nqp-eval-server-gradle")
    dependsOn(syncRuntimeJars, syncTruffleModules, syncLib, generateLocalJvmConfig)
}

// The JVM backend has no Perl 5 regex support: NQPP5QRegex is not built,
// and rakudo's frontend guards its P5Regex slang off on JVM.
tasks.register("buildJvm") {
    group = "nqp jvm"
    description = "Builds the complete JVM backend (runtime, stage1+2 bootstrap, runner)"
    dependsOn(generateRunner)
}

fun registerProveTask(name: String, testDirs: List<String>) =
    tasks.register<Exec>(name) {
        group = "nqp jvm"
        description = "Runs ${testDirs.joinToString(" ")} against the Gradle-built runner"
        dependsOn("buildJvm")
        workingDir = projectDir
        commandLine(listOf("prove", "-r", "--exec", "./nqp-j-gradle") + testDirs)
    }

registerProveTask("testNqpCore", listOf("t/nqp"))
registerProveTask("testNqp",
    listOf("t/nqp", "t/hll", "t/qregex", "t/qast", "t/jvm", "t/serialization",
        "t/nativecall"))

// Explicit, never part of buildJvm: install into the configured prefix,
// mirroring the Makefile's j-install layout.
tasks.register("installJvm") {
    group = "nqp jvm"
    description = "Installs the JVM backend into $nqpPrefix (share/nqp/{runtime,lib}, bin/nqp-j)"
    dependsOn("buildJvm")
    doLast {
        val runtimeDest = File("$nqpPrefix/share/nqp/runtime")
        val libDest = File("$nqpPrefix/share/nqp/lib")
        val truffleDest = File("$nqpPrefix/share/nqp/truffle")
        val binDest = File("$nqpPrefix/bin")
        listOf(runtimeDest, libDest, truffleDest, binDest).forEach { it.mkdirs() }
        shareRuntimeDir.asFile.listFiles()?.forEach { it.copyTo(File(runtimeDest, it.name), overwrite = true) }
        shareTruffleDir.asFile.listFiles()?.forEach { it.copyTo(File(truffleDest, it.name), overwrite = true) }
        shareLibDir.asFile.listFiles()
            ?.filterNot { it.name == "jvmconfig.properties" }
            ?.forEach { it.copyTo(File(libDest, it.name), overwrite = true) }
        File(projectDir, "tools/jvm/eval-client.pl")
            .copyTo(File(binDest, "eval-client.pl"), overwrite = true).setExecutable(true, false)
    }
}

// Explicit cutover switch: overlay the Gradle output onto the Makefile
// build's gen/jvm/share layout and regenerate ./nqp-j against it, so
// downstream consumers (rakudo's configure) see the Gradle-built backend.
val genShareDir = layout.projectDirectory.dir("gen/jvm/share")

val generateGenJvmConfig = tasks.register<JvmConfigPropertiesTask>("generateGenJvmConfig") {
    prefix = projectDir.absolutePath
    nqpHome = genShareDir.asFile.absolutePath
    thirdPartyJars.set(provider { thirdPartySorted().map { it.absolutePath } })
    generatorLabel = layout.projectDirectory.file("tools/build/gen-jvm-properties.pl").asFile.absolutePath
    output = genShareDir.file("lib/jvmconfig.properties")
}

val generateGenRunner = tasks.register<GenerateRunnerTask>("generateGenRunner") {
    jarDir = genShareDir.dir("runtime").asFile.absolutePath
    libDir = genShareDir.dir("lib").asFile.absolutePath
    runnerJarNames.set(provider { NqpDeps.runnerJars(thirdPartySorted().map { it.name }) })
    truffleModuleDir = genShareDir.dir("truffle").asFile.absolutePath
    engineJar = genShareDir.file("runtime/${engineJarFile.name}").asFile.absolutePath
    output = layout.projectDirectory.file("nqp-j")
}

tasks.register("syncToGen") {
    group = "nqp jvm"
    description = "Copies build/jvm/share over gen/jvm/share and regenerates nqp-j against it"
    dependsOn("buildJvm", generateGenJvmConfig, generateGenRunner)
    doLast {
        shareRuntimeDir.asFile.listFiles()?.forEach {
            it.copyTo(genShareDir.file("runtime/${it.name}").asFile, overwrite = true)
        }
        genShareDir.dir("truffle").asFile.mkdirs()
        shareTruffleDir.asFile.listFiles()?.forEach {
            it.copyTo(genShareDir.file("truffle/${it.name}").asFile, overwrite = true)
        }
        shareLibDir.asFile.listFiles()
            ?.filterNot { it.name == "jvmconfig.properties" }
            ?.forEach { it.copyTo(genShareDir.file("lib/${it.name}").asFile, overwrite = true) }
    }
}

// Explicit, never part of buildJvm: refresh the committed bootstrap jars.
tasks.register<Copy>("jBootstrapFiles") {
    group = "nqp jvm"
    description = "Copies the stage2 output over src/vm/jvm/stage0 (bootstrap refresh)"
    stageTargets.forEach {
        from(jvmDir.dir("stage2").file(it.jar))
    }
    into("src/vm/jvm/stage0")
    stage2.values.forEach { dependsOn(it) }
}
