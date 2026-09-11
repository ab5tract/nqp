# Gradle build for the JVM backend

This checkout carries a Gradle build for the JVM backend alongside the
classic `Configure.pl` + `make` flow. The two are independent: Gradle builds
into `build/jvm/` and `nqp-runtime/build/`, the Makefile into `bin/`,
`gen/jvm/` and the repo root. Neither disturbs the other until you opt in
to the cutover (below).

## Layout

| File | Purpose |
|---|---|
| `settings.gradle.kts` | project layout (`:nqp-runtime`) + toolchain resolver |
| `build.gradle.kts` | bootstrap stage graph, runner, tests, parity checks |
| `nqp-runtime/build.gradle.kts` | compiles `src/vm/jvm/runtime` into `nqp-runtime.jar` |
| `buildSrc/src/main/kotlin/` | `GenCat` (gen-cat.pl port), `JvmConfigPropertiesTask` (gen-jvm-properties.pl port), `GenerateRunnerTask`, `NqpSources`, `NqpDeps` |
| `gradle.properties` | `nqpPrefix` (mirrors config.status), toolchain pin |

Third-party jars come from Maven Central at the same versions as the jars
vendored in `3rdparty/` (see `NqpDeps.kt`). The only difference: fastutil is
the full artifact, not the custom `-min` build — a superset.

## Common tasks

```sh
./gradlew buildJvm        # full build: runtime jar, stage1+stage2, runner
./gradlew testNqpCore     # prove t/nqp with the Gradle-built runner
./gradlew testNqp         # the full JVM test-directory list
./gradlew checkSourceLists genCatParityCheck   # drift guards vs Makefile/perl
./gradlew jBootstrapFiles # refresh src/vm/jvm/stage0 from stage2 (explicit)
```

The build-tree runner is `./nqp-j-gradle` (absolute paths into
`build/jvm/share/`, so it works from any directory and never picks up the
Makefile build's artifacts). The Makefile's runner remains `./nqp-j`.

## Memory limits

Unlike the Makefile build (`-XX:+AggressiveHeap`, which sizes every JVM's
heap at roughly half of physical RAM), the Gradle path caps heaps
explicitly — several AggressiveHeap JVMs overlapping (a stage compile, the
Gradle/Kotlin daemons, a test and its spawned subprocesses) can stall a
swapless machine in memory reclaim before the OOM killer reacts:

- stage-compile JVMs: `-Xmx8g`, override with `-PnqpStageMaxHeap=12g`
- `nqp-j-gradle` (and every test process prove spawns with it): `-Xmx4g`,
  override with the `NQP_JVM_MAXHEAP` environment variable

An overflow surfaces as a contained `java.lang.OutOfMemoryError`; raise the
knob and retry. For heavy runs an OS-level ceiling on top is cheap
insurance — the kernel then OOM-kills inside the scope instead of stalling
the desktop:

```sh
systemd-run --user --scope -p MemoryHigh=18G -p MemoryMax=20G ./gradlew testNqp
```

## How the bootstrap is modeled

Each stage registers, per target, a `GenCatTask` (concatenation +
`#?if jvm` / `#?if stageN` preprocessing) and a `JavaExec` running the
previous stage's compiler through the unit loader (`-cp <stageDir>:<engine
jar>` + `-Xbootclasspath/a:<stageDir>:<runtime>:<3rdparty>
org.raku.nqp.runtime.unit.UnitMain <stageDir>/nqp.jar --bootstrap ...`).
Stage 1 uses the committed `src/vm/jvm/stage0` jars — themselves unit
artifacts, not class files — and passes `--stable-sc=stage1`; stage 2 uses
the stage 1 output. `NQPP5QRegex.jar` is compiled afterwards with the
finished runner, matching `make`. The exact flags were captured from a
`make -n j-all` transcript (the entry point changed from `nqp` to
`UnitMain` once the compiler stopped emitting class files).

`jvmconfig.properties` exists in two variants, as in the Makefile build: an
install-prefix variant baked into `nqp-runtime.jar`, and a build-tree
variant in `build/jvm/share/lib/` which shadows the baked one via
bootclasspath ordering (lib dir precedes the jar).

## Cutover / coexistence notes

- The Makefile path stays authoritative until CI is migrated. Parity was
  verified by byte-comparing every `.class` in `nqp-runtime.jar` and by
  matching `prove` results. Note that the stage jars themselves can never be
  compared byte-wise between builds: `HLL::Backend::JVM.classname`
  (src/vm/jvm/HLL/Backend.nqp:52) derives each unit's class name from
  `sha1(sha1($source) ~ nqp::time() ~ $count)`, so class names differ on
  every build, including two consecutive `make` builds.
- Rakudo's JVM configure consumes `nqp-j --show-config` and the
  `gen/jvm/share/{runtime,lib}` layout. Point it at the Gradle output only
  after installing (`installJvm`, TBD) or copying `build/jvm/share` over
  `gen/jvm/share`.
- Gradle 9.7 runs directly on the default JDK 25 (the earlier
  `org.gradle.java.home` pin to JDK 21, needed by Gradle 8.14, is gone);
  the compile toolchain, stage launchers and runner also use JDK 25.
- The Windows runner template (`nqp-j.bat`) has no Gradle equivalent yet.

## Modernization (2026-08)

The JVM backend was modernized off its 2012-era pins:

- **ASM 4.1 → 9.10.1** (Maven Central + vendored copies in `3rdparty/asm/`,
  paths updated in `tools/lib/NQP/Config/NQP.pm`). Two behavioral fixes were
  required at the time, both in code that no longer exists (the JAST
  compiler and its autosplitter went with the class road in 2026-09):
  `JASTCompiler.processType` parsed the JAST type language
  ("Long", "Byte", "[Byte", …) explicitly — ASM 4 accepted those names only
  because it inspected just the leading character — and the
  autosplit-on-oversized-method retry matched the typed
  `MethodTooLargeException` instead of ASM 4's exception message string.
  ASM itself stays, for `P6Opaque`'s generated attribute-storage classes
  and the Java-interop adaptors.
- **`javac --release 9` → `--release 25`**; Kotlin `jvmTarget` 25.
- **Emitted bytecode V1_7 → V25**, centralized in `BytecodeVersion`
  (2026-08 it lived in `org.raku.nqp.jast2bc`; since the class road went it
  is `org.raku.nqp.runtime.BytecodeVersion`, and its only readers are
  `BootJavaInterop` and the P6Opaque/C-struct REPRs).
- **`sun.misc.Unsafe` eliminated from NQP's runtime**: P6Opaque atomic
  attribute ops use `VarHandle`; the obsolete `Ops.disableWarning` hack
  (targeting a class removed in JDK 17) was deleted. Remaining Unsafe noise
  at runtime comes from lz4-java (third-party; future dep upgrade).
  `ThreadDeath` (deprecated-for-removal) is still used for exit unwinding —
  replacing it needs a designed exit protocol across generated mainlines and
  EvalServer; deferred.
- **stage0 regenerated (2026-08)** as Java 25 class files (`./gradlew
  jBootstrapFiles`), which set the JDK 25+ floor for building or running the
  JVM backend; superseded 2026-09 by the unit-artifact regeneration below —
  the JDK 25+ floor stands, but stage0 is no longer class files.
- **stage0 regenerated 2026-09 as unit artifacts** (`./gradlew
  jBootstrapFiles` after the JAST-free driver landed). Rule: a change that
  an OLD stage0 could not read (an incompatible wire change, a meta format
  bump, a syscall shape change) is preceded by a regeneration from the LAST
  compiler that still speaks the old shape; additive wire changes need
  none.
