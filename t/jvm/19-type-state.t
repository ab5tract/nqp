# Milestone 8, the type state: a site or a dispatch program that folded a
# type's published facts sees a republish. Two halves: a method-call site,
# whose recorded dispatch program folded the method the HOW resolved (tests
# 1-2), and an istype site, which folded the type-check cache (tests 3-4).
#
# Both halves put their two checks through ONE instruction (a sub's body),
# so the second check re-enters the very site the loop resolved. Two
# separate call sites would be two sites, and the second would simply
# resolve afresh against the new facts -- proving nothing.

plan(4);

# REPUBLISH PREMISE for the method half (measured 2026-09-16; see the Phase
# A ledger, Task 4): NQP method calls go through the `nqp-meth-call`
# dispatcher (src/core/dispatchers.nqp), which resolves through
# `$how.find_method` -- the HOW's own cache, NOT the STable's method cache.
# So `nqp::setmethcache` changes nothing a method call reads, on a fresh
# site as much as on a resolved one; `add_method` is the republish that
# matters, and NQPClassHOW.add_method makes it (setmethcacheauth on the
# jvm, which publishes a new state). add_method refuses a name the class
# already declares, so Foo overrides an INHERITED `m`: the loop resolves
# Base's, the add_method publishes Foo, and the site must let go of it.

class Base { method m() { 'old' } }
class Foo is Base { }
sub call_m($o) { $o.m }
my $o := Foo.new;
my $m := '';
my $j := 0;
while $j < 200 { $m := call_m($o); $j++ }
is($m, 'old', "the inherited method resolves before Foo declares its own");
Foo.HOW.add_method(Foo, 'm', sub ($self) { 'new' });
is(call_m($o), 'new', 'a method-call site resolved before add_method sees the added method');

# ROUTING PREMISE (load-bearing, and invisible from this file):
# `nqp::istype` is a CLASSLIB op. It reaches NqpTypeOps.IsTypeSite ONLY
# because NqpProgramBuilder.dedicatedClasslib maps it to Op.ISTYPE (the
# `istype` line beside `hllize`). Remove that line and every istype here
# goes back down the generic classlib road, which recomputes the answer on
# every call -- both tests below then pass no matter what the site does,
# and this file proves nothing. If you touch dedicatedClasslib, this test
# is the one that stops meaning anything, silently.
#
# The bite was proved by hand on 2026-09-16: with the site's
# valid()/typeValid() check removed from istype's fast path, test 4 fails
# (`got: '0'`, `expected: '1'`); with it restored, ok. Recorded in the
# Phase A ledger.

class Bar { }
class Baz { }
sub is_baz($o) { nqp::istype($o, Baz) }
my $b := Bar.new;
my $t := -1;
my $i := 0;
while $i < 200 { $t := is_baz($b); $i++ }
is($t, 0, 'istype is false before settypecache');
nqp::settypecache(Bar, [Bar, Baz]);
is(is_baz($b), 1, 'an istype site resolved before settypecache sees the new cache');
