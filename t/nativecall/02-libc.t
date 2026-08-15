#! nqp

# End-to-end native calls against the C library, which is the one shared
# object every platform we run on is guaranteed to have. t/nativecall/01
# only covers passing a string and handing back a pointer; this exercises
# the rest of the marshalling: integer arguments and returns, rw pointer
# arguments, native arrays passed as buffers and written back, casting
# through a pointer, and calling back into NQP from C.

plan(13);

BEGIN {
    nqp::initnativecall();
}

class Call is repr('NativeCall') { }

my $i8 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($i8, nqp::hash('integer', nqp::hash('bits', 8)));
my $i32 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($i32, nqp::hash('integer', nqp::hash('bits', 32)));
my $i64 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($i64, nqp::hash('integer', nqp::hash('bits', 64)));
my $ptr := nqp::newtype(nqp::null(), 'CPointer');

sub arg($type) { nqp::hash('type', $type) }

sub build($symbol, @args, $ret) {
    my $call := nqp::create(Call);
    nqp::buildnativecall($call, '', $symbol, '', @args, $ret);
    $call
}

# strlen: a string in, a size_t out.
my $strlen := build('strlen', [arg('utf8str')], arg('ulong'));
ok(nqp::unbox_i(nqp::nativecall($i64, $strlen, ['hello'])) == 5,
   'strlen of a passed string');
ok(nqp::unbox_i(nqp::nativecall($i64, $strlen, [''])) == 0,
   'strlen of the empty string');

# abs: an int in, an int out.
my $abs := build('abs', [arg('int')], arg('int'));
ok(nqp::unbox_i(nqp::nativecall($i32, $abs, [nqp::box_i(-42, $i32)])) == 42,
   'abs of a negative int');

# strtol: also writes back through a char ** argument, which is where
# rw pointer arguments get exercised.
my $end_arg := arg('cpointer');
$end_arg<rw> := 1;
my $strtol := build('strtol', [arg('utf8str'), $end_arg, arg('int')], arg('long'));

my $endptr := nqp::create($ptr);
ok(nqp::unbox_i(nqp::nativecall($i64, $strtol, ['123abc', $endptr, nqp::box_i(10, $i32)])) == 123,
   'strtol parsed the leading number');
ok(nqp::unbox_i($endptr) != 0, 'strtol wrote back an end pointer');
ok(nqp::unbox_i(nqp::nativecall($i64, $strtol, ['ff', $endptr, nqp::box_i(16, $i32)])) == 255,
   'strtol honoured the base it was passed');

# Casting a returned pointer back to something readable.
my $strdup := build('strdup', [arg('utf8str')], arg('cpointer'));
my $dupped := nqp::nativecall($ptr, $strdup, ['duplicated']);
ok(nqp::isconcrete($dupped) == 1, 'strdup handed back a pointer');
ok(nqp::unbox_i(nqp::nativecallcast($i8, $i8, $dupped)) == 100,
   'the duplicated string starts with "d"');

# sizeof, over the widths the C library just used.
ok(nqp::nativecallsizeof(nqp::box_i(0, $i8)) == 1, 'sizeof an 8 bit int');
ok(nqp::nativecallsizeof(nqp::box_i(0, $i32)) == 4, 'sizeof a 32 bit int');
ok(nqp::nativecallsizeof(nqp::box_i(0, $i64)) == 8, 'sizeof a 64 bit int');

# qsort: a native array goes out as a buffer and comes back sorted, and
# the comparison function is NQP code C calls into.
my $callback := arg('callback');
$callback<callback_args> := [
    nqp::hash('type', 'int',      'typeobj', $i32),
    nqp::hash('type', 'cpointer', 'typeobj', $ptr),
    nqp::hash('type', 'cpointer', 'typeobj', $ptr),
];
my $qsort := build('qsort',
    [arg('vmarray'), arg('ulong'), arg('ulong'), $callback],
    arg('void'));

my int $calls := 0;
my $compare := -> $a, $b {
    $calls := $calls + 1;
    my int $x := nqp::unbox_i(nqp::nativecallcast($i64, $i64, $a));
    my int $y := nqp::unbox_i(nqp::nativecallcast($i64, $i64, $b));
    $x < $y ?? -1 !! ($x > $y ?? 1 !! 0)
};

my @nums := nqp::list_i(5, 3, 9, 1, 7);
nqp::nativecall(nqp::null(), $qsort,
    [@nums, nqp::box_i(5, $i64), nqp::box_i(8, $i64), $compare]);

ok($calls > 0, 'qsort called back into NQP');
ok(nqp::atpos_i(@nums, 0) == 1
&& nqp::atpos_i(@nums, 1) == 3
&& nqp::atpos_i(@nums, 2) == 5
&& nqp::atpos_i(@nums, 3) == 7
&& nqp::atpos_i(@nums, 4) == 9,
   'the native array came back sorted');

# vim: ft=perl6
