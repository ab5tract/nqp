use QASTNode;
use NQPHLL;

# The unit record: what the artifact writer reads (RecordReader.kt) --
# a unit's identity, its block table, its programs and its serialized
# context. Built by QAST::UnitCompiler.unit; no instruction list of any
# kind (docs/superpowers/specs/2026-09-10-jvm-unit-artifact-milestone-4-design.md).
class QAST::UnitRecord {
    has str $!unit_id;
    has str $!file;
    has str $!hll;
    has int $!mainline_qbid;
    has int $!entry_qbid;
    has int $!deserialize_qbid;
    has int $!load_qbid;
    has int $!serialized_count;
    has str $!sc_handle;
    has str $!sc_desc;
    has str $!serialized;        # base64 of the serialized context; '' when none
    has @!blocks;                # QAST::BlockRecord, in registration order
    has @!programs;              # engine program text per program index
    has @!callsites;             # [@arg_types, @arg_names] per call site
    has @!blockvalues;           # [qbid, name, sc_handle, sc_idx, flags] rows
    has @!nested_units;          # unit ids of nested in-memory units

    method BUILD(:$unit_id!, :$file) {
        $!unit_id := $unit_id;
        $!file := $file // '';
        $!hll := '';
        $!mainline_qbid := -1;
        $!entry_qbid := -1;
        $!deserialize_qbid := -1;
        $!load_qbid := -1;
        $!serialized_count := -1;
        $!sc_handle := '';
        $!sc_desc := '';
        $!serialized := nqp::null_s();
        @!blocks := [];
        @!programs := [];
        @!callsites := [];
        @!blockvalues := [];
        @!nested_units := [];
    }

    method add_block($b) { nqp::push(@!blocks, $b) }
    method blocks() { @!blocks }
    method unit_id() { $!unit_id }
    method file() { $!file }
    method hll(*@value) { @value ?? ($!hll := @value[0]) !! $!hll }
    method mainline_qbid(*@value) { @value ?? ($!mainline_qbid := @value[0]) !! $!mainline_qbid }
    method entry_qbid(*@value) { @value ?? ($!entry_qbid := @value[0]) !! $!entry_qbid }
    method deserialize_qbid(*@value) { @value ?? ($!deserialize_qbid := @value[0]) !! $!deserialize_qbid }
    method load_qbid(*@value) { @value ?? ($!load_qbid := @value[0]) !! $!load_qbid }
    method serialized_count(*@value) { @value ?? ($!serialized_count := @value[0]) !! $!serialized_count }
    method sc_handle(*@value) { @value ?? ($!sc_handle := @value[0]) !! $!sc_handle }
    method sc_desc(*@value) { @value ?? ($!sc_desc := @value[0]) !! $!sc_desc }
    method serialized(*@value) { @value ?? ($!serialized := @value[0]) !! $!serialized }
    method programs(*@value) { @value ?? (@!programs := @value[0]) !! @!programs }
    method callsites(*@value) { @value ?? (@!callsites := @value[0]) !! @!callsites }
    method blockvalues(*@value) { @value ?? (@!blockvalues := @value[0]) !! @!blockvalues }
    method nested_units(*@value) { @value ?? (@!nested_units := @value[0]) !! @!nested_units }
}

# One block of the unit: everything the loader needs to make its code ref
# (ProgramUnit.buildTable) -- name, cuid, outer, lexical names by type,
# handlers, flags, source position -- plus the index of its program.
class QAST::BlockRecord {
    has int $!qbid;
    has str $!name;
    has str $!cuid;              # '' on a jar-bound comp-mode block
    has int $!outer;             # qbid of the outer block; -1 = none
    has @!olex;
    has @!ilex;
    has @!nlex;
    has @!slex;
    has @!handlers;              # flat: [count, (len, fields...)*]
    has int $!has_exit_handler;
    has int $!is_thunk;
    has str $!file;
    has int $!line;
    has int $!rawline;
    has @!sections;              # [rawline, line, file] rows; empty today
    has int $!program;           # index into the unit's programs; -1 = none

    method BUILD(:$qbid!, :$name, :$cuid) {
        $!qbid := $qbid;
        $!name := $name // '';
        $!cuid := $cuid // '';
        $!outer := -1;
        @!olex := []; @!ilex := []; @!nlex := []; @!slex := [];
        @!handlers := [0];
        $!has_exit_handler := 0;
        $!is_thunk := 0;
        $!file := '';
        $!line := 0;
        $!rawline := 0;
        @!sections := [];
        $!program := -1;
    }

    method qbid() { $!qbid }
    method name() { $!name }
    method cuid() { $!cuid }
    method outer(*@value) { @value ?? ($!outer := @value[0]) !! $!outer }
    method olex(*@value) { @value ?? (@!olex := @value[0]) !! @!olex }
    method ilex(*@value) { @value ?? (@!ilex := @value[0]) !! @!ilex }
    method nlex(*@value) { @value ?? (@!nlex := @value[0]) !! @!nlex }
    method slex(*@value) { @value ?? (@!slex := @value[0]) !! @!slex }
    method handlers(*@value) { @value ?? (@!handlers := @value[0]) !! @!handlers }
    method has_exit_handler(*@value) { @value ?? ($!has_exit_handler := @value[0]) !! $!has_exit_handler }
    method is_thunk(*@value) { @value ?? ($!is_thunk := @value[0]) !! $!is_thunk }
    method file(*@value) { @value ?? ($!file := @value[0]) !! $!file }
    method line(*@value) { @value ?? ($!line := @value[0]) !! $!line }
    method rawline(*@value) { @value ?? ($!rawline := @value[0]) !! $!rawline }
    method sections() { @!sections }
    method program(*@value) { @value ?? ($!program := @value[0]) !! $!program }

    # A #line directive section: source at raw line $rawline reads as line
    # $line of $file. Only a change of mapping adds a row (ported from the
    # method carrier's cr_add_section; nothing feeds it on the engine road
    # today, kept so the reader's section fields have a source).
    method add_section($rawline, $line, $file) {
        my int $n := nqp::elems(@!sections);
        my $cur_file;
        my int $cur_delta;
        if $n {
            my @last := @!sections[$n - 1];
            return 0 if $rawline < @last[0];
            $cur_file  := @last[2];
            $cur_delta := @last[0] - @last[1];
        }
        else {
            $cur_file  := $!file;
            $cur_delta := $!rawline - $!line;
        }
        unless $file eq $cur_file && $rawline - $line == $cur_delta {
            nqp::push(@!sections, [$rawline, $line, $file]);
        }
        1
    }
}

# The JVM types the classlib registry's descriptors are built from.
my $TYPE_TC         := 'Lorg/raku/nqp/runtime/ThreadContext;';
my $TYPE_OPS        := 'Lorg/raku/nqp/runtime/Ops;';
my $TYPE_NATIVE_OPS := 'Lorg/raku/nqp/runtime/NativeCallOps;';
my $TYPE_IO_OPS     := 'Lorg/raku/nqp/runtime/IOOps;';
my $TYPE_MATH       := 'Ljava/lang/Math;';
my $TYPE_SMO        := 'Lorg/raku/nqp/sixmodel/SixModelObject;';
my $TYPE_STR        := 'Ljava/lang/String;';

# Exception handler kinds, as the block record's handler rows spell them.
my $EX_UNWIND_SIMPLE := 0;
my $EX_UNWIND_OBJECT := 1;
my $EX_BLOCK         := 2;

# Result types: the register kinds a value can have.
my $RT_OBJ  := 0;
my $RT_INT  := 1;
my $RT_NUM  := 2;
my $RT_STR  := 3;
my $RT_UINT := 10;
my $RT_VOID := -1;

# The classlib op registry, published for the Truffle encoder: every op a
# map_classlib_*_op call maps to a static method is recorded here as
# [class, method, JVM descriptor, arg RT types, result RT type, tc], so
# the encoder derives its table from the same declarations -- no
# hand-written twin per op.
my %CODE_CLASSLIB_OPS;
my %CODE_CLASSLIB_HLL_OPS;
nqp::bindhllsym('nqp', 'CODE_CLASSLIB_OPS', %CODE_CLASSLIB_OPS);
nqp::bindhllsym('nqp', 'CODE_CLASSLIB_HLL_OPS', %CODE_CLASSLIB_HLL_OPS);

my @jtypes := [$TYPE_SMO, 'Long', 'Double', $TYPE_STR, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, 'Long'];
my @rttypes := [$RT_OBJ, $RT_INT, $RT_NUM, $RT_STR, -1, -1, -1, -1, -1, -1, $RT_UINT];
my @typeobjs := [NQPMu, int, num, str, NQPMu, NQPMu, NQPMu, NQPMu, NQPMu, NQPMu, uint];
my @typechars := ['o', 'i', 'n', 's', '', '', '', '', '', '', 'u'];

sub jtype($type_idx) { @jtypes[$type_idx] }
sub rttype_from_typeobj($typeobj) { @rttypes[nqp::objprimspec($typeobj)] }
sub typeobj_from_rttype($rttype) { @typeobjs[$rttype] }
sub typechar($type_idx) { @typechars[$type_idx] }

sub jdesc($jt) { $jt eq 'Long' ?? 'J' !! $jt eq 'Double' ?? 'D' !! $jt eq 'Void' ?? 'V' !! $jt }
sub classlib_record($class, $method, @stack_in, $stack_out, $tc, $cont) {
    my str $desc := '(';
    for @stack_in { $desc := $desc ~ jdesc(jtype($_)) }
    $desc := $desc ~ jdesc($TYPE_TC) if $tc;
    $desc := $desc ~ ')' ~ ($cont ?? 'V' !! jdesc(jtype($stack_out)));
    [$class, $method, $desc, nqp::clone(@stack_in), $stack_out, $tc ?? 1 !! 0, $cont ?? 1 !! 0]
}

# The op registry: data, not code generation. The Truffle encoder is the
# only consumer of a QAST::Op, so what lives here is what it (and HLL code
# asking about an op) reads -- the classlib maps and the inlinability
# tables. Nothing in it emits anything.
class QAST::OperationsJVM {
    # What we know about inlinability.
    my %core_inlinability;
    my %hll_inlinability;

    # Sets op inlinability at a core level.
    method set_core_op_inlinability($op, $inlinable) {
        %core_inlinability{$op} := $inlinable;
    }

    # Sets op inlinability at a HLL level. (Can override at HLL level whether
    # or not the HLL overrides the op itself.)
    method set_hll_op_inlinability($hll, $op, $inlinable) {
        %hll_inlinability{$hll} := {} unless nqp::existskey(%hll_inlinability, $hll);
        %hll_inlinability{$hll}{$op} := $inlinable;
    }

    # Checks if an op is considered inlinable.
    method is_inlinable($hll, $op) {
        if nqp::existskey(%hll_inlinability, $hll) {
            if nqp::existskey(%hll_inlinability{$hll}, $op) {
                return %hll_inlinability{$hll}{$op};
            }
        }
        return %core_inlinability{$op} // 0;
    }

    # Records a core nqp:: op provided by a static method in the class
    # library, for the encoder to call through.
    method map_classlib_core_op($op, $class, $method, @stack_in, $stack_out, :$tc, :$cont, :$inlinable = 1) {
        self.set_core_op_inlinability($op, $inlinable);
        %CODE_CLASSLIB_OPS{$op} := classlib_record($class, $method, @stack_in, $stack_out, $tc, $cont);
    }

    # The same, for a HLL-specific op.
    method map_classlib_hll_op($hll, $op, $class, $method, @stack_in, $stack_out, :$tc, :$cont, :$inlinable = 1) {
        self.set_hll_op_inlinability($hll, $op, $inlinable);
        %CODE_CLASSLIB_HLL_OPS{$hll} := nqp::hash() unless nqp::existskey(%CODE_CLASSLIB_HLL_OPS, $hll);
        %CODE_CLASSLIB_HLL_OPS{$hll}{$op} := classlib_record($class, $method, @stack_in, $stack_out, $tc, $cont);
    }

    # HLL code asks through the backend's supports-op (NativeCall probes
    # 'dispatch_v'). An op is supported when the encoder has a row for it
    # or the classlib registry maps it.
    method core_op_supported($op) {
        nqp::existskey(%CODE_CLASSLIB_OPS, $op) || QAST::TruffleEncoder.supports_op($op)
    }
}

# Constant mapping.
my %const_map := nqp::hash(
    'CCLASS_ANY',           65535,
    'CCLASS_UPPERCASE',     1,
    'CCLASS_LOWERCASE',     2,
    'CCLASS_ALPHABETIC',    4,
    'CCLASS_NUMERIC',       8,
    'CCLASS_HEXADECIMAL',   16,
    'CCLASS_WHITESPACE',    32,
    'CCLASS_PRINTING',      64,
    'CCLASS_BLANK',         256,
    'CCLASS_CONTROL',       512,
    'CCLASS_PUNCTUATION',   1024,
    'CCLASS_ALPHANUMERIC',  2048,
    'CCLASS_NEWLINE',       4096,
    'CCLASS_WORD',          8192,

    'HLL_ROLE_NONE',        0,
    'HLL_ROLE_INT',         1,
    'HLL_ROLE_NUM',         2,
    'HLL_ROLE_STR',         3,
    'HLL_ROLE_ARRAY',       4,
    'HLL_ROLE_HASH',        5,
    'HLL_ROLE_CODE',        6,

    'CONTROL_ANY',          2,
    'CONTROL_NEXT',         4,
    'CONTROL_REDO',         8,
    'CONTROL_LAST',         16,
    'CONTROL_RETURN',       32,
    'CONTROL_TAKE',         128,
    'CONTROL_WARN',         256,
    'CONTROL_SUCCEED',      512,
    'CONTROL_PROCEED',      1024,
    'CONTROL_LABELED',      4096,
    'CONTROL_AWAIT',        8192,
    'CONTROL_EMIT',         16384,
    'CONTROL_DONE',         32768,

    'STAT_EXISTS',             0,
    'STAT_FILESIZE',           1,
    'STAT_ISDIR',              2,
    'STAT_ISREG',              3,
    'STAT_ISDEV',              4,
    'STAT_CREATETIME',         5,
    'STAT_ACCESSTIME',         6,
    'STAT_MODIFYTIME',         7,
    'STAT_CHANGETIME',         8,
    'STAT_BACKUPTIME',         9,
    'STAT_UID',                10,
    'STAT_GID',                11,
    'STAT_ISLNK',              12,
    'STAT_PLATFORM_DEV',       -1,
    'STAT_PLATFORM_INODE',     -2,
    'STAT_PLATFORM_MODE',      -3,
    'STAT_PLATFORM_NLINKS',    -4,
    'STAT_PLATFORM_DEVTYPE',   -5,
    'STAT_PLATFORM_BLOCKSIZE', -6,
    'STAT_PLATFORM_BLOCKS',    -7,

    'PIPE_INHERIT_IN',          1,
    'PIPE_IGNORE_IN',           2,
    'PIPE_CAPTURE_IN',          4,
    'PIPE_INHERIT_OUT',         8,
    'PIPE_IGNORE_OUT',          16,
    'PIPE_CAPTURE_OUT',         32,
    'PIPE_INHERIT_ERR',         64,
    'PIPE_IGNORE_ERR',          128,
    'PIPE_CAPTURE_ERR',         256,
    'PIPE_MERGED_OUT_ERR',      512,

    'TYPE_CHECK_CACHE_DEFINITIVE',  0,
    'TYPE_CHECK_CACHE_THEN_METHOD', 1,
    'TYPE_CHECK_NEEDS_ACCEPTS',     2,

    'C_TYPE_CHAR',              -1,
    'C_TYPE_SHORT',             -2,
    'C_TYPE_INT',               -3,
    'C_TYPE_LONG',              -4,
    'C_TYPE_LONGLONG',          -5,
    'C_TYPE_SIZE_T',            -6,
    'C_TYPE_BOOL',              -7,
    'C_TYPE_ATOMIC_INT',        -8,
    'C_TYPE_FLOAT',             -1,
    'C_TYPE_DOUBLE',            -2,
    'C_TYPE_LONGDOUBLE',        -3,

    'NORMALIZE_NONE',            0,
    'NORMALIZE_NFC',             1,
    'NORMALIZE_NFD',             2,
    'NORMALIZE_NFKC',            3,
    'NORMALIZE_NFKD',            4,

    'RUSAGE_UTIME_SEC',          0,
    'RUSAGE_UTIME_MSEC',         1,
    'RUSAGE_STIME_SEC',          2,
    'RUSAGE_STIME_MSEC',         3,
    'RUSAGE_MAXRSS',             4,
    'RUSAGE_IXRSS',              5,
    'RUSAGE_IDRSS',              6,
    'RUSAGE_ISRSS',              7,
    'RUSAGE_MINFLT',             8,
    'RUSAGE_MAJFLT',             9,
    'RUSAGE_NSWAP',              10,
    'RUSAGE_INBLOCK',            11,
    'RUSAGE_OUBLOCK',            12,
    'RUSAGE_MSGSND',             13,
    'RUSAGE_MSGRCV',             14,
    'RUSAGE_NSIGNALS',           15,
    'RUSAGE_NVCSW',              16,
    'RUSAGE_NIVCSW',             17,

    'BINARY_ENDIAN_NATIVE',       0,
    'BINARY_ENDIAN_LITTLE',       1,
    'BINARY_ENDIAN_BIG',          2,

    'BINARY_SIZE_8_BIT',          0,
    'BINARY_SIZE_16_BIT',         4,
    'BINARY_SIZE_32_BIT',         8,
    'BINARY_SIZE_64_BIT',        12,

    'SOCKET_FAMILY_UNSPEC',       0,
    'SOCKET_FAMILY_INET',         1,
    'SOCKET_FAMILY_INET6',        2,
    'SOCKET_FAMILY_UNIX',         3,

    'DISP_NONE',                  0,
    'DISP_CALLSAME',              1,
    'DISP_CALLWITH',              2,
    'DISP_LASTCALL',              3,
    'DISP_NEXTCALLEE',            4,
    'DISP_ONLYSTAR',              5,
    'DISP_DECONT',                6,
    'DISP_BIND_SUCCESS',          7,
    'DISP_BIND_FAILURE',          8,
    'DISP_PROPAGATE_CALLWITH',    9,

    'SIG_ELEM_BIND_CAPTURE',        1,
    'SIG_ELEM_BIND_PRIVATE_ATTR',   2,
    'SIG_ELEM_BIND_PUBLIC_ATTR',    4,
    # BIND_PRIVATE_ATTR + BIND_PUBLIC_ATTR
    'SIG_ELEM_BIND_ATTRIBUTIVE',    6,
    'SIG_ELEM_SLURPY_POS',          8,
    'SIG_ELEM_SLURPY_NAMED',        16,
    'SIG_ELEM_SLURPY_LOL',          32,
    'SIG_ELEM_INVOCANT',            64,
    'SIG_ELEM_MULTI_INVOCANT',      128,
    'SIG_ELEM_IS_RW',               256,
    'SIG_ELEM_IS_COPY',             512,
    'SIG_ELEM_IS_RAW',              1024,
    # IS_RW + IS_COPY + IS_RAW
    'SIG_ELEM_IS_NOT_READONLY',     1792,
    'SIG_ELEM_IS_OPTIONAL',         2048,
    'SIG_ELEM_ARRAY_SIGIL',         4096,
    'SIG_ELEM_HASH_SIGIL',          8192,
    'SIG_ELEM_DEFAULT_FROM_OUTER',  16384,
    'SIG_ELEM_IS_CAPTURE',          32768,
    # SLURPY_NAMED + IS_CAPTURE
    'SIG_ELEM_ALL_NAMES_OK',        32784,
    'SIG_ELEM_UNDEFINED_ONLY',      65536,
    'SIG_ELEM_DEFINED_ONLY',        131072,
    # UNDEFINED_ONLY + DEFINED_ONLY
    'SIG_ELEM_DEFINEDNES_CHECK',    196608,
    'SIG_ELEM_TYPE_GENERIC',        524288,
    'SIG_ELEM_DEFAULT_IS_LITERAL',  1048576,
    'SIG_ELEM_NATIVE_INT_VALUE',    2097152,
    'SIG_ELEM_NATIVE_UINT_VALUE',   134217728,
    'SIG_ELEM_NATIVE_NUM_VALUE',    4194304,
    'SIG_ELEM_NATIVE_STR_VALUE',    8388608,
    # NATIVE_UINT_VALUE + NATIVE_INT_VALUE + NATIVE_NUM_VALUE + NATIVE_STR_VALUE
    'SIG_ELEM_NATIVE_VALUE',        148897792,
    'SIG_ELEM_SLURPY_ONEARG',       16777216,
    # SLURPY_POS + SLURPY_NAMED + SLURPY_LOL + SLURPY_ONEARG
    'SIG_ELEM_IS_SLURPY',           16777272,
    # SLURPY_POS + SLURPY_LOL + SLURPY_ONEARG + IS_CAPTURE
    'SIG_ELEM_SLURPY_ARITY',        16810024,
    # SLURPY_POS + SLURPY_NAMED + SLURPY_LOL + SLURPY_ONEARG + IS_CAPTURE
    'SIG_ELEM_IS_NOT_POSITIONAL',   16810040,
    'SIG_ELEM_CODE_SIGIL',          33554432,
    'SIG_ELEM_IS_COERCIVE',         67108864,
    'SIG_ELEM_IS_ITEM',             268435456,
    'SIG_ELEM_IS_EXACT_TYPE',       536870912,

    'EDGE_FATE',               0,
    'EDGE_EPSILON',            1,
    'EDGE_CODEPOINT',          2,
    'EDGE_CODEPOINT_NEG',      3,
    'EDGE_CHARCLASS',          4,
    'EDGE_CHARCLASS_NEG',      5,
    'EDGE_CHARLIST',           6,
    'EDGE_CHARLIST_NEG',       7,
    'EDGE_SUBRULE',            8,
    'EDGE_CODEPOINT_I',        9,
    'EDGE_CODEPOINT_I_NEG',   10,
    'EDGE_GENERIC_VAR',       11,
    'EDGE_CHARRANGE',         12,
    'EDGE_CHARRANGE_NEG',     13,
    'EDGE_CODEPOINT_LL',      14,
    'EDGE_CODEPOINT_I_LL',    15,
    'EDGE_CODEPOINT_M',       16,
    'EDGE_CODEPOINT_M_NEG',   17,
    'EDGE_CODEPOINT_M_LL',    18,
    'EDGE_CODEPOINT_IM',      19,
    'EDGE_CODEPOINT_IM_NEG',  20,
    'EDGE_CODEPOINT_IM_LL',   21,
    'EDGE_CHARRANGE_M',       22,
    'EDGE_CHARRANGE_M_NEG',   23,

);
nqp::bindkey(%const_map, 'DEFCON_DEFINED',   1);
nqp::bindkey(%const_map, 'DEFCON_UNDEFINED', 2);
# DEFINED + UNDEFINED
nqp::bindkey(%const_map, 'DEFCON_MASK',      3);

nqp::bindkey(%const_map, 'TYPE_NATIVE_INT',   4);
nqp::bindkey(%const_map, 'TYPE_NATIVE_NUM',   8);
nqp::bindkey(%const_map, 'TYPE_NATIVE_STR',  16);
nqp::bindkey(%const_map, 'TYPE_NATIVE_UINT', 32);
# INT + NUM + STR + UINT
nqp::bindkey(%const_map, 'TYPE_NATIVE_MASK', 60);

nqp::bindkey(%const_map, 'BIND_RESULT_OK',       0);
nqp::bindkey(%const_map, 'BIND_RESULT_FAIL',     1);
nqp::bindkey(%const_map, 'BIND_RESULT_JUNCTION', 2);

nqp::bindkey(%const_map, 'BIND_VAL_OBJ',   0);
nqp::bindkey(%const_map, 'BIND_VAL_INT',   1);
nqp::bindkey(%const_map, 'BIND_VAL_NUM',   2);
nqp::bindkey(%const_map, 'BIND_VAL_STR',   3);
nqp::bindkey(%const_map, 'BIND_VAL_UINT', 10);

# Published for the code engine's encoder, which lives in its own file
# and resolves nqp::const names to the same values (QAST::TruffleEncoder).
nqp::bindhllsym('nqp', 'CODE_CONST_MAP', %const_map);

# Exception handling/munging.
QAST::OperationsJVM.map_classlib_core_op('die_s', $TYPE_OPS, 'die_s_c', [$RT_STR], $RT_STR, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('die', $TYPE_OPS, 'die_s_c', [$RT_STR], $RT_STR, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('exception', $TYPE_OPS, 'exception', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getextype', $TYPE_OPS, 'getextype', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('setextype', $TYPE_OPS, 'setextype', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getpayload', $TYPE_OPS, 'getpayload', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setpayload', $TYPE_OPS, 'setpayload', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getmessage', $TYPE_OPS, 'getmessage', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('setmessage', $TYPE_OPS, 'setmessage', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('newexception', $TYPE_OPS, 'newexception', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('backtrace', $TYPE_OPS, 'backtrace', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('backtracestrings', $TYPE_OPS, 'backtracestrings', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('throw', $TYPE_OPS, '_throw_c', [$RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('rethrow', $TYPE_OPS, 'rethrow_c', [$RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('resume', $TYPE_OPS, 'resume', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('throwpayloadlex', $TYPE_OPS, '_throwpayloadlex_c', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc, :cont, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('throwpayloadlexcaller', $TYPE_OPS, '_throwpayloadlexcaller_c', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc, :cont, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('lastexpayload', $TYPE_OPS, 'lastexpayload', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('throwextype', $TYPE_OPS, 'throwcatdyn_c', [$RT_INT], $RT_OBJ, :tc, :cont);

# Context introspection; note that lexpads and contents are actually the same object
# in the JVM port, which allows a little op re-use.
QAST::OperationsJVM.map_classlib_core_op('ctx', $TYPE_OPS, 'ctx', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxouter', $TYPE_OPS, 'ctxouter', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxcaller', $TYPE_OPS, 'ctxcaller', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxcode', $TYPE_OPS, 'ctxcode', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxouterskipthunks', $TYPE_OPS, 'ctxouterskipthunks', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxcallerskipthunks', $TYPE_OPS, 'ctxcallerskipthunks', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('curcode', $TYPE_OPS, 'curcode', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('callercode', $TYPE_OPS, 'callercode', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('ctxlexpad', $TYPE_OPS, 'ctxlexpad', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('curlexpad', $TYPE_OPS, 'ctx', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('lexprimspec', $TYPE_OPS, 'lexprimspec', [$RT_OBJ, $RT_STR], $RT_INT, :tc, :!inlinable);
QAST::OperationsJVM.map_classlib_core_op('captureposelems', $TYPE_OPS, 'captureposelems', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposarg', $TYPE_OPS, 'captureposarg', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposarg_i', $TYPE_OPS, 'captureposarg_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposarg_u', $TYPE_OPS, 'captureposarg_u', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposarg_n', $TYPE_OPS, 'captureposarg_n', [$RT_OBJ, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposarg_s', $TYPE_OPS, 'captureposarg_s', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureposprimspec', $TYPE_OPS, 'captureposprimspec', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureexistsnamed', $TYPE_OPS, 'captureexistsnamed', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('capturehasnameds', $TYPE_OPS, 'capturehasnameds', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('capturenamedshash', $TYPE_OPS, 'capturenamedshash', [$RT_OBJ], $RT_OBJ, :tc);

# Multiple dispatch related.
QAST::OperationsJVM.map_classlib_core_op('invokewithcapture', $TYPE_OPS, 'invokewithcapture', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multicacheadd', $TYPE_OPS, 'multicacheadd', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multicachefind', $TYPE_OPS, 'multicachefind', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# Default way to do positional and associative lookups.
QAST::OperationsJVM.map_classlib_core_op('positional_get', $TYPE_OPS, 'atpos', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('positional_bind', $TYPE_OPS, 'bindpos', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('associative_get', $TYPE_OPS, 'atkey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('associative_bind', $TYPE_OPS, 'bindkey', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);

# I/O opcodes
QAST::OperationsJVM.map_classlib_core_op('print', $TYPE_OPS, 'print', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('say', $TYPE_OPS, 'say', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('note', $TYPE_OPS, 'note', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('printfh', $TYPE_OPS, 'printfh', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('sayfh', $TYPE_OPS, 'sayfh', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('stat', $TYPE_OPS, 'stat', [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('lstat', $TYPE_OPS, 'lstat', [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('stat_time', $TYPE_OPS, 'stat_time', [$RT_STR, $RT_INT], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('lstat_time', $TYPE_OPS, 'lstat_time', [$RT_STR, $RT_INT], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('open', $TYPE_OPS, 'open', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('openasync', $TYPE_OPS, 'openasync', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('spurtasync', $TYPE_OPS, 'spurtasync', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('slurpasync', $TYPE_OPS, 'slurpasync', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('linesasync', $TYPE_OPS, 'linesasync', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('readlink', $TYPE_OPS, 'readlink', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('filereadable', $TYPE_OPS, 'filereadable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('filewritable', $TYPE_OPS, 'filewritable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('fileexecutable', $TYPE_OPS, 'fileexecutable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('fileislink', $TYPE_OPS, 'fileislink', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getstdin', $TYPE_OPS, 'getstdin', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getstdout', $TYPE_OPS, 'getstdout', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getstderr', $TYPE_OPS, 'getstderr', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('seekfh', $TYPE_OPS, 'seekfh', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('tellfh', $TYPE_OPS, 'tellfh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('lockfh', $TYPE_OPS, 'lockfh', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('unlockfh', $TYPE_OPS, 'unlockfh', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('readfh', $TYPE_OPS, 'readfh', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('writefh', $TYPE_OPS, 'writefh', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('flushfh', $TYPE_OPS, 'flushfh', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('eoffh', $TYPE_OPS, 'eoffh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('closefh', $TYPE_OPS, 'closefh', [$RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('isttyfh', $TYPE_OPS, 'isttyfh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('filenofh', $TYPE_OPS, 'filenofh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('setbuffersizefh', $TYPE_OPS, 'setbuffersizefh', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('chmod', $TYPE_OPS, 'chmod', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('chown', $TYPE_OPS, 'chown', [$RT_STR, $RT_UINT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('unlink', $TYPE_OPS, 'unlink', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('rmdir', $TYPE_OPS, 'rmdir', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('cwd', $TYPE_OPS, 'cwd', [], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('chdir', $TYPE_OPS, 'chdir', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('mkdir', $TYPE_OPS, 'mkdir', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('rename', $TYPE_OPS, 'rename', [$RT_STR, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('copy', $TYPE_OPS, 'copy', [$RT_STR, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('link', $TYPE_OPS, 'link', [$RT_STR, $RT_STR], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('gethostname', $TYPE_OPS, 'gethostname', [], $RT_STR);

QAST::OperationsJVM.map_classlib_core_op('symlink', $TYPE_OPS, 'symlink', [$RT_STR, $RT_STR], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('opendir', $TYPE_OPS, 'opendir', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nextfiledir', $TYPE_OPS, 'nextfiledir', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('closedir', $TYPE_OPS, 'closedir', [$RT_OBJ], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('socket', $TYPE_OPS, 'socket', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('connect', $TYPE_OPS, 'connect', [$RT_OBJ, $RT_STR, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindsock', $TYPE_OPS, 'bindsock', [$RT_OBJ, $RT_STR, $RT_INT, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('accept', $TYPE_OPS, 'accept', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getport', $TYPE_OPS, 'getport', [$RT_OBJ], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('debugnoop', $TYPE_OPS, 'debugnoop', [$RT_OBJ], $RT_OBJ, :tc);

# terms
QAST::OperationsJVM.map_classlib_core_op('time', $TYPE_OPS, 'time', [], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('add_I', $TYPE_OPS, 'add_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('sub_I', $TYPE_OPS, 'sub_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('mul_I', $TYPE_OPS, 'mul_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('div_i', $TYPE_OPS, 'div_i', [$RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('div_I', $TYPE_OPS, 'div_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('div_In', $TYPE_OPS, 'div_In', [$RT_OBJ, $RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('mod_I', $TYPE_OPS, 'mod_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('expmod_I', $TYPE_OPS, 'expmod_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('isprime_I', $TYPE_OPS, 'isprime_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('rand_n', $TYPE_OPS, 'rand_n', [$RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('srand', $TYPE_OPS, 'srand', [$RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('rand_I', $TYPE_OPS, 'rand_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('mod_n', $TYPE_OPS, 'mod_n', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('pow_i', $TYPE_OPS, 'pow_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('pow_n', $TYPE_OPS, 'pow_n', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('pow_I', $TYPE_OPS, 'pow_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('neg_I', $TYPE_OPS, 'neg_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('abs_i', $TYPE_MATH, 'abs', [$RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('abs_I', $TYPE_OPS, 'abs_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('abs_n', $TYPE_MATH, 'abs', [$RT_NUM], $RT_NUM);

QAST::OperationsJVM.map_classlib_core_op('ceil_n', $TYPE_MATH, 'ceil', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('floor_n', $TYPE_MATH, 'floor', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('sqrt_n', $TYPE_MATH, 'sqrt', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('log_n', $TYPE_MATH, 'log', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('exp_n', $TYPE_MATH, 'exp', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('isnanorinf', $TYPE_OPS, 'isnanorinf', [$RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('inf', $TYPE_OPS, 'inf', [], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('neginf', $TYPE_OPS, 'neginf', [], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('nan', $TYPE_OPS, 'nan', [], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('radix', $TYPE_OPS, 'radix', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('radix_I', $TYPE_OPS, 'radix_I', [$RT_INT, $RT_STR, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

# trig opcodes
QAST::OperationsJVM.map_classlib_core_op('sin_n', $TYPE_MATH, 'sin', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('asin_n', $TYPE_MATH, 'asin', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('cos_n', $TYPE_MATH, 'cos', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('acos_n', $TYPE_MATH, 'acos', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('tan_n', $TYPE_MATH, 'tan', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('atan_n', $TYPE_MATH, 'atan', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('atan2_n', $TYPE_MATH, 'atan2', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('sinh_n', $TYPE_MATH, 'sinh', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('cosh_n', $TYPE_MATH, 'cosh', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('tanh_n', $TYPE_MATH, 'tanh', [$RT_NUM], $RT_NUM);

# esoteric math opcodes
QAST::OperationsJVM.map_classlib_core_op('gcd_i', $TYPE_OPS, 'gcd_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('gcd_I', $TYPE_OPS, 'gcd_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('lcm_i', $TYPE_OPS, 'lcm_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('lcm_I', $TYPE_OPS, 'lcm_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# string bitwise ops
QAST::OperationsJVM.map_classlib_core_op('bitor_s', $TYPE_OPS, 'bitor_s', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('bitxor_s', $TYPE_OPS, 'bitxor_s', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('bitand_s', $TYPE_OPS, 'bitand_s', [$RT_STR, $RT_STR], $RT_STR);

# string opcodes
QAST::OperationsJVM.map_classlib_core_op('chars', $TYPE_OPS, 'chars', [$RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('uc', $TYPE_OPS, 'uc', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('lc', $TYPE_OPS, 'lc', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('tc', $TYPE_OPS, 'tc', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('tclc', $TYPE_OPS, 'tclc', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('fc', $TYPE_OPS, 'lc', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('x', $TYPE_OPS, 'x', [$RT_STR, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('iscclass', $TYPE_OPS, 'iscclass', [$RT_INT, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('concat', $TYPE_OPS, 'concat', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('chr', $TYPE_OPS, 'chr', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('join', $TYPE_OPS, 'join', [$RT_STR, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('split', $TYPE_OPS, 'split', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('findcclass', $TYPE_OPS, 'findcclass', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('findnotcclass', $TYPE_OPS, 'findnotcclass', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('escape', $TYPE_OPS, 'escape', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('flip', $TYPE_OPS, 'flip', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('replace', $TYPE_OPS, 'replace', [$RT_STR, $RT_INT, $RT_INT, $RT_STR], $RT_STR);

# substr can take 2 or 3 args, so needs special handling.
QAST::OperationsJVM.map_classlib_core_op('substr2', $TYPE_OPS, 'substr2', [$RT_STR, $RT_INT], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('substr3', $TYPE_OPS, 'substr3', [$RT_STR, $RT_INT, $RT_INT], $RT_STR);

QAST::OperationsJVM.map_classlib_core_op('eqat', $TYPE_OPS, 'eqat', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('eqatic', $TYPE_OPS, 'eqatic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('eqatim', $TYPE_OPS, 'eqatim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('eqaticim', $TYPE_OPS, 'eqaticim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('eqatic', $TYPE_OPS, 'eqatic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
# ord can be on a the first char in a string or at a particular char.
QAST::OperationsJVM.map_classlib_core_op('ordfirst', $TYPE_OPS, 'ordfirst', [$RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('ordat',    $TYPE_OPS, 'ordat',    [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('ordbaseat', $TYPE_OPS, 'ordbaseat', [$RT_STR, $RT_INT], $RT_INT);

# index may or may not take a starting position
QAST::OperationsJVM.map_classlib_core_op('indexfrom', $TYPE_OPS, 'indexfrom', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('indexic', $TYPE_OPS, 'indexic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('indexim', $TYPE_OPS, 'indexim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('indexicim', $TYPE_OPS, 'indexicim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);

# rindex may or may not take a starting position
QAST::OperationsJVM.map_classlib_core_op('rindexfromend', $TYPE_OPS, 'rindexfromend', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('rindexfrom', $TYPE_OPS, 'rindexfrom', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('strfromcodes', $TYPE_OPS, 'strfromcodes', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('strtocodes', $TYPE_OPS, 'strtocodes', [$RT_STR, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('normalizecodes', $TYPE_OPS, 'normalizecodes', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('codes', $TYPE_OPS, 'codes', [$RT_STR], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('codepointfromname', $TYPE_OPS, 'codepointfromname', [$RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('strfromname', $TYPE_OPS, 'strfromname', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('encode', $TYPE_OPS, 'encode', [$RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('encoderep', $TYPE_OPS, 'encoderep', [$RT_STR, $RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decode', $TYPE_OPS, 'decode', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decoderconfigure', $TYPE_OPS, 'decoderconfigure', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodersetlineseps', $TYPE_OPS, 'decodersetlineseps', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decoderaddbytes', $TYPE_OPS, 'decoderaddbytes', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakechars', $TYPE_OPS, 'decodertakechars', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakecharseof', $TYPE_OPS, 'decodertakecharseof', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakeallchars', $TYPE_OPS, 'decodertakeallchars', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakeavailablechars', $TYPE_OPS, 'decodertakeavailablechars', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakeline', $TYPE_OPS, 'decodertakeline', [$RT_OBJ, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('decoderbytesavailable', $TYPE_OPS, 'decoderbytesavailable', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('decodertakebytes', $TYPE_OPS, 'decodertakebytes', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decoderempty', $TYPE_OPS, 'decoderempty', [$RT_OBJ], $RT_INT, :tc);

# serialization context opcodes
QAST::OperationsJVM.map_classlib_core_op('sha1', $TYPE_OPS, 'sha1', [$RT_STR], $RT_STR);
QAST::OperationsJVM.map_classlib_core_op('createsc', $TYPE_OPS, 'createsc', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scsetobj', $TYPE_OPS, 'scsetobj', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scsetcode', $TYPE_OPS, 'scsetcode', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scgetobj', $TYPE_OPS, 'scgetobj', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scgethandle', $TYPE_OPS, 'scgethandle', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('scgetdesc', $TYPE_OPS, 'scgetdesc', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('scgetobjidx', $TYPE_OPS, 'scgetobjidx', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('scsetdesc', $TYPE_OPS, 'scsetdesc', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('scobjcount', $TYPE_OPS, 'scobjcount', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('setobjsc', $TYPE_OPS, 'setobjsc', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getobjsc', $TYPE_OPS, 'getobjsc', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('serialize', $TYPE_OPS, 'serialize', [$RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('serializetobuf', $TYPE_OPS, 'serializetobuf', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('deserialize', $TYPE_OPS, 'deserialize', [$RT_STR, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('wval', $TYPE_OPS, 'wval', [$RT_STR, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scwbdisable', $TYPE_OPS, 'scwbdisable', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('scwbenable', $TYPE_OPS, 'scwbenable', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('pushcompsc', $TYPE_OPS, 'pushcompsc', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('popcompsc', $TYPE_OPS, 'popcompsc', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('neverrepossess', $TYPE_OPS, 'neverrepossess', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('scdisclaim', $TYPE_OPS, 'scdisclaim', [$RT_OBJ], $RT_OBJ, :tc);

# bitwise opcodes
QAST::OperationsJVM.map_classlib_core_op('bitor_i', $TYPE_OPS, 'bitor_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitor_I', $TYPE_OPS, 'bitor_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bitxor_i', $TYPE_OPS, 'bitxor_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitxor_I', $TYPE_OPS, 'bitxor_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bitand_i', $TYPE_OPS, 'bitand_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitand_I', $TYPE_OPS, 'bitand_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bitneg_i', $TYPE_OPS, 'bitneg_i', [$RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitneg_u', $TYPE_OPS, 'bitneg_i', [$RT_UINT], $RT_UINT);
QAST::OperationsJVM.map_classlib_core_op('bitneg_I', $TYPE_OPS, 'bitneg_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bitshiftl_i', $TYPE_OPS, 'bitshiftl_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitshiftl_I', $TYPE_OPS, 'bitshiftl_I', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bitshiftr_i', $TYPE_OPS, 'bitshiftr_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('bitshiftr_I', $TYPE_OPS, 'bitshiftr_I', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

# relational opcodes
QAST::OperationsJVM.map_classlib_core_op('cmp_i',  $TYPE_OPS, 'cmp_i',  [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iseq_i', $TYPE_OPS, 'iseq_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isne_i', $TYPE_OPS, 'isne_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('islt_i', $TYPE_OPS, 'islt_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isle_i', $TYPE_OPS, 'isle_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isgt_i', $TYPE_OPS, 'isgt_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isge_i', $TYPE_OPS, 'isge_i', [$RT_INT, $RT_INT], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('cmp_u',  $TYPE_OPS, 'cmp_u',  [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iseq_u', $TYPE_OPS, 'iseq_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isne_u', $TYPE_OPS, 'isne_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('islt_u', $TYPE_OPS, 'islt_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isle_u', $TYPE_OPS, 'isle_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isgt_u', $TYPE_OPS, 'isgt_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isge_u', $TYPE_OPS, 'isge_u', [$RT_UINT, $RT_UINT], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('bool_I', $TYPE_OPS, 'bool_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('cmp_I', $TYPE_OPS, 'cmp_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('iseq_I', $TYPE_OPS, 'iseq_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isne_I', $TYPE_OPS, 'isne_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('islt_I', $TYPE_OPS, 'islt_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isle_I', $TYPE_OPS, 'isle_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isgt_I', $TYPE_OPS, 'isgt_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isge_I', $TYPE_OPS, 'isge_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('cmp_n',  $TYPE_OPS, 'cmp_n',  [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iseq_n', $TYPE_OPS, 'iseq_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isne_n', $TYPE_OPS, 'isne_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('islt_n', $TYPE_OPS, 'islt_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isle_n', $TYPE_OPS, 'isle_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isgt_n', $TYPE_OPS, 'isgt_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isge_n', $TYPE_OPS, 'isge_n', [$RT_NUM, $RT_NUM], $RT_INT);

QAST::OperationsJVM.map_classlib_core_op('cmp_s',  $TYPE_OPS, 'cmp_s',  [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iseq_s', $TYPE_OPS, 'iseq_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isne_s', $TYPE_OPS, 'isne_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('islt_s', $TYPE_OPS, 'islt_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isle_s', $TYPE_OPS, 'isle_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isgt_s', $TYPE_OPS, 'isgt_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isge_s', $TYPE_OPS, 'isge_s', [$RT_STR, $RT_STR], $RT_INT);

# bigint ops
QAST::OperationsJVM.map_classlib_core_op('fromstr_I', $TYPE_OPS, 'fromstr_I', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('tostr_I', $TYPE_OPS, 'tostr_I', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('base_I', $TYPE_OPS, 'base_I', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('isbig_I', $TYPE_OPS, 'isbig_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('fromnum_I', $TYPE_OPS, 'fromnum_I', [$RT_NUM, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('tonum_I', $TYPE_OPS, 'tonum_I', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('fromI_I', $TYPE_OPS, 'fromI_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# boolean opcodes
QAST::OperationsJVM.map_classlib_core_op('not_i', $TYPE_OPS, 'not_i', [$RT_INT], $RT_INT);

# aggregate opcodes
QAST::OperationsJVM.map_classlib_core_op('atpos', $TYPE_OPS, 'atpos', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos_i', $TYPE_OPS, 'atpos_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos_u', $TYPE_OPS, 'atpos_u', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos_n', $TYPE_OPS, 'atpos_n', [$RT_OBJ, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos_s', $TYPE_OPS, 'atpos_s', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposref_i', $TYPE_OPS, 'atposref_i', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposref_u', $TYPE_OPS, 'atposref_u', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposref_n', $TYPE_OPS, 'atposref_n', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposref_s', $TYPE_OPS, 'atposref_s', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos2d', $TYPE_OPS, 'atpos2d_o', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos2d_i', $TYPE_OPS, 'atpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos2d_u', $TYPE_OPS, 'atpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos2d_n', $TYPE_OPS, 'atpos2d_n', [$RT_OBJ, $RT_INT, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos2d_s', $TYPE_OPS, 'atpos2d_s', [$RT_OBJ, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos3d', $TYPE_OPS, 'atpos3d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos3d_i', $TYPE_OPS, 'atpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos3d_u', $TYPE_OPS, 'atpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos3d_n', $TYPE_OPS, 'atpos3d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('atpos3d_s', $TYPE_OPS, 'atpos3d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposnd', $TYPE_OPS, 'atposnd_o', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposnd_i', $TYPE_OPS, 'atposnd_i', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposnd_u', $TYPE_OPS, 'atposnd_i', [$RT_OBJ, $RT_OBJ], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposnd_n', $TYPE_OPS, 'atposnd_n', [$RT_OBJ, $RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('atposnd_s', $TYPE_OPS, 'atposnd_s', [$RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('atkey', $TYPE_OPS, 'atkey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atkey_i', $TYPE_OPS, 'atkey_i', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atkey_n', $TYPE_OPS, 'atkey_n', [$RT_OBJ, $RT_STR], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('atkey_s', $TYPE_OPS, 'atkey_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos', $TYPE_OPS, 'bindpos', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos_i', $TYPE_OPS, 'bindpos_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos_u', $TYPE_OPS, 'bindpos_u', [$RT_OBJ, $RT_INT, $RT_UINT], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos_n', $TYPE_OPS, 'bindpos_n', [$RT_OBJ, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos_s', $TYPE_OPS, 'bindpos_s', [$RT_OBJ, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos2d', $TYPE_OPS, 'bindpos2d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos2d_i', $TYPE_OPS, 'bindpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos2d_u', $TYPE_OPS, 'bindpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos2d_n', $TYPE_OPS, 'bindpos2d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos2d_s', $TYPE_OPS, 'bindpos2d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos3d', $TYPE_OPS, 'bindpos3d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos3d_i', $TYPE_OPS, 'bindpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos3d_u', $TYPE_OPS, 'bindpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos3d_n', $TYPE_OPS, 'bindpos3d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindpos3d_s', $TYPE_OPS, 'bindpos3d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindposnd', $TYPE_OPS, 'bindposnd_o', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multidimref_i', $TYPE_OPS, 'multidimref_i', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multidimref_u', $TYPE_OPS, 'multidimref_u', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multidimref_n', $TYPE_OPS, 'multidimref_n', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('multidimref_s', $TYPE_OPS, 'multidimref_s', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindposnd_i', $TYPE_OPS, 'bindposnd_i', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindposnd_u', $TYPE_OPS, 'bindposnd_i', [$RT_OBJ, $RT_OBJ, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindposnd_n', $TYPE_OPS, 'bindposnd_n', [$RT_OBJ, $RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindposnd_s', $TYPE_OPS, 'bindposnd_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindkey', $TYPE_OPS, 'bindkey', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindkey_i', $TYPE_OPS, 'bindkey_i', [$RT_OBJ, $RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindkey_n', $TYPE_OPS, 'bindkey_n', [$RT_OBJ, $RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindkey_s', $TYPE_OPS, 'bindkey_s', [$RT_OBJ, $RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('existspos', $TYPE_OPS, 'existspos', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('existskey', $TYPE_OPS, 'existskey', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('deletekey', $TYPE_OPS, 'deletekey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('elems', $TYPE_OPS, 'elems', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('setelems', $TYPE_OPS, 'setelems', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('dimensions', $TYPE_OPS, 'dimensions', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setdimensions', $TYPE_OPS, 'setdimensions', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('numdimensions', $TYPE_OPS, 'numdimensions', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('push', $TYPE_OPS, 'push', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('push_i', $TYPE_OPS, 'push_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('push_n', $TYPE_OPS, 'push_n', [$RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('push_s', $TYPE_OPS, 'push_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('pop', $TYPE_OPS, 'pop', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('pop_i', $TYPE_OPS, 'pop_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('pop_n', $TYPE_OPS, 'pop_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('pop_s', $TYPE_OPS, 'pop_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('unshift', $TYPE_OPS, 'unshift', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('unshift_i', $TYPE_OPS, 'unshift_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('unshift_n', $TYPE_OPS, 'unshift_n', [$RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('unshift_s', $TYPE_OPS, 'unshift_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('shift', $TYPE_OPS, 'shift', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('shift_i', $TYPE_OPS, 'shift_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('shift_n', $TYPE_OPS, 'shift_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('shift_s', $TYPE_OPS, 'shift_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('slice', $TYPE_OPS, 'slice', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('splice', $TYPE_OPS, 'splice', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('isint', $TYPE_OPS, 'isint', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isnum', $TYPE_OPS, 'isnum', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isstr', $TYPE_OPS, 'isstr', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('islist', $TYPE_OPS, 'islist', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('ishash', $TYPE_OPS, 'ishash', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('iterator', $TYPE_OPS, 'iter', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('iterkey_s', $TYPE_OPS, 'iterkey_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('iterval', $TYPE_OPS, 'iterval', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('what', $TYPE_OPS, 'what', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('how', $TYPE_OPS, 'how', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('who', $TYPE_OPS, 'who', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('where', $TYPE_OPS, 'where', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('objectid', $TYPE_OPS, 'where', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('findmethod', $TYPE_OPS, 'findmethod', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('tryfindmethod', $TYPE_OPS, 'findmethodNonFatal', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setwho', $TYPE_OPS, 'setwho', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('rebless', $TYPE_OPS, 'rebless', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('knowhow', $TYPE_OPS, 'knowhow', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('knowhowattr', $TYPE_OPS, 'knowhowattr', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootint', $TYPE_OPS, 'bootint', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootnum', $TYPE_OPS, 'bootnum', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootstr', $TYPE_OPS, 'bootstr', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootarray', $TYPE_OPS, 'bootarray', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootintarray', $TYPE_OPS, 'bootintarray', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootnumarray', $TYPE_OPS, 'bootnumarray', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bootstrarray', $TYPE_OPS, 'bootstrarray', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('boothash', $TYPE_OPS, 'boothash', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hlllist', $TYPE_OPS, 'hlllist', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllhash', $TYPE_OPS, 'hllhash', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('create', $TYPE_OPS, 'create', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('clone', $TYPE_OPS, 'clone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('isconcrete', $TYPE_OPS, 'isconcrete', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('null', $TYPE_OPS, 'createNull', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('isnull', $TYPE_OPS, 'isnull', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isnull_s', $TYPE_OPS, 'isnull_s', [$RT_STR], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('istrue', $TYPE_OPS, 'istrue', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isfalse', $TYPE_OPS, 'isfalse', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('istype', $TYPE_OPS, 'istype', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('eqaddr', $TYPE_OPS, 'eqaddr', [$RT_OBJ, $RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('getattr', $TYPE_OPS, 'getattr', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattr_i', $TYPE_OPS, 'getattr_i', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattr_u', $TYPE_OPS, 'getattr_u', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattr_n', $TYPE_OPS, 'getattr_n', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattr_s', $TYPE_OPS, 'getattr_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattrref_i', $TYPE_OPS, 'getattrref_i', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattrref_u', $TYPE_OPS, 'getattrref_u', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattrref_n', $TYPE_OPS, 'getattrref_n', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getattrref_s', $TYPE_OPS, 'getattrref_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindattr', $TYPE_OPS, 'bindattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindattr_i', $TYPE_OPS, 'bindattr_i', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindattr_u', $TYPE_OPS, 'bindattr_u', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_UINT], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindattr_n', $TYPE_OPS, 'bindattr_n', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindattr_s', $TYPE_OPS, 'bindattr_s', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('attrinited', $TYPE_OPS, 'attrinited', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('attrhintfor', $TYPE_OPS, 'attrhintfor', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('unbox_i', $TYPE_OPS, 'unbox_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('unbox_u', $TYPE_OPS, 'unbox_u', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('unbox_n', $TYPE_OPS, 'unbox_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('unbox_s', $TYPE_OPS, 'unbox_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('box_i', $TYPE_OPS, 'box_i', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('box_u', $TYPE_OPS, 'box_u', [$RT_UINT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('box_n', $TYPE_OPS, 'box_n', [$RT_NUM, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('box_s', $TYPE_OPS, 'box_s', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllboxtype_i', $TYPE_OPS, 'hllboxtype_i', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllboxtype_n', $TYPE_OPS, 'hllboxtype_n', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllboxtype_s', $TYPE_OPS, 'hllboxtype_s', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('can', $TYPE_OPS, 'can', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('reprname', $TYPE_OPS, 'reprname', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('newtype', $TYPE_OPS, 'newtype', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('newmixintype', $TYPE_OPS, 'newtype', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('composetype', $TYPE_OPS, 'composetype', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setboolspec', $TYPE_OPS, 'setboolspec', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setmethcache', $TYPE_OPS, 'setmethcache', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setmethcacheauth', $TYPE_OPS, 'setmethcacheauth', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('settypecache', $TYPE_OPS, 'settypecache', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('settypecheckmode', $TYPE_OPS, 'settypecheckmode', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('objprimspec', $TYPE_OPS, 'objprimspec', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('objprimunsigned', $TYPE_OPS, 'objprimunsigned', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('objprimbits', $TYPE_OPS, 'objprimbits', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('isinvokable', $TYPE_OPS, 'isinvokable', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('iscoderef', $TYPE_OPS, 'iscoderef', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('gettypehllrole', $TYPE_OPS, 'gettypehllrole', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('assertparamcheck', $TYPE_OPS, 'assertparamcheck', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindcomplete', $TYPE_OPS, 'bindcomplete', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setinvokespec', $TYPE_OPS, 'setinvokespec', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setparameterizer', $TYPE_OPS, 'setparameterizer', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('parameterizetype', $TYPE_OPS, 'parameterizetype', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('typeparameterized', $TYPE_OPS, 'typeparameterized', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('typeparameters', $TYPE_OPS, 'typeparameters', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('typeparameterat', $TYPE_OPS, 'typeparameterat', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('setdebugtypename', $TYPE_OPS, 'setdebugtypename', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);

# defined - overridden by HLL, but by default same as .DEFINITE.
QAST::OperationsJVM.map_classlib_core_op('defined', $TYPE_OPS, 'isconcrete', [$RT_OBJ], $RT_INT, :tc);

# object ops that don't do the usual decontainerization
QAST::OperationsJVM.map_classlib_core_op('what_nd', $TYPE_OPS, 'what_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('how_nd', $TYPE_OPS, 'how_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('clone_nd', $TYPE_OPS, 'clone_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('isconcrete_nd', $TYPE_OPS, 'isconcrete_nd', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('istype_nd', $TYPE_OPS, 'istype_nd', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);

# container related
QAST::OperationsJVM.map_classlib_core_op('setcontspec', $TYPE_OPS, 'setcontspec', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('iscont', $TYPE_OPS, 'iscont', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iscont_i', $TYPE_OPS, 'iscont_i', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iscont_u', $TYPE_OPS, 'iscont_u', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iscont_n', $TYPE_OPS, 'iscont_n', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('iscont_s', $TYPE_OPS, 'iscont_s', [$RT_OBJ], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('isrwcont', $TYPE_OPS, 'isrwcont', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('decont', $TYPE_OPS, 'decont', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('decont_i', $TYPE_OPS, 'decont_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('decont_u', $TYPE_OPS, 'decont_u', [$RT_OBJ], $RT_UINT, :tc);
QAST::OperationsJVM.map_classlib_core_op('decont_n', $TYPE_OPS, 'decont_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('decont_s', $TYPE_OPS, 'decont_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('assign', $TYPE_OPS, 'assign', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('assignunchecked', $TYPE_OPS, 'assignunchecked', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvm_container_assign_i', $TYPE_OPS, 'assign_i', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvm_container_assign_u', $TYPE_OPS, 'assign_u', [$RT_OBJ, $RT_UINT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvm_container_assign_n', $TYPE_OPS, 'assign_n', [$RT_OBJ, $RT_NUM], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvm_container_assign_s', $TYPE_OPS, 'assign_s', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);

# lexical related opcodes
QAST::OperationsJVM.map_classlib_core_op('getlex', $TYPE_OPS, 'getlex', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlex_i', $TYPE_OPS, 'getlex_i', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlex_n', $TYPE_OPS, 'getlex_n', [$RT_STR], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlex_s', $TYPE_OPS, 'getlex_s', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexref_i', $TYPE_OPS, 'getlexref_i', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexref_u', $TYPE_OPS, 'getlexref_u', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexref_n', $TYPE_OPS, 'getlexref_n', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexref_s', $TYPE_OPS, 'getlexref_s', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlex', $TYPE_OPS, 'bindlex', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlex_i', $TYPE_OPS, 'bindlex_i', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlex_u', $TYPE_OPS, 'bindlex_u', [$RT_STR, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlex_n', $TYPE_OPS, 'bindlex_n', [$RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlex_s', $TYPE_OPS, 'bindlex_s', [$RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexdyn', $TYPE_OPS, 'getlexdyn', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindlexdyn', $TYPE_OPS, 'bindlexdyn', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexcaller', $TYPE_OPS, 'getlexcaller', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexouter', $TYPE_OPS, 'getlexouter', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexrel', $TYPE_OPS, 'getlexrel', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexreldyn', $TYPE_OPS, 'getlexreldyn', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlexrelcaller', $TYPE_OPS, 'getlexrelcaller', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);

# code object related opcodes
QAST::OperationsJVM.map_classlib_core_op('takeclosure', $TYPE_OPS, 'takeclosure', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getcodeobj', $TYPE_OPS, 'getcodeobj', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setcodeobj', $TYPE_OPS, 'setcodeobj', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getcodename', $TYPE_OPS, 'getcodename', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('setcodename', $TYPE_OPS, 'setcodename', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getcodecuid', $TYPE_OPS, 'getcodecuid', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('forceouterctx', $TYPE_OPS, 'forceouterctx', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('captureinnerlex', $TYPE_OPS, 'captureinnerlex', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('freshcoderef', $TYPE_OPS, 'freshcoderef', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('markcodestatic', $TYPE_OPS, 'markcodestatic', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('markcodestub', $TYPE_OPS, 'markcodestub', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getstaticcode', $TYPE_OPS, 'getstaticcode', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setdispatcher', $TYPE_OPS, 'setdispatcher', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('setdispatcherfor', $TYPE_OPS, 'setdispatcherfor', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nextdispatcherfor', $TYPE_OPS, 'nextdispatcherfor', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# language/compiler ops
QAST::OperationsJVM.map_classlib_core_op('getcomp', $TYPE_OPS, 'getcomp', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindcomp', $TYPE_OPS, 'bindcomp', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getcurhllsym', $TYPE_OPS, 'getcurhllsym', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindcurhllsym', $TYPE_OPS, 'bindcurhllsym', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('gethllsym', $TYPE_OPS, 'gethllsym', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('bindhllsym', $TYPE_OPS, 'bindhllsym', [$RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('sethllconfig', $TYPE_OPS, 'sethllconfig', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('loadbytecode', $TYPE_OPS, 'loadbytecode', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('loadbytecodebuffer', $TYPE_OPS, 'loadbytecodebuffer', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('usecompilerhllconfig', $TYPE_OPS, 'usecompilerhllconfig', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('usecompileehllconfig', $TYPE_OPS, 'usecompileehllconfig', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('settypehll', $TYPE_OPS, 'settypehll', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('settypehllrole', $TYPE_OPS, 'settypehllrole', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllize', $TYPE_OPS, 'hllize', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllizefor', $TYPE_OPS, 'hllizefor', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllbool', $TYPE_OPS, 'hllbool', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('hllboolfor', $TYPE_OPS, 'hllboolfor', [$RT_INT, $RT_STR], $RT_OBJ, :tc);

# regex engine related opcodes
QAST::OperationsJVM.map_classlib_core_op('nfafromstatelist', $TYPE_OPS, 'nfafromstatelist', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nfarunproto', $TYPE_OPS, 'nfarunproto', [$RT_OBJ, $RT_STR, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nfarunalt', $TYPE_OPS, 'nfarunalt', [$RT_OBJ, $RT_STR, $RT_INT, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# process related opcodes
QAST::OperationsJVM.map_classlib_core_op('exit', $TYPE_OPS, 'exit', [$RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getsignals', $TYPE_IO_OPS, 'getsignals', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('sleep', $TYPE_OPS, 'sleep', [$RT_NUM], $RT_NUM);
QAST::OperationsJVM.map_classlib_core_op('getenvhash', $TYPE_OPS, 'getenvhash', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getpid', $TYPE_OPS, 'getpid', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getppid', $TYPE_OPS, 'getppid', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvmgetproperties', $TYPE_OPS, 'jvmgetproperties', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('execname', $TYPE_OPS, 'execname', [], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getrusage', $TYPE_OPS, 'getrusage', [$RT_OBJ], $RT_OBJ, :tc);

# thread related opcodes
QAST::OperationsJVM.map_classlib_core_op('newthread', $TYPE_OPS, 'newthread', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('threadrun', $TYPE_OPS, 'threadrun', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('threadjoin', $TYPE_OPS, 'threadjoin', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('threadid', $TYPE_OPS, 'threadid', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('threadyield', $TYPE_OPS, 'threadyield', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('currentthread', $TYPE_OPS, 'currentthread', [], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('lock', $TYPE_OPS, 'lock', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('unlock', $TYPE_OPS, 'unlock', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('getlockcondvar', $TYPE_OPS, 'getlockcondvar', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('condwait', $TYPE_OPS, 'condwait', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('condsignalone', $TYPE_OPS, 'condsignalone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('condsignalall', $TYPE_OPS, 'condsignalall', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('semacquire', $TYPE_OPS, 'semacquire', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('semtryacquire', $TYPE_OPS, 'semtryacquire', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('semrelease', $TYPE_OPS, 'semrelease', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('queuepoll', $TYPE_OPS, 'queuepoll', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('cpucores', $TYPE_OPS, 'cpucores', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('freemem', $TYPE_OPS, 'freemem', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('totalmem', $TYPE_OPS, 'totalmem', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('threadlockcount', $TYPE_OPS, 'threadlockcount', [$RT_OBJ], $RT_INT, :tc);

# asynchrony related ops
QAST::OperationsJVM.map_classlib_core_op('timer', $TYPE_OPS, 'timer', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('permit', $TYPE_OPS, 'permit', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('cancel', $TYPE_OPS, 'cancel', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('cancelnotify', $TYPE_OPS, 'cancelnotify', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('signal', $TYPE_IO_OPS, 'signal', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('watchfile', $TYPE_IO_OPS, 'watchfile', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('asyncconnect', $TYPE_IO_OPS, 'asyncconnect', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('asynclisten', $TYPE_IO_OPS, 'asynclisten', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('asyncwritebytes', $TYPE_IO_OPS, 'asyncwritebytes', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('asyncreadbytes', $TYPE_IO_OPS, 'asyncreadbytes', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('spawnprocasync', $TYPE_IO_OPS, 'spawnprocasync', [$RT_OBJ, $RT_STR, $RT_OBJ, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('killprocasync', $TYPE_IO_OPS, 'killprocasync', [$RT_OBJ, $RT_INT], $RT_INT, :tc);

# Atomic ops
QAST::OperationsJVM.map_classlib_core_op('cas', $TYPE_OPS, 'cas', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicload', $TYPE_OPS, 'atomicload', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicstore', $TYPE_OPS, 'atomicstore', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicload_i', $TYPE_OPS, 'atomicload_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicstore_i', $TYPE_OPS, 'atomicstore_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicadd_i', $TYPE_OPS, 'atomicadd_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicinc_i', $TYPE_OPS, 'atomicinc_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicdec_i', $TYPE_OPS, 'atomicdec_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('cas_i', $TYPE_OPS, 'cas_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('barrierfull', $TYPE_OPS, 'barrierfull', [], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('casattr', $TYPE_OPS, 'casattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('atomicbindattr', $TYPE_OPS, 'atomicbindattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);

# JVM-specific ops for compilation unit handling
QAST::OperationsJVM.map_classlib_core_op('loadcompunit', $TYPE_OPS, 'loadcompunit', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('iscompunit', $TYPE_OPS, 'iscompunit', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('compunitmainline', $TYPE_OPS, 'compunitmainline', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('compunitcodes', $TYPE_OPS, 'compunitcodes', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('jvmclasspaths', $TYPE_OPS, 'jvmclasspaths', [], $RT_OBJ, :tc);

# JVM-specific ops for continuation handling
# The three main continuation ops are fudgy because they need to be called partially like subs
QAST::OperationsJVM.map_classlib_core_op('continuationclone', $TYPE_OPS, 'continuationclone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('continuationreset', $TYPE_OPS, 'continuationreset', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('continuationcontrol', $TYPE_OPS, 'continuationcontrol', [$RT_INT, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJVM.map_classlib_core_op('continuationinvoke', $TYPE_OPS, 'continuationinvoke', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);

# JVM interop ops
QAST::OperationsJVM.map_classlib_core_op('backendconfig', $TYPE_OPS, 'jvmgetconfig', [], $RT_OBJ, :tc);

# Native call ops
QAST::OperationsJVM.map_classlib_core_op('initnativecall', $TYPE_NATIVE_OPS, 'init', [], $RT_INT);
QAST::OperationsJVM.map_classlib_core_op('buildnativecall', $TYPE_NATIVE_OPS, 'build', [$RT_OBJ, $RT_STR, $RT_STR, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecall', $TYPE_NATIVE_OPS, 'call', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecallinvoke', $TYPE_NATIVE_OPS, 'call', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecallrefresh', $TYPE_NATIVE_OPS, 'refresh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecallsizeof', $TYPE_NATIVE_OPS, 'nativecallsizeof', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecallcast', $TYPE_NATIVE_OPS, 'nativecallcast', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJVM.map_classlib_core_op('nativecallglobal', $TYPE_NATIVE_OPS, 'nativecallglobal', [$RT_STR, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('getcodelocation', $TYPE_OPS, 'getcodelocation', [$RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('jvmgetunicodeversion', $TYPE_OPS, 'jvmgetunicodeversion', [], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getuniname', $TYPE_OPS, 'getuniname', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('unipropcode', $TYPE_OPS, 'unipropcode', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getuniprop_str', $TYPE_OPS, 'getuniprop_str', [$RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('getuniprop_int', $TYPE_OPS, 'getuniprop_int', [$RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('getuniprop_bool', $TYPE_OPS, 'getuniprop_bool', [$RT_INT, $RT_INT], $RT_INT, :tc);

QAST::OperationsJVM.map_classlib_core_op('force_gc', $TYPE_OPS, 'force_gc', [], $RT_OBJ, :tc);

QAST::OperationsJVM.map_classlib_core_op('coerce_si', $TYPE_OPS, 'coerce_si', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_is', $TYPE_OPS, 'coerce_is', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_us', $TYPE_OPS, 'coerce_us', [$RT_UINT], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_ns', $TYPE_OPS, 'coerce_ns', [$RT_NUM], $RT_STR, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_in', $TYPE_OPS, 'coerce_in', [$RT_INT], $RT_NUM, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_ni', $TYPE_OPS, 'coerce_ni', [$RT_NUM], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_ui', $TYPE_OPS, 'coerce_ui', [$RT_UINT], $RT_INT, :tc);
QAST::OperationsJVM.map_classlib_core_op('coerce_iu', $TYPE_OPS, 'coerce_iu', [$RT_INT], $RT_UINT, :tc);

QAST::OperationsJVM.map_classlib_core_op('decodelocaltime', $TYPE_OPS, 'decodelocaltime', [$RT_INT], $RT_OBJ, :tc);

# The unit compiler: a plain QAST walk that fills a QAST::UnitRecord. Every
# block's body is an engine program (QAST::TruffleEncoder), so the walk
# itself emits nothing -- it assigns qbids, keeps the block table, collects
# the programs and hands the record to the artifact writer.
class QAST::UnitCompiler {
    # Responsible for handling issues around code references: which cuids
    # have been compiled, and the unit's call-site descriptors.
    my class CodeRefBuilder {
        has int $!cur_idx;
        has %!cuid_to_idx;
        has @!blocks;
        has @!cuids;
        has @!callsites;
        has %!callsite_map;

        method BUILD() {
            $!cur_idx := 0;
            %!cuid_to_idx := {};
            @!blocks := [];
            @!cuids := [];
            @!callsites := [];
            %!callsite_map := {};
        }

        method register_block($brec, $cuid) {
            %!cuid_to_idx{$cuid} := $!cur_idx;
            nqp::push(@!blocks, $brec);
            nqp::push(@!cuids, $cuid);
            $!cur_idx := $!cur_idx + 1;
        }

        method know_cuid($cuid) {
            nqp::existskey(%!cuid_to_idx, $cuid)
        }

        method cuid_to_idx($cuid) {
            nqp::existskey(%!cuid_to_idx, $cuid)
                ?? %!cuid_to_idx{$cuid}
                !! nqp::die("Unknown CUID '$cuid'")
        }

        method get_callsite_idx(@arg_types, @arg_names) {
            my $key := join("-", @arg_types) ~ ';' ~ join("\0", @arg_names);
            if nqp::existskey(%!callsite_map, $key) {
                return %!callsite_map{$key};
            }
            else {
                my $idx := +@!callsites;
                nqp::push(@!callsites, [@arg_types, @arg_names]);
                %!callsite_map{$key} := $idx;
                return $idx;
            }
        }

        # The artifact road reads the descriptors as data:
        # [@arg_types, @arg_names] per site.
        method callsite_data() { @!callsites }
    }

    # Holds information about the QAST::Block we're currently compiling.
    my class BlockInfo {
        has $!qast;             # The QAST::Block
        has $!outer;            # Outer block's BlockInfo
        has %!lexical_types;    # Mapping of lexical names to types
        has %!lexical_returns;  # Mapping of lexical names to their type objects
        has %!lexicalref_types; # Mapping of lexical names to types
        has %!lexical_idxs;     # Lexical indexes (but have to know type too)
        has @!lexical_names;    # List by type of lexical name lists

        method new($qast, $outer) {
            my $obj := nqp::create(self);
            $obj.BUILD($qast, $outer);
            $obj
        }

        method BUILD($qast, $outer) {
            $!qast := $qast;
            $!outer := $outer;
            %!lexical_types := nqp::hash();
            %!lexical_returns := nqp::hash();
            %!lexicalref_types := nqp::hash();
            %!lexical_idxs := nqp::hash();
            @!lexical_names := nqp::list([],[],[],[], nqp::null, nqp::null, nqp::null, nqp::null, nqp::null, nqp::null, []);
        }

        method add_lexical($var, :$is_static, :$is_cont, :$is_state) {
            self.register_lexical($var);
            if $is_static || $is_cont || $is_state {
                my %blv := %*BLOCK_LEX_VALUES;
                unless nqp::existskey(%blv, $!qast.cuid) {
                    %blv{$!qast.cuid} := [];
                }
                my $flags := $is_static ?? 0 !!
                             $is_cont   ?? 1 !! 2;
                nqp::push(%blv{$!qast.cuid}, [$var.name, $var.value, $flags]);
            }
        }

        method add_lexicalref($var) {
            self.register_lexicalref($var);
        }

        method register_lexical($var) {
            my $name := $var.name;
            my $type := rttype_from_typeobj($var.returns);
            if nqp::existskey(%!lexical_types, $name) || nqp::existskey(%!lexicalref_types, $name) {
                # Name the block: "already declared" with no scope named is
                # unchaseable when the first declaration came from another
                # compilation road (the code engine commits a block's
                # lexicals itself when it encodes the block).
                nqp::die("Lexical '$name' already declared in block '"
                    ~ ($!qast.name eq '' ?? '<anon>' !! $!qast.name)
                    ~ "' (cuid " ~ $!qast.cuid ~ ")");
            }
            %!lexical_returns{$name} := $var.returns;
            %!lexical_types{$name} := $type;
            $type := 1 if $type == 10; # Work around for missing unsigned lexical type category
            %!lexical_idxs{$name} := nqp::elems(@!lexical_names[$type]);
            nqp::push(@!lexical_names[$type], $name);
        }

        method register_lexicalref($var) {
            my $name := $var.name;
            my $type := rttype_from_typeobj($var.returns);
            if nqp::existskey(%!lexical_types, $name) || nqp::existskey(%!lexicalref_types, $name) {
                nqp::die("Lexical '$name' already declared in block '"
                    ~ ($!qast.name eq '' ?? '<anon>' !! $!qast.name)
                    ~ "' (cuid " ~ $!qast.cuid ~ ")");
            }
            %!lexicalref_types{$name} := $type;
            %!lexical_idxs{$name}     := nqp::elems(@!lexical_names[$RT_OBJ]);
            nqp::push(@!lexical_names[$RT_OBJ], $name);
        }

        method qast() { $!qast }
        method outer() { $!outer }
        method lexical_type($name) { %!lexical_types{$name} }
        method lexical_returns($name) { %!lexical_returns{$name} }
        method lexicalref_type($name) { %!lexicalref_types{$name} }
        method lexical_idx($name) { %!lexical_idxs{$name} }
        method lexical_names_by_type() { @!lexical_names }
    }

    method source_for_node($node) {
        my $source := $node.node
                        ?? ~ nqp::escape($node.node.Str)
                        !! '';
        if nqp::chars($source) > 103 {
            $source := nqp::substr($source, 0, 100) ~ '...';
        }
        if nqp::chars($source) {
            $source := qq[ (source text: "$source")];
        }
        $source;
    }

    # The entry point: compiles a QAST tree into the unit record the
    # artifact writer reads.
    method unit($source, :$unit_id!, *%adverbs) {
        # Wrap $source in a QAST::CompUnit if it's not already a viable root node.
        unless nqp::istype($source, QAST::CompUnit) {
            my $unit := $source;
            $unit := QAST::Block.new($unit) unless nqp::istype($unit, QAST::Block);
            $source := QAST::CompUnit.new(:hll(''), $unit);
        }
        my $file := nqp::ifnull(nqp::getlexdyn('$?FILES'), "");
        my $*UNIT := QAST::UnitRecord.new(:$unit_id, :$file);
        my $*CODEREFS := CodeRefBuilder.new();
        self.compile_unit($source);
        $*UNIT
    }

    our $serno;
    INIT {
        $serno := 10;
    }

    method unique($prefix = '') { $prefix ~ $serno++ }

    method cuid_to_qbid(str $cuid) {
        my $map := %*CUID_TO_QBID;
        nqp::existskey($map, $cuid) ?? $map{$cuid} !! ($map{$cuid} := $*NEXT_QBID++);
    }

    method compile_unit($cu) {
        # Truffle-migration coverage survey (Phase 1): reporting only, and
        # only when its knobs are set; see QAST::TruffleEncoder.
        QAST::TruffleEncoder.survey_cu($cu);

        # A compilation-unit-wide source of IDs for handlers.
        my $*EH_IDX := 1;

        # Set HLL.
        my $*HLL := '';
        if $cu.hll {
            $*HLL := $cu.hll;
        }

        # Should have a single child which is the outer block.
        if nqp::elems(@($cu)) != 1 || !nqp::istype($cu[0], QAST::Block) {
            nqp::die("QAST::CompUnit should have one child that is a QAST::Block");
        }

        my %*CUID_TO_QBID;
        my $*NEXT_QBID := 0;
        # Engine programs of the unit's blocks, collected here; the block
        # table references them by index.
        my @*ENGINE_PROGRAMS := nqp::list_s();
        # The unit road (rakudo docs/superpowers/specs/2026-09-09-jvm-
        # unit-artifact-design.md) is the only road since milestone 3: every
        # unit is a record -- programs + block table (+ serialized context
        # for a comp-mode unit), no class file. A jar-bound unit with an
        # output file is written as a zip; any other unit is built in memory
        # and loaded as a ProgramUnit. A block that cannot encode is a
        # compile error at the junction below, never a fallback.
        my %env := nqp::getenvhash();
        nqp::die('unit artifact: the road needs the encoder on; NQP_CODE_RUN=0 or NQP_CODE_PRECOMP=0 is set, every block must encode')
            if (nqp::existskey(%env, 'NQP_CODE_RUN') && nqp::atkey(%env, 'NQP_CODE_RUN') eq '0')
            || (nqp::existskey(%env, 'NQP_CODE_PRECOMP') && nqp::atkey(%env, 'NQP_CODE_PRECOMP') eq '0');
        my str $target := %*COMPILING<%?OPTIONS><target> // '';
        nqp::die("unit artifact: --target=$target is not a stage; use --target=jar (with --output) or --target=unit")
            if $target eq 'classfile' || $target eq 'jast';
        # Pre-seed to make sure that qbids correspond to serialization IDs
        my $*COMP_MODE := $cu.compilation_mode;
        # Comp-mode units pair code refs with blocks by block id, so the
        # cuid strings are dead weight there. A nested unit is the
        # exception: it never deserializes, and the enclosing compilation
        # reconnects its code objects by looking the cuids up on the
        # freshly compiled code refs.
        my $*EMIT_CUIDS := !$*COMP_MODE || $cu.is_nested;
        if $*COMP_MODE {
            for $cu.code_ref_blocks() -> $qblock {
                %*CUID_TO_QBID{$qblock.cuid} := $*NEXT_QBID++;
            }
        }

        # Hash mapping blocks with static lexicals to an array of arrays. Each
        # of the sub-arrays has the form [$name, $value, $flags], where flags
        # are 0 = static lex, 1 = container, 2 = state container.
        my %*BLOCK_LEX_VALUES;

        # Compile the mainline block.
        self.compile_block($cu[0]);

        # If we are in compilation mode, or have pre-deserialization or
        # post-deserialization tasks, handle those. Overall, the process
        # is to desugar this into simpler QAST nodes, then compile those.
        my @pre_des   := $cu.pre_deserialize;
        my @post_des  := $cu.post_deserialize;
        # The record road builds its static-lexical-value rows inside the
        # deserialize wrapper below, so the wrapper must exist for them.
        if $*COMP_MODE || @pre_des || @post_des || need_set_code_object($cu)
            || %*BLOCK_LEX_VALUES {
            # Create a block into which we'll install all of the other
            # pieces.
            my $block := QAST::Block.new( :blocktype('raw') );

            # Add pre-deserialization tasks, each as a QAST::Stmt.
            for @pre_des {
                $block.push(QAST::Stmt.new($_));
            }

            # If we need to do deserialization, emit code for that. A
            # nested unit (an EVAL inside another compilation) does not
            # serialize: its objects live in the enclosing compilation's
            # SC and this unit only ever runs in the process that compiled
            # it. Serializing it would also fail outright, as compiler
            # state like @!compstuff thunks is still live mid-compilation.
            # The MoarVM backend skips it the same way.
            if $*COMP_MODE && !$cu.is_nested {
                $block.push(self.deserialization_code($cu.sc(), $cu.code_ref_blocks(),
                    $cu.repo_conflict_resolver()));
            }

            # Deserialization pairs the serialized code refs with this unit's
            # blocks by block id, so every block in the code ref table needs a
            # program, including one the tree never mentioned: a thunk that
            # only ever ran at BEGIN time leaves its block registered but
            # unreached. Compile the leftovers here, which is what the MoarVM
            # backend gets from hanging the whole code ref table off its list_b.
            if $cu.code_ref_blocks() {
                my $orphans := QAST::Block.new( :blocktype('immediate') );
                for $cu.code_ref_blocks() {
                    unless $*CODEREFS.know_cuid($_.cuid) {
                        # Only the program matters; make sure referencing it
                        # cannot call it or take a closure over it.
                        $_.blocktype('declaration_static');
                        $orphans.push($_);
                    }
                }
                $block.push($orphans) if nqp::elems($orphans.list);
            }

            # Add code object fixups.
            if $cu.code_ref_blocks() {
                my $cur_pd_block := QAST::Block.new( :blocktype('immediate') );
                my $i := 0;
                for $cu.code_ref_blocks() {
                    my $code_obj := $_.code_object;
                    if nqp::isconcrete($code_obj) {
                        $cur_pd_block.push(QAST::Op.new(
                            :op('setcodeobj'),
                            QAST::BVal.new( :value($_) ),
                            QAST::WVal.new( :value($code_obj) )
                        ));
                        $i++;
                        if $i == 2000 {
                            $block.push($cur_pd_block);
                            $cur_pd_block := QAST::Block.new( :blocktype('immediate') );
                            $i := 0;
                        }
                    }
                }
                $block.push($cur_pd_block);
            }

            # Add post-deserialization tasks.
            my $cur_pd_block := QAST::Block.new( :blocktype('immediate') );
            my $i := 0;
            for @post_des {
                $cur_pd_block.push(QAST::Stmt.new($_));
                $i++;
                if $i == 2000 {
                    $block.push($cur_pd_block);
                    $cur_pd_block := QAST::Block.new( :blocktype('immediate') );
                    $i := 0;
                }
            }
            $block.push($cur_pd_block);

            # Compile the wrapper and register it as the deserialization
            # handler.
            self.compile_block($block);
            # The artifact's meta carries the static lexical values; the
            # loader installs them after the deserialize program. Built
            # here, not at the push site: serialization is what first gives
            # an object its SC, and %*BLOCK_LEX_VALUES keeps growing until
            # every block -- this wrapper included -- has compiled.
            if %*BLOCK_LEX_VALUES {
                my @rows;
                for %*BLOCK_LEX_VALUES {
                    my int $qbid := self.cuid_to_qbid($_.key);
                    for $_.value -> @lex {
                        my $sc := nqp::getobjsc(@lex[1]);
                        nqp::push(@rows, [$qbid, @lex[0], nqp::scgethandle($sc),
                            nqp::scgetobjidx($sc, @lex[1]), @lex[2]]);
                    }
                }
                $*UNIT.blockvalues(@rows);
            }
            $*UNIT.deserialize_qbid(self.cuid_to_qbid($block.cuid));
        }

        # Compile and include load-time logic, if any.
        if nqp::defined($cu.load) {
            my $load_block := QAST::Block.new(
                :blocktype('raw'),
                $cu.load,
                QAST::Op.new( :op('null') )
            );
            self.compile_block($load_block);
            $*UNIT.load_qbid(self.cuid_to_qbid($load_block.cuid));
        }

        # Compile and include main-time logic, if any; the unit's entry
        # point names its qbid.
        if nqp::defined($cu.main) {
            my $main_block := QAST::Block.new(
                :blocktype('raw'),
                $cu.main,
                QAST::Op.new( :op('null') )
            );
            self.compile_block($main_block);
            $*UNIT.entry_qbid(self.cuid_to_qbid($main_block.cuid));
        }

        # The HLL name and the mainline block.
        $*UNIT.hll($*HLL);
        $*UNIT.mainline_qbid(self.cuid_to_qbid($cu[0].cuid));

        # The programs go to the writer as a boxed list (@*ENGINE_PROGRAMS
        # is a native str list; the writer reads through at_pos_boxed),
        # byte-framed by it, last, so every block -- the deserialize and
        # load wrappers included -- has had its say.
        my @progs;
        for @*ENGINE_PROGRAMS -> str $p { nqp::push(@progs, $p) }
        $*UNIT.programs(@progs);
        $*UNIT.callsites($*CODEREFS.callsite_data);
        nqp::say('code unit ' ~ $*UNIT.unit_id ~ ' -> unit road')
            if nqp::existskey(nqp::getenvhash(), 'NQP_CODE_WHY');

        $*UNIT
    }

    sub need_set_code_object($cu) {
        if $cu.code_ref_blocks() {
            for $cu.code_ref_blocks() {
                return 1 if nqp::isconcrete($_.code_object);
            }
        }
        return 0;
    }

    method deserialization_code($sc, @code_ref_blocks, $repo_conf_res) {
        # Some code-ref slots may belong to nested units (EVALs run at
        # BEGIN time) rather than to blocks compiled into this unit. Their
        # units ride along in the jar (under nested/ in the parent's
        # artifact), and the deserialization code
        # loads them back and installs their code refs into the slots
        # before deserializing, matched by cuid. The slot index is the
        # block's position: the code ref table is keyed that way.
        my %nested_by_class;
        my @nested_class_names;
        my int $crb_idx := 0;
        for @code_ref_blocks {
            my str $crb_cuid := $_.cuid;
            unless $*CODEREFS.know_cuid($crb_cuid) {
                my str $nested_class := nqp::syscall('jvm-class-of-cuid', $crb_cuid);
                if $nested_class ne '' {
                    unless nqp::existskey(%nested_by_class, $nested_class) {
                        %nested_by_class{$nested_class} := [[], []];
                        nqp::push(@nested_class_names, $nested_class);
                    }
                    nqp::push(%nested_by_class{$nested_class}[0], $crb_idx);
                    nqp::push(%nested_by_class{$nested_class}[1], $crb_cuid);
                }
            }
            $crb_idx := $crb_idx + 1;
        }
        my $nested_claims := QAST::Stmts.new();
        my $nested_finish := QAST::Stmts.new();
        if @nested_class_names {
            $*UNIT.nested_units(@nested_class_names);
            for @nested_class_names -> $nested_class {
                $nested_finish.push(QAST::Op.new(
                    :op('syscall'),
                    QAST::SVal.new( :value('jvm-finish-nested') ),
                    QAST::SVal.new( :value($nested_class) )
                ));
                my $idx_list := QAST::Op.new( :op('list_i') );
                for %nested_by_class{$nested_class}[0] {
                    $idx_list.push(QAST::IVal.new( :value($_) ));
                }
                my $cuid_list := QAST::Op.new( :op('list_s') );
                for %nested_by_class{$nested_class}[1] {
                    $cuid_list.push(QAST::SVal.new( :value($_) ));
                }
                $nested_claims.push(QAST::Op.new(
                    :op('syscall'),
                    QAST::SVal.new( :value('jvm-claim-nested') ),
                    QAST::SVal.new( :value($nested_class) ),
                    $idx_list, $cuid_list
                ));
            }
        }

        # Serialize it.
        my $sh := nqp::list_s();
        my $serialized := nqp::serialize($sc, $sh);

        if %*COMPILING<%?OPTIONS><target> eq 'jar' {
            $*UNIT.serialized($serialized);
            $serialized := nqp::null();
        }

        # Now it's serialized, pop this SC off the compiling SC stack.
        nqp::popcompsc();

        # String heap QAST.
        my $sh_ast := QAST::Op.new( :op('list_s') );
        my $sh_elems := nqp::elems($sh);
        my $i := 0;
        while $i < $sh_elems {
            $sh_ast.push(nqp::isnull_s(nqp::atpos_s($sh, $i))
                ?? QAST::Op.new( :op('null_s') )
                !! QAST::SVal.new( :value(nqp::atpos_s($sh, $i)) ));
            $i := $i + 1;
        }
        $sh_ast := QAST::Block.new( :blocktype('immediate'), $sh_ast );

        # Handle repossession conflict resolution code, if any.
        if $repo_conf_res {
            $repo_conf_res.push(QAST::Var.new( :name('conflicts'), :scope('local') ));
        }
        else {
            $repo_conf_res := QAST::Op.new(
                :op('die_s'),
                QAST::SVal.new( :value('Repossession conflicts occurred during deserialization') )
            );
        }

        # Which of our blocks need to be serialized?
        $*UNIT.serialized_count(+@code_ref_blocks);
        $*UNIT.sc_handle(nqp::scgethandle($sc));
        $*UNIT.sc_desc(nqp::scgetdesc($sc));

        # Overall deserialization QAST.
        QAST::Stmts.new(
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name('cur_sc'), :scope('local'), :decl('var') ),
                QAST::Op.new( :op('createsc'), QAST::SVal.new( :value(nqp::scgethandle($sc)) ) )
            ),
            QAST::Op.new(
                :op('scsetdesc'),
                QAST::Var.new( :name('cur_sc'), :scope('local') ),
                QAST::SVal.new( :value(nqp::scgetdesc($sc)) )
            ),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name('conflicts'), :scope('local'), :decl('var') ),
                QAST::Op.new( :op('list') )
            ),
            $nested_claims,
            QAST::Op.new(
                :op('deserialize'),
                nqp::isnull($serialized) ?? QAST::Op.new( :op('null_s') ) !! QAST::SVal.new( :value($serialized) ),
                QAST::Var.new( :name('cur_sc'), :scope('local') ),
                $sh_ast,
                QAST::Op.new( :op('null') ),
                QAST::Var.new( :name('conflicts'), :scope('local') )
            ),
            $nested_finish,
            QAST::Op.new(
                :op('if'),
                QAST::Op.new(
                    :op('elems'),
                    QAST::Var.new( :name('conflicts'), :scope('local') )
                ),
                $repo_conf_res
            )
        )
    }

    # Compiles one block: registers it, hands it to the encoder, records
    # what the loader needs. A block already compiled (the encoder's
    # nested-block deferral reaches a block before the tree walk does) is
    # left alone. Answers nothing: the block's program lives in
    # @*ENGINE_PROGRAMS and its record in $*UNIT.
    method compile_block($node) {
        return 0 if $*CODEREFS.know_cuid($node.cuid);
        my $outer := $*BLOCK;
        my $block := BlockInfo.new($node, $outer);

        # Catch/control handlers the block gets; the encoder registers them
        # through these two contextuals while it encodes the body.
        my @handlers;
        my $*HANDLER_IDX := 0;
        my &*REGISTER_UNWIND_HANDLER := sub ($outer, $category, :$ex_obj) {
            my $unwind := $*EH_IDX++;
            nqp::push(@handlers, [$unwind, $outer, $category,
                $ex_obj ?? $EX_UNWIND_OBJECT !! $EX_UNWIND_SIMPLE]);
            $unwind
        }
        my &*REGISTER_BLOCK_HANDLER := sub ($outer, $category, $lexidx) {
            my $unwind := $*EH_IDX++;
            nqp::push(@handlers, [$unwind, $outer, $category,
                $EX_BLOCK, $lexidx]);
            $unwind
        }

        my int $qbid := self.cuid_to_qbid($node.cuid);
        my $*BREC := QAST::BlockRecord.new(:$qbid, :name($node.name),
            :cuid($*COMP_MODE && !$*EMIT_CUIDS ?? '' !! $node.cuid));

        # Source location, so nqp::getcodelocation has something to answer
        # with at runtime. A node that knows its own file and line (RakuAST
        # origins) is believed outright; otherwise the position is computed
        # from the orig, honoring #line directives.
        if $node.node && nqp::can($node.node, 'file') && nqp::can($node.node, 'line') {
            my $loc-file := $node.node.file;
            if $loc-file {
                $*BREC.file(~$loc-file);
                $*BREC.line($node.node.line);
                $*BREC.rawline(nqp::can($node.node, 'orig-line')
                    ?? $node.node.orig-line()
                    !! nqp::can($node.node, 'orig')
                        ?? HLL::Compiler.lineof($node.node.orig(),
                               $node.node.from(), :cache(1), :directives(0))
                        !! $node.node.line);
            }
        }
        elsif $node.node && nqp::can($node.node, 'orig') {
            my $line-file := HLL::Compiler.linefileof(
                $node.node.orig(), $node.node.from(), :cache(1), :directives(1));
            my $loc-file := $line-file[1]
                || nqp::ifnull(nqp::getlexdyn('$?FILES'), '');
            if $loc-file {
                $*BREC.file(~$loc-file);
                $*BREC.line($line-file[0]);
                $*BREC.rawline(HLL::Compiler.lineof(
                    $node.node.orig(), $node.node.from(), :cache(1), :directives(0)));
            }
        }
        # The mainline (built from the comp_unit cursor before it matched)
        # and the compiler's own raw wrappers have no node to take a file
        # from; the block record carries the unit's file itself.
        $*BREC.file($*UNIT.file) if $*BREC.file eq '' && $*UNIT.file ne '';

        $*CODEREFS.register_block($*BREC, $node.cuid);
        $*BREC.outer(nqp::istype($outer, BlockInfo) ?? self.cuid_to_qbid($outer.qast.cuid) !! -1);

        {
            my $*BLOCK := $block;
            my str $prog := QAST::TruffleEncoder.encode_block($node, $block, self,
                :comp_mode($*COMP_MODE));
            if $prog eq '' {
                nqp::die('unit artifact: block '
                    ~ ($node.name eq '' ?? '<anon ' ~ $node.cuid ~ '>' !! $node.name)
                    ~ ' (cuid ' ~ $node.cuid ~ ') has no engine program;'
                    ~ ' run with NQP_CODE_BAIL=1 or NQP_CODE_WHY=1 for the reason');
            }
            my int $pidx := nqp::elems(@*ENGINE_PROGRAMS);
            nqp::push_s(@*ENGINE_PROGRAMS, $prog);
            $*BREC.program($pidx);
        }

        my @lex_names := $block.lexical_names_by_type();
        $*BREC.olex(@lex_names[$RT_OBJ]);
        $*BREC.ilex(@lex_names[$RT_INT]);
        $*BREC.nlex(@lex_names[$RT_NUM]);
        $*BREC.slex(@lex_names[$RT_STR]);

        my @flat_handlers := [nqp::elems(@handlers)];
        for @handlers {
            nqp::push(@flat_handlers, nqp::elems($_));
            for $_ { nqp::push(@flat_handlers, $_) }
        }
        $*BREC.handlers(@flat_handlers);
        $*BREC.has_exit_handler(1) if $node.has_exit_handler;
        $*BREC.is_thunk(1) if $node.is_thunk;
        $*UNIT.add_block($*BREC);
        1
    }

    # The descriptor for this rule. The engine is the only regex code path,
    # so this does not return null in a normal compile: a rule it cannot
    # encode dies in RxDescriptor.bail, naming the rule. The one null it can
    # hand back is NQP_RX_SURVEY's diagnostic pass, which the caller turns
    # into the same hard error.
    method rx_descriptor($node) {
        QAST::RxDescriptor.encode($node)
    }

    # The block a rule's inline code pieces are dispatched from.
    #
    # The cursor and its declaring class are parameters rather than lexicals
    # because they are LOCALS of the rule's frame, which a nested block cannot
    # see -- the same reason a rule whose code reads a lowered local is
    # refused outright.
    method rx_callback_block($desc) {
        self.rx_callback_block_for($desc.callbacks, 0)
    }

    # A rule with very many pieces is split rather than dispatched from one
    # block: above the group size the pieces split into nested blocks of at
    # most that many, the outer block dispatching on index range and
    # forwarding its arguments. The pieces then run one frame deeper, which
    # is safe for the same reason the callback block itself is: compile-time
    # nesting and run-time frame nesting gain the same level in the same
    # place, and the ops that could tell the difference already refuse the
    # rule (reads_frame_ops).
    method rx_callback_block_for(@bodies, int $base) {
        my int $GROUP := 12;
        my $idx   := QAST::Node.unique('rxcb_idx');
        my $cur   := QAST::Node.unique('rxcb_cur');
        my $class := QAST::Node.unique('rxcb_class');
        my $pos   := QAST::Node.unique('rxcb_pos');

        my sub local($name, *%opts) {
            QAST::Var.new( :name($name), :scope('local'), |%opts )
        }

        my $dispatch;
        if nqp::elems(@bodies) > $GROUP {
            # Range dispatch over groups. Built LOW to HIGH so the highest
            # range test lands outermost -- with the nesting the other way,
            # every index above the first group's floor would take the first
            # branch and dispatch to nothing.
            $dispatch := QAST::Op.new( :op('null') );
            my int $start := 0;
            while $start < nqp::elems(@bodies) {
                my @group;
                my int $g := $start;
                while $g < nqp::elems(@bodies) && $g < $start + $GROUP {
                    nqp::push(@group, @bodies[$g]);
                    $g := $g + 1;
                }
                $dispatch := QAST::Op.new(
                    :op('if'),
                    QAST::Op.new( :op('isge_i'), local($idx),
                        QAST::IVal.new( :value($base + $start) ) ),
                    QAST::Op.new(
                        :op('call'),
                        self.rx_callback_block_for(@group, $base + $start),
                        local($idx), local($cur), local($class), local($pos)
                    ),
                    $dispatch
                );
                $start := $start + $GROUP;
            }
        }
        else {
            # Chosen from the last back, so each `if` wraps the ones after it.
            $dispatch := QAST::Op.new( :op('null') );
            my int $i := nqp::elems(@bodies);
            while $i > 0 {
                $i := $i - 1;
                $dispatch := QAST::Op.new(
                    :op('if'),
                    QAST::Op.new( :op('iseq_i'), local($idx),
                        QAST::IVal.new( :value($base + $i) ) ),
                    # What runs before the code does: the position it is
                    # looking at goes on the cursor, and $¢ names the cursor,
                    # because the code is written to read both.
                    QAST::Stmts.new(
                        QAST::Op.new(
                            :op('bindattr_i'),
                            local($cur), local($class),
                            QAST::SVal.new( :value('$!pos') ),
                            local($pos)
                        ),
                        QAST::Op.new(
                            :op('bind'),
                            QAST::Var.new( :name("\$\xa2"), :scope('lexical') ),
                            local($cur)
                        ),
                        @bodies[$i]
                    ),
                    $dispatch
                );
            }
        }

        QAST::Block.new(
            local($idx,   :decl('param'), :returns(int)),
            local($cur,   :decl('param')),
            local($class, :decl('param')),
            local($pos,   :decl('param'), :returns(int)),
            $dispatch
        )
    }

    method operations() { QAST::OperationsJVM }
}

# Register as the QAST compiler.
if nqp::isnull(nqp::getcomp('QAST')) {
    nqp::bindcomp('QAST', QAST::UnitCompiler);
}
