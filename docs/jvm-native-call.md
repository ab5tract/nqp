# Native calls on the JVM backend

The JVM backend implements NQP's native call ops on `java.lang.foreign`,
the Panama FFI that has been a final Java API since JDK 22. It replaced
JNA in 2026; nothing in the build depends on `com.sun.jna` any more.

The ops themselves are unchanged, and are mapped in
`src/vm/jvm/QAST/Compiler.nqp` onto `org.raku.nqp.runtime.NativeCallOps`:
`initnativecall`, `buildnativecall`, `nativecall`, `nativecallinvoke`,
`nativecallrefresh`, `nativecallsizeof`, `nativecallcast` and
`nativecallglobal`. Those eight method descriptors are ABI: emitted
bytecode calls them by name and signature.

## The pieces

`runtime/NativeSupport.kt` holds the platform plumbing: the C type sizes
this ABI uses (`long` and `size_t` come from the linker's canonical
layouts, so they are four bytes on Windows and eight on LP64), off-heap
allocation, raw-address wrapping, C string encoding, and symbol lookup.

`runtime/NativeCallOps.kt` builds one downcall `MethodHandle` per call
site when `buildnativecall` runs, and invokes it in `nativecall`. It also
marshals values in both directions and builds the upcall stubs that let C
call back into NQP.

The C aggregate representations — CStruct, CPPStruct and CUnion — share
`sixmodel/reprs/CTypeREPR.kt` (layout) and `CTypeInstance.kt` (member
access), with `CAttrInfo` and `CTypeREPRData` holding what composition
worked out. CArray, CPointer and CStr keep a `MemorySegment` where they
used to keep a JNA `Pointer` or `Memory`.

## Things worth knowing

**Everything aggregate is passed by reference.** Structs, unions, arrays
and pointers all cross as `ADDRESS`; nothing is passed or returned by
value. This is what JNA did (a `Structure` argument is a pointer unless it
implements `Structure.ByValue`, which nothing here does), so no function
descriptor ever mentions a struct layout — only primitives and addresses.

**Layout is computed here, not by a library.** `CTypeREPR.computeLayout`
aligns each member to its own natural alignment, pads between members, and
rounds the total up to the widest member's alignment; a union gives every
member offset zero and takes the width of the widest. That is the standard
C rule and matches what JNA derived from the Java field types of the
`Structure` subclass it used to generate — the three ASM class generators
are gone. `t/jvm/08-cstruct.t` covers the arithmetic.

**Off-heap lifetime is the garbage collector's.** `NativeSupport.allocate`
allocates from a fresh `Arena.ofAuto()`, so a block is freed once nothing
can reach it, which is the contract the rest of the runtime was written
against. The corollary is that anything holding only a C *address* to a
block has to keep the segment reachable itself: `CTypeInstance` pins the C
strings it writes into members, and `CArrayInstance` pins the ones it
writes into elements.

**Only foreign addresses get reinterpreted.** `NativeSupport.unbounded`
widens a zero-length segment — which is all a foreign call hands back —
to unbounded access, and leaves any segment that already knows its extent
alone. Reinterpreting one of our own segments would detach it from the
arena keeping it alive.

**Variadic functions are not meaningfully supported, and were not before
this change either.** Rakudo has two routes into `buildnativecall`.
`NativeCall::Dispatcher` builds argument information per call, knows which
arguments are the variadic ones and carries a variadic-rw bitfield; it is
reached through `CUSTOM-DISPATCHER`, and `lib/NativeCall.rakumod` only
loads it when the compiler `supports-op('dispatch_v')`. The JVM backend
does not, so it always takes the older `!setup()` route instead. That
route builds the argument list with `param_list_for`, which *drops* the
`**@varargs` slurpy and records nothing but a boolean `variadic` key on
the return hash.

The consequence is not an ABI subtlety: when a variadic sub is called,
`arg_types` describes the declared parameters only while `arguments`
carries the whole call, so the two disagree in length — which is what the
`/* TODO: Make sure n == call.arg_types.length? */` in `call` has always
been worried about — a mismatch neither implementation of the op could
have coped with, since both index the argument types by the argument
position. Rakudo's `t/04-nativecall/26-varargs.t` dies before its first
test here. Making this work needs the fixed-parameter count to reach
`buildnativecall`; given that, `Linker.Option.firstVariadicArg` would say
the rest, and the ABIs that distinguish variadic from named arguments
would be handled properly rather than by accident.

**`entry_point` is assigned last, and has to be.** Rakudo decides a call
site is already built with `self!setup unless nqp::unbox_i($!call)`, and
that guard sits *outside* the lock `!setup` takes; `unbox_i` on a
NativeCall object reports whether `entry_point` is set. So a thread that
sees a non-null entry point goes straight to calling, and everything else
`call` needs — the argument types, the return type, the downcall handle —
must already be in place by then. Filling the handle in after the entry
point makes `t/04-nativecall/20-concurrent.t` die with a
NullPointerException.

**Restricted methods need `--enable-native-access`.** Linking a downcall,
opening a library and reinterpreting a segment are all restricted. Both
launch points pass `--enable-native-access=ALL-UNNAMED`, which covers the
runtime even though it is loaded from the boot classpath.

## Finding libraries

`dlopen` searches the platform's library path and nothing else, so
`NativeSupport.libraryCandidates` walks the extra places JNA used to look:
a bare `foo` is also tried as `System.mapLibraryName("foo")`, and a
relative name is tried under each directory in `nqp.library.path` and
`java.library.path`. Rakudo's runner sets `nqp.library.path` to the
install's share directory, where it used to set `jna.library.path`.

An empty library name means the process itself. That resolves through
`dlsym(RTLD_DEFAULT, …)` where `dlsym` is available, so symbols from
libraries an earlier call pulled in are visible, exactly as they were
through JNA's process handle; the linker's own default lookup (the C and
math libraries) is the fallback, and the whole story on Windows.

## Testing

`t/nativecall/02-libc.t` calls the C library directly — integer and string
arguments and returns, a rw pointer argument written back, a native array
passed as a buffer and read back, a cast through a returned pointer, and
`qsort` calling back into NQP. It needs no compiled helper, so it runs
anywhere. Rakudo's `t/04-nativecall` is the broader suite and covers
struct passing, C globals and C++ constructors.
