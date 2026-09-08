#! nqp

# for-loop control flow (next/last/redo), arity, topic and value context,
# plus the post-increment family. The expectations are what nqp-m answers
# (2026-09-08); the JVM's two roads -- bytecode and the Truffle compiler's
# FORLOOP -- must agree with it.

plan(16);

my @a := [1, 2, 3, 4];

my @seen := [];
for @a -> $x { @seen.push(~$x) }
is(nqp::join(',', @seen), '1,2,3,4', 'plain for over a list');

@seen := [];
for @a -> $x { next if $x == 2; @seen.push(~$x) }
is(nqp::join(',', @seen), '1,3,4', 'next skips the rest of the body');

@seen := [];
for @a -> $x { last if $x == 3; @seen.push(~$x) }
is(nqp::join(',', @seen), '1,2', 'last ends the loop');

# All three backends (moar, JVM bytecode, JVM Truffle) agree that a redo
# in a for body does not re-run the body: it continues with the next value.
my $n := 0;
@seen := [];
for @a -> $x { $n++; @seen.push(~$x); redo if $n == 2; }
is(nqp::join(',', @seen), '1,2,3,4', 'redo in a for body: values seen');
is($n, 4, 'redo in a for body: body ran once per value');

@seen := [];
for @a -> $p, $q { @seen.push($p ~ '-' ~ $q) }
is(nqp::join(',', @seen), '1-2,3-4', 'arity-2 block takes two values per iteration');

@seen := [];
for @a { @seen.push(~($_ * 10)) }
is(nqp::join(',', @seen), '10,20,30,40', 'implicit $_ topic');

my $cnt := 0;
for [] { $cnt++ }
is($cnt, 0, 'empty list runs the body zero times');

$cnt := 0;
for @a -> $x { for @a -> $y { $cnt++; last if $y == 2 } }
is($cnt, 8, 'last in an inner for ends only the inner loop');

# A redo thrown from inside a while body re-runs the body without re-testing.
@seen := [];
my $i := 0;
while $i < 1 { $i++; @seen.push(~$i); redo if $i == 1 }
is(nqp::join(',', @seen), '1,2', 'redo in a while body re-runs without re-testing');

my $pi := 5; my $pj := $pi++;
is($pi ~ ' ' ~ $pj, '6 5', 'postinc: new value, old value');
my int $pk := 7; my int $pm := $pk--;
is($pk ~ ' ' ~ $pm, '6 7', 'postdec on native int');
my $pu; my $pv := $pu++;
is(~$pu, '1', 'postinc on an unassigned variable counts from 0');
my $pw := 3; ++$pw; --$pw; $pw++; $pw++; $pw--;
is($pw, 4, 'pre and post increments mixed');
is(nqp::index(nqp::indexingoptimized('hello'), 'l'), 2, 'indexingoptimized is a str pass-through');

my $sum := 0;
for @a -> $x { $sum := $sum + $x }
is($sum, 10, 'for body sees and updates an outer lexical');
