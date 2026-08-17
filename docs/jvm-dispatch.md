# The dispatch mechanism on the JVM backend

This describes the port of MoarVM's `newdisp` dispatch mechanism (`src/disp/`
in MoarVM) to the JVM backend. The user-facing surface is the same as MoarVM's,
so `nqp::dispatch`, `nqp::register`, `nqp::delegate`, `nqp::track`,
`nqp::guard` and `nqp::syscall('dispatcher-*')` mean the same things here, and
`t/jvm/11-dispatch.t` is a copy of `t/moar/53-dispatch.t`.

The code lives in `src/vm/jvm/runtime/org/raku/nqp/dispatch/`.

## What a dispatch is

A dispatch is a callsite that hands its arguments to a *dispatcher*: a callback
that inspects them and says what should happen — produce a value, invoke
something, or hand over to another dispatcher. While it does that, the runtime
records what the dispatcher looked at and what it relied upon. The result is a
*dispatch program*: a set of guards plus an outcome, cached at the callsite. As
long as the guards hold, later dispatches skip the dispatcher entirely.

That much is exactly MoarVM's design, and the observable behaviour (including
how many times a dispatch or resume callback runs) is meant to match.

## How the port differs from MoarVM

MoarVM compiles a recording into a linear program: an array of opcodes over a
bank of temporaries, with separate opcodes for guarding an argument and
guarding a temporary, for loading an attribute into a temporary, and so on
(`MVMDispProgramOpcode` has some 60 members). That shape exists because the
program is also an input to the specializer, which translates it into
optimized bytecode.

There is no specializer here, so the program does not need to be flat. Instead:

- **`ValueSource`** (sealed) describes *where a value comes from* as a tree:
  `Arg(index)`, `ResumeInitArg(level, index)`, `Literal(kind, value)`,
  `Attribute(from, classHandle, name, kind)`, `How(from)`, `Unbox(from, kind)`,
  `Lookup(table, key)`, `ResumeState(level)`. Each knows how to evaluate
  itself against a `DispatchContext`, so there are no load opcodes and no
  temporaries at all.
- **`Guard`** (sealed) is a condition on a `ValueSource`: `OfType`,
  `Concreteness`, `Literal`, `NotLiteralObj`, `OfHll`. One guard type covers
  both of MoarVM's argument/temporary variants, because a `ValueSource` can be
  either.
- **`Outcome`** (sealed) is `Value`, `InvokeCode` or `InvokeSyscall`.
- **`CaptureShape`** is a `List<ValueSource>` plus a `CallSiteDescriptor`. A
  capture derived by a dispatcher (`dispatcher-drop-arg` and friends) is a
  list operation on the shape. MoarVM instead keeps a tree of capture
  derivations and walks it to translate an argument index back through every
  insert and drop that happened on the way (`MVM_disp_program_record_track_arg`
  is most of that work); here the translated answer is what the shape already
  holds.

Two more places where Kotlin does the bookkeeping for free:

- MoarVM deduplicates the values a recording is interested in by scanning a
  values table for an existing entry with matching fields (`value_index_*`,
  eight near-identical functions). The `ValueSource` variants are data classes,
  so structural equality *is* that deduplication: one `HashMap<ValueSource,
  TrackedInstance>` replaces the table and the scans.
- Guards are collected per value in a `LinkedHashMap`, so they come out in the
  order the values were first tracked. That ordering matters: tracking an
  attribute forces type and concreteness guards on the object it was read
  from, so by the time an `Attribute` guard is checked, the guards that make
  the read safe have already passed.

## Callsites and the inline cache

Each dispatch instruction compiles to an `invokedynamic` whose bootstrap
(`DispatchBootstrap.dispatch_noa`) creates a `DispatchCallSite`. That object
*is* the inline cache: it holds the programs recorded there, tried in order.
Using the indy callsite means no separate cache table and no per-site index to
allocate; the JVM already gives every indy instruction its own `CallSite`.

Programs accumulate up to `Dispatch.MAX_PROGRAMS`. Past that the callsite is
megamorphic and nothing further is installed — dispatches still work, by
recording each time. A dispatcher can see this coming with
`dispatcher-inline-cache-size` and arrange something better, which is what
nqp's and Rakudo's method dispatchers do.

The compiled program is not used for the dispatch that produced it: the
recording's own outcome is carried out directly, as on MoarVM. The outcome is
expressed in `ValueSource`s either way, so the same `realize` path serves both.

## Resumption

A dispatch can be resumed: `nqp::dispatch('boot-resume')` inside something a
dispatch invoked goes back to the dispatcher that invoked it and asks for the
next thing (this is how `callsame` and method deferral work).

MoarVM finds the dispatch to resume by walking its callstack, which has frames
and dispatch records interleaved on it. The JVM has no such unified stack, so
the interleaving is reconstructed: `ThreadContext.dispatchRecords` holds the
dispatches in progress, and `CallFrame.dispatchRecord` says which dispatch
invoked a frame, if one did. Walking the caller chain and reading that field at
each step visits frames and dispatches in the same order MoarVM's callstack
iterator does — see `Dispatch.findResumption`, which follows
`MVM_disp_resume_find_topmost`/`find_caller` including the `exhausted` count
and the rule about not looking past a dispatch that had resumptions of its own
that were not used.

A record stays in `dispatchRecords` while whatever the dispatch invoked is
running (a `finally` pops it), which gives it the same lifetime it has on
MoarVM's callstack.

Resumptions of one dispatch are stored innermost-first, which is the reverse of
the order the dispatchers registered them in, grouped by the resumption level
that was current at the time. MoarVM does this with the counting-down loop in
`emit_resume_inits`; getting it wrong is invisible until a dispatch has two
resumptions and one falls back to the other.

## Not done yet

- `nqp::assertparamcheck` does not exist on this backend, so
  `dispatcher-resume-on-bind-failure` and `dispatcher-resume-after-bind` are
  recorded onto the program but nothing raises them.
- The language-sensitive dispatchers are in place, but nqp's own dispatcher
  set (`src/core/dispatchers.nqp`) is still `#?if moar`, so nothing registers
  `nqp-call`, `nqp-meth-call` and friends here yet.
- `boot-boolify` and the boolification syscalls are not implemented.
- Guards are checked by walking a list. A natural next step on this backend is
  to compile them into a `MethodHandles.guardWithTest` chain and set that as
  the callsite target, which is what the indy callsite is there for.
