# The Truffle grammar engine

A rule the engine covers is flattened to a *descriptor* at compile time and
matched by a Truffle program at run time; no bytecode matcher is emitted for
it at all. Anything the engine does not cover keeps the bytecode path, so
coverage can grow one rxtype at a time.

This document is the operational half: the things that are not visible in the
diff and that cost a wrong turn to rediscover.

## The one rule that explains most surprises

**A descriptor has no bytecode matcher behind it.** The choice is made when
the rule is compiled, so:

* `NQP_JVM_NO_TRUFFLE=1` must be set for the **compile and the run alike**.
  It decides whether descriptors are emitted at all. A tree compiled with
  descriptors cannot run without the engine, and says so rather than failing
  quietly.
* Any JVM that loads `nqp.jar` needs Truffle, not just `nqp-j`. Rakudo has
  four such places; see below.

## Layout, and why it is that shape

    -Xbootclasspath/a:  nqp-runtime.jar + third-party jars   (unchanged)
    --module-path       share/truffle/*.jar
    --add-modules       org.graalvm.truffle,org.graalvm.truffle.runtime
    -cp                 share/lib : share/runtime/nqp-truffle.jar

Truffle binds to the JDK's compiler only when its jars resolve as **modules**;
on the class path it silently falls back to an interpreter that does no
partial evaluation and measures as "Truffle is slow". The engine jar goes on
the **class** path, because the boot loader cannot see the module path.

That split is why the runtime cannot simply call the engine: boot-loader code
cannot name an application-loader class. It goes through `GrammarEngine`, an
interface declared in nqp-runtime (so both loaders resolve the same one) and
found by name via `Class.forName` on the system loader.

`Truffle.getRuntime().getName()` must answer `Oracle GraalVM`. If it answers
`Interpreted`, the modules did not resolve. Artifact versions must match the
JDK's GraalVM version exactly (25.2.4 here, not the nominal 25.0.0).

## Diagnosing a mis-parse

A wrongly encoded rule does not fail; it parses a **different language**, and
the symptom appears a long way from the cause. In that situation:

**Reach for the trace before bisecting.** `NQP_RX_TRACE=1` is a *run* time
switch and the descriptors are already in the class files, so a hypothesis
costs one run rather than a 2.5-minute bootstrap.

    rx> circumfix:sym<{ }>    0      { 1 }
    rx< circumfix:sym<{ }>    0..-3  FAIL

Entry and exit are traced separately because a rule can leave **without
returning**: a `<.panic: ...>` subrule throws and unwinds straight through.
An `rx>` with no matching `rx<` is the rule that threw — and reading a missing
line as "the engine was not involved" is the wrong conclusion, which has been
drawn here before. Anonymous rules print `<anon %08x>` of the descriptor's
`hashCode`; match one to a rule by hashing the `rxd ` strings out of the jars
(Java's `h = h*31 + ch`).

Compile-time knobs, in `QAST::Compiler.rx_descriptor`:

    NQP_RX_ENCODED=1     print each rule the engine takes over
    NQP_RX_BAIL=1        print why a rule was refused
    NQP_RX_SKIP=a,b      refuse these by rule name
    NQP_RX_SKIP_ANON=1   refuse rules that do not reduce
    NQP_RX_ONLY=a,b      refuse everything else

A name matching nothing (`NQP_RX_ONLY=__none__`) turns the engine off
entirely. **Verify that case is green before trusting any bisection** — a
restricted build that happens to exclude the culprit looks like a clean
result.

## Building

Use the Gradle task `buildJvm`, not `:generateRunner`. `syncLib` deliberately
preserves `NQPP5QRegex.jar`, which only its own task rebuilds, so a
`generateRunner`-only rebuild leaves it out of step with a freshly built
`NQPHLL` and it dies with

    Missing or wrong version of dependency '.../stage2/NQPHLL.nqp'

Clear `build/jvm/stage1` and `build/jvm/stage2` first; a half-refreshed pair
fails the same way.

## Downstream (rakudo)

nqp publishes `runtime.truffle.{modulepath,addmodules,engine}` in
`jvmconfig.properties`. Rakudo reads them and threads them into **four**
places — miss one and the build dies rather than running slowly:

1. `tools/templates/jvm/rakudo-j-build.in` — the build-time runner
2. `tools/build/create-jvm-runner.pl` — `rakudo-j`, `perl6-j`, jdb/eval servers
3. `tools/templates/jvm/Makefile.in`, `J_NQP_RR` — a raw `java` invocation
   that deliberately bypasses the nqp runner, and is what compiles
   `rakudo.jar` and all three BOOTSTRAP jars. This is the one that actually
   breaks the build, and the easiest to overlook.
4. nqp's own runner (`nqp-j`, `nqp-j-gradle`)

An nqp built without the engine reports nothing, every flag stays empty, and
the behaviour is exactly what it was.

The runners also pass `--enable-native-access=org.graalvm.truffle` (Truffle
is a *named* module, so `ALL-UNNAMED` does not reach it) and
`--sun-misc-unsafe-memory-access=allow` (truffle-runtime's own call to a
terminally deprecated method). That is not cosmetic: those warnings land on
stderr ahead of the compiler's own output and broke `t/nqp/114-pod-panic.t`,
which asserts on the first line of it.

## Semantics that must match NQP exactly

Each of these produced a wrong parse rather than an error:

* **`scan` is not a wrapper.** It is a zero-child *sibling* at the start of a
  rule body, it only scans when the **invocant's** `$!from` is -1 (a
  top-level parse, never a subrule), and each retry rebinds `$!from` on the
  new cursor. Because that updates a cursor attribute it cannot live in the
  program; it is a property of the rule, looped around it.
* **Attribute lookup needs the declaring class**, `$?CLASS` from
  `!cursor_start_all`, not the cursor's `WHAT` — a subclass for any real
  grammar, which answers "No such attribute '$!pos'".
* **A named `alt` is longest-token-match**, ordered by an NFA. The engine
  asks the cursor (`!alt`) rather than owning the NFA, and the branch indices
  it passes must be in **source** order.
* **Quantifiers in a `token`/`rule` are ratcheted.** `\d+\d` FAILS on `123`,
  where an ordinary greedy quantifier matches.
* **A zero-width character class must not consume.** `<?[{]>` encoded as
  consuming made `circumfix:sym<{ }>` call `pblock` one character late.
  A *negated* zero-width class succeeds at end of string, having nothing to
  exclude.
* **`!cursor_pass` takes the rule's name**, and that is what makes it reduce.
  Without it a rule matches the right span and builds nothing.

## Status

The nqp suite is at parity with the bytecode baseline: 142 files, 13110
tests, one pre-existing `t/p5regex` failure (test 78) that fails identically
with `NQP_JVM_NO_TRUFFLE=1`. `make j-all` builds a working `rakudo-j`.

## Measured against the thing it replaces

The engine had only ever been compared to `java.util.regex`, which is not
what it replaces. Here it is against the bytecode path: same machine, same
sources, CORE.c compiled by the **RakuAST** front end, which is what this
work targets (the legacy front end is being cut). Engine-off is a full
rebuild of nqp *and* rakudo under `NQP_JVM_NO_TRUFFLE=1`, since descriptors
are a compile-time decision.

CORE.c, seconds:

| stage     | engine off | engine on |   delta |
|-----------|-----------:|----------:|--------:|
| parse     |    161.158 |   165.981 | **+3.0%** |
| optimize  |     43.245 |    44.990 |   +4.0% |
| qast      |     11.920 |    12.432 |   +4.3% |
| jast      |     41.697 |    42.816 |   +2.7% |
| classfile |     49.138 |    49.641 |   +1.0% |
| **total** |    **307.2** | **316.4** | **+3.0%** |

**The engine is currently ~3% SLOWER than the bytecode path it replaces.**

Note *where* the cost lands. If the engine were merely a slower matcher, only
`parse` would move; instead every stage is up by roughly the same proportion,
including stages that run no regexes at all. That points at the Truffle
runtime itself — its background compilation threads competing with the
compiler's own work — rather than at matching being slow. Worth confirming
with `engine.TraceCompilation` and a pinned compiler thread count before
optimising anything else.

The same shape appears on the legacy front end (CORE.c parse 124.702 off vs
126.936 on, +1.8%; CORE.d +1.7%; CORE.e +1.1%), so the direction is
consistent across five measurements. These are single runs, so treat the
magnitudes as approximate — but not the sign.

Worth trying, in the order I would bet on them:

1. **Truffle's compiler threads.** See above; the cost is spread across
   stages that do no matching. Cheapest thing to test, and if it is the whole
   story the matching itself may already be at parity or better.
2. **The subrule boundary.** Every `<foo>` leaves the engine, invokes an NQP
   CodeRef and returns; partial evaluation stops dead there. A grammar is
   mostly subrule calls, so the compiled region between two of them is small.
   This is the same boundary that makes replacing newdisp the *tail* of
   moving code generation to Truffle rather than the head.
3. **Coverage.** About 57 rules are on the engine and the rest of the grammar
   is still bytecode, so the crossing cost is paid without whole-grammar
   speedup.
4. **`altOrder` allocates** a fresh marks array per named-alt entry and reads
   back through the bstack, where the bytecode path pushes onto a stack it
   already owns.

Not yet done: rakudo's own test suite has not been run under the engine. And
a grammar used once may never get hot enough to be compiled — a setting
compile is the *favourable* case — so any cutover still needs a warmup story.
