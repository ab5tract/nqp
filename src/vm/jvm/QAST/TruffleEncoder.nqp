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
        $code_report := nqp::existskey(%env, 'NQP_CODE_REPORT') ?? 1 !! 0;
        $code_survey := nqp::existskey(%env, 'NQP_CODE_SURVEY') ?? 1 !! 0;
        $code_on := $code_report || $code_survey;
        return 0 unless $code_on;

        for nqp::split(' ', subst_ws($ops))    { %covered{'op:' ~ $_} := 1 }
        for nqp::split(' ', subst_ws($scopes)) { %covered{'var:' ~ $_} := 1 }
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
        # tag group still needs an NQP_CODE_ALSO run, exactly like NQP_RX_NO.
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
    #   NQP_CODE_RUN=1        master switch: attempt encoding at all
    #   NQP_CODE_ENCODED=1    print each block the engine takes over
    #   NQP_CODE_BAIL=1       print why a block was refused
    #   NQP_CODE_SKIP=a,b     refuse these blocks by name
    #   NQP_CODE_SKIP_ANON=1  refuse blocks with no name
    #   NQP_CODE_ONLY=a,b     refuse everything else
    #   NQP_CODE_LEAF=1      refuse blocks that dispatch at all: a block
    #                        that never calls can never be caught inside a
    #                        continuation, so this is the conservative mode

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

    # Types, matching both NqpWire and this backend's $RT_* numbering.
    my int $T_OBJ := 0;
    my int $T_INT := 1;
    my int $T_NUM := 2;
    my int $T_STR := 3;
    my int $T_VOID := -1;
    my int $T_ANY := -2;

    my int $run_init_done := 0;
    my int $code_run := 0;
    my int $code_encoded := 0;
    my int $code_bail_p := 0;
    my int $code_leaf := 0;
    my int $code_skip_anon := 0;
    my int $code_only_set := 0;
    my %code_skip;
    my %code_only;

    sub run_init() {
        return 0 if $run_init_done;
        $run_init_done := 1;
        my %env := nqp::getenvhash();
        $code_run := nqp::existskey(%env, 'NQP_CODE_RUN') ?? 1 !! 0;
        return 0 unless $code_run;
        $code_encoded  := nqp::existskey(%env, 'NQP_CODE_ENCODED') ?? 1 !! 0;
        $code_bail_p   := nqp::existskey(%env, 'NQP_CODE_BAIL') ?? 1 !! 0;
        $code_leaf     := nqp::existskey(%env, 'NQP_CODE_LEAF') ?? 1 !! 0;
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
    sub emit_init() {
        return 0 if $emit_init_done;
        $emit_init_done := 1;
        op3('say', 0, $T_STR, 's');
        op3('print', 1, $T_STR, 's');
        op3('add_i', 2, $T_INT, 'ii');
        op3('sub_i', 3, $T_INT, 'ii');
        op3('mul_i', 4, $T_INT, 'ii');
        op3('div_i', 5, $T_INT, 'ii');
        op3('mod_i', 6, $T_INT, 'ii');
        op3('neg_i', 7, $T_INT, 'i');
        op3('abs_i', 8, $T_INT, 'i');
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
        op3('concat', 33, $T_STR, 'ss');
        op3('chars', 34, $T_INT, 's');
        op3('uc', 35, $T_STR, 's');
        op3('lc', 36, $T_STR, 's');
        op3('substr/2', 37, $T_STR, 'si');
        op3('substr/3', 38, $T_STR, 'sii');
        op3('index/2', 39, $T_INT, 'ss');
        op3('index/3', 40, $T_INT, 'ssi');
        op3('eqat', 41, $T_INT, 'ssi');
        op3('chr', 42, $T_STR, 'i');
        op3('join', 43, $T_STR, 'so');
        op3('split', 44, $T_OBJ, 'ss');
        op3('iseq_s', 45, $T_INT, 'ss');
        op3('isne_s', 46, $T_INT, 'ss');
        op3('islt_s', 47, $T_INT, 'ss');
        op3('isle_s', 48, $T_INT, 'ss');
        op3('isgt_s', 49, $T_INT, 'ss');
        op3('isge_s', 50, $T_INT, 'ss');
        op3('decont', 51, $T_OBJ, 'o');
        op3('isnull', 52, $T_INT, 'o');
        op3('isconcrete', 53, $T_INT, 'o');
        op3('defined', 53, $T_INT, 'o');
        op3('istrue', 54, $T_INT, 'o');
        op3('istype', 55, $T_INT, 'oo');
        op3('eqaddr', 56, $T_INT, 'oo');
        op3('what', 57, $T_OBJ, 'o');
        op3('create', 58, $T_OBJ, 'o');
        op3('clone', 59, $T_OBJ, 'o');
        op3('elems', 60, $T_INT, 'o');
        op3('push', 61, $T_OBJ, 'oo');
        op3('pop', 62, $T_OBJ, 'o');
        op3('shift', 63, $T_OBJ, 'o');
        op3('unshift', 64, $T_OBJ, 'oo');
        op3('atpos', 65, $T_OBJ, 'oi');
        op3('bindpos', 66, $T_OBJ, 'oio');
        op3('atkey', 67, $T_OBJ, 'os');
        op3('bindkey', 68, $T_OBJ, 'oso');
        op3('existskey', 69, $T_INT, 'os');
        op3('deletekey', 70, $T_OBJ, 'os');
        op3('iscont', 71, $T_INT, 'o');
        op3('hllize', 72, $T_OBJ, 'o');
        op3('islist', 73, $T_INT, 'o');
        op3('ishash', 74, $T_INT, 'o');
        op3('unbox_i', 75, $T_INT, 'o');
        op3('unbox_n', 76, $T_NUM, 'o');
        op3('unbox_s', 77, $T_STR, 'o');
        op3('box_i', 78, $T_OBJ, 'i');
        op3('box_n', 79, $T_OBJ, 'n');
        op3('box_s', 80, $T_OBJ, 's');
        op3('ord/1', 83, $T_INT, 's');
        op3('null_s', 84, $T_STR, '');
        op3('getlexdyn', 86, $T_OBJ, 's');
        op3('forceouterctx', 88, $T_OBJ, 'oo');
        op3('can', 89, $T_INT, 'os');
        op3('isinvokable', 90, $T_INT, 'o');
        op3('setelems', 91, $T_OBJ, 'oi');
        op3('existspos', 92, $T_INT, 'oi');
        op3('clone_nd', 93, $T_OBJ, 'o');
        op3('setcodeobj', 94, $T_OBJ, 'oo');
        op3('getcurhllsym', 95, $T_OBJ, 's');
        op3('takeclosure', 96, $T_OBJ, 'o');
        op3('getcodeobj', 97, $T_OBJ, 'o');
        op3('curcode', 98, $T_OBJ, '');
        op3('p6capturelex', 100, $T_OBJ, 'o');
        op3('p6sink', 101, $T_OBJ, 'o');
        op3('p6store', 102, $T_OBJ, 'oo');
        op3('p6box_i', 103, $T_OBJ, 'i');
        op3('p6box_n', 104, $T_OBJ, 'n');
        op3('p6box_s', 105, $T_OBJ, 's');
        op3('p6definite', 106, $T_OBJ, 'o');
        op3('p6bindattrinvres', 107, $T_OBJ, 'ooso');
        1
    }

    # Coercion kinds, matching NqpOps.
    sub coerce_kind(int $from, int $to) {
        if $from == $T_INT { return $to == $T_OBJ ?? 0 !! $to == $T_NUM ?? 6 !! $to == $T_STR ?? 8 !! -1 }
        if $from == $T_NUM { return $to == $T_OBJ ?? 1 !! $to == $T_INT ?? 7 !! -1 }
        if $from == $T_STR { return $to == $T_OBJ ?? 2 !! -1 }
        if $from == $T_OBJ { return $to == $T_INT ?? 3 !! $to == $T_NUM ?? 4 !! $to == $T_STR ?? 5 !! -1 }
        -1
    }

    sub cbail(str $why) { nqp::die('code-bail ' ~ $why) }

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

    method encode_block($node, $block, $comp) {
        run_init();
        return '' unless $code_run;
        my str $name := $node.name;
        return '' if $code_skip_anon && $name eq '';
        return '' if nqp::existskey(%code_skip, $name);
        if $code_only_set {
            return '' unless nqp::existskey(%code_only, $name);
        }
        return '' if $node.has_exit_handler || $node.blocktype eq 'raw';

        my %e := nqp::hash(
            'code', nqp::list(), 'pool', nqp::list(), 'pooli', nqp::hash(),
            'own', nqp::hash(), 'locals', nqp::hash(), 'ltypes', nqp::list(),
            'params', nqp::list(), 'decls', nqp::list(),
            'nested', nqp::list(),
            'block', $block, 'qast', $node, 'comp', $comp, 'dispatches', 0);
        epush(%e, 1);   # wire version
        epush(%e, 0);   # result type, patched below
        epush(%e, 0);   # local count, patched below
        my str $out := '';
        my $err := nqp::null();
        try {
            nqp::bindpos(%e<code>, 1, self.encode_body($node, %e));
            cbail('leaf-only') if $code_leaf && %e<dispatches>;
            CATCH { $err := $! }
        }
        if !nqp::isnull($err) {
            # Only a deliberate refusal falls back to bytecode; any other
            # exception is a real compile error that must stay loud -- a
            # swallowed one lets a block that should refuse to compile
            # compile.
            my str $msg := nqp::getmessage($err);
            nqp::rethrow($err) if nqp::index($msg, 'code-bail') < 0;
            if $code_bail_p {
                nqp::say('code bail: '
                    ~ ($name eq '' ?? '<anon ' ~ $node.cuid ~ '>' !! $name)
                    ~ ' ' ~ $msg);
            }
            return '';
        }

        # Splice the local types in after [version, nlocals], and only now
        # touch the BlockInfo: a bail above must leave it untouched or the
        # bytecode path would re-declare every lexical and die.
        my @code := %e<code>;
        my @ltypes := %e<ltypes>;
        nqp::bindpos(@code, 2, nqp::elems(@ltypes));
        nqp::splice(@code, @ltypes, 3, 0);
        for %e<decls> -> $d {
            my str $kind := $d[0];
            my $var := $d[1];
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
                my $r := $comp.as_jast($blk);
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
        # The program travels as one string constant; the class file caps
        # those at 65535 UTF-8 bytes, the same cliff the rx descriptor
        # refuses at.
        return '' if nqp::chars($out) > 60000;
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
        if $n == 0 { epush(%e, $W_NULLC) }
        self.patch_params($params_at, %e);
        $rtype
    }

    # Parameters were collected while the walk met their declarations;
    # the prologue is spliced in where the placeholder sits. Any default
    # expressions are encoded here, into a scratch list.
    method patch_params($params_at, %e) {
        my @params := %e<params>;
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
        nqp::bindkey(%e, 'inparams', 1);
        epush(%e, $pos_required);
        epush(%e, $pos_slurpy ?? -1 !! $pos_required + $pos_optional);
        epush(%e, nqp::elems(@params));
        for @params -> $p {
            my int $kind := $p.slurpy
                ?? ($p.named ?? 3 !! 1)
                !! ($p.named ?? 2 !! 0);
            epush(%e, $kind);
            epush(%e, $T_OBJ);
            if $p.scope eq 'local' {
                epush(%e, 1);
                epush(%e, %e<locals>{$p.name}[0]);
            }
            else {
                epush(%e, 0);
                epush(%e, epool(%e, $p.name));
            }
            if $kind == 2 || $kind == 3 {
                epush(%e, epool(%e, ~$p.named));
            }
            if $p.default {
                epush(%e, 1);
                self.encode_child($p.default, %e, $T_OBJ);
            }
            else {
                epush(%e, 0);
            }
        }
        %e<code> := @save;
        nqp::bindkey(%e, 'inparams', 0);
        nqp::splice(%e<code>, @p, $params_at + 1, 0);
        # Everything the walk recorded by position sits after the
        # placeholder this prologue was just spliced over; shift it.
        for %e<nested> -> $nb {
            nqp::bindpos($nb, 0, $nb[0] + nqp::elems(@p)) if $nb[0] > $params_at;
        }
    }

    # Encodes one node, coercing its value to $want when the types allow
    # it and bailing when they do not. $T_ANY takes what comes; $T_VOID
    # discards, so anything goes.
    method encode_child($n, %e, int $want) {
        my int $mark := nqp::elems(%e<code>);
        my int $got := self.encode_node($n, %e, $want);
        return $got if $want == $T_ANY || $want == $T_VOID || $got == $want;
        my int $kind := coerce_kind($got, $want);
        cbail('no coercion ' ~ $got ~ '->' ~ $want) if $kind < 0;
        nqp::splice(%e<code>, [$W_COERCE, $kind], $mark, 0);
        $want
    }

    method encode_node($n, %e, int $want) {
        if nqp::istype($n, QAST::Op) {
            return self.encode_op($n, %e, $want);
        }
        if nqp::istype($n, QAST::VarWithFallback) {
            cbail('var-with-fallback');
        }
        if nqp::istype($n, QAST::Var) {
            return self.encode_var($n, %e, nqp::null(), $want);
        }
        if nqp::istype($n, QAST::Want) {
            # This backend always takes the default child and post-coerces
            # (see as_jast(QAST::Want)); mirror that exactly.
            return self.encode_node($n[0], %e, $want);
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
            cbail('block ' ~ $bt);
        }
        if nqp::istype($n, QAST::BVal) {
            cbail('bval to an uncompiled block')
                unless $*CODEREFS.know_cuid($n.value.cuid);
            epush(%e, $W_CODEREF);
            nqp::push(%e<nested>, [nqp::elems(%e<code>), $n.value]);
            epush(%e, 0);
            return $T_OBJ;
        }
        if nqp::istype($n, QAST::Regex) {
            cbail('regex');
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
            epush(%e, $W_NULLC);
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

    method encode_op($op, %e, int $want) {
        my str $name := $op.op;

        if $name eq 'bind' {
            cbail('bind arity') unless nqp::elems(@($op)) == 2;
            return self.encode_var($op[0], %e, $op[1], $want);
        }
        if $name eq 'if' || $name eq 'unless' {
            return self.encode_if($op, %e, $want, $name eq 'unless' ?? 1 !! 0);
        }
        if $name eq 'while' || $name eq 'until' {
            # A loop normally registers last/next/redo unwind handlers; an
            # engine frame has none, and the runtime resolving a callee's
            # `last` would silently target an OUTER loop instead. Only a
            # loop compiled :nohandler is honest to encode; handlers are
            # Phase 3's ControlFlowException work.
            my int $nohandler := 0;
            my @operands;
            for @($op) {
                if $_.named eq 'nohandler' { $nohandler := 1 }
                elsif $_.named eq 'label' { cbail('labeled loop') }
                else { nqp::push(@operands, $_) }
            }
            cbail('loop with handlers') unless $nohandler;
            cbail('loop shape') unless nqp::elems(@operands) == 2;
            epush(%e, $W_LOOP);
            epush(%e, $name eq 'until' ?? 1 !! 0);
            epush(%e, 0);
            my int $ct_at := nqp::elems(%e<code>);
            epush(%e, 0);
            my int $condt := self.encode_node(@operands[0], %e, $T_ANY);
            nqp::bindpos(%e<code>, $ct_at, $condt);
            self.encode_node(@operands[1], %e, $T_VOID);
            return $T_OBJ;
        }
        if $name eq 'call' || $name eq 'callstatic' {
            return self.encode_call($op, %e);
        }
        if $name eq 'callmethod' {
            return self.encode_callmethod($op, %e);
        }
        if $name eq 'chain' || $name eq 'chainstatic' {
            cbail('chained chain') if nqp::istype($op[0], QAST::Op)
                && ($op[0].op eq 'chain' || $op[0].op eq 'chainstatic');
            cbail('chain arity') unless nqp::elems(@($op)) == 2;
            epush(%e, $W_DISPATCH);
            %e<dispatches> := %e<dispatches> + 1;
            epush(%e, rt_of($op.returns));
            epush(%e, epool(%e, 'lang-call'));
            epush(%e, 3);
            epush(%e, $T_OBJ); epush(%e, $T_OBJ); epush(%e, $T_OBJ);
            self.encode_op_named_lexical_decont($op.name, %e);
            self.encode_child($op[0], %e, $T_OBJ);
            self.encode_child($op[1], %e, $T_OBJ);
            return rt_of($op.returns);
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
        if $name eq 'getlexouter' {
            cbail('getlexouter shape') unless nqp::istype($op[0], QAST::SVal);
            epush(%e, $W_GETLEXOUTER);
            epush(%e, epool(%e, $op[0].value));
            return $T_OBJ;
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

    method encode_if($op, %e, int $want, int $negate) {
        my int $n := nqp::elems(@($op));
        cbail('if arity') unless $n == 2 || $n == 3;
        my int $void := $want == $T_VOID;
        cbail('two-child if in value context') if $n == 2 && !$void;
        epush(%e, $void ?? $W_IFS !! $W_IFV);
        my int $ct_at := nqp::elems(%e<code>);
        epush(%e, 0);
        epush(%e, $negate);
        epush(%e, $n == 3 ?? 1 !! 0);
        # Condition type is known only after encoding it; the slot is
        # reserved above and patched here.
        my int $mark := nqp::elems(%e<code>);
        my int $condt := self.encode_node($op[0], %e, $T_ANY);
        nqp::bindpos(%e<code>, $ct_at, $condt);
        my int $btype := $void ?? $T_VOID !! ($want == $T_ANY ?? $T_OBJ !! $want);
        self.encode_child($op[1], %e, $btype);
        self.encode_child($op[2], %e, $btype) if $n == 3;
        $void ?? $T_OBJ !! ($want == $T_ANY ?? $T_OBJ !! $want)
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
            nqp::push(@flagpos, nqp::elems(%e<code>));
            epush(%e, 0);
            epush(%e, epool(%e, ~$named)) if $named;
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
    method encode_var($var, %e, $bindval, int $want) {
        my str $name := $var.name;
        my str $scope := $var.scope;
        my str $decl := $var.decl;

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
            if nqp::isnull($bindval) {
                epush(%e, $W_LEXGET); epush(%e, $type); epush(%e, epool(%e, $name));
            }
            else {
                epush(%e, $W_LEXBIND); epush(%e, $type); epush(%e, epool(%e, $name));
                self.encode_child($bindval, %e, $type);
            }
            return $type;
        }
        if $scope eq 'attribute' {
            cbail('attribute shape') unless nqp::elems(@($var)) == 2;
            cbail('typed attribute') unless rt_of($var.returns) == $T_OBJ;
            epush(%e, $W_OPCALL);
            epush(%e, nqp::isnull($bindval) ?? 81 !! 82);   # getattr/bindattr
            epush(%e, nqp::isnull($bindval) ?? 3 !! 4);
            self.encode_child($var[0], %e, $T_OBJ);
            self.encode_child($var[1], %e, $T_OBJ);
            epush(%e, $W_SVAL); epush(%e, epool(%e, $name));
            self.encode_child($bindval, %e, $T_OBJ) unless nqp::isnull($bindval);
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

    method encode_lexget(str $name, %e) {
        my int $type := self.lexical_type_of($name, %e, 'lexical');
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
                    cbail('typed outer lexical wider than obj') if $t > 3 || $t < 0;
                    return $t;
                }
                cbail('lexicalref ' ~ $name) if nqp::defined($cur.lexicalref_type($name));
                $cur := $cur.outer;
            }
        }
        $T_OBJ
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
        my int $type := rt_of($var.returns);
        cbail('uint or wide lexical') if $type > 3 || $type < 0;
        if $decl eq 'param' {
            cbail('param scope ' ~ $scope) unless $scope eq 'lexical' || $scope eq 'local';
            cbail('typed param') unless $type == $T_OBJ;
            if $scope eq 'local' {
                self.declare_elocal($var, %e, $type);
            }
            else {
                cbail('redeclared lexical ' ~ $name) if nqp::existskey(%e<own>, $name);
                %e<own>{$name} := $type;
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
                nqp::push(%e<decls>, ['lex', $var]);
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

    sub rt_of($typeobj) {
        my int $spec := nqp::objprimspec($typeobj);
        $spec == 1 ?? $T_INT !! $spec == 2 ?? $T_NUM !! $spec == 3 ?? $T_STR
            !! $spec == 0 ?? $T_OBJ !! -9
    }
}
