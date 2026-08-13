plan(10);

# Exercises AsyncFileHandle via the openasync/spurtasync/slurpasync/linesasync
# ops. Callbacks fire on IO completion threads; a ConcBlockingQueue provides
# the synchronization (blocking nqp::shift), as in 111-spawnprocasync.t.
#
# NOTE: the callbacks only push values that were boxed on the main thread (or
# arrive pre-boxed from the handle). Calling a named file-scope sub from a
# callback thread does not work: it compiles to a static-code-ref invocation
# whose outer frame chain is auto-vivified fresh on the foreign thread, so
# mainline lexicals read as null there.

my class Queue is repr('ConcBlockingQueue') { }
my $strType := nqp::hllboxtype_s();
my $tmpfile := 't/jvm/05-asyncfile.tmp';
my $content := "first line\nsecond line\nthird";

my $SPURT-DONE := nqp::box_s('spurt-done', $strType);
my $LINES-DONE := nqp::box_s('===DONE===', $strType);

# --- spurtasync ---
my $q := nqp::create(Queue);
my $wfh := nqp::openasync($tmpfile, 'w');
nqp::spurtasync($wfh, $strType, nqp::box_s($content, $strType),
    -> { nqp::push($q, $SPURT-DONE) },
    -> $err { nqp::push($q, $err) });
ok(nqp::unbox_s(nqp::shift($q)) eq 'spurt-done', 'spurtasync invoked the done callback');
nqp::closefh($wfh);

my $rt := open($tmpfile, :r, :!chomp);
my $written := $rt.slurp;
$rt.close;
ok($written eq $content, 'spurtasync wrote the expected bytes (sync read-back)');

# --- slurpasync ---
my $rfh := nqp::openasync($tmpfile, 'r');
nqp::slurpasync($rfh, $strType,
    -> $s { nqp::push($q, $s) },
    -> $err { nqp::push($q, $err) });
my $slurped := nqp::unbox_s(nqp::shift($q));
ok($slurped eq $content, 'slurpasync delivered the file content to done');
nqp::closefh($rfh);

# --- linesasync, chomp ---
# lines() pushes each boxed line onto the queue itself; the done callback
# pushes a pre-boxed sentinel afterwards, preserving order.
sub collect_lines($chomp) {
    my $lq := nqp::create(Queue);
    my $lfh := nqp::openasync($tmpfile, 'r');
    nqp::linesasync($lfh, $strType, $chomp, $lq,
        -> { nqp::push($lq, $LINES-DONE) },
        -> $err { nqp::push($lq, $err) });
    my @lines;
    my $line := nqp::unbox_s(nqp::shift($lq));
    while $line ne '===DONE===' {
        nqp::push(@lines, $line);
        $line := nqp::unbox_s(nqp::shift($lq));
    }
    nqp::closefh($lfh);
    @lines
}

my @chomped := collect_lines(1);
ok(nqp::elems(@chomped) == 3, 'linesasync produced three lines');
ok(@chomped[0] eq 'first line' && @chomped[1] eq 'second line',
    'linesasync chomped the line terminators');
ok(@chomped[2] eq 'third', 'the unterminated tail line is delivered');

my @raw := collect_lines(0);
ok(nqp::elems(@raw) == 3 && @raw[0] eq "first line\n",
    'linesasync without chomp keeps the terminator');

# --- error paths ---
my $died := 0;
try { nqp::openasync('t/jvm/no-such-dir/nope', 'r'); CATCH { $died := 1; } }
ok($died, 'openasync on a nonexistent path dies');

$died := 0;
try { nqp::openasync($tmpfile, 'bogus'); CATCH { $died := 1; } }
ok($died, 'openasync with an unhandled mode dies');

my $rfh2 := nqp::openasync($tmpfile, 'r');
nqp::slurpasync($rfh2, $strType,
    -> $s { nqp::push($q, $s) },
    -> $err { nqp::push($q, $err) });
my $again := nqp::unbox_s(nqp::shift($q));
ok($again eq $content, 'a second independent slurpasync also works');
nqp::closefh($rfh2);

nqp::unlink($tmpfile);
