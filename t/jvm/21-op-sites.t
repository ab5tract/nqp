# Milestone 8 Phase B, batch 1: the promoted sites. Each op has two halves:
# an in-process fold-then-refold check (a loop resolves the site, a writer
# op republishes the type, the same site must answer the new fact), and a
# routing check through a child process under NQP_OP_CENSUS=1 (the site's
# calls move; the classlib name the op used to travel under is absent).
#
# The child-process helper is the one of t/jvm/20-op-census.t, copied: nqp
# test files are standalone, and the two files assert different things.
# This file spawns ./nqp-j-gradle (the gradle-generated runner) relative
# to the nqp tree, so prove must run from there.

plan(6);

my class Queue is repr('ConcBlockingQueue') { }
my class VMDecoder is repr('Decoder') { }
my sub create_buf($type) {
    my $buf := nqp::newtype(nqp::null(), 'VMArray');
    nqp::composetype($buf, nqp::hash('array', nqp::hash('type', $type)));
    $buf
}

# Runs ./nqp-j-gradle -e $code as a child under %env; returns [status, stderr].
sub child-stderr($code, %env) {
    my $queue := nqp::create(Queue);
    my $done := 0; my $out-eof := 0; my $err-eof := 0; my $status := -1;
    my @err;
    my $config := nqp::hash(
        'done', -> $st { $status := $st; $done := 1 },
        'ready', -> $stdin?, $stdout?, $stderr? { },
        'stdout_bytes', -> $seq, $data, $err { $out-eof := 1 unless nqp::isconcrete($data) },
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
    my $dec := nqp::create(VMDecoder);
    nqp::decoderconfigure($dec, 'utf8', nqp::hash());
    for @err -> $bytes { nqp::decoderaddbytes($dec, $bytes) if nqp::isconcrete($bytes) }
    nqp::list($status, nqp::decodertakeallchars($dec))
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

# "  site <Site> calls=N misses=N ..." -> the field, or -1.
sub site-field($text, $site, $field) {
    for nqp::split("\n", $text) -> $line {
        if nqp::index($line, '  site ' ~ $site ~ ' ') == 0 {
            for nqp::split(' ', $line) -> $kv {
                return +nqp::substr($kv, nqp::chars($field) + 1) if nqp::index($kv, $field ~ '=') == 0;
            }
        }
    }
    -1
}

my %on := nqp::getenvhash();
%on<NQP_OP_CENSUS> := '1';

# ---- iscont ------------------------------------------------------------
# The container spec is a fact of the type's state: a site folds 0 for a
# plain class, and setcontspec republishes the type, so the same site must
# answer 1 afterwards without a fresh site.
class ContFoo { }
sub is_cont($o) { nqp::iscont($o) }
my $cf := ContFoo.new;
my $c := -1;
my $i := 0;
while $i < 200 { $c := is_cont($cf); $i++ }
is($c, 0, 'iscont folds 0 for a plain class');
nqp::setcontspec(ContFoo, 'code_pair', nqp::hash('fetch', -> $cont { 42 }, 'store', -> $cont, $v { }));
is(is_cont($cf), 1, 'an iscont site resolved before setcontspec sees the container spec');

my $cont-child := child-stderr(
    'class Foo { }; my $o := Foo.new; my $i := 0; my $n := 0; while $i < 1000 { $n := $n + nqp::iscont($o); $i++ }; say($n)',
    %on);
is($cont-child[0], 0, 'the iscont child exits 0');
my $cont-err := $cont-child[1];
ok(site-field($cont-err, 'IsContSite', 'calls') >= 1000, 'iscont reaches IsContSite');
ok(count-of($cont-err, 'classlib', 'Ops.iscont') < 0, 'iscont no longer travels the classlib road');
ok(site-field($cont-err, 'IsContSite', 'misses') == 0, 'a monomorphic iscont loop never misses');
