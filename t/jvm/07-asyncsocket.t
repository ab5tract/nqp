plan(8);

# Loopback exercise of the async socket stack: asynclisten/asyncconnect/
# asyncwritebytes/asyncreadbytes (AsyncServerSocketHandle/AsyncSocketHandle).
# Event lists are shifted straight off ConcBlockingQueues; callbacks stay
# anonymous and push nothing (all data arrives via the event lists).

my class Queue is repr('ConcBlockingQueue') { }
my class Task is repr('AsyncTask') { }

my $bufType := nqp::newtype(nqp::null(), 'VMArray');
nqp::composetype($bufType, nqp::hash('array', nqp::hash('type', uint8)));

my $noop := -> *@args { };

# --- listen ---
my $lq := nqp::create(Queue);
my $listen-task := nqp::asynclisten($lq, $noop, '127.0.0.1', 0, 4, Task);
my $levent := nqp::shift($lq);
my $port := nqp::unbox_i(nqp::atpos($levent, 7));
ok($port > 0 && nqp::unbox_s(nqp::atpos($levent, 6)) eq '127.0.0.1',
    'asynclisten reports the bound host and an ephemeral port (' ~ $port ~ ')');

# --- connect ---
my $cq := nqp::create(Queue);
nqp::asyncconnect($cq, $noop, '127.0.0.1', $port, Task);
my $cevent := nqp::shift($cq);
my $conn := nqp::atpos($cevent, 1);
ok(nqp::isconcrete($conn) && !nqp::isconcrete(nqp::atpos($cevent, 2)),
    'asyncconnect delivers a concrete handle and no error');

# --- accept ---
my $aevent := nqp::shift($lq);
my $accepted := nqp::atpos($aevent, 1);
ok(nqp::isconcrete($accepted), 'the listener delivers the accepted connection');
ok(nqp::unbox_i(nqp::atpos($aevent, 4)) > 0, 'the accept event carries the peer port');

# --- write from connector, read on accepted side ---
my $payload := nqp::encode('hello sockets', 'utf8', nqp::create($bufType));
my $expected := nqp::elems($payload);

my $rq := nqp::create(Queue);
nqp::asyncreadbytes($accepted, $rq, $noop, $bufType, Task);

my $wq := nqp::create(Queue);
nqp::asyncwritebytes($conn, $wq, $noop, $payload, Task);
my $wevent := nqp::shift($wq);
ok(nqp::isconcrete(nqp::atpos($wevent, 1))
    && nqp::unbox_i(nqp::atpos($wevent, 1)) == $expected,
    'asyncwritebytes reports all payload bytes written');

my $revent := nqp::shift($rq);
my $got := nqp::atpos($revent, 2);
my $match := nqp::isconcrete($got) && nqp::elems($got) == $expected;
if $match {
    my int $i := 0;
    while $i < $expected {
        $match := 0 unless nqp::atpos_i($got, $i) == nqp::atpos_i($payload, $i);
        $i++;
    }
}
ok($match, 'asyncreadbytes delivers the exact payload bytes');
ok(nqp::unbox_i(nqp::atpos($revent, 1)) == 0, 'the first read event carries sequence number 0');

# --- close the writer; reader sees EOF ---
nqp::closefh($conn);
my $eevent := nqp::shift($rq);
ok(!nqp::isconcrete(nqp::atpos($eevent, 2)) && !nqp::isconcrete(nqp::atpos($eevent, 3)),
    'closing the peer delivers an EOF event with no error');

nqp::cancel($listen-task);
nqp::closefh($accepted);
