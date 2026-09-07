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

* (Historical: the `NQP_JVM_NO_TRUFFLE=1` bytecode-regex fallback was removed on 2026-09-07; the engine is the only regex path.)
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

## Status: feature-complete, sweep-green (2026-08-29)

Every bytecode grammar-engine piece is ported: qastnode, subrule calls of
every shape (named, lexical, computed arguments), backtrackable rules with
full cursor re-entry (`!cursor_next`, exhaustive matching at bytecode
parity, backtracking INTO subrules), dynamically-bounded quantifiers,
conjunctions, ignoremark literals, uniprop pairs, computed pass names, and
rules of any piece count via the grouped callback dispatch. The remaining
bytecode rules are guards, not gaps: pieces that walk the caller chain,
pieces over optimizer-lowered locals, and shapes the bytecode path cannot
compile either. First fully green sweep: 377 files across twelve t/
suites, 35/35 chunks, zero failures -- including the CRLF files that were
the standing baseline red.

## What the engine covers, and how to find out what to do next

Coverage grows one rxtype at a time, and the honest measure of it is how many
whole **rules** the engine takes over — not how many rxtypes are implemented.
A rule moves only when *every* construct in it can be encoded, so a rxtype
that appears everywhere can still be worth nothing on its own, and the first
reason a rule reports says almost nothing about what to implement.

Two knobs answer that properly:

    NQP_RX_SURVEY=1   one line per rule, with EVERY reason it was refused
    NQP_RX_NO=a,b     refuse these features, to measure what they are worth
    NQP_RX_TRY=a,b    turn ON a feature that is written but known broken

    rx survey: statement OK
    rx survey: term:sym<...> BAIL rxtype qastnode;subrule with computed arguments

`NQP_RX_NO` takes feature names (`anchor-const`, `quant-sep`, `uniprop`,
`subrule-args`, `goal`), and what it is for is subtraction: build once with everything, then measure with
a group refused, and the difference is what that group actually bought.
Compiling rakudo's Raku grammar takes about fifteen seconds against two and a
half minutes to rebuild nqp, so every combination is a run rather than a
build:

    nqp/nqp-j-gradle tools/build/gen-cat.nqp jvm src/Raku/Grammar.nqp > /tmp/g.nqp
    NQP_RX_SURVEY=1 NQP_RX_NO=quant-sep nqp/nqp-j-gradle --module-path=blib \
        --target=jar --output=/tmp/g.jar /tmp/g.nqp

Beware: rakudo's `blib` records the nqp module versions it was built against,
so it has to be rebuilt after every nqp rebuild or the compile dies with
"Missing or wrong version of dependency". `make blib/Raku/Grammar.jar` after
deleting the module jars is enough; it does not need the settings.

Encoded: concat, alt (ordered and longest-token), literal, cclass,
enumcharlist, charrange, anchor — including the constant `pass` and `fail` —
quant, with or without a separator, uniprop, subrule — including calls
carrying literal arguments — subcapture, scan, pass, dba, ws, and goal (`~`),
which is rewritten exactly as `QAST::Compiler.goal` rewrites it rather than
compiled separately.

Not encoded: qastnode (`{ ... }`, `<?{ ... }>`), which is arbitrary NQP code
compiled into the matcher's own frame and is by far the largest remaining
group — there is an attempt at it, and it does not work; see below. Then
subrule calls whose arguments are *computed*, rules that can be resumed
(`regex` rather than `token`/`rule`), which the engine cannot express at all,
subrule calls through a variable, dynquant, conj, the `<:Block("...")>` form
of uniprop, which smartmatches a value rather than testing one, and
ignoremark literals.

Measured on rakudo's Raku grammar, which has 1105 rules:

| group                                  | rules |
|----------------------------------------|------:|
| on the engine before                   |   655 |
| constant anchors (`pass`, `fail`)      |   +24 |
| quantifier separators (`a+ % ','`)     |    +5 |
| Unicode properties (`<:Alpha>`)        |    +2 |
| subrule calls with literal arguments   |   +79 |
| goal (`~`), which waits on those       |    +7 |
| **on the engine now**                  | **773** |

Refusing all five reproduces 655 exactly. That is the check worth keeping: an
apparatus that cannot reproduce the number it started from is not measuring
what it claims to.

What the next groups are worth, by the same subtraction:

| group                                  | rules |
|----------------------------------------|------:|
| qastnode                               |   +93 |
| subrule calls with computed arguments  |   +83 |
| resumable (`regex`) rules              |   +35 |
| subrule calls through a variable       |    +7 |

## Coming back for the rule's own code — working since 2026-08-28

`{ ... }`, `<?{ ... }>` and `:my $x := ...` are arbitrary NQP, and the
bytecode path compiles them straight into the matcher's frame where they can
read the rule's lexicals. `qastnode` was the largest thing the engine did not
cover: 203 of the 1105 rules in rakudo's Raku grammar mention it, and 93 were
blocked by nothing else. It is on by default now (`NQP_RX_NO=qastnode`
refuses it); the two wrong-parse causes and their fixes are described at the
end of this section.

The attempt here does not move the code — it **comes back for it**:

* the encoder collects each `qastnode` body and emits only an *index*;
* `QAST::Compiler.engine_jast` wraps the bodies in one nested block that
  switches on that index, and hands the rule's class the **static** code
  object for it (a `QAST::BVal`, so a rule that never reaches a callback pays
  one constant load and no allocation);
* `NqpCursor.callbackHolds` closes it over the frame *at the moment a
  callback fires* — `Ops.takeclosure(callback, tc)`. That works because
  `rxmatch` is a static call and pushes no frame of its own, so `tc.curFrame`
  is still the rule's own frame.

**The wrong parse had two causes, both fixed (nqp 257968d1d):**

* **Captures.** A `{ ... }` reads the captures made so far through `$/`,
  which `$¢.MATCH` builds from the cursor's capture stack — and the engine
  held captures pending until the match ended, so mid-rule code saw none
  (`package_def` reached `install_package_symbol` with an NQPMu where a
  capture should be). The engine now syncs pending captures to the cursor
  before a callback runs, takes them back (cstack plus the bstack frames
  `!cursor_capture` grew) when it backtracks past them, and the final flush
  hands over only the remainder.
* **Frame-counting ops.** A piece runs one frame deeper than the inline
  code it replaces, and ops that COUNT frames shift meaning by exactly that
  frame: `nqp::getlexdyn` starts at the caller by design, so nibbler's
  `:my $OLDRX := nqp::getlexdyn('%*RX')` reached past the rule's own fresh
  `%*RX` when inline and read back the rule's own empty one from the
  closure — every `<sym>` downstream resolved against nothing. A piece
  containing a frame- or caller-walking op refuses the rule
  (`reads_frame_ops`, the same op set rakudo's var-lowering poisons on,
  memoized per node identity because QAST shares subtrees and a naive walk
  ballooned BOOTSTRAP's compile by gigabytes).

The earlier depth-indexing constraint holds and is what the design already
satisfies: ordinary lexical and contextual access from a piece is compiled
against the real nesting, and `takeclosure` at the moment a callback fires
gives the frame chain the matching extra level. What it cannot fix is the
runtime frame-walkers above, which is why they refuse rather than encode.

Two structural limits stay:

* `NQP::Optimizer` turns a lexical that no *inner block* uses into a local,
  and cannot know about a block the backend invents afterwards. A local
  lives in one frame and cannot be named from another, so a rule whose code
  touches one is refused outright (`qastnode over a lowered local`).
* PROVISIONAL: a rule with more than 16 pieces stays on the bytecode path —
  the callback dispatch compiles into one generated method, and rakudo's
  `comp_unit` (sixty-odd `:my` pieces) broke its emission ("JAST node isn't
  a JAST::Class", the 64KB method limit is the suspect). Once-per-parse
  rules gain nothing from the engine; split the dispatch across methods if
  a hot rule ever hits the cap.

## Why the engine is Kotlin

The node set is hand-written: `RxVmNode` is one interpreter loop over a
`@CompilationFinal` program, and the specialization that matters — the
program becoming a constant — comes from that annotation rather than from
Truffle's `@Specialization` DSL. The DSL is an annotation processor that
*generates a Java subclass* of your node, which would mean kapt on every
build and `allopen` to defeat Kotlin's final-by-default, to buy a mechanical
generation of specializations this engine does not have.

So the engine proper is Kotlin — nodes, program, descriptor, wire format,
cursor, and the pattern parser the harnesses use — next to the Kotlin runtime
it calls into. `RxLanguage` stays Java because
`@TruffleLanguage.Registration` and `@ExportLibrary` genuinely are
annotation-processed: polyglot finds a language through a generated provider.
The three standalone `main()` harnesses (`RxCheck`, `RxBench`,
`RxDescriptorCheck`) stay Java as well, for no better reason than that they
are test scaffolding and moving them would risk the tests to gain nothing.

Gradle handles the mix (Kotlin compiles first, Java against its output), but
note the harness tasks need `kotlin-stdlib` on their classpath explicitly —
it cannot come from `runtimeClasspath` wholesale, because that also carries
the Truffle jars, which have to resolve as *modules*. In a real run the
stdlib is already on nqp's boot classpath next to `nqp-runtime`.

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

773 of the 1105 rules in rakudo's Raku grammar are on the engine, and the
CORE.c compile is at parity with the bytecode path — see the two sections
below for how that is measured and what is left.

**Rakudo's own suite has now been run under the engine**, which it had not
been before: `RAKUDO_RAKUAST=1 make j-test`, 377 files, 4231 tests, three
files failing and all three pre-existing and unrelated to matching —
`regex-crlf-grapheme.t` and `regex-vspace-class-crlf.t` want CR+LF to fuse
into one grapheme and this backend has no NFG, and
`22-traited-variable-by-name.t` is about LEAVE phasers not receiving their
value. That is the branch's documented baseline exactly: one known failure
and two excluded NFG files. Clear `lib/.precomp`, `t/**/.precomp` and
`~/.raku/precomp` before the run or it reports failures that are not there.

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

### Coverage was the hypothesis, and it held

Hypothesis 3 above was that the engine holds too little of the grammar to pay
for the crossings. It has now been tested by raising coverage from 655 rules
to 773, and it is right. Same measurement, RakuAST front end:

| stage     | 655 rules | 773 rules | engine off | 773 vs off |
|-----------|----------:|----------:|-----------:|-----------:|
| parse     |   165.981 |   160.973 |    161.158 | **−0.1%** |
| optimize  |    44.990 |    43.330 |     43.245 |     +0.2% |
| qast      |    12.432 |    13.285 |     11.920 |    +11.5% |
| jast      |    42.816 |    42.528 |     41.697 |     +2.0% |
| classfile |    49.641 |    48.927 |     49.138 |     −0.4% |
| **total** | **316.4** | **309.0** |  **307.2** | **+0.6%** |

**The engine is now at parity with the bytecode path it replaces** — +0.6%
overall, and parse itself, the stage that actually runs the regexes, is
within noise of engine-off. The 3% it was down has been recovered by covering
more of the grammar, which is what hypothesis 3 predicted.

Do not measure this on the legacy front end. The same coverage change
measured there looks like a 3.3% *regression* (parse 126.936 → 131.129),
which is the opposite conclusion; the legacy front end is being cut and its
numbers are not worth acting on.

What is left is +11.5% on `qast` and +2.0% on `jast` — stages that run **no
regexes at all**. Whatever that is, it is not matching, and it is now the
whole of the remaining overhead.

Worth trying, in the order I would now bet on them:

1. **Truffle's compiler threads.** The remaining cost is concentrated in
   stages that do no matching, which is exactly the shape of background
   compilation competing with the compiler's own work. Cheapest to test, with
   `engine.TraceCompilation` and a pinned compiler thread count.
2. **The per-match entry cost, which is avoidable bookkeeping.** Every match
   does a `ConcurrentHashMap` lookup keyed by the descriptor *string* to find
   its compiled program, and allocates a fresh `NqpCursor`; every subrule
   call does `Ops.findmethod` by name plus a fresh argument array, where the
   bytecode path has an `invokedynamic` site with a guard chain. The program
   lookup should not be a lookup at all: `JAST::Class` takes fields, so the
   rule's own class can hold the compiled program in a static.
3. **`altOrder` allocates** a fresh marks array per named-alt entry — a real
   `BOOTIntArray` SixModelObject, filled one `push_native` at a time — and
   reads its answer back through the cursor's bstack, where the bytecode path
   pushes onto a stack it already owns.
4. **The subrule boundary.** Every `<foo>` leaves the engine, invokes an NQP
   CodeRef and returns; partial evaluation stops dead there. A grammar is
   mostly subrule calls, so the compiled region between two of them is small.
   This is the same boundary that makes replacing newdisp the *tail* of
   moving code generation to Truffle rather than the head.

Still open: a grammar used once may never get hot enough to be compiled — a
setting compile is the *favourable* case — so any cutover needs a warmup
story. Rakudo's own suite is no longer on this list; see Status.
