# Milestone 8 Phase C: serialization format 12 and the demand reader.
# Part A (Task 4): round trips of the varint and packed-reference wire --
# integer edges, strings, own-SC and dependency references -- through
# nqp::serialize / nqp::deserialize on fresh SCs. Part B (Task 7) reads a
# child's sc-demand exit lines. The child-process helper is the one of
# t/jvm/20-op-census.t; prove must run from the nqp tree.

plan(24);

my $n := -1;
sub fresh-sc($tag) { $n := $n + 1; nqp::createsc('SC_DEMAND_' ~ $n ~ '_' ~ $tag) }
sub add-to-sc($sc, $idx, $obj) { nqp::scsetobj($sc, $idx, $obj); nqp::setobjsc($obj, $sc) }
sub round-trip($sc) {
    my $sh := nqp::list_s();
    my $blob := nqp::serialize($sc, $sh);
    my $out := fresh-sc('OUT');
    nqp::deserialize($blob, $out, $sh, nqp::list(), nqp::null());
    $out
}

# Integer edges through a VMArray of ints (VM_ARR_INT: varint count, zigzag elements).
{
    my $sc := fresh-sc('IN');
    my $big := nqp::bitshiftl_i(1, 62);
    my @edges := nqp::list_i(0, 1, -1, 63, -64, 64, -65, 127, 128, 16383, 16384,
        $big, nqp::sub_i(nqp::add_i($big, $big), 1), nqp::neg_i(nqp::add_i($big, $big)));
    my $holder := nqp::hash('ints', @edges);
    add-to-sc($sc, 0, $holder);
    my $out := round-trip($sc);
    my @back := nqp::atkey(nqp::scgetobj($out, 0), 'ints');
    is(nqp::elems(@back), nqp::elems(@edges), 'the int list keeps its length');
    my $i := 0;
    my $same := 1;
    while $i < nqp::elems(@edges) { $same := 0 unless nqp::atpos_i(@back, $i) == nqp::atpos_i(@edges, $i); $i++ }
    ok($same, 'every integer edge survives the zigzag varint (0, +-1, +-64/65, 127/128, 16383/16384, 2^62, max, min)');
    is(nqp::atpos_i(@back, 12), nqp::sub_i(nqp::add_i($big, $big), 1), 'Long.MAX_VALUE');
    is(nqp::atpos_i(@back, 13), nqp::neg_i(nqp::add_i($big, $big)), 'Long.MIN_VALUE');
}

# Strings: empty, ASCII, non-ASCII, long; a hash keyed by them (VM_HASH_STR_VAR).
{
    my $sc := fresh-sc('IN');
    my $long := nqp::x('abcdefghij', 30);
    my @strs := nqp::list_s('', 'abc', 'ünïcödé ☃', $long);
    my %h := nqp::hash('', 'empty key', 'ünïcödé ☃', 'unicode key', $long, 'long key');
    add-to-sc($sc, 0, nqp::hash('strs', @strs, 'h', %h));
    my $out := round-trip($sc);
    my $o := nqp::scgetobj($out, 0);
    my @back := nqp::atkey($o, 'strs');
    is(nqp::atpos_s(@back, 0), '', 'the empty string');
    is(nqp::atpos_s(@back, 1), 'abc', 'an ASCII string');
    is(nqp::atpos_s(@back, 2), 'ünïcödé ☃', 'a non-ASCII string');
    is(nqp::atpos_s(@back, 3), $long, 'a 300-char string');
    my %hb := nqp::atkey($o, 'h');
    is(nqp::atkey(%hb, ''), 'empty key', 'the empty string as a hash key');
    is(nqp::atkey(%hb, 'ünïcödé ☃'), 'unicode key', 'a non-ASCII hash key');
    is(nqp::atkey(%hb, $long), 'long key', 'a long hash key');
    is(nqp::elems(%hb), 3, 'the hash keeps its size');
}

# Own-SC references (packed, dependency bit 0) between two objects.
class Pair2 {
    has $!left;
    has $!right;
    method left() { $!left }
    method right() { $!right }
};
{
    my $sc := fresh-sc('IN');
    my $a := Pair2.new; my $b := Pair2.new;
    nqp::bindattr($a, Pair2, '$!left', $b);
    nqp::bindattr($a, Pair2, '$!right', $a);
    nqp::bindattr($b, Pair2, '$!left', nqp::null());
    nqp::bindattr($b, Pair2, '$!right', $a);
    add-to-sc($sc, 0, $a);
    add-to-sc($sc, 1, $b);
    my $out := round-trip($sc);
    my $a2 := nqp::scgetobj($out, 0); my $b2 := nqp::scgetobj($out, 1);
    ok(nqp::eqaddr($a2.left, $b2), 'an own-SC reference to the other root object');
    ok(nqp::eqaddr($a2.right, $a2), 'an own-SC self reference (a cycle)');
    ok(nqp::isnull($b2.left), 'a VM null reference');
    ok(nqp::eqaddr($b2.right, $a2), 'the cycle from the other side');
}

# A dependency reference (packed, bit 1 set, then the SC id): the object
# lives in a first SC; a second SC's object points at it.
{
    my $dep := fresh-sc('DEP');
    my $p := Pair2.new;
    add-to-sc($dep, 0, $p);
    my $sc := fresh-sc('IN');
    my $q := Pair2.new;
    nqp::bindattr($q, Pair2, '$!left', $p);
    nqp::bindattr($q, Pair2, '$!right', nqp::null());
    add-to-sc($sc, 0, $q);
    my $out := round-trip($sc);
    my $q2 := nqp::scgetobj($out, 0);
    ok(nqp::eqaddr($q2.left, $p), 'a reference into a dependency SC resolves to the same object');
    ok(nqp::eqaddr(nqp::getobjsc($q2.left), $dep), 'and that object still belongs to its own SC');
}

# Type objects (an object row with bit 31 set: no data) and an STable
# reference (a type's STable reached through a new type in the SC).
{
    my $sc := fresh-sc('IN');
    my $t := nqp::knowhow().new_type(:name('DemandT'), :repr('P6opaque'));
    $t.HOW.compose($t);
    add-to-sc($sc, 0, $t);
    my $out := round-trip($sc);
    my $t2 := nqp::scgetobj($out, 0);
    ok(!nqp::isconcrete($t2), 'a type object round-trips as a type object');
    is($t2.HOW.name($t2), 'DemandT', 'with its HOW (an own-SC object reference from the STable)');
    ok(nqp::eqaddr($t2.WHAT, $t2), 'and its WHAT');
}

# A boxed int, num and str in a list: the HLL's box types (P6int/P6num/
# P6str objects, own-SC references) or the BOOT boxes inline (VM_INT
# zigzag, VM_NUM 8 bytes, VM_STR index), whichever nqp::list produces;
# both roads are on the wire this file tests.
{
    my $sc := fresh-sc('IN');
    add-to-sc($sc, 0, nqp::list(-9000000000, 2.5e0, 'boxed'));
    my $out := round-trip($sc);
    my @l := nqp::scgetobj($out, 0);
    is(nqp::unbox_i(nqp::atpos(@l, 0)), -9000000000, 'a boxed int past 32 bits');
    is(nqp::unbox_n(nqp::atpos(@l, 1)), 2.5e0, 'a boxed num');
    is(nqp::unbox_s(nqp::atpos(@l, 2)), 'boxed', 'a boxed str');
}
