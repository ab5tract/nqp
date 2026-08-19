# Flattens a QAST::Regex tree into the descriptor the Truffle engine
# decodes (see nqp-truffle's RxDescriptor). The backend already holds the
# grammar as QAST::Regex by the time it emits code, so the engine never
# parses anything: this walk happens once, at compile time, and what it
# produces is a plain int array plus a pool of strings -- the cheapest
# thing to hand across, and it keeps NQP types out of the engine.
#
# Only the rxtypes the engine covers are encoded. Anything else answers a
# null, and the caller keeps the bytecode path for that regex; that is what
# lets the engine be switched on for the rules it can already handle while
# the rest go on working.

class QAST::RxDescriptor {
    # Tags, matching RxDescriptor.java.
    my int $SEQ     := 1;
    my int $ALT     := 2;
    my int $LITERAL := 3;
    my int $CCLASS  := 4;
    my int $ENUM    := 5;
    my int $RANGE   := 6;
    my int $ANCHOR  := 7;
    my int $QUANT   := 8;
    my int $SUB     := 9;
    my int $CAPTURE := 10;
    my int $SCAN    := 11;

    my int $F_NEGATE     := 1;
    my int $F_ZEROWIDTH  := 2;
    my int $F_IGNORECASE := 4;

    # cclass names to the engine's predicate kinds.
    my %cclass := nqp::hash(
        '.',  0,   # any
        'd',  1,
        's',  2,
        'w',  3,
        'n',  4,
        'h',  5,
        'v',  6,
    );

    # anchor subtypes to Anchor.Kind's order.
    my %anchor := nqp::hash(
        'bos',  0,
        'eos',  1,
        'bol',  2,
        'eol',  3,
        'lwb',  4,
        'rwb',  5,
    );

    has @!code;
    has @!pool;
    has int $!bailed;

    # The descriptor for a QAST::Regex tree, or null when it uses something
    # the engine does not implement yet.
    method encode($node) {
        my $self := QAST::RxDescriptor.new;
        nqp::bindattr($self, QAST::RxDescriptor, '@!code', []);
        nqp::bindattr($self, QAST::RxDescriptor, '@!pool', []);
        $self.walk($node);
        $self.bailed ?? nqp::null() !! $self
    }

    method bailed() { $!bailed }

    method code() { @!code }

    method pool() { @!pool }

    method emit(int $value) { nqp::push_i(@!code, $value) }

    method constant(str $value) {
        nqp::push(@!pool, $value);
        nqp::elems(@!pool) - 1
    }

    method bail() { $!bailed := 1 }

    method flags($node) {
        my int $flags := 0;
        $flags := $flags + $F_NEGATE if $node.negate;
        $flags := $flags + $F_ZEROWIDTH if $node.subtype eq 'zerowidth';
        my str $subtype := $node.subtype;
        $flags := $flags + $F_IGNORECASE
            if $subtype eq 'ignorecase' || $subtype eq 'ignorecase+ignoremark';
        $flags
    }

    # The children that put something in the descriptor. A node that
    # encodes to nothing cannot be counted by its parent.
    method encodable(@nodes) {
        my @kept;
        for @nodes {
            my str $rxtype := $_.rxtype // 'concat';
            nqp::push(@kept, $_)
                unless $rxtype eq 'pass' || $rxtype eq 'dba';
        }
        @kept
    }

    method walk($node) {
        return nqp::null if $!bailed;
        my str $rxtype := $node.rxtype // 'concat';

        if $rxtype eq 'concat' {
            # pass and dba encode to nothing, so they must not be counted;
            # a child count that disagrees with what follows would be read
            # as a malformed descriptor.
            my @kept := self.encodable(@($node));
            self.emit($SEQ);
            self.emit(nqp::elems(@kept));
            self.walk($_) for @kept;
        }
        elsif $rxtype eq 'altseq' || $rxtype eq 'alt' {
            my @kept := self.encodable(@($node));
            self.emit($ALT);
            self.emit(nqp::elems(@kept));
            self.walk($_) for @kept;
        }
        elsif $rxtype eq 'literal' {
            # An ignoremark literal needs the mark-insensitive comparisons
            # the bytecode path calls into; not encoded.
            my str $subtype := $node.subtype;
            return self.bail
                if $subtype eq 'ignoremark' || $subtype eq 'ignorecase+ignoremark';
            self.emit($LITERAL);
            self.emit(self.constant(~$node[0]));
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'cclass' {
            my str $name := $node.name;
            return self.bail unless nqp::existskey(%cclass, $name);
            self.emit($CCLASS);
            self.emit(%cclass{$name});
            self.emit($node.negate ?? 1 !! 0);
        }
        elsif $rxtype eq 'enumcharlist' {
            self.emit($ENUM);
            self.emit(self.constant(~$node[0]));
            self.emit($node.negate ?? 1 !! 0);
        }
        elsif $rxtype eq 'charrange' {
            self.emit($RANGE);
            self.emit(nqp::ord(~$node[1]));
            self.emit(nqp::ord(~$node[2]));
            self.emit($node.negate ?? 1 !! 0);
        }
        elsif $rxtype eq 'anchor' {
            my str $subtype := $node.subtype;
            return self.bail unless nqp::existskey(%anchor, $subtype);
            self.emit($ANCHOR);
            self.emit(%anchor{$subtype});
        }
        elsif $rxtype eq 'quant' {
            # A separator or a dynamic bound both mean more than the engine
            # encodes so far.
            return self.bail if nqp::elems(@($node)) > 1;
            self.emit($QUANT);
            self.emit($node.min);
            self.emit($node.max);
            self.emit($node.backtrack eq 'f' ?? 0 !! 1);
            self.walk($node[0]);
        }
        elsif $rxtype eq 'subrule' {
            # Only a plain named call; a subrule with arguments, or one
            # calling a variable, keeps the bytecode path.
            return self.bail unless nqp::istype($node[0][0], QAST::SVal)
                && nqp::elems($node[0].list) == 1;
            self.emit($SUB);
            self.emit(self.constant($node[0][0].value));
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'scan' {
            # Every NQP regex is wrapped in one of these.
            self.emit($SCAN);
            self.walk($node[0]);
        }
        elsif $rxtype eq 'pass' {
            # The engine answers the position it reached, and the caller
            # tells the cursor; nothing to encode.
        }
        elsif $rxtype eq 'dba' {
            # Only a name for error messages.
        }
        elsif $rxtype eq 'ws' {
            # A normal subrule call, which is how the bytecode path treats
            # it too.
            self.emit($SUB);
            self.emit(self.constant('ws'));
            self.emit(0);
        }
        elsif $rxtype eq 'subcapture' {
            self.emit($CAPTURE);
            self.emit(self.constant(~$node.name));
            self.walk($node[0]);
        }
        else {
            # qastnode, dynquant, goal, conj and the rest: the bytecode path
            # still owns these.
            self.bail;
        }
        nqp::null
    }
}
