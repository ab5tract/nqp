import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        // Matches javac --release 25 (class file major version 69).
        jvmTarget = JvmTarget.JVM_25
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // See NqpDeps (buildSrc) for provenance; shared with the root project.
    NqpDeps.thirdParty.forEach { implementation(it) }
}

// The monomorphized VMArrayInstance_* REPR classes are generated from one
// template; see buildSrc/src/main/kotlin/VMArrayInstances.kt.
val generateVMArrayInstances = tasks.register<GenerateVMArrayInstancesTask>("generateVMArrayInstances") {
    outputDir = layout.buildDirectory.dir("generated/vmarray")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.layout.projectDirectory.dir("src/vm/jvm/runtime")))
        kotlin.setSrcDirs(listOf(rootProject.layout.projectDirectory.dir("src/vm/jvm/runtime")))
        kotlin.srcDir(generateVMArrayInstances)
        resources.setSrcDirs(emptyList<Any>())
    }
}

tasks.compileJava {
    // Flag set mirrors tools/templates/jvm/Makefile.in (which used
    // --release 9); the release level was modernized to 25.
    options.release = 25
    options.encoding = "UTF8"
    options.isDebug = false
}

// The jvmconfig.properties baked into the jar (read at runtime by
// Ops.jvmgetconfig via getResourceAsStream). Mirrors the second
// gen-jvm-properties.pl invocation in the Makefile: install-prefix paths.
val generateJarJvmConfig = tasks.register<JvmConfigPropertiesTask>("generateJarJvmConfig") {
    prefix = providers.gradleProperty("nqpPrefix")
    nqpHome = providers.gradleProperty("nqpPrefix").map { "$it/share/nqp" }
    thirdPartyJars = configurations.runtimeClasspath.map { cp ->
        cp.files.sortedBy { NqpDeps.orderKey(it.name) }.map { it.absolutePath }
    }
    generatorLabel = rootProject.layout.projectDirectory
        .file("tools/build/gen-jvm-properties.pl").asFile.absolutePath
    output = layout.buildDirectory.file("generated/jvmconfig-jar/jvmconfig.properties")
}

tasks.jar {
    archiveFileName = "nqp-runtime.jar"
    // Mirrors `jar cf0` (store, no compression).
    entryCompression = ZipEntryCompression.STORED
    from(generateJarJvmConfig)
}
