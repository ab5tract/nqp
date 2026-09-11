# Rebless (nqp::rebless, the road behind Raku's `does` on an instance) keeps
# the object: same identity, same attribute values, the target's new slots
# readable. Each source class size reblesses into a type one size up, so the
# new runtime's in-place growth is exercised across every grid boundary.

plan(14);

class A4  { has $!a; has $!b; has $!c; has $!d; }
class A5  is A4 { has $!e; }
class A8  is A5 { has $!f; has $!g; has $!h; }
class A9  is A8 { has $!i; }
class A16 is A9 { has $!j; has $!k; has $!l; has $!m; has $!n; has $!o; has $!p; }
class A17 is A16 { has $!q; }
class L0  { has $!x; }
class L1  is L0 { has int $!i; }
class L3  is L1 { has num $!n; has int $!j; }

sub rebless_check($obj, $from, $to, $attr, $label) {
    nqp::bindattr($obj, $from, '$!a', 'kept') if nqp::attrhintfor($from, '$!a') >= 0;
    my $before := $obj;
    my $after := nqp::rebless($obj, $to);
    ok(nqp::eqaddr($before, $after), $label ~ ': identity kept');
    nqp::bindattr($after, $to, $attr, 'new');
    ok(nqp::getattr($after, $to, $attr) eq 'new'
        && (nqp::attrhintfor($from, '$!a') < 0 || nqp::getattr($after, A4, '$!a') eq 'kept'),
        $label ~ ': old values kept, new slot works');
}
rebless_check(A4.new,  A4,  A5,  '$!e', '4 -> 5 (into overflow of a 4-class)');
rebless_check(A8.new,  A8,  A9,  '$!i', '8 -> 9');
rebless_check(A16.new, A16, A17, '$!q', '16 -> 17');
rebless_check(A4.new,  A4,  A16, '$!p', '4 -> 16 (twelve slots past the class)');

my $l := L0.new; nqp::bindattr($l, L0, '$!x', 'x');
nqp::rebless($l, L1); nqp::bindattr_i($l, L1, '$!i', 5);
ok(nqp::getattr_i($l, L1, '$!i') == 5 && nqp::getattr($l, L0, '$!x') eq 'x', 'a long slot appears on rebless');
nqp::rebless($l, L3); nqp::bindattr_n($l, L3, '$!n', 2.5); nqp::bindattr_i($l, L3, '$!j', 9);
ok(nqp::getattr_n($l, L3, '$!n') == 2.5 && nqp::getattr_i($l, L3, '$!j') == 9
    && nqp::getattr_i($l, L1, '$!i') == 5, 'three longs after two reblesses');

# A reblessed object and a fresh one of the target type agree.
my $fresh := A9.new; nqp::bindattr($fresh, A9, '$!i', 'f');
my $grown := nqp::rebless(A8.new, A9); nqp::bindattr($grown, A9, '$!i', 'g');
ok(nqp::getattr($fresh, A9, '$!i') eq 'f' && nqp::getattr($grown, A9, '$!i') eq 'g', 'fresh and grown objects of one type both work');
ok(nqp::eqaddr(nqp::what($grown), A9), 'the grown object is of the target type');

# Rebless of a clone leaves the original alone (Raku's `but`).
my $orig := A4.new; nqp::bindattr($orig, A4, '$!a', 'o');
my $but := nqp::rebless(nqp::clone($orig), A5);
ok(!nqp::eqaddr($orig, $but) && nqp::eqaddr(nqp::what($orig), A4), 'clone-then-rebless keeps the original');

# Errors.
my $msg := '';
try { nqp::rebless(A4.new, L1); CATCH { $msg := nqp::getmessage($_); } }
ok(nqp::index($msg, 'Incompatible MROs') >= 0, 'rebless to an incompatible type dies');
