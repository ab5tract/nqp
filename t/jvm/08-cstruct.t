#! nqp

# Layout and member access for the CStruct/CPPStruct/CUnion representations.
# Nothing else in the nqp test suite ever composes a C type -- that normally
# happens in Rakudo's NativeCall -- so without this the three REPRs only get
# compile-level verification. Composing drives the layout computation end to
# end, and binding and reading members drives the offsets it worked out.

plan(27);

my $int8 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($int8, nqp::hash('integer', nqp::hash('bits', 8)));
my $int32 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($int32, nqp::hash('integer', nqp::hash('bits', 32)));
my $int64 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($int64, nqp::hash('integer', nqp::hash('bits', 64)));
my $num64 := nqp::newtype(nqp::null(), 'P6num');
nqp::composetype($num64, nqp::hash('float', nqp::hash('bits', 64)));

sub attr($name, $type) {
    nqp::hash('name', $name, 'type', $type, 'inlined', 0)
}

sub compose_c($repr, @attrs) {
    my $t := nqp::newtype(nqp::null(), $repr);
    nqp::composetype($t, nqp::hash('attribute', [[nqp::null(), @attrs, []]]));
    $t
}

# A single-member type of each kind still composes and allocates.
for <CStruct CPPStruct CUnion> -> $repr {
    my $t := compose_c($repr, [attr('x', $int32)]);
    ok(1, $repr ~ ' composes');
    my $obj := nqp::create($t);
    ok(nqp::isconcrete($obj) == 1, $repr ~ ' allocates a concrete instance');
    ok(nqp::nativecallsizeof($obj) == 4, $repr ~ ' of one int32 is 4 bytes');
}

# struct { int8 a; int32 b; double c; } -- b is padded out to offset 4 and c
# to offset 8, and the whole thing is 16 bytes with its double alignment.
my $mixed := compose_c('CStruct', [attr('a', $int8), attr('b', $int32), attr('c', $num64)]);
my $m := nqp::create($mixed);
ok(nqp::nativecallsizeof($m) == 16, 'CStruct pads and aligns like C does');

nqp::bindattr_i($m, $mixed, 'a', 65);
nqp::bindattr_i($m, $mixed, 'b', 1000000);
nqp::bindattr_n($m, $mixed, 'c', 6.25);
ok(nqp::getattr_i($m, $mixed, 'a') == 65,      'int8 member round-trips');
ok(nqp::getattr_i($m, $mixed, 'b') == 1000000, 'int32 member round-trips');
ok(nqp::getattr_n($m, $mixed, 'c') == 6.25,    'num64 member round-trips');

# Members must not tread on each other, which is what the padding is for.
nqp::bindattr_i($m, $mixed, 'a', -1);
ok(nqp::getattr_i($m, $mixed, 'b') == 1000000, 'writing a leaves b alone');
ok(nqp::getattr_n($m, $mixed, 'c') == 6.25,    'writing a leaves c alone');
ok(nqp::getattr_i($m, $mixed, 'a') == -1,      'int8 member is signed');

# Widths: each integer member truncates to its own size.
my $widths := compose_c('CStruct', [attr('b', $int8), attr('i', $int32), attr('l', $int64)]);
my $w := nqp::create($widths);
ok(nqp::nativecallsizeof($w) == 16, 'CStruct of int8/int32/int64 is 16 bytes');
nqp::bindattr_i($w, $widths, 'b', 300);
ok(nqp::getattr_i($w, $widths, 'b') == 44, 'int8 member truncates to 8 bits');
nqp::bindattr_i($w, $widths, 'l', 4294967296);
ok(nqp::getattr_i($w, $widths, 'l') == 4294967296, 'int64 member holds more than 32 bits');
ok(nqp::getattr_i($w, $widths, 'i') == 0, 'the int32 member is untouched by it');

# union { int8 a; int32 b; double c; } -- one byte of storage, eight bytes
# wide, and every member starts at the front of it.
my $u := compose_c('CUnion', [attr('a', $int8), attr('b', $int32), attr('c', $num64)]);
my $un := nqp::create($u);
ok(nqp::nativecallsizeof($un) == 8, 'CUnion is as wide as its widest member');

nqp::bindattr_i($un, $u, 'b', 66);
ok(nqp::getattr_i($un, $u, 'b') == 66, 'int32 union member round-trips');
ok(nqp::getattr_i($un, $u, 'a') == 66, 'the int8 member overlaps it');

nqp::bindattr_i($un, $u, 'a', 67);
ok(nqp::getattr_i($un, $u, 'b') == 67, 'writing the int8 member is seen through the int32 one');

# A nested struct held by reference is a pointer, whatever it contains.
my $inner := compose_c('CStruct', [attr('a', $int64), attr('b', $int64)]);
my $outer := compose_c('CStruct', [attr('x', $int32), attr('p', $inner)]);
my $o := nqp::create($outer);
ok(nqp::nativecallsizeof($inner) == 16, 'the nested struct is 16 bytes on its own');
ok(nqp::nativecallsizeof($o) == 16, 'holding it by reference costs a pointer, not its size');
nqp::bindattr_i($o, $outer, 'x', 7);
ok(nqp::getattr_i($o, $outer, 'x') == 7, 'the member before the pointer round-trips');

# vim: ft=perl6
