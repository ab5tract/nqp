# NQP_DISPATCH_STATS=1 prints the dispatch counters at exit, with a
# misses-by-dispatcher histogram (milestone 7, A2). JVM only.
#
# The child runs under /bin/sh so that the env var can be set for it:
# nqp::shell is not an encodable op (the engine refuses it) and
# run-command hands the child nqp::getenvhash unchanged.
plan(5);

if nqp::getcomp('nqp').backend.name ne 'jvm'
   || nqp::backendconfig()<osname> eq 'MSWin32' {
    skip('dispatch stats are a JVM/Truffle counter, read through /bin/sh', 5);
}
else {
    my $args := nqp::list('/bin/sh', '-c',
        "NQP_DISPATCH_STATS=1 '" ~ nqp::execname() ~ "' -e 'say(1)'");
    my @out  := run-command($args, :stderr);
    my $text := @out[2];
    ok(nqp::index($text, 'dispatch stats: hits=') >= 0, 'the stats line is printed at exit');
    ok(nqp::index($text, ' misses=') >= 0,               'it carries the miss total');
    ok(nqp::index($text, "\n  misses ") >= 0,            'a per-dispatcher misses line follows it');
    ok(nqp::index($text, ' slowLayout=') >= 0,           'AttrSrc slow evaluations are split by cause');

    # NQP_CLASSLIB_INLINE=1 restores the pre-boundary classlib road (op
    # bodies inlined into the caller's compilation unit). Nothing else in
    # the suite runs with it, so the knob rots unnoticed unless one run
    # here proves it still starts, prints and exits clean.
    my $inline-args := nqp::list('/bin/sh', '-c',
        "NQP_CLASSLIB_INLINE=1 '" ~ nqp::execname() ~ "' -e 'say(1)'" ~ '; echo rc=$?');
    my @inline := run-command($inline-args, :stdout, :stderr);
    unless ok(nqp::index(@inline[1], "1\nrc=0") >= 0,
              'NQP_CLASSLIB_INLINE=1 still runs the classlib road') {
        say('# out: ' ~ @inline[1]);
        say('# err: ' ~ @inline[2]);
    }
}
