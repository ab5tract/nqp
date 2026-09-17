# Milestone 8 Phase B, batch 2: the typed classlib road (spec section 6.2).
# In-process: one op per arity and flavour answers correctly through the
# new nodes (the process runs with the road on). Child processes under
# NQP_OP_CENSUS=1 prove the routing: the header's classlibTyped= counter
# moves, and its classlibLong= counter -- the long flavour's share of it --
# moves with it but stays strictly below it; under NQP_SITES_OFF=classlib
# both are 0 while the per-name classlib
# count is unchanged; under NQP_CLASSLIB_INLINE=1 the typed road still runs;
# and a continuation captured inside a classlib op's callee (a container
# FETCH under nqp::decont, with the decont sites off) resumes through the
# road's suspend token.
#
# The child-process helper is the one of t/jvm/20-op-census.t, grown to
# return stdout as well. This file spawns ./nqp-j-gradle relative to the
# nqp tree, so prove must run from there.

plan(27);

my class Queue is repr('ConcBlockingQueue') { }
my class VMDecoder is repr('Decoder') { }
my sub create_buf($type) {
    my $buf := nqp::newtype(nqp::null(), 'VMArray');
    nqp::composetype($buf, nqp::hash('array', nqp::hash('type', $type)));
    $buf
}

# Runs ./nqp-j-gradle -e $code as a child under %env; returns [status, stderr, stdout].
sub child-run($code, %env) {
    my $queue := nqp::create(Queue);
    my $done := 0; my $out-eof := 0; my $err-eof := 0; my $status := -1;
    my @err; my @out;
    my $config := nqp::hash(
        'done', -> $st { $status := $st; $done := 1 },
        'ready', -> $stdin?, $stdout?, $stderr? { },
        'stdout_bytes', -> $seq, $data, $err {
            if nqp::isconcrete($data) { @out[$seq] := $data } else { $out-eof := 1 }
        },
        'stderr_bytes', -> $seq, $data, $err {
            if nqp::isconcrete($data) { @err[$seq] := $data } else { $err-eof := 1 }
        },
        'buf_type', create_buf(uint8));
    my $task := nqp::spawnprocasync($queue, './nqp-j-gradle', nqp::list('./nqp-j-gradle', '-e', $code),
                                    nqp::cwd(), %env, $config);
    nqp::permit($task, 1, -1);
    nqp::permit($task, 2, -1);
    while !$done || !$out-eof || !$err-eof {
        if nqp::shift($queue) -> $t {
            if nqp::islist($t) { my $cb := nqp::shift($t); $cb(|$t) } else { $t() }
        }
    }
    sub decode(@bytes) {
        my $dec := nqp::create(VMDecoder);
        nqp::decoderconfigure($dec, 'utf8', nqp::hash());
        for @bytes -> $b { nqp::decoderaddbytes($dec, $b) if nqp::isconcrete($b) }
        nqp::decodertakeallchars($dec)
    }
    nqp::list($status, decode(@err), decode(@out))
}

# "  <prefix> <count> <name>" -> count, or -1 when absent.
sub count-of($text, $prefix, $name) {
    for nqp::split("\n", $text) -> $line {
        my $lead := '  ' ~ $prefix ~ ' ';
        if nqp::index($line, $lead) == 0 {
            my @f := nqp::split(' ', nqp::substr($line, nqp::chars($lead)));
            return +@f[0] if @f[1] eq $name;
        }
    }
    -1
}

# "op census: ... <field>=N" -> N, or -1.
sub header-field($text, $field) {
    for nqp::split("\n", $text) -> $line {
        if nqp::index($line, 'op census:') == 0 {
            for nqp::split(' ', $line) -> $kv {
                return +nqp::substr($kv, nqp::chars($field) + 1) if nqp::index($kv, $field ~ '=') == 0;
            }
        }
    }
    -1
}

# ---- in-process: one op per arity and flavour ---------------------------
ok(nqp::time() > 0, 'arity 0, INT, no context: time');
ok(nqp::chars(nqp::cwd()) > 0, 'arity 0, STR, no context: cwd');
is(nqp::chars('abc'), 3, 'arity 1, INT, no context (long flavour): chars');
is(nqp::elems(nqp::list(1, 2, 3)), 3, 'arity 1, INT, with context (Object flavour): elems');
is(nqp::concat('a', 'b'), 'ab', 'arity 2, STR, no context: concat');
my %h; %h<k> := 1;
is(nqp::existskey(%h, 'k'), 1, 'arity 2, INT, with context: existskey, present');
is(nqp::existskey(%h, 'z'), 0, 'existskey, absent');
is(nqp::atpos(nqp::list(1, 2, 3), 1), 2, 'arity 2, OBJ, with context: atpos');
is(nqp::eqat('hello', 'ell', 1), 1, 'arity 3, INT, no context (long flavour): eqat');
is(nqp::findcclass(nqp::const::CCLASS_WHITESPACE, 'ab cd', 0, 5), 2, 'arity 4, INT, no context (Object flavour by Ruling 1): findcclass');
my $mda := nqp::newtype(nqp::knowhow(), 'MultiDimArray');
nqp::composetype($mda, nqp::hash('array', nqp::hash('dimensions', 3)));
my $cube := nqp::create($mda);
nqp::setdimensions($cube, nqp::list_i(2, 2, 2));
nqp::bindpos3d($cube, 1, 1, 1, 'x');
is(nqp::atpos3d($cube, 1, 1, 1), 'x', 'arity 5 stays on the variadic node: bindpos3d');
class Unboxable { }
my $died := 0;
try { nqp::unbox_i(Unboxable); CATCH { $died := 1 } }
is($died, 1, 'an op that dies through the typed road is caught by try');

# ---- routing: the census header's classlibTyped= / classlibLong= counters -
my $loop := 'my $s := 0; my $i := 0; while $i < 1000 { $s := $s + nqp::chars("abc") + nqp::elems(nqp::list(1)); $i++ }; say($s)';
my %on := nqp::getenvhash();
%on<NQP_OP_CENSUS> := '1';
my $typed := child-run($loop, %on);
is($typed[0], 0, 'the typed-road child exits 0');
ok(header-field($typed[1], 'classlibTyped') >= 2000, 'chars and elems travel the typed road');
ok(header-field($typed[1], 'classlibLong') >= 1000, 'chars, context-free INT, travels the long flavour (classlibLong=)');
ok(header-field($typed[1], 'classlibLong') < header-field($typed[1], 'classlibTyped'), 'the Object flavour does not bump the long total');
ok(count-of($typed[1], 'classlib', 'Ops.chars') >= 1000, 'the per-name census still counts chars');
ok(count-of($typed[1], 'classlib', 'Ops.elems') >= 1000, 'and elems');

my %off := nqp::getenvhash();
%off<NQP_OP_CENSUS> := '1';
%off<NQP_SITES_OFF> := 'classlib';
my $variadic := child-run($loop, %off);
is($variadic[0], 0, 'the kill-switch child exits 0');
is(header-field($variadic[1], 'classlibTyped'), 0, 'NQP_SITES_OFF=classlib builds no typed node');
is(header-field($variadic[1], 'classlibLong'), 0, 'NQP_SITES_OFF=classlib builds no long node either');
ok(count-of($variadic[1], 'classlib', 'Ops.chars') >= 1000, 'the variadic road still counts chars');

my %inline := nqp::getenvhash();
%inline<NQP_OP_CENSUS> := '1';
%inline<NQP_CLASSLIB_INLINE> := '1';
my $pe := child-run($loop, %inline);
is($pe[0], 0, 'the NQP_CLASSLIB_INLINE=1 child exits 0');
ok(header-field($pe[1], 'classlibTyped') >= 2000, 'the knob keeps the typed road (PE-visible calls)');

# ---- a capture inside a classlib op's callee resumes through the road ----
my $capture := 'class Box { has $!v; method new($v) { my $o := nqp::create(self); nqp::bindattr($o, Box, q<$!v>, $v); $o } }
nqp::setcontspec(Box, q<code_pair>, nqp::hash(
    q<fetch>, -> $c { nqp::continuationcontrol(0, nqp::null(), -> $k { nqp::continuationinvoke($k, { 42 }) }) },
    q<store>, -> $c, $v { }));
my $b := Box.new(1);
say(nqp::continuationreset(nqp::null(), { nqp::decont($b) + 1 }))';
my %reach := nqp::getenvhash();
%reach<NQP_OP_CENSUS> := '1';
%reach<NQP_SITES_OFF> := 'reach,decont';
my $resumed := child-run($capture, %reach);
is($resumed[0], 0, 'the capture child exits 0');
ok(nqp::index($resumed[2], '43') >= 0, 'a FETCH that captures a continuation resumes through the typed road (42 + 1)');
ok(count-of($resumed[1], 'classlib', 'Ops.decont') >= 1, 'and decont travelled the classlib road');
