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
    my int $ALT_LTM := 12;

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
    has str $!pass_name;
    has str $!bail_reason;
    has int $!scan;
    has int $!bailed;

    # The descriptor for a QAST::Regex tree, or null when it uses something
    # the engine does not implement yet.
    method encode($node) {
        my $self := QAST::RxDescriptor.new;
        # A native int array: the code is nothing but ints, and push_i needs
        # one -- a plain list answers "does not implement push_native".
        nqp::bindattr($self, QAST::RxDescriptor, '@!code', nqp::list_i());
        nqp::bindattr($self, QAST::RxDescriptor, '@!pool', []);
        nqp::bindattr_s($self, QAST::RxDescriptor, '$!pass_name', '');
        nqp::bindattr_i($self, QAST::RxDescriptor, '$!scan', 0);
        $self.inspect_pass($node);
        $self.walk($node);
        $self.bailed ?? nqp::null() !! $self
    }

    # What the rule does when it succeeds, and whether the engine may be the
    # one doing it.
    #
    # Two separate things are settled here, both from the pass node:
    #
    # 1. Whether the rule can be re-entered to yield its next match. The
    #    bytecode path keeps its choice points in the cursor's bstack, so a
    #    rule that passed with :backtrack can be resumed later and match
    #    differently. The engine's choice points live in one match call and
    #    are gone when it returns, so a resumed rule would answer its first
    #    match forever -- a wrong parse rather than a slow one. NQP's `token`
    #    and `rule` are ratcheted and never resumed; `regex` is not.
    #
    # 2. The name to pass to !cursor_pass, which is what makes it reduce and
    #    build the match tree. Dropping it would give a rule that matches the
    #    right span and reduces to nothing.
    method inspect_pass($node) {
        my $pass := self.find_pass($node);
        # No pass at all: not a whole rule body, so there is no defined thing
        # for the engine to hand back.
        return self.bail('no pass node') if nqp::isnull($pass);
        return self.bail('backtrackable rule') unless $pass.backtrack eq 'r';
        if $pass.name() {
            $!pass_name := $pass.name();
        }
        elsif nqp::elems(@($pass)) == 1 {
            # A computed name, known only while the rule runs.
            return self.bail('computed pass name');
        }
    }

    method find_pass($node) {
        return nqp::null() unless nqp::istype($node, QAST::Regex);
        my str $rxtype := $node.rxtype // 'concat';
        return $node if $rxtype eq 'pass';
        for @($node) {
            if nqp::istype($_, QAST::Regex) {
                my $found := self.find_pass($_);
                return $found unless nqp::isnull($found);
            }
        }
        nqp::null()
    }

    method pass_name() { $!pass_name }

    method scan() { $!scan }

    method bailed() { $!bailed }

    method code() { @!code }

    method pool() { @!pool }

    # The descriptor as one string, which is what the class file carries: a
    # string constant costs one ldc at every regex, where rebuilding the
    # arrays would cost code proportional to their length. The engine reads
    # the same format back -- see RxWire.java, which this must agree with.
    #
    # Pool entries are length-prefixed rather than delimited because a regex
    # may match any character at all, including whichever one a delimiter
    # would have been.
    method encoded() {
        my @out;
        nqp::push(@out, 'rxd ');
        nqp::push(@out, ~$!scan);
        nqp::push(@out, ' ');
        nqp::push(@out, ~nqp::elems(@!code));
        for @!code {
            nqp::push(@out, ' ');
            nqp::push(@out, ~$_);
        }
        nqp::push(@out, ' ');
        nqp::push(@out, ~nqp::elems(@!pool));
        for @!pool {
            self.chunk(@out, ~$_);
        }
        # What !cursor_pass is called with; empty means no reduce.
        self.chunk(@out, $!pass_name);
        nqp::join('', @out)
    }

    method chunk(@out, str $entry) {
        nqp::push(@out, ' ');
        nqp::push(@out, ~nqp::chars($entry));
        nqp::push(@out, ':');
        nqp::push(@out, $entry);
    }

    method emit(int $value) { nqp::push_i(@!code, $value) }

    method constant(str $value) {
        nqp::push(@!pool, $value);
        nqp::elems(@!pool) - 1
    }

    # Refuses the rule, recording what stopped it.
    #
    # The reason is the whole point: a silent bail is indistinguishable from
    # a rule the engine handles, and the difference between those two is
    # exactly what says how much of a grammar has actually been taken over.
    # NQP_RX_BAIL=1 prints them.
    method bail($why = 'unknown') {
        unless $!bailed {
            $!bailed := 1;
            $!bail_reason := $why;
            nqp::say('rx bail: ' ~ $why)
                if nqp::existskey(nqp::getenvhash(), 'NQP_RX_BAIL');
        }
        nqp::null()
    }

    method bail_reason() { $!bail_reason }

    # A plain named rule call. A subrule with arguments, or one calling a
    # variable, keeps the bytecode path.
    method subrule_call($node) {
        return self.bail('subrule without a literal name')
            unless nqp::istype($node[0], QAST::Node)
                && nqp::elems($node[0].list) == 1
                && nqp::istype($node[0][0], QAST::SVal);
        self.emit($SUB);
        self.emit(self.constant($node[0][0].value));
        self.emit(self.flags($node));
        # A capturing subrule's own cursor is the capture.
        self.emit($node.subtype eq 'capture'
            ?? self.constant(~$node.name) + 1
            !! 0);
    }

    # negate/zerowidth/ignorecase as one int. Character classes carry this
    # in place of a bare negate: a zero-width class such as <?[{]> must LOOK
    # and not consume, and encoding one as consuming makes every rule after
    # it run one character too far -- which reads as the grammar rejecting
    # valid input, a long way from the cause.
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
            unless nqp::istype($_, QAST::Regex) {
                self.bail('non-regex child');
                return @kept;
            }
            my str $rxtype := $_.rxtype // 'concat';
            nqp::push(@kept, $_)
                unless $rxtype eq 'pass' || $rxtype eq 'dba';
        }
        @kept
    }

    method walk($node) {
        return nqp::null if $!bailed;
        # A missing child, or one that is not a regex node at all: the engine
        # has no reading of either, so the rule keeps the bytecode path.
        return self.bail('non-regex node') unless nqp::istype($node, QAST::Regex);
        my str $rxtype := $node.rxtype // 'concat';

        if $rxtype eq 'concat' {
            # pass and dba encode to nothing, so they must not be counted;
            # a child count that disagrees with what follows would be read
            # as a malformed descriptor.
            my @kept := self.encodable(@($node));

            # The scan marker is a SIBLING at the start of a rule body, not
            # a wrapper: it carries no regex child, only an optional literal
            # prefix to look for. It means "if the body fails here, try again
            # one character along" -- and, crucially, it only does that when
            # the INVOCANT's $!from is -1, which is true for a top-level
            # parse and false for every subrule call.
            #
            # That is a loop over start positions with a cursor attribute to
            # update, not something the program can express, so it is
            # recorded as a property of the rule and run around the program
            # rather than inside it.
            if nqp::elems(@kept) && (@kept[0].rxtype // '') eq 'scan' {
                $!scan := 1;
                nqp::shift(@kept);
            }

            self.emit($SEQ);
            self.emit(nqp::elems(@kept));
            self.walk($_) for @kept;
        }
        elsif $rxtype eq 'alt' && $node.name {
            # Longest-token match: the branch order comes from an NFA the
            # grammar carries, not from the source order. The engine asks the
            # cursor for that order at match time -- the same '!alt' the
            # bytecode path calls -- so only the name and the branches need
            # encoding here. A named alt in a ratcheted rule commits to its
            # branch, which QAST::Compiler does with regex_commit.
            # The branches go in verbatim, NOT through encodable(): the NFA
            # numbers its answers by position in the source branch list, and
            # dropping one would shift every index after it, so the engine
            # would run a different branch than the NFA chose. If a branch is
            # not a plain regex node, the whole rule has to go.
            my @kept := @($node);
            for @kept {
                return self.bail('unencodable alternation branch')
                    unless nqp::istype($_, QAST::Regex)
                        && ($_.rxtype // 'concat') ne 'pass'
                        && ($_.rxtype // 'concat') ne 'dba';
            }
            self.emit($ALT_LTM);
            self.emit(self.constant(~$node.name));
            self.emit($node.backtrack eq 'r' ?? 1 !! 0);
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
            return self.bail('ignoremark literal')
                if $subtype eq 'ignoremark' || $subtype eq 'ignorecase+ignoremark';
            self.emit($LITERAL);
            self.emit(self.constant(~$node[0]));
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'cclass' {
            my str $name := $node.name;
            return self.bail('cclass ' ~ $name) unless nqp::existskey(%cclass, $name);
            self.emit($CCLASS);
            self.emit(%cclass{$name});
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'enumcharlist' {
            self.emit($ENUM);
            self.emit(self.constant(~$node[0]));
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'charrange' {
            # The bounds arrive as IVals holding codepoints, not as text.
            return self.bail('charrange without literal bounds')
                unless nqp::istype($node[1], QAST::IVal)
                    && nqp::istype($node[2], QAST::IVal);
            self.emit($RANGE);
            self.emit($node[1].value);
            self.emit($node[2].value);
            self.emit(self.flags($node));
        }
        elsif $rxtype eq 'anchor' {
            my str $subtype := $node.subtype;
            return self.bail('anchor ' ~ $subtype) unless nqp::existskey(%anchor, $subtype);
            self.emit($ANCHOR);
            self.emit(%anchor{$subtype});
        }
        elsif $rxtype eq 'quant' {
            # A separator or a dynamic bound both mean more than the engine
            # encodes so far.
            return self.bail('quant with separator') if nqp::elems(@($node)) > 1;
            # 'f' is frugal (lazy), 'r' is ratcheted -- it keeps what it
            # took and never gives any of it back. A token makes every
            # quantifier in it ratcheted, so this flag is what most of a
            # grammar actually needs; reading it as ordinary greedy accepts
            # input the bytecode path rejects.
            self.emit($QUANT);
            self.emit($node.min);
            self.emit($node.max);
            self.emit($node.backtrack eq 'f' ?? 0 !! 1);
            self.emit($node.backtrack eq 'r' ?? 1 !! 0);
            self.walk($node[0]);
        }
        elsif $rxtype eq 'subrule' {
            self.subrule_call($node);
        }
        elsif $rxtype eq 'scan' {
            # Handled where it means something -- as the first child of the
            # rule body's concat. Anywhere else there is nothing for it to
            # apply to, and guessing would be worse than declining.
            return self.bail('scan away from the start');
        }
        elsif $rxtype eq 'pass' {
            # The engine answers the position it reached, and the caller
            # tells the cursor; nothing to encode.
        }
        elsif $rxtype eq 'dba' {
            # Only a name for error messages.
        }
        elsif $rxtype eq 'ws' {
            # A normal subrule call, which is how the bytecode path treats it
            # too (QAST::Compiler.ws is `self.subrule($node)`). The name has
            # to come from the node rather than be assumed to be 'ws': a
            # grammar can say what <.ws> calls, and calling the wrong rule
            # would silently skip the wrong things.
            self.subrule_call($node);
        }
        elsif $rxtype eq 'subcapture' {
            # The span becomes a cursor when it is captured, the way
            # !cursor_start_subcapture does it for the bytecode path; the
            # engine only has to say which span, and when.
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
