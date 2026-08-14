#! nqp

# Compose-and-allocate coverage for the CStruct/CPPStruct/CUnion ASM
# class generators: nothing else in the nqp test suite ever composes a
# C type (that normally happens in Rakudo's NativeCall), so without this
# the generators only get compile-level verification. Composing drives
# the generated JNA Structure/Union subclass end to end: ASM emission,
# class definition through the ByteClassLoader, and instantiation.

plan(6);

my $int32 := nqp::newtype(nqp::null(), 'P6int');
nqp::composetype($int32, nqp::hash('integer', nqp::hash('bits', 32)));

sub compose_c($repr) {
    my $t := nqp::newtype(nqp::null(), $repr);
    my $attr := nqp::hash('name', 'x', 'type', $int32, 'inlined', 0);
    my @entry := [nqp::null(), [$attr], []];
    nqp::composetype($t, nqp::hash('attribute', [@entry]));
    ok(1, $repr ~ ' composes');
    my $obj := nqp::create($t);
    ok(nqp::isconcrete($obj) == 1, $repr ~ ' allocates a concrete instance');
}

compose_c('CStruct');
compose_c('CPPStruct');
compose_c('CUnion');
