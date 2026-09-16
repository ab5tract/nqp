# Milestone 8 Phase B, B0: the op census. NQP_OP_CENSUS=1 counts every
# table op by id, every classlib op by name and every site's calls and
# misses, printed at exit next to the dispatch stats. The knob is a static
# final read once per JVM, so the test runs CHILD processes with and
# without it and reads their stderr; the routing facts it asserts on are
# the ones batch 1's site tests will assert on the same way.
#
# The children are spawned as `./nqp-j-gradle` -- the runner gradle
# generates, resolved against the nqp tree (prove's cwd here). Under
# `make test` this file therefore exercises THAT runner, not whatever
# J_BUILD_RUNNER_BIN points the rest of the build at; a census that only
# works under one of the two would still pass here.

plan(8);

my class Queue is repr('ConcBlockingQueue') { }
my class VMDecoder is repr('Decoder') { }
my sub create_buf($type) {
    my $buf := nqp::newtype(nqp::null(), 'VMArray');
    nqp::composetype($buf, nqp::hash('array', nqp::hash('type', $type)));
    $buf
}

# Runs ./nqp-j-gradle -e $code as a child under %env; returns a list
# [$status, $stderr] -- the exit status the 'done' callback reports
# (exitcode shl 8, so 0 for a clean exit) and the decoded stderr. A child
# that died would otherwise read as an empty census block, which is a
# confusing way to learn the program failed.
# (prove runs this file with cwd = the nqp tree, where ./nqp-j-gradle is.)
sub child-stderr($code, %env) {
    my $queue := nqp::create(Queue);
    my $done := 0; my $out-eof := 0; my $err-eof := 0;
    my $status := -1;
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

# One program exercising all three roads 1000 times: a table op (sha1 has
# an encoder row), a classlib op (reprname arrives by name), and a site
# (istype, Phase A's). The examples must be ops NO site has claimed:
# tryfindmethod and findmethod stood here until batch 1 promoted them to
# FindMethodSite, at which point their old names left the census.
my $code := 'my $i := 0; my $n := 0; while $i < 1000 { $n := $n + nqp::istype($i, int); nqp::sha1("x"); nqp::reprname(NQPMu); $i++ }; say($n)';

my %on := nqp::getenvhash();
%on<NQP_OP_CENSUS> := '1';
my @on := child-stderr($code, %on);
my $err := @on[1];

is(@on[0], 0, 'the child under NQP_OP_CENSUS=1 exits clean');
ok(nqp::index($err, 'op census: table=') >= 0, 'the census block prints at exit under NQP_OP_CENSUS=1');

sub count-of($text, $prefix, $name) {
    # "  <prefix> <count> <name>" -> count, or -1
    for nqp::split("\n", $text) -> $line {
        my $lead := '  ' ~ $prefix ~ ' ';
        if nqp::index($line, $lead) == 0 {
            my @f := nqp::split(' ', nqp::substr($line, nqp::chars($lead)));
            return +@f[0] if @f[1] eq $name;
        }
    }
    -1
}
ok(count-of($err, 'table', 'sha1') >= 1000, 'a table op is counted by id and printed by name');
ok(count-of($err, 'classlib', 'Ops.reprname') >= 1000, 'a classlib op is counted by class and method name');

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
ok(site-field($err, 'IsTypeSite', 'calls') >= 1000, 'a site counts its calls');
ok(site-field($err, 'IsTypeSite', 'misses') >= 0, 'a site reports its misses');

# The knob is presence-based, so an ambient NQP_OP_CENSUS in this
# process's own environment would be inherited by the quiet child and
# turn its census on. Delete the key rather than set it to a false value.
my %off := nqp::getenvhash();
nqp::deletekey(%off, 'NQP_OP_CENSUS');
my @off := child-stderr($code, %off);

is(@off[0], 0, 'the child without the knob exits clean');
ok(nqp::index(@off[1], 'op census:') < 0, 'without the knob nothing is printed');
