#! nqp

# The type of an if/ternary with no wanted type is its arms' common type,
# object only when they differ (Compiler.nqp's rule). It shows at a
# dispatch callsite, whose argument flags record the native type: a
# dispatcher name spelled `$c ?? 'a' !! 'b'` must reach the syscall as a
# str (Rakudo's dispatchers.nqp does exactly that, 2026-09-09).

plan(6);

nqp::register('t122-ternary', -> $capture {
    nqp::delegate(
        nqp::captureposarg_i($capture, 0) ?? 'boot-value' !! 'boot-value',
        nqp::syscall('dispatcher-drop-arg', $capture, 0))
});
is(nqp::dispatch('t122-ternary', 1, 42), 42, 'delegate with a ternary dispatcher name (then-arm)');
is(nqp::dispatch('t122-ternary', 0, 43), 43, 'delegate with a ternary dispatcher name (else-arm)');

my $c := 1;
my $sa := $c ?? 'yes' !! 'no';
is($sa, 'yes', 'str ternary in an untyped bind');
my $ia := $c ?? 7 !! 'seven';
is(~$ia, '7', 'mixed-type ternary boxes');
my int $n := 3;
my $two-arm := $n ?? $n + 1 !! 0;
is($two-arm, 4, 'two-arm value context keeps the common int type');
my $ei := 0;
my $fallthrough := $ei ?? 'x' !! 'y';
is($fallthrough, 'y', 'else-arm str');
