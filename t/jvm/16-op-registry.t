# The JVM op registry, pinned. Both answers here are read by HLL code to
# decide how to compile something, and both lost their table once before
# (milestone 4: the add_core_op closures that seeded them were deleted with
# the class road, and nothing between that change and the milestone gate
# exercised either consumer). is_inlinable is the worse of the two to get
# wrong silently: RakuAST's IMPL-INLINE-INFO refuses to inline a routine
# containing a non-inlinable op, so a table that answers 0 for add_i turns
# routine inlining and native arithmetic lowering off across a whole
# setting build without failing anything.

plan(17);

my $ops := nqp::getcomp('QAST').operations;
my $nqp := nqp::getcomp('nqp');

sub inlinable($op) { $ops.is_inlinable('nqp', $op) ?? 1 !! 0 }

# Inlinable: ops the encoder compiles and that do not act on the frame
# they are compiled into. add_i is the canary for native lowering.
ok(inlinable('add_i')      == 1, 'add_i is inlinable');
ok(inlinable('if')         == 1, 'if is inlinable');
ok(inlinable('list')       == 1, 'list is inlinable');
ok(inlinable('callmethod') == 1, 'callmethod is inlinable');

# Not inlinable: each of these acts on the frame it is compiled into, so
# an inlined copy would act on the inliner's frame.
ok(inlinable('call')    == 0, 'call is not inlinable');
ok(inlinable('handle')  == 0, 'handle is not inlinable');
ok(inlinable('ctx')     == 0, 'ctx is not inlinable');
ok(inlinable('syscall') == 0, 'syscall is not inlinable');

# An op no backend knows is not inlinable either.
ok(inlinable('nosuchop') == 0, 'an unknown op is not inlinable');

# An HLL's own answer wins over the core one.
$ops.set_hll_op_inlinability('regtest', 'add_i', 0);
ok($ops.is_inlinable('regtest', 'add_i') == 0, 'an HLL override beats the core answer');
ok(inlinable('add_i') == 1, 'and does not leak into another HLL');

# supports-op: the encoder's hand rows ($extra_ops in TruffleEncoder.nqp)
# and the classlib registry, which is also is_inlinable's last resort.
ok($nqp.supports-op('index'),            'supports-op: index');
ok($nqp.supports-op('sprintf'),          'supports-op: sprintf');
ok($nqp.supports-op('settypefinalize'),  'supports-op: settypefinalize');
ok($nqp.supports-op('dispatch_v'),       'supports-op: dispatch_v (NativeCall probes this one)');
ok($nqp.supports-op('handlepayload'),    'supports-op: handlepayload');
ok(!$nqp.supports-op('nosuchop'),        'supports-op says no to an op nothing has a row for');
