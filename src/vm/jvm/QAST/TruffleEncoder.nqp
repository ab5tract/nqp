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
#   rebuild is ~12 minutes end to end). NQP_CODE_ALSO treats extra tags as
#   covered, NQP_CODE_NO refuses covered ones; both take the tag spellings
#   the survey itself prints (op:handle, var:lexicalref, node:VM, regex).
#
# Knobs (all compile-time, read once; output is nqp::say with a "code "
# prefix, greppable out of a build log the same way NQP_RX_SURVEY is):
#   NQP_CODE_REPORT=1        per-compilation-unit summary and top bail tags
#   NQP_CODE_SURVEY=1        one line per block with every reason it bails
#   NQP_CODE_ALSO=a,b        treat these tags as covered for this run
#   NQP_CODE_NO=a,b          treat these covered tags as refused
class QAST::TruffleEncoder {
    # The committed first tranche, from the Phase 1 op census (migration
    # doc, 2026-09-01): the ~20 census heads plus the structural, primitive
    # and rakudo ops that always travel with them. Exceptions and handlers
    # (op:handle and friends) are deliberately absent -- they are Phase 3's
    # ControlFlowException story, and their weight in the bail histogram is
    # the measurement that phase is scheduled on.
    my %covered;
    my int $init_done := 0;
    my int $code_on := 0;
    my int $code_report := 0;
    my int $code_survey := 0;

    # The ops the survey counts as covered are DERIVED from the encoder's
    # own emit table plus the names encode_op special-cases, never listed
    # by hand: a hand-written list drifts both ways, and did. Before
    # 2026-09-02 it still claimed `for` and, for a year, the repeat
    # loops the encoder had never encoded, while missing every op added
    # since Phase 1 -- so the report under-counted exactly the coverage
    # Phase 5's deletion gate is waiting on. Being in the table means the
    # op has an encoding, not that every use of it encodes (arity and
    # shape still bail), so the survey stays an upper bound and the honest
    # yield of a tag group still wants an NQP_CODE_ALSO run.
    my $extra_ops := 'assign_i assign_n assign_s assign_u bind call
        callmethod callstatic chain chainstatic const curlexpad
        control defor dispatch getlexouter handle handlepayload hash if
        ifnull list list_i list_n list_s locallifetime null p6argvmarray p6assign usecapture
        p6bindsig p6trybindsig
        p6decontrv p6decontrv_6c
        repeat_until repeat_while stmt stmts unless until while
        numify preinc predec falsey stringify intify
        register delegate track guard savecapture for';

    # Node kinds the encoder handles outside the op table.
    my $covered_nodes := 'QAST::ParamTypeCheck';

    my $scopes := 'local lexical lexicalref contextual attribute attributeref positional associative';

    sub init() {
        return 0 if $init_done;
        $init_done := 1;
        my %env := nqp::getenvhash();
        $code_report := nqp::existskey(%env, 'NQP_CODE_REPORT') ?? 1 !! 0;
        $code_survey := nqp::existskey(%env, 'NQP_CODE_SURVEY') ?? 1 !! 0;
        $code_on := $code_report || $code_survey;
        return 0 unless $code_on;

        covered_from_table();
        for nqp::split(' ', subst_ws($extra_ops)) { %covered{'op:' ~ $_} := 1 }
        for nqp::split(' ', subst_ws($scopes))    { %covered{'var:' ~ $_} := 1 }
        for nqp::split(' ', subst_ws($covered_nodes)) { %covered{'node:' ~ $_} := 1 }
        # Ops we reach through their registered desugar count as covered:
        # the survey walks the ORIGINAL tree, so without this it reports a
        # bail for an op the encoder now encodes. Desugars are on by default,
        # so every registered desugar op is covered unless NQP_CODE_NO_DESUGAR
        # excludes it.
        my %no_desugar;
        if nqp::existskey(%env, 'NQP_CODE_NO_DESUGAR') {
            for nqp::split(',', %env<NQP_CODE_NO_DESUGAR>) { %no_desugar{$_} := 1 }
        }
        my $dreg := nqp::gethllsym('nqp', 'CODE_OP_DESUGARS');
        unless nqp::isnull($dreg) {
            my $it := nqp::iterator($dreg);
            while $it {
                my str $dname := nqp::iterkey_s(nqp::shift($it));
                %covered{'op:' ~ $dname} := 1 unless nqp::existskey(%no_desugar, $dname);
            }
        }
        if nqp::existskey(%env, 'NQP_CODE_ALSO') {
            for nqp::split(',', %env<NQP_CODE_ALSO>) { %covered{$_} := 1 }
        }
        if nqp::existskey(%env, 'NQP_CODE_NO') {
            for nqp::split(',', %env<NQP_CODE_NO>) { nqp::deletekey(%covered, $_) }
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
        return 0 unless $code_on;

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
        if $code_survey {
            my str $name := %blk<name> eq '' ?? '<anon ' ~ $cuid ~ '>' !! %blk<name>;
            my @reasons;
            for %blk<reasons> { nqp::push(@reasons, $_.key) }
            nqp::say(nqp::elems(@reasons)
                ?? 'code survey: ' ~ $name ~ ' BAIL ' ~ nqp::join(';', @reasons)
                !! 'code survey: ' ~ $name ~ ' OK');
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
        return 0 unless $code_report;
        my @blocks := %st<blocks>;
        my int $total := nqp::elems(@blocks);
        my int $ok := 0;
        my int $nodes := 0;
        my int $ok_nodes := 0;
        # Per-tag: how many blocks it blocks, and how many it alone blocks.
        # "Sole" is the free prioritization signal; the honest yield of a
        # tag group still needs an NQP_CODE_ALSO run.
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
        nqp::say('code cu hll=' ~ $cu.hll ~ ' ' ~ $name
            ~ ' blocks ' ~ $total ~ ' ok ' ~ $ok ~ ' (' ~ pct($ok, $total) ~ ')'
            ~ ' nodes ' ~ $nodes ~ ' in-ok ' ~ $ok_nodes ~ ' (' ~ pct($ok_nodes, $nodes) ~ ')');
        for top_tags(%blocked, 20) -> $tag {
            nqp::say('code bail ' ~ $tag ~ ' blocks ' ~ %blocked{$tag}
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

    # ------------------------------------------------------------------
    # Phase 2: the encoder proper. survey_cu above measures; encode_block
    # moves. A block either encodes whole into the nqpp wire format
    # (nqp-truffle's NqpWire.java is the other half of the contract) or
    # answers '' with a reason, exactly like QAST::RxDescriptor -- there
    # is no half a block. The emitted method's body becomes one call into
    # CodeEngines.codeRun; parameter binding included, so nothing here may
    # leave the block half-described.
    #
    # Compile-time knobs, mirroring the rx set:
    #   NQP_CODE_RUN          master switch: attempt encoding at all.
    #                        ON by default since milestone 3 (2026-09-09);
    #                        NQP_CODE_RUN=0 turns the encoder off.
    #   NQP_CODE_ENCODED=1    print each block the engine takes over
    #   NQP_CODE_BAIL=1       print why a block was refused
    #   NQP_CODE_SKIP=a,b     refuse these blocks by name
    #   NQP_CODE_SKIP_ANON=1  refuse blocks with no name
    #   NQP_CODE_ONLY=a,b     refuse everything else
    #   NQP_CODE_PRECOMP     precompiled (comp_mode) units encode too: the
    #                        program bakes into the emitted class as a
    #                        string constant, exactly as an rx descriptor
    #                        does, and runs from the jar with no knob set
    #                        at run time (Phase 4 of the migration).
    #                        ON by default since milestone 3 (2026-09-09);
    #                        NQP_CODE_PRECOMP=0 turns it off.

    # Wire tags; NqpWire.java numbers them identically.
    my int $W_STMTS := 1;
    my int $W_NULLC := 2;
    my int $W_IVAL := 3;
    my int $W_NVAL := 4;
    my int $W_SVAL := 5;
    my int $W_WVAL := 6;
    my int $W_LEXGET := 7;
    my int $W_LEXBIND := 8;
    my int $W_LOCGET := 9;
    my int $W_LOCBIND := 10;
    my int $W_IFV := 11;
    my int $W_IFS := 12;
    my int $W_LOOP := 13;
    my int $W_DISPATCH := 14;
    my int $W_OPCALL := 15;
    my int $W_COERCE := 16;
    my int $W_PARAMS := 17;
    my int $W_GETLEXOUTER := 18;
    my int $W_CODEREF := 19;
    my int $W_LOOPH := 20;
    my int $W_JNULL := 21;
    my int $W_HANDLE := 22;
    my int $W_HANDLEPAYLOAD := 23;
    my int $W_LEXREF := 24;
    my int $W_CURLEXPAD := 25;
    my int $W_P6ARGVMARRAY := 26;
    my int $W_CLASSLIB := 27;
    my int $W_USECAPTURE := 28;
    my int $W_LEXGET_OUTER := 29;
    my int $W_LEXBIND_OUTER := 30;
    my int $W_SAVECAPTURE := 31;
    my int $W_FORLOOP := 32;
    my int $W_P6BINDSIG := 33;
    my int $W_P6TRYBINDSIG := 34;
    my int $W_FORLOOPL := 35;

    # Handler categories, matching ExceptionHandling on the runtime side
    # (and the Compiler's own copies).
    my int $EX_CAT_CATCH   := 1;
    my int $EX_CAT_ANY     := 2;
    my int $EX_CAT_NEXT    := 4;
    my int $EX_CAT_REDO    := 8;
    my int $EX_CAT_LAST    := 16;
    my int $EX_CAT_RETURN  := 32;
    my int $EX_CAT_TAKE    := 128;
    my int $EX_CAT_WARN    := 256;
    my int $EX_CAT_SUCCEED := 512;
    my int $EX_CAT_PROCEED := 1024;
    my int $EX_CAT_LABELED := 4096;
    my int $EX_CAT_AWAIT   := 8192;
    my int $EX_CAT_EMIT    := 16384;
    my int $EX_CAT_DONE    := 32768;
    my int $EX_CAT_CONTROL := $EX_CAT_NEXT +| $EX_CAT_REDO +| $EX_CAT_LAST +|
                              $EX_CAT_TAKE +| $EX_CAT_WARN +|
                              $EX_CAT_SUCCEED +| $EX_CAT_PROCEED +|
                              $EX_CAT_AWAIT +| $EX_CAT_EMIT +| $EX_CAT_DONE +|
                              $EX_CAT_RETURN +| $EX_CAT_ANY;
    my %handler_names := nqp::hash(
        'CATCH',   $EX_CAT_CATCH,
        'CONTROL', $EX_CAT_CONTROL,
        'NEXT',    $EX_CAT_NEXT,
        'LAST',    $EX_CAT_LAST,
        'REDO',    $EX_CAT_REDO,
        'TAKE',    $EX_CAT_TAKE,
        'WARN',    $EX_CAT_WARN,
        'PROCEED', $EX_CAT_PROCEED,
        'SUCCEED', $EX_CAT_SUCCEED,
        'AWAIT',   $EX_CAT_AWAIT,
        'EMIT',    $EX_CAT_EMIT,
        'DONE',    $EX_CAT_DONE,
        'RETURN',  $EX_CAT_RETURN,
        'ANY',     $EX_CAT_ANY,
    );

    # Types, matching both NqpWire and this backend's $RT_* numbering.
    my int $T_OBJ := 0;
    my int $T_INT := 1;
    my int $T_NUM := 2;
    my int $T_STR := 3;
    my int $T_UINT := 4;   # int storage; unsigned only at the box (batch 22)
    my int $T_VOID := -1;
    my int $T_ANY := -2;

    my int $run_init_done := 0;
    my int $code_run := 0;
    my int $code_encoded := 0;
    my int $code_bail_p := 0;
    my int $code_strict := 0;
    my int $code_noframe := 0;
    my int $code_precomp := 0;
    my %code_no_desugar;
    my int $code_skip_anon := 0;
    my int $code_only_set := 0;
    my %code_skip;
    my %code_only;

    sub run_init() {
        return 0 if $run_init_done;
        $run_init_done := 1;
        my %env := nqp::getenvhash();
        # ON by default since milestone 3 (2026-09-09): the engine build is
        # the build. NQP_CODE_RUN=0 turns the encoder off (class road only;
        # meaningless on the unit road, where Compiler.nqp dies on it).
        $code_run := nqp::existskey(%env, 'NQP_CODE_RUN')
            ?? (nqp::atkey(%env, 'NQP_CODE_RUN') ne '0' ?? 1 !! 0)
            !! 1;
        return 0 unless $code_run;
        $code_encoded  := nqp::existskey(%env, 'NQP_CODE_ENCODED') ?? 1 !! 0;
        $code_bail_p   := nqp::existskey(%env, 'NQP_CODE_BAIL') ?? 1 !! 0;
        # NQP_CODE_STRICT: a refusal is a HARD ERROR, not a silent fallback to
        # bytecode. The campaign tool for reaching zero refusals -- the build
        # stops at the first uncovered op (named), and there is no fallback to
        # corrupt (killing the commit-before-refusal trap). Off = the old
        # fallback, so normal builds are unaffected.
        $code_strict   := nqp::existskey(%env, 'NQP_CODE_STRICT') ?? 1 !! 0;
        # NQP_CODE_NOFRAME: mark a block frame-free (needsFrame=0 in the wire
        # header) when it declares no lexicals, makes no dispatch, has no
        # nested block, reads no frame (lex/getlexouter/ctx/usecapture/args),
        # runs no handler region, and takes only positional local-scope
        # params. Such a block runs with cf==null and the runtime skips the
        # CallFrame allocation entirely. ON by default since 2026-09-07
        # (jesp diamond 5: spesh's inlining leaves no frame for an inlined
        # leaf); NQP_CODE_NOFRAME=0 makes every header word 1 again. The
        # CORE.d "Bind check failed" that kept it off was a frame-free
        # callee's assertparamcheck reading the caller's frame for its
        # dispatch; NqpDispatch now owns that failure (NqpFrameFreeBindFailure).
        $code_noframe  := nqp::existskey(%env, 'NQP_CODE_NOFRAME')
            ?? (nqp::atkey(%env, 'NQP_CODE_NOFRAME') ne '0' ?? 1 !! 0)
            !! 1;
        $code_precomp  := nqp::existskey(%env, 'NQP_CODE_PRECOMP')
            ?? (nqp::atkey(%env, 'NQP_CODE_PRECOMP') ne '0' ?? 1 !! 0)
            !! 1;
        # Desugars are ON BY DEFAULT (every register_op_desugar entry builds
        # a fresh tree, so applying one and bailing leaves the original tree
        # for the bytecode path). NQP_CODE_NO_DESUGAR=a,b names ops to
        # EXCLUDE, for bisecting a suspect desugar.
        if nqp::existskey(%env, 'NQP_CODE_NO_DESUGAR') {
            for nqp::split(',', %env<NQP_CODE_NO_DESUGAR>) { %code_no_desugar{$_} := 1 }
        }
        $code_skip_anon := nqp::existskey(%env, 'NQP_CODE_SKIP_ANON') ?? 1 !! 0;
        if nqp::existskey(%env, 'NQP_CODE_SKIP') {
            for nqp::split(',', %env<NQP_CODE_SKIP>) { %code_skip{$_} := 1 }
        }
        if nqp::existskey(%env, 'NQP_CODE_ONLY') {
            $code_only_set := 1;
            for nqp::split(',', %env<NQP_CODE_ONLY>) { %code_only{$_} := 1 }
        }
        emit_init();
        1
    }

    # The op table: name (or name/arity where arity picks the id) ->
    # [id, result type, argument types]. One contract with NqpOps.java's
    # id list; an entry added on one side only fails loudly at program
    # compile time.
    my %emit_ops;
    my int $emit_init_done := 0;
    sub op3(str $name, int $id, int $res, str $args) {
        %emit_ops{$name} := [$id, $res, $args];
    }

    # Every op the emit table knows, as a survey coverage tag. Table keys
    # may carry an arity suffix (name/N); the tag is the bare name.
    sub covered_from_table() {
        emit_init();
        for %emit_ops {
            my str $k := $_.key;
            my int $slash := nqp::index($k, '/');
            %covered{'op:' ~ ($slash >= 0 ?? nqp::substr($k, 0, $slash) !! $k)} := 1;
        }
        # The classlib registry (Compiler.nqp's map_classlib_*_op, published
        # as hllsyms) is the encoder's fallback for an op with no hand row,
        # so its ops count as covered too -- otherwise the survey undercounts
        # every op that moved from a hand op3 row to the registry. Upper
        # bound like the rest: a :cont op or an arg type the fallback cannot
        # carry still bails per use, and NQP_CODE_BAIL shows it.
        my $creg := nqp::gethllsym('nqp', 'CODE_CLASSLIB_OPS');
        if !nqp::isnull($creg) {
            for $creg { %covered{'op:' ~ $_.key} := 1 }
        }
        my $hreg := nqp::gethllsym('nqp', 'CODE_CLASSLIB_HLL_OPS');
        if !nqp::isnull($hreg) {
            for $hreg {
                for $_.value { %covered{'op:' ~ $_.key} := 1 }
            }
        }
        1
    }
    sub emit_init() {
        return 0 if $emit_init_done;
        $emit_init_done := 1;
        op3('add_i', 2, $T_INT, 'ii');
        op3('sub_i', 3, $T_INT, 'ii');
        op3('mul_i', 4, $T_INT, 'ii');
        op3('mod_i', 6, $T_INT, 'ii');
        op3('neg_i', 7, $T_INT, 'i');
        op3('bitand_i', 9, $T_INT, 'ii');
        op3('bitor_i', 10, $T_INT, 'ii');
        op3('bitxor_i', 11, $T_INT, 'ii');
        op3('bitshiftl_i', 12, $T_INT, 'ii');
        op3('bitshiftr_i', 13, $T_INT, 'ii');
        op3('bitneg_i', 14, $T_INT, 'i');
        op3('not_i', 15, $T_INT, 'i');
        op3('iseq_i', 16, $T_INT, 'ii');
        op3('isne_i', 17, $T_INT, 'ii');
        op3('islt_i', 18, $T_INT, 'ii');
        op3('isle_i', 19, $T_INT, 'ii');
        op3('isgt_i', 20, $T_INT, 'ii');
        op3('isge_i', 21, $T_INT, 'ii');
        op3('add_n', 22, $T_NUM, 'nn');
        op3('sub_n', 23, $T_NUM, 'nn');
        op3('mul_n', 24, $T_NUM, 'nn');
        op3('div_n', 25, $T_NUM, 'nn');
        op3('neg_n', 26, $T_NUM, 'n');
        op3('iseq_n', 27, $T_INT, 'nn');
        op3('isne_n', 28, $T_INT, 'nn');
        op3('islt_n', 29, $T_INT, 'nn');
        op3('isle_n', 30, $T_INT, 'nn');
        op3('isgt_n', 31, $T_INT, 'nn');
        op3('isge_n', 32, $T_INT, 'nn');
        op3('substr/2', 37, $T_STR, 'si');
        op3('substr/3', 38, $T_STR, 'sii');
        op3('index/2', 39, $T_INT, 'ss');
        op3('index/3', 40, $T_INT, 'ssi');
        op3('iseq_s', 45, $T_INT, 'ss');
        op3('isne_s', 46, $T_INT, 'ss');
        op3('islt_s', 47, $T_INT, 'ss');
        op3('isle_s', 48, $T_INT, 'ss');
        op3('isgt_s', 49, $T_INT, 'ss');
        op3('isge_s', 50, $T_INT, 'ss');
        op3('ord/1', 83, $T_INT, 's');
        op3('null_s', 84, $T_STR, '');
        op3('p6capturelex', 100, $T_OBJ, 'o');
        op3('p6sink', 101, $T_OBJ, 'o');
        op3('p6store', 102, $T_OBJ, 'oo');
        op3('p6box_i', 103, $T_OBJ, 'i');
        op3('p6box_n', 104, $T_OBJ, 'n');
        op3('p6box_s', 105, $T_OBJ, 's');
        op3('p6definite', 106, $T_OBJ, 'o');
        op3('p6bindattrinvres', 107, $T_OBJ, 'ooso');
        # 108 is `control`, encoded as a special case with a synthetic
        # category argument.
        op3('throwpayloadlex', 110, $T_OBJ, 'io');
        op3('throwpayloadlexcaller', 111, $T_OBJ, 'io');
        # The routine calling-convention family (Phase 5): what the Phase 1
        # census said gates two thirds of the setting's blocks.
        op3('p6typecheckrv', 114, $T_OBJ, 'ooo');
        # 115 is p6decontrv_rt, reached only through the p6decontrv
        # desugar below (the rw split is a compile-time decision).
        op3('p6decontrv_rt', 115, $T_OBJ, 'ooi');
        # Attribute access and the typed collection accessors: the
        # survey's old hand-written list claimed these for a year without
        # the encoder ever having them (found 2026-09-02 when the list was
        # replaced by a derivation from this table). Every one is a
        # fixed-arity runtime call, mapped exactly as Compiler.nqp maps it;
        # the hinted getattr/bindattr overloads stay bytecode's, since a
        # hint is a compile-time slot index this encoder does not carry.
        op3('getattr', 81, $T_OBJ, 'oos');
        op3('bindattr', 82, $T_OBJ, 'ooso');
        # Chosen by sole-blocker count over a CORE.c NQP_CODE_REPORT run
        # (2026-09-03): these seven alone gate ~500 of the setting's
        # blocks. assign_i/assign_s are NOT table rows -- their bytecode
        # desugar REWRITES the QAST node in place (op('bind'), scope(...)),
        # and mutating a shared tree during an encode that may still bail
        # would corrupt it for the bytecode path; encode_op does the same
        # rewrite on a copy of the target instead (see 'assign_i' there),
        # and the container road is these four rows.
        op3('p6bindassert', 155, $T_OBJ, 'oo');
        # Second sole-blocker batch off the same CORE.c report. push_i/_n/_s
        # are the bare ops only: the list_i/list_n/list_s CONSTRUCTORS that
        # would also use them stay reverted (see the note in encode_op).
        op3('isbig_I', 176, $T_INT, 'o');
        # The :cont family: the engine reads a resumed result off the
        # frame's return register, as the bytecode path does.
        op3('die', 180, $T_STR, 's');
        op3('die_s', 180, $T_STR, 's');
        op3('throw', 181, $T_OBJ, 'o');
        op3('rethrow', 182, $T_OBJ, 'o');
        op3('throwextype', 183, $T_OBJ, 'i');
        # Delimited continuations. A hand row wins over the classlib registry's
        # :cont bail; each suspends through the same save-stack machinery the
        # throw ops use, and the resumed result is read from the frame.
        op3('continuationreset', 379, $T_OBJ, 'oo');
        op3('continuationcontrol', 380, $T_OBJ, 'ioo');
        op3('continuationinvoke', 381, $T_OBJ, 'oo');
        # Encoder-internal (no QAST op of this name exists): the num32 half
        # of Compiler.nqp's emit_sized_native_trunc, a round trip through a
        # 32-bit float (the bytecode path's d2f/f2d). sized_trunc emits it.
        op3('sized_num32', 382, $T_NUM, 'n');
        op3('box_i/2', 186, $T_OBJ, 'io');
        op3('box_n/2', 187, $T_OBJ, 'no');
        op3('box_s/2', 188, $T_OBJ, 'so');
        op3('sha1', 199, $T_STR, 's');
        op3('iseq_I', 201, $T_INT, 'oo');
        op3('isne_I', 202, $T_INT, 'oo');
        op3('islt_I', 203, $T_INT, 'oo');
        op3('isle_I', 204, $T_INT, 'oo');
        op3('isgt_I', 205, $T_INT, 'oo');
        op3('isge_I', 206, $T_INT, 'oo');
        op3('tostr_I', 213, $T_STR, 'o');
        op3('add_I', 214, $T_OBJ, 'ooo');
        op3('sub_I', 215, $T_OBJ, 'ooo');
        op3('mul_I', 216, $T_OBJ, 'ooo');
        op3('cmp_I', 241, $T_INT, 'oo');
        op3('div_I', 242, $T_OBJ, 'ooo');
        # The grammar engine's rxmatch: descriptor, cursor, cursor class,
        # target, from, invocant-from, restart, invocant, callback.
        op3('rxmatch', 249, $T_OBJ, 'soosiiioo');
        op3('tryfindmethod', 254, $T_OBJ, 'os');
        op3('pow_I', 264, $T_OBJ, 'oooo');
        op3('ctx', 281, $T_OBJ, '');
        op3('bitand_I', 285, $T_OBJ, 'ooo');
        op3('neg_I', 286, $T_OBJ, 'oo');
        op3('gcd_I', 287, $T_OBJ, 'ooo');
        op3('fromnum_I', 288, $T_OBJ, 'no');
        op3('rand_I', 289, $T_OBJ, 'oo');
        op3('radix_I', 301, $T_OBJ, 'isiio');
        op3('mod_I', 334, $T_OBJ, 'ooo');
        op3('expmod_I', 335, $T_OBJ, 'oooo');
        op3('abs_I', 336, $T_OBJ, 'oo');
        op3('bitshiftl_I', 337, $T_OBJ, 'oio');
        op3('bitshiftr_I', 338, $T_OBJ, 'oio');
        op3('bitor_I', 339, $T_OBJ, 'ooo');
        op3('bitxor_I', 340, $T_OBJ, 'ooo');
        op3('bitneg_I', 341, $T_OBJ, 'oo');
        op3('lcm_I', 342, $T_OBJ, 'ooo');
        op3('fromI_I', 343, $T_OBJ, 'oo');
        op3('isprime_I', 344, $T_INT, 'o');
        op3('base_I', 345, $T_STR, 'oi');
        op3('bool_I', 346, $T_INT, 'o');
        op3('tonum_I', 347, $T_NUM, 'o');
        op3('div_In', 348, $T_NUM, 'oo');
        1
    }

    # Coercion kinds, matching NqpOps.
    sub coerce_kind(int $from, int $to) {
        if $from == $T_INT { return $to == $T_OBJ ?? 0 !! $to == $T_NUM ?? 6 !! $to == $T_STR ?? 8 !! -1 }
        if $from == $T_NUM { return $to == $T_OBJ ?? 1 !! $to == $T_INT ?? 7 !! $to == $T_STR ?? 14 !! -1 }
        if $from == $T_STR { return $to == $T_OBJ ?? 2 !! $to == $T_INT ?? 13 !! $to == $T_NUM ?? 15 !! -1 }
        if $from == $T_OBJ { return $to == $T_INT ?? 3 !! $to == $T_NUM ?? 4 !! $to == $T_STR ?? 5 !! $to == $T_UINT ?? 12 !! -1 }
        # A uint boxes/widens UNSIGNED: 2^64-1 is a large Int, not -1.
        if $from == $T_UINT { return $to == $T_OBJ ?? 9 !! $to == $T_NUM ?? 10 !! $to == $T_STR ?? 11 !! -1 }
        -1
    }

    sub cbail(str $why) { nqp::die('code-bail ' ~ $why) }

    # Is a block with this cuid one of the blocks THIS block's own deferred
    # loop will compile? A QAST::BVal names a block of the same
    # compilation, and the block may sit on either side of the reference
    # (t/qast/01-qast.t's 'BVal node preceding its block'). Either order
    # resolves on the deferred nested-block road -- whichever slot the loop
    # reaches first compiles the block, and every slot patches from the
    # same cuid_to_qbid -- so $*CODEREFS.know_cuid alone is too strict a
    # test at encode time.
    #
    # The walk STOPS at a nested QAST::Block, and that boundary is the
    # whole point: the deferred loop compiles a block through
    # $comp.as_jast($blk), which takes its outer from $*BLOCK -- the block
    # being encoded here. A block declared one level deeper (inside a
    # nested block B) belongs to B's deferral, not this one; admitting it
    # would let this block's deferral reach it first and compile it with
    # the WRONG outer, silently, on a road that has no fallback. So the
    # answer is exactly the set of blocks this block declares itself, and
    # the deeper shape refuses loudly, as it did before.
    #
    # A BVal's value is likewise a REFERENCE, not containment: descending
    # into it would walk in circles (the very shape the tests build).
    #
    # $root marks the block being encoded, whose OWN children are the walk
    # (it is a QAST::Block itself, so the boundary above would otherwise
    # stop the walk before it began).
    sub block_in_tree($node, str $cuid, int $root = 0) {
        return 0 unless nqp::istype($node, QAST::Node);
        unless $root {
            return ($node.cuid eq $cuid ?? 1 !! 0)
                if nqp::istype($node, QAST::Block);
            return 0 if nqp::istype($node, QAST::BVal);
        }
        for $node.list {
            return 1 if block_in_tree($_, $cuid);
        }
        0
    }

    # A classlib registry RT type (Compiler.nqp: obj 0, int 1, num 2, str 3,
    # uint 10) as an encoder type; a uint RESULT is T_UINT so it boxes
    # unsigned, a uint ARGUMENT is the int slot it travels in.
    sub classlib_t(int $rt, int $result) {
        $rt == 0 ?? $T_OBJ !! $rt == 1 ?? $T_INT !! $rt == 2 ?? $T_NUM !! $rt == 3 ?? $T_STR
            !! $rt == 10 ?? ($result ?? $T_UINT !! $T_INT) !! -1
    }

    sub epush(%e, int $v) { nqp::push(%e<code>, $v) }

    sub epool(%e, str $s) {
        if nqp::existskey(%e<pooli>, $s) {
            return %e<pooli>{$s};
        }
        my int $idx := nqp::elems(%e<pool>);
        nqp::push(%e<pool>, $s);
        %e<pooli>{$s} := $idx;
        $idx
    }

    sub new_elocal(%e, int $type) {
        my int $idx := nqp::elems(%e<ltypes>);
        nqp::push(%e<ltypes>, $type);
        $idx
    }

    # NQP_CODE_WHY traces the encode/refuse decision per block, with the
    # inputs that decide it. A block that encodes once and refuses the
    # next time commits its lexicals and then lets the bytecode path
    # declare them again; this is how that is caught. Compiler.nqp calls
    # this for the blocks it never hands the encoder (custom_args), so a
    # census of the verdicts sees every block the unit compiles.
    my int $code_why := -1;
    method why($node, $comp_mode, str $verdict) {
        $code_why := nqp::existskey(nqp::getenvhash(), 'NQP_CODE_WHY') ?? 1 !! 0
            if $code_why < 0;
        return 0 unless $code_why;
        my str $who := $node.name eq '' ?? '<anon ' ~ $node.cuid ~ '>' !! $node.name;
        nqp::say('code why ' ~ $who ~ ' cuid ' ~ $node.cuid
            ~ ' blocktype ' ~ $node.blocktype
            ~ ' comp_mode ' ~ ($comp_mode ?? 1 !! 0)
            ~ ' exith ' ~ ($node.has_exit_handler ?? 1 !! 0)
            ~ ' -> ' ~ $verdict);
        1
    }

    method encode_block($node, $block, $comp, :$comp_mode, :$sidecar, :$unit_road) {
        run_init();
        sub trace(str $verdict) { self.why($node, $comp_mode, $verdict) }
        if !$code_run { trace('no: code_run off'); return '' }
        if $comp_mode && !$code_precomp { trace('no: comp_mode'); return '' }
        my str $name := $node.name;
        if $code_skip_anon && $name eq '' { trace('no: skip_anon'); return '' }
        if nqp::existskey(%code_skip, $name) { trace('no: skip'); return '' }
        if $code_only_set && !nqp::existskey(%code_only, $name) {
            trace('no: not in only'); return ''
        }
        # A raw block (Compiler.nqp's own deserialize/load/main wrappers)
        # is a parameterless declaration to the engine; on the class road
        # its body stays bytecode, on the artifact road it must encode.
        if $node.blocktype eq 'raw' && !$unit_road { trace('no: raw blocktype'); return '' }
        # An immediate block is compiled AND called by its enclosing block,
        # which is why encode_node already refuses one as a child
        # ('block immediate'). Encoding one as a target is the same
        # entanglement seen from the other end: the block commits its
        # lexicals here, then the enclosing road declares them again and
        # the compiler dies ("Lexical '&parent' already declared", found
        # 2026-09-02 when list/hash coverage first made a generated BEGIN
        # block encodable). BEGIN bodies run once at compile time, so this
        # costs nothing worth having.
        if $node.blocktype eq 'immediate' || $node.blocktype eq 'immediate_static' {
            trace('no: immediate blocktype'); return ''
        }

        my %e := nqp::hash(
            'code', nqp::list(), 'pool', nqp::list(), 'pooli', nqp::hash(),
            'own', nqp::hash(), 'ownref', nqp::hash(), 'ownret', nqp::hash(),
            'locals', nqp::hash(), 'ltypes', nqp::list(),
            'params', nqp::list(), 'decls', nqp::list(),
            'nested', nqp::list(),
            'block', $block, 'qast', $node, 'comp', $comp, 'dispatches', 0,
            # An exit-handler block (Raku LEAVE/KEEP/UNDO/POST, Lock.protect)
            # runs its handler from CallFrame.leave(): it needs a frame, since
            # a frame-free entry builds none and would skip the handler
            # silently. The value is already on the caller's registers when
            # leave() runs (StoreRet wraps the program; NqpDispatch and
            # ProgramEntry leave after the call), as the stub's postlude did.
            'frame_op', ($node.has_exit_handler ?? 1 !! 0), 'uses_hll', 0, 'hidx', 0,
            # A custom_args block (Raku's full-binder signatures: sub-
            # signatures, generic/coercive types, capture slurpies) binds
            # its arguments itself, through the p6bindsig prologue in its
            # body; patch_params emits an empty header for it.
            'custom_args', ($node.custom_args ?? 1 !! 0));
        epush(%e, 2);   # wire version
        epush(%e, 0);   # result type, patched below
        epush(%e, 0);   # local count, patched below
        epush(%e, 1);   # needs frame, patched below (index 3)
        my str $out := '';
        my $err := nqp::null();
        try {
            nqp::bindpos(%e<code>, 1, self.encode_body($node, %e));
            CATCH { $err := $! }
        }
        if !nqp::isnull($err) {
            # Only a deliberate refusal falls back to bytecode; any other
            # exception is a real compile error that must stay loud -- a
            # swallowed one lets a block that should refuse to compile
            # compile.
            my str $msg := nqp::getmessage($err);
            nqp::rethrow($err) if nqp::index($msg, 'code-bail') < 0;
            trace('no: bail ' ~ $msg);
            if $code_bail_p {
                nqp::say('code bail: '
                    ~ ($name eq '' ?? '<anon ' ~ $node.cuid ~ '>' !! $name)
                    ~ ' ' ~ $msg);
            }
            # NQP_CODE_STRICT: no fallback -- a refusal is a hard error naming
            # the block and the uncovered op, so the campaign build stops here.
            nqp::rethrow($err) if $code_strict;
            return '';
        }

        # Splice the local types in after [version, nlocals], and only now
        # touch the BlockInfo: a bail above must leave it untouched or the
        # bytecode path would re-declare every lexical and die.
        my @code := %e<code>;
        my @ltypes := %e<ltypes>;
        nqp::bindpos(@code, 2, nqp::elems(@ltypes));
        # A block needs a CallFrame unless it is frame-free: no declared
        # lexicals, no dispatch, no nested block, no frame-reading op, and
        # only positional local-scope params (patch_params sets frame_op for
        # named/slurpy/lexical-scope). Off the knob, always 1.
        #
        # Placeholder lexical declarations do not force a frame: a `static`
        # container prototype, a `contvar`/`statevar` slot, and the implicit
        # `%_` are all dead unless the body actually reads them -- and every
        # such read emits a lexical op, which sets frame_op. So the bare decl
        # never needs a CallFrame; only a real use does. This is what makes a
        # method with `my` variables or container parameters frame-free: the
        # value lives in a lowered local, and the kept lexical is just a
        # prototype the body never names. A `lex`/`lexref` decl (a lexical
        # the optimizer did NOT lower, i.e. one that escapes) still counts.
        # (`state` keeps forcing a frame: its once-only init runs at frame
        # construction, subtle enough to leave for later.)
        # The implicit magicals ($/ $! $_ $¢) a routine declares are bare
        # slots when the body never names them -- kept for a context an
        # early (BEGIN-time) compilation serialized, which rebinds them by
        # name into the STATIC lexical table at load; that table a
        # frame-free block keeps. None of them is dynamic, so a callee's
        # dynamic lookup never expects to find them on this frame. Any
        # read by the body itself is a lexical op and sets frame_op.
        my int $fdecls := 0;
        for %e<decls> {
            my str $k := $_[0];
            my str $n := $_[1].name;
            $fdecls := $fdecls + 1
                unless $k eq 'static' || $k eq 'cont'
                    || nqp::iseq_s($n, '%_')
                    || nqp::iseq_s($n, '$/') || nqp::iseq_s($n, '$!')
                    || nqp::iseq_s($n, '$_') || nqp::iseq_s($n, '$¢');
        }
        my int $needs_frame := %e<frame_op>
            || $fdecls || %e<dispatches> || nqp::elems(%e<nested>);
        # Bit 0: needs a frame. Bit 1: runs no current-language reader
        # (%hll_ops), so a frame-free entry may cross languages. A program
        # from before bit 1 reads as 0 there: the conservative side.
        nqp::bindpos(@code, 3, (($code_noframe && !$needs_frame) ?? 0 !! 1)
            + (%e<uses_hll> ?? 0 !! 2));
        # NQP_CODE_WHY: the frame verdict with its inputs, per block. On
        # stdout like the encode/refuse trace, so never export it to make
        # (the gen-cat recipes pipe stdout into generated sources); run the
        # one compile you want traced by hand and capture stdout.
        if nqp::existskey(nqp::getenvhash(), 'NQP_CODE_WHY') {
            my str $fdecl_names := '';
            for %e<decls> {
                my str $k := $_[0];
                $fdecl_names := $fdecl_names ~ ' ' ~ $k ~ ':' ~ $_[1].name
                    unless $k eq 'static' || $k eq 'cont' || nqp::iseq_s($_[1].name, '%_');
            }
            nqp::say('code frame ' ~ %e<qast>.name
                ~ ' -> ' ~ ($needs_frame ?? 'framed' !! 'free')
                ~ ' frame_op=' ~ %e<frame_op> ~ ' fdecls=' ~ $fdecls ~ $fdecl_names
                ~ ' dispatches=' ~ %e<dispatches> ~ ' nested=' ~ nqp::elems(%e<nested>)
                ~ ' uses_hll=' ~ %e<uses_hll>);
        }
        nqp::splice(@code, @ltypes, 4, 0);
        # The size gate, BEFORE the commit: on the string road the program
        # travels as one string constant, which the class file caps at
        # 65535 UTF-8 bytes (the rx descriptor's cliff). A jar-bound unit
        # (:sidecar) ships its programs in the LZ4 sidecar by index, where
        # no such cap exists, so the gate is the string road's alone: the
        # BOOTSTRAP BEGIN bodies (67k-134k, six of them) were the whole
        # residue of it (2026-09-09). A refusal after the commit
        # would hand the bytecode path a block whose lexicals are already
        # registered ("Lexical '&parent' already declared", found
        # 2026-09-04 when the BOOTSTRAP BEGIN body, 4 decls and thousands
        # of statements, first reached here as an immediate block). The
        # estimate is an upper bound: every code word as decimal plus its
        # separator, every pool entry with its length prefix, and room
        # for the nested qbids patched in below.
        my int $est := 32 + 6 * nqp::elems(%e<nested>);
        for @code { $est := $est + nqp::chars(~$_) + 1 }
        for %e<pool> { $est := $est + nqp::chars($_) + 8 }
        if $est > 60000 && !$sidecar {
            trace('no: program too large (' ~ $est ~ ')'); return ''
        }
        trace('YES: committing ' ~ nqp::elems(%e<decls>) ~ ' decls');
        for %e<decls> -> $d {
            my str $kind := $d[0];
            my $var := $d[1];
            nqp::say('code decl ' ~ $node.cuid ~ ' ' ~ $kind ~ ' ' ~ $var.name)
                if $code_why > 0;
            if $kind eq 'lex' { $block.add_lexical($var) }
            elsif $kind eq 'lexref' { $block.add_lexicalref($var) }
            elsif $kind eq 'static' { $block.add_lexical($var, :is_static) }
            elsif $kind eq 'cont' { $block.add_lexical($var, :is_cont) }
            elsif $kind eq 'state' { $block.add_lexical($var, :is_state) }
        }

        # Nested blocks compile only now, against the committed lexical
        # table; a die in one of them is a real compile error, exactly as
        # it would be on the bytecode path. The code refs patch in after.
        for %e<nested> -> $nb {
            my $blk := $nb[1];
            unless $*CODEREFS.know_cuid($blk.cuid) {
                # An immediate block is compiled as a declaration, the way
                # the bytecode path's own if/for roads flip it: compiled
                # as immediate, as_jast would also emit its direct call
                # into the enclosing method -- registering a reentry
                # label there that the discarded emission never defines
                # ("reenter_N used but not defined").
                my str $bt := $blk.blocktype;
                my int $imm := $bt eq 'immediate' || $bt eq 'immediate_static';
                $blk.blocktype($bt eq 'immediate' ?? 'declaration' !! 'declaration_static')
                    if $imm;
                my $r := $comp.as_jast($blk);
                $blk.blocktype($bt) if $imm;
                $*STACK.obtain(NQPMu, $r);
            }
            # Positions were recorded before the local types were spliced
            # into the header; account for the shift.
            nqp::bindpos(@code, $nb[0] + nqp::elems(@ltypes),
                $comp.cuid_to_qbid($blk.cuid));
        }

        my @out := ['nqpp ', ~nqp::elems(@code)];
        for @code { nqp::push(@out, ' ' ~ $_) }
        nqp::push(@out, ' ' ~ nqp::elems(%e<pool>));
        for %e<pool> { nqp::push(@out, ' ' ~ nqp::chars($_) ~ ':' ~ $_) }
        $out := nqp::join('', @out);
        # The gate above bounded this; refusing here would be the bug it
        # exists to prevent, so a miss is an invariant failure, not a bail.
        nqp::die('code engine: program of ' ~ $node.cuid ~ ' grew past the size gate after commit ('
            ~ nqp::chars($out) ~ ' chars)') if nqp::chars($out) > 65000 && !$sidecar;
        if $code_encoded {
            nqp::say('code engine: ' ~ ($name eq '' ?? '<anon ' ~ $node.cuid ~ '>' !! $name));
        }
        $out
    }

    # The whole body: parameter prologue first, then the statements, the
    # last one supplying the block's value.
    method encode_body($node, %e) {
        my @stmts;
        for @($node) {
            nqp::push(@stmts, $_) if nqp::istype($_, QAST::Node);
        }
        epush(%e, $W_STMTS);
        epush(%e, 1 + nqp::elems(@stmts));
        my int $params_at := nqp::elems(%e<code>);
        epush(%e, $W_PARAMS);  # patched into a full prologue at the end
        my int $n := nqp::elems(@stmts);
        my int $i := 0;
        my int $rtype := $T_OBJ;
        while $i < $n {
            $rtype := self.encode_node(@stmts[$i], %e,
                $i == $n - 1 ?? $T_ANY !! $T_VOID);
            $i++;
        }
        if $n == 0 { epush(%e, $W_JNULL) }
        self.patch_params($params_at, %e);
        $rtype
    }

    # Splices @words into the program at $at and moves every nested-block
    # slot the walk recorded by position at or after $at right by the
    # same amount, so the deferred qbid patch still lands on its CODEREF
    # cell. One rule serves both callers: a prologue goes in after its
    # placeholder ($at is the placeholder's index + 1, so the placeholder
    # itself stays put), a coercion goes in at the mark of the subtree it
    # wraps. Splicing without the shift wrote every deferred qbid over
    # the tag of a neighbouring node ("unknown tag 11142", 2026-09-09).
    sub splice_code(%e, @words, int $at) {
        nqp::splice(%e<code>, @words, $at, 0);
        my int $n := nqp::elems(@words);
        for %e<nested> -> $nb {
            nqp::bindpos($nb, 0, $nb[0] + $n) if $nb[0] >= $at;
        }
    }

    # Parameters were collected while the walk met their declarations;
    # the prologue is spliced in where the placeholder sits. Any default
    # expressions are encoded here, into a scratch list.
    method patch_params($params_at, %e) {
        my @params := %e<params>;
        if %e<custom_args> {
            # The runtime Binder binds every parameter from the body's
            # p6bindsig, by name into the frame's lexicals, so the header
            # declares none and accepts any arity: the arity check still
            # runs (required 0, accepted -1 never fails) because it is what
            # puts csd/args on the frame for the binder to read. A lowered
            # parameter alongside would be bound twice; nothing emits one.
            cbail('custom_args block with lowered params') if nqp::elems(@params);
            # Written exactly as the full prologue below is: the $W_PARAMS
            # placeholder stays where it was and the three header words go in
            # after it, so the nested-block slots the walk recorded by
            # position all move right by three (splice_code moves the slots).
            my @hdr := nqp::list(0, -1, 0);
            splice_code(%e, @hdr, $params_at + 1);
            return 0;
        }
        my int $pos_required := 0;
        my int $pos_optional := 0;
        my int $pos_slurpy := 0;
        for @params -> $p {
            if $p.named { }
            elsif $p.slurpy { $pos_slurpy := 1 }
            elsif $p.default {
                cbail('optional positional after slurpy') if $pos_slurpy;
                $pos_optional++;
            }
            else {
                cbail('required positional after optional') if $pos_optional;
                cbail('required positional after slurpy') if $pos_slurpy;
                $pos_required++;
            }
        }
        my @save := %e<code>;
        my @p := nqp::list();
        %e<code> := @p;
        # The nested-block list is hidden while the prologue is built in
        # its own array: encode_child's coercion splice shifts every entry
        # at or past its mark, and a mark in this scratch array is a small
        # number that every main-program position exceeds -- each
        # coercion inside the prologue (a default or a task coerced to a
        # native parameter's type) would bump every recorded qbid slot by
        # two, and the deferred patch would land on a tag ("unknown tag
        # 17041 at 92", 2026-09-04, once typed parameters encoded). A
        # nested block inside the prologue bails anyway.
        my @nested_save := %e<nested>;
        %e<nested> := nqp::list();
        nqp::bindkey(%e, 'inparams', 1);
        epush(%e, $pos_required);
        epush(%e, $pos_slurpy ?? -1 !! $pos_required + $pos_optional);
        epush(%e, nqp::elems(@params));
        for @params -> $p {
            my int $kind := $p.slurpy
                ?? ($p.named ?? 3 !! 1)
                !! ($p.named ?? 2 !! 0);
            # A discard `%_` named slurpy (the JVM lowering annotates it):
            # accepts and discards stray nameds, builds no hash, binds
            # nothing. Kind 4 tells the builder to suppress the extra-named
            # rejection but emit no fetch -- so it needs no CallFrame and does
            # not force a frame. Off the knob it stays a normal kind-3 slurpy.
            $kind := 4 if $code_noframe && $kind == 3 && $p.ann('discard_named');
            # Frame-free covers positional local-scope params and the kind-4
            # discard slurpy; a real named/slurpy fetch and a lexical-scope
            # bind both go through the CallFrame.
            %e<frame_op> := 1 if $kind != 0 && $kind != 4;
            epush(%e, $kind);
            my int $ptype := $p.slurpy ?? $T_OBJ !! rt_of($p.returns);
            my int $uint := 0;
            if $ptype == -9 && !$p.slurpy {
                # A uint parameter (objprimspec 10): fetch unsigned so a
                # full-width value unboxes, and bind/default as int -- the
                # wire type 4 (T_UINT) tells the builder to use posparam_u.
                # A sized uint (under 64 bits) is masked by the truncating
                # task below, the way the bytecode path masks after the fetch.
                my int $uspec := nqp::objprimspec($p.returns);
                if $uspec == 10 {
                    $uint := 1;
                    $ptype := $T_INT;
                }
            }
            cbail('param type') if $ptype < 0 || $ptype > 3;
            epush(%e, $uint ?? 4 !! $ptype);
            if $p.scope eq 'local' {
                epush(%e, 1);
                epush(%e, %e<locals>{$p.name}[0]);
            }
            else {
                %e<frame_op> := 1;   # a lexical-scope param bind needs the frame
                epush(%e, 0);
                epush(%e, epool(%e, $p.name));
            }
            if $kind == 2 || $kind == 3 || $kind == 4 {
                epush(%e, epool(%e, ~$p.named));
            }
            if $p.default {
                epush(%e, 1);
                self.encode_child($p.default, %e, $ptype);
            }
            else {
                epush(%e, 0);
            }
            # Param tasks -- the declaration's children, run after the
            # bind, exactly as emit_param_tasks does on the bytecode path.
            my @tasks;
            for @($p) {
                nqp::push(@tasks, $_) if nqp::istype($_, QAST::Node);
            }
            # MoarVM fetches a sized parameter into a sized register, so the
            # argument arrives already truncated; every slot here is full
            # width, so Compiler.nqp emits emit_sized_native_trunc between
            # the fetch and the store. The wire has no room for a width in
            # the param record, so the same truncation rides as the FIRST
            # task -- tasks run right after the bind, in order, and a
            # re-bind of the parameter through sized_trunc is exactly the
            # value the bytecode path would have stored. A full-width or
            # object parameter produces no task at all.
            my int $ntrunc := 0;
            my $truncq;
            unless $p.slurpy {
                my $pv := QAST::Var.new( :name($p.name), :scope($p.scope),
                                         :returns($p.returns) );
                my $tv := self.sized_trunc($pv, $p.returns);
                unless nqp::eqaddr($tv, $pv) {
                    $ntrunc := 1;
                    $truncq := QAST::Op.new( :op('bind'),
                        QAST::Var.new( :name($p.name), :scope($p.scope),
                                       :returns($p.returns) ),
                        $tv );
                }
            }
            epush(%e, $ntrunc + nqp::elems(@tasks));
            self.encode_node($truncq, %e, $T_VOID) if $ntrunc;
            for @tasks {
                self.encode_node($_, %e, $T_VOID);
            }
        }
        %e<code> := @save;
        %e<nested> := @nested_save;
        nqp::bindkey(%e, 'inparams', 0);
        splice_code(%e, @p, $params_at + 1);
    }

    # Encodes one node, coercing its value to $want when the types allow
    # it and bailing when they do not. $T_ANY takes what comes; $T_VOID
    # discards, so anything goes.
    method encode_child($n, %e, int $want) {
        my int $mark := nqp::elems(%e<code>);
        my int $got := self.encode_node($n, %e, $want);
        return $got if $want == $T_ANY || $want == $T_VOID || $got == $want;
        # uint and int share the int slot; only the box differs, so a uint
        # value satisfies an int want (and vice versa) with no coercion.
        return $want if ($got == $T_UINT && $want == $T_INT) || ($got == $T_INT && $want == $T_UINT);
        my int $kind := coerce_kind($got, $want);
        cbail('no coercion ' ~ $got ~ '->' ~ $want) if $kind < 0;
        splice_code(%e, [$W_COERCE, $kind], $mark);
        $want
    }

    method encode_node($n, %e, int $want) {
        if nqp::istype($n, QAST::Op) {
            return self.encode_op($n, %e, $want);
        }
        if nqp::istype($n, QAST::VarWithFallback) {
            # A read whose null answer is replaced by the fallback; a
            # native read has no null and is the plain read, as the
            # bytecode path has it. The ifnull shape: read once into a
            # scratch local, test, fall back.
            my int $vt := self.var_read_type($n, %e);
            return self.encode_var($n, %e, nqp::null(), $want) if $vt != $T_OBJ;
            return self.encode_var($n, %e, nqp::null(), $want) if $want == $T_VOID;
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS); epush(%e, 2);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            cbail('var-with-fallback read type')
                unless self.encode_var($n, %e, nqp::null(), $T_OBJ) == $T_OBJ;
            epush(%e, $W_IFV);
            epush(%e, $T_INT);
            epush(%e, 0);
            epush(%e, 1);
            epush(%e, $W_OPCALL); epush(%e, 52); epush(%e, 1);   # isnull
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            self.encode_child($n.fallback, %e, $T_OBJ);
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            return $T_OBJ;
        }
        if nqp::istype($n, QAST::Var) {
            return self.encode_var($n, %e, nqp::null(), $want);
        }
        if nqp::istype($n, QAST::Want) {
            # Mirror the compiler's want() selector: a typed or void want
            # picks the matching variant ('v' hides BEGIN-parked dead code
            # like the runtime ENUM_VALUES call); anything else takes the
            # default and post-coerces.
            my $sel := $n[0];
            if $want == $T_VOID || $want == $T_INT
                || $want == $T_NUM || $want == $T_STR {
                my str $char := $want == $T_VOID ?? 'v'
                    !! $want == $T_INT ?? 'I'
                    !! $want == $T_NUM ?? 'N' !! 'S';
                my int $i := 1;
                my int $nn := nqp::elems(@($n));
                while $i < $nn {
                    if nqp::index($n[$i], $char) >= 0 {
                        $sel := $n[$i + 1];
                        $i := $nn;
                    }
                    else {
                        $i := $i + 2;
                    }
                }
            }
            return self.encode_node($sel, %e, $want);
        }
        if nqp::istype($n, QAST::IVal) {
            epush(%e, $W_IVAL);
            epush(%e, epool(%e, ~$n.value));
            return $T_INT;
        }
        if nqp::istype($n, QAST::NVal) {
            my num $v := $n.value;
            my str $s := ~$v;
            # Java's parser spells the specials differently.
            $s := 'Infinity'  if $s eq 'Inf';
            $s := '-Infinity' if $s eq '-Inf';
            epush(%e, $W_NVAL);
            epush(%e, epool(%e, $s));
            return $T_NUM;
        }
        if nqp::istype($n, QAST::SVal) {
            epush(%e, $W_SVAL);
            epush(%e, epool(%e, $n.value));
            return $T_STR;
        }
        if nqp::istype($n, QAST::WVal) {
            my $val := $n.value;
            my $sc := nqp::getobjsc($val);
            cbail('wval not in an SC') if nqp::isnull($sc);
            epush(%e, $W_WVAL);
            epush(%e, epool(%e, nqp::scgethandle($sc)));
            epush(%e, nqp::scgetobjidx($sc, $val));
            return $T_OBJ;
        }
        if nqp::istype($n, QAST::Stmts) || nqp::istype($n, QAST::Stmt)
            || nqp::istype($n, QAST::NodeList) {
            return self.encode_stmts_children($n, %e, $want);
        }
        if nqp::istype($n, QAST::Block) {
            my str $bt := $n.blocktype;
            if $bt eq '' || $bt eq 'declaration' || $bt eq 'declaration_static' {
                cbail('nested block in a parameter default')
                    if nqp::existskey(%e, 'inparams') && %e<inparams>;
                # Reference it exactly as a BVal would; the compilation is
                # deferred until this block has committed (registered its
                # lexicals), so the nested block resolves outers against
                # the real table and a later bail leaves no orphans.
                epush(%e, $W_CODEREF);
                nqp::push(%e<nested>, [nqp::elems(%e<code>), $n]);
                epush(%e, 0);   # qbid, patched after the deferred compile
                return $T_OBJ;
            }
            if $bt eq 'immediate' || $bt eq 'immediate_static' {
                # Compiled as a code object and called at once with no
                # arguments, which is what the bytecode path's direct call
                # of the block's code ref comes to (Compiler.nqp: an
                # emptyCallSite, whatever the block's arity or count). A
                # `count` annotation alone marks an implicit OPTIONAL topic
                # (a bare block's `$_`, arity 0): zero arguments is exactly
                # what the bytecode passes, and the parameter's default
                # resolves in the callee's own binder either way -- so it
                # encodes. Only a REQUIRED parameter (arity > 0) still
                # bails; the if/with road passes the condition to those.
                cbail('nested block in a parameter default')
                    if nqp::existskey(%e, 'inparams') && %e<inparams>;
                cbail('immediate block wanting arguments')
                    if $n.arity > 0;
                self.encode_immediate_call($n, %e, 0, 0);
                return $T_OBJ;
            }
            cbail('block ' ~ $bt);
        }
        if nqp::istype($n, QAST::BVal) {
            cbail('bval to a block the unit never compiles')
                unless $*CODEREFS.know_cuid($n.value.cuid)
                    || block_in_tree(%e<qast>, $n.value.cuid, 1);
            epush(%e, $W_CODEREF);
            nqp::push(%e<nested>, [nqp::elems(%e<code>), $n.value]);
            epush(%e, 0);
            return $T_OBJ;
        }
        if nqp::istype($n, QAST::Regex) {
            return self.encode_regex($n, %e);
        }
        if nqp::istype($n, QAST::ParamTypeCheck) {
            # Compiles exactly as emit_param_tasks does: the check value
            # feeds assertparamcheck, which turns a miss into a bind
            # failure a multi can try past rather than a throw.
            epush(%e, $W_OPCALL); epush(%e, 112); epush(%e, 1);
            self.encode_child($n[0], %e, $T_INT);
            return $T_OBJ;
        }
        if nqp::istype($n, QAST::VM) {
            # Exactly what as_jast(QAST::VM) does: the backend picks its
            # own alternative and the rest of the node is not ours. A node
            # with no 'jvm' alternative would not compile on the class
            # road either, so it bails rather than dying here.
            cbail('QAST::VM without a jvm alternative') unless $n.supports('jvm');
            return self.encode_node($n.alternative('jvm'), %e, $want);
        }
        cbail('node ' ~ $n.HOW.name($n));
    }

    method encode_stmts_children($n, %e, int $want) {
        my @kids;
        for @($n) {
            nqp::push(@kids, $_) if nqp::istype($_, QAST::Node);
        }
        my int $count := nqp::elems(@kids);
        if $count == 0 {
            epush(%e, $W_JNULL);
            return $T_OBJ;
        }
        # An explicit resultchild (topicalization save/restore wraps the
        # real result) rides a scratch local: the designated child's value
        # is stored, the rest run void, and the local is the value.
        my $rc := nqp::can($n, 'resultchild') ?? $n.resultchild !! nqp::null();
        my int $has_rc := !nqp::isnull($rc) && nqp::defined($rc) && $rc != $count - 1;
        if $has_rc {
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS);
            epush(%e, $count + 1);
            my int $i := 0;
            while $i < $count {
                if $i == $rc {
                    epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
                    self.encode_child(@kids[$i], %e, $T_OBJ);
                }
                else {
                    self.encode_node(@kids[$i], %e, $T_VOID);
                }
                $i++;
            }
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            return $T_OBJ;
        }
        epush(%e, $W_STMTS);
        epush(%e, $count);
        my int $i := 0;
        my int $type := $T_OBJ;
        while $i < $count {
            $type := self.encode_node(@kids[$i], %e,
                $i == $count - 1 ?? $want !! $T_VOID);
            $i++;
        }
        $type
    }

    # Ops whose runtime asks the CURRENT FRAME for the block's own code ref,
    # caller, or phaser flags -- things a frame-free block, which has no
    # frame, cannot answer (tc.curFrame is its caller's). The current-HLL
    # readers (hllbool, hllize, getattr's native boxing ...) are NOT here:
    # they are tracked separately, in %hll_ops below: a block that runs
    # none of them may be entered frame-free ACROSS languages (wire bit 1),
    # the rest only in the caller's language, where the caller's frame
    # answers the right unit. spesh's inline.c has both rules: no :useshll
    # op across HLLs, and no inlining of frames that introspect themselves.
    my %frame_forcing_ops := nqp::hash(
        # The throw family and the loop controls read the block's OWN frame
        # after the handler runs -- a block handler that resumes leaves its
        # result in the frame's return register (Ops.result_o(cf)), and a
        # frame-free block has cf == null there (2026-09-08: t/nqp/044's
        # resumed die from a frame-free `oops()` NPE'd on cf.retType). The
        # continuation ops and ctx hand cf to the runtime the same way.
        'die', 1, 'die_s', 1, 'throw', 1, 'rethrow', 1, 'throwextype', 1,
        'throwpayloadlex', 1, 'throwpayloadlexcaller', 1, 'control', 1,
        'continuationreset', 1, 'continuationcontrol', 1, 'continuationinvoke', 1,
        'ctx', 1,
        'curcode', 1, 'callercode', 1, 'getcodecuid', 1,
        'getlexcaller', 1, 'getlexrelcaller', 1, 'ctxcaller', 1, 'ctxcallerskipthunks', 1,
        'backtrace', 1, 'backtracestrings', 1,
        'p6bindsig', 1, 'p6trybindsig', 1, 'p6bindcaptosig', 1, 'p6isbindable', 1,
        'p6capturelex', 1, 'p6capturelexwhere', 1,
        'p6finddispatcher', 1, 'p6argsfordispatcher', 1, 'p6bindfailerror', 1,
        'p6setpre', 1, 'p6clearpre', 1, 'p6inpre', 1, 'p6stateinit', 1, 'p6takefirstflag', 1,
    );

    # Ops whose runtime reads the CURRENT language off tc's frame (spesh's
    # :useshll): a frame-free callee entered across languages would read its
    # caller's. The union of MoarVM's oplist marks and the JVM runtime's own
    # readers (every Ops function that reaches hllConfig through the frame,
    # mapped back to its op name through the classlib table; 2026-09-07).
    # getattr is NOT here: the engine boxes a native slot with the block's
    # own unit (NqpOps.getattrSlow -> Ops.getattrIn). Ops naming their
    # language (hllizefor, hllboolfor) read no frame either. hllize is NOT
    # here either (2026-09-08): its site resolves the wanted config from the
    # block's own comp unit (NqpTypeOps.hllize -> NqpRaw.hll(cu)), which
    # travels as ARG_CU and is invariant to the caller -- a frame-free entry
    # across languages still hllizes into the block's own HLL. MoarVM marks
    # it :useshll only because spesh merges an inlined frame; we never do.
    my %hll_ops := nqp::hash(
        'hllbool', 1, 'hllboxtype_i', 1, 'hllboxtype_n', 1, 'hllboxtype_s', 1,
        'hlllist', 1, 'hllhash', 1, 'getcurhllsym', 1, 'bindcurhllsym', 1,
        'usecompileehllconfig', 1, 'usecompilerhllconfig', 1,
        'die', 1, 'die_s', 1, 'newexception', 1,
        'iterator', 1, 'iter', 1, 'split', 1, 'radix', 1, 'radix_I', 1,
        'getlexdyn', 1, 'getlexreldyn', 1,
        'captureposarg', 1, 'capturenamedshash', 1, 'capturehasnameds', 1,
        'getlexref_i', 1, 'getlexref_n', 1, 'getlexref_s', 1, 'getlexref_u', 1,
        'getlexref_i32', 1, 'getlexref_i16', 1, 'getlexref_i8', 1, 'getlexref_n32', 1,
        'getlexref_u32', 1, 'getlexref_u16', 1, 'getlexref_u8', 1,
        'getlexref_ni', 1, 'getlexref_nn', 1, 'getlexref_ns', 1, 'getlexref_nu', 1,
        'getattrref_i', 1, 'getattrref_n', 1, 'getattrref_s', 1, 'getattrref_u', 1,
        'getattrsref_i', 1, 'getattrsref_n', 1, 'getattrsref_s', 1, 'getattrsref_u', 1,
        'atposref_i', 1, 'atposref_n', 1, 'atposref_s', 1, 'atposref_u', 1,
        'multidimref_i', 1, 'multidimref_n', 1, 'multidimref_s', 1, 'multidimref_u', 1,
        'getenvhash', 1, 'clargs', 1, 'getsignals', 1, 'getrusage', 1, 'backendconfig', 1,
        'getstdin', 1, 'getstdout', 1, 'getstderr', 1,
        'open', 1, 'openasync', 1, 'opendir', 1, 'socket', 1,
        'getcodelocation', 1, 'compunitcodes', 1,
        'jvmclasspaths', 1, 'jvmgetproperties', 1, 'jvmgetconfig', 1,
    );

    method encode_op($op, %e, int $want) {
        my str $name := $op.op;

        %e<frame_op> := 1 if nqp::existskey(%frame_forcing_ops, $name);
        %e<uses_hll> := 1 if nqp::existskey(%hll_ops, $name);
        # A generic return type instantiates against the routine's own frame
        # (RakOps.p6typecheckrv makes a ContextRef of tc.curFrame): framed.
        # Unknown is treated as generic.
        if $name eq 'p6typecheckrv' {
            my int $generic := 1;
            if nqp::elems(@($op)) == 3 && nqp::istype($op[1], QAST::WVal) {
                my $block := $op[1].value;
                try {
                    my $returns := $block.signature.returns;
                    $generic := nqp::isnull($returns) ?? 0
                        !! ($returns.HOW.archetypes($returns).generic ?? 1 !! 0);
                }
            }
            %e<frame_op> := 1 if $generic;
        }

        if $name eq 'bind' {
            cbail('bind arity') unless nqp::elems(@($op)) == 2;
            return self.encode_var($op[0], %e, $op[1], $want);
        }
        if $name eq 'assign_i' || $name eq 'assign_u' || $name eq 'assign_n'
                || $name eq 'assign_s' {
            # Compiler.nqp's typed assign: a native lexical/attribute
            # reference target is a plain bind to the variable, anything
            # else a container store. The bytecode desugar rewrites the
            # node in place; here the rewrite lands on a COPY of the
            # target, so a later bail hands the bytecode path its tree
            # untouched. This was the top sole-blocker of CORE.c (223).
            cbail('assign arity') unless nqp::elems(@($op)) == 2;
            my str $bind_scope := self.native_assign_bind_scope($op[0], %e);
            if $bind_scope ne '' {
                my $target := $op[0].shallow_clone;
                $target.scope($bind_scope);
                return self.encode_var($target, %e, $op[1], $want);
            }
            return self.encode_op(QAST::Op.new( :op('jvm_container_' ~ $name),
                $op[0], $op[1] ), %e, $want);
        }
        if $name eq 'if' || $name eq 'unless' {
            return self.encode_if($op, %e, $want, $name eq 'unless' ?? 1 !! 0);
        }
        if $name eq 'with' || $name eq 'without' {
            return self.encode_if($op, %e, $want, $name eq 'without' ?? 1 !! 0, 1);
        }
        if $name eq 'while' || $name eq 'until'
            || $name eq 'repeat_while' || $name eq 'repeat_until' {
            # repeat_* is the same loop with the body run once ahead
            # of the first test; the wire form has carried that flag
            # since Phase 3 and nothing set it until now.
            my int $repeat := nqp::eqat($name, 'repeat_', 0) ?? 1 !! 0;
            my int $is_until := ($name eq 'until' || $name eq 'repeat_until') ?? 1 !! 0;
            my int $nohandler := 0;
            my $label_node;
            my @operands;
            for @($op) {
                if $_.named eq 'nohandler' { $nohandler := 1 }
                elsif $_.named eq 'label' { $label_node := $_ }
                else { nqp::push(@operands, $_) }
            }
            my int $has_label := nqp::defined($label_node) ?? 1 !! 0;
            # 2 operands = cond+body; a 3rd is the loop's "next" expression
            # (a C-style `loop(init;cond;incr)` increment or a NEXT-phaser
            # body), run after the body and after a NEXT unwind, before the
            # cond re-test -- exactly Compiler.nqp's 2-or-3 operand shape.
            cbail('loop shape')
                unless nqp::elems(@operands) == 2 || nqp::elems(@operands) == 3;
            my int $has_next := nqp::elems(@operands) == 3 ?? 1 !! 0;
            # A repeat_ loop runs its body once ahead of the first test; the
            # "next" would have to run in that pre-run too, and getting that
            # ordering wrong is worse than not encoding this rare combination.
            cbail('repeat loop with next-expr') if $repeat && $has_next;
            # A body that takes the condition (`while $x -> $y {}`): the
            # bytecode path binds the condition into an __IM_ local and
            # calls the body with it. Here the condition child becomes
            # [bind scratch local; read it] and the body a call of the
            # block with that local, through the same road if/with use.
            my int $im := needs_cond_passed(@operands[1]);
            my int $im_tmp := $im ?? new_elocal(%e, $T_OBJ) !! 0;
            if $nohandler {
                # A label is only meaningful with the last/next/redo regions
                # that carry a labeled unwind; a nohandler loop never has one.
                cbail('labeled nohandler loop') if $has_label;
                epush(%e, $W_LOOP);
                epush(%e, $is_until);
                epush(%e, $repeat);
                epush(%e, $has_next);
                my int $ct_at := nqp::elems(%e<code>);
                epush(%e, 0);
                my int $condt := self.encode_loop_cond(@operands[0], %e, $im, $im_tmp);
                nqp::bindpos(%e<code>, $ct_at, $condt);
                if $im { self.encode_immediate_call(@operands[1], %e, 1, $T_OBJ, $im_tmp) }
                else { self.encode_node(@operands[1], %e, $T_VOID) }
                if $has_next { self.encode_node(@operands[2], %e, $T_VOID) }
                return $T_OBJ;
            }
            # A handled loop: register the same LAST and NEXT|REDO rows the
            # bytecode path registers -- the runtime's handler walk reads
            # them from this block's StaticCodeInfo through the live
            # CallFrame -- and let the program delimit the regions and
            # catch the unwinds (W_LOOPH). Rows registered before a later
            # bail are harmless orphans: the walk follows the curHandler
            # chain, and nothing ever names an orphan's id.
            my int $outer := %e<hidx>;
            my int $lid := &*REGISTER_UNWIND_HANDLER($outer, $EX_CAT_LAST, :ex_obj(1));
            my int $nrid := &*REGISTER_UNWIND_HANDLER($lid, $EX_CAT_NEXT +| $EX_CAT_REDO, :ex_obj(1));
            # A labeled loop keeps its label object in a scratch local; the
            # unwind arms read it as the `where` for _is_same_label (an
            # unlabeled loop passes null -> _rethrow_label). The value is
            # evaluated once at loop entry, outside the regions.
            my int $lbl_local := $has_label ?? new_elocal(%e, $T_OBJ) !! 0;
            # A repeat_ loop runs its body once ahead of the first cond test,
            # inside these same last/next/redo regions (Compiler.nqp's
            # `goto redo_lbl` before the test). The builder duplicates the
            # body-with-redo emission for that pre-run when repeat is set.
            %e<frame_op> := 1;
            epush(%e, $W_LOOPH);
            epush(%e, $is_until);
            epush(%e, $repeat);
            epush(%e, $has_next);
            epush(%e, $has_label);
            epush(%e, $lbl_local);
            my int $ct_at := nqp::elems(%e<code>);
            epush(%e, 0);
            epush(%e, $lid);
            epush(%e, $nrid);
            epush(%e, $outer);
            if $has_label {
                # The label value, bound into $lbl_local by the builder before
                # the loop's try; evaluated in the outer handler context.
                %e<hidx> := $outer;
                self.encode_child($label_node, %e, $T_OBJ);
            }
            %e<hidx> := $lid;
            my int $condt := self.encode_loop_cond(@operands[0], %e, $im, $im_tmp);
            nqp::bindpos(%e<code>, $ct_at, $condt);
            %e<hidx> := $nrid;
            if $im { self.encode_immediate_call(@operands[1], %e, 1, $T_OBJ, $im_tmp) }
            else { self.encode_node(@operands[1], %e, $T_VOID) }
            # The "next" expression runs under the LAST handler (a `last` in
            # it still exits the loop; a `next`/`redo` there is not caught),
            # after the body and after a NEXT unwind -- Compiler.nqp:1370.
            %e<hidx> := $lid;
            if $has_next { self.encode_node(@operands[2], %e, $T_VOID) }
            %e<hidx> := $outer;
            return $T_OBJ;
        }
        if $name eq 'p6decontrv' || $name eq 'p6decontrv_6c' {
            # Mirror the bytecode emitter's compile-time split: an rw
            # routine's return passes through untouched; otherwise the
            # cached-per-routine decont road (wantdecont is a pass-through
            # on this backend, so the value child encodes directly).
            cbail('p6decontrv shape')
                unless nqp::elems(@($op)) == 2 && nqp::istype($op[0], QAST::WVal);
            if nqp::istrue($op[0].value.rw) {
                return self.encode_child($op[1], %e, $want == $T_VOID ?? $T_VOID !! $T_OBJ);
            }
            epush(%e, $W_OPCALL); epush(%e, 115); epush(%e, 3);
            self.encode_child($op[0], %e, $T_OBJ);
            self.encode_child($op[1], %e, $T_OBJ);
            self.encode_child(QAST::IVal.new(
                :value($name eq 'p6decontrv_6c' ?? 1 !! 0)), %e, $T_INT);
            return $T_OBJ;
        }
        # list/hash/list_i/list_n/list_s DELIBERATELY absent. A desugar for
        # them (create the type, push into a scratch local, exactly as
        # Compiler.nqp does) was written and reverted on 2026-09-02: it is
        # correct in isolation -- construction, nesting and the typed
        # variants all match bytecode -- but with it in BOOTSTRAP the
        # signature binder mis-handles a flattened named argument
        # ("Unexpected named argument '1' passed", from
        # `OperatorProperties.new(|%value, ...)` while compiling CORE.c;
        # rebuilding BOOTSTRAP engine-free makes it go away). The ops it
        # needs (hlllist/hllhash/boot*array/push_i/push_n/push_s, ids
        # 139-147) are still in NqpOps.java, so re-landing it means
        # restoring this branch plus its table rows -- but not before that
        # binder interaction is understood.
        if $name eq 'hash' {
            # The hash constructor, parallel to the list ones: create the
            # hash type into a scratch local, then bindkey each key/value
            # pair. Held out until now because an engine-built hash meeting
            # an engine-bound named-parameter prologue reproduced the
            # BOOTSTRAP binder bug (OperatorProperties.new) -- the native
            # parameter binder that landed since changed that prologue, so
            # this is being re-tested against it. Keys are strings, values
            # objects; an odd child count is a malformed hash the bytecode
            # path would reject too.
            my @children := $op.list;
            my int $items := nqp::elems(@children);
            cbail('hash odd child count') if $items % 2;
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS);
            epush(%e, ($items / 2) + 2);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            epush(%e, $W_OPCALL); epush(%e, 58); epush(%e, 1);    # create
            epush(%e, $W_OPCALL); epush(%e, 140); epush(%e, 0);   # hllhash
            my int $i := 0;
            while $i < $items {
                epush(%e, $W_OPCALL); epush(%e, 68); epush(%e, 3);   # bindkey
                epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
                self.encode_child(@children[$i], %e, $T_STR);
                self.encode_child(@children[$i + 1], %e, $T_OBJ);
                $i := $i + 2;
            }
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            return $T_OBJ;
        }
        if $name eq 'list' || $name eq 'list_i'
            || $name eq 'list_n' || $name eq 'list_s' {
            # The list constructors, the same desugar Compiler.nqp uses:
            # create the array type into a scratch local, then push each
            # child into it. `hash` is DELIBERATELY not here -- it alone
            # reproduces the BOOTSTRAP binder bug (bisected 2026-09-03 to
            # OperatorProperties.new); the list family was tested apart
            # from it and was never implicated.
            my @children := $op.list;
            my int $items := nqp::elems(@children);
            my int $type_op := $name eq 'list_i' ?? 142
                !! $name eq 'list_n' ?? 143
                !! $name eq 'list_s' ?? 144 !! 139;
            my int $push_op := $name eq 'list_i' ?? 145
                !! $name eq 'list_n' ?? 146
                !! $name eq 'list_s' ?? 147 !! 61;
            my int $elem_want := $name eq 'list_i' ?? $T_INT
                !! $name eq 'list_n' ?? $T_NUM
                !! $name eq 'list_s' ?? $T_STR !! $T_OBJ;
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS);
            epush(%e, $items + 2);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            epush(%e, $W_OPCALL); epush(%e, 58); epush(%e, 1);
            epush(%e, $W_OPCALL); epush(%e, $type_op); epush(%e, 0);
            my int $i := 0;
            while $i < $items {
                epush(%e, $W_OPCALL); epush(%e, $push_op); epush(%e, 2);
                epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
                self.encode_child(@children[$i], %e, $elem_want);
                $i++;
            }
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            return $T_OBJ;
        }
        if $name eq 'call' || $name eq 'callstatic' {
            return self.encode_call($op, %e);
        }
        if $name eq 'callmethod' {
            return self.encode_callmethod($op, %e);
        }
        if $name eq 'sprintf' || $name eq 'sprintfdirectives'
            || $name eq 'sprintfaddargumenthandler' {
            # All three desugar to a call of the nqp hll sub of the same
            # name, exactly Compiler.nqp: call(gethllsym('nqp', name), args)
            # returning str (int for sprintfdirectives).
            my $call := QAST::Op.new( :op('call'),
                :returns($name eq 'sprintfdirectives' ?? int !! str),
                QAST::Op.new( :op('gethllsym'),
                    QAST::SVal.new( :value('nqp') ),
                    QAST::SVal.new( :value($name) ) ) );
            for @($op) { $call.push($_) }
            return self.encode_node($call, %e, $want);
        }
        if $name eq 'xor' {
            return self.encode_xor($op, %e, $want);
        }
        if $name eq 'falsey' {
            # falsey(x): the logical negation of truthiness, an int. not_i and
            # istrue are both covered (istrue is classlib [RT_OBJ]->RT_INT), so
            # not_i(istrue(x)); a native operand coerces to obj for istrue,
            # matching the JAST handler's boxed result. Non-committing.
            cbail('falsey arity') unless nqp::elems(@($op)) == 1;
            return self.encode_op(QAST::Op.new( :op('not_i'),
                QAST::Op.new( :op('istrue'), $op[0] ) ), %e, $want);
        }
        if $name eq 'numify' {
            # numify(x): x in num context, exactly Compiler.nqp's as_jast(x,
            # :want(NUM)). encode_CHILD, not encode_node: the node road only
            # passes the want down and answers whatever type the child has,
            # so numify(~$/) came back a str and dec_number handed
            # QAST::NVal.new a P6str (2026-09-08, t/nqp/041-flat.t). The
            # child road inserts the coercion and answers num.
            cbail('numify arity') unless nqp::elems(@($op)) == 1;
            return self.encode_child($op[0], %e, $T_NUM);
        }
        if $name eq 'stringify' || $name eq 'intify' {
            # Coerce the child to the wanted native type via encode_child (not
            # encode_node): that inserts $W_COERCE, whose obj->str/int road is
            # Ops.smart_stringify/smart_intify -- the proper stringification,
            # not a raw unbox (which failed on a P6opaque). Mirrors the JAST
            # as_jast(x, :want(STR/INT)) path.
            cbail($name ~ ' arity') unless nqp::elems(@($op)) == 1;
            return self.encode_child($op[0], %e, $name eq 'stringify' ?? $T_STR !! $T_INT);
        }
        # ++/-- on a variable: bind var (add_i/sub_i <read> 1), the original
        # QAST::Var kept as the bind lvalue. The read differs by the var's
        # native spec (nqp::objprimspec, as native_assign_bind_scope uses):
        #   object var (spec 0) -- the value may be null (an uninitialized
        #     `my $x`), which the op auto-vivifies to 0 before ++/-- (so
        #     `my $x; --$x == -1`). The JAST add_i unboxes null as 0; the
        #     engine's native add_i would blow up on it, so read defined-or-0.
        #   native var (spec 1/2/3) -- never null (0-initialised), read direct.
        # Reads are shallow_clones so no node is encoded twice.
        if $name eq 'preinc' || $name eq 'predec' {
            cbail($name ~ ' arity') unless nqp::elems(@($op)) == 1;
            cbail($name ~ ' target not a var') unless nqp::istype($op[0], QAST::Var);
            my str $aop  := $name eq 'preinc' ?? 'add_i' !! 'sub_i';
            my int $spec := nqp::isnull($op[0].returns) ?? 0 !! nqp::objprimspec($op[0].returns);
            # Fresh-tree contract: NOTHING may reference the input node -- if
            # encoding this rewrite bails, encode_block hands the ORIGINAL
            # block to the bytecode path, and a shared node left in a
            # half-encoded state corrupts it (obtain NPE at Compiler.nqp:3994).
            # So clone the target too, not just the reads.
            my $target := $op[0].shallow_clone;
            my $read;
            if $spec == 0 {
                $read := QAST::Op.new( :op('if'),
                    QAST::Op.new( :op('isnull'), $op[0].shallow_clone ),
                    QAST::IVal.new( :value(0) ),
                    $op[0].shallow_clone );
            }
            else {
                $read := $op[0].shallow_clone;
            }
            return self.encode_op(QAST::Op.new( :op('bind'), $target,
                QAST::Op.new( :op($aop), $read, QAST::IVal.new( :value(1) ) ) ), %e, $want);
        }
        # stringify/intify still deferred: unlike numify, their obj->str/int
        # coercion is not a plain unbox -- an arbitrary object needs the HLL's
        # stringification (JAST's :want(STR) path), and encode_node's coercion
        # raw-unboxes ("P6opaque cannot unbox to a native string", 2026-09-08).
        if $name eq 'settypefinalize' {
            # A no-op stub on the JVM (Compiler.nqp: as_jast($op[0])); the
            # finalize wiring is not hooked up, so just yield the child.
            cbail('settypefinalize arity') unless nqp::elems(@($op)) >= 1;
            return self.encode_node($op[0], %e, $want);
        }
        if $name eq 'p6invokeflat' {
            # p6invokeflat(callee, args): a call with the argument list
            # flattened, exactly the registered desugar (src/vm/jvm/Raku/
            # Ops.nqp): $op[1].flat(1); call($op[0], $op[1]).
            cbail('p6invokeflat arity') unless nqp::elems(@($op)) == 2;
            $op[1].flat(1);
            return self.encode_node(
                QAST::Op.new( :op('call'), $op[0], $op[1] ), %e, $want);
        }
        if $name eq 'chain' || $name eq 'chainstatic' {
            # A nested chain (`$a < $b < $c`) short-circuits and shares the
            # middle operand, exactly as Compiler.nqp's chain_codegen: each
            # link is a lang-call comparing the previous operand with the
            # next, and a false link is the result. Desugar the whole nest
            # into binds + ifs (a fresh tree over the same children, so a
            # later bail hands the bytecode path the untouched op) and let
            # the simple-chain road below encode each individual link.
            if nqp::istype($op[0], QAST::Op)
                && ($op[0].op eq 'chain' || $op[0].op eq 'chainstatic') {
                my @callees;
                my @operands;
                my $cur := $op;
                while nqp::istype($cur, QAST::Op)
                    && ($cur.op eq 'chain' || $cur.op eq 'chainstatic') {
                    cbail('unnamed chain link') if $cur.name eq '';
                    cbail('chain link arity') unless nqp::elems(@($cur)) == 2;
                    nqp::unshift(@callees, $cur.name);
                    nqp::unshift(@operands, $cur[1]);
                    $cur := $cur[0];
                }
                nqp::unshift(@operands, $cur);
                my int $nlinks := nqp::elems(@callees);
                my @v;
                my int $k := 0;
                while $k <= $nlinks { nqp::push(@v, $op.unique('chain_o')); $k++ }
                my @r;
                $k := 0;
                while $k < $nlinks { nqp::push(@r, $op.unique('chain_r')); $k++ }
                # link($i): result of links $i..end, with @v[$i] and @v[$i+1] bound.
                my $linkq;
                $linkq := -> int $i {
                    my $call := QAST::Op.new( :op('call'), :name(@callees[$i]),
                        QAST::Var.new( :name(@v[$i]),   :scope('local') ),
                        QAST::Var.new( :name(@v[$i + 1]), :scope('local') ) );
                    $i == $nlinks - 1
                        ?? $call
                        !! QAST::Stmts.new(
                             QAST::Op.new( :op('bind'),
                               QAST::Var.new( :name(@r[$i]), :scope('local'), :decl('var') ),
                               $call ),
                             QAST::Op.new( :op('if'),
                               QAST::Var.new( :name(@r[$i]), :scope('local') ),
                               QAST::Stmts.new(
                                 QAST::Op.new( :op('bind'),
                                   QAST::Var.new( :name(@v[$i + 2]), :scope('local'), :decl('var') ),
                                   @operands[$i + 2] ),
                                 $linkq($i + 1) ),
                               QAST::Var.new( :name(@r[$i]), :scope('local') ) ) );
                };
                my $tree := QAST::Stmts.new(
                    QAST::Op.new( :op('bind'),
                      QAST::Var.new( :name(@v[0]), :scope('local'), :decl('var') ), @operands[0] ),
                    QAST::Op.new( :op('bind'),
                      QAST::Var.new( :name(@v[1]), :scope('local'), :decl('var') ), @operands[1] ),
                    $linkq(0) );
                return self.encode_node($tree, %e, $want);
            }
            # A simple (non-nested) link: named (callee is $op.name, two
            # operands) or unnamed (callee is $op[0], two operands after).
            my int $named := $op.name ne '';
            cbail('chain arity') unless nqp::elems(@($op)) == ($named ?? 2 !! 3);
            my $lop := $named ?? $op[0] !! $op[1];
            my $rop := $named ?? $op[1] !! $op[2];
            epush(%e, $W_DISPATCH);
            %e<dispatches> := %e<dispatches> + 1;
            epush(%e, rt_of($op.returns));
            epush(%e, epool(%e, 'lang-call'));
            epush(%e, 3);
            epush(%e, $T_OBJ); epush(%e, $T_OBJ); epush(%e, $T_OBJ);
            if $named {
                self.encode_op_named_lexical_decont($op.name, %e);
            }
            else {
                epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
                self.encode_child($op[0], %e, $T_OBJ);
            }
            self.encode_child($lop, %e, $T_OBJ);
            self.encode_child($rop, %e, $T_OBJ);
            return rt_of($op.returns);
        }
        return self.encode_op_tail($op, $name, %e, $want);
    }

    # The tail of encode_op's op dispatch, split out so neither half crosses
    # the 64KB JVM method limit -- above it jast2bc's AutosplitMethodWriter
    # takes a split path it mishandles (a null/unreachable frame -> NPE).
    # $name is the op name already computed by encode_op.
    # nqp::for(list, block): the iterator loop Compiler.nqp's add_core_op('for')
    # builds. The list, its iterator and the block ride scratch locals; each
    # iteration fetches the block's arity values (shift) into locals and
    # calls the block with them. The handled form is its own wire op,
    # W_FORLOOP: the same LAST / NEXT|REDO regions a handled while has, but
    # with the fetch OUTSIDE the redo loop and only the call inside it, so
    # `redo` re-runs the call with the same values while `next` re-fetches
    # (Compiler.nqp's goto redo_lbl sits between the fetch and the call).
    # No per-iteration allocation: the earlier desugar through `handle`
    # failed because a handle's handler is a nested block -- a separate frame
    # that cannot see this block's locals -- and would have taken a closure
    # per iteration besides. :nohandler is the plain W_LOOP over fetch+call.
    # Value context answers the iterated list; void answers nothing. The
    # labelled form (NQP's Actions push :label onto nqp::for, t/nqp/084)
    # is W_FORLOOPL: the same shape with a label local the unwind arms read.
    method encode_for($op, %e, int $want) {
        my int $nohandler := 0;
        my $label_node;
        my @ops;
        for @($op) {
            if $_.named eq 'nohandler' { $nohandler := 1 }
            elsif $_.named eq 'label' { $label_node := $_ }
            elsif $_.named ne '' { cbail('for :' ~ $_.named) }
            else { nqp::push(@ops, $_) }
        }
        my int $has_label := nqp::defined($label_node) ?? 1 !! 0;
        cbail('labeled nohandler for') if $has_label && $nohandler;
        cbail('for needs 2 operands') unless nqp::elems(@ops) == 2;
        cbail('for block not a block') unless nqp::istype(@ops[1], QAST::Block);
        my $blk := @ops[1];
        my str $bt := $blk.blocktype;
        $blk.blocktype('declaration') if $bt eq 'immediate';
        $blk.blocktype('declaration_static') if $bt eq 'immediate_static';
        my int $arity := $blk.arity || 1;
        my str $iterL  := QAST::Node.unique('for_iter');
        my str $blockL := QAST::Node.unique('for_block');
        my str $listL  := QAST::Node.unique('for_list');
        my sub lv($n)  { QAST::Var.new( :name($n), :scope('local') ) }
        my sub ld($n)  { QAST::Var.new( :name($n), :scope('local'), :decl('var') ) }
        my $call := QAST::Op.new( :op('call'), lv($blockL) );
        my $pre := QAST::Stmts.new();
        my int $i := 0;
        while $i < $arity {
            my str $t := QAST::Node.unique('for_v');
            $pre.push(QAST::Op.new( :op('bind'), ld($t),
                QAST::Op.new( :op('shift'), lv($iterL) ) ));
            $call.push(lv($t));
            $i := $i + 1;
        }
        my $cond := QAST::Op.new( :op('istrue'), lv($iterL) );

        # [bind list; bind iter; bind block; loop; (list)]
        epush(%e, $W_STMTS);
        epush(%e, $want == $T_VOID ?? 4 !! 5);
        self.encode_node(QAST::Op.new( :op('bind'), ld($listL), @ops[0] ), %e, $T_VOID);
        self.encode_node(QAST::Op.new( :op('bind'), ld($iterL),
            QAST::Op.new( :op('iterator'), lv($listL) ) ), %e, $T_VOID);
        self.encode_node(QAST::Op.new( :op('bind'), ld($blockL), $blk ), %e, $T_VOID);
        if $nohandler {
            # W_LOOP until=0 repeat=0 hasNext=0 condType cond body
            epush(%e, $W_LOOP);
            epush(%e, 0); epush(%e, 0); epush(%e, 0);
            my int $ct_at := nqp::elems(%e<code>);
            epush(%e, 0);
            nqp::bindpos(%e<code>, $ct_at, self.encode_node($cond, %e, $T_ANY));
            $pre.push($call);
            self.encode_node($pre, %e, $T_VOID);
        }
        else {
            # The same rows a handled while registers; the runtime's handler
            # walk reads them from this block's StaticCodeInfo.
            my int $outer := %e<hidx>;
            my int $lid := &*REGISTER_UNWIND_HANDLER($outer, $EX_CAT_LAST, :ex_obj(1));
            my int $nrid := &*REGISTER_UNWIND_HANDLER($lid, $EX_CAT_NEXT +| $EX_CAT_REDO, :ex_obj(1));
            %e<frame_op> := 1;
            my int $lbl_local := $has_label ?? new_elocal(%e, $T_OBJ) !! 0;
            epush(%e, $has_label ?? $W_FORLOOPL !! $W_FORLOOP);
            my int $ct_at := nqp::elems(%e<code>);
            epush(%e, 0);
            epush(%e, $lid);
            epush(%e, $nrid);
            epush(%e, $outer);
            if $has_label {
                # The label value, bound into $lbl_local by the builder before
                # the loop's try; evaluated in the outer handler context.
                epush(%e, $lbl_local);
                %e<hidx> := $outer;
                self.encode_child($label_node, %e, $T_OBJ);
            }
            %e<hidx> := $lid;
            nqp::bindpos(%e<code>, $ct_at, self.encode_node($cond, %e, $T_ANY));
            %e<hidx> := $nrid;
            self.encode_node($pre, %e, $T_VOID);
            self.encode_node($call, %e, $T_VOID);
            %e<hidx> := $outer;
        }
        return $T_OBJ if $want == $T_VOID;   # the loop's null, discarded
        self.encode_node(lv($listL), %e, $T_OBJ)
    }

    method encode_op_tail($op, str $name, %e, int $want) {
        if $name eq 'for' {
            return self.encode_for($op, %e, $want);
        }
        if $name eq 'postinc' || $name eq 'postdec' {
            # NQP/Ops.nqp's postinc: the old value into a scratch local, the
            # variable rebound to it +/- 1, the local as the value. The same
            # fresh-tree contract and null->0 auto-vivification as preinc
            # (an object variable never assigned reads as 0, so the answer
            # for that case is a boxed 0 where bytecode answered the null).
            cbail($name ~ ' arity') unless nqp::elems(@($op)) == 1;
            cbail($name ~ ' target not a var') unless nqp::istype($op[0], QAST::Var);
            my str $aop  := $name eq 'postinc' ?? 'add_i' !! 'sub_i';
            my int $spec := nqp::isnull($op[0].returns) ?? 0 !! nqp::objprimspec($op[0].returns);
            my str $tmp  := QAST::Node.unique('post_old');
            my $read;
            if $spec == 0 {
                $read := QAST::Op.new( :op('if'),
                    QAST::Op.new( :op('isnull'), $op[0].shallow_clone ),
                    QAST::IVal.new( :value(0) ),
                    $op[0].shallow_clone );
            }
            else {
                $read := $op[0].shallow_clone;
            }
            my $tdecl := QAST::Var.new( :name($tmp), :scope('local'), :decl('var') );
            my $tread := QAST::Var.new( :name($tmp), :scope('local') );
            if $spec != 0 {
                $tdecl.returns($op[0].returns);
                $tread.returns($op[0].returns);
            }
            return self.encode_node(QAST::Stmts.new(
                QAST::Op.new( :op('bind'), $tdecl, $read ),
                QAST::Op.new( :op('bind'), $op[0].shallow_clone,
                    QAST::Op.new( :op($aop), $tread, QAST::IVal.new( :value(1) ) ) ),
                $tread.shallow_clone ), %e, $want);
        }
        if $name eq 'indexingoptimized' {
            # A string-indexing hint on the JVM: the operand wanted as a str
            # (Compiler.nqp: as_jast($op[0], :want($RT_STR))).
            cbail('indexingoptimized arity') unless nqp::elems(@($op)) == 1;
            return self.encode_child($op[0], %e, $T_STR);
        }
        if $name eq 'register' || $name eq 'delegate'
                || $name eq 'track' || $name eq 'guard' {
            # New-dispatch definition ops (Compiler.nqp add_dispatcher_op): a
            # dispatch to the 'boot-syscall' dispatcher, the operation named by
            # a 'dispatcher-<kind>' string. register/delegate pass their
            # children through; track/guard fold their first (constant-string)
            # operand into the dispatcher name (the trailing '-' is the JAST
            # spelling). Reduces to the covered 'dispatch' op.
            my $disp := QAST::Op.new( :op('dispatch'),
                QAST::SVal.new( :value('boot-syscall') ) );
            if $name eq 'register' || $name eq 'delegate' {
                $disp.push( QAST::SVal.new( :value('dispatcher-' ~ $name) ) );
                for @($op) { $disp.push($_) }
            }
            else {
                cbail($name ~ ' kind not a constant string')
                    unless nqp::elems(@($op)) && nqp::istype($op[0], QAST::SVal);
                $disp.push( QAST::SVal.new( :value('dispatcher-' ~ $name ~ '-' ~ $op[0].value) ) );
                my int $i := 1;
                while $i < nqp::elems(@($op)) { $disp.push($op[$i]); $i := $i + 1; }
            }
            return self.encode_op($disp, %e, $want);
        }
        if $name eq 'dispatch' {
            # The generic dispatch-by-name op several desugars produce;
            # the first child names the dispatcher.
            cbail('computed dispatch name') unless nqp::istype($op[0], QAST::SVal);
            my @args;
            my int $i := 1;
            while $i < nqp::elems(@($op)) {
                nqp::push(@args, $op[$i]);
                $i := $i + 1;
            }
            @args := self.reorder_args(@args);
            my int $rt := rt_of($op.returns);
            epush(%e, $W_DISPATCH);
            %e<dispatches> := %e<dispatches> + 1;
            epush(%e, $rt);
            epush(%e, epool(%e, $op[0].value));
            epush(%e, nqp::elems(@args));
            my @fp := self.encode_arg_flags(@args, %e);
            self.encode_args(@args, %e, @fp);
            return $rt;
        }
        if $name eq 'p6assign' {
            # The registered desugar for p6assign, mirrored: bind the
            # container to a scratch local, raku-assign, answer the
            # container.
            cbail('p6assign arity') unless nqp::elems(@($op)) == 2;
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS); epush(%e, 2);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            self.encode_child($op[0], %e, $T_OBJ);
            epush(%e, $W_STMTS); epush(%e, 2);
            epush(%e, $W_DISPATCH);
            %e<dispatches> := %e<dispatches> + 1;
            epush(%e, $T_OBJ);
            epush(%e, epool(%e, 'raku-assign'));
            epush(%e, 2);
            epush(%e, $T_OBJ); epush(%e, $T_OBJ);
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
            self.encode_child($op[1], %e, $T_OBJ);
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            return $T_OBJ;
        }
        if $name eq 'stmts' || $name eq 'stmt' {
            return self.encode_stmts_children($op, %e, $want);
        }
        if $name eq 'locallifetime' {
            return self.encode_node($op[0], %e, $want);
        }
        if $name eq 'null' {
            epush(%e, $W_NULLC);
            return $T_OBJ;
        }
        # ord/rindex/index are arity-based desugars, exactly as
        # Compiler.nqp's add_core_op builds them: a fresh tree over the same
        # children (ordfirst/ordat, rindexfromend/rindexfrom, indexfrom --
        # all already in the table), so a later bail leaves the op untouched.
        if $name eq 'ord' {
            my @k := $op.list;
            cbail('ord arity') unless nqp::elems(@k) == 1 || nqp::elems(@k) == 2;
            return self.encode_op(QAST::Op.new(
                :op(nqp::elems(@k) == 1 ?? 'ordfirst' !! 'ordat'), |@k), %e, $want);
        }
        if $name eq 'rindex' {
            my @k := $op.list;
            cbail('rindex arity') unless nqp::elems(@k) == 2 || nqp::elems(@k) == 3;
            return self.encode_op(QAST::Op.new(
                :op(nqp::elems(@k) == 2 ?? 'rindexfromend' !! 'rindexfrom'), |@k), %e, $want);
        }
        if $name eq 'index' {
            my @k := $op.list;
            cbail('index arity') unless nqp::elems(@k) == 2 || nqp::elems(@k) == 3;
            my @args := nqp::elems(@k) == 2
                ?? [@k[0], @k[1], QAST::IVal.new( :value(0) )]
                !! @k;
            return self.encode_op(QAST::Op.new( :op('indexfrom'), |@args ), %e, $want);
        }
        if $name eq 'const' {
            # Compiler.nqp's %const_map, published as an HLL symbol: the
            # same integer the bytecode path folds this to.
            my $map := nqp::gethllsym('nqp', 'CODE_CONST_MAP');
            cbail('const map unpublished') if nqp::isnull($map);
            cbail('const ' ~ $op.name) unless nqp::existskey($map, $op.name);
            epush(%e, $W_IVAL); epush(%e, epool(%e, ~$map{$op.name}));
            return $T_INT;
        }
        if $name eq 'curlexpad' {
            cbail('curlexpad arity') if nqp::elems(@($op));
            %e<frame_op> := 1;
            epush(%e, $W_CURLEXPAD);
            return $T_OBJ;
        }
        if $name eq 'p6argvmarray' {
            cbail('p6argvmarray arity') if nqp::elems(@($op));
            %e<frame_op> := 1;
            epush(%e, $W_P6ARGVMARRAY);
            return $T_OBJ;
        }
        if $name eq 'p6bindsig' || $name eq 'p6trybindsig' {
            # The full-binder prologue of a custom_args block (Raku's
            # runtime Binder over the frame's own csd/args). p6bindsig is a
            # statement whose builder shape returns from the program when the
            # binder auto-threaded (value null, LOOP's shape); p6trybindsig
            # answers 1/0 for the assertparamcheck around it. Both read and
            # rewrite cf.csd/cf.args, so they are frame-forcing.
            cbail($name ~ ' arity') if nqp::elems(@($op));
            %e<frame_op> := 1;
            if $name eq 'p6bindsig' {
                epush(%e, $W_P6BINDSIG);
                return $T_OBJ;
            }
            epush(%e, $W_P6TRYBINDSIG);
            return $T_INT;
        }
        if $name eq 'usecapture' {
            # The current frame's arguments captured for a re-dispatch,
            # exactly Compiler.nqp's usecapture(tc, csd, args). A 0-operand
            # op that reads cf.csd/cf.args, mirroring p6argvmarray.
            cbail('usecapture arity') if nqp::elems(@($op));
            %e<frame_op> := 1;
            epush(%e, $W_USECAPTURE);
            return $T_OBJ;
        }
        if $name eq 'savecapture' {
            # The current frame's args saved into a capture, Compiler.nqp's
            # savecapture(tc, csd, args) -- a 0-operand frame reader, exactly
            # like usecapture but Ops.savecapture on the engine side.
            cbail('savecapture arity') if nqp::elems(@($op));
            %e<frame_op> := 1;
            epush(%e, $W_SAVECAPTURE);
            return $T_OBJ;
        }
        if $name eq 'syscall' {
            # nqp::syscall(name, args...) is sugar for a boot-syscall
            # dispatch -- exactly Compiler.nqp's add_dispatcher_op with the
            # 'boot-syscall' prefix: the dispatcher name is unshifted as a
            # constant string and the rest rides in the callsite. Encoded
            # as the dispatch op it desugars to (a fresh tree over the same
            # children, so a later bail hands the fallback the untouched op).
            my $d := QAST::Op.new( :op('dispatch'), QAST::SVal.new( :value('boot-syscall') ) );
            for @($op) { $d.push($_) }
            $d.returns($op.returns) if nqp::can($op, 'returns');
            return self.encode_node($d, %e, $want);
        }
        if $name eq 'p6return' {
            # p6return appears only as the SUCCEED handler of a `handle`
            # that wraps a block's whole body (src/Raku/ast/scoping.rakumod):
            # so the handle's value is the block's value is the routine's
            # return. The bytecode path forces the routine to return via
            # return_o + cf.outer.exitAfterUnwind + leave; here the handler
            # dispatcher's completion value already becomes the handle's
            # result (NqpProgramBuilder HANDLE -> resL), which flows out as
            # the block value -- so yielding the argument produces the same
            # routine return without an unwind. (Only ever a SUCCEED
            # handler, so no other shape reaches this.)
            cbail('p6return arity') unless nqp::elems(@($op)) == 1;
            return self.encode_child($op[0], %e, $want == $T_VOID ?? $T_VOID !! $T_OBJ);
        }
        if $name eq 'handle' {
            my @children := nqp::clone($op.list);
            cbail('handle no children') unless nqp::elems(@children) >= 1;
            my $protected := nqp::shift(@children);
            return self.encode_node($protected, %e, $want)
                unless nqp::elems(@children);

            # The category-dispatch closure, synthesized exactly as the
            # bytecode path's handle op synthesizes it. It compiles as an
            # ordinary nested block through the deferred road; the handler
            # expressions inside it are bytecode, whatever they contain.
            my int $mask := 0;
            my int $cares := 0;
            my $hblock := QAST::Block.new(
                QAST::Op.new(
                    :op('bind'),
                    QAST::Var.new( :name('__category__'), :scope('local'), :decl('var') ),
                    QAST::Op.new(
                        :op('getextype'),
                        QAST::Op.new( :op('exception') )
                    )));
            my $push_target := $hblock;
            my int $ci := 0;
            my int $cn := nqp::elems(@children);
            while $ci < $cn {
                my $type := @children[$ci];
                my $handler := @children[$ci + 1];
                $ci := $ci + 2;
                $cares := 1 if $type eq 'CONTROL' || $type eq 'LABELED';
                if $type eq 'LABELED' {
                    $hblock.push(QAST::Op.new(
                        :op('if'),
                        QAST::Op.new(
                            :op('bitand_i'),
                            QAST::Var.new( :name('__category__'), :scope('local') ),
                            QAST::IVal.new( :value($EX_CAT_LABELED) )
                        ),
                        QAST::Op.new(
                            :op('unless'),
                            QAST::Op.new(
                                :op('iseq_i'),
                                QAST::Op.new( :op('where'),
                                    QAST::Op.new( :op('getpayload'),
                                        QAST::Op.new( :op('exception') ) )
                                ),
                                QAST::Op.new( :op('where'), $handler )
                            ),
                            QAST::Op.new( :op('rethrow'),
                                QAST::Op.new( :op('exception') ) )
                        )
                    ));
                }
                else {
                    cbail('handle type ' ~ $type)
                        unless nqp::existskey(%handler_names, $type);
                    my int $cat_mask := %handler_names{$type};
                    my $check := QAST::Op.new(
                        :op('if'),
                        QAST::Op.new(
                            :op('bitand_i'),
                            QAST::Var.new( :name('__category__'), :scope('local') ),
                            QAST::IVal.new( :value($cat_mask) )
                        ),
                        $handler
                    );
                    $push_target.push($check);
                    $push_target := $check;
                    $mask := nqp::bitor_i($mask, $cat_mask);
                }
            }

            # The lexical the dispatcher lives in. Its name is unique, so
            # the eager declaration a later bail strands is only an unused
            # slot -- the bytecode path then declares its own !HANDLER_.
            my str $hname := QAST::Node.unique('!HANDLER_');
            %e<block>.add_lexical(QAST::Var.new( :name($hname) ));
            my int $lexidx := %e<block>.lexical_idx($hname);
            my int $outer := %e<hidx>;
            my int $hid := &*REGISTER_BLOCK_HANDLER($outer, $mask, $lexidx);

            # Bind the closure, then the guarded region; the pair is a
            # two-statement STMTS whose value is the handle's.
            epush(%e, $W_STMTS); epush(%e, 2);
            %e<frame_op> := 1;
            epush(%e, $W_LEXBIND); epush(%e, $T_OBJ); epush(%e, epool(%e, $hname));
            epush(%e, $W_OPCALL); epush(%e, 96); epush(%e, 1);   # takeclosure
            epush(%e, $W_CODEREF);
            nqp::push(%e<nested>, [nqp::elems(%e<code>), $hblock]);
            epush(%e, 0);
            %e<frame_op> := 1;
            epush(%e, $W_HANDLE);
            epush(%e, $hid);
            epush(%e, $outer);
            epush(%e, $cares);
            %e<hidx> := $hid;
            # encode_child, not encode_node: the wire op's value slot is
            # object-typed, and a protected body whose own result is
            # native (a block mixing `return` with an int fall-through)
            # must box on the way in -- StoreRet casts what it is given.
            self.encode_child($protected, %e, $T_OBJ);
            %e<hidx> := $outer;
            return $T_OBJ;
        }
        if $name eq 'handlepayload' {
            cbail('handlepayload arity') unless nqp::elems(@($op)) == 3;
            my str $type := $op[1];
            cbail('handlepayload type ' ~ $type)
                unless nqp::existskey(%handler_names, $type);
            my int $mask := %handler_names{$type};
            my int $outer := %e<hidx>;
            my int $hid := &*REGISTER_UNWIND_HANDLER($outer, $mask, :ex_obj(1));
            %e<frame_op> := 1;
            epush(%e, $W_HANDLEPAYLOAD);
            epush(%e, $hid);
            epush(%e, $outer);
            %e<hidx> := $hid;
            self.encode_child($op[0], %e, $T_OBJ);
            %e<hidx> := $outer;
            self.encode_child($op[2], %e, $T_OBJ);
            return $T_OBJ;
        }
        if $name eq 'control' {
            # next/last/redo: the same dynamic category throw the bytecode
            # path makes (Ops.throwcatdyn_c); the result, should a block
            # handler resume, is read from the frame's return register.
            my str $kind := $op.name;
            my $label;
            for @($op) { $label := $_ if $_.named eq 'label' }
            if $label {
                my int $lcat := $kind eq 'next' ?? $EX_CAT_NEXT +| $EX_CAT_LABELED
                             !! $kind eq 'redo' ?? $EX_CAT_REDO +| $EX_CAT_LABELED
                             !! $kind eq 'last' ?? $EX_CAT_LAST +| $EX_CAT_LABELED
                             !! 0;
                cbail('labeled control ' ~ $kind) unless $lcat;
                my str $tmp := QAST::Node.unique('ctrl_ex');
                my sub lv() { QAST::Var.new( :name($tmp), :scope('local') ) }
                return self.encode_node(QAST::Stmts.new(
                    QAST::Op.new( :op('bind'),
                        QAST::Var.new( :name($tmp), :scope('local'), :decl('var') ),
                        QAST::Op.new( :op('newexception') ) ),
                    QAST::Op.new( :op('setpayload'), lv(), $label ),
                    QAST::Op.new( :op('setextype'), lv(), QAST::IVal.new( :value($lcat) ) ),
                    QAST::Op.new( :op('throw'), lv() )), %e, $want);
            }
            my int $cat := $kind eq 'next' ?? $EX_CAT_NEXT
                        !! $kind eq 'redo' ?? $EX_CAT_REDO
                        !! $kind eq 'last' ?? $EX_CAT_LAST
                        !! 0;
            cbail('control ' ~ $kind) unless $cat;
            # A control throw runs whatever handler catches it.
            %e<dispatches> := %e<dispatches> + 1;
            epush(%e, $W_OPCALL);
            epush(%e, 108);   # OP_CONTROL
            epush(%e, 1);
            epush(%e, $W_IVAL);
            epush(%e, epool(%e, ~$cat));
            return $T_OBJ;
        }
        if $name eq 'getlexouter' {
            cbail('getlexouter shape') unless nqp::istype($op[0], QAST::SVal);
            %e<frame_op> := 1;
            epush(%e, $W_GETLEXOUTER);
            epush(%e, epool(%e, $op[0].value));
            return $T_OBJ;
        }
        if $name eq 'defor' && nqp::ifnull(nqp::getlexdyn('$*HLL'), '') eq 'Raku' {
            # Raku overrides defor (src/vm/jvm/Raku/Ops.nqp): definedness
            # is the .defined method, not nqp's isconcrete -- a Failure is
            # concrete and undefined, and `$*MISSING // fallback` must
            # take the fallback. Encode the very tree the override builds;
            # it is a fresh tree over the same children, so a later bail
            # hands the bytecode path the untouched op.
            cbail('defor arity') unless nqp::elems(@($op)) == 2;
            my $tmp := $op.unique('defined');
            return self.encode_node(QAST::Stmts.new(
                QAST::Op.new( :op('bind'),
                    QAST::Var.new( :name($tmp), :scope('local'), :decl('var') ),
                    $op[0] ),
                QAST::Op.new( :op('if'),
                    QAST::Op.new( :op('callmethod'), :name('defined'),
                        QAST::Var.new( :name($tmp), :scope('local') ) ),
                    QAST::Var.new( :name($tmp), :scope('local') ),
                    $op[1] )), %e, $want);
        }
        if $name eq 'ifnull' || $name eq 'defor' {
            # Evaluate once into a scratch local, test, fall back.
            #   ifnull(a, b): isnull(tmp) ?? b !! tmp
            #   defor(a, b):  isconcrete(tmp) ?? tmp !! b
            cbail('ifnull arity') unless nqp::elems(@($op)) == 2;
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_STMTS);
            epush(%e, 2);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            self.encode_child($op[0], %e, $T_OBJ);
            epush(%e, $W_IFV);
            epush(%e, $T_INT);
            epush(%e, 0);
            epush(%e, 1);
            epush(%e, $W_OPCALL);
            epush(%e, $name eq 'ifnull' ?? 52 !! 53);   # isnull / isconcrete
            epush(%e, 1);
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            if $name eq 'ifnull' {
                self.encode_child($op[1], %e, $T_OBJ);
                epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
            }
            else {
                epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
                self.encode_child($op[1], %e, $T_OBJ);
            }
            return $T_OBJ;
        }

        # The general table.
        my int $nargs := nqp::elems(@($op));
        my $entry := nqp::atkey(%emit_ops, $name ~ '/' ~ $nargs);
        $entry := nqp::atkey(%emit_ops, $name) if nqp::isnull($entry);
        if nqp::isnull($entry) {
            # No hand-written encoding, but the HLL may have registered a
            # desugar (src/vm/jvm/Raku/Ops.nqp, register_op_desugar).
            # Desugars are ON BY DEFAULT: every registered one builds a FRESH
            # tree (it may rebind its own parameter but never mutates the node
            # handed in), so applying it and then bailing leaves the original
            # tree intact for the bytecode path -- the opaque desugar value is
            # neither reproduced nor read. A NEW desugar MUST keep that
            # fresh-tree contract. NQP_CODE_NO_DESUGAR=a,b excludes ops, for
            # bisecting a suspect desugar.
            my $reg := nqp::gethllsym('nqp', 'CODE_OP_DESUGARS');
            unless nqp::isnull($reg) {
                my $desugar := nqp::atkey($reg, $name);
                if !nqp::isnull($desugar) && !nqp::existskey(%code_no_desugar, $name) {
                    return self.encode_node($desugar($op), %e, $want);
                }
            }
        }
        if nqp::isnull($entry) {
            # No hand-written encoding: derive one from the classlib
            # registry the bytecode path compiles to an invokestatic
            # (Compiler.nqp map_classlib_*_op). The HLL's own ops first,
            # then the core ones. A continuation-style op (result via the
            # frame) or a void one stays out of this road.
            my $rec := nqp::null();
            my $hreg := nqp::gethllsym('nqp', 'CODE_CLASSLIB_HLL_OPS');
            my str $hll := nqp::ifnull(nqp::getlexdyn('$*HLL'), '');
            if !nqp::isnull($hreg) && nqp::existskey($hreg, $hll) {
                $rec := nqp::atkey(nqp::atkey($hreg, $hll), $name);
            }
            if nqp::isnull($rec) {
                my $creg := nqp::gethllsym('nqp', 'CODE_CLASSLIB_OPS');
                $rec := nqp::atkey($creg, $name) unless nqp::isnull($creg);
            }
            unless nqp::isnull($rec) {
                cbail('classlib cont op ' ~ $name) if $rec[6];
                my @rin := $rec[3];
                cbail('classlib arity ' ~ $name) unless nqp::elems(@rin) == $nargs;
                my int $rt := classlib_t($rec[4], 1);
                cbail('classlib result type ' ~ $name) if $rt < 0;
                epush(%e, $W_CLASSLIB);
                epush(%e, $rt);
                epush(%e, epool(%e, $rec[0]));
                epush(%e, epool(%e, $rec[1]));
                epush(%e, epool(%e, $rec[2]));
                epush(%e, $rec[5]);
                epush(%e, $nargs);
                my @at;
                for @rin {
                    my int $t := classlib_t($_, 0);
                    cbail('classlib arg type ' ~ $name) if $t < 0;
                    nqp::push(@at, $t);
                    epush(%e, $t);
                }
                my int $i := 0;
                for @($op) { self.encode_child($_, %e, @at[$i]); $i++ }
                return $rt;
            }
        }
        cbail('op ' ~ $name) if nqp::isnull($entry);
        my str $args := $entry[2];
        cbail('op ' ~ $name ~ ' arity ' ~ $nargs) unless $nargs == nqp::chars($args);
        epush(%e, $W_OPCALL);
        epush(%e, $entry[0]);
        epush(%e, $nargs);
        my int $i := 0;
        while $i < $nargs {
            my str $tc := nqp::substr($args, $i, 1);
            my int $t := $tc eq 'i' ?? $T_INT !! $tc eq 'n' ?? $T_NUM
                !! $tc eq 's' ?? $T_STR !! $T_OBJ;
            self.encode_child($op[$i], %e, $t);
            $i++;
        }
        $entry[1]
    }

    # The `with`/`without` branch test: definedness is the `.defined`
    # method (overridable), not nqp isconcrete -- exactly the bytecode
    # path's findmethod('defined') + lang-call. The condition VALUE stays
    # in $tmp (block topic and fail-value); only the test consults
    # .defined. Mirrors encode_callmethod's lang-meth-call wire with the
    # invocant read from $tmp and no further arguments.
    # $condt is the type the condition local actually holds. `with`/`without`
    # tests it by CALLING `.defined` on it, so a native has to be boxed
    # first -- exactly the `$qastcomp.coercion($cond, $RT_OBJ)` the class
    # road applies to its dup'd copy (Compiler.nqp's withy arm). The local
    # itself keeps its own type: the __IM_ local the class road hands to a
    # cond-passing block is typed from the condition, and so is the value
    # the two-child form yields when the test fails.
    method emit_defined_test(%e, int $tmp, int $condt = $T_OBJ) {
        my int $mtmp := new_elocal(%e, $T_OBJ);
        epush(%e, $W_DISPATCH);
        %e<dispatches> := %e<dispatches> + 1;
        epush(%e, $T_OBJ);
        epush(%e, epool(%e, 'lang-meth-call'));
        epush(%e, 3);
        epush(%e, $T_OBJ); epush(%e, $T_STR); epush(%e, $T_OBJ);
        epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
        epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $mtmp);
        if $condt != $T_OBJ {
            my int $kind := coerce_kind($condt, $T_OBJ);
            cbail('withy cond coercion') if $kind < 0;
            epush(%e, $W_COERCE); epush(%e, $kind);
        }
        epush(%e, $W_LOCGET); epush(%e, $condt); epush(%e, $tmp);
        epush(%e, $W_SVAL); epush(%e, epool(%e, 'defined'));
        epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $mtmp);
    }

    # xor: variadic "exactly one true". Desugared to scratch locals and
    # nested ifs that reproduce Compiler.nqp's label-based flow: evaluate
    # children left to right; the moment a SECOND true appears, short-circuit
    # (skipping the rest) to the :false child, or null; otherwise yield the
    # single true value, or -- when none is true -- the last child. $t = "a
    # true has been seen and frozen into $r", $x = "two trues seen". Kept out
    # of encode_op: that method is already near the codegen size limit.
    method encode_xor($op, %e, int $want) {
        my @childlist;
        my $f_ast;
        for @($op) {
            if $_.named eq 'false' { $f_ast := $_ }
            else { nqp::push(@childlist, $_) }
        }
        cbail('xor arity') unless nqp::elems(@childlist) >= 2;
        my str $r := $op.unique('xor_r');
        my str $t := $op.unique('xor_t');
        my str $x := $op.unique('xor_x');
        my str $b := $op.unique('xor_b');
        my str $u := $op.unique('xor_u');
        my $lget := -> str $n { QAST::Var.new( :name($n), :scope('local') ) };
        my $tree := QAST::Stmts.new(
            QAST::Op.new( :op('bind'),
                QAST::Var.new( :name($r), :scope('local'), :decl('var') ),
                @childlist[0] ),
            QAST::Op.new( :op('bind'),
                QAST::Var.new( :name($t), :scope('local'), :decl('var'), :returns(int) ),
                QAST::Op.new( :op('istrue'), $lget($r) ) ),
            QAST::Op.new( :op('bind'),
                QAST::Var.new( :name($x), :scope('local'), :decl('var'), :returns(int) ),
                QAST::IVal.new( :value(0) ) ),
            QAST::Op.new( :op('bind'),
                QAST::Var.new( :name($b), :scope('local'), :decl('var') ),
                QAST::Op.new( :op('null') ) ),
            QAST::Op.new( :op('bind'),
                QAST::Var.new( :name($u), :scope('local'), :decl('var'), :returns(int) ),
                QAST::IVal.new( :value(0) ) ) );
        my int $ci := 1;
        my int $nc := nqp::elems(@childlist);
        while $ci < $nc {
            # unless $x { $b := ck; $u := istrue($b);
            #   if $u { if $t {$x:=1} else {$r:=$b;$t:=1} }
            #   else  { unless $t {$r:=$b} } }
            my $per := QAST::Stmts.new(
                QAST::Op.new( :op('bind'), $lget($b), @childlist[$ci] ),
                QAST::Op.new( :op('bind'), $lget($u),
                    QAST::Op.new( :op('istrue'), $lget($b) ) ),
                QAST::Op.new( :op('if'), $lget($u),
                    QAST::Op.new( :op('if'), $lget($t),
                        QAST::Op.new( :op('bind'), $lget($x), QAST::IVal.new( :value(1) ) ),
                        QAST::Stmts.new(
                            QAST::Op.new( :op('bind'), $lget($r), $lget($b) ),
                            QAST::Op.new( :op('bind'), $lget($t), QAST::IVal.new( :value(1) ) ) ) ),
                    QAST::Op.new( :op('unless'), $lget($t),
                        QAST::Op.new( :op('bind'), $lget($r), $lget($b) ) ) ) );
            $tree.push( QAST::Op.new( :op('unless'), $lget($x), $per ) );
            $ci++;
        }
        $tree.push( QAST::Op.new( :op('if'), $lget($x),
            QAST::Op.new( :op('bind'), $lget($r),
                nqp::defined($f_ast) ?? $f_ast !! QAST::Op.new( :op('null') ) ) ) );
        $tree.push( $lget($r) );
        return self.encode_node($tree, %e, $want);
    }

    method encode_if($op, %e, int $want, int $negate, int $withy = 0) {
        my int $n := nqp::elems(@($op));
        cbail('if arity') unless $n == 2 || $n == 3;
        my int $void := $want == $T_VOID;
        if needs_cond_passed($op[1]) || ($n == 3 && needs_cond_passed($op[2])) {
            # An immediate block wanting the condition (`if $x -> $y {}`):
            # the condition evaluates once into a scratch local, and the
            # branch is a call of the block with that local, the bytecode
            # path's __IM_ local. Value context keeps the two-child rule
            # below (the condition is the value when it fails).
            cbail('cond-passing if in a parameter default')
                if nqp::existskey(%e, 'inparams') && %e<inparams>;
            my int $rt := $void ?? $T_VOID !! ($want == $T_ANY ?? $T_OBJ !! $want);
            epush(%e, $W_STMTS); epush(%e, 2);
            my int $bind_at := nqp::elems(%e<code>);
            epush(%e, $W_LOCBIND); epush(%e, 0); epush(%e, 0);
            my int $condt := self.encode_node($op[0], %e, $T_ANY);
            $condt := $T_INT if $condt == $T_UINT;
            cbail('if condition type') if $condt < 0 || $condt > 3;
            my int $tmp := new_elocal(%e, $condt);
            nqp::bindpos(%e<code>, $bind_at + 1, $condt);
            nqp::bindpos(%e<code>, $bind_at + 2, $tmp);
            my int $has_else := $n == 3 || !$void;
            epush(%e, $void ?? $W_IFS !! $W_IFV);
            epush(%e, $withy ?? $T_OBJ !! $condt);
            epush(%e, $negate);
            epush(%e, $has_else ?? 1 !! 0);
            if $withy {
                self.emit_defined_test(%e, $tmp, $condt);
            }
            else {
                epush(%e, $W_LOCGET); epush(%e, $condt); epush(%e, $tmp);
            }
            # then
            if needs_cond_passed($op[1]) {
                self.encode_immediate_call($op[1], %e, 1, $condt, $tmp);
                if !$void && $rt != $T_OBJ {
                    cbail('cond-passing if wanted native');
                }
            }
            else {
                self.encode_child($op[1], %e, $rt);
            }
            # else
            if $n == 3 {
                if needs_cond_passed($op[2]) {
                    self.encode_immediate_call($op[2], %e, 1, $condt, $tmp);
                    cbail('cond-passing if wanted native') if !$void && $rt != $T_OBJ;
                }
                else {
                    self.encode_child($op[2], %e, $rt);
                }
            }
            elsif !$void {
                if $condt != $rt {
                    my int $kind := coerce_kind($condt, $rt);
                    cbail('if result coercion') if $kind < 0;
                    epush(%e, $W_COERCE); epush(%e, $kind);
                }
                epush(%e, $W_LOCGET); epush(%e, $condt); epush(%e, $tmp);
            }
            return $void ?? $T_OBJ !! $rt;
        }
        if $n == 2 && !$void {
            # No else, but a value is wanted: the condition IS the value
            # when it does not hold, exactly as the bytecode path keeps it
            # (dup'd into a temp, the result type the common one). The
            # condition evaluates once into a scratch local of its own
            # type -- allocated after encoding it, since the type is only
            # known then -- and the else arm re-reads it, coerced to the
            # result type, which is the wanted type, or object when the
            # context takes anything.
            my int $rt := $want == $T_ANY ?? $T_OBJ !! $want;
            epush(%e, $W_STMTS); epush(%e, 2);
            my int $bind_at := nqp::elems(%e<code>);
            epush(%e, $W_LOCBIND); epush(%e, 0); epush(%e, 0);
            my int $condt := self.encode_node($op[0], %e, $T_ANY);
            $condt := $T_INT if $condt == $T_UINT;
            cbail('if condition type') if $condt < 0 || $condt > 3;
            my int $tmp := new_elocal(%e, $condt);
            nqp::bindpos(%e<code>, $bind_at + 1, $condt);
            nqp::bindpos(%e<code>, $bind_at + 2, $tmp);
            epush(%e, $W_IFV);
            epush(%e, $withy ?? $T_OBJ !! $condt);
            epush(%e, $negate);
            epush(%e, 1);
            if $withy {
                self.emit_defined_test(%e, $tmp, $condt);
            }
            else {
                epush(%e, $W_LOCGET); epush(%e, $condt); epush(%e, $tmp);
            }
            if $want == $T_ANY {
                # Compiler.nqp: the common type of the then-arm and the
                # condition, object when they differ.
                my int $m1 := nqp::elems(%e<code>);
                my int $t1 := self.encode_node($op[1], %e, $T_ANY);
                $t1 := $T_INT if $t1 == $T_UINT;
                $rt := $t1 == $condt ?? $condt !! $T_OBJ;
                self.coerce_at(%e, $m1, $t1, $rt);
            }
            else {
                self.encode_child($op[1], %e, $rt);
            }
            if $condt != $rt {
                my int $kind := coerce_kind($condt, $rt);
                cbail('if result coercion') if $kind < 0;
                epush(%e, $W_COERCE); epush(%e, $kind);
            }
            epush(%e, $W_LOCGET); epush(%e, $condt); epush(%e, $tmp);
            return $rt;
        }
        if $withy {
            # with/without whose then-arm takes no condition: the condition
            # into an obj local, the same `defined` dispatch the cond-passing
            # branches make (Compiler.nqp: coerce to obj, findmethod
            # 'defined', lang-call, istrue), then the plain IFS/IFV layout
            # with an obj condition. Value context with two children never
            # reaches here (the branch above owns it).
            my int $rt := $void ?? $T_VOID !! ($want == $T_ANY ?? $T_OBJ !! $want);
            epush(%e, $W_STMTS); epush(%e, 2);
            my int $tmp := new_elocal(%e, $T_OBJ);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
            self.encode_child($op[0], %e, $T_OBJ);
            epush(%e, $void ?? $W_IFS !! $W_IFV);
            epush(%e, $T_OBJ); epush(%e, $negate); epush(%e, $n == 3 ?? 1 !! 0);
            self.emit_defined_test(%e, $tmp);
            self.encode_child($op[1], %e, $rt);
            self.encode_child($op[2], %e, $rt) if $n == 3;
            return $void ?? $T_OBJ !! $rt;
        }
        epush(%e, $void ?? $W_IFS !! $W_IFV);
        my int $ct_at := nqp::elems(%e<code>);
        epush(%e, 0);
        epush(%e, $negate);
        epush(%e, $n == 3 ?? 1 !! 0);
        # Condition type is known only after encoding it; the slot is
        # reserved above and patched here.
        my int $condt := self.encode_node($op[0], %e, $T_ANY);
        $condt := $T_INT if $condt == $T_UINT;
        nqp::bindpos(%e<code>, $ct_at, $condt);
        if $void || $want != $T_ANY {
            my int $btype := $void ?? $T_VOID !! $want;
            self.encode_child($op[1], %e, $btype);
            self.encode_child($op[2], %e, $btype) if $n == 3;
            return $void ?? $T_OBJ !! $want;
        }
        # No wanted type: Compiler.nqp's rule, the arms' common type when
        # they agree, object otherwise. Boxing both unconditionally made
        # `nqp::delegate($c ?? 'a' !! 'b', ...)` declare its dispatcher
        # name as an object argument ("Argument 0 to the
        # 'dispatcher-delegate' syscall is a obj, but should be a str",
        # Rakudo's dispatchers.nqp, 2026-09-09).
        my int $m1 := nqp::elems(%e<code>);
        my int $t1 := self.encode_node($op[1], %e, $T_ANY);
        my int $m2 := nqp::elems(%e<code>);
        my int $t2 := self.encode_node($op[2], %e, $T_ANY);
        return $t1 if $t1 == $t2;
        self.coerce_at(%e, $m2, $t2, $T_OBJ);
        self.coerce_at(%e, $m1, $t1, $T_OBJ);
        $T_OBJ
    }

    # Retro-fits a coercion around an already encoded subtree that starts at
    # $mark, the way encode_child does after the fact: the two-cell COERCE
    # prefix moves everything after $mark two places, so the deferred
    # nested-block slots are shifted with it. A no-op when no coercion is
    # needed (the int/uint pair shares its slot).
    method coerce_at(%e, int $mark, int $from, int $to) {
        return 0 if $from == $to
            || ($from == $T_UINT && $to == $T_INT) || ($from == $T_INT && $to == $T_UINT);
        my int $kind := coerce_kind($from, $to);
        cbail('no coercion ' ~ $from ~ '->' ~ $to) if $kind < 0;
        splice_code(%e, [$W_COERCE, $kind], $mark);
        1
    }

    # lang-call: the callee (decontainerized) first, then the arguments.
    method encode_call($op, %e) {
        my @args;
        for @($op) { nqp::push(@args, $_) }
        my $callee := nqp::null();
        if $op.name ne '' {
            # resolved below as a lexical
        }
        elsif nqp::elems(@args) {
            $callee := nqp::shift(@args);
        }
        else {
            cbail('call with no callee');
        }
        @args := self.reorder_args(@args);
        epush(%e, $W_DISPATCH);
        %e<dispatches> := %e<dispatches> + 1;
        epush(%e, rt_of($op.returns));
        epush(%e, epool(%e, 'lang-call'));
        epush(%e, 1 + nqp::elems(@args));
        epush(%e, $T_OBJ);
        my @fp := self.encode_arg_flags(@args, %e);
        if nqp::isnull($callee) {
            self.encode_op_named_lexical_decont($op.name, %e);
        }
        elsif nqp::istype($callee, QAST::WVal) && !nqp::iscont($callee.value) {
            # Already known not to be a container; skip the decont, the
            # same as the bytecode emission does.
            self.encode_child($callee, %e, $T_OBJ);
        }
        else {
            epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
            self.encode_child($callee, %e, $T_OBJ);
        }
        self.encode_args(@args, %e, @fp);
        rt_of($op.returns)
    }

    # lang-meth-call: decont(invocant), the name, the invocant again, then
    # the arguments; the invocant rides a scratch local so it evaluates
    # once, exactly as the bytecode desugaring binds it to a local.
    method encode_callmethod($op, %e) {
        my @kids;
        for @($op) { nqp::push(@kids, $_) }
        cbail('callmethod with no invocant') unless nqp::elems(@kids);
        my $inv := nqp::shift(@kids);
        my $namenode := nqp::null();
        if $op.name eq '' {
            cbail('callmethod with no name') unless nqp::elems(@kids);
            $namenode := nqp::shift(@kids);
        }
        @kids := self.reorder_args(@kids);
        my int $tmp := new_elocal(%e, $T_OBJ);
        epush(%e, $W_DISPATCH);
        %e<dispatches> := %e<dispatches> + 1;
        epush(%e, rt_of($op.returns));
        epush(%e, epool(%e, 'lang-meth-call'));
        epush(%e, 3 + nqp::elems(@kids));
        epush(%e, $T_OBJ);
        epush(%e, $T_STR);
        epush(%e, $T_OBJ);
        my @fp := self.encode_arg_flags(@kids, %e);
        epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
        epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $tmp);
        self.encode_child($inv, %e, $T_OBJ);
        if nqp::isnull($namenode) {
            epush(%e, $W_SVAL);
            epush(%e, epool(%e, $op.name));
        }
        else {
            self.encode_child($namenode, %e, $T_STR);
        }
        epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $tmp);
        self.encode_args(@kids, %e, @fp);
        rt_of($op.returns)
    }

    method encode_op_named_lexical_decont(str $name, %e) {
        epush(%e, $W_OPCALL); epush(%e, 51); epush(%e, 1);   # decont
        self.encode_lexget($name, %e);
    }

    # Positionals before nameds, the same stable reorder
    # process_args_onto_stack does -- the dispatch machinery indexes
    # positionals as the leading slots.
    method reorder_args(@args) {
        my @pos;
        my @named;
        for @args {
            my $n := nqp::can($_, 'named') ?? $_.named !! '';
            nqp::push($n ?? @named !! @pos, $_);
        }
        for @named { nqp::push(@pos, $_) }
        @pos
    }

    # Argument flags carry each argument's NATURAL type, exactly as the
    # bytecode path's process_args does -- a dispatcher is entitled to
    # read a capture argument as native int, and an obj-boxed one breaks
    # it. The flag slots are reserved first (wire order is flags before
    # children) and patched once each child's type is known; a flattening
    # argument stays obj, as it does on the bytecode path.
    method encode_arg_flags(@args, %e) {
        my @flagpos;
        for @args -> $a {
            my $named := nqp::can($a, 'named') ?? $a.named !! '';
            my $flat  := nqp::can($a, 'flat') ?? $a.flat !! 0;
            nqp::push(@flagpos, nqp::elems(%e<code>));
            epush(%e, 0);
            # A flat argument carries NO name string: a flat NAMED arg
            # (`|%h`) has its names supplied by the hash keys at flatten
            # time, and its `.named` is a truth flag, not a name -- pushing
            # `~$named` there wrote the literal "1" as the argument's name,
            # which the binder then rejected ("Unexpected named argument
            # '1'"). The bytecode path likewise names only NON-flat named
            # args (flat args take flags 16/24 and push nothing).
            epush(%e, epool(%e, ~$named)) if $named && !$flat;
        }
        @flagpos
    }

    method encode_args(@args, %e, @flagpos) {
        my int $i := 0;
        for @args -> $a {
            my $named := nqp::can($a, 'named') ?? $a.named !! '';
            my $flat  := nqp::can($a, 'flat') ?? $a.flat !! 0;
            my int $t;
            if $flat {
                self.encode_child($a, %e, $T_OBJ);
                $t := $T_OBJ;
            }
            else {
                $t := self.encode_node($a, %e, $T_ANY);
            }
            my int $flag := $t;
            $flag := $flag + 4 if $named;
            $flag := $flag + 8 if $flat;
            nqp::bindpos(%e<code>, @flagpos[$i], $flag);
            $i := $i + 1;
        }
    }

    # A variable access; $bindval is a QAST node when this is a bind.
    # Compiler.nqp's native_assign_bind_scope, over the encoder's own
    # view of the block chain: an attributeref target binds as an
    # attribute; a lexicalref target binds as a lexical when the nearest
    # declaration is a plain lexical, and stays a reference (container
    # road) when it is a reference declaration.
    method native_assign_bind_scope($target, %e) {
        return '' unless nqp::istype($target, QAST::Var);
        my str $scope := $target.scope;
        return 'attribute' if $scope eq 'attributeref';
        if $scope eq 'lexicalref' {
            my str $name := $target.name;
            if nqp::existskey(%e<own>, $name) {
                return nqp::existskey(%e<ownref>, $name) ?? '' !! 'lexical';
            }
            my $cur := %e<block>.outer;
            while $cur {
                if $cur.qast.ann('DYN_COMP_WRAPPER') {
                    $cur := 0;
                }
                else {
                    return 'lexical' if nqp::defined($cur.lexical_type($name));
                    return '' if nqp::defined($cur.lexicalref_type($name));
                    $cur := $cur.outer;
                }
            }
        }
        ''
    }

    method encode_var($var, %e, $bindval, int $want) {
        my str $name := $var.name;
        my str $scope := $var.scope;
        my str $decl := $var.decl;

        # A reference scope asked for in a NATIVE type devolves to the plain
        # scope, because the only thing the caller can do with the reference
        # is dereference it immediately. Compiler.nqp does exactly this
        # ("we'd only de-ref right away anyway"); mirroring it here is what
        # makes the common `my int $i; $i = ...` shapes encodable at all.
        # A reference wanted as an OBJECT is a real reference object and
        # still refuses -- that needs getattrref_*/getlexref_*.
        if $decl eq '' && nqp::isnull($bindval)
            && ($want == $T_INT || $want == $T_NUM || $want == $T_STR) {
            $scope := 'lexical'   if $scope eq 'lexicalref';
            $scope := 'attribute' if $scope eq 'attributeref';
        }

        if $decl eq 'contvar' && $scope eq 'local' {
            # The declaration IS the expression: clone the prototype
            # container into the local unless something (a lowered
            # parameter) bound it already, and answer the local -- the
            # exact shape the bytecode declaration compiles to.
            cbail('bind to a contvar declaration') unless nqp::isnull($bindval);
            my $proto := $var.value;
            my $sc := nqp::getobjsc($proto);
            cbail('local contvar proto not in an SC') if nqp::isnull($sc);
            self.declare_elocal($var, %e, $T_OBJ);
            my int $idx := %e<locals>{$name}[0];
            epush(%e, $W_STMTS); epush(%e, 2);
            epush(%e, $W_IFS);
            epush(%e, $T_INT);
            epush(%e, 0);
            epush(%e, 0);
            epush(%e, $W_OPCALL); epush(%e, 52); epush(%e, 1);   # isnull
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $idx);
            epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $idx);
            epush(%e, $W_OPCALL); epush(%e, 93); epush(%e, 1);   # clone_nd
            epush(%e, $W_WVAL);
            epush(%e, epool(%e, nqp::scgethandle($sc)));
            epush(%e, nqp::scgetobjidx($sc, $proto));
            epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $idx);
            return $T_OBJ;
        }
        if $decl ne '' {
            self.encode_decl($var, %e, $decl, $scope);
        }

        # A read in void context compiles to nothing, exactly as the
        # bytecode path nops it -- and that is semantics, not tidiness: an
        # emitted read of a contvar would clone the container early and
        # sever the lazy first-toucher sharing the traited-variable
        # pattern depends on.
        if $want == $T_VOID && nqp::isnull($bindval) {
            epush(%e, $W_JNULL);
            return $T_OBJ;
        }

        # Scope from the symbol tables when not spelled out, the same walk
        # the compiler does.
        if $scope eq '' {
            my $cur := %e<block>;
            while $cur {
                my %sym := $cur.qast.symbol($name);
                if %sym {
                    $scope := %sym<scope>;
                    $cur := 0;
                }
                else {
                    $cur := $cur.outer;
                }
            }
            cbail('scopeless var ' ~ $name) if $scope eq '';
        }

        if $scope eq 'local' {
            cbail('unknown local ' ~ $name) unless nqp::existskey(%e<locals>, $name);
            my @info := %e<locals>{$name};
            if nqp::isnull($bindval) {
                epush(%e, $W_LOCGET); epush(%e, @info[1]); epush(%e, @info[0]);
            }
            else {
                epush(%e, $W_LOCBIND); epush(%e, @info[1]); epush(%e, @info[0]);
                self.encode_child($bindval, %e, @info[1]);
            }
            return @info[1];
        }
        if $scope eq 'lexical' || $scope eq 'typevar' || $scope eq 'contextual' {
            if $scope eq 'contextual' && !self.lexical_in_scope($name, %e) {
                # Not statically visible: the dynamic caller-chain road,
                # the same rewrite the bytecode path makes.
                epush(%e, $W_OPCALL);
                epush(%e, nqp::isnull($bindval) ?? 86 !! 87);
                epush(%e, nqp::isnull($bindval) ?? 1 !! 2);
                epush(%e, $W_SVAL); epush(%e, epool(%e, $name));
                self.encode_child($bindval, %e, $T_OBJ) unless nqp::isnull($bindval);
                return $T_OBJ;
            }
            my int $type := self.lexical_type_of($name, %e, $scope);
            # An OUTER lexical (declared in an enclosing block, not this
            # one) is read or bound from the program's code ref's resolved
            # outer, so it does not need this block's frame: a block whose
            # only lexical traffic is with its outers runs frame-free
            # (jesp: arguments in registers, part two). The block's own
            # lexicals, and anything not found statically, keep the frame.
            my int $outer := $scope eq 'lexical'
                && !nqp::existskey(%e<own>, $name)
                && self.lexical_in_scope($name, %e);
            if nqp::isnull($bindval) {
                if $outer {
                    epush(%e, $W_LEXGET_OUTER); epush(%e, $type); epush(%e, epool(%e, $name));
                }
                else {
                    %e<frame_op> := 1;
                    epush(%e, $W_LEXGET); epush(%e, $type); epush(%e, epool(%e, $name));
                }
            }
            else {
                # "Cannot bind to QAST::Var resolving to a lexicalref" on
                # the bytecode path; a bail keeps that error its own.
                cbail('bind to a lexicalref through lexical scope')
                    if self.resolve_lexref($name, %e)[0] == 2;
                if $outer {
                    epush(%e, $W_LEXBIND_OUTER); epush(%e, $type); epush(%e, epool(%e, $name));
                }
                else {
                    %e<frame_op> := 1;
                    epush(%e, $W_LEXBIND); epush(%e, $type); epush(%e, epool(%e, $name));
                }
                # A store to a SIZED native lexical truncates to the
                # declared width, exactly as Compiler.nqp's lexical bind
                # road does (emit_sized_native_trunc before bindlex_<c>):
                # int8/16/32 sign-wrap, uint8/16/32 mask, num32 rounds to
                # single precision. A full-width type wraps to nothing.
                self.encode_child(
                    self.sized_trunc($bindval, self.sized_ret($var, $name, %e)),
                    %e, $type);
            }
            return $type;
        }
        if $scope eq 'attribute' {
            cbail('attribute shape') unless nqp::elems(@($var)) == 2;
            # The typed accessors by the declared type, as the bytecode
            # path picks getattr_<t>/bindattr_<t>: 81/82 object, 117-119
            # and 121-123 for int/num/str.
            my int $aspec := nqp::isnull($var.returns) ?? 0 !! nqp::objprimspec($var.returns);
            my int $auint := $aspec == 10 ?? 1 !! 0;
            cbail('sized uint attribute')
                if $auint && nqp::objprimbits($var.returns) > 0 && nqp::objprimbits($var.returns) < 64;
            my int $t := $auint ?? $T_UINT !! rt_of($var.returns);
            cbail('attribute type') if $t < 0 || $t > 4;
            # A uint attribute (objprimspec 10) uses getattr_u/bindattr_u,
            # exactly as the bytecode path's '_u' suffix; its value lives
            # in an int slot.
            my int $id := $auint
                ?? (nqp::isnull($bindval) ?? 291 !! 312)
                !! (nqp::isnull($bindval)
                    ?? ($t == $T_OBJ ?? 81 !! 116 + $t)
                    !! ($t == $T_OBJ ?? 82 !! 120 + $t));
            epush(%e, $W_OPCALL);
            epush(%e, $id);
            epush(%e, nqp::isnull($bindval) ?? 3 !! 4);
            self.encode_child($var[0], %e, $T_OBJ);
            self.encode_child($var[1], %e, $T_OBJ);
            epush(%e, $W_SVAL); epush(%e, epool(%e, $name));
            self.encode_child($bindval, %e, $t) unless nqp::isnull($bindval);
            return $t;
        }
        if $scope eq 'lexicalref' {
            # A reference to a native lexical wanted as an object, the
            # Compiler.nqp shape: the nearest declaration decides. A
            # reference declaration is an object lexical holding the
            # reference, so it reads (and binds) as one; a plain native
            # declaration gets a reference taken over its slot, told the
            # declared width when the type is sized; nothing found
            # statically means the dynamic by-name road with the type
            # from .returns. The engine's LEXREF resolves the declaring
            # frame the way LEXGET does and allocates the reference there.
            my @r := self.resolve_lexref($name, %e);
            my int $kind := @r[0];
            if !nqp::isnull($bindval) {
                cbail('bind to a non-reference lexicalref ' ~ $name) unless $kind == 2;
                %e<frame_op> := 1;
                epush(%e, $W_LEXBIND); epush(%e, $T_OBJ); epush(%e, epool(%e, $name));
                self.encode_child($bindval, %e, $T_OBJ);
                return $T_OBJ;
            }
            if $kind == 2 {
                %e<frame_op> := 1;
                epush(%e, $W_LEXGET); epush(%e, $T_OBJ); epush(%e, epool(%e, $name));
                return $T_OBJ;
            }
            # A declaration found statically decides the SLOT type, and a
            # uint declaration was remapped to the int slots long before
            # this (so the bytecode path's getlexref_i is what matches).
            # Nothing found statically takes the type from .returns, where
            # a uint stays a uint: the bytecode path's getlexref_u, wire
            # type 4, which Ops.lexref_at wraps in the HLL's uint ref.
            my int $t := $kind == 1 ?? @r[1]
                !! (nqp::isnull($var.returns) ?? $T_OBJ
                    !! (nqp::objprimspec($var.returns) == 10 ?? $T_UINT
                        !! rt_of($var.returns)));
            cbail('lexicalref to a non-native ' ~ $name) if $t == $T_OBJ;
            cbail('lexicalref type') if $t < 0 || $t > 4;
            my int $spec := $kind == 1 ?? sized_native_ref_spec(@r[2]) !! 0;
            %e<frame_op> := 1;
            epush(%e, $W_LEXREF); epush(%e, $t); epush(%e, epool(%e, $name)); epush(%e, $spec);
            return $T_OBJ;
        }
        if $scope eq 'attributeref' {
            # A reference to a NATIVE attribute, exactly as Compiler.nqp
            # emits it: getattrref_<char>(object, class handle, name).
            # Binding through a reference is not a thing the bytecode path
            # allows either, and an object-typed attribute has no reference
            # form.
            cbail('attributeref bind') unless nqp::isnull($bindval);
            cbail('attributeref shape') unless nqp::elems(@($var)) == 2;
            # The width plays no part: Compiler.nqp picks getattrref_<char>
            # off the primspec alone (typechar), and Ops.getattrref_u
            # allocates a hintless attribute reference whose stores go
            # through the REPR, which knows the attribute's declared width.
            # A sized attributeref is therefore the same emission as a
            # full-width one.
            my int $arspec := nqp::isnull($var.returns) ?? 0 !! nqp::objprimspec($var.returns);
            my int $aruint := $arspec == 10 ?? 1 !! 0;
            my int $t := $aruint ?? $T_INT !! rt_of($var.returns);
            cbail('attributeref to a non-native') if $t == $T_OBJ;
            my int $id := $aruint ?? 313
                !! ($t == $T_INT ?? 159 !! $t == $T_NUM ?? 160
                    !! $t == $T_STR ?? 161 !! -1);
            cbail('attributeref type') if $id < 0;
            epush(%e, $W_OPCALL); epush(%e, $id); epush(%e, 3);
            self.encode_child($var[0], %e, $T_OBJ);
            self.encode_child($var[1], %e, $T_OBJ);
            epush(%e, $W_SVAL); epush(%e, epool(%e, $name));
            return $T_OBJ;
        }
        if $scope eq 'positional' {
            cbail('positional shape') unless nqp::elems(@($var)) == 2;
            epush(%e, $W_OPCALL);
            epush(%e, nqp::isnull($bindval) ?? 65 !! 66);   # atpos/bindpos
            epush(%e, nqp::isnull($bindval) ?? 2 !! 3);
            self.encode_child($var[0], %e, $T_OBJ);
            self.encode_child($var[1], %e, $T_INT);
            self.encode_child($bindval, %e, $T_OBJ) unless nqp::isnull($bindval);
            return $T_OBJ;
        }
        if $scope eq 'associative' {
            cbail('associative shape') unless nqp::elems(@($var)) == 2;
            epush(%e, $W_OPCALL);
            epush(%e, nqp::isnull($bindval) ?? 67 !! 68);   # atkey/bindkey
            epush(%e, nqp::isnull($bindval) ?? 2 !! 3);
            self.encode_child($var[0], %e, $T_OBJ);
            self.encode_child($var[1], %e, $T_STR);
            self.encode_child($bindval, %e, $T_OBJ) unless nqp::isnull($bindval);
            return $T_OBJ;
        }
        cbail('var scope ' ~ $scope);
    }

    # The type a plain read of this variable answers, before encoding it:
    # what VarWithFallback needs to know to decide whether a null test
    # applies at all.
    method var_read_type($var, %e) {
        my str $scope := $var.scope;
        my str $name := $var.name;
        cbail('var-with-fallback decl') if $var.decl ne '';
        if $scope eq 'local' {
            cbail('unknown local ' ~ $name) unless nqp::existskey(%e<locals>, $name);
            return %e<locals>{$name}[1];
        }
        if $scope eq 'lexical' || $scope eq 'typevar' {
            return self.lexical_type_of($name, %e, $scope);
        }
        if $scope eq 'contextual' {
            return self.lexical_in_scope($name, %e)
                ?? self.lexical_type_of($name, %e, $scope) !! $T_OBJ;
        }
        if $scope eq 'attribute' {
            my int $t := rt_of($var.returns);
            cbail('attribute type') if $t < 0 || $t > 3;
            return $t;
        }
        return $T_OBJ if $scope eq 'positional' || $scope eq 'associative'
            || $scope eq 'lexicalref' || $scope eq 'attributeref';
        cbail('var-with-fallback scope ' ~ $scope);
    }

    # The call of an immediate block: a lang-call on the block's code ref
    # (the CODEREF road, compiled at commit like any nested block), with
    # the condition local passed boxed when the block takes one.
    method encode_immediate_call($blk, %e, int $with_cond, int $cond_tmp_type, int $cond_tmp = 0) {
        epush(%e, $W_DISPATCH);
        %e<dispatches> := %e<dispatches> + 1;
        epush(%e, $T_OBJ);
        epush(%e, epool(%e, 'lang-call'));
        epush(%e, $with_cond ?? 2 !! 1);
        epush(%e, $T_OBJ);
        epush(%e, $T_OBJ) if $with_cond;
        epush(%e, $W_CODEREF);
        nqp::push(%e<nested>, [nqp::elems(%e<code>), $blk]);
        epush(%e, 0);   # qbid, patched after the deferred compile
        if $with_cond {
            if $cond_tmp_type != $T_OBJ {
                epush(%e, $W_COERCE); epush(%e, coerce_kind($cond_tmp_type, $T_OBJ));
            }
            epush(%e, $W_LOCGET); epush(%e, $cond_tmp_type); epush(%e, $cond_tmp);
        }
        $T_OBJ
    }

    # A loop condition, plain or bound into the scratch local a
    # cond-taking body is called with (then re-read as the test, so the
    # loop tests the same value the body receives).
    method encode_loop_cond($cond, %e, int $im, int $im_tmp) {
        return self.encode_node($cond, %e, $T_ANY) unless $im;
        epush(%e, $W_STMTS); epush(%e, 2);
        epush(%e, $W_LOCBIND); epush(%e, $T_OBJ); epush(%e, $im_tmp);
        self.encode_child($cond, %e, $T_OBJ);
        epush(%e, $W_LOCGET); epush(%e, $T_OBJ); epush(%e, $im_tmp);
        $T_OBJ
    }

    # Compiler.nqp's needs_cond_passed: an immediate block child of an
    # if/with that takes the condition as its argument.
    sub needs_cond_passed($n) {
        nqp::istype($n, QAST::Block)
        && ($n.arity > 0 || $n.ann('count'))
        && ($n.blocktype eq 'immediate' || $n.blocktype eq 'immediate_static')
    }

    # A rule the grammar engine covers, handed to it whole -- exactly what
    # Compiler.nqp's engine_jast does, built here as a QAST tree the general
    # encoder consumes. The bytecode path is untouched; a rule the engine
    # does NOT cover (rx_descriptor is null) bails, so its full matcher
    # stays bytecode. The prologue is engine_jast's: !cursor_start_all
    # answers the cursor, target and start position (the cursor's own $!pos
    # is -3 until the rule finishes), the invocant's $!from decides
    # scanning, and rxmatch runs the rule. The callback block -- the pieces
    # the engine cannot express -- rides the CODEREF road (compiled to
    # bytecode as any nested block), so nothing about the rule body needs
    # to encode; only this prologue does.
    method encode_regex($node, %e) {
        my $comp := %e<comp>;
        my $desc := $comp.rx_descriptor($node);
        cbail('regex the engine does not cover') if nqp::isnull($desc);

        my $p := QAST::Node.unique('rxe') ~ '_';
        my sub loc($n, *%o) { QAST::Var.new( :name($p ~ $n), :scope('local'), |%o ) }
        my sub decl($n, $ret?) {
            my %o := nqp::defined($ret) ?? nqp::hash('returns', $ret) !! nqp::hash();
            QAST::Var.new( :name($p ~ $n), :scope('local'), :decl('var'), |%o )
        }
        my sub b($t, $v) { QAST::Op.new( :op('bind'), $t, $v ) }
        my sub startpos($i) {
            QAST::Op.new( :op('atpos'), loc('start'), QAST::IVal.new( :value($i) ) )
        }
        my $self := QAST::Var.new( :name('self'), :scope('local') );
        my $callback := nqp::elems($desc.callbacks)
            ?? $comp.rx_callback_block($desc)
            !! QAST::Op.new( :op('null') );

        my $tree := QAST::Stmts.new(
            b(decl('start'),
                QAST::Op.new( :op('callmethod'), :name('!cursor_start_all'), $self )),
            b(QAST::Var.new( :name("\$¢"), :scope('lexical') ),
                b(decl('cur'), startpos(0))),
            b(decl('tgt', str), QAST::Op.new( :op('unbox_s'), startpos(1) )),
            b(decl('pos', int), QAST::Op.new( :op('unbox_i'), startpos(2) )),
            b(decl('curclass'), startpos(3)),
            b(decl('selffrom', int),
                QAST::Op.new( :op('getattr_i'), $self, loc('curclass'),
                    QAST::SVal.new( :value('$!from') ) )),
            b(decl('restart', int), QAST::Op.new( :op('unbox_i'), startpos(5) )),
            b(decl('callback'), $callback),
            QAST::Op.new( :op('rxmatch'),
                QAST::SVal.new( :value($desc.encoded) ),
                loc('cur'), loc('curclass'), loc('tgt'), loc('pos'),
                loc('selffrom'), loc('restart'), $self, loc('callback') )
        );
        self.encode_node($tree, %e, $T_OBJ)
    }

    method encode_lexget(str $name, %e) {
        my int $type := self.lexical_type_of($name, %e, 'lexical');
        %e<frame_op> := 1;
        epush(%e, $W_LEXGET); epush(%e, $type); epush(%e, epool(%e, $name));
        $type
    }

    # The declared type of a lexical: this block's shadow table first,
    # then the enclosing BlockInfos, the same walk the compiler does. Not
    # found anywhere means the dynamic road, which is object-typed --
    # exactly the rewrite the bytecode path does. A contextual that is
    # not in static scope walks the dynamic chain instead.
    method lexical_type_of(str $name, %e, str $scope) {
        if nqp::existskey(%e<own>, $name) {
            return %e<own>{$name};
        }
        my $cur := %e<block>.outer;
        while $cur {
            if $cur.qast.ann('DYN_COMP_WRAPPER') {
                $cur := 0;
            }
            else {
                my $t := $cur.lexical_type($name);
                if nqp::defined($t) {
                    # A plain uint outer lexical (stored type 10) reads from
                    # the int slot table it was remapped into.
                    $t := $T_INT if $t == 10 && !nqp::objprimbits($cur.lexical_returns($name))
                        || $t == 10 && nqp::objprimbits($cur.lexical_returns($name)) == 64;
                    cbail('typed outer lexical wider than obj') if $t > 3 || $t < 0;
                    return $t;
                }
                # A reference declaration is an object lexical holding
                # the reference; a plain read answers that object, as
                # the bytecode path does.
                return $T_OBJ if nqp::defined($cur.lexicalref_type($name));
                $cur := $cur.outer;
            }
        }
        $T_OBJ
    }

    # The nearest declaration of a name up the block chain, for the
    # lexicalref scope: [kind, type, returns], kind 0 not found, 1 a
    # plain lexical (type and declared .returns follow), 2 a reference
    # declaration. This block's shadow tables first, then the enclosing
    # BlockInfos, stopping at a DYN_COMP_WRAPPER like every other walk.
    method resolve_lexref(str $name, %e) {
        if nqp::existskey(%e<own>, $name) {
            return [2, $T_OBJ, nqp::null()] if nqp::existskey(%e<ownref>, $name);
            return [1, %e<own>{$name},
                nqp::existskey(%e<ownret>, $name) ?? %e<ownret>{$name} !! nqp::null()];
        }
        my $cur := %e<block>.outer;
        while $cur {
            if $cur.qast.ann('DYN_COMP_WRAPPER') {
                $cur := 0;
            }
            else {
                my $t := $cur.lexical_type($name);
                if nqp::defined($t) {
                    return [1, $t, nqp::ifnull($cur.lexical_returns($name), nqp::null())];
                }
                return [2, $T_OBJ, nqp::null()] if nqp::defined($cur.lexicalref_type($name));
                $cur := $cur.outer;
            }
        }
        [0, $T_OBJ, nqp::null()]
    }

    # Whether a lexical resolves statically anywhere up the BlockInfo
    # chain (this block's shadow table included).
    method lexical_in_scope(str $name, %e) {
        return 1 if nqp::existskey(%e<own>, $name);
        my $cur := %e<block>.outer;
        while $cur {
            if $cur.qast.ann('DYN_COMP_WRAPPER') {
                $cur := 0;
            }
            else {
                return 1 if nqp::defined($cur.lexical_type($name));
                return 1 if nqp::defined($cur.lexicalref_type($name));
                $cur := $cur.outer;
            }
        }
        0
    }

    method encode_decl($var, %e, str $decl, str $scope) {
        my str $name := $var.name;
        # A uint lexical shares the int slot table, exactly as
        # BlockInfo.register_lexical remaps it ("$type := 1 if $type ==
        # 10"): its storage is a long, and the unsigned-ness lives in the
        # ops that read it, not the slot. A SIZED native (int8, uint16,
        # num32) stores truncated to its declared width -- the bind road
        # wraps every store in sized_trunc, as Compiler.nqp's
        # emit_sized_native_trunc does.
        my int $type := lex_rt($var.returns);
        cbail('uint or wide lexical') if $type > 3 || $type < 0;
        if $decl eq 'param' {
            cbail('param scope ' ~ $scope) unless $scope eq 'lexical' || $scope eq 'local';
            # A native parameter fetches through posparam_<t>/namedparam_<t>
            # and binds into a typed slot. A slurpy is always an object; a
            # sized native gets patch_params' truncating first task, the
            # bytecode path's post-fetch emit_sized_native_trunc.
            if $type != $T_OBJ {
                cbail('typed slurpy param') if $var.slurpy;
            }
            if $scope eq 'local' {
                self.declare_elocal($var, %e, $type);
            }
            else {
                cbail('redeclared lexical ' ~ $name) if nqp::existskey(%e<own>, $name);
                %e<own>{$name} := $type;
                %e<ownret>{$name} := $var.returns;
                nqp::push(%e<decls>, ['lex', $var]);
            }
            nqp::push(%e<params>, $var);
        }
        elsif $decl eq 'var' {
            if $scope eq 'local' {
                self.declare_elocal($var, %e, $type);
            }
            elsif $scope eq 'lexical' {
                cbail('redeclared lexical ' ~ $name) if nqp::existskey(%e<own>, $name);
                %e<own>{$name} := $type;
                %e<ownret>{$name} := $var.returns;
                nqp::push(%e<decls>, ['lex', $var]);
            }
            elsif $scope eq 'lexicalref' {
                # An object lexical that will hold a reference; the
                # BlockInfo registers it in the object table at commit
                # (add_lexicalref), exactly as the bytecode declaration.
                cbail('redeclared lexical ' ~ $name) if nqp::existskey(%e<own>, $name);
                cbail('lexicalref declaration of a non-native') if $type == $T_OBJ;
                %e<own>{$name} := $T_OBJ;
                %e<ownref>{$name} := 1;
                nqp::push(%e<decls>, ['lexref', $var]);
            }
            else {
                cbail('decl var scope ' ~ $scope);
            }
        }
        elsif $decl eq 'static' {
            cbail('static scope') unless $scope eq 'lexical';
            %e<own>{$name} := $T_OBJ;
            nqp::push(%e<decls>, ['static', $var]);
        }
        elsif $decl eq 'contvar' {
            cbail('contvar scope ' ~ $scope) unless $scope eq 'lexical';
            %e<own>{$name} := $T_OBJ;
            nqp::push(%e<decls>, ['cont', $var]);
        }
        elsif $decl eq 'statevar' {
            cbail('statevar scope') unless $scope eq 'lexical';
            %e<own>{$name} := $T_OBJ;
            nqp::push(%e<decls>, ['state', $var]);
        }
        else {
            cbail('decl ' ~ $decl);
        }
    }

    method declare_elocal($var, %e, int $type) {
        my str $name := $var.name;
        cbail('redeclared local ' ~ $name) if nqp::existskey(%e<locals>, $name);
        my int $idx := new_elocal(%e, $type);
        %e<locals>{$name} := [$idx, $type];
    }

    # The slot-storage type of a lexical: a plain (unsized) uint shares
    # the int slots, as BlockInfo.register_lexical remaps it. A sized uint
    # keeps its own -9 so encode_decl bails on it.
    sub lex_rt($typeobj) {
        my int $spec := nqp::isnull($typeobj) ?? 0 !! nqp::objprimspec($typeobj);
        if $spec == 10 {
            # Any uint lexical shares the int slot table (BlockInfo remaps
            # type 10 -> 1). A SIZED uint (uint8/16/32) additionally masks its
            # value to `bits` on every bind -- emit_lex_bind_value does that --
            # so the stored/read long is the correct zero-extended magnitude
            # (a uint is a subset of Int, so it boxes to a positive Int).
            return $T_INT;
        }
        rt_of($typeobj)
    }

    # The declared `returns` that decides the width a store to this
    # lexical truncates to: Compiler.nqp reads it off the DECLARING block
    # ($decl-block.lexical_returns($name)), so a bind through an outer
    # lexical truncates to the outer declaration's width too. The bind
    # node's own :returns is the fallback for a decl-with-init
    # (`my uint8 $x = v`), whose declaration is the node itself.
    method sized_ret($var, str $name, %e) {
        my @r := self.resolve_lexref($name, %e);
        return @r[2] if @r[0] == 1 && !nqp::isnull(@r[2]);
        $var.returns
    }

    # Compiler.nqp's emit_sized_native_trunc, as a QAST rewrite over the
    # value being stored: a store to a sized native truncates to the
    # declared width the way MoarVM's sized registers do -- mask for
    # unsigned, shift out and arithmetically back for signed, a round trip
    # through a 32-bit float for num32. A full-width or non-native type
    # answers the value unchanged, so every caller can wrap unconditionally.
    method sized_trunc($value, $returns) {
        return $value if nqp::isnull($returns);
        my int $spec := nqp::objprimspec($returns);
        if $spec == 1 || $spec == 10 {
            my int $bits := nqp::objprimbits($returns);
            if $bits > 0 && $bits < 64 {
                return $spec == 10
                    ?? QAST::Op.new( :op('bitand_i'), $value,
                         QAST::IVal.new(
                           :value(nqp::sub_i(nqp::bitshiftl_i(1, $bits), 1)) ) )
                    !! QAST::Op.new( :op('bitshiftr_i'),
                         QAST::Op.new( :op('bitshiftl_i'), $value,
                           QAST::IVal.new( :value(64 - $bits) ) ),
                         QAST::IVal.new( :value(64 - $bits) ) );
            }
        }
        elsif $spec == 2 && nqp::objprimbits($returns) == 32 {
            return QAST::Op.new( :op('sized_num32'), $value );
        }
        $value
    }

    sub rt_of($typeobj) {
        my int $spec := nqp::objprimspec($typeobj);
        $spec == 1 ?? $T_INT !! $spec == 2 ?? $T_NUM !! $spec == 3 ?? $T_STR
            !! $spec == 0 ?? $T_OBJ !! -9
    }

    # Compiler.nqp's sized_native_ref_spec: the width a reference to a
    # sized native lexical must be told, since the long/double slots
    # carry none. Low byte the bit width, +256 unsigned, 32 alone num32;
    # 0 for full width or an unsized type.
    sub sized_native_ref_spec($returns) {
        my int $spec := nqp::isnull($returns) ?? 0 !! nqp::objprimspec($returns);
        if $spec == 1 || $spec == 10 {
            my int $bits := nqp::objprimbits($returns);
            if $bits > 0 && $bits < 64 {
                return $spec == 10 ?? 256 + $bits !! $bits;
            }
        }
        elsif $spec == 2 {
            return 32 if nqp::objprimbits($returns) == 32;
        }
        0
    }
}
