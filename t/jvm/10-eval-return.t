#! nqp

plan(3);

# On the JVM (and js) backend the compilation unit's mainline is the
# last expression of the unit block, so HLL::Compiler.eval returns the
# eval'd code's value. (MoarVM deliberately keeps its historical
# assembly order and still returns VMNull.)

my $comp := nqp::getcomp("nqp");

ok($comp.eval('1+1') == 2, 'eval returns an integer expression value');
ok($comp.eval('"x" ~ "y"') eq 'xy', 'eval returns a string expression value');
ok($comp.eval('my $a := 20; my $b := 22; $a + $b') == 42,
    'eval returns the last statement value of a multi-statement program');
