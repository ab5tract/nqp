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
    # NQP_RX_NO=a,b refuses named features that the encoder otherwise
    # handles.
    #
    # What a group of rxtypes is worth is not the number of rules that name
    # it first, nor the number of times it occurs: a rule moves to the engine
    # only when everything in it can be encoded, so the yield of a group is
    # how many rules it CLOSES OUT, and that can only be had by measuring
    # with and without it. Rebuilding nqp to find out costs two and a half
    # minutes; this costs a run.
    #
    #   anchor-const   the constant `pass` and `fail` anchors
    #   quant-sep      quantifiers with a separator, `a+ % ','`
    #   uniprop        Unicode property tests, <:Alpha>
    #   subrule-args   subrule calls carrying literal arguments
    #   goal           the `~` construct
    #   backtrack      backtrackable (non-ratcheted) rules without subrules
    #   subrule-callback  subrule calls carried as callback pieces (lexical
    #                     and other computed callees, computed arguments)
    #   qastnode       `{ ... }` and `<?{ ... }>` run back in the rule's frame
    my %rx_no;
    my int $rx_no_read := 0;
    sub rx_refuses(str $feature) {
        unless $rx_no_read {
            $rx_no_read := 1;
            my %env := nqp::getenvhash();
            if nqp::existskey(%env, 'NQP_RX_NO') {
                for nqp::split(',', %env<NQP_RX_NO>) { %rx_no{$_} := 1 }
            }
        }
        nqp::existskey(%rx_no, $feature)
    }

    # NQP_RX_TRY=a,b turns ON a feature that is written but not trusted.
    #
    # The opposite sense to NQP_RX_NO, deliberately: what sits behind this is
    # known to produce a wrong parse, so off has to be the default and turning
    # it on has to be an act of intent rather than an omission.
    #
    # (No feature currently sits here. qastnode graduated to NQP_RX_NO once
    # its wrong parse was traced to captures the cursor could not yet see:
    # the engine now syncs pending captures before a callback runs.)
    my %rx_try;
    my int $rx_try_read := 0;
    sub rx_tries(str $feature) {
        unless $rx_try_read {
            $rx_try_read := 1;
            my %env := nqp::getenvhash();
            if nqp::existskey(%env, 'NQP_RX_TRY') {
                for nqp::split(',', %env<NQP_RX_TRY>) { %rx_try{$_} := 1 }
            }
        }
        nqp::existskey(%rx_try, $feature)
    }

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
    my int $UNIPROP := 13;
    my int $QASTNODE := 14;
    my int $SUBCB   := 15;

    # Subrule argument kinds, matching RxDescriptor.java.
    my int $ARG_STR := 0;
    my int $ARG_INT := 1;

    my int $F_NEGATE     := 1;
    my int $F_ZEROWIDTH  := 2;
    my int $F_IGNORECASE := 4;

    # Ops that walk the frame or caller chain at run time; a callback piece
    # containing one refuses the rule (see reads_frame_ops).
    my %frame_ops := nqp::hash(
        'ctx', 1, 'ctxcaller', 1, 'ctxcallerskipthunks', 1,
        'ctxouter', 1, 'ctxouterskipthunks', 1,
        'curcode', 1, 'callercode', 1,
        'getlexdyn', 1, 'getlexreldyn', 1, 'getlexrelcaller', 1,
        'getlexcaller', 1, 'getlexouter', 1, 'getlexrel', 1,
        'savecapture', 1, 'usecapture', 1, 'takedispatcher', 1,
    );

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
        # The two constant assertions. QAST::Compiler spells `fail` as a jump
        # to the fail label and `pass` as no instructions at all -- an anchor
        # subtype it does not recognise simply holds.
        'pass', 6,
        'fail', 7,
    );

    has @!code;
    has @!pool;
    has @!callbacks;
    has str $!pass_name;
    has str $!bail_reason;
    has @!bail_reasons;
    has int $!scan;
    has int $!bailed;
    has int $!survey;
    has int $!backtrackable;
    has int $!has_sub;

    # The descriptor for a QAST::Regex tree, or null when it uses something
    # the engine does not implement yet.
    method encode($node) {
        my $self := QAST::RxDescriptor.new;
        # A native int array: the code is nothing but ints, and push_i needs
        # one -- a plain list answers "does not implement push_native".
        nqp::bindattr($self, QAST::RxDescriptor, '@!code', nqp::list_i());
        nqp::bindattr($self, QAST::RxDescriptor, '@!pool', []);
        nqp::bindattr($self, QAST::RxDescriptor, '@!bail_reasons', []);
        nqp::bindattr($self, QAST::RxDescriptor, '@!callbacks', []);
        nqp::bindattr_s($self, QAST::RxDescriptor, '$!pass_name', '');
        nqp::bindattr_i($self, QAST::RxDescriptor, '$!scan', 0);
        nqp::bindattr_i($self, QAST::RxDescriptor, '$!survey',
            nqp::existskey(nqp::getenvhash(), 'NQP_RX_SURVEY') ?? 1 !! 0);
        $self.inspect_pass($node);
        $self.walk($node);
        # Decided after the walk, which is what knows whether any subrule
        # was called: see inspect_pass for why the pairing is refused.
        $self.bail('backtrackable rule with subrules')
            if $self.backtrackable && $self.has_sub;
        # PROVISIONAL: a rule with very many callback pieces stays on the
        # bytecode path. The callback dispatch compiles into one generated
        # method, and rakudo's comp_unit -- sixty-odd `:my` pieces of
        # substantial code -- broke its emission ("JAST node isn't a
        # JAST::Class"; the JVM's 64KB method limit is the suspect). Such
        # once-per-parse rules gain nothing from the engine anyway. Lift
        # this by splitting the dispatch across methods if a hot rule ever
        # hits it; NQP_RX_SURVEY names what the cap refuses.
        $self.bail('qastnode-heavy rule ('
                ~ nqp::elems(nqp::getattr($self, QAST::RxDescriptor, '@!callbacks'))
                ~ ' pieces)')
            if nqp::elems(nqp::getattr($self, QAST::RxDescriptor, '@!callbacks')) > 16;
        $self.report if $self.survey;
        $self.bailed ?? nqp::null() !! $self
    }

    method backtrackable() { $!backtrackable }
    method has_sub() { $!has_sub }

    method survey() { $!survey }

    # One line per rule, naming EVERY reason it was refused rather than the
    # first.
    #
    # The first reason alone cannot say what a group of rxtypes is worth: a
    # rule refused for an anchor may hold four other things the engine does
    # not cover, so implementing anchors moves it from one bail to another
    # and buys nothing. What decides which group to do next is how many
    # rules a given SET of reasons accounts for, and that needs all of them.
    method report() {
        my str $name := $!pass_name eq '' ?? '<anon>' !! $!pass_name;
        if $!bailed {
            nqp::say('rx survey: ' ~ $name ~ ' BAIL ' ~ nqp::join(';', @!bail_reasons));
        }
        else {
            nqp::say('rx survey: ' ~ $name ~ ' OK');
        }
    }

    # What the rule does when it succeeds, and whether the engine may be the
    # one doing it.
    #
    # Two separate things are settled here, both from the pass node:
    #
    # 1. Whether the rule backtracks, and how far the engine can honor that.
    #    Backtracking INSIDE one match call is the engine's native
    #    discipline -- SPLIT choice points, greedy and frugal quantifiers --
    #    so a backtrackable rule as such is fine. What the engine cannot do
    #    is re-enter: the bytecode path keeps its choice points in the
    #    cursor's bstack, so a rule that passed with :backtrack can be
    #    resumed later (`!cursor_next`) and match differently, while the
    #    engine's choice points live in one match call and are gone when it
    #    returns. That bites in one place a parse meets routinely: a
    #    backtrackable rule that CALLS subrules must resume a subrule's own
    #    next match when it backtracks past it, and the engine can only
    #    re-run the call fresh. So that pairing is refused -- decided in
    #    encode, after the walk, which is when the subrule calls have been
    #    seen. A backtrackable rule with no subrule calls keeps every choice
    #    point internal and encodes. Resuming such a rule from OUTSIDE
    #    (exhaustive `:ex`-style matching of an already-passed cursor) still
    #    answers no further match; that is the remaining divergence, and
    #    `NQP_RX_NO=backtrack` refuses the whole feature for bisecting it.
    #
    # 2. The name to pass to !cursor_pass, which is what makes it reduce and
    #    build the match tree. Dropping it would give a rule that matches the
    #    right span and reduces to nothing.
    method inspect_pass($node) {
        my $pass := self.find_pass($node);
        # No pass at all: not a whole rule body, so there is no defined thing
        # for the engine to hand back.
        return self.bail('no pass node') if nqp::isnull($pass);
        # The name first, so that a rule refused for any other reason still
        # has one to be reported under.
        if $pass.name() {
            $!pass_name := $pass.name();
        }
        elsif nqp::elems($pass) == 1 {
            # A computed name, known only while the rule runs.
            self.bail('computed pass name');
        }
        unless $pass.backtrack eq 'r' {
            nqp::bindattr_i(self, QAST::RxDescriptor, '$!backtrackable', 1);
            self.bail('backtrackable rule (refused)') if rx_refuses('backtrack');
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

    # The QAST the engine has to come back into the rule's frame to run, one
    # entry per callback index. QAST::Compiler.engine_jast turns these into a
    # single block that switches on the index; the descriptor itself carries
    # only the index, since the code cannot be flattened into an int array.
    method callbacks() { @!callbacks }

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
        if $!survey {
            my int $seen := 0;
            for @!bail_reasons { $seen := 1 if $_ eq $why }
            nqp::push(@!bail_reasons, $why) unless $seen;
        }
        nqp::null()
    }

    method bail_reason() { $!bail_reason }

    # A named rule call, with any arguments it was written with.
    #
    # A call through a variable keeps the bytecode path: the engine has a name
    # to call, not a code object to invoke.
    method subrule_call($node) {
        # Noted for encode's backtrackable check, whatever comes of the call.
        nqp::bindattr_i(self, QAST::RxDescriptor, '$!has_sub', 1);

        my int $named := nqp::istype($node[0], QAST::Node)
            && nqp::elems($node[0]) >= 1
            && nqp::istype($node[0][0], QAST::SVal);

        # The arguments are collected BEFORE anything is emitted, because one
        # of them may decline and a half-written node would leave the code
        # array describing something that is not there. They still take pool
        # slots first, which costs nothing: the pool is indexed, not ordered.
        my @args := $named ?? self.subrule_args($node[0]) !! nqp::null;
        if $named && !nqp::isnull(@args) {
            self.emit($SUB);
            self.emit(self.constant($node[0][0].value));
            self.emit(self.flags($node));
            # A capturing subrule's own cursor is the capture.
            self.emit($node.subtype eq 'capture'
                ?? self.constant(~$node.name) + 1
                !! 0);
            self.emit(nqp::div_i(nqp::elems(@args), 2));
            self.emit($_) for @args;
            return 1;
        }

        # Anything the direct form cannot carry -- a lexical rule or other
        # computed callee, an argument that is not a source literal, a named
        # or flattened argument -- runs as a callback piece instead: the
        # whole invocation, evaluated back in the rule's own frame, exactly
        # the way the bytecode path's "normal invocation" arm evaluates it.
        # The callback dispatch has already bound $!pos and $\xa2 when the
        # piece runs, which is the same prologue the direct call gets, and
        # the piece's value is the subcursor.
        return self.bail($named
            ?? 'subrule with unencodable arguments (refused)'
            !! 'subrule via a variable (refused)')
            if rx_refuses('subrule-callback');

        # Same limits as qastnode: the piece lands in a block the backend
        # invents, which reaches the rule's lexicals but not its locals,
        # and runs one frame deeper than the inline call it replaces.
        my str $lowered := self.reads_outer_local($node[0], nqp::hash());
        return self.bail('subrule call over a lowered local ' ~ $lowered)
            if $lowered;
        my str $walker := self.reads_frame_ops($node[0], nqp::hash());
        return self.bail('subrule call walks the caller chain (' ~ $walker ~ ')')
            if $walker;

        my @callargs := nqp::clone($node[0].list);
        my $target := nqp::shift(@callargs);
        my $cursor := QAST::Var.new( :name("\$\xa2"), :scope('lexical') );
        my $piece := $named
            ?? QAST::Op.new( :op('callmethod'), :name(~$target.value), $cursor, |@callargs )
            !! QAST::Op.new( :op('call'), $target, $cursor, |@callargs );

        self.emit($SUBCB);
        self.emit(nqp::elems(@!callbacks));
        self.emit(self.flags($node));
        self.emit($node.subtype eq 'capture'
            ?? self.constant(~$node.name) + 1
            !! 0);
        nqp::push(@!callbacks, $piece);
    }

    # (kind, pool index) per argument, or null when one of them cannot travel.
    #
    # Only what the grammar's source says outright can be carried: a variable,
    # an expression or a block is evaluated in the rule's own frame, which the
    # engine has no access to. Literals are worth having because the rules
    # that report errors are almost all called with them -- <.panic('...')>,
    # <.obs('x', 'y')>, and the <.FAILGOAL(...)> every `~` ends in.
    # Answers null, without bailing, for anything the direct form cannot
    # carry -- the caller falls back to the callback form, which evaluates
    # the invocation in the rule's own frame and so takes any argument.
    method subrule_args($call) {
        my @args;
        my int $i := 1;
        my int $n := nqp::elems($call);
        return nqp::null if $n > 1 && rx_refuses('subrule-args');
        while $i < $n {
            my $arg := $call[$i];
            # A named or flattened argument arrives at the callee differently
            # from a positional one, and the call site the engine builds says
            # positional. Encoding one as the other would call the rule with
            # arguments it did not ask for.
            if $arg.named || $arg.flat {
                return nqp::null;
            }
            elsif nqp::istype($arg, QAST::SVal) {
                nqp::push(@args, $ARG_STR);
                nqp::push(@args, self.constant(~$arg.value));
            }
            elsif nqp::istype($arg, QAST::IVal) {
                # The pool is strings, so an int travels as its decimal text.
                nqp::push(@args, $ARG_INT);
                nqp::push(@args, self.constant(~$arg.value));
            }
            else {
                return nqp::null;
            }
            $i := $i + 1;
        }
        @args
    }

    # Whether a subtree reads a local it does not itself declare.
    #
    # A local lives in one frame and is named by slot, so a block that did not
    # declare it cannot name it at all. %seen carries the declarations found
    # so far, which is what tells a temporary the code made for itself from a
    # variable NQP::Optimizer lowered out of the rule's lexical scope.
    #
    # A nested QAST::Block is skipped: it has a frame of its own, and anything
    # local in it was declared in it.
    # Answers the NAME of the first such local, or '' for none. The name is
    # the whole value of this check: "a lowered local" does not say whether
    # the rule is unreachable or whether one well-known variable is in the
    # way of every codeblock alike.
    # Whether a subtree runs an op that walks the frame or caller chain at
    # run time. A callback piece runs one frame DEEPER than the inline code
    # it replaces -- inside the callback closure -- and these ops count
    # frames: nqp::getlexdyn starts at the CALLER by design, which inline
    # means "past the rule's own declaration to the rule's caller", and from
    # the closure means "at the rule itself". That is how nibbler's
    # `:my $OLDRX := nqp::getlexdyn('%*RX')` read back the rule's own fresh
    # hash instead of the enclosing rule's populated one, and every <sym>
    # downstream saw an empty %*RX. Ordinary lexical and contextual variable
    # access is compiled against the real nesting and stays correct; only
    # the explicit walkers shift meaning, so only they refuse the rule.
    # The list mirrors the FRAME-OPS set rakudo's var-lowering treats as
    # flatten-blockers, for the same reason in the other direction.
    method reads_frame_ops($node, %seen) {
        # QAST trees share subtrees; without the %seen guard this walk
        # re-traverses every shared node once per path to it, and on
        # BOOTSTRAP-sized pieces that ballooned the compile by gigabytes
        # in seconds. One visit per node identity is all the answer needs.
        my str $id := ~nqp::objectid($node);
        return '' if nqp::existskey(%seen, $id);
        nqp::bindkey(%seen, $id, 1);
        if nqp::istype($node, QAST::Op) && nqp::existskey(%frame_ops, $node.op) {
            return $node.op;
        }
        for @($node) {
            if nqp::istype($_, QAST::Node) {
                my str $found := self.reads_frame_ops($_, %seen);
                return $found if $found;
            }
        }
        ''
    }

    method reads_outer_local($node, %seen) {
        return '' unless nqp::istype($node, QAST::Node);
        return '' if nqp::istype($node, QAST::Block);
        if nqp::istype($node, QAST::Var) && $node.scope eq 'local' {
            my str $decl := $node.decl // '';
            if $decl eq 'var' || $decl eq 'param' {
                %seen{$node.name} := 1;
            }
            elsif !nqp::existskey(%seen, $node.name) {
                return $node.name;
            }
        }
        for @($node) {
            my str $found := self.reads_outer_local($_, %seen);
            return $found if $found;
        }
        ''
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
        # A survey wants every reason, so it keeps walking a rule that has
        # already been refused; what it emits is garbage, and is thrown away
        # with the descriptor.
        return nqp::null if $!bailed && !$!survey;
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
                unless nqp::istype($_, QAST::Regex)
                        && ($_.rxtype // 'concat') ne 'pass'
                        && ($_.rxtype // 'concat') ne 'dba' {
                    self.bail('unencodable alternation branch');
                    return nqp::null unless $!survey;
                }
            }
            @kept := self.encodable(@kept) if $!bailed && $!survey;
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
            return self.bail('anchor ' ~ $subtype)
                if ($subtype eq 'pass' || $subtype eq 'fail') && rx_refuses('anchor-const');
            self.emit($ANCHOR);
            self.emit(%anchor{$subtype});
        }
        elsif $rxtype eq 'quant' {
            # A separator is a second child -- `<digit>+ % ','`. It sits
            # BETWEEN two repetitions and is never trailing, which is what
            # the engine's program has to say too.
            my int $sep := nqp::elems($node) > 1 ?? 1 !! 0;
            if $sep {
                if rx_refuses('quant-sep') {
                    self.bail('quant with separator');
                    return nqp::null unless $!survey;
                }
                # Nothing in NQP builds a quant with more than a body and a
                # separator, so a third child means something this does not
                # understand rather than something it can guess at.
                if nqp::elems($node) > 2 {
                    self.bail('quant with more than a separator');
                    return nqp::null unless $!survey;
                }
            }
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
            self.emit($sep);
            self.walk($node[0]);
            self.walk($node[1]) if $sep;
        }
        elsif $rxtype eq 'qastnode' {
            # `{ ... }`, `<?{ ... }>`, `:my $x := ...`: arbitrary NQP code,
            # compiled by the bytecode path straight into the matcher's own
            # frame, where it can see the rule's lexicals. The engine is a
            # Java loop with no frame of its own, so instead of moving the
            # code it comes BACK for it: the code becomes one branch of a
            # block the rule hands over, and the descriptor carries only
            # which branch.
            # OFF by default, and it must stay that way until the failure
            # below is understood. Turn it on with NQP_RX_TRY=qastnode.
            #
            # What is known: with this enabled, nqp bootstraps and compiles
            # itself, but the first program compiled by a stage whose OWN code
            # carries descriptors mis-parses -- `package_def` reaches
            # `install_package_symbol` with an NQPMu where a capture should
            # be. Refusing qastnode alone makes that build green, so the
            # mechanism here is the cause and nothing else in the engine is.
            #
            # The constraint a fix has to satisfy, established rather than
            # guessed: lexical access here is DEPTH-INDEXED and resolved at
            # compile time -- as_jast(QAST::Var) walks BlockInfo.outer()
            # counting frames and emits an access at that depth. Compile-time
            # nesting and run-time frame nesting therefore have to correspond
            # exactly, and a codeblock is not bare code:
            # QRegex::P6Regex::Actions.codeblock wraps every `{ ... }` in a
            # nested QAST::Block of its own with blocktype('immediate'), whose
            # outer is found by a different mechanism from a closure's.
            #
            # A sound version may have to reuse THAT block as the callback,
            # turning it from immediate into a closure value, rather than wrap
            # it in a new one. That part is a hypothesis; do not build on it
            # without checking. The previous guess here was wrong --
            # NQP::Actions.variable_declarator hoists `my $x` into $BLOCK[0],
            # so declarations were never the problem.
            return self.bail('rxtype qastnode (refused)') if rx_refuses('qastnode');
            return self.bail('qastnode without a body') unless nqp::elems($node) == 1;

            # The block is nested inside the rule's, so it reaches the rule's
            # lexicals as a closure does -- but NOT its locals, and
            # NQP::Optimizer turns a lexical no inner block uses into exactly
            # that. It cannot know about a block the backend invents later,
            # so a rule whose code touches one of those has to keep the
            # bytecode path; encoding it would compile a reference to a local
            # that does not exist where the code ended up.
            my str $lowered := self.reads_outer_local($node[0], nqp::hash());
            return self.bail('qastnode over a lowered local ' ~ $lowered)
                if $lowered;
            my str $walker := self.reads_frame_ops($node[0], nqp::hash());
            return self.bail('qastnode walks the caller chain (' ~ $walker ~ ')')
                if $walker;

            self.emit($QASTNODE);
            self.emit(nqp::elems(@!callbacks));
            self.emit(self.flags($node));
            nqp::push(@!callbacks, $node[0]);
        }
        elsif $rxtype eq 'uniprop' {
            # <:Alpha>. The pair form, <:Block("Basic Latin")>, smartmatches
            # the property's VALUE against a matcher the cursor supplies, so
            # it is a call rather than a test and stays on the bytecode path.
            return self.bail('uniprop pair') unless nqp::elems($node) == 1;
            return self.bail('rxtype uniprop') if rx_refuses('uniprop');
            self.emit($UNIPROP);
            self.emit(self.constant(~$node[0]));
            self.emit(self.flags($node));
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
        elsif $rxtype eq 'goal' {
            # `'(' ~ ')' <thing>`. QAST::Compiler does not compile this at
            # all: it rewrites it into nodes it already has, and the rewrite
            # is what is copied here rather than reimplemented, so the two
            # cannot come to disagree about what `~` means.
            #
            # The third child is the rule that reports the missing goal --
            # <.FAILGOAL(')', 'argument list')> -- so nothing of this shape
            # can be encoded until a subrule can carry literal arguments.
            return self.bail('rxtype goal') if rx_refuses('goal');
            return self.bail('goal without three children')
                unless nqp::elems($node) == 3;
            self.walk(QAST::Regex.new(
                :rxtype<concat>,
                $node[1],
                QAST::Regex.new( :rxtype<altseq>, $node[0], $node[2] )
            ));
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
            # qastnode, dynquant, conj and the rest: the bytecode path still
            # owns these. Named, because "unknown" cannot say which rxtype is
            # worth implementing next.
            self.bail('rxtype ' ~ $rxtype);
            # A survey looks inside anyway: what else the rule holds decides
            # whether covering this rxtype would actually free it.
            if $!survey {
                for @($node) {
                    self.walk($_) if nqp::istype($_, QAST::Regex);
                }
            }
        }
        nqp::null
    }
}
