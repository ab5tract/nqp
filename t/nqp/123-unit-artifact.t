# Compiles a module on the artifact road (NQP_UNIT=1, --target=jar) and
# loads it back with `use`: the jar must carry unit.meta and no class
# entry, and a sub, a closure over an outer, a handler and a regex from it
# must run.
#
# Both halves run in child processes: the compile because the road is
# chosen by an environment variable, and the load because `use` resolves
# the module's symbols while this file is being compiled, long before the
# jar exists.

plan(8);

my $is-windows := nqp::backendconfig()<osname> eq 'MSWin32';
if nqp::getcomp('nqp').backend.name ne 'jvm' || $is-windows {
    skip('the unit artifact road is JVM-only and driven through /bin/sh', 8);
}
else {
    my $dir := nqp::cwd() ~ '/t/nqp/123-unit-artifact.tmp';
    nqp::mkdir($dir, 0o777) unless nqp::stat($dir, nqp::const::STAT_EXISTS);
    my $src    := $dir ~ '/UnitMod.nqp';
    my $jar    := $dir ~ '/UnitMod.jar';
    my $driver := $dir ~ '/use-unitmod.nqp';

    sub spew($path, @lines) {
        my $fh := nqp::open($path, 'w');
        nqp::printfh($fh, nqp::join("\n", @lines) ~ "\n");
        nqp::closefh($fh);
    }

    spew($src, [
        'module UnitMod {',
        '    my $counter := 0;',
        '    our sub twice($x) { $x * 2 }',
        '    our sub counter() { $counter := $counter + 1; $counter }',
        '    our sub guarded($x) {',
        '        my $r := "no throw";',
        '        try { nqp::die("boom " ~ $x); CATCH { $r := "caught " ~ nqp::getmessage($_) } }',
        '        $r',
        '    }',
        '    our sub matches($s) { $s ~~ /^ \d+ $/ ?? 1 !! 0 }',
        '}',
    ]);

    # A stale jar from a previous run must not let this test pass on old
    # output: the compile below must be the thing that produces it.
    nqp::unlink($jar) if nqp::stat($jar, nqp::const::STAT_EXISTS);

    spew($driver, [
        'use UnitMod;',
        'say(UnitMod::twice(21));',
        'say(UnitMod::counter() ~ "," ~ UnitMod::counter());',
        'say(UnitMod::guarded("x"));',
        'say(UnitMod::matches("123"));',
        'say(UnitMod::matches("12a"));',
    ]);

    # t/nqp runs from the nqp checkout; the rakudo build drives the same
    # files from a directory up.
    my %env := nqp::getenvhash();
    my $runner := nqp::existskey(%env, 'NQP_TEST_RUNNER')
        ?? %env<NQP_TEST_RUNNER>
        !! (nqp::stat(nqp::cwd() ~ '/nqp-j-gradle', nqp::const::STAT_EXISTS)
            ?? './nqp-j-gradle' !! 'nqp/nqp-j-gradle');

    sub sh($command) {
        run-command(nqp::list('/bin/sh', '-c', $command), :stdout, :stderr)
    }

    my @compiled := sh("NQP_UNIT=1 $runner --target=jar --output=$jar $src");
    unless ok(nqp::stat($jar, nqp::const::STAT_EXISTS) == 1,
              'compiled the module on the artifact road') {
        say('# ' ~ @compiled[1]);
        say('# ' ~ @compiled[2]);
    }

    # A zip keeps its entry names in plain bytes, in the local headers and
    # again in the central directory, so reading the file as latin-1 shows
    # every name the jar carries.
    my $buftype := nqp::newtype(nqp::null(), 'VMArray');
    nqp::composetype($buftype, nqp::hash('array', nqp::hash('type', uint8)));
    my $fh := nqp::open($jar, 'r');
    my $bytes := nqp::readfh($fh, nqp::create($buftype), 64 * 1024 * 1024);
    nqp::closefh($fh);
    my $text := nqp::decode($bytes, 'iso-8859-1');
    ok(nqp::index($text, 'unit.meta') >= 0, 'the jar carries unit.meta');
    ok(nqp::index($text, '.class') < 0, 'the jar carries no class entry');

    my @ran := sh("$runner --module-path=$dir $driver");
    my @out := nqp::split("\n", @ran[1]);
    unless nqp::elems(@out) >= 5 {
        say('# ' ~ @ran[1]);
        say('# ' ~ @ran[2]);
    }
    is(@out[0] // '', '42',        'a sub from the artifact runs');
    is(@out[1] // '', '1,2',       'a closure over the mainline keeps its outer');
    is(@out[2] // '', 'caught boom x', 'a handler in an artifact block catches');
    is(@out[3] // '', '1',         'a regex from the artifact matches');
    is(@out[4] // '', '0',         'and fails to match');

    for [$src, $jar, $driver] { nqp::unlink($_) if nqp::stat($_, nqp::const::STAT_EXISTS) }
    nqp::rmdir($dir) if nqp::stat($dir, nqp::const::STAT_EXISTS);
}
