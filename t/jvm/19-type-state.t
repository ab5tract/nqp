# Milestone 8, the type state: a site or a dispatch program that folded a
# type's published facts sees a republish. istype folds the type-check
# cache -- that is this half, and all this file tests today. Task 4 adds
# the method-call half (a dispatch program recorded before setmethcache).
#
# Both checks go through ONE istype instruction (the sub's body), so the
# second one re-enters the very site the loop resolved. Two separate
# `nqp::istype` call sites would be two sites, and the second would simply
# resolve afresh against the new cache -- proving nothing.

plan(2);

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
# valid()/typeValid() check removed from istype's fast path, test 2 fails
# (`got: '0'`, `expected: '1'`); with it restored, 2/2 ok. Recorded in the
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
