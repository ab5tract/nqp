plan(7);

# Coverage for the atomic attribute ops: nqp::atomicbindattr and
# nqp::casattr on boxed object attributes.
#
# Values are plain objects compared with nqp::eqaddr, so the test does not
# depend on HLL box type configuration. The concurrency section keeps all
# thread code in anonymous blocks: named file-scope subs must not be called
# from other threads (their outer frame chain is auto-vivified fresh there).

class Foo {
    has $!x;
    has $!y;
}

my $obj := Foo.new;
my $a := Foo.new;
my $b := Foo.new;
my $c := Foo.new;

nqp::atomicbindattr($obj, Foo, '$!x', $a);
ok(nqp::eqaddr(nqp::getattr($obj, Foo, '$!x'), $a),
    'atomicbindattr binds the attribute');

my $witness := nqp::casattr($obj, Foo, '$!x', $a, $b);
ok(nqp::eqaddr($witness, $a), 'successful casattr returns the expected value');
ok(nqp::eqaddr(nqp::getattr($obj, Foo, '$!x'), $b),
    'successful casattr stores the new value');

$witness := nqp::casattr($obj, Foo, '$!x', $a, $c);
ok(nqp::eqaddr($witness, $b), 'failed casattr returns the current value');
ok(nqp::eqaddr(nqp::getattr($obj, Foo, '$!x'), $b),
    'failed casattr leaves the attribute unchanged');

nqp::atomicbindattr($obj, Foo, '$!y', $c);
ok(nqp::eqaddr(nqp::getattr($obj, Foo, '$!y'), $c)
    && nqp::eqaddr(nqp::getattr($obj, Foo, '$!x'), $b),
    'atomic ops on one attribute do not disturb another');

# Contended case: four threads each CAS-prepend nodes onto a shared list
# head; every prepend must retry until its exchange wins, so the final
# chain length proves no update was lost.
class Node {
    has $!next;
    method next() { $!next }
}
class Holder {
    has $!head;
    method head() { $!head }
}

my $holder   := Holder.new;
my int $each := 200;
my @threads;
my int $t := 0;
while $t < 4 {
    nqp::push(@threads, nqp::newthread({
        my int $n := 0;
        while $n < $each {
            my $node := Node.new;
            my int $done := 0;
            until $done {
                my $cur := nqp::getattr($holder, Holder, '$!head');
                nqp::bindattr($node, Node, '$!next', $cur);
                $done := nqp::eqaddr(
                    nqp::casattr($holder, Holder, '$!head', $cur, $node), $cur);
            }
            $n++;
        }
    }, 0));
    $t++;
}
for @threads { nqp::threadrun($_) }
for @threads { nqp::threadjoin($_) }

my int $count := 0;
my $cursor := $holder.head;
while !nqp::isnull($cursor) && nqp::isconcrete($cursor) {
    $count++;
    $cursor := $cursor.next;
}
ok($count == 4 * $each, 'no CAS update was lost under contention (' ~ $count ~ ')');
