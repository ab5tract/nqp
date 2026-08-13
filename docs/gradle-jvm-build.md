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

## How the bootstrap is modeled

Each stage registers, per target, a `GenCatTask` (concatenation +
`#?if jvm` / `#?if stageN` preprocessing) and a `JavaExec` running the
previous stage's compiler (`-cp <stageDir>` +
`-Xbootclasspath/a:<stageDir>:<runtime>:<3rdparty>:<stageDir>/nqp.jar nqp
--bootstrap ...`). Stage 1 uses the committed `src/vm/jvm/stage0` jars and
passes `--stable-sc=stage1`; stage 2 uses the stage 1 output.
`NQPP5QRegex.jar` is compiled afterwards with the finished runner, matching
`make`. The exact flags were captured from a `make -n j-all` transcript.

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
- Gradle needs a Java 21 JVM (Gradle 8.14 cannot run on Java 25;
  see `org.gradle.java.home` in `gradle.properties`). Compilation targets
  `--release 9` either way, identical to the Makefile.
- The Windows runner template (`nqp-j.bat`) has no Gradle equivalent yet.
