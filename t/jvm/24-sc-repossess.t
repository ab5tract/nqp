# Milestone 8 Phase C: repossession under the demand reader.
# A repossessed entry keeps its identity: the object or STable an older SC
# holds is the one the new SC's slot holds after deserialize, and it
# carries the new SC's data. Two shapes the two-pass repossess got wrong:
#  1. a repossessed STable whose method cache names a repossessed object of
#     the same SC (finishing the STable demanded the object before the
#     object pass registered it, so a fresh copy took the slot);
#  2. a mixin-repossessed object whose original was never read before the
#     new SC loaded (its original data must be finished under the original
#     layout before the object takes the mixin's STable).
# Modelled on t/serialization/04-repossession.t.

plan(12);

my $seq := 0;
sub fresh_sc_name() { $seq := $seq + 1; 'SC_REPOSSESS_' ~ $seq }
sub add_to_sc($sc, $idx, $obj) { nqp::scsetobj($sc, $idx, $obj); nqp::setobjsc($obj, $sc) }

class Holder {
    has $!v;
    method set($v) { $!v := $v }
    method get() { $!v }
}

# 1. The method cache of a repossessed STable names a repossessed object.
{
    my $sc1 := fresh_sc_name();
    my $sc2 := fresh_sc_name();
    my $old_sc := nqp::createsc($sc1);
    my $old_sh := nqp::list_s();

    my $T := nqp::knowhow().new_type(:name('ReposT'), :repr('P6opaque'));
    $T.HOW.compose($T);
    my $o := Holder.new;
    $o.set('old');
    add_to_sc($old_sc, 0, $T);
    add_to_sc($old_sc, 1, $o);
    my $old_serialized := nqp::serialize($old_sc, $old_sh);

    my $new_sc := nqp::createsc($sc2);
    my $new_sh := nqp::list_s();
    nqp::pushcompsc($new_sc);
    $o.set('new');                                   # the object, repossessed
    nqp::setmethcache($T, nqp::hash('m', $o));       # the STable, repossessed; its cache names the object
    nqp::popcompsc();
    my $slot := nqp::scgetobjidx($new_sc, $o);
    my $new_serialized := nqp::serialize($new_sc, $new_sh);

    my $old_dsc := nqp::createsc($sc1);
    nqp::deserialize($old_serialized, $old_dsc, $old_sh, nqp::list(), nqp::null());
    my $T2 := nqp::scgetobj($old_dsc, 0);
    my $o2 := nqp::scgetobj($old_dsc, 1);
    is($o2.get, 'old', 'the original object has the old data before the repossession');

    my $new_dsc := nqp::createsc($sc2);
    my $conflicts := nqp::list();
    nqp::deserialize($new_serialized, $new_dsc, $new_sh, nqp::list(), $conflicts);

    ok(nqp::eqaddr(nqp::scgetobj($new_dsc, $slot), $o2),
        'the new SC slot holds the original object, not a copy');
    is($o2.get, 'new', 'the original object carries the new data');
    ok(nqp::eqaddr(nqp::getobjsc($o2), $new_dsc), 'the original object is repossessed into the new SC');
    ok(nqp::eqaddr(nqp::findmethod($T2, 'm'), $o2),
        "the repossessed STable's method cache names the original object");
    is(nqp::elems($conflicts), 0, 'no conflicts');
}

# 2. A mixin-repossessed object whose original was never read before the
#    new SC loaded.
{
    my class Foo {
        has int $!int;
        method set($value) { $!int := $value }
        method get() { $!int }
    }
    my class Bar is mixin is Foo {
        has num $!num;
        has $!extra;
        has str $!s;
        method set_num($value) { $!num := $value; $!extra := nqp::list($value); $!s := "mixed" }
        method extra() { $!extra }
        method s() { $!s }
        method get_num() { $!num }
    }

    my $sc1 := fresh_sc_name();
    my $sc2 := fresh_sc_name();
    my $old_sc := nqp::createsc($sc1);
    my $old_sh := nqp::list_s();
    my $obj := Foo.new;
    $obj.set(123);
    add_to_sc($old_sc, 0, $obj);
    my $old_serialized := nqp::serialize($old_sc, $old_sh);

    my $new_sc := nqp::createsc($sc2);
    my $new_sh := nqp::list_s();
    nqp::pushcompsc($new_sc);
    nqp::rebless($obj, Bar);          # a ref and a str after the int: read past the original data, they fail
    $obj.set_num(2.5e0);
    nqp::popcompsc();
    my $slot := nqp::scgetobjidx($new_sc, $obj);
    my $new_serialized := nqp::serialize($new_sc, $new_sh);

    my $old_dsc := nqp::createsc($sc1);
    nqp::deserialize($old_serialized, $old_dsc, $old_sh, nqp::list(), nqp::null());
    # Nothing of the old SC is read here: the repossession demands it.
    my $new_dsc := nqp::createsc($sc2);
    nqp::deserialize($new_serialized, $new_dsc, $new_sh, nqp::list(), nqp::list());

    my $o2 := nqp::scgetobj($new_dsc, $slot);
    ok(nqp::eqaddr($o2, nqp::scgetobj($old_dsc, 0)), 'the new SC slot holds the original object');
    ok(nqp::istype($o2, Bar), 'the object has the mixin type');
    is($o2.get, 123, 'the original attribute keeps its value');
    is($o2.get_num, 2.5e0, 'the added attribute has the new value');
    is(nqp::atpos($o2.extra, 0), 2.5e0, "an added reference attribute has the new value");
    is($o2.s, "mixed", "an added str attribute has the new value");
}
