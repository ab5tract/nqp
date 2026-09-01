# Phase 1 of the jast2bc-to-Truffle migration (rakudo's
# docs/jvm-truffle-migration.md): measurement before movement. This walk
# classifies every code object a compilation unit emits against the op set
# the Truffle interpreter commits to covering first, and reports what share
# of each compile WOULD encode and what the top refusal reasons are.
# Nothing here changes what is emitted; the encoder proper grows into this
# class once the numbers say where to start.
#
# The rx engine's lessons are built in rather than relearned:
#
# - A rule (here: a code object) moves only when EVERYTHING in it encodes,
#   so the unit of measurement is blocks closed out, not ops implemented.
#   The survey records every reason a block bails, not just the first.
# - Changing the committed set must cost a run, not a rebuild (a QAST
#   rebuild is ~12 minutes end to end). NQP_QT_ALSO treats extra tags as
#   covered, NQP_QT_NO refuses covered ones; both take the tag spellings
#   the survey itself prints (op:handle, var:lexicalref, node:VM, regex).
#
# Knobs (all compile-time, read once; output is nqp::say with a "qt "
# prefix, greppable out of a build log the same way NQP_RX_SURVEY is):
#   NQP_QT_REPORT=1        per-compilation-unit summary and top bail tags
#   NQP_QT_SURVEY=1        one line per block with every reason it bails
#   NQP_QT_ALSO=a,b        treat these tags as covered for this run
#   NQP_QT_NO=a,b          treat these covered tags as refused
class QAST::QtEncoder {
    # The committed first tranche, from the Phase 1 op census (migration
    # doc, 2026-09-01): the ~20 census heads plus the structural, primitive
    # and rakudo ops that always travel with them. Exceptions and handlers
    # (op:handle and friends) are deliberately absent -- they are Phase 3's
    # ControlFlowException story, and their weight in the bail histogram is
    # the measurement that phase is scheduled on.
    my %covered;
    my int $init_done := 0;
    my int $qt_on := 0;
    my int $qt_report := 0;
    my int $qt_survey := 0;

    my $ops := 'if unless while until repeat_while repeat_until for bind
        call callmethod callstatic chain chainstatic locallifetime null
        say print
        hllize decont what create clone clone_nd defined
        isconcrete isnull istype eqaddr iscont iscont_i iscont_n iscont_s
        getattr getattr_i getattr_n getattr_s
        bindattr bindattr_i bindattr_n bindattr_s
        list list_i list_n list_s list_b hash
        atpos atpos_i atpos_n atpos_s bindpos bindpos_i bindpos_n bindpos_s
        atkey atkey_i atkey_n atkey_s bindkey bindkey_i bindkey_n bindkey_s
        existskey deletekey existspos elems setelems
        push pop shift unshift islist ishash
        add_i sub_i mul_i div_i mod_i neg_i abs_i
        bitand_i bitor_i bitxor_i bitshiftl_i bitshiftr_i bitneg_i not_i
        iseq_i isne_i islt_i isle_i isgt_i isge_i
        add_n sub_n mul_n div_n neg_n
        iseq_n isne_n islt_n isle_n isgt_n isge_n
        concat chars substr index eqat ord chr join split uc lc
        iseq_s isne_s islt_s isle_s isgt_s isge_s
        unbox_i unbox_n unbox_s box_i box_n box_s
        takeclosure getlexouter
        p6sink p6capturelex p6assign p6store p6bindattrinvres
        p6bool p6box_i p6box_n p6box_s';
    my $scopes := 'local lexical contextual attribute positional associative';

    sub init() {
        return 0 if $init_done;
        $init_done := 1;
        my %env := nqp::getenvhash();
        $qt_report := nqp::existskey(%env, 'NQP_QT_REPORT') ?? 1 !! 0;
        $qt_survey := nqp::existskey(%env, 'NQP_QT_SURVEY') ?? 1 !! 0;
        $qt_on := $qt_report || $qt_survey;
        return 0 unless $qt_on;

        for nqp::split(' ', subst_ws($ops))    { %covered{'op:' ~ $_} := 1 }
        for nqp::split(' ', subst_ws($scopes)) { %covered{'var:' ~ $_} := 1 }
        if nqp::existskey(%env, 'NQP_QT_ALSO') {
            for nqp::split(',', %env<NQP_QT_ALSO>) { %covered{$_} := 1 }
        }
        if nqp::existskey(%env, 'NQP_QT_NO') {
            for nqp::split(',', %env<NQP_QT_NO>) { nqp::deletekey(%covered, $_) }
        }
        1
    }

    # The op lists above are written wrapped; fold all whitespace runs to
    # single spaces so split sees one name per field.
    sub subst_ws($s) {
        my @parts;
        for nqp::split(' ', nqp::join(' ', nqp::split("\n", $s))) {
            nqp::push(@parts, $_) if $_ ne '';
        }
        nqp::join(' ', @parts)
    }

    method survey_cu($cu) {
        init();
        return 0 unless $qt_on;

        my %st := nqp::hash('blocks', nqp::list(), 'seen', nqp::hash());
        self.survey_block($cu[0], %st) if nqp::elems(@($cu)) && nqp::istype($cu[0], QAST::Block);
        if $cu.compilation_mode {
            for $cu.code_ref_blocks() {
                self.survey_block($_, %st);
            }
        }
        self.report($cu, %st);
        1
    }

    method survey_block($node, %st) {
        my str $cuid := $node.cuid;
        return 0 if nqp::existskey(%st<seen>, $cuid);
        nqp::bindkey(%st<seen>, $cuid, 1);
        my %blk := nqp::hash(
            'name', $node.name, 'cuid', $cuid,
            'nodes', 0, 'reasons', nqp::hash());
        nqp::push(%st<blocks>, %blk);
        self.tag(%blk, 'exit-handler') if $node.has_exit_handler;
        self.walk_children($node, %blk, %st);
        if $qt_survey {
            my str $name := %blk<name> eq '' ?? '<anon ' ~ $cuid ~ '>' !! %blk<name>;
            my @reasons;
            for %blk<reasons> { nqp::push(@reasons, $_.key) }
            nqp::say(nqp::elems(@reasons)
                ?? 'qt survey: ' ~ $name ~ ' BAIL ' ~ nqp::join(';', @reasons)
                !! 'qt survey: ' ~ $name ~ ' OK');
        }
        1
    }

    method tag(%blk, str $t) {
        nqp::bindkey(%blk<reasons>, $t, 1) unless nqp::existskey(%covered, $t);
    }

    method walk($node, %blk, %st) {
        nqp::bindkey(%blk, 'nodes', %blk<nodes> + 1);
        if nqp::istype($node, QAST::Op) {
            self.tag(%blk, 'op:' ~ $node.op);
            self.walk_children($node, %blk, %st);
        }
        elsif nqp::istype($node, QAST::Var) {   # VarWithFallback included
            self.tag(%blk, 'var:' ~ $node.scope);
            self.walk_children($node, %blk, %st);
        }
        elsif nqp::istype($node, QAST::Block) {
            # A nested block is its own code object; what the enclosing
            # block holds is only the closure/immediate reference, which
            # encodes.
            self.survey_block($node, %st);
        }
        elsif nqp::istype($node, QAST::Regex) {
            # The rx engine's business, not this encoder's; but qastnode
            # pieces and nested blocks inside the rule are real code and
            # are surveyed on the way through.
            self.tag(%blk, 'regex');
            self.walk_rx($node, %blk, %st);
        }
        elsif nqp::istype($node, QAST::Stmts) || nqp::istype($node, QAST::Stmt)
           || nqp::istype($node, QAST::Want) || nqp::istype($node, QAST::NodeList) {
            self.walk_children($node, %blk, %st);
        }
        elsif nqp::istype($node, QAST::IVal) || nqp::istype($node, QAST::NVal)
           || nqp::istype($node, QAST::SVal) || nqp::istype($node, QAST::BVal)
           || nqp::istype($node, QAST::WVal) {
            # Constants all encode.
        }
        elsif nqp::istype($node, QAST::VM) {
            # Backend-specific payloads (inline JAST); never encodable,
            # and the alternates inside are not QAST to walk.
            self.tag(%blk, 'node:VM');
        }
        else {
            self.tag(%blk, 'node:' ~ $node.HOW.name($node));
        }
    }

    method walk_children($node, %blk, %st) {
        for @($node) {
            self.walk($_, %blk, %st) if nqp::istype($_, QAST::Node);
        }
    }

    method walk_rx($node, %blk, %st) {
        for @($node) {
            if nqp::istype($_, QAST::Regex) {
                self.walk_rx($_, %blk, %st);
            }
            elsif nqp::istype($_, QAST::Node) {
                self.walk($_, %blk, %st);
            }
        }
    }

    method report($cu, %st) {
        return 0 unless $qt_report;
        my @blocks := %st<blocks>;
        my int $total := nqp::elems(@blocks);
        my int $ok := 0;
        my int $nodes := 0;
        my int $ok_nodes := 0;
        # Per-tag: how many blocks it blocks, and how many it alone blocks.
        # "Sole" is the free prioritization signal; the honest yield of a
        # tag group still needs an NQP_QT_ALSO run, exactly like NQP_RX_NO.
        my %blocked;
        my %sole;
        for @blocks -> %blk {
            $nodes := $nodes + %blk<nodes>;
            my int $nreasons := nqp::elems(%blk<reasons>);
            if $nreasons == 0 {
                $ok := $ok + 1;
                $ok_nodes := $ok_nodes + %blk<nodes>;
            }
            else {
                for %blk<reasons> {
                    nqp::bindkey(%blocked, $_.key,
                        (nqp::existskey(%blocked, $_.key) ?? %blocked{$_.key} !! 0) + 1);
                    if $nreasons == 1 {
                        nqp::bindkey(%sole, $_.key,
                            (nqp::existskey(%sole, $_.key) ?? %sole{$_.key} !! 0) + 1);
                    }
                }
            }
        }
        my str $name := nqp::elems(@blocks) ?? @blocks[0]<name> !! '';
        $name := '<anon>' if $name eq '';
        nqp::say('qt cu hll=' ~ $cu.hll ~ ' ' ~ $name
            ~ ' blocks ' ~ $total ~ ' ok ' ~ $ok ~ ' (' ~ pct($ok, $total) ~ ')'
            ~ ' nodes ' ~ $nodes ~ ' in-ok ' ~ $ok_nodes ~ ' (' ~ pct($ok_nodes, $nodes) ~ ')');
        for top_tags(%blocked, 20) -> $tag {
            nqp::say('qt bail ' ~ $tag ~ ' blocks ' ~ %blocked{$tag}
                ~ ' sole ' ~ (nqp::existskey(%sole, $tag) ?? %sole{$tag} !! 0));
        }
        1
    }

    sub pct(int $part, int $whole) {
        return '0.0%' unless $whole;
        my int $permille := nqp::div_i($part * 1000, $whole);
        nqp::div_i($permille, 10) ~ '.' ~ nqp::mod_i($permille, 10) ~ '%'
    }

    # The tags that block the most blocks, most-blocking first. The tag
    # count is small (dozens), so a selection walk per slot is plenty.
    sub top_tags(%counts, int $n) {
        my @tags;
        for %counts { nqp::push(@tags, $_.key) }
        my @out;
        while nqp::elems(@out) < $n && nqp::elems(@tags) {
            my int $best := 0;
            my int $i := 1;
            while $i < nqp::elems(@tags) {
                $best := $i if %counts{@tags[$i]} > %counts{@tags[$best]};
                $i := $i + 1;
            }
            nqp::push(@out, @tags[$best]);
            nqp::splice(@tags, nqp::list(), $best, 1);
        }
        @out
    }
}
