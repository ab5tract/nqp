use nqpmo;

# The RakuObject layout, pinned: attribute counts across the storage-class
# grid (4, 8, 16 inline references; 0 or 2 inline longs) and past it (the
# overflow arrays), every slot kind through the ops, hints, multiple
# inheritance, the four error texts, clone. Written before milestone 5's
# runtime and green on milestone 4's, so a difference is a regression.

plan(50);

# --- reference slots across the grid boundaries: 3, 4, 5, 8, 9, 16, 17 ---
class R3  { has $!a; has $!b; has $!c; }
class R4  { has $!a; has $!b; has $!c; has $!d; }
class R5  { has $!a; has $!b; has $!c; has $!d; has $!e; }
class R8  { has $!a; has $!b; has $!c; has $!d; has $!e; has $!f; has $!g; has $!h; }
class R9  is R8 { has $!i; }
class R16 is R8 { has $!i; has $!j; has $!k; has $!l; has $!m; has $!n; has $!o; has $!p; }
class R17 is R16 { has $!q; }

sub fill($obj, $type, @names) {
    my int $i := 0;
    for @names { nqp::bindattr($obj, $type, '$!' ~ $_, $i); $i := $i + 1; }
}
sub check($obj, $type, @names, $label) {
    my int $ok := 1; my int $i := 0;
    for @names { $ok := 0 unless nqp::getattr($obj, $type, '$!' ~ $_) == $i; $i := $i + 1; }
    ok($ok, $label);
}
my @r8  := <a b c d e f g h>;
my @r16 := <a b c d e f g h i j k l m n o p>;

my $r3 := R3.new;  fill($r3, R3, <a b c>);        check($r3, R3, <a b c>, '3 refs');
my $r4 := R4.new;  fill($r4, R4, <a b c d>);      check($r4, R4, <a b c d>, '4 refs (boundary)');
my $r5 := R5.new;  fill($r5, R5, <a b c d e>);    check($r5, R5, <a b c d e>, '5 refs');
my $r8 := R8.new;  fill($r8, R8, @r8);            check($r8, R8, @r8, '8 refs (boundary)');
my $r9 := R9.new;  fill($r9, R8, @r8); nqp::bindattr($r9, R9, '$!i', 8);
ok(nqp::getattr($r9, R9, '$!i') == 8 && nqp::getattr($r9, R8, '$!h') == 7, '9 refs across a parent');
my $r16 := R16.new; fill($r16, R8, @r8);
fill($r16, R16, <i j k l m n o p>);
ok(nqp::getattr($r16, R16, '$!p') == 7 && nqp::getattr($r16, R8, '$!a') == 0, '16 refs (boundary)');
my $r17 := R17.new; fill($r17, R8, @r8); fill($r17, R16, <i j k l m n o p>);
nqp::bindattr($r17, R17, '$!q', 99);
ok(nqp::getattr($r17, R17, '$!q') == 99, '17 refs: the slot past the largest class');
ok(nqp::getattr($r17, R16, '$!p') == 7, '17 refs: the last inline slot still reads');

# --- long slots: 0, 1, 2, 3 natives, mixed with references ---
class L1 { has int $!i; has $!o; }
class L2 { has int $!i; has num $!n; has $!o; }
class L3 { has int $!i; has num $!n; has int $!j; has $!o; }
my $l1 := L1.new; nqp::bindattr_i($l1, L1, '$!i', 7); nqp::bindattr($l1, L1, '$!o', 'x');
ok(nqp::getattr_i($l1, L1, '$!i') == 7 && nqp::getattr($l1, L1, '$!o') eq 'x', '1 long + 1 ref');
my $l2 := L2.new; nqp::bindattr_i($l2, L2, '$!i', 1); nqp::bindattr_n($l2, L2, '$!n', 2.5);
ok(nqp::getattr_i($l2, L2, '$!i') == 1 && nqp::getattr_n($l2, L2, '$!n') == 2.5, '2 longs (boundary)');
my $l3 := L3.new; nqp::bindattr_i($l3, L3, '$!i', 1); nqp::bindattr_n($l3, L3, '$!n', 2.5);
nqp::bindattr_i($l3, L3, '$!j', 3);
ok(nqp::getattr_i($l3, L3, '$!j') == 3 && nqp::getattr_n($l3, L3, '$!n') == 2.5, '3 longs: the slot past the inline pair');

# --- every kind through the ops ---
class K { has int $!i; has num $!n; has str $!s; has $!o; }
my $k := K.new;
nqp::bindattr_i($k, K, '$!i', -5); nqp::bindattr_n($k, K, '$!n', 1.5);
nqp::bindattr_s($k, K, '$!s', 'str'); nqp::bindattr($k, K, '$!o', $k);
ok(nqp::getattr_i($k, K, '$!i') == -5, 'int slot');
ok(nqp::getattr_n($k, K, '$!n') == 1.5, 'num slot');
ok(nqp::getattr_s($k, K, '$!s') eq 'str', 'str slot');
ok(nqp::eqaddr(nqp::getattr($k, K, '$!o'), $k), 'ref slot');
ok(nqp::getattr($k, K, '$!i') == -5, 'a native int read boxed');
ok(nqp::getattr($k, K, '$!n') == 1.5, 'a native num read boxed');
ok(nqp::getattr($k, K, '$!s') eq 'str', 'a native str read boxed');

# --- attrinited, hints, MI ---
class I { has $!x; has $!y; }
my $iobj := I.new;
ok(nqp::attrinited($iobj, I, '$!x') == 0, 'attrinited is 0 before a bind');
nqp::bindattr($iobj, I, '$!x', 1);
ok(nqp::attrinited($iobj, I, '$!x') == 1, 'attrinited is 1 after a bind');
ok(nqp::attrhintfor(I, '$!x') == 0 && nqp::attrhintfor(I, '$!y') == 1, 'hints are the slot numbers, parents first');
ok(nqp::attrhintfor(R9, '$!i') == 8, "a subclass's own attribute follows the parent's slots");
ok(nqp::attrhintfor(I, '$!nope') == -1, 'no hint for an unknown attribute');
class MA { has $!a; }
class MB { has $!b; }
# NQP's `class` declaration takes only one `is`, so the two-parent type is
# built through the HOW, as t/nqp/058-attrs.t builds its types.
my $MAB := NQPClassHOW.new_type(:name('MAB'), :repr('P6opaque'));
$MAB.HOW.add_attribute($MAB, NQPAttribute.new(:name('$!c')));
$MAB.HOW.add_parent($MAB, MA);
$MAB.HOW.add_parent($MAB, MB);
$MAB.HOW.compose($MAB);
my $mab := nqp::create($MAB);
nqp::bindattr($mab, MA, '$!a', 'a'); nqp::bindattr($mab, MB, '$!b', 'b'); nqp::bindattr($mab, $MAB, '$!c', 'c');
ok(nqp::getattr($mab, MA, '$!a') eq 'a' && nqp::getattr($mab, MB, '$!b') eq 'b'
    && nqp::getattr($mab, $MAB, '$!c') eq 'c', 'multiple inheritance resolves by name');

# --- the four error texts ---
sub dies_with($code, $needle, $label) {
    my $msg := '';
    try { $code(); CATCH { $msg := nqp::getmessage($_); } }
    ok(nqp::index($msg, $needle) >= 0, $label ~ " ($msg)");
}
dies_with({ nqp::getattr($iobj, I, '$!nope') }, "No such attribute '\$!nope'", 'unknown attribute');
dies_with({ nqp::getattr_i($iobj, I, '$!x') }, 'Cannot access a reference attribute as a native attribute', 'reference slot read natively');
dies_with({ nqp::getattr_i($k, K, '$!o') }, 'Cannot access a reference attribute as a native attribute', 'reference slot read natively (K)');
dies_with({ nqp::rebless(I.new, K) }, 'Incompatible MROs', 'rebless to an unrelated type');

# --- clone ---
my $c17 := nqp::clone($r17);
ok(nqp::getattr($c17, R17, '$!q') == 99 && nqp::getattr($c17, R8, '$!a') == 0, 'clone copies inline and overflow slots');
nqp::bindattr($c17, R17, '$!q', 1);
ok(nqp::getattr($r17, R17, '$!q') == 99, 'a clone has its own overflow array');
my $cl3 := nqp::clone($l3);
nqp::bindattr_i($cl3, L3, '$!j', 30);
ok(nqp::getattr_i($l3, L3, '$!j') == 3, 'a clone has its own long overflow array');

# --- boxing through the unbox slots (built as t/nqp/098-boxing.t builds them) ---
sub boxer($name, $type) {
    my $t := NQPClassHOW.new_type(:name($name), :repr('P6opaque'));
    $t.HOW.add_attribute($t, NQPAttribute.new(:name('$!value'), :type($type), :box_target(1)));
    $t.HOW.add_parent($t, NQPMu);
    $t.HOW.compose($t);
    $t
}
my $BI := boxer('BI', int); my $BN := boxer('BN', num); my $BS := boxer('BS', str);
ok(nqp::unbox_i(nqp::box_i(42, $BI)) == 42, 'int box target');
ok(nqp::unbox_n(nqp::box_n(4.5, $BN)) == 4.5, 'num box target');
ok(nqp::unbox_s(nqp::box_s('q', $BS)) eq 'q', 'str box target');
ok(nqp::unbox_i(nqp::box_i(-1, $BI)) == -1, 'int box target keeps the sign');

# --- sized natives store truncated, as MoarVM's sized registers do ---
my $S8 := NQPClassHOW.new_type(:name('S8'), :repr('P6opaque'));
$S8.HOW.add_attribute($S8, NQPAttribute.new(:name('$!v'), :type(int8)));
$S8.HOW.add_attribute($S8, NQPAttribute.new(:name('$!u'), :type(uint8)));
$S8.HOW.add_parent($S8, NQPMu);
$S8.HOW.compose($S8);
my $s8 := nqp::create($S8);
nqp::bindattr_i($s8, $S8, '$!v', 300); nqp::bindattr_i($s8, $S8, '$!u', 300);
ok(nqp::getattr_i($s8, $S8, '$!v') == 44, 'int8 wraps to its width');
ok(nqp::getattr_i($s8, $S8, '$!u') == 44, 'uint8 masks to its width');

# --- an unsigned attribute through the _u ops and through the box ---
my $U32 := NQPClassHOW.new_type(:name('U32'), :repr('P6opaque'));
$U32.HOW.add_attribute($U32, NQPAttribute.new(:name('$!u'), :type(uint32)));
$U32.HOW.add_parent($U32, NQPMu);
$U32.HOW.compose($U32);
my $u32 := nqp::create($U32);
nqp::bindattr_u($u32, $U32, '$!u', 4294967295);
ok(nqp::getattr_u($u32, $U32, '$!u') == 4294967295, 'a uint32 attribute holds its whole unsigned range');
nqp::bindattr_u($u32, $U32, '$!u', 4294967296);
ok(nqp::getattr_u($u32, $U32, '$!u') == 0, 'a uint32 store masks to its width');
nqp::bindattr_u($u32, $U32, '$!u', 4294967295);
ok(nqp::getattr($u32, $U32, '$!u') == 4294967295, 'a boxed read of a uint slot is unsigned');

# --- type objects ---
# Milestone 4 words this one "Cannot look up attributes in a type object".
dies_with({ nqp::getattr(I, I, '$!x') }, 'Cannot look up attributes in a type object', 'attribute read on a type object');
ok(nqp::isconcrete(I.new) == 1 && nqp::isconcrete(I) == 0, 'isconcrete');

# --- positional / associative delegates ---
class PD { has @!list is positional_delegate; has %!hash is associative_delegate; }
my $pd := PD.new;
nqp::bindattr($pd, PD, '@!list', nqp::list(1, 2, 3));
nqp::bindattr($pd, PD, '%!hash', nqp::hash('k', 'v'));
ok(nqp::elems($pd) == 3 && nqp::atpos($pd, 1) == 2, 'positional delegate');
ok(nqp::atkey($pd, 'k') eq 'v' && nqp::existskey($pd, 'k'), 'associative delegate');
nqp::push($pd, 4);
ok(nqp::elems($pd) == 4, 'push through the delegate');

# --- 50 planned; the remaining ok()s pin auto-viv ---
class AV { has @!a; has %!h; has $!s; }
my $av := AV.new;
ok(nqp::islist(nqp::getattr($av, AV, '@!a')), 'an @ attribute auto-vivifies a list');
ok(nqp::ishash(nqp::getattr($av, AV, '%!h')), 'a % attribute auto-vivifies a hash');
# Milestone 4 does not hand back a null here: an unbound $ attribute reads as
# the NQPMu type object (and attrinited only then turns 1 for it, while an @ or
# % attribute counts as initialized from creation).
ok(nqp::attrinited($av, AV, '$!s') == 0, 'a $ attribute is uninitialized until read or bound');
my $unbound := nqp::getattr($av, AV, '$!s');
ok(nqp::eqaddr($unbound, NQPMu) && nqp::isconcrete($unbound) == 0,
    'an unbound $ attribute reads as the NQPMu type object');
ok(nqp::attrinited($av, AV, '@!a') == 1, 'auto-viv counts as initialized');
