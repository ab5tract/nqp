use JASTNodes;
use QASTNode;
use NQPHLL;

# Instruction constants for argument-less ops.
my $ACONST_NULL := JAST::Instruction.new( :op('aconst_null') );
my $ALOAD_0     := JAST::Instruction.new( :op('aload_0') );
my $ALOAD_1     := JAST::Instruction.new( :op('aload_1') );
my $IASTORE     := JAST::Instruction.new( :op('iastore') );
my $LASTORE     := JAST::Instruction.new( :op('lastore') );
my $AALOAD      := JAST::Instruction.new( :op('aaload') );
my $AASTORE     := JAST::Instruction.new( :op('aastore') );
my $BASTORE     := JAST::Instruction.new( :op('bastore') );
my $POP         := JAST::Instruction.new( :op('pop') );
my $POP2        := JAST::Instruction.new( :op('pop2') );
my $DUP         := JAST::Instruction.new( :op('dup') );
my $DUP_X2      := JAST::Instruction.new( :op('dup_x2') );
my $DUP2        := JAST::Instruction.new( :op('dup2') );
my $DUP2_X2     := JAST::Instruction.new( :op('dup2_x2') );
my $SWAP        := JAST::Instruction.new( :op('swap') );
my $IADD        := JAST::Instruction.new( :op('iadd') );
my $LADD        := JAST::Instruction.new( :op('ladd') );
my $LSUB        := JAST::Instruction.new( :op('lsub') );
my $IAND        := JAST::Instruction.new( :op('iand') );
my $I2L         := JAST::Instruction.new( :op('i2l') );
my $I2B         := JAST::Instruction.new( :op('i2b') );
my $L2I         := JAST::Instruction.new( :op('l2i') );
my $L2D         := JAST::Instruction.new( :op('l2d') );
my $D2L         := JAST::Instruction.new( :op('d2l') );
my $LCMP        := JAST::Instruction.new( :op('lcmp') );
my $DCMPL       := JAST::Instruction.new( :op('dcmpl') );
my $RETURN      := JAST::Instruction.new( :op('return') );
my $ARETURN     := JAST::Instruction.new( :op('areturn') );
my $IRETURN     := JAST::Instruction.new( :op('ireturn') );
my $ATHROW      := JAST::Instruction.new( :op('athrow') );

# Common constant loads.
my $IVAL_ZERO     := JAST::PushIVal.new( :value(0) );
my $IVAL_ONE      := JAST::PushIVal.new( :value(1) );
my $IVAL_MINUSONE := JAST::PushIVal.new( :value(-1) );
my $NVAL_ZERO     := JAST::PushNVal.new( :value(0.0) );

# Some common types we'll need.
my $TYPE_TC         := 'Lorg/raku/nqp/runtime/ThreadContext;';
my $TYPE_CU         := 'Lorg/raku/nqp/runtime/CompilationUnit;';
my $TYPE_CR         := 'Lorg/raku/nqp/runtime/CodeRef;';
my $TYPE_CF         := 'Lorg/raku/nqp/runtime/CallFrame;';
my $TYPE_OPS        := 'Lorg/raku/nqp/runtime/Ops;';
my $TYPE_RXENGINE   := 'Lorg/raku/nqp/runtime/GrammarEngines;';
my $TYPE_NATIVE_OPS := 'Lorg/raku/nqp/runtime/NativeCallOps;';
my $TYPE_IO_OPS     := 'Lorg/raku/nqp/runtime/IOOps;';
my $TYPE_CSD        := 'Lorg/raku/nqp/runtime/CallSiteDescriptor;';
my $TYPE_SMO        := 'Lorg/raku/nqp/sixmodel/SixModelObject;';
my $TYPE_STR        := 'Ljava/lang/String;';
my $TYPE_OBJ        := 'Ljava/lang/Object;';

# How many invokedynamic instructions one compilation unit's class may hold.
# HotSpot gives a class a single resolved-references array and indexes it with
# a 16-bit field. It holds one entry per invokedynamic INSTRUCTION -- not per
# constant pool entry, so sites sharing a bootstrap still take one each -- plus
# one per string, class, method-handle and method-type constant. Past 65535 the
# index wraps and entries alias: a string constant reads back as some other
# site's CallSite, and a bootstrap slot reads back as something that is not a
# MethodHandle, which the VM reports as "classfile must supply a valid BSM" or
# dies on outright. Nothing downstream can detect that, so leave room for the
# constants and put the dispatches that do not fit on the uncached invokestatic
# path, which needs no resolved reference at all.
my int $INDY_SITE_BUDGET := 48000;
my $TYPE_MATH       := 'Ljava/lang/Math;';
my $TYPE_MH         := 'Ljava/lang/invoke/MethodHandle;';
my $TYPE_MT         := 'Ljava/lang/invoke/MethodType;';
my $TYPE_MHS        := 'Ljava/lang/invoke/MethodHandles;';
my $TYPE_MHL        := 'Ljava/lang/invoke/MethodHandles$Lookup;';
my $TYPE_CLASS      := 'Ljava/lang/Class;';
my $TYPE_LONG       := 'Ljava/lang/Long;';
my $TYPE_DOUBLE     := 'Ljava/lang/Double;';
my $TYPE_EH         := 'Lorg/raku/nqp/runtime/ExceptionHandling;';
my $TYPE_EX_UNWIND  := 'Lorg/raku/nqp/runtime/UnwindException;';
my $TYPE_EX_CONT    := 'Lorg/raku/nqp/runtime/ControlException;';
my $TYPE_EX_RT      := 'Ljava/lang/RuntimeException;';
my $TYPE_EX_SAVE    := 'Lorg/raku/nqp/runtime/SaveStackException;';
my $TYPE_THROWABLE  := 'Ljava/lang/Throwable;';
my $TYPE_RESUME     := 'Lorg/raku/nqp/runtime/ResumeStatus$Frame;';

# Exception handler categories.
my $EX_CAT_CATCH   := 1;
my $EX_CAT_ANY     := 2;
my $EX_CAT_NEXT    := 4;
my $EX_CAT_REDO    := 8;
my $EX_CAT_LAST    := 16;
my $EX_CAT_RETURN  := 32;
my $EX_CAT_TAKE    := 128;
my $EX_CAT_WARN    := 256;
my $EX_CAT_SUCCEED := 512;
my $EX_CAT_PROCEED := 1024;
my $EX_CAT_LABELED := 4096;
my $EX_CAT_AWAIT   := 8192;
my $EX_CAT_EMIT    := 16384;
my $EX_CAT_DONE    := 32768;
my $EX_CAT_CONTROL := $EX_CAT_NEXT +| $EX_CAT_REDO +| $EX_CAT_LAST +|
                      $EX_CAT_TAKE +| $EX_CAT_WARN +|
                      $EX_CAT_SUCCEED +| $EX_CAT_PROCEED +|
                      $EX_CAT_AWAIT +| $EX_CAT_EMIT +| $EX_CAT_DONE +|
                      $EX_CAT_RETURN +| $EX_CAT_ANY;

# Exception handler kinds.
my $EX_UNWIND_SIMPLE := 0;
my $EX_UNWIND_OBJECT := 1;
my $EX_BLOCK         := 2;

# Represents the result of turning some QAST into JAST. That includes any
# instructions, but also some metadata that goes with them.
my $RT_OBJ  := 0;
my $RT_INT  := 1;
my $RT_NUM  := 2;
my $RT_STR  := 3;
my $RT_UINT := 10;
my $RT_VOID := -1;
my class Result {
    has $!jast;         # The JAST
    has int $!type;     # Result type (obj/int/num/str)
    has str $!local;    # Local where the result is; if empty, it's on the stack

    method jast()  { $!jast }
    method type()  { $!type }
    method local() { $!local }

    method set_local($local) { $!local := $local }
}
sub result($jast, int $type) {
    my $r := nqp::create(Result);
    nqp::bindattr($r, Result, '$!jast', $jast);
    nqp::bindattr_i($r, Result, '$!type', $type);
    nqp::bindattr_s($r, Result, '$!local', '');
    $*STACK.push($r);
    $r
}
sub result_from_cf($il, $rtype) {
    if $*WANT != $RT_VOID {
        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            'result_' ~ typechar($rtype), jtype($rtype), $TYPE_CF ));
        result($il, $rtype)
    }
    else {
        result($il, $RT_VOID)
    }
}
my @jtypes := [$TYPE_SMO, 'Long', 'Double', $TYPE_STR, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, 'Long'];
sub jtype($type_idx) { @jtypes[$type_idx] }
my @rttypes := [$RT_OBJ, $RT_INT, $RT_NUM, $RT_STR, -1, -1, -1, -1, -1, -1, $RT_UINT];
sub rttype_from_typeobj($typeobj) {
    @rttypes[nqp::objprimspec($typeobj)]
}
my @typeobjs := [NQPMu, int, num, str, NQPMu, NQPMu, NQPMu, NQPMu, NQPMu, NQPMu, uint];
sub typeobj_from_rttype($rttype) {
    @typeobjs[$rttype]
}
my @typechars := ['o', 'i', 'n', 's', '', '', '', '', '', '', 'u'];
sub typechar($type_idx) { @typechars[$type_idx] }

# Various typed instructions.
my @store_ins := ['astore', 'lstore', 'dstore', 'astore', '', '', '', '', '', '', 'lstore'];
sub store_ins($type) {
    @store_ins[$type]
}
my @load_ins := ['aload', 'lload', 'dload', 'aload', '', '', '', '', '', '', 'lload'];
sub load_ins($type) {
    @load_ins[$type]
}
my @dup_ins := [
    $DUP,
    $DUP2,
    $DUP2,
    $DUP,
    $DUP,
    $DUP,
    $DUP,
    $DUP,
    $DUP,
    $DUP,
    $DUP2,
];
sub dup_ins($type) {
    @dup_ins[$type]
}
my @pop_ins := [
    $POP,
    $POP2,
    $POP2,
    $POP,
    $POP,
    $POP,
    $POP,
    $POP,
    $POP,
    $POP,
    $POP2,
];
sub pop_ins($type) {
    # A void result left nothing on the stack; $RT_VOID is -1 and a negative
    # index would wrap around to the end of the table and pop2 thin air.
    $type == $RT_VOID
        ?? JAST::InstructionList.new()
        !! @pop_ins[$type]
}

# Mapping of QAST::Want type identifiers to $RT_*.
my %WANTMAP := nqp::hash(
    'v', $RT_VOID,
    'I', $RT_INT, 'i', $RT_INT,
    'U', $RT_UINT, 'u', $RT_UINT,
    'N', $RT_NUM, 'n', $RT_NUM,
    'S', $RT_STR, 's', $RT_STR,
    'P', $RT_OBJ, 'p', $RT_OBJ
);

# Utility for getting a fresh temporary by type.
my @fresh_methods := ["fresh_o", "fresh_i", "fresh_n", "fresh_s", '', '', '', '', '' ,'', "fresh_i"];
sub fresh($type) {
    my $meth := @fresh_methods[$type];
    $*TA."$meth"()
}
sub bfresh($type) {
    my $meth := @fresh_methods[$type];
    $*BLOCK_TA."$meth"()
}

# Argument flags.
my $ARG_OBJ   := 0;
my $ARG_INT   := 1;
my $ARG_NUM   := 2;
my $ARG_STR   := 4;
my $ARG_UINT  := 32;
my $ARG_NAMED := 8;
my $ARG_FLAT  := 16;
my @arg_types := [$ARG_OBJ, $ARG_INT, $ARG_NUM, $ARG_STR, -1, -1, -1, -1, -1, -1, $ARG_UINT];
sub arg_type($t) { @arg_types[$t] }

class QAST::OperationsJAST {
    # Maps operations to code that will handle them. Hash of code.
    my %core_ops;

    # Maps HLL-specific operations to code that will handle them.
    # Hash of hash of code.
    my %hll_ops;

    # Mapping of how to box/unbox by HLL.
    my %hll_box;
    my %hll_unbox;

    # What we know about inlinability.
    my %core_inlinability;
    my %hll_inlinability;

    # What we know about op native results types.
    my %core_result_type;
    my %hll_result_type;

    # Compiles an operation.
    method compile_op($qastcomp, $hll, $op) {
        my $name := $op.op;
        if $hll {
            if %hll_ops{$hll} && %hll_ops{$hll}{$name} -> $mapper {
                return $mapper($qastcomp, $op);
            }
        }
        if %core_ops{$name} -> $mapper {
            return $mapper($qastcomp, $op);
        }

        my $source := $qastcomp.source_for_node($op);
        nqp::die("Error while compiling op $name$source, no registered operation handler");
    }

    # Adds a core op handler.
    method add_core_op($op, $handler, :$inlinable = 1) {
        %core_ops{$op} := $handler;
        self.set_core_op_inlinability($op, $inlinable);
    }

    # Is there a handler for this core op? HLL code asks through the
    # backend's supports-op to decide between implementation strategies.
    method core_op_supported($op) {
        nqp::existskey(%core_ops, $op)
    }

    # Adds a HLL op handler.
    method add_hll_op($hll, $op, $handler, :$inlinable = 1) {
        %hll_ops{$hll} := {} unless nqp::existskey(%hll_ops, $hll);
        %hll_ops{$hll}{$op} := $handler;
        self.set_hll_op_inlinability($hll, $op, $inlinable);
    }

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

    # Adds a core nqp:: op provided directly by a JVM op.
    method map_jvm_core_op($op, $jvm_op, @stack_in, $stack_out) {
        my $ins := JAST::Instruction.new( :op($jvm_op) );
        self.add_core_op($op, op_mapper($op, $ins, @stack_in, $stack_out));
        self.set_core_op_result_type($op, $stack_out);
    }

    # Adds a HLL nqp:: op provided directly by a JVM op.
    method map_jvm_hll_op($hll, $op, $jvm_op, @stack_in, $stack_out) {
        my $ins := JAST::Instruction.new( :op($jvm_op) );
        self.add_hll_op($hll, $op, op_mapper($op, $ins, @stack_in, $stack_out));
        self.set_hll_op_result_type($hll, $op, $stack_out);
    }

    # Adds a core nqp:: op provided by a static method in the
    # class library.
    method map_classlib_core_op($op, $class, $method, @stack_in, $stack_out, :$tc, :$cont, :$inlinable = 1) {
        my @jtypes_in;
        for @stack_in {
            nqp::push(@jtypes_in, jtype($_));
        }
        nqp::push(@jtypes_in, $TYPE_TC) if $tc;
        my $ins := JAST::Instruction.new( :op('invokestatic'),
            $class, $method, $cont ?? 'Void' !! jtype($stack_out), |@jtypes_in );
        self.add_core_op($op, op_mapper($op, $ins, @stack_in, $stack_out, :$tc, :$cont));
        self.set_core_op_inlinability($op, $inlinable);
        self.set_core_op_result_type($op, $stack_out);
    }

    # Adds a HLL nqp:: op provided by a static method in the
    # class library.
    method map_classlib_hll_op($hll, $op, $class, $method, @stack_in, $stack_out, :$tc, :$cont, :$inlinable = 1) {
        my @jtypes_in;
        for @stack_in {
            nqp::push(@jtypes_in, jtype($_));
        }
        nqp::push(@jtypes_in, $TYPE_TC) if $tc;
        my $ins := JAST::Instruction.new( :op('invokestatic'),
            $class, $method, $cont ?? 'Void' !! jtype($stack_out), |@jtypes_in );
        self.add_hll_op($hll, $op, op_mapper($op, $ins, @stack_in, $stack_out, :$tc, :$cont));
        self.set_core_op_inlinability($op, $inlinable);
        self.set_hll_op_result_type($hll, $op, $stack_out);
    }

    # Generates an operation mapper. Covers a range of operations,
    # including those provided by calling a method and those that are
    # just JVM op invocations.
    sub op_mapper($op, $instruction, @stack_in, $stack_out, :$tc = 0, :$cont = 0) {
        my int $expected_args := +@stack_in;
        return -> $qastcomp, $node {
            if nqp::elems(@($node)) != $expected_args {
                nqp::die("Operation '$op' requires $expected_args operands");
            }

            # Emit operands.
            my $il := JAST::InstructionList.new();
            my int $i := 0;
            my @arg_res;
            while $i < $expected_args {
                my $type := @stack_in[$i];
                my $operand := $node[$i];
                my $operand_res := $qastcomp.as_jast($node[$i], :want($type));
                $il.append($operand_res.jast);
                $i++;
                nqp::push(@arg_res, $operand_res);
            }

            # Emit operation.
            $*STACK.spill_to_locals($il) if $cont;
            $*STACK.obtain($il, |@arg_res) if @arg_res;
            if $tc {
                $il.append($ALOAD_1);
            }
            if $cont {
                $il.append(savesite($instruction));
                result_from_cf($il, $stack_out);
            } else {
                $il.append($instruction);
                result($il, $stack_out)
            }
        }
    }

    # Sets op native result type at a core level.
    method set_core_op_result_type($op, $type) {
        if $type == $RT_INT {
            %core_result_type{$op} := int;
        }
        elsif $type == $RT_UINT {
            %core_result_type{$op} := uint;
        }
        elsif $type == $RT_NUM {
            %core_result_type{$op} := num;
        }
        elsif $type == $RT_STR {
            %core_result_type{$op} := str;
        }
    }

    # Sets op inlinability at a HLL level. (Can override at HLL level whether
    # or not the HLL overrides the op itself.)
    method set_hll_op_result_type($hll, $op, $type) {
        %hll_result_type{$hll} := {} unless nqp::existskey(%hll_result_type, $hll);
        if $type == $RT_INT {
            %hll_result_type{$hll}{$op} := int;
        }
        elsif $type == $RT_UINT {
            %hll_result_type{$hll}{$op} := uint;
        }
        elsif $type == $RT_NUM {
            %hll_result_type{$hll}{$op} := num;
        }
        elsif $type == $RT_STR {
            %hll_result_type{$hll}{$op} := str;
        }
    }

    # Sets returns on an op node if it has a native result type.
    method attach_result_type($hll, $node) {
        my $op := $node.op;
        if nqp::existskey(%hll_result_type, $hll) {
            if nqp::existskey(%hll_result_type{$hll}, $op) {
                $node.returns(%hll_result_type{$hll}{$op});
                return 1;
            }
        }
        if nqp::existskey(%core_result_type, $op) {
            $node.returns(%core_result_type{$op});
        }
    }

    # Adds a HLL box handler.
    method add_hll_box($hll, $type, $handler) {
        unless $type == $RT_INT || $type == $RT_UINT || $type == $RT_NUM || $type == $RT_STR {
            nqp::die("Unknown box type '$type'");
        }
        %hll_box{$hll} := {} unless nqp::existskey(%hll_box, $hll);
        %hll_box{$hll}{$type} := $handler;
    }

    # Adds a HLL unbox handler.
    method add_hll_unbox($hll, $type, $handler) {
        unless $type == $RT_INT || $type == $RT_UINT || $type == $RT_NUM || $type == $RT_STR {
            nqp::die("Unknown unbox type '$type'");
        }
        %hll_unbox{$hll} := {} unless nqp::existskey(%hll_unbox, $hll);
        %hll_unbox{$hll}{$type} := $handler;
    }

    # Generates instructions to box what's currently on the stack top.
    method box($qastcomp, $hll, $type) {
        (%hll_box{$hll} // %hll_box{''}){$type}($qastcomp)
    }

    # Generates instructions to unbox what's currently on the stack top.
    method unbox($qastcomp, $hll, $type) {
        (%hll_unbox{$hll} // %hll_unbox{''}){$type}($qastcomp)
    }

    # Builds a result; helper method for extensions to the ops from outside
    # of this file.
    method result($jast, int $type) {
        result($jast, $type)
    }
}

# The size spec Ops.sizedref wants for a reference to a sized native
# lexical: low byte = bit width, +256 = unsigned, 32 alone = num32;
# 0 = full width, nothing to note.
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

# A store to a sized native variable truncates the value to the declared
# width, the way MoarVM's sized registers do: mask for unsigned, shift out
# and arithmetically back for signed, and a round-trip through float for
# num32. The value to store is on the stack; full-width types emit nothing.
sub emit_sized_native_trunc($il, $returns, int $rt) {
    my int $spec := nqp::isnull($returns) ?? 0 !! nqp::objprimspec($returns);
    if ($rt == $RT_INT || $rt == $RT_UINT) && ($spec == 1 || $spec == 10) {
        my int $bits := nqp::objprimbits($returns);
        if $bits > 0 && $bits < 64 {
            if $spec == 10 {
                $il.append(JAST::PushIVal.new(
                    :value(nqp::sub_i(nqp::bitshiftl_i(1, $bits), 1)) ));
                $il.append(JAST::Instruction.new( :op('land') ));
            }
            else {
                $il.append(JAST::PushIndex.new( :value(64 - $bits) ));
                $il.append(JAST::Instruction.new( :op('lshl') ));
                $il.append(JAST::PushIndex.new( :value(64 - $bits) ));
                $il.append(JAST::Instruction.new( :op('lshr') ));
            }
        }
    }
    elsif $rt == $RT_NUM && $spec == 2 && nqp::objprimbits($returns) == 32 {
        $il.append(JAST::Instruction.new( :op('d2f') ));
        $il.append(JAST::Instruction.new( :op('f2d') ));
    }
}

sub savesite($il) {
    my $index   := $*BLOCK.alloc_save_site;
    my $reenter := JAST::Label.new( :name( "reenter_"~$index ) );
    my $saver   := JAST::Label.new( :name( "SAVER" ) );
    my $try     := JAST::InstructionList.new();
    my $catch   := JAST::InstructionList.new();

    $try.append($il);
    $try.append($reenter);

    $catch.append(JAST::PushIndex.new( :value($index) ));
    $catch.append(JAST::Instruction.new( :op('goto'), $saver ));

    JAST::TryCatch.new( :try($try), :catch($catch), :type($TYPE_EX_SAVE) );
}

# Chaining.
my $chain_codegen := sub ($qastcomp, $op) {
    # First, we build up the list of nodes in the chain
    my @clist;
    my $c_ast := $op;

    # Check if callee sub in name, if not first child is callee, not arg
    my $arg_idx;
    my &get_arg_idx := -> $cq { $cq.name ?? 0 !! 1 };

    while nqp::istype($c_ast, QAST::Op)
    && ($c_ast.op eq 'chain' || $c_ast.op eq 'chainstatic') {
        nqp::push(@clist, $c_ast);
        $arg_idx := get_arg_idx($c_ast);
        $c_ast   := $c_ast[$arg_idx];
    }

    my $il       := JAST::InstructionList.new();
    my $result   := $*TA.fresh_o();
    my $endlabel := JAST::Label.new(:name($qastcomp.unique('chain_end_')));

    $c_ast := nqp::pop(@clist);
    my $a_ast := $c_ast[$arg_idx];
    my $ares  := $qastcomp.as_jast($a_ast, :want($RT_OBJ));
    my $atmp  := $*TA.fresh_o();
    $il.append($ares.jast);
    $*STACK.obtain($il, $ares);
    $il.append(JAST::Instruction.new( :op('astore'), $atmp ));

    my $more := 1;
    while $more {
        my $b_ast := $c_ast[$arg_idx + 1];
        my $bres  := $qastcomp.as_jast($b_ast, :want($RT_OBJ));
        my $btmp  := $*TA.fresh_o();
        $il.append($bres.jast);
        $*STACK.obtain($il, $bres);
        $il.append(JAST::Instruction.new( :op('astore'), $btmp ));

        # The link is a lang-call dispatch on the (decontainerized) callee,
        # the same as MoarVM's chain emission.
        my $callee_qast := $c_ast.name
            ?? QAST::Op.new( :op('decont'),
                 QAST::Var.new( :name($c_ast.name), :scope('lexical') ) )
            !! QAST::Op.new( :op('decont'), $c_ast[0] );
        my $callres := $qastcomp.as_jast($callee_qast, :want($RT_OBJ));
        my $calltmp := $*TA.fresh_o();
        $il.append($callres.jast);
        $*STACK.obtain($il, $callres);
        $il.append(JAST::Instruction.new( :op('astore'), $calltmp ));

        $*STACK.spill_to_locals($il);
        my $cs_idx := $*CODEREFS.get_callsite_idx([$ARG_OBJ, $ARG_OBJ, $ARG_OBJ], []);

        $il.append(JAST::PushSVal.new( :value('lang-call') ));
        $il.append(JAST::PushIndex.new( :value($cs_idx) ));
        $il.append($ALOAD_1);
        if $*CODEREFS.take_indy_site() {
            $il.append(JAST::Instruction.new( :op('aload'), $calltmp ));
            $il.append(JAST::Instruction.new( :op('aload'), $atmp ));
            $il.append(JAST::Instruction.new( :op('aload'), $btmp ));
            $il.append(savesite(JAST::InvokeDynamic.new(
                'dispatch_noa', 'V', [$TYPE_STR, 'I', $TYPE_TC, $TYPE_SMO, $TYPE_SMO, $TYPE_SMO],
                'org/raku/nqp/dispatch/DispatchBootstrap', 'dispatch_noa'
            )));
        }
        else {
            # Out of resolved-reference budget; see $INDY_SITE_BUDGET. All three
            # arguments are objects already in locals, so the array needs no
            # boxing and the stack tracker is not involved.
            my @tmps := [$calltmp, $atmp, $btmp];
            $il.append(JAST::PushIndex.new( :value(3) ));
            $il.append(JAST::Instruction.new( :op('anewarray'), $TYPE_OBJ ));
            my int $ti := 0;
            while $ti < 3 {
                $il.append($DUP);
                $il.append(JAST::PushIndex.new( :value($ti) ));
                $il.append(JAST::Instruction.new( :op('aload'), @tmps[$ti] ));
                $il.append($AASTORE);
                $ti := $ti + 1;
            }
            # The emitting class rides along so the descriptor table is
            # resolved from it, never from a possibly-stale tc.curFrame.
            $il.append(JAST::PushCVal.new( :value('L' ~ $*JCLASS.name ~ ';') ));
            $il.append(savesite(JAST::Instruction.new( :op('invokestatic'),
                'Lorg/raku/nqp/dispatch/Dispatch;', 'dispatchWide', 'Void',
                $TYPE_STR, 'Integer', $TYPE_TC, "[$TYPE_OBJ", 'Ljava/lang/Class;' )));
        }
        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            'result_o', $TYPE_SMO, $TYPE_CF ));
        $il.append(JAST::Instruction.new( :op('astore'), $result ));

        if @clist {
            $il.append(JAST::Instruction.new( :op('aload'), $result ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'istrue', 'Long', $TYPE_SMO, $TYPE_TC ));
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $endlabel ));
            $c_ast := nqp::pop(@clist);
            $arg_idx := get_arg_idx($c_ast);
            $atmp := $btmp;
        }
        else {
            $more := 0;
        }
    }

    $il.append($endlabel);
    $il.append(JAST::Instruction.new( :op('aload'), $result ));
    result($il, $RT_OBJ)
};
QAST::OperationsJAST.add_core_op: 'chain',       $chain_codegen;
QAST::OperationsJAST.add_core_op: 'chainstatic', $chain_codegen;

# Set of sequential statements
QAST::OperationsJAST.add_core_op('stmts', -> $qastcomp, $op {
    $qastcomp.as_jast(QAST::Stmts.new( |@($op) ))
});

# Data structures
QAST::OperationsJAST.add_core_op('list', -> $qastcomp, $op {
    # Just desugar to create the empty list.
    my $arr := $qastcomp.as_jast(QAST::Op.new(
        :op('create'),
        QAST::Op.new( :op('hlllist') )
    ));
    if +$op.list {
        # Put list into a temporary so we can push to it.
        my $il := JAST::InstructionList.new();
        $il.append($arr.jast);
        $*STACK.obtain($il, $arr);
        my $list_tmp := $*TA.fresh_o();
        $il.append(JAST::Instruction.new( :op('astore'), $list_tmp ));

        # Push things to the list.
        for $op.list {
            my $item := $qastcomp.as_jast($_, :want($RT_OBJ));
            $il.append($item.jast);
            $*STACK.obtain($il, $item);
            $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
            $il.append($SWAP);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push',
                $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_TC ));
            $il.append($POP);
        }

        $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
        result($il, $RT_OBJ);
    }
    else {
        $arr
    }
});
QAST::OperationsJAST.add_core_op('list_i', -> $qastcomp, $op {
    # Just desugar to create the empty list.
    my $arr := $qastcomp.as_jast(QAST::Op.new(
        :op('create'),
        QAST::Op.new( :op('bootintarray') )
    ));
    if +$op.list {
        # Put list into a temporary so we can push to it.
        my $il := JAST::InstructionList.new();
        $il.append($arr.jast);
        $*STACK.obtain($il, $arr);
        my $list_tmp := $*TA.fresh_o();
        $il.append(JAST::Instruction.new( :op('astore'), $list_tmp ));

        # Push things to the list.
        for $op.list {
            my $item := $qastcomp.as_jast($_, :want($RT_INT));
            $il.append($item.jast);
            $*STACK.obtain($il, $item);
            $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
            $il.append($DUP_X2);
            $il.append($POP);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append($POP2);
        }

        $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
        result($il, $RT_OBJ);
    }
    else {
        $arr
    }
});
QAST::OperationsJAST.add_core_op('list_n', -> $qastcomp, $op {
    # Just desugar to create the empty list.
    my $arr := $qastcomp.as_jast(QAST::Op.new(
        :op('create'),
        QAST::Op.new( :op('bootnumarray') )
    ));
    if +$op.list {
        # Put list into a temporary so we can push to it.
        my $il := JAST::InstructionList.new();
        $il.append($arr.jast);
        $*STACK.obtain($il, $arr);
        my $list_tmp := $*TA.fresh_o();
        $il.append(JAST::Instruction.new( :op('astore'), $list_tmp ));

        # Push things to the list.
        for $op.list {
            my $item := $qastcomp.as_jast($_, :want($RT_NUM));
            $il.append($item.jast);
            $*STACK.obtain($il, $item);
            $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
            $il.append($DUP_X2);
            $il.append($POP);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_n',
                'Double', $TYPE_SMO, 'Double', $TYPE_TC ));
            $il.append($POP2);
        }

        $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
        result($il, $RT_OBJ);
    }
    else {
        $arr
    }
});
QAST::OperationsJAST.add_core_op('list_s', -> $qastcomp, $op {
    # Just desugar to create the empty list.
    my $arr := $qastcomp.as_jast(QAST::Op.new(
        :op('create'),
        QAST::Op.new( :op('bootstrarray') )
    ));
    if +$op.list {
        # Put list into a temporary so we can push to it.
        my $il := JAST::InstructionList.new();
        $il.append($arr.jast);
        $*STACK.obtain($il, $arr);
        my $list_tmp := $*TA.fresh_o();
        $il.append(JAST::Instruction.new( :op('astore'), $list_tmp ));

        # Push things to the list.
        for $op.list {
            my $item := $qastcomp.as_jast($_, :want($RT_STR));
            $il.append($item.jast);
            $*STACK.obtain($il, $item);
            $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
            $il.append($SWAP);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_s',
                $TYPE_STR, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
            $il.append($POP);
        }

        $il.append(JAST::Instruction.new( :op('aload'), $list_tmp ));
        result($il, $RT_OBJ);
    }
    else {
        $arr
    }
});
QAST::OperationsJAST.add_core_op('intify', -> $qastcomp, $op {
    $qastcomp.as_jast($op[0], :want($RT_INT))
});
QAST::OperationsJAST.add_core_op('numify', -> $qastcomp, $op {
    $qastcomp.as_jast($op[0], :want($RT_NUM))
});
QAST::OperationsJAST.add_core_op('hash', -> $qastcomp, $op {
    # Just desugar to create the empty hash.
    my $hash := $qastcomp.as_jast(QAST::Op.new(
        :op('create'),
        QAST::Op.new( :op('hllhash') )
    ));
    if +$op.list {
        # Put hash into a temporary so we can add the items to it.
        my $il := JAST::InstructionList.new();
        $il.append($hash.jast);
        $*STACK.obtain($il, $hash);
        my $hash_tmp := $*TA.fresh_o();
        $il.append(JAST::Instruction.new( :op('astore'), $hash_tmp ));

        my $key_tmp := $*TA.fresh_s();
        my $val_tmp := $*TA.fresh_o();
        for $op.list -> $key, $val {
            my $key_res := $qastcomp.as_jast($key, :want($RT_STR));
            $il.append($key_res.jast);
            $*STACK.obtain($il, $key_res);
            $il.append(JAST::Instruction.new( :op('astore'), $key_tmp ));

            my $val_res := $qastcomp.as_jast($val, :want($RT_OBJ));
            $il.append($val_res.jast);
            $*STACK.obtain($il, $val_res);
            $il.append(JAST::Instruction.new( :op('astore'), $val_tmp ));

            $il.append(JAST::Instruction.new( :op('aload'), $hash_tmp ));
            $il.append(JAST::Instruction.new( :op('aload'), $key_tmp ));
            $il.append(JAST::Instruction.new( :op('aload'), $val_tmp ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'bindkey',
                $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_SMO, $TYPE_TC ));
            $il.append($POP);
        }

        $il.append(JAST::Instruction.new( :op('aload'), $hash_tmp ));
        result($il, $RT_OBJ);
    }
    else {
        $hash
    }
});

# Conditionals.
sub boolify_instructions($il, $cond_type) {
    if $cond_type == $RT_INT {
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
    }
    elsif $cond_type == $RT_UINT {
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
    }
    elsif $cond_type == $RT_NUM {
        $il.append($NVAL_ZERO);
        $il.append($DCMPL);
    }
    elsif $cond_type == $RT_STR {
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'istrue_s', 'Long', $TYPE_STR ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
    }
    else {
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'istrue', 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
    }
}
sub needs_cond_passed($n) {
    nqp::istype($n, QAST::Block)
    && ($n.arity > 0 || $n.ann: 'count') # slurpies would have .arity 0
    && ($n.blocktype eq 'immediate' || $n.blocktype eq 'immediate_static')
}
for <if unless with without> -> $op_name {
    my $is_withy := $op_name eq 'with' || $op_name eq 'without';
    QAST::OperationsJAST.add_core_op($op_name, -> $qastcomp, $op {
        # Check operand count.
        my $operands := +$op.list;
        nqp::die("Operation '$op_name' needs either 2 or 3 operands")
            if $operands < 2 || $operands > 3;

        # See if any immediate block wants to be passed the condition.
        my $im_then := needs_cond_passed($op[1]);
        my $im_else := needs_cond_passed($op[2]);

        # Create labels and a place to store the overall result.
        my $if_id    := $qastcomp.unique($op_name);
        my $else_lbl := JAST::Label.new(:name($if_id ~ '_else'));
        my $end_lbl  := JAST::Label.new(:name($if_id ~ '_end'));
        my $res_temp;
        my $res_type;

        # Compile conditional expression and saving of it if we need to.
        my $il := JAST::InstructionList.new();
        $*STACK.spill_to_locals($il);
        my $cond := $qastcomp.as_jast($op[0]);
        $il.append($cond.jast);
        $*STACK.obtain($il, $cond);
        my $comp_ops1;
        my $comp_ops2;
        my $orig_type_op1;
        my $orig_type_op2;
        if $im_then || $im_else {
            my $im_local := QAST::Node.unique('__IM_');
            $*BLOCK.add_local(QAST::Var.new(
                :name($im_local),
                :returns(typeobj_from_rttype($cond.type))
            ));
            if $im_then {
                $orig_type_op1 := $op[1].blocktype;
                $op[1].blocktype('declaration');
                $comp_ops1 := QAST::Op.new(
                    :op('call'), $op[1],
                    QAST::Var.new( :name($im_local), :scope('local') )
                );
            }
            if $im_else {
                $orig_type_op2 := $op[2].blocktype;
                $op[2].blocktype('declaration');
                $comp_ops2 := QAST::Op.new(
                    :op('call'), $op[2],
                    QAST::Var.new( :name($im_local), :scope('local') )
                );
            }
            $il.append(dup_ins($cond.type));
            $il.append(JAST::Instruction.new( :op(store_ins($cond.type)), $im_local ));
        }
        unless $*WANT == $RT_VOID || $operands == 3 {
            $il.append(dup_ins($cond.type));
            $res_type := $cond.type;
            $res_temp := fresh($res_type);
            $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
        }

        # Emit test.
        if $is_withy {
            $il.append($qastcomp.coercion($cond, $RT_OBJ));

            # Use 'findmethod' to detect and invoke method 'defined'.
            $il.append($DUP);
            $il.append(JAST::PushSVal.new( :value('defined') ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new(:op('invokestatic'),
                $TYPE_OPS, 'findmethod', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC));

            my $meth_temp := $*TA.fresh_o();
            my $cond_temp := $*TA.fresh_o();
            $il.append(JAST::Instruction.new( :op('astore'), $meth_temp));
            $il.append(JAST::Instruction.new( :op('astore'), $cond_temp));
            # Through lang-call, not a raw invocation: the resolved method
            # can be a multi's proto, whose {*} resumes the dispatch that
            # invoked it.
            my $cs_idx := $*CODEREFS.get_callsite_idx([$ARG_OBJ, $ARG_OBJ], []);
            my @argTypes := [$TYPE_STR, 'I', $TYPE_TC, $TYPE_SMO, $TYPE_SMO];
            $il.append(JAST::PushSVal.new( :value('lang-call') ));
            $il.append(JAST::PushIndex.new( :value($cs_idx) ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('aload'), $meth_temp ));
            $il.append(JAST::Instruction.new( :op('aload'), $cond_temp ));
            $il.append(savesite(JAST::InvokeDynamic.new(
                'dispatch_noa', 'V', @argTypes,
                'org/raku/nqp/dispatch/DispatchBootstrap', 'dispatch_noa'
            )));
            $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                'result_o', $TYPE_SMO, $TYPE_CF ));

            boolify_instructions($il, $RT_OBJ);
        }
        else {
            boolify_instructions($il, $cond.type);
        }
        $il.append(JAST::Instruction.new($else_lbl,
            :op($op_name eq 'if' || $op_name eq 'with' ?? 'ifeq' !! 'ifne')));

        # Compile the "then".
        my $then;
        if $im_then {
            $then := $qastcomp.as_jast($comp_ops1);
            $op[1].blocktype($orig_type_op1);
        }
        else {
            $then := $qastcomp.as_jast($op[1]);
        }
        $il.append($then.jast);

        # What comes next depends on whether there's an else.
        if $operands == 3 {
            # A little care needed here; we make sure we obtain the
            # result of the then, but before we actually use it we
            # compile the else branch so we can see what result type
            # is needed. It's fine as we don't append the else JAST
            # until later.
            $*STACK.obtain($il, $then);
            my $else;
            if $im_else {
                $else := $qastcomp.as_jast($comp_ops2);
                $op[2].blocktype($orig_type_op2);
            }
            else {
                $else := $qastcomp.as_jast($op[2]);
            }
            if $*WANT == $RT_VOID {
                $il.append(pop_ins($then.type));
            }
            else {
                $res_type := $then.type == $else.type ?? $then.type !! $RT_OBJ;
                $res_temp := fresh($res_type);
                $il.append($qastcomp.coercion($then, $res_type));
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
            }

            # Then branch needs to go to the loop end.
            $il.append(JAST::Instruction.new( :op('goto'), $end_lbl ));

            # Emit the else branch.
            $il.append($else_lbl);
            $il.append($else.jast);
            $*STACK.obtain($il, $else);
            if $*WANT == $RT_VOID {
                $il.append(pop_ins($else.type));
            }
            else {
                $il.append($qastcomp.coercion($else, $res_type));
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
            }
        }
        else {
            # If void context, just pop the result and we're done.
            # Otherwise, need to find a common type between it and
            # the condition.
            $*STACK.obtain($il, $then);
            if $*WANT == $RT_VOID {
                $il.append(pop_ins($then.type));
                $il.append($else_lbl);
            }
            elsif $then.type == $res_type {
                # Already have a common type.
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
                $il.append($else_lbl);
            }
            else {
                # Need a new result, and to coerce both condition and
                # result of then to it as needed (basically, add an else
                # that handles coercion).
                my $old_res_type := $res_type;
                my $old_res_temp := $res_temp;
                $res_type := $RT_OBJ;
                $res_temp := fresh($res_type);
                $il.append($qastcomp.coercion($then, $res_type));
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
                $il.append(JAST::Instruction.new( :op('goto'), $end_lbl ));
                $il.append($else_lbl);
                $il.append(JAST::Instruction.new( :op(load_ins($old_res_type)), $old_res_temp ));
                $il.append($qastcomp.coercion($cond, $res_type));
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
            }
        }

        # Add final label and load result if needed.
        $il.append($end_lbl);
        if $res_temp {
            $il.append(JAST::Instruction.new( :op(load_ins($res_type)), $res_temp ));
            result($il, $res_type);
        }
        else {
            result($il, $RT_VOID);
        }
    });
}

QAST::OperationsJAST.add_core_op('defor', -> $qastcomp, $op {
    if +$op.list != 2 {
        nqp::die("Operation 'defor' needs 2 operands");
    }
    my $tmp := $op.unique('defined');
    $qastcomp.as_jast(QAST::Stmts.new(
        QAST::Op.new(
            :op('bind'),
            QAST::Var.new( :name($tmp), :scope('local'), :decl('var') ),
            $op[0]
        ),
        QAST::Op.new(
            :op('if'),
            QAST::Op.new(
                :op('defined'),
                QAST::Var.new( :name($tmp), :scope('local') )
            ),
            QAST::Var.new( :name($tmp), :scope('local') ),
            $op[1]
        )))
});

QAST::OperationsJAST.add_core_op('xor', -> $qastcomp, $op {
    my $prefix     := $op.unique('xor');
    my $falselabel := JAST::Label.new(:name($prefix ~ '_false'));
    my $endlabel   := JAST::Label.new(:name($prefix ~ '_end'));

    my @childlist;
    my $f_ast;
    for $op.list {
        if $_.named eq 'false' {
            $f_ast := $_;
        }
        else {
            nqp::push(@childlist, $_);
        }
    }

    my $r := $*TA.fresh_o();
    my $b := $*TA.fresh_o();
    my $i := $*TA.fresh_i();
    my $t := $*TA.fresh_i();
    my $u := $*TA.fresh_i();

    my $il    := JAST::InstructionList.new();
    my $a_ast := nqp::shift(@childlist);
    my $ares := $qastcomp.as_jast($a_ast, :want($RT_OBJ));
    $il.append($ares.jast);
    $*STACK.obtain($il, $ares);
    $il.append($DUP);
    $il.append(JAST::Instruction.new( :op('astore'), $r ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'istrue', 'Long', $TYPE_SMO, $TYPE_TC ));
    $il.append(JAST::Instruction.new( :op('lstore'), $t ));

    my $have_middle_child := 1;
    my $bres;
    while $have_middle_child {
        my $b_ast := nqp::shift(@childlist);
        $bres := $qastcomp.as_jast($b_ast, :want($RT_OBJ));
        $il.append($bres.jast);
        $*STACK.obtain($il, $bres);
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('astore'), $b ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'istrue', 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($DUP2);
        $il.append(JAST::Instruction.new( :op('lstore'), $u ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('lload'), $t ));
        $il.append($L2I);
        $il.append($IAND);
        $il.append(JAST::Instruction.new( :op('ifne'), $falselabel ));

        if @childlist {
            my $truelabel := JAST::Label.new(:name($op.unique('xor_true')));
            $il.append(JAST::Instruction.new( :op('lload'), $t ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), $truelabel ));
            $il.append(JAST::Instruction.new( :op('aload'), $b ));
            $il.append(JAST::Instruction.new( :op('astore'), $r ));
            $il.append(JAST::Instruction.new( :op('lload'), $u ));
            $il.append(JAST::Instruction.new( :op('lstore'), $t ));
            $il.append($truelabel);
        }
        else {
            $have_middle_child := 0;
        }
    }

    $il.append(JAST::Instruction.new( :op('lload'), $t ));
    $il.append($L2I);
    $il.append(JAST::Instruction.new( :op('ifne'), $endlabel ));
    $il.append(JAST::Instruction.new( :op('aload'), $b ));
    $il.append(JAST::Instruction.new( :op('astore'), $r ));
    $il.append(JAST::Instruction.new( :op('goto'), $endlabel ));
    $il.append($falselabel);

    if $f_ast {
        my $fres := $qastcomp.as_jast($f_ast, :want($RT_OBJ));
        $il.append($fres.jast);
        $*STACK.obtain($il, $fres);
        $il.append(JAST::Instruction.new( :op('astore'), $r ));
    }
    else {
        $il.append($ACONST_NULL);
        $il.append(JAST::Instruction.new( :op('astore'), $r ));
    }

    $il.append($endlabel);
    $il.append(JAST::Instruction.new( :op('aload'), $r ));
    result($il, $RT_OBJ)
});

QAST::OperationsJAST.add_core_op('ifnull', -> $qastcomp, $op {
    if +$op.list != 2 {
        nqp::die("The 'ifnull' op expects two children");
    }

    # Compile the expression.
    my $il   := JAST::InstructionList.new();
    $*STACK.spill_to_locals($il);
    my $expr := $qastcomp.as_jast($op[0], :want($RT_OBJ));
    $il.append($expr.jast);

    # Emit null check.
    my $lbl := JAST::Label.new( :name($qastcomp.unique('ifnull_')) );
    $*STACK.obtain($il, $expr);
    $il.append($DUP);
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'isnull', 'Long', $TYPE_SMO));
    $il.append($IVAL_ZERO);
    $il.append($LCMP);
    $il.append(JAST::Instruction.new( :op('ifeq'), $lbl ));

    # Emit "then" part.
    $il.append($POP);
    my $then := $qastcomp.as_jast($op[1], :want($RT_OBJ));
    $il.append($then.jast);
    $*STACK.obtain($il, $then);
    $il.append($lbl);

    result($il, $RT_OBJ);
});

# Loops.
for ('', 'repeat_') -> $repness {
    for <while until> -> $op_name {
        QAST::OperationsJAST.add_core_op("$repness$op_name", -> $qastcomp, $op {
            # Check if we need a handler and operand count.
            my $handler := 1;
            my @operands;
            my $orig_type;
            my $label;
            my $im_local;
            for $op.list {
                if $_.named eq 'nohandler' { $handler := 0; }
                elsif $_.named eq 'label' { $label := $_; }
                else { @operands.push($_) }
            }
            if +@operands != 2 && +@operands != 3 {
                nqp::die("Operation '$repness$op_name' needs 2 or 3 operands");
            }

            # See if there's an immediate block wanting to be passed the condition.
            # If so, set up variable now, so that it is known if evaluation starts
            # with loop body (repeat_ variants).
            my $has_im := needs_cond_passed(@operands[1]);
            if $has_im {
                $im_local := QAST::Node.unique('__IM_');
                @operands[0] := QAST::Op.new(
                    :op('bind'),
                    QAST::Var.new( :name($im_local), :scope('local'), :decl('var') ),
                    @operands[0]);
                $orig_type := @operands[1].blocktype;
                @operands[1].blocktype('declaration');
                @operands[1] := QAST::Op.new(
                    :op('call'), @operands[1],
                    QAST::Var.new( :name($im_local), :scope('local') )
                );
            }

            # Create labels.
            my $while_id := $qastcomp.unique($op_name);
            my $test_lbl := JAST::Label.new( :name($while_id ~ '_test') );
            my $next_lbl := JAST::Label.new( :name($while_id ~ '_next') );
            my $redo_lbl := JAST::Label.new( :name($while_id ~ '_redo') );
            my $done_lbl := JAST::Label.new( :name($while_id ~ '_done') );

            # If we need handlers, produce them.
            my $l_handler_id;
            my $nr_handler_id;
            if $handler {
                $l_handler_id  := &*REGISTER_UNWIND_HANDLER($*HANDLER_IDX, $EX_CAT_LAST,                 :ex_obj(1));
                $nr_handler_id := &*REGISTER_UNWIND_HANDLER($l_handler_id, $EX_CAT_NEXT +| $EX_CAT_REDO, :ex_obj(1));
            }

            # Emit loop prelude, evaluating condition.
            my $testil := JAST::InstructionList.new();
            $*STACK.spill_to_locals($testil);
            if $repness {
                # It's a repeat_ variant, need to go straight into the
                # loop body unconditionally.
                $testil.append(JAST::Instruction.new( :op('goto'), $redo_lbl ));
            }
            $testil.append($test_lbl);
            my $cond_res := $qastcomp.as_jast_in_handler(@operands[0], $l_handler_id || $*HANDLER_IDX);
            $testil.append($cond_res.jast);
            $*STACK.obtain($testil, $cond_res);
            if $has_im {
                $testil.append(dup_ins($cond_res.type));
                $testil.append(JAST::Instruction.new( :op(store_ins($cond_res.type)), $im_local ));
            }
            my $res;
            my $res_type;
            if $*WANT != $RT_VOID {
                $res_type := $cond_res.type;
                $res := $*TA."fresh_{typechar($res_type)}"();
            }

            # If we're non-void, store the condition's evaluation as a
            # result.
            if $res {
                $testil.append(dup_ins($cond_res.type));
                $testil.append($qastcomp.coercion($cond_res, $res_type));
                $testil.append(JAST::Instruction.new( :op(store_ins($res_type)), $res ));
            }

            # Emit test.
            boolify_instructions($testil, $cond_res.type);
            $testil.append(JAST::Instruction.new($done_lbl,
                :op($op_name eq 'while' ?? 'ifeq' !! 'ifne')));

            # Emit the loop body; stash the result if needed.
            my $body_res := $qastcomp.as_jast_in_handler(@operands[1], $nr_handler_id || $*HANDLER_IDX);
            @operands[1][0].blocktype($orig_type) if $orig_type;
            my $il := JAST::InstructionList.new();
            $il.append($redo_lbl);
            my $body_il := JAST::InstructionList.new();
            $body_il.append($body_res.jast);
            $*STACK.obtain($body_il, $body_res);
            $body_il.append(pop_ins($body_res.type));

            # Add redo and next handler if needed.
            if $handler {
                my $catch := JAST::InstructionList.new();
                $qastcomp.unwind_check($catch, $nr_handler_id, :$label, :outer($l_handler_id));
                $catch.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'category', 'Long' ));
                $catch.append(JAST::PushIVal.new( :value($EX_CAT_REDO) ));
                $catch.append(JAST::Instruction.new( :op('land') ));
                $catch.append(JAST::PushIVal.new( :value($EX_CAT_REDO) ));
                $catch.append($LCMP);
                $catch.append(JAST::Instruction.new( :op('ifeq'), $redo_lbl ));
                $body_il := $qastcomp.delimit_handler(
                    JAST::TryCatch.new( :try($body_il), :$catch, :type($TYPE_EX_UNWIND) ),
                    $l_handler_id, $nr_handler_id);
            }
            $il.append($body_il);

            # If there's a third child, evaluate it as part of the
            # "next".
            if +@operands == 3 {
                my $next_res := $qastcomp.as_jast_in_handler(@operands[2],
                    $l_handler_id || $*HANDLER_IDX, :want($RT_VOID));
                $il.append($next_res.jast);
                $*STACK.obtain($il, $next_res);
            }

            # Emit the iteration jump and end label.
            $il.append(JAST::Instruction.new( :op('goto'), $test_lbl ));
            $il.append($done_lbl);

            # If needed, wrap the whole thing in a last exception handler.
            if $handler {
                my $catch := JAST::InstructionList.new();
                $qastcomp.unwind_check($catch, $l_handler_id, :$label, :outer($*HANDLER_IDX));
                $catch.append($POP);
                $il := $qastcomp.delimit_handler(
                    JAST::TryCatch.new( :try($il), :catch($catch), :type($TYPE_EX_UNWIND) ),
                    $*HANDLER_IDX, $l_handler_id);
            }

            my $res_il := JAST::InstructionList.new();
            $res_il.append($testil);
            $res_il.append($il);
            if $res {
                $res_il.append(JAST::Instruction.new( :op(load_ins($res_type)), $res ));
                result($res_il, $res_type)
            }
            else {
                result($res_il, $RT_VOID)
            }
        });
    }
}

QAST::OperationsJAST.add_core_op('for', -> $qastcomp, $op {
    my $handler := 1;
    my @operands;
    my $label;
    for $op.list {
        if $_.named eq 'nohandler' { $handler := 0; }
        elsif $_.named eq 'label' { $label := $_; }
        else { @operands.push($_) }
    }

    if +@operands != 2 {
        nqp::die("Operation 'for' needs 2 operands");
    }
    unless nqp::istype(@operands[1], QAST::Block) {
        nqp::die("Operation 'for' expects a block as its second operand");
    }
    if @operands[1].blocktype eq 'immediate' {
        @operands[1].blocktype('declaration');
    }
    elsif @operands[1].blocktype eq 'immediate_static' {
        @operands[1].blocktype('declaration_static');
    }

    # Create result temporary if we'll need one.
    my $res := $*WANT == $RT_VOID ?? 0 !! $*TA.fresh_o();

    # If we need handlers, produce them.
    my $l_handler_id;
    my $n_handler_id;
    my $r_handler_id;
    if $handler {
        $l_handler_id := &*REGISTER_UNWIND_HANDLER($*HANDLER_IDX, $EX_CAT_LAST, :ex_obj(1));
        $n_handler_id := &*REGISTER_UNWIND_HANDLER($l_handler_id, $EX_CAT_NEXT, :ex_obj(1));
        $r_handler_id := &*REGISTER_UNWIND_HANDLER($n_handler_id, $EX_CAT_REDO, :ex_obj(1));
    }

    # Evaluate the thing we'll iterate over, get the iterator and
    # store it in a temporary.
    my $il := JAST::InstructionList.new();
    $*STACK.spill_to_locals($il);
    my $list_res := $qastcomp.as_jast(@operands[0]);
    $il.append($list_res.jast);
    $*STACK.obtain($il, $list_res);
    if $res {
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('astore'), $res ));
    }
    my $iter_tmp := $*TA.fresh_o();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'iter', $TYPE_SMO, $TYPE_SMO, $TYPE_TC ));
    $il.append(JAST::Instruction.new( :op('astore'), $iter_tmp ));

    # Do similar for the block.
    my $block_res := $qastcomp.as_jast(@operands[1], :want($RT_OBJ));
    my $block_tmp := $op.unique('iterblock');
    $*BLOCK.add_local(QAST::Var.new( :name($block_tmp), :scope('local') ));
    $il.append($block_res.jast);
    $*STACK.obtain($il, $block_res);
    $il.append(JAST::Instruction.new( :op('astore'), $block_tmp ));

    # Some labels we'll need.
    my $for_id := $qastcomp.unique('for');
    my $lbl_next := JAST::Label.new( :name($for_id ~ 'next') );
    my $lbl_redo := JAST::Label.new( :name($for_id ~ 'redo') );
    my $lbl_done := JAST::Label.new( :name($for_id ~ 'done') );

    # Emit loop test.
    my $loop_il := JAST::InstructionList.new();
    $loop_il.append($lbl_next);
    $loop_il.append(JAST::Instruction.new( :op('aload'), $iter_tmp ));
    $loop_il.append($ALOAD_1);
    $loop_il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'istrue', 'Long', $TYPE_SMO, $TYPE_TC ));
    $loop_il.append($L2I);
    $loop_il.append(JAST::Instruction.new( :op('ifeq'), $lbl_done ));

    # Fetch values into temporaries (on the stack ain't enough in case
    # of redo).
    my $val_il := JAST::InstructionList.new();
    my @val_temps;
    my $arity := @operands[1].arity || 1;
    while $arity > 0 {
        my $tmp := $op.unique('itertmp');
        $*BLOCK.add_local(QAST::Var.new( :name($tmp), :scope('local') ));
        $val_il.append(JAST::Instruction.new( :op('aload'), $iter_tmp ));
        $val_il.append($ALOAD_1);
        $val_il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'shift', $TYPE_SMO, $TYPE_SMO, $TYPE_TC ));
        $val_il.append(JAST::Instruction.new( :op('astore'), $tmp ));
        nqp::push(@val_temps, $tmp);
        $arity := $arity - 1;
    }
    $val_il.append($lbl_redo);

    # Now do block invocation.
    my $inv_ast := QAST::Op.new(
        :op('call'),
        QAST::Var.new( :name($block_tmp), :scope('local') )
    );
    for @val_temps {
        $inv_ast.push(QAST::Var.new( :name($_), :scope('local') ));
    }
    my $inv_res := $qastcomp.as_jast($inv_ast, :want($RT_VOID));
    my $inv_il := JAST::InstructionList.new();
    $inv_il.append($inv_res.jast);
    $*STACK.obtain($inv_il, $inv_res);

    # Wrap block invocation in redo handler if needed.
    if $handler {
        my $catch := JAST::InstructionList.new();
        $qastcomp.unwind_check($catch, $r_handler_id, :$label, :outer($n_handler_id));
        $catch.append($POP);
        $catch.append(JAST::Instruction.new( :op('goto'), $lbl_redo ));
        $inv_il := $qastcomp.delimit_handler(
            JAST::TryCatch.new( :try($inv_il), :$catch, :type($TYPE_EX_UNWIND) ),
            $n_handler_id, $r_handler_id);
    }
    $val_il.append($inv_il);

    # Wrap value fetching and call in "next" handler if needed.
    if $handler {
        my $catch := JAST::InstructionList.new();
        $qastcomp.unwind_check($catch, $n_handler_id, :$label, :outer($l_handler_id));
        $catch.append($POP);
        $val_il := $qastcomp.delimit_handler(
            JAST::TryCatch.new( :try($val_il), :$catch, :type($TYPE_EX_UNWIND) ),
            $l_handler_id, $n_handler_id);
    }
    $loop_il.append($val_il);
    $loop_il.append(JAST::Instruction.new( :op('goto'), $lbl_next ));

    # Emit postlude, wrapping in last handler if needed.
    if $handler {
        my $catch := JAST::InstructionList.new();
        $qastcomp.unwind_check($catch, $l_handler_id, :$label, :outer($*HANDLER_IDX));
        $catch.append($POP);
        $catch.append(JAST::Instruction.new( :op('goto'), $lbl_done ));
        $loop_il := $qastcomp.delimit_handler(
            JAST::TryCatch.new( :try($loop_il), :$catch, :type($TYPE_EX_UNWIND) ),
            $*HANDLER_IDX, $l_handler_id);
    }
    $il.append($loop_il);
    $il.append($lbl_done);

    # Result, as needed.
    if $res {
        $il.append(JAST::Instruction.new( :op('aload'), $res ));
        result($il, $RT_OBJ)
    }
    else {
        result($il, $RT_VOID)
    }
});

# Calling
sub process_args_onto_stack($qastcomp, @children, $il, :$obj_first, :$inv_first, :$name_first, :$obj_second, :$str_second) {
    # Make sure we do positionals before nameds.
    my @pos;
    my @named;
    for @children {
        nqp::push(($_.named ?? @named !! @pos), $_);
    }
    my @order := @pos;
    for @named { nqp::push(@order, $_) }

    # Process the arguments, computing each of them and putting them onto the
    # stack.
    my @arg_results;
    my @arg_jtypes := [$TYPE_TC];
    my @callsite;
    my @argnames;
    my int $i := 0;
    while $i < +@order {
        my $arg_res;
        if $i == 0 && ($obj_first || $inv_first) || $i == 1 && $obj_second {
            $arg_res := $qastcomp.as_jast(@order[$i], :want($RT_OBJ));
        }
        elsif $i == 0 && $name_first {
            $arg_res := $qastcomp.as_jast(@order[$i], :want($RT_STR));
        }
        elsif $i == 1 && $str_second {
            # A method name in a dispatch capture must be a native str, the
            # way MoarVM compiles it with :want(MVM_reg_str).
            $arg_res := $qastcomp.as_jast(@order[$i], :want($RT_STR));
        }
        else {
            $arg_res := $qastcomp.as_jast(@order[$i]);
        }
        $il.append($arg_res.jast);
        nqp::push(@arg_results, $arg_res);

        my int $type := $arg_res.type;
        if $type == $RT_INT {
            nqp::push(@arg_jtypes, 'J');
        }
        elsif $type == $RT_UINT {
            nqp::push(@arg_jtypes, 'J');
        }
        elsif $type == $RT_NUM {
            nqp::push(@arg_jtypes, 'D');
        }
        else {
            nqp::push(@arg_jtypes, jtype($arg_res.type));
        }

        unless $i == 0 && ($inv_first || $name_first) {
            my int $flags := 0;
            if @order[$i].flat {
                $flags := @order[$i].named ?? 24 !! 16;
            }
            elsif @order[$i].named -> $name {
                $flags := 8;
                nqp::push(@argnames, $name);
            }
            nqp::push(@callsite, arg_type($type) + $flags);
        }

        $i++;
    }

    # Return callsite index (which may create it if needed).
    return [$*CODEREFS.get_callsite_idx(@callsite, @argnames), @arg_results, @arg_jtypes];
}
# A JVM method descriptor is capped at 255 parameter slots (longs and
# doubles take two), so a callsite wider than that cannot be an
# invokedynamic MethodType. Count the slots a dispatch would need; past the
# limit the args are packed into an Object[] by emit_wide_dispatch instead.
sub dispatch_arg_slots(@arg_results) {
    my int $slots := 3;   # ThreadContext + dispatcher name + callsite index
    for @arg_results {
        my int $t := $_.type;
        $slots := $slots + ($t == $RT_INT || $t == $RT_UINT || $t == $RT_NUM ?? 2 !! 1);
    }
    $slots
}

# The wide-callsite fallback: build the argument array directly, boxing
# natives the way the invokedynamic path's asType adapter does, and dispatch
# through a plain invokestatic with no per-instruction cache. The array is
# filled back to front because the stack tracker releases results from its
# tail; the args were already evaluated in order by process_args_onto_stack.
sub emit_wide_dispatch($il, str $dispatcher, $cs_idx, @arg_results) {
    my int $nargs := +@arg_results;
    $il.append(JAST::PushSVal.new( :value($dispatcher) ));
    $il.append(JAST::PushIndex.new( :value($cs_idx) ));
    $il.append($ALOAD_1);
    $il.append(JAST::PushIndex.new( :value($nargs) ));
    $il.append(JAST::Instruction.new( :op('anewarray'), $TYPE_OBJ ));
    my int $i := $nargs - 1;
    while $i >= 0 {
        my $res := @arg_results[$i];
        $il.append($DUP);
        $il.append(JAST::PushIndex.new( :value($i) ));
        $*STACK.obtain($il, $res);
        my int $type := $res.type;
        if $type == $RT_INT || $type == $RT_UINT {
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_LONG, 'valueOf', $TYPE_LONG, 'Long' ));
        }
        elsif $type == $RT_NUM {
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_DOUBLE, 'valueOf', $TYPE_DOUBLE, 'Double' ));
        }
        $il.append($AASTORE);
        $i := $i - 1;
    }
    $il.append(JAST::PushCVal.new( :value('L' ~ $*JCLASS.name ~ ';') ));
    $il.append(savesite(JAST::Instruction.new( :op('invokestatic'),
        'Lorg/raku/nqp/dispatch/Dispatch;', 'dispatchWide', 'Void',
        $TYPE_STR, 'Integer', $TYPE_TC, "[$TYPE_OBJ", 'Ljava/lang/Class;' )));
}

# Emit a dispatch on @args, the way the 'dispatch' op does: the dispatcher
# name and callsite index ride along as extra arguments, using the fact that
# the stack was spilled to sneak the ThreadContext in.
sub emit_dispatch($qastcomp, $node, str $dispatcher, @args, :$str_second) {
    my $il := JAST::InstructionList.new();
    my @argstuff := process_args_onto_stack($qastcomp, @args, $il, :$str_second);
    my $cs_idx := @argstuff[0];
    $*STACK.spill_to_locals($il);

    if dispatch_arg_slots(@argstuff[1]) > 250 || !$*CODEREFS.take_indy_site() {
        emit_wide_dispatch($il, $dispatcher, $cs_idx, @argstuff[1]);
        return result_from_cf($il, rttype_from_typeobj($node.returns));
    }

    nqp::unshift(@argstuff[2], 'I');
    nqp::unshift(@argstuff[2], $TYPE_STR);
    $il.append(JAST::PushSVal.new( :value($dispatcher) ));
    $il.append(JAST::PushIndex.new( :value($cs_idx) ));
    $il.append($ALOAD_1);
    $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
    $il.append(savesite(JAST::InvokeDynamic.new(
        'dispatch_noa', 'V', @argstuff[2],
        'org/raku/nqp/dispatch/DispatchBootstrap', 'dispatch_noa'
    )));

    result_from_cf($il, rttype_from_typeobj($node.returns));
}

my $call_codegen := sub ($qastcomp, $node) {
    # Calls go through the language's call dispatcher, which lang-call looks
    # up from the HLL of what is being invoked. The callee is its first
    # argument, so a named call resolves the name lexically first -- the same
    # lookup the invokedynamic call path did at its callsite. Set
    # NQP_JVM_NO_LANG_CALL to compile the old invokedynamic paths instead,
    # for chasing a dispatch bug back under its rock.
    unless nqp::getenvhash()<NQP_JVM_NO_LANG_CALL> {
        # The callee is decontainerized at the callsite, the same as MoarVM's
        # call emission: lang-call goes on the callee's type and a container
        # would defeat both the delegation and the callsite's type guard.
        my @dispatch-args := nqp::clone(@($node));
        if $node.name ne "" {
            nqp::unshift(@dispatch-args, QAST::Op.new( :op('decont'),
                QAST::Var.new( :name($node.name), :scope('lexical') ) ));
        }
        elsif nqp::elems(@dispatch-args) {
            my $callee := nqp::shift(@dispatch-args);
            $callee := QAST::Op.new( :op('decont'), $callee )
                unless nqp::istype($callee, QAST::WVal) && !nqp::iscont($callee.value);
            nqp::unshift(@dispatch-args, $callee);
        }
        else {
            nqp::die("A 'call' node must have a name or at least one child");
        }
        return emit_dispatch($qastcomp, $node, 'lang-call', @dispatch-args);
    }

    my $il := JAST::InstructionList.new();

    # If it's a direct call, then use invokedynamic to resolve the name in
    # the current lexical scope.
    if $node.name ne "" {
        # Process arguments and force them into locals.
        my @argstuff := process_args_onto_stack($qastcomp, @($node), $il);
        my $cs_idx := @argstuff[0];
        $*STACK.spill_to_locals($il);

        # Emit the call. Note, name passed as extra arg as some valid names in
        # Perl 6 are not valid method names on the JVM. We use the fact that
        # the stack was spilled to sneak the ThreadContext arg in.
        nqp::unshift(@argstuff[2], 'I');
        nqp::unshift(@argstuff[2], $TYPE_STR);
        $il.append(JAST::PushSVal.new( :value($node.name) ));
        $il.append(JAST::PushIndex.new( :value($cs_idx) ));
        $il.append($ALOAD_1);
        $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
        my $indy_meth := $node.op eq 'callstatic' ?? 'subcallstatic_noa' !! 'subcall_noa';
        $il.append(savesite(JAST::InvokeDynamic.new(
            $indy_meth, 'V', @argstuff[2],
            'org/raku/nqp/runtime/IndyBootstrap', $indy_meth
        )));
    }

    # Otherwise, it's an indirect call.
    else {
        # Ensure we have a thing to invoke.
        nqp::die("A 'call' node must have a name or at least one child") unless nqp::elems(@($node)) >= 1;

        # Process arguments, making sure first one is an object (since that is
        # the thing to invoke).
        my @argstuff := process_args_onto_stack($qastcomp, @($node), $il, :inv_first);
        my $cs_idx := @argstuff[0];
        $*STACK.spill_to_locals($il);

        # Emit the call, using the same thread context trick. The first thing
        # will be invoked.
        nqp::unshift(@argstuff[2], 'I');
        $il.append(JAST::PushIndex.new( :value($cs_idx) ));
        $il.append($ALOAD_1);
        $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
        $il.append(savesite(JAST::InvokeDynamic.new(
            'indcall_noa', 'V', @argstuff[2],
            'org/raku/nqp/runtime/IndyBootstrap', 'indcall_noa'
        )));
    }

    result_from_cf($il, rttype_from_typeobj($node.returns));
}
QAST::OperationsJAST.add_core_op('call', :!inlinable, $call_codegen);
QAST::OperationsJAST.add_core_op('callstatic', :!inlinable, $call_codegen);
my $callmethod_codegen := sub ($qastcomp, $node) {
    my $il := JAST::InstructionList.new();

    # Ensure we have an invocant.
    if nqp::elems(@($node)) == 0 {
        nqp::die("A 'callmethod' node must have at least one child");
    }
    my @children := nqp::clone(@($node));

    # lang-meth-call takes the decontainerized invocant, the method name, and
    # then the invocant again followed by the arguments: resolution drops the
    # first two and puts the method it found in their place, leaving the
    # invocant to be the method's first argument. Bind the invocant to a local
    # on the way past so the expression is evaluated once and not twice.
    unless nqp::getenvhash()<NQP_JVM_NO_LANG_CALL> {
        my @rest := nqp::clone(@children);
        my $inv  := nqp::shift(@rest);
        my $name := $node.name ne ''
            ?? QAST::SVal.new( :value($node.name) )
            !! nqp::elems(@rest)
                ?? nqp::shift(@rest)
                !! nqp::die("Method call must either supply a name or have a child node that evaluates to the name");

        my str $inv-local := QAST::Node.unique('__meth_inv');
        my @dispatch-args := [
            QAST::Op.new( :op('decont'),
                QAST::Op.new( :op('bind'),
                    QAST::Var.new( :name($inv-local), :scope('local'), :decl('var') ),
                    $inv ) ),
            $name,
            QAST::Var.new( :name($inv-local), :scope('local') )
        ];
        nqp::push(@dispatch-args, $_) for @rest;
        return emit_dispatch($qastcomp, $node, 'lang-meth-call', @dispatch-args, :str_second);
    }

    # If it's a direct call, we can get invokedynamic to do something smart
    # with guard clauses for us.
    if $node.name ne '' {
        # Process arguments and force them into locals.
        my @argstuff := process_args_onto_stack($qastcomp, @children, $il, :obj_first);
        my $cs_idx := @argstuff[0];
        $*STACK.spill_to_locals($il);

        # Emit the call. Note, name passed as extra arg as some valid names in
        # Perl 6 are not valid method names on the JVM. We use the fact that
        # the stack was spilled to sneak the ThreadContext arg in.
        nqp::unshift(@argstuff[2], 'I');
        nqp::unshift(@argstuff[2], $TYPE_STR);
        $il.append(JAST::PushSVal.new( :value($node.name) ));
        $il.append(JAST::PushIndex.new( :value($cs_idx) ));
        $il.append($ALOAD_1);
        $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
        $il.append(savesite(JAST::InvokeDynamic.new(
            'methcall_noa', 'V', @argstuff[2],
            'org/raku/nqp/runtime/IndyBootstrap', 'methcall_noa',
        )));
    }

    # Otherwise, it's indirect, and we need to resolve the method each and
    # every call. Still wire it through invokedynamic, but it can't do quite
    # so much for us.
    else {
        # Ensure we have a name, and re-arrange it to come first.
        if +@children == 1 {
            nqp::die("Method call must either supply a name or have a child node that evaluates to the name");
        }
        my $inv := nqp::shift(@children);
        my $name := nqp::shift(@children);
        nqp::unshift(@children, $inv);
        nqp::unshift(@children, $name);

        # Process arguments and force them into locals.
        my @argstuff := process_args_onto_stack($qastcomp, @children, $il, :name_first, :obj_second);
        my $cs_idx := @argstuff[0];
        $*STACK.spill_to_locals($il);

        # Emit the call.
        nqp::unshift(@argstuff[2], 'I');
        $il.append(JAST::PushIndex.new( :value($cs_idx) ));
        $il.append($ALOAD_1);
        $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
        $il.append(savesite(JAST::InvokeDynamic.new(
            'indmethcall_noa', 'V', @argstuff[2],
            'org/raku/nqp/runtime/IndyBootstrap', 'indmethcall_noa'
        )));
    }

    result_from_cf($il, rttype_from_typeobj($node.returns));
}
QAST::OperationsJAST.add_core_op('callmethod', $callmethod_codegen);

# Dispatching. All of these are sugar over a dispatch: the dispatcher to use
# is a compile-time constant, and everything else travels in the callsite, so
# one code path serves them all.
sub add_dispatcher_op($qastcomp, $op, str $prefix) {
    my @args := nqp::clone($op.list);
    if $prefix eq 'boot-syscall' {
        nqp::unshift(@args, QAST::SVal.new( :value($prefix) ));
    }
    elsif $prefix eq 'dispatcher-register' || $prefix eq 'dispatcher-delegate' {
        nqp::unshift(@args, QAST::SVal.new( :value($prefix) ));
        nqp::unshift(@args, QAST::SVal.new( :value('boot-syscall') ));
    }
    elsif $prefix eq 'dispatcher-track-' || $prefix eq 'dispatcher-guard-' {
        my $what := nqp::shift(@args);
        nqp::die("First operand of a '" ~ $op.op ~ "' op must be a constant string")
            unless nqp::istype($what, QAST::SVal);
        nqp::unshift(@args, QAST::SVal.new( :value($prefix ~ $what.value) ));
        nqp::unshift(@args, QAST::SVal.new( :value('boot-syscall') ));
    }

    my $name_qast := nqp::shift(@args);
    nqp::die('First node of a dispatch op must be a constant string naming the dispatcher')
        unless nqp::istype($name_qast, QAST::SVal);

    # Compile the arguments and form a callsite, exactly as for a call; the
    # dispatcher name and callsite index ride along as extra arguments, using
    # the fact that the stack was spilled to sneak the ThreadContext in.
    my $il := JAST::InstructionList.new();
    my @argstuff := process_args_onto_stack($qastcomp, @args, $il);
    my $cs_idx := @argstuff[0];
    $*STACK.spill_to_locals($il);

    if dispatch_arg_slots(@argstuff[1]) > 250 || !$*CODEREFS.take_indy_site() {
        emit_wide_dispatch($il, $name_qast.value, $cs_idx, @argstuff[1]);
        return result_from_cf($il, rttype_from_typeobj($op.returns));
    }

    nqp::unshift(@argstuff[2], 'I');
    nqp::unshift(@argstuff[2], $TYPE_STR);
    $il.append(JAST::PushSVal.new( :value($name_qast.value) ));
    $il.append(JAST::PushIndex.new( :value($cs_idx) ));
    $il.append($ALOAD_1);
    $*STACK.obtain($il, |@argstuff[1]) if @argstuff[1];
    $il.append(savesite(JAST::InvokeDynamic.new(
        'dispatch_noa', 'V', @argstuff[2],
        'org/raku/nqp/dispatch/DispatchBootstrap', 'dispatch_noa'
    )));

    result_from_cf($il, rttype_from_typeobj($op.returns));
}
QAST::OperationsJAST.add_core_op('dispatch', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, '');
});
QAST::OperationsJAST.add_core_op('syscall', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, 'boot-syscall');
});
QAST::OperationsJAST.add_core_op('register', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, 'dispatcher-register');
});
QAST::OperationsJAST.add_core_op('delegate', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, 'dispatcher-delegate');
});
QAST::OperationsJAST.add_core_op('track', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, 'dispatcher-track-');
});
QAST::OperationsJAST.add_core_op('guard', :!inlinable, -> $qastcomp, $op {
    add_dispatcher_op($qastcomp, $op, 'dispatcher-guard-');
});

# Binding
QAST::OperationsJAST.add_core_op('bind', -> $qastcomp, $op {
    # Sanity checks.
    my @children := $op.list;
    if +@children != 2 {
        nqp::die("A 'bind' op must have exactly two children");
    }
    unless nqp::istype(@children[0], QAST::Var) {
        nqp::die("First child of a 'bind' op must be a QAST::Var");
    }

    # Set the QAST of the thing we're to bind, then delegate to
    # the compilation of the QAST::Var to handle the rest.
    my $*BINDVAL := @children[1];
    $qastcomp.as_jast(@children[0])
});

# Exception handling/munging.
QAST::OperationsJAST.map_classlib_core_op('die_s', $TYPE_OPS, 'die_s_c', [$RT_STR], $RT_STR, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('die', $TYPE_OPS, 'die_s_c', [$RT_STR], $RT_STR, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('exception', $TYPE_OPS, 'exception', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getextype', $TYPE_OPS, 'getextype', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('setextype', $TYPE_OPS, 'setextype', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getpayload', $TYPE_OPS, 'getpayload', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setpayload', $TYPE_OPS, 'setpayload', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getmessage', $TYPE_OPS, 'getmessage', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('setmessage', $TYPE_OPS, 'setmessage', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('newexception', $TYPE_OPS, 'newexception', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('backtrace', $TYPE_OPS, 'backtrace', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('backtracestrings', $TYPE_OPS, 'backtracestrings', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('throw', $TYPE_OPS, '_throw_c', [$RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('rethrow', $TYPE_OPS, 'rethrow_c', [$RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('resume', $TYPE_OPS, 'resume', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('throwpayloadlex', $TYPE_OPS, '_throwpayloadlex_c', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc, :cont, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('throwpayloadlexcaller', $TYPE_OPS, '_throwpayloadlexcaller_c', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc, :cont, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('lastexpayload', $TYPE_OPS, 'lastexpayload', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('throwextype', $TYPE_OPS, 'throwcatdyn_c', [$RT_INT], $RT_OBJ, :tc, :cont);
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
QAST::OperationsJAST.add_core_op('handle', :!inlinable, sub ($qastcomp, $op) {
    my @children := nqp::clone($op.list());
    if @children == 0 {
        nqp::die("The 'handle' op requires at least one child");
    }

    # If there's exactly one child, then there's nothing protecting
    # it; just compile it and we're done.
    my $protected := @children.shift();
    unless @children {
        return $qastcomp.as_jast($protected);
    }

    # Otherwise, we need to install a handler block, which will
    # decide what to do by category.
    my $mask := 0;
    my $hblock := QAST::Block.new(
        QAST::Op.new(
            :op('bind'),
            QAST::Var.new( :name('__category__'), :scope('local'), :decl('var') ),
            QAST::Op.new(
                :op('getextype'),
                QAST::Op.new( :op('exception') )
            )));
    my $push_target := $hblock;
    my $handler_cares := 0;
    for @children -> $type, $handler {
        $handler_cares := 1 if $type eq 'CONTROL' || $type eq 'LABELED';
        if $type eq 'LABELED' {
            # Rethrow if a label was requested which we are not in charge for.
            $hblock.push(
                QAST::Op.new(
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
                                QAST::Op.new( :op('getpayload'), QAST::Op.new( :op('exception') ) )
                            ),
                            QAST::Op.new( :op('where'), $handler )
                        ),
                        QAST::Op.new( :op('rethrow'), QAST::Op.new( :op('exception') ) )
                    )
                )
            );
        }
        else {
            # Get the category mask.
            unless nqp::existskey(%handler_names, $type) {
                nqp::die("Invalid handler type '$type'");
            }
            my $cat_mask := %handler_names{$type};

            # Chain in this handler.
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

            # Add to mask.
            $mask := nqp::bitor_i($mask, $cat_mask);
        }
    }

    # Compile, create a lexical to put the handler in, and add it. Should
    # also force the stack to empty.
    my $name   := QAST::Node.unique('!HANDLER_');
    $*BLOCK.add_lexical(QAST::Var.new( :name($name) ));
    my $lexidx := $*BLOCK.lexical_idx($name);
    my $il     := JAST::InstructionList.new();
    $*STACK.spill_to_locals($il);
    my $hb_res := $qastcomp.as_jast($hblock, :want($RT_OBJ));
    $il.append($hb_res.jast);
    $*STACK.obtain($il, $hb_res);
    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
    $il.append(JAST::PushIndex.new( :value($lexidx) ));
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'bindlex_o', $TYPE_SMO, $TYPE_SMO, $TYPE_CF, 'Integer' ));
    $il.append($POP);

    # Register a handler.
    my $handler := &*REGISTER_BLOCK_HANDLER($*HANDLER_IDX, $mask, $lexidx);

    # Evaluate the protected code and stash it in a temporary.
    my $result := $*TA.fresh_o();
    my $prores := $qastcomp.as_jast_in_handler($protected, $handler, :want($RT_OBJ));
    my $tryil  := JAST::InstructionList.new();
    $tryil.append($prores.jast);
    $*STACK.obtain($tryil, $prores);
    $tryil.append(JAST::Instruction.new( :op('astore'), $result ));

    # Handle any runtime exceptions (Throwable) that are not ControlException.
    my $erril := JAST::InstructionList.new();
    my $nclab := JAST::Label.new( :name( $qastcomp.unique('non_cont_ex') ) );
    $erril.append($DUP);
    $erril.append(JAST::Instruction.new( :op('instanceof'), $TYPE_EX_CONT ));
    $erril.append(JAST::Instruction.new( :op('ifeq'), $nclab ));
    $erril.append($ATHROW);
    $erril.append($nclab);
    $erril.append($ALOAD_1);
    $erril.append($SWAP);
    $erril.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_EH, 'dieInternal', $TYPE_EX_RT, $TYPE_TC, $TYPE_THROWABLE ));
    $erril.append($ATHROW);

    # Inner try/catch block to catch runtime exceptions (Throwable).
    my $inner := JAST::InstructionList.new();
    $inner.append(JAST::TryCatch.new( :try($tryil), :catch($erril), :type($TYPE_THROWABLE) ));

    # The catch part just handles unwind; grab the result. Also check "exit
    # after unwind" flag, used to force this whole block to exit.
    my $catchil := JAST::InstructionList.new();
    my $exitlbl := JAST::Label.new( :name($qastcomp.unique('unwindexit')) );
    $qastcomp.unwind_check($catchil, $handler, :outer($*HANDLER_IDX), :$handler_cares);
    $catchil.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'result', $TYPE_SMO ));
    $catchil.append(JAST::Instruction.new( :op('astore'), $result ));
    $catchil.append(JAST::Instruction.new( :op('aload'), 'cf' ));
    $catchil.append(JAST::Instruction.new( :op('getfield'), $TYPE_CF, 'exitAfterUnwind', "Z" ));
    $catchil.append(JAST::Instruction.new( :op('ifeq'), $exitlbl ));
    $catchil.append(JAST::Instruction.new( :op('aload'), $result ));
    $catchil.append(JAST::Instruction.new( :op('aload'), 'cf' ));
    $catchil.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'return_o', 'Void', $TYPE_SMO, $TYPE_CF ));
    $catchil.append(JAST::Instruction.new( :op('aload'), 'cf' ));
    $catchil.append(JAST::Instruction.new( :op('invokevirtual'),
        $TYPE_CF, 'leave', 'Void' ));
    $catchil.append($RETURN);
    $catchil.append($exitlbl);

    # Wrap it all up in try/catch etc.
    $il.append($qastcomp.delimit_handler(
        JAST::TryCatch.new( :try($inner), :catch($catchil), :type($TYPE_EX_UNWIND) ),
        $*HANDLER_IDX, $handler));

    # Evaluate to the result.
    $il.append(JAST::Instruction.new( :op('aload'), $result ));
    result($il, $RT_OBJ);
});

# Simple payload handler.
QAST::OperationsJAST.add_core_op('handlepayload', :!inlinable, sub ($qastcomp, $op) {
    my @children := $op.list;
    if @children != 3 {
        nqp::die("The 'handlepayload' op requires three children");
    }
    my str $type := @children[1];
    unless nqp::existskey(%handler_names, $type) {
        nqp::die("Invalid handler type '$type'");
    }
    my int $mask := %handler_names{$type};

    # Create label and handler.
    my $hp_id := $qastcomp.unique('payload_handler');
    my $done_lbl := JAST::Label.new( :name($hp_id ~ '_done') );
    my $handler_id := &*REGISTER_UNWIND_HANDLER($*HANDLER_IDX, $mask, :ex_obj(1));

    # Spill stack ahead of emitting try/catch.
    my $il := JAST::InstructionList.new();
    $*STACK.spill_to_locals($il);

    # Compile code in protected region and stash result.
    my $result := $*TA.fresh_o();
    my $prores := $qastcomp.as_jast_in_handler(@children[0], $handler_id, :want($RT_OBJ));
    my $tryil  := JAST::InstructionList.new();
    $tryil.append($prores.jast);
    $*STACK.obtain($tryil, $prores);
    $tryil.append(JAST::Instruction.new( :op('astore'), $result ));
    $tryil.append(JAST::Instruction.new( :op('goto'), $done_lbl ));

    # Compile handler.
    my $catch := JAST::InstructionList.new();
    $qastcomp.unwind_check($catch, $handler_id, :outer($*HANDLER_IDX));
    $catch.append($POP);
    my $hanres := $qastcomp.as_jast(@children[2], :want($RT_OBJ));
    $catch.append($hanres.jast);
    $*STACK.obtain($catch, $hanres);
    $catch.append(JAST::Instruction.new( :op('astore'), $result ));

    # Wrap in try/catch.
    $il.append($qastcomp.delimit_handler(
        JAST::TryCatch.new( :try($tryil), :catch($catch), :type($TYPE_EX_UNWIND) ),
        $*HANDLER_IDX, $handler_id));
    $il.append($done_lbl);

    # Load result.
    $il.append(JAST::Instruction.new( :op('aload'), $result ));
    result($il, $RT_OBJ)
});

# Control exception throwing.
my %control_map := nqp::hash(
    'next', $EX_CAT_NEXT,
    'last', $EX_CAT_LAST,
    'redo', $EX_CAT_REDO,
    'next_label', $EX_CAT_NEXT +| $EX_CAT_LABELED,
    'last_label', $EX_CAT_LAST +| $EX_CAT_LABELED,
    'redo_label', $EX_CAT_REDO +| $EX_CAT_LABELED
);
QAST::OperationsJAST.add_core_op('control', -> $qastcomp, $op {
    my $label;
    for $op.list {
        if $_.named eq 'label' { $label := $_; }
    }
    my $name := $op.name;
    if nqp::existskey(%control_map, $name) {
        my $il := JAST::InstructionList.new();
        $*STACK.spill_to_locals($il);
        if $label {
            my $cat := %control_map{$name ~ '_label'};
            my $new_ex := $*TA.fresh_o();

            # Create a new exception object
            $il.append($ALOAD_1); # TC
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                'newexception', $TYPE_SMO, $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('astore'), $new_ex ));

            # Store the label as payload
            $il.append(JAST::Instruction.new( :op('aload'), $new_ex ));
            my $payload := $qastcomp.as_jast($label, :want($RT_OBJ));
            $il.append($payload.jast);
            $*STACK.obtain($il, $payload);
            $il.append(JAST::Instruction.new( :op('aload'), 'tc' ));
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                'setpayload', $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_TC ));
            $il.append($POP); # discard payload

            # Set exception type
            $il.append(JAST::Instruction.new( :op('aload'), $new_ex ));
            $il.append(JAST::PushIVal.new( :value($cat) ));
            $il.append(JAST::Instruction.new( :op('aload'), 'tc' ));
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                'setextype', 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append($POP2); # discard exception category

            # Throw it
            $il.append(JAST::Instruction.new( :op('aload'), $new_ex ));
            $il.append(JAST::Instruction.new( :op('aload'), 'tc' ));
            $il.append(savesite(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                '_throw_c', 'Void', $TYPE_SMO, $TYPE_TC )));
        }
        else {
            my $cat := %control_map{$name};
            $il.append(JAST::PushIVal.new( :value($cat) ));
            $il.append($ALOAD_1);
            $il.append(savesite(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                'throwcatdyn_c', 'Void', 'Long', $TYPE_TC )));
        }
        result_from_cf($il, $RT_OBJ);
    }
    else {
        nqp::die("Unknown control exception type '$name'");
    }
});

QAST::OperationsJAST.add_hll_box('nqp', $RT_INT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'bootint', $TYPE_SMO, $TYPE_TC ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'box_i', $TYPE_SMO, 'Long', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('nqp', $RT_UINT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'bootint', $TYPE_SMO, $TYPE_TC ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'box_i', $TYPE_SMO, 'Long', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('nqp', $RT_NUM, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'bootnum', $TYPE_SMO, $TYPE_TC ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'box_n', $TYPE_SMO, 'Double', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('nqp', $RT_STR, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'bootstr', $TYPE_SMO, $TYPE_TC ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'box_s', $TYPE_SMO, $TYPE_STR, $TYPE_SMO, $TYPE_TC ));
    $il
});

# Default ways to box/unbox (for no particular HLL).
# Boxing and unboxing configuration.
QAST::OperationsJAST.add_hll_box('', $RT_INT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'hllboxtype_i', $TYPE_SMO, 'Long', $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('', $RT_UINT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'hllboxtype_i', $TYPE_SMO, 'Long', $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('', $RT_NUM, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'hllboxtype_n', $TYPE_SMO, 'Double', $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_box('', $RT_STR, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'hllboxtype_s', $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_unbox('', $RT_INT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'unbox_i', 'Long', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_unbox('', $RT_UINT, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'unbox_u', 'Long', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_unbox('', $RT_NUM, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'unbox_n', 'Double', $TYPE_SMO, $TYPE_TC ));
    $il
});
QAST::OperationsJAST.add_hll_unbox('', $RT_STR, -> $qastcomp {
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
        'unbox_s', $TYPE_STR, $TYPE_SMO, $TYPE_TC ));
    $il
});

# Context introspection; note that lexpads and contents are actually the same object
# in the JVM port, which allows a little op re-use.
QAST::OperationsJAST.map_classlib_core_op('ctx', $TYPE_OPS, 'ctx', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxouter', $TYPE_OPS, 'ctxouter', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxcaller', $TYPE_OPS, 'ctxcaller', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxcode', $TYPE_OPS, 'ctxcode', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxouterskipthunks', $TYPE_OPS, 'ctxouterskipthunks', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxcallerskipthunks', $TYPE_OPS, 'ctxcallerskipthunks', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('curcode', $TYPE_OPS, 'curcode', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('callercode', $TYPE_OPS, 'callercode', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('ctxlexpad', $TYPE_OPS, 'ctxlexpad', [$RT_OBJ], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('curlexpad', $TYPE_OPS, 'ctx', [], $RT_OBJ, :tc, :!inlinable);
QAST::OperationsJAST.map_classlib_core_op('lexprimspec', $TYPE_OPS, 'lexprimspec', [$RT_OBJ, $RT_STR], $RT_INT, :tc, :!inlinable);

# Argument capture processing, for writing things like multi-dispatchers in
# high level languages.
QAST::OperationsJAST.add_core_op('usecapture', :!inlinable, -> $qastcomp, $op {
    $*NEED_ARGS_ARRAY := 1;
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
    $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'usecapture', $TYPE_SMO, $TYPE_TC, $TYPE_CSD, "[$TYPE_OBJ" ));
    result($il, $RT_OBJ)
});
QAST::OperationsJAST.add_core_op('savecapture', :!inlinable, -> $qastcomp, $op {
    $*NEED_ARGS_ARRAY := 1;
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
    $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'savecapture', $TYPE_SMO, $TYPE_TC, $TYPE_CSD, "[$TYPE_OBJ" ));
    result($il, $RT_OBJ)
});
QAST::OperationsJAST.map_classlib_core_op('captureposelems', $TYPE_OPS, 'captureposelems', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposarg', $TYPE_OPS, 'captureposarg', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposarg_i', $TYPE_OPS, 'captureposarg_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposarg_u', $TYPE_OPS, 'captureposarg_u', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposarg_n', $TYPE_OPS, 'captureposarg_n', [$RT_OBJ, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposarg_s', $TYPE_OPS, 'captureposarg_s', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureposprimspec', $TYPE_OPS, 'captureposprimspec', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureexistsnamed', $TYPE_OPS, 'captureexistsnamed', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('capturehasnameds', $TYPE_OPS, 'capturehasnameds', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('capturenamedshash', $TYPE_OPS, 'capturenamedshash', [$RT_OBJ], $RT_OBJ, :tc);

# Multiple dispatch related.
QAST::OperationsJAST.map_classlib_core_op('invokewithcapture', $TYPE_OPS, 'invokewithcapture', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multicacheadd', $TYPE_OPS, 'multicacheadd', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multicachefind', $TYPE_OPS, 'multicachefind', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

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

QAST::OperationsJAST.add_core_op('const', -> $qastcomp, $op {
    if nqp::existskey(%const_map, $op.name) {
        $qastcomp.as_jast(QAST::IVal.new( :value(%const_map{$op.name}) ))
    }
    else {
        nqp::die("Unknown constant '" ~ $op.name ~ "'");
    }
});

# Default way to do positional and associative lookups.
QAST::OperationsJAST.map_classlib_core_op('positional_get', $TYPE_OPS, 'atpos', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('positional_bind', $TYPE_OPS, 'bindpos', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('associative_get', $TYPE_OPS, 'atkey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('associative_bind', $TYPE_OPS, 'bindkey', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);

# I/O opcodes
QAST::OperationsJAST.map_classlib_core_op('print', $TYPE_OPS, 'print', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('say', $TYPE_OPS, 'say', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('stat', $TYPE_OPS, 'stat', [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('lstat', $TYPE_OPS, 'lstat', [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('stat_time', $TYPE_OPS, 'stat_time', [$RT_STR, $RT_INT], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('lstat_time', $TYPE_OPS, 'lstat_time', [$RT_STR, $RT_INT], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('open', $TYPE_OPS, 'open', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('openasync', $TYPE_OPS, 'openasync', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('spurtasync', $TYPE_OPS, 'spurtasync', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('slurpasync', $TYPE_OPS, 'slurpasync', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('linesasync', $TYPE_OPS, 'linesasync', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('readlink', $TYPE_OPS, 'readlink', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('filereadable', $TYPE_OPS, 'filereadable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('filewritable', $TYPE_OPS, 'filewritable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('fileexecutable', $TYPE_OPS, 'fileexecutable', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('fileislink', $TYPE_OPS, 'fileislink', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getstdin', $TYPE_OPS, 'getstdin', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getstdout', $TYPE_OPS, 'getstdout', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getstderr', $TYPE_OPS, 'getstderr', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('seekfh', $TYPE_OPS, 'seekfh', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('tellfh', $TYPE_OPS, 'tellfh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('lockfh', $TYPE_OPS, 'lockfh', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('unlockfh', $TYPE_OPS, 'unlockfh', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('readfh', $TYPE_OPS, 'readfh', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('writefh', $TYPE_OPS, 'writefh', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('flushfh', $TYPE_OPS, 'flushfh', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('eoffh', $TYPE_OPS, 'eoffh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('closefh', $TYPE_OPS, 'closefh', [$RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('isttyfh', $TYPE_OPS, 'isttyfh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('filenofh', $TYPE_OPS, 'filenofh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('setbuffersizefh', $TYPE_OPS, 'setbuffersizefh', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('chmod', $TYPE_OPS, 'chmod', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('chown', $TYPE_OPS, 'chown', [$RT_STR, $RT_UINT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('unlink', $TYPE_OPS, 'unlink', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('rmdir', $TYPE_OPS, 'rmdir', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('cwd', $TYPE_OPS, 'cwd', [], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('chdir', $TYPE_OPS, 'chdir', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('mkdir', $TYPE_OPS, 'mkdir', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('rename', $TYPE_OPS, 'rename', [$RT_STR, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('copy', $TYPE_OPS, 'copy', [$RT_STR, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('link', $TYPE_OPS, 'link', [$RT_STR, $RT_STR], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('gethostname', $TYPE_OPS, 'gethostname', [], $RT_STR);

QAST::OperationsJAST.map_classlib_core_op('symlink', $TYPE_OPS, 'symlink', [$RT_STR, $RT_STR], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('opendir', $TYPE_OPS, 'opendir', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nextfiledir', $TYPE_OPS, 'nextfiledir', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('closedir', $TYPE_OPS, 'closedir', [$RT_OBJ], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('socket', $TYPE_OPS, 'socket', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('connect', $TYPE_OPS, 'connect', [$RT_OBJ, $RT_STR, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindsock', $TYPE_OPS, 'bindsock', [$RT_OBJ, $RT_STR, $RT_INT, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('accept', $TYPE_OPS, 'accept', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getport', $TYPE_OPS, 'getport', [$RT_OBJ], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('debugnoop', $TYPE_OPS, 'debugnoop', [$RT_OBJ], $RT_OBJ, :tc);

# terms
QAST::OperationsJAST.map_classlib_core_op('time', $TYPE_OPS, 'time', [], $RT_INT);

# Arithmetic ops
QAST::OperationsJAST.map_jvm_core_op('add_i', 'ladd', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('add_I', $TYPE_OPS, 'add_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_jvm_core_op('add_n', 'dadd', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_jvm_core_op('sub_i', 'lsub', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('sub_I', $TYPE_OPS, 'sub_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_jvm_core_op('sub_n', 'dsub', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_jvm_core_op('mul_i', 'lmul', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('mul_I', $TYPE_OPS, 'mul_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_jvm_core_op('mul_n', 'dmul', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('div_i', $TYPE_OPS, 'div_i', [$RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('div_I', $TYPE_OPS, 'div_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('div_In', $TYPE_OPS, 'div_In', [$RT_OBJ, $RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_jvm_core_op('div_n', 'ddiv', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_jvm_core_op('mod_i', 'lrem', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('mod_I', $TYPE_OPS, 'mod_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('expmod_I', $TYPE_OPS, 'expmod_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('isprime_I', $TYPE_OPS, 'isprime_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('rand_n', $TYPE_OPS, 'rand_n', [$RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('srand', $TYPE_OPS, 'srand', [$RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('rand_I', $TYPE_OPS, 'rand_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('mod_n', $TYPE_OPS, 'mod_n', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('pow_i', $TYPE_OPS, 'pow_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('pow_n', $TYPE_OPS, 'pow_n', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('pow_I', $TYPE_OPS, 'pow_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_jvm_core_op('neg_i', 'lneg', [$RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('neg_I', $TYPE_OPS, 'neg_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_jvm_core_op('neg_n', 'dneg', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('abs_i', $TYPE_MATH, 'abs', [$RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('abs_I', $TYPE_OPS, 'abs_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('abs_n', $TYPE_MATH, 'abs', [$RT_NUM], $RT_NUM);

QAST::OperationsJAST.map_classlib_core_op('ceil_n', $TYPE_MATH, 'ceil', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('floor_n', $TYPE_MATH, 'floor', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('sqrt_n', $TYPE_MATH, 'sqrt', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('log_n', $TYPE_MATH, 'log', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('exp_n', $TYPE_MATH, 'exp', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('isnanorinf', $TYPE_OPS, 'isnanorinf', [$RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('inf', $TYPE_OPS, 'inf', [], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('neginf', $TYPE_OPS, 'neginf', [], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('nan', $TYPE_OPS, 'nan', [], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('radix', $TYPE_OPS, 'radix', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('radix_I', $TYPE_OPS, 'radix_I', [$RT_INT, $RT_STR, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

# trig opcodes
QAST::OperationsJAST.map_classlib_core_op('sin_n', $TYPE_MATH, 'sin', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('asin_n', $TYPE_MATH, 'asin', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('cos_n', $TYPE_MATH, 'cos', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('acos_n', $TYPE_MATH, 'acos', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('tan_n', $TYPE_MATH, 'tan', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('atan_n', $TYPE_MATH, 'atan', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('atan2_n', $TYPE_MATH, 'atan2', [$RT_NUM, $RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('sinh_n', $TYPE_MATH, 'sinh', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('cosh_n', $TYPE_MATH, 'cosh', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('tanh_n', $TYPE_MATH, 'tanh', [$RT_NUM], $RT_NUM);

# esoteric math opcodes
QAST::OperationsJAST.map_classlib_core_op('gcd_i', $TYPE_OPS, 'gcd_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('gcd_I', $TYPE_OPS, 'gcd_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('lcm_i', $TYPE_OPS, 'lcm_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('lcm_I', $TYPE_OPS, 'lcm_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# string bitwise ops
QAST::OperationsJAST.map_classlib_core_op('bitor_s', $TYPE_OPS, 'bitor_s', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('bitxor_s', $TYPE_OPS, 'bitxor_s', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('bitand_s', $TYPE_OPS, 'bitand_s', [$RT_STR, $RT_STR], $RT_STR);

# string opcodes
QAST::OperationsJAST.map_classlib_core_op('chars', $TYPE_OPS, 'chars', [$RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('uc', $TYPE_OPS, 'uc', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('lc', $TYPE_OPS, 'lc', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('tc', $TYPE_OPS, 'tc', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('tclc', $TYPE_OPS, 'tclc', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('fc', $TYPE_OPS, 'lc', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('x', $TYPE_OPS, 'x', [$RT_STR, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('iscclass', $TYPE_OPS, 'iscclass', [$RT_INT, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('concat', $TYPE_OPS, 'concat', [$RT_STR, $RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('chr', $TYPE_OPS, 'chr', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('join', $TYPE_OPS, 'join', [$RT_STR, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('split', $TYPE_OPS, 'split', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('findcclass', $TYPE_OPS, 'findcclass', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('findnotcclass', $TYPE_OPS, 'findnotcclass', [$RT_INT, $RT_STR, $RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.add_core_op('sprintf', -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(
        QAST::Op.new(
            :op('call'),
            :returns(str),
            QAST::Op.new(
                :op('gethllsym'),
                QAST::SVal.new( :value('nqp') ),
                QAST::SVal.new( :value('sprintf') )
            ),
            |@operands )
    );
});
QAST::OperationsJAST.add_core_op('sprintfdirectives', -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(
        QAST::Op.new(
            :op('call'),
            :returns(int),
            QAST::Op.new(
                :op('gethllsym'),
                QAST::SVal.new( :value('nqp') ),
                QAST::SVal.new( :value('sprintfdirectives') )
            ),
            |@operands )
    );
});
QAST::OperationsJAST.add_core_op('sprintfaddargumenthandler', -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(
        QAST::Op.new(
            :op('call'),
            :returns(str),
            QAST::Op.new(
                :op('gethllsym'),
                QAST::SVal.new( :value('nqp') ),
                QAST::SVal.new( :value('sprintfaddargumenthandler') )
            ),
            |@operands )
    );
});
QAST::OperationsJAST.map_classlib_core_op('escape', $TYPE_OPS, 'escape', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('flip', $TYPE_OPS, 'flip', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('replace', $TYPE_OPS, 'replace', [$RT_STR, $RT_INT, $RT_INT, $RT_STR], $RT_STR);

QAST::OperationsJAST.add_core_op('indexingoptimized', -> $qastcomp, $op {
    $qastcomp.as_jast($op[0], :want($RT_STR));
});

# substr can take 2 or 3 args, so needs special handling.
QAST::OperationsJAST.map_classlib_core_op('substr2', $TYPE_OPS, 'substr2', [$RT_STR, $RT_INT], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('substr3', $TYPE_OPS, 'substr3', [$RT_STR, $RT_INT, $RT_INT], $RT_STR);
QAST::OperationsJAST.add_core_op('substr', -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(+@operands == 2
        ?? QAST::Op.new( :op('substr2'), |@operands )
        !! QAST::Op.new( :op('substr3'), |@operands ));
});

QAST::OperationsJAST.map_classlib_core_op('eqat', $TYPE_OPS, 'eqat', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('eqatic', $TYPE_OPS, 'eqatic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('eqatim', $TYPE_OPS, 'eqatim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('eqaticim', $TYPE_OPS, 'eqaticim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('eqatic', $TYPE_OPS, 'eqatic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
# ord can be on a the first char in a string or at a particular char.
QAST::OperationsJAST.map_classlib_core_op('ordfirst', $TYPE_OPS, 'ordfirst', [$RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('ordat',    $TYPE_OPS, 'ordat',    [$RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.add_core_op('ord',  -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(+@operands == 1
        ?? QAST::Op.new( :op('ordfirst'), |@operands )
        !! QAST::Op.new( :op('ordat'), |@operands ));
});
QAST::OperationsJAST.map_classlib_core_op('ordbaseat', $TYPE_OPS, 'ordbaseat', [$RT_STR, $RT_INT], $RT_INT);

# index may or may not take a starting position
QAST::OperationsJAST.map_classlib_core_op('indexfrom', $TYPE_OPS, 'indexfrom', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.add_core_op('index',  -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(+@operands == 2
        ?? QAST::Op.new( :op('indexfrom'), |@operands, QAST::IVal.new( :value(0)) )
        !! QAST::Op.new( :op('indexfrom'), |@operands ));
});

QAST::OperationsJAST.map_classlib_core_op('indexic', $TYPE_OPS, 'indexic', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('indexim', $TYPE_OPS, 'indexim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('indexicim', $TYPE_OPS, 'indexicim', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);

# rindex may or may not take a starting position
QAST::OperationsJAST.map_classlib_core_op('rindexfromend', $TYPE_OPS, 'rindexfromend', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('rindexfrom', $TYPE_OPS, 'rindexfrom', [$RT_STR, $RT_STR, $RT_INT], $RT_INT);
QAST::OperationsJAST.add_core_op('rindex',  -> $qastcomp, $op {
    my @operands := $op.list;
    $qastcomp.as_jast(+@operands == 2
        ?? QAST::Op.new( :op('rindexfromend'), |@operands )
        !! QAST::Op.new( :op('rindexfrom'), |@operands ));
});

QAST::OperationsJAST.map_classlib_core_op('strfromcodes', $TYPE_OPS, 'strfromcodes', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('strtocodes', $TYPE_OPS, 'strtocodes', [$RT_STR, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('normalizecodes', $TYPE_OPS, 'normalizecodes', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('codes', $TYPE_OPS, 'codes', [$RT_STR], $RT_INT);

QAST::OperationsJAST.map_classlib_core_op('codepointfromname', $TYPE_OPS, 'codepointfromname', [$RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('strfromname', $TYPE_OPS, 'strfromname', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('encode', $TYPE_OPS, 'encode', [$RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('encoderep', $TYPE_OPS, 'encoderep', [$RT_STR, $RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('decode', $TYPE_OPS, 'decode', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decoderconfigure', $TYPE_OPS, 'decoderconfigure', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodersetlineseps', $TYPE_OPS, 'decodersetlineseps', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('decoderaddbytes', $TYPE_OPS, 'decoderaddbytes', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakechars', $TYPE_OPS, 'decodertakechars', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakecharseof', $TYPE_OPS, 'decodertakecharseof', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakeallchars', $TYPE_OPS, 'decodertakeallchars', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakeavailablechars', $TYPE_OPS, 'decodertakeavailablechars', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakeline', $TYPE_OPS, 'decodertakeline', [$RT_OBJ, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('decoderbytesavailable', $TYPE_OPS, 'decoderbytesavailable', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('decodertakebytes', $TYPE_OPS, 'decodertakebytes', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('decoderempty', $TYPE_OPS, 'decoderempty', [$RT_OBJ], $RT_INT, :tc);

# serialization context opcodes
QAST::OperationsJAST.map_classlib_core_op('sha1', $TYPE_OPS, 'sha1', [$RT_STR], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('createsc', $TYPE_OPS, 'createsc', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scsetobj', $TYPE_OPS, 'scsetobj', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scsetcode', $TYPE_OPS, 'scsetcode', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scgetobj', $TYPE_OPS, 'scgetobj', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scgethandle', $TYPE_OPS, 'scgethandle', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('scgetdesc', $TYPE_OPS, 'scgetdesc', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('scgetobjidx', $TYPE_OPS, 'scgetobjidx', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('scsetdesc', $TYPE_OPS, 'scsetdesc', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('scobjcount', $TYPE_OPS, 'scobjcount', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('setobjsc', $TYPE_OPS, 'setobjsc', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getobjsc', $TYPE_OPS, 'getobjsc', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('serialize', $TYPE_OPS, 'serialize', [$RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('serializetobuf', $TYPE_OPS, 'serializetobuf', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('deserialize', $TYPE_OPS, 'deserialize', [$RT_STR, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('wval', $TYPE_OPS, 'wval', [$RT_STR, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scwbdisable', $TYPE_OPS, 'scwbdisable', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('scwbenable', $TYPE_OPS, 'scwbenable', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('pushcompsc', $TYPE_OPS, 'pushcompsc', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('popcompsc', $TYPE_OPS, 'popcompsc', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('neverrepossess', $TYPE_OPS, 'neverrepossess', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('scdisclaim', $TYPE_OPS, 'scdisclaim', [$RT_OBJ], $RT_OBJ, :tc);

# bitwise opcodes
QAST::OperationsJAST.map_classlib_core_op('bitor_i', $TYPE_OPS, 'bitor_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitor_I', $TYPE_OPS, 'bitor_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bitxor_i', $TYPE_OPS, 'bitxor_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitxor_I', $TYPE_OPS, 'bitxor_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bitand_i', $TYPE_OPS, 'bitand_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitand_I', $TYPE_OPS, 'bitand_I', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bitneg_i', $TYPE_OPS, 'bitneg_i', [$RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitneg_u', $TYPE_OPS, 'bitneg_i', [$RT_UINT], $RT_UINT);
QAST::OperationsJAST.map_classlib_core_op('bitneg_I', $TYPE_OPS, 'bitneg_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bitshiftl_i', $TYPE_OPS, 'bitshiftl_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitshiftl_I', $TYPE_OPS, 'bitshiftl_I', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bitshiftr_i', $TYPE_OPS, 'bitshiftr_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('bitshiftr_I', $TYPE_OPS, 'bitshiftr_I', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);

# relational opcodes
QAST::OperationsJAST.map_classlib_core_op('cmp_i',  $TYPE_OPS, 'cmp_i',  [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iseq_i', $TYPE_OPS, 'iseq_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isne_i', $TYPE_OPS, 'isne_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('islt_i', $TYPE_OPS, 'islt_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isle_i', $TYPE_OPS, 'isle_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isgt_i', $TYPE_OPS, 'isgt_i', [$RT_INT, $RT_INT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isge_i', $TYPE_OPS, 'isge_i', [$RT_INT, $RT_INT], $RT_INT);

QAST::OperationsJAST.map_classlib_core_op('cmp_u',  $TYPE_OPS, 'cmp_u',  [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iseq_u', $TYPE_OPS, 'iseq_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isne_u', $TYPE_OPS, 'isne_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('islt_u', $TYPE_OPS, 'islt_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isle_u', $TYPE_OPS, 'isle_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isgt_u', $TYPE_OPS, 'isgt_u', [$RT_UINT, $RT_UINT], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isge_u', $TYPE_OPS, 'isge_u', [$RT_UINT, $RT_UINT], $RT_INT);

QAST::OperationsJAST.map_classlib_core_op('bool_I', $TYPE_OPS, 'bool_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('cmp_I', $TYPE_OPS, 'cmp_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('iseq_I', $TYPE_OPS, 'iseq_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isne_I', $TYPE_OPS, 'isne_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('islt_I', $TYPE_OPS, 'islt_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isle_I', $TYPE_OPS, 'isle_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isgt_I', $TYPE_OPS, 'isgt_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isge_I', $TYPE_OPS, 'isge_I', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('cmp_n',  $TYPE_OPS, 'cmp_n',  [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iseq_n', $TYPE_OPS, 'iseq_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isne_n', $TYPE_OPS, 'isne_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('islt_n', $TYPE_OPS, 'islt_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isle_n', $TYPE_OPS, 'isle_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isgt_n', $TYPE_OPS, 'isgt_n', [$RT_NUM, $RT_NUM], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isge_n', $TYPE_OPS, 'isge_n', [$RT_NUM, $RT_NUM], $RT_INT);

QAST::OperationsJAST.map_classlib_core_op('cmp_s',  $TYPE_OPS, 'cmp_s',  [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iseq_s', $TYPE_OPS, 'iseq_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isne_s', $TYPE_OPS, 'isne_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('islt_s', $TYPE_OPS, 'islt_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isle_s', $TYPE_OPS, 'isle_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isgt_s', $TYPE_OPS, 'isgt_s', [$RT_STR, $RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isge_s', $TYPE_OPS, 'isge_s', [$RT_STR, $RT_STR], $RT_INT);

# bigint ops
QAST::OperationsJAST.map_classlib_core_op('fromstr_I', $TYPE_OPS, 'fromstr_I', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('tostr_I', $TYPE_OPS, 'tostr_I', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('base_I', $TYPE_OPS, 'base_I', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('isbig_I', $TYPE_OPS, 'isbig_I', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('fromnum_I', $TYPE_OPS, 'fromnum_I', [$RT_NUM, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('tonum_I', $TYPE_OPS, 'tonum_I', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('fromI_I', $TYPE_OPS, 'fromI_I', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# boolean opcodes
QAST::OperationsJAST.map_classlib_core_op('not_i', $TYPE_OPS, 'not_i', [$RT_INT], $RT_INT);

# aggregate opcodes
QAST::OperationsJAST.map_classlib_core_op('atpos', $TYPE_OPS, 'atpos', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos_i', $TYPE_OPS, 'atpos_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos_u', $TYPE_OPS, 'atpos_u', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos_n', $TYPE_OPS, 'atpos_n', [$RT_OBJ, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos_s', $TYPE_OPS, 'atpos_s', [$RT_OBJ, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposref_i', $TYPE_OPS, 'atposref_i', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposref_u', $TYPE_OPS, 'atposref_u', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposref_n', $TYPE_OPS, 'atposref_n', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposref_s', $TYPE_OPS, 'atposref_s', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos2d', $TYPE_OPS, 'atpos2d_o', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos2d_i', $TYPE_OPS, 'atpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos2d_u', $TYPE_OPS, 'atpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos2d_n', $TYPE_OPS, 'atpos2d_n', [$RT_OBJ, $RT_INT, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos2d_s', $TYPE_OPS, 'atpos2d_s', [$RT_OBJ, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos3d', $TYPE_OPS, 'atpos3d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos3d_i', $TYPE_OPS, 'atpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos3d_u', $TYPE_OPS, 'atpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos3d_n', $TYPE_OPS, 'atpos3d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('atpos3d_s', $TYPE_OPS, 'atpos3d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposnd', $TYPE_OPS, 'atposnd_o', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposnd_i', $TYPE_OPS, 'atposnd_i', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposnd_u', $TYPE_OPS, 'atposnd_i', [$RT_OBJ, $RT_OBJ], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposnd_n', $TYPE_OPS, 'atposnd_n', [$RT_OBJ, $RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('atposnd_s', $TYPE_OPS, 'atposnd_s', [$RT_OBJ, $RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('atkey', $TYPE_OPS, 'atkey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atkey_i', $TYPE_OPS, 'atkey_i', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atkey_n', $TYPE_OPS, 'atkey_n', [$RT_OBJ, $RT_STR], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('atkey_s', $TYPE_OPS, 'atkey_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos', $TYPE_OPS, 'bindpos', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos_i', $TYPE_OPS, 'bindpos_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos_u', $TYPE_OPS, 'bindpos_u', [$RT_OBJ, $RT_INT, $RT_UINT], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos_n', $TYPE_OPS, 'bindpos_n', [$RT_OBJ, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos_s', $TYPE_OPS, 'bindpos_s', [$RT_OBJ, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos2d', $TYPE_OPS, 'bindpos2d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos2d_i', $TYPE_OPS, 'bindpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos2d_u', $TYPE_OPS, 'bindpos2d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos2d_n', $TYPE_OPS, 'bindpos2d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos2d_s', $TYPE_OPS, 'bindpos2d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos3d', $TYPE_OPS, 'bindpos3d_o', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos3d_i', $TYPE_OPS, 'bindpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos3d_u', $TYPE_OPS, 'bindpos3d_i', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos3d_n', $TYPE_OPS, 'bindpos3d_n', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindpos3d_s', $TYPE_OPS, 'bindpos3d_s', [$RT_OBJ, $RT_INT, $RT_INT, $RT_INT, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindposnd', $TYPE_OPS, 'bindposnd_o', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multidimref_i', $TYPE_OPS, 'multidimref_i', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multidimref_u', $TYPE_OPS, 'multidimref_u', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multidimref_n', $TYPE_OPS, 'multidimref_n', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('multidimref_s', $TYPE_OPS, 'multidimref_s', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindposnd_i', $TYPE_OPS, 'bindposnd_i', [$RT_OBJ, $RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindposnd_u', $TYPE_OPS, 'bindposnd_i', [$RT_OBJ, $RT_OBJ, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindposnd_n', $TYPE_OPS, 'bindposnd_n', [$RT_OBJ, $RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindposnd_s', $TYPE_OPS, 'bindposnd_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindkey', $TYPE_OPS, 'bindkey', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindkey_i', $TYPE_OPS, 'bindkey_i', [$RT_OBJ, $RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindkey_n', $TYPE_OPS, 'bindkey_n', [$RT_OBJ, $RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindkey_s', $TYPE_OPS, 'bindkey_s', [$RT_OBJ, $RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('existspos', $TYPE_OPS, 'existspos', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('existskey', $TYPE_OPS, 'existskey', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('deletekey', $TYPE_OPS, 'deletekey', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('elems', $TYPE_OPS, 'elems', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('setelems', $TYPE_OPS, 'setelems', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('dimensions', $TYPE_OPS, 'dimensions', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setdimensions', $TYPE_OPS, 'setdimensions', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('numdimensions', $TYPE_OPS, 'numdimensions', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('push', $TYPE_OPS, 'push', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('push_i', $TYPE_OPS, 'push_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('push_n', $TYPE_OPS, 'push_n', [$RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('push_s', $TYPE_OPS, 'push_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('pop', $TYPE_OPS, 'pop', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('pop_i', $TYPE_OPS, 'pop_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('pop_n', $TYPE_OPS, 'pop_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('pop_s', $TYPE_OPS, 'pop_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('unshift', $TYPE_OPS, 'unshift', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('unshift_i', $TYPE_OPS, 'unshift_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('unshift_n', $TYPE_OPS, 'unshift_n', [$RT_OBJ, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('unshift_s', $TYPE_OPS, 'unshift_s', [$RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('shift', $TYPE_OPS, 'shift', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('shift_i', $TYPE_OPS, 'shift_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('shift_n', $TYPE_OPS, 'shift_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('shift_s', $TYPE_OPS, 'shift_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('slice', $TYPE_OPS, 'slice', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('splice', $TYPE_OPS, 'splice', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('isint', $TYPE_OPS, 'isint', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isnum', $TYPE_OPS, 'isnum', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isstr', $TYPE_OPS, 'isstr', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('islist', $TYPE_OPS, 'islist', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('ishash', $TYPE_OPS, 'ishash', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('iterator', $TYPE_OPS, 'iter', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('iterkey_s', $TYPE_OPS, 'iterkey_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('iterval', $TYPE_OPS, 'iterval', [$RT_OBJ], $RT_OBJ, :tc);

(-> {
# object opcodes
QAST::OperationsJAST.map_jvm_core_op('null_s', 'aconst_null', [], $RT_STR);
QAST::OperationsJAST.map_classlib_core_op('what', $TYPE_OPS, 'what', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('how', $TYPE_OPS, 'how', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('who', $TYPE_OPS, 'who', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('where', $TYPE_OPS, 'where', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('objectid', $TYPE_OPS, 'where', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('findmethod', $TYPE_OPS, 'findmethod', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('tryfindmethod', $TYPE_OPS, 'findmethodNonFatal', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setwho', $TYPE_OPS, 'setwho', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('rebless', $TYPE_OPS, 'rebless', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('knowhow', $TYPE_OPS, 'knowhow', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('knowhowattr', $TYPE_OPS, 'knowhowattr', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootint', $TYPE_OPS, 'bootint', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootnum', $TYPE_OPS, 'bootnum', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootstr', $TYPE_OPS, 'bootstr', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootarray', $TYPE_OPS, 'bootarray', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootintarray', $TYPE_OPS, 'bootintarray', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootnumarray', $TYPE_OPS, 'bootnumarray', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bootstrarray', $TYPE_OPS, 'bootstrarray', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('boothash', $TYPE_OPS, 'boothash', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hlllist', $TYPE_OPS, 'hlllist', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllhash', $TYPE_OPS, 'hllhash', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('create', $TYPE_OPS, 'create', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('clone', $TYPE_OPS, 'clone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('isconcrete', $TYPE_OPS, 'isconcrete', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('null', $TYPE_OPS, 'createNull', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('isnull', $TYPE_OPS, 'isnull', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isnull_s', $TYPE_OPS, 'isnull_s', [$RT_STR], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('istrue', $TYPE_OPS, 'istrue', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isfalse', $TYPE_OPS, 'isfalse', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('istype', $TYPE_OPS, 'istype', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('eqaddr', $TYPE_OPS, 'eqaddr', [$RT_OBJ, $RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('getattr', $TYPE_OPS, 'getattr', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattr_i', $TYPE_OPS, 'getattr_i', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattr_u', $TYPE_OPS, 'getattr_u', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattr_n', $TYPE_OPS, 'getattr_n', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattr_s', $TYPE_OPS, 'getattr_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattrref_i', $TYPE_OPS, 'getattrref_i', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattrref_u', $TYPE_OPS, 'getattrref_u', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattrref_n', $TYPE_OPS, 'getattrref_n', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getattrref_s', $TYPE_OPS, 'getattrref_s', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindattr', $TYPE_OPS, 'bindattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindattr_i', $TYPE_OPS, 'bindattr_i', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindattr_u', $TYPE_OPS, 'bindattr_u', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_UINT], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindattr_n', $TYPE_OPS, 'bindattr_n', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindattr_s', $TYPE_OPS, 'bindattr_s', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('attrinited', $TYPE_OPS, 'attrinited', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('attrhintfor', $TYPE_OPS, 'attrhintfor', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('unbox_i', $TYPE_OPS, 'unbox_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('unbox_u', $TYPE_OPS, 'unbox_u', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('unbox_n', $TYPE_OPS, 'unbox_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('unbox_s', $TYPE_OPS, 'unbox_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('box_i', $TYPE_OPS, 'box_i', [$RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('box_u', $TYPE_OPS, 'box_u', [$RT_UINT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('box_n', $TYPE_OPS, 'box_n', [$RT_NUM, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('box_s', $TYPE_OPS, 'box_s', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllboxtype_i', $TYPE_OPS, 'hllboxtype_i', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllboxtype_n', $TYPE_OPS, 'hllboxtype_n', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllboxtype_s', $TYPE_OPS, 'hllboxtype_s', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('can', $TYPE_OPS, 'can', [$RT_OBJ, $RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('reprname', $TYPE_OPS, 'reprname', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('newtype', $TYPE_OPS, 'newtype', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('newmixintype', $TYPE_OPS, 'newtype', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('composetype', $TYPE_OPS, 'composetype', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setboolspec', $TYPE_OPS, 'setboolspec', [$RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setmethcache', $TYPE_OPS, 'setmethcache', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setmethcacheauth', $TYPE_OPS, 'setmethcacheauth', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('settypecache', $TYPE_OPS, 'settypecache', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('settypecheckmode', $TYPE_OPS, 'settypecheckmode', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.add_core_op('settypefinalize', -> $qastcomp, $op {
    # XXX Needs internals work to have this wire things up to JVM's finalize support.
    $qastcomp.as_jast($op[0])
});
QAST::OperationsJAST.map_classlib_core_op('objprimspec', $TYPE_OPS, 'objprimspec', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('objprimunsigned', $TYPE_OPS, 'objprimunsigned', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('objprimbits', $TYPE_OPS, 'objprimbits', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('isinvokable', $TYPE_OPS, 'isinvokable', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('iscoderef', $TYPE_OPS, 'iscoderef', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('gettypehllrole', $TYPE_OPS, 'gettypehllrole', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('assertparamcheck', $TYPE_OPS, 'assertparamcheck', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindcomplete', $TYPE_OPS, 'bindcomplete', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setinvokespec', $TYPE_OPS, 'setinvokespec', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setparameterizer', $TYPE_OPS, 'setparameterizer', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('parameterizetype', $TYPE_OPS, 'parameterizetype', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('typeparameterized', $TYPE_OPS, 'typeparameterized', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('typeparameters', $TYPE_OPS, 'typeparameters', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('typeparameterat', $TYPE_OPS, 'typeparameterat', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('setdebugtypename', $TYPE_OPS, 'setdebugtypename', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
})();

# defined - overridden by HLL, but by default same as .DEFINITE.
QAST::OperationsJAST.map_classlib_core_op('defined', $TYPE_OPS, 'isconcrete', [$RT_OBJ], $RT_INT, :tc);

# object ops that don't do the usual decontainerization
QAST::OperationsJAST.map_classlib_core_op('what_nd', $TYPE_OPS, 'what_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('how_nd', $TYPE_OPS, 'how_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('clone_nd', $TYPE_OPS, 'clone_nd', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('isconcrete_nd', $TYPE_OPS, 'isconcrete_nd', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('istype_nd', $TYPE_OPS, 'istype_nd', [$RT_OBJ, $RT_OBJ], $RT_INT, :tc);

# container related
QAST::OperationsJAST.map_classlib_core_op('setcontspec', $TYPE_OPS, 'setcontspec', [$RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('iscont', $TYPE_OPS, 'iscont', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iscont_i', $TYPE_OPS, 'iscont_i', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iscont_u', $TYPE_OPS, 'iscont_u', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iscont_n', $TYPE_OPS, 'iscont_n', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('iscont_s', $TYPE_OPS, 'iscont_s', [$RT_OBJ], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('isrwcont', $TYPE_OPS, 'isrwcont', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('decont', $TYPE_OPS, 'decont', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.add_core_op('wantdecont', -> $qastcomp, $op {
    # Currently, no optimization for this on JVM backend.
    $qastcomp.as_jast($op[0])
});
QAST::OperationsJAST.map_classlib_core_op('decont_i', $TYPE_OPS, 'decont_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('decont_u', $TYPE_OPS, 'decont_u', [$RT_OBJ], $RT_UINT, :tc);
QAST::OperationsJAST.map_classlib_core_op('decont_n', $TYPE_OPS, 'decont_n', [$RT_OBJ], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('decont_s', $TYPE_OPS, 'decont_s', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('assign', $TYPE_OPS, 'assign', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('assignunchecked', $TYPE_OPS, 'assignunchecked', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
# A native assign whose target is a reference to a lexical or attribute in
# reach lowers to a direct bind, exactly as the MoarVM backend does. The
# bind's result is the assigned value (an attribute bind yields it at full
# width, so a compound step yields the unstored result while the attribute
# stores truncated), where the real container assign yields the container.
sub native_assign_bind_scope($target) {
    if nqp::istype($target, QAST::Var) {
        my str $scope := $target.scope;
        if $scope eq 'attributeref' {
            return 'attribute';
        }
        elsif $scope eq 'lexicalref' {
            my $block := $*BLOCK;
            my str $name := $target.name;
            while nqp::istype($block, $*BLOCK.WHAT) {
                last if $block.qast.ann('DYN_COMP_WRAPPER');
                return 'lexical' if nqp::defined($block.lexical_type($name));
                last if nqp::defined($block.lexicalref_type($name));
                $block := $block.outer;
            }
        }
    }
    ''
}
for [['assign_i', 'jvm_container_assign_i'], ['assign_u', 'jvm_container_assign_u'],
     ['assign_n', 'jvm_container_assign_n'], ['assign_s', 'jvm_container_assign_s']] -> @spec {
    my str $op_name       := @spec[0];
    my str $fallback_name := @spec[1];
    QAST::OperationsJAST.add_core_op($op_name, -> $qastcomp, $op {
        my $target := $op[0];
        my str $bind_scope := native_assign_bind_scope($target);
        if $bind_scope ne '' {
            $op.op('bind');
            $target.scope($bind_scope);
            $qastcomp.as_jast($op)
        }
        else {
            $qastcomp.as_jast(QAST::Op.new( :op($fallback_name), $op[0], $op[1] ))
        }
    });
}
QAST::OperationsJAST.map_classlib_core_op('jvm_container_assign_i', $TYPE_OPS, 'assign_i', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('jvm_container_assign_u', $TYPE_OPS, 'assign_u', [$RT_OBJ, $RT_UINT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('jvm_container_assign_n', $TYPE_OPS, 'assign_n', [$RT_OBJ, $RT_NUM], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('jvm_container_assign_s', $TYPE_OPS, 'assign_s', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);

# lexical related opcodes
QAST::OperationsJAST.map_classlib_core_op('getlex', $TYPE_OPS, 'getlex', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlex_i', $TYPE_OPS, 'getlex_i', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlex_n', $TYPE_OPS, 'getlex_n', [$RT_STR], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlex_s', $TYPE_OPS, 'getlex_s', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexref_i', $TYPE_OPS, 'getlexref_i', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexref_u', $TYPE_OPS, 'getlexref_u', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexref_n', $TYPE_OPS, 'getlexref_n', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexref_s', $TYPE_OPS, 'getlexref_s', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlex', $TYPE_OPS, 'bindlex', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlex_i', $TYPE_OPS, 'bindlex_i', [$RT_STR, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlex_u', $TYPE_OPS, 'bindlex_u', [$RT_STR, $RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlex_n', $TYPE_OPS, 'bindlex_n', [$RT_STR, $RT_NUM], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlex_s', $TYPE_OPS, 'bindlex_s', [$RT_STR, $RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexdyn', $TYPE_OPS, 'getlexdyn', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindlexdyn', $TYPE_OPS, 'bindlexdyn', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexcaller', $TYPE_OPS, 'getlexcaller', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexouter', $TYPE_OPS, 'getlexouter', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexrel', $TYPE_OPS, 'getlexrel', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexreldyn', $TYPE_OPS, 'getlexreldyn', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlexrelcaller', $TYPE_OPS, 'getlexrelcaller', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.add_core_op('locallifetime', -> $qastcomp, $op {
    my @children := nqp::clone($op.list());
    if @children == 0 {
        nqp::die('locallifetime requires at least one child');
    }
    my $arg := @children.shift();
    my $ta := $qastcomp.new_temp_allocator;

    $*BLOCK.tempify($ta, @children);
    my $res := $qastcomp.as_jast($op[0]);
    $*BLOCK.untempify($ta, @children);

    $res;
});

# code object related opcodes
QAST::OperationsJAST.map_classlib_core_op('takeclosure', $TYPE_OPS, 'takeclosure', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getcodeobj', $TYPE_OPS, 'getcodeobj', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setcodeobj', $TYPE_OPS, 'setcodeobj', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getcodename', $TYPE_OPS, 'getcodename', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('setcodename', $TYPE_OPS, 'setcodename', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getcodecuid', $TYPE_OPS, 'getcodecuid', [$RT_OBJ], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('forceouterctx', $TYPE_OPS, 'forceouterctx', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('captureinnerlex', $TYPE_OPS, 'captureinnerlex', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('freshcoderef', $TYPE_OPS, 'freshcoderef', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('markcodestatic', $TYPE_OPS, 'markcodestatic', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('markcodestub', $TYPE_OPS, 'markcodestub', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getstaticcode', $TYPE_OPS, 'getstaticcode', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setdispatcher', $TYPE_OPS, 'setdispatcher', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('setdispatcherfor', $TYPE_OPS, 'setdispatcherfor', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.add_core_op('takedispatcher', -> $qastcomp, $op {
    if nqp::elems(@($op)) != 1 || !nqp::istype($op[0], QAST::SVal) {
        nqp::die('takedispatcher requires one string literal operand');
    }
    my $name := $op[0].value;
    my $idx := $*BLOCK.lexical_type($name);
    unless nqp::defined($idx) {
        nqp::die('takedispatcher used with non-existing lexical');
    }
    my $il := JAST::InstructionList.new();
    $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'takedispatcher', 'V', 'I', $TYPE_TC ));
    if $*WANT != $RT_VOID {
        $il.append($ACONST_NULL);
        result($il, $RT_OBJ)
    }
    else {
        result($il, $RT_VOID)
    }
});
QAST::OperationsJAST.add_core_op('cleardispatcher', -> $qastcomp, $op {
    if nqp::elems(@($op)) != 0 {
        nqp::die('cleardispatcher accepts no operands');
    }
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append($ACONST_NULL);
    $il.append(JAST::Instruction.new( :op('putfield'), $TYPE_TC, 'currentDispatcher', $TYPE_SMO ));
    $il.append($ACONST_NULL);
    result($il, $RT_OBJ);
});
QAST::OperationsJAST.map_classlib_core_op('nextdispatcherfor', $TYPE_OPS, 'nextdispatcherfor', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.add_core_op('takenextdispatcher', -> $qastcomp, $op {
    if nqp::elems(@($op)) != 1 || !nqp::istype($op[0], QAST::SVal) {
        nqp::die('takenextdispatcher requires one string literal operand');
    }
    my $name := $op[0].value;
    my $idx := $*BLOCK.lexical_type($name);
    unless nqp::defined($idx) {
        nqp::die('takenextdispatcher used with non-existing lexical ');
    }
    my $il := JAST::InstructionList.new();
    $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
    $il.append($ALOAD_1);
    $il.append(JAST::Instruction.new( :op('invokestatic'),
        $TYPE_OPS, 'takenextdispatcher', 'V', 'I', $TYPE_TC ));
    if $*WANT != $RT_VOID {
        $il.append($ACONST_NULL);
        result($il, $RT_OBJ)
    }
    else {
        result($il, $RT_VOID)
    }
});
QAST::OperationsJAST.add_core_op('clearnextdispatcher', -> $qastcomp, $op {
    if nqp::elems(@($op)) != 0 {
        nqp::die('clearnextdispatcher accepts no operands');
    }
    my $il := JAST::InstructionList.new();
    $il.append($ALOAD_1);
    $il.append($ACONST_NULL);
    $il.append(JAST::Instruction.new( :op('putfield'), $TYPE_TC, 'nextDispatcher', $TYPE_SMO ));
    $il.append($ACONST_NULL);
    result($il, $RT_OBJ);
});
QAST::OperationsJAST.add_core_op('setup_blv', -> $qastcomp, $op {
    if nqp::elems(@($op)) != 1 || !nqp::ishash($op[0]) {
        nqp::die('setup_blv requires one hash operand');
    }

    my $il := JAST::InstructionList.new();

    # One string constant per block would be two constant-pool entries per
    # block, and the core setting has tens of thousands of them -- enough on
    # its own to push the unit past the 65535-entry limit. Batch the blocks
    # into as few strings as a constant-pool Utf8 entry can hold (its length
    # is a u2 in bytes, so stay well under 65535 to leave room for non-ASCII
    # lexical names). Each block contributes a qbid, a lexical count, and
    # four fields per lexical.
    my int $CHUNK-CHARS := 20000;
    my @chunk;
    my int $chunk-chars;
    my sub flush() {
        if nqp::elems(@chunk) {
            $il.append($ALOAD_0);
            $il.append($ALOAD_1);
            $il.append(JAST::PushSVal.new( :value(nqp::join("\0", @chunk)) ));
            $il.append(JAST::Instruction.new( :op('invokevirtual'),
                $TYPE_CU, 'setLexValuesBulk', 'Void', $TYPE_TC, $TYPE_STR ));
            @chunk := [];
            $chunk-chars := 0;
        }
    }

    for $op[0] {
        my $cuid := $_.key;
        my @bits;
        for $_.value -> @lex {
            nqp::push(@bits, @lex[0]);
            my $sc := nqp::getobjsc(@lex[1]);
            nqp::push(@bits, nqp::scgethandle($sc));
            nqp::push(@bits, ~nqp::scgetobjidx($sc, @lex[1]));
            nqp::push(@bits, ~@lex[2]);
        }
        nqp::push(@chunk, ~$qastcomp.cuid_to_qbid($cuid));
        nqp::push(@chunk, ~nqp::div_i(nqp::elems(@bits), 4));
        $chunk-chars := $chunk-chars + 16;
        for @bits {
            nqp::push(@chunk, $_);
            $chunk-chars := $chunk-chars + nqp::chars($_) + 1;
        }
        flush() if $chunk-chars >= $CHUNK-CHARS;
    }
    flush();

    $il.append($ACONST_NULL);
    result($il, $RT_OBJ)
});

# language/compiler ops
QAST::OperationsJAST.map_classlib_core_op('getcomp', $TYPE_OPS, 'getcomp', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindcomp', $TYPE_OPS, 'bindcomp', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getcurhllsym', $TYPE_OPS, 'getcurhllsym', [$RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindcurhllsym', $TYPE_OPS, 'bindcurhllsym', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('gethllsym', $TYPE_OPS, 'gethllsym', [$RT_STR, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('bindhllsym', $TYPE_OPS, 'bindhllsym', [$RT_STR, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('sethllconfig', $TYPE_OPS, 'sethllconfig', [$RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('loadbytecode', $TYPE_OPS, 'loadbytecode', [$RT_STR], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('loadbytecodebuffer', $TYPE_OPS, 'loadbytecodebuffer', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('usecompilerhllconfig', $TYPE_OPS, 'usecompilerhllconfig', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('usecompileehllconfig', $TYPE_OPS, 'usecompileehllconfig', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('settypehll', $TYPE_OPS, 'settypehll', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('settypehllrole', $TYPE_OPS, 'settypehllrole', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllize', $TYPE_OPS, 'hllize', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllizefor', $TYPE_OPS, 'hllizefor', [$RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllbool', $TYPE_OPS, 'hllbool', [$RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('hllboolfor', $TYPE_OPS, 'hllboolfor', [$RT_INT, $RT_STR], $RT_OBJ, :tc);

# regex engine related opcodes
QAST::OperationsJAST.map_classlib_core_op('nfafromstatelist', $TYPE_OPS, 'nfafromstatelist', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nfarunproto', $TYPE_OPS, 'nfarunproto', [$RT_OBJ, $RT_STR, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nfarunalt', $TYPE_OPS, 'nfarunalt', [$RT_OBJ, $RT_STR, $RT_INT, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

# process related opcodes
QAST::OperationsJAST.map_classlib_core_op('exit', $TYPE_OPS, 'exit', [$RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getsignals', $TYPE_IO_OPS, 'getsignals', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('sleep', $TYPE_OPS, 'sleep', [$RT_NUM], $RT_NUM);
QAST::OperationsJAST.map_classlib_core_op('getenvhash', $TYPE_OPS, 'getenvhash', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getpid', $TYPE_OPS, 'getpid', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getppid', $TYPE_OPS, 'getppid', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('jvmgetproperties', $TYPE_OPS, 'jvmgetproperties', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('execname', $TYPE_OPS, 'execname', [], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getrusage', $TYPE_OPS, 'getrusage', [$RT_OBJ], $RT_OBJ, :tc);

# thread related opcodes
QAST::OperationsJAST.map_classlib_core_op('newthread', $TYPE_OPS, 'newthread', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('threadrun', $TYPE_OPS, 'threadrun', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('threadjoin', $TYPE_OPS, 'threadjoin', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('threadid', $TYPE_OPS, 'threadid', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('threadyield', $TYPE_OPS, 'threadyield', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('currentthread', $TYPE_OPS, 'currentthread', [], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('lock', $TYPE_OPS, 'lock', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('unlock', $TYPE_OPS, 'unlock', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('getlockcondvar', $TYPE_OPS, 'getlockcondvar', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('condwait', $TYPE_OPS, 'condwait', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('condsignalone', $TYPE_OPS, 'condsignalone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('condsignalall', $TYPE_OPS, 'condsignalall', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('semacquire', $TYPE_OPS, 'semacquire', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('semtryacquire', $TYPE_OPS, 'semtryacquire', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('semrelease', $TYPE_OPS, 'semrelease', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('queuepoll', $TYPE_OPS, 'queuepoll', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('cpucores', $TYPE_OPS, 'cpucores', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('freemem', $TYPE_OPS, 'freemem', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('totalmem', $TYPE_OPS, 'totalmem', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('threadlockcount', $TYPE_OPS, 'threadlockcount', [$RT_OBJ], $RT_INT, :tc);

# asynchrony related ops
QAST::OperationsJAST.map_classlib_core_op('timer', $TYPE_OPS, 'timer', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('permit', $TYPE_OPS, 'permit', [$RT_OBJ, $RT_INT, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('cancel', $TYPE_OPS, 'cancel', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('cancelnotify', $TYPE_OPS, 'cancelnotify', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('signal', $TYPE_IO_OPS, 'signal', [$RT_OBJ, $RT_OBJ, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('watchfile', $TYPE_IO_OPS, 'watchfile', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('asyncconnect', $TYPE_IO_OPS, 'asyncconnect', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('asynclisten', $TYPE_IO_OPS, 'asynclisten', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_INT, $RT_INT, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('asyncwritebytes', $TYPE_IO_OPS, 'asyncwritebytes', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('asyncreadbytes', $TYPE_IO_OPS, 'asyncreadbytes', [$RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('spawnprocasync', $TYPE_IO_OPS, 'spawnprocasync', [$RT_OBJ, $RT_STR, $RT_OBJ, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('killprocasync', $TYPE_IO_OPS, 'killprocasync', [$RT_OBJ, $RT_INT], $RT_INT, :tc);

# Atomic ops
QAST::OperationsJAST.map_classlib_core_op('cas', $TYPE_OPS, 'cas', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicload', $TYPE_OPS, 'atomicload', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicstore', $TYPE_OPS, 'atomicstore', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicload_i', $TYPE_OPS, 'atomicload_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicstore_i', $TYPE_OPS, 'atomicstore_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicadd_i', $TYPE_OPS, 'atomicadd_i', [$RT_OBJ, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicinc_i', $TYPE_OPS, 'atomicinc_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicdec_i', $TYPE_OPS, 'atomicdec_i', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('cas_i', $TYPE_OPS, 'cas_i', [$RT_OBJ, $RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('barrierfull', $TYPE_OPS, 'barrierfull', [], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('casattr', $TYPE_OPS, 'casattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('atomicbindattr', $TYPE_OPS, 'atomicbindattr', [$RT_OBJ, $RT_OBJ, $RT_STR, $RT_OBJ], $RT_OBJ, :tc);

# JVM-specific ops for compilation unit handling
QAST::OperationsJAST.map_classlib_core_op('compilejast', $TYPE_OPS, 'compilejast', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('compilejasttofile', $TYPE_OPS, 'compilejasttofile', [$RT_OBJ, $RT_OBJ, $RT_STR], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('loadcompunit', $TYPE_OPS, 'loadcompunit', [$RT_OBJ, $RT_INT], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('iscompunit', $TYPE_OPS, 'iscompunit', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('compunitmainline', $TYPE_OPS, 'compunitmainline', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('compunitcodes', $TYPE_OPS, 'compunitcodes', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('jvmclasspaths', $TYPE_OPS, 'jvmclasspaths', [], $RT_OBJ, :tc);

# JVM-specific ops for continuation handling
# The three main continuation ops are fudgy because they need to be called partially like subs
QAST::OperationsJAST.map_classlib_core_op('continuationclone', $TYPE_OPS, 'continuationclone', [$RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('continuationreset', $TYPE_OPS, 'continuationreset', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('continuationcontrol', $TYPE_OPS, 'continuationcontrol', [$RT_INT, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);
QAST::OperationsJAST.map_classlib_core_op('continuationinvoke', $TYPE_OPS, 'continuationinvoke', [$RT_OBJ, $RT_OBJ], $RT_OBJ, :tc, :cont);

# JVM interop ops
QAST::OperationsJAST.map_classlib_core_op('backendconfig', $TYPE_OPS, 'jvmgetconfig', [], $RT_OBJ, :tc);

# Native call ops
QAST::OperationsJAST.map_classlib_core_op('initnativecall', $TYPE_NATIVE_OPS, 'init', [], $RT_INT);
QAST::OperationsJAST.map_classlib_core_op('buildnativecall', $TYPE_NATIVE_OPS, 'build', [$RT_OBJ, $RT_STR, $RT_STR, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecall', $TYPE_NATIVE_OPS, 'call', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecallinvoke', $TYPE_NATIVE_OPS, 'call', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecallrefresh', $TYPE_NATIVE_OPS, 'refresh', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecallsizeof', $TYPE_NATIVE_OPS, 'nativecallsizeof', [$RT_OBJ], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecallcast', $TYPE_NATIVE_OPS, 'nativecallcast', [$RT_OBJ, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);
QAST::OperationsJAST.map_classlib_core_op('nativecallglobal', $TYPE_NATIVE_OPS, 'nativecallglobal', [$RT_STR, $RT_STR, $RT_OBJ, $RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('getcodelocation', $TYPE_OPS, 'getcodelocation', [$RT_OBJ], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('jvmgetunicodeversion', $TYPE_OPS, 'jvmgetunicodeversion', [], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getuniname', $TYPE_OPS, 'getuniname', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('unipropcode', $TYPE_OPS, 'unipropcode', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getuniprop_str', $TYPE_OPS, 'getuniprop_str', [$RT_INT, $RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('getuniprop_int', $TYPE_OPS, 'getuniprop_int', [$RT_INT, $RT_INT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('getuniprop_bool', $TYPE_OPS, 'getuniprop_bool', [$RT_INT, $RT_INT], $RT_INT, :tc);

QAST::OperationsJAST.map_classlib_core_op('force_gc', $TYPE_OPS, 'force_gc', [], $RT_OBJ, :tc);

QAST::OperationsJAST.map_classlib_core_op('coerce_si', $TYPE_OPS, 'coerce_si', [$RT_STR], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_is', $TYPE_OPS, 'coerce_is', [$RT_INT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_us', $TYPE_OPS, 'coerce_us', [$RT_UINT], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_ns', $TYPE_OPS, 'coerce_ns', [$RT_NUM], $RT_STR, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_in', $TYPE_OPS, 'coerce_in', [$RT_INT], $RT_NUM, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_ni', $TYPE_OPS, 'coerce_ni', [$RT_NUM], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_ui', $TYPE_OPS, 'coerce_ui', [$RT_UINT], $RT_INT, :tc);
QAST::OperationsJAST.map_classlib_core_op('coerce_iu', $TYPE_OPS, 'coerce_iu', [$RT_INT], $RT_UINT, :tc);

QAST::OperationsJAST.map_classlib_core_op('decodelocaltime', $TYPE_OPS, 'decodelocaltime', [$RT_INT], $RT_OBJ, :tc);

class QAST::CompilerJAST {
    # Responsible for handling issues around code references, building the
    # switch statement dispatcher, etc.
    my class CodeRefBuilder {
        has int $!cur_idx;
        has %!cuid_to_idx;
        has @!jastmeth_names;
        has @!jastmeths;
        has @!cuids;
        has @!callsites;
        has %!callsite_map;
        has int $!indy_sites;

        method BUILD() {
            $!cur_idx := 0;
            %!cuid_to_idx := {};
            @!jastmeth_names := [];
            @!jastmeths := [];
            @!cuids := [];
            @!callsites := [];
            %!callsite_map := {};
            $!indy_sites := 0;
        }

        # Claims one of this class's invokedynamic sites, or returns false when
        # the budget is spent and the caller must emit the uncached form.
        method take_indy_site() {
            return 0 if $!indy_sites >= $INDY_SITE_BUDGET;
            $!indy_sites := $!indy_sites + 1;
            1
        }

        method indy_sites() { $!indy_sites }

        method register_method($jastmeth, $cuid) {
            %!cuid_to_idx{$cuid} := $!cur_idx;
            nqp::push(@!jastmeth_names, $jastmeth.name);
            nqp::push(@!jastmeths, $jastmeth);
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

        method cuid_to_jastmethname($cuid) {
            @!jastmeth_names[self.cuid_to_idx($cuid)]
        }

        # What the already-emitted method for this cuid actually takes. The
        # JAST::Method is mutable and its args expectation is only settled
        # after register_method, so keep the object and ask it.
        method cuid_to_args_expectation($cuid) {
            @!jastmeths[self.cuid_to_idx($cuid)].args_expectation
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

        method jastify() {
            self.callsites();
        }

        method callsites() {
            my $csa := JAST::Method.new( :name('getCallSites'), :returns("[$TYPE_CSD"), :static(0) );

            # Create array.
            $csa.append(JAST::PushIndex.new( :value(+@!callsites) ));
            $csa.append(JAST::Instruction.new( :op('anewarray'), $TYPE_CSD ));

            # Add all the callsites.
            my int $i := 0;
            for @!callsites -> @cs {
                my @cs_flags := @cs[0];
                my @cs_names := @cs[1];
                $csa.append($DUP); # Target array.
                $csa.append(JAST::PushIndex.new( :value($i++) )); # Index.
                $csa.append(JAST::Instruction.new( :op('new'), $TYPE_CSD ));
                $csa.append($DUP);
                $csa.append(JAST::PushIndex.new( :value(+@cs_flags) ));
                $csa.append(JAST::Instruction.new( :op('newarray'), 'Byte' ));
                my int $j := 0;
                for @cs_flags {
                    $csa.append($DUP);
                    $csa.append(JAST::PushIndex.new( :value($j++) ));
                    $csa.append(JAST::PushIndex.new( :value($_) ));
                    $csa.append($I2B);
                    $csa.append($BASTORE);
                }
                if @cs_names {
                    $csa.append(JAST::PushIndex.new( :value(+@cs_names) ));
                    $csa.append(JAST::Instruction.new( :op('anewarray'), $TYPE_STR ));
                    $j := 0;
                    for @cs_names {
                        $csa.append($DUP);
                        $csa.append(JAST::PushIndex.new( :value($j++) ));
                        $csa.append(JAST::PushSVal.new( :value($_) ));
                        $csa.append($AASTORE);
                    }
                }
                else {
                    $csa.append($ACONST_NULL);
                }
                $csa.append(JAST::Instruction.new( :op('invokespecial'),
                    $TYPE_CSD, '<init>', 'Void', '[Byte', "[$TYPE_STR"));
                $csa.append($AASTORE);
            }

            # Return the array. Add method to class.
            $csa.append($ARETURN);
            $*JCLASS.add_method($csa);
        }
    }

    # Holds information about the QAST::Block we're currently compiling.
    my class BlockInfo {
        has $!qast;             # The QAST::Block
        has $!outer;            # Outer block's BlockInfo
        has @!params;           # QAST::Var nodes of params
        has @!locals;           # QAST::Var nodes of declared locals
        has %!local_types;      # Mapping of local registers to type names
        has %!lexical_types;    # Mapping of lexical names to types
        has %!local_returns;    # Mapping of local names to their type objects
        has %!lexical_returns;  # Mapping of lexical names to their type objects
        has %!lexicalref_types; # Mapping of lexical names to types
        has %!lexical_idxs;     # Lexical indexes (but have to know type too)
        has @!lexical_names;    # List by type of lexial name lists
        has int $!num_save_sites;   # Count of points where a SaveStackException handler is needed
        has %!local2temp;       # Maps local names to temporarization info

        method new($qast, $outer) {
            my $obj := nqp::create(self);
            $obj.BUILD($qast, $outer);
            $obj
        }

        method BUILD($qast, $outer) {
            $!qast := $qast;
            $!outer := $outer;
            @!params := nqp::list();
            @!locals := nqp::list();
            %!local_types := nqp::hash();
            %!lexical_types := nqp::hash();
            %!local_returns := nqp::hash();
            %!lexical_returns := nqp::hash();
            %!lexicalref_types := nqp::hash();
            %!lexical_idxs := nqp::hash();
            %!local2temp := nqp::hash();
            @!lexical_names := nqp::list([],[],[],[], nqp::null, nqp::null, nqp::null, nqp::null, nqp::null, nqp::null, []);
        }

        method add_param($var) {
            if $var.scope eq 'local' {
                self.add_local($var);
            }
            else {
                self.add_lexical($var);
            }
            @!params[+@!params] := $var;
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

        method add_local($var) {
            my $tempify := %!local2temp{$var.name};
            if $tempify {
                if $tempify[0] {
                    nqp::die("Local '"~$var.name~"' already declared");
                }
                my int $type  := rttype_from_typeobj($var.returns);
                my str $tch   := typechar($type);
                my str $local := $tempify[1]."fresh_$tch"();
                $tempify[0] := [ $local, $type ];
            } else {
                self.register_local($var);
                @!locals[+@!locals] := $var;
            }
        }

        method tempify($ta, @vars) {
            for @vars -> $v {
                %!local2temp{$v} := [ '', $ta ];
            }
        }

        method untempify($ta, @vars) {
            for @vars -> $v {
                nqp::deletekey(%!local2temp, $v);
            }
            $ta.release();
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
                # Name the block: "already declared" with no scope named is
                # unchaseable when the first declaration came from another
                # compilation road (the code engine commits a block's
                # lexicals itself when it encodes the block).
                nqp::die("Lexical '$name' already declared in block '"
                    ~ ($!qast.name eq '' ?? '<anon>' !! $!qast.name)
                    ~ "' (cuid " ~ $!qast.cuid ~ ")");
            }
            %!lexicalref_types{$name} := $type;
            %!lexical_idxs{$name}     := nqp::elems(@!lexical_names[$RT_OBJ]);
            nqp::push(@!lexical_names[$RT_OBJ], $name);
        }

        method register_local($var) {
            my $name := $var.name;
            if nqp::existskey(%!local_types, $name) {
                nqp::die("Local '$name' already declared");
            }
            %!local_returns{$name} := $var.returns;
            %!local_types{$name} := rttype_from_typeobj($var.returns);
        }

        method alloc_save_site() {
            my $index := $!num_save_sites;
            $!num_save_sites := $index + 1;
            $index;
        }

        method num_save_sites() { $!num_save_sites }

        method qast() { $!qast }
        method outer() { $!outer }
        method params() { @!params }
        method locals() { @!locals }

        method local_info($name) {
            my $tempify := %!local2temp{$name};
            $tempify ?? $tempify[0] !! [ $name, %!local_types{$name} ]
        }
        method lexical_type($name) { %!lexical_types{$name} }
        method lexical_returns($name) { %!lexical_returns{$name} }
        method local_returns($name) { %!local_returns{$name} }
        method lexicalref_type($name) { %!lexicalref_types{$name} }
        method lexical_idx($name) { %!lexical_idxs{$name} }
        method lexical_names_by_type() { @!lexical_names }
    }

    my class BlockTempAlloc {
        has int $!cur_i;
        has int $!cur_n;
        has int $!cur_s;
        has int $!cur_o;
        has @!free_i;
        has @!free_n;
        has @!free_s;
        has @!free_o;

        method fresh_i() {
            @!free_i ?? nqp::pop(@!free_i) !! "__TMP_I_" ~ $!cur_i++
        }

        method fresh_n() {
            @!free_n ?? nqp::pop(@!free_n) !! "__TMP_N_" ~ $!cur_n++
        }

        method fresh_s() {
            @!free_s ?? nqp::pop(@!free_s) !! "__TMP_S_" ~ $!cur_s++
        }

        method fresh_o() {
            @!free_o ?? nqp::pop(@!free_o) !! "__TMP_O_" ~ $!cur_o++
        }

        method release(@i, @n, @s, @o) {
            for @i { nqp::push(@!free_i, $_) }
            for @n { nqp::push(@!free_n, $_) }
            for @s { nqp::push(@!free_s, $_) }
            for @o { nqp::push(@!free_o, $_) }
        }

        method add_temps_to_set($set) {
            sub temps($prefix, $n, $type) {
                my int $i := 0;
                while $i < $n {
                    nqp::push($set, ["$prefix$i", $type]);
                    $i++;
                }
            }
            temps("__TMP_I_", $!cur_i, $RT_INT);
            temps("__TMP_N_", $!cur_n, $RT_NUM);
            temps("__TMP_S_", $!cur_s, $RT_STR);
            temps("__TMP_O_", $!cur_o, $RT_OBJ);
        }
    }

    my class StmtTempAlloc {
        has @!used_i;
        has @!used_n;
        has @!used_s;
        has @!used_o;

        method fresh_i() {
            my $al := $*BLOCK_TA.fresh_i();
            nqp::push(@!used_i, $al);
            $al
        }

        method fresh_n() {
            my $al := $*BLOCK_TA.fresh_n();
            nqp::push(@!used_n, $al);
            $al
        }

        method fresh_s() {
            my $al := $*BLOCK_TA.fresh_s();
            nqp::push(@!used_s, $al);
            $al
        }

        method fresh_o() {
            my $al := $*BLOCK_TA.fresh_o();
            nqp::push(@!used_o, $al);
            $al
        }

        method release() {
            $*BLOCK_TA.release(@!used_i, @!used_n, @!used_s, @!used_o)
        }
    }

    method new_temp_allocator() { StmtTempAlloc.new }

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

    method jast($source, :$classname!, *%adverbs) {
        # Wrap $source in a QAST::CompUnit if it's not already a viable root node.
        unless nqp::istype($source, QAST::CompUnit) {
            my $unit := $source;
            $unit := QAST::Block.new($unit) unless nqp::istype($unit, QAST::Block);
            $source := QAST::CompUnit.new(:hll(''), $unit);
        }

        # Set up a JAST::Class that will hold all the blocks (which become Java
        # methods) that we shall compile.
        my $file := nqp::ifnull(nqp::getlexdyn('$?FILES'), "");
        my $*JCLASS := JAST::Class.new(
            :name($classname),
            :super('org.raku.nqp.runtime.CompilationUnit'),
            :filename($file)
        );

        # We'll also need to keep track of all the blocks we compile into Java
        # methods; the CodeRefBuilder takes care of that.
        my $*CODEREFS := CodeRefBuilder.new();

        # Now compile $source. By the end of this, the various data structures
        # set up above will be fully populated.
        self.as_jast($source);

        # Make various code-ref/dispatch related things.
        $*CODEREFS.jastify();

        # Finally, we hand back the finished class.
        return $*JCLASS
    }

    # Tracks what is currently on the stack, and what things that were on the
    # stack have been spilled to temporaries and thus will need re-instating
    # at some point in the future.
    my class StackState {
        has @!stack;
        has @!spill_locals;

        method push($result) {
            nqp::istype($result, Result)
                ?? nqp::push(@!stack, $result)
                !! nqp::die("Can only push a Result onto the stack")
        }

        method obtain($il, *@things) {
            # Sanity checks.
            if nqp::elems(@things) == 0 {
                nqp::die("Should not try to obtain zero stack elements");
            }
            if nqp::elems(@!stack) < nqp::elems(@things) {
                nqp::die("Cannot obtain from empty or undersized stack");
            }

            # See if the things we need are all on the stack.
            my int $sp        := @!stack - +@things;
            my int $tp        := 0;
            my int $ok        := 1;
            my int $all_stack := 1;
            my int $all_local := 1;
            while $tp < +@things {
                unless nqp::istype(@things[$tp], Result) {
                    nqp::die("Should only look up Result objects on the stack");
                }
                unless nqp::eqaddr(@!stack[$sp], @things[$tp]) {
                    $ok := 0;
                    last;
                }
                if @!stack[$sp].local {
                    $all_stack := 0;
                }
                else {
                    $all_local := 0;
                }
                $sp++, $tp++;
            }
            if $ok {
                # If they're all on the stack, easy.
                if $all_stack {
                    for @things { nqp::pop(@!stack) }
                    return 1;
                }

                # If they're all local, load them onto the stack. Also, we can
                # re-use the stack saving temporaries.
                elsif $all_local {
                    for @things {
                        my $local := $_.local;
                        my $type  := $_.type;
                        $il.append(JAST::Instruction.new( :op(load_ins($type)), $local ));
                        if nqp::islist(@!spill_locals[$type]) {
                            nqp::push(@!spill_locals[$type], $local);
                        }
                        else {
                            @!spill_locals[$type] := [$local];
                        }
                        nqp::pop(@!stack)
                    }
                    return 1;
                }

                # Mix of local and stack: just spill everything still on the
                # stack, and try again.
                else {
                    self.spill_to_locals($il);
                    return self.obtain($il, |@things);
                }
            }

            # Otherwise, bad access.
            nqp::die("Out-of-order access or re-use of stack items");
        }

        # Spills the current stack contents to local variables.
        method spill_to_locals($il) {
            sub obtain_temp($type) {
                if $type == $RT_VOID {
                    nqp::die("Cannot spill a stack containing a void");
                }
                if @!spill_locals[$type] {
                    nqp::pop(@!spill_locals[$type])
                }
                else {
                    bfresh($type);
                }
            }

            my $sp := nqp::elems(@!stack);
            while $sp-- {
                my $item := @!stack[$sp];
                unless $item.local {
                    my $temp := obtain_temp($item.type);
                    $il.append(JAST::Instruction.new( :op(store_ins($item.type)), $temp ));
                    $item.set_local($temp);
                }
            }
        }
    }

    our $serno;
    INIT {
        $serno := 10;
    }

    method unique($prefix = '') { $prefix ~ $serno++ }

    proto method as_jast($node, :$want) {
        my $*WANT;
        if nqp::defined($want) {
            $*WANT := %WANTMAP{$want} // $want;
            if nqp::istype($node, QAST::Want) {
                self.coerce(self.as_jast(want($node, $*WANT)), $*WANT)
            }
            else {
                self.coerce({*}, $*WANT)
            }
        }
        else {
            {*}
        }
    }

    sub want($node, $type) {
        my @possibles := $node.list;
        my $best      := nqp::atpos(@possibles, 0);
        if $type != $RT_OBJ {
            my $char := $type == $RT_VOID ?? 'v' !!
                        $type == $RT_INT  ?? 'I' !!
                        $type == $RT_NUM  ?? 'N' !!
                        $type == $RT_STR  ?? 'S' !!
                                             'X';
            my int $i := 1;
            my int $n := nqp::elems(@possibles);
            while $i < $n {
                if nqp::index(nqp::atpos(@possibles, $i), $char) >= 0 {
                    $best := nqp::atpos(@possibles, $i + 1);
                    last;
                }
                $i := $i + 2;
            }
        }
        $best
    }

    method cuid_to_qbid(str $cuid) {
        my $map := %*CUID_TO_QBID;
        nqp::existskey($map, $cuid) ?? $map{$cuid} !! ($map{$cuid} := $*NEXT_QBID++);
    }

    multi method as_jast(QAST::CompUnit $cu, :$want) {
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
        # Engine programs of jar-bound comp-mode blocks, collected here and
        # written as one jar sidecar (see JAST::Class.codeprograms); the
        # emitted bodies reference them by index.
        my @*ENGINE_PROGRAMS := nqp::list_s();
        # Pre-seed to make sure that qbids correspond to serialization IDs
        my $*COMP_MODE := $cu.compilation_mode;
        # Comp-mode units pair code refs with methods by block id, so the
        # cuid strings are dead weight there (and the setting's constant
        # pool lives close to the 64K limit). A nested unit is the
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

        # Compile the block.
        my $block_jast := self.as_jast($cu[0]);

        # If we are in compilation mode, or have pre-deserialization or
        # post-deserialization tasks, handle those. Overall, the process
        # is to desugar this into simpler QAST nodes, then compile those.
        my @pre_des   := $cu.pre_deserialize;
        my @post_des  := $cu.post_deserialize;
        if %*BLOCK_LEX_VALUES {
            nqp::push(@post_des, QAST::Block.new(
                :blocktype('immediate'),
                QAST::Op.new( :op('setup_blv'), %*BLOCK_LEX_VALUES )
            ));
        }
        if $*COMP_MODE || @pre_des || @post_des || need_set_code_object($cu) {
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

            # Deserialization pairs the serialized code refs with this class's
            # methods by block id, so every block in the code ref table needs a
            # method, including one the tree never mentioned: a thunk that only
            # ever ran at BEGIN time leaves its block registered but unreached.
            # Compile the leftovers here, which is what the MoarVM backend gets
            # from hanging the whole code ref table off its list_b.
            if $cu.code_ref_blocks() {
                my $orphans := QAST::Block.new( :blocktype('immediate') );
                for $cu.code_ref_blocks() {
                    unless $*CODEREFS.know_cuid($_.cuid) {
                        # Only the method matters; make sure referencing it
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

            # Compile to JAST and register this block as the deserialization
            # handler.
            self.as_jast($block);
            my $des_meth := JAST::Method.new( :name('deserializeQbid'), :returns('I'), :static(0) );
            $des_meth.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($block.cuid)) ));
            $des_meth.append($IRETURN);
            $*JCLASS.add_method($des_meth);
        }

        # Compile and include load-time logic, if any.
        if nqp::defined($cu.load) {
            my $load_block := QAST::Block.new(
                :blocktype('raw'),
                $cu.load,
                QAST::Op.new( :op('null') )
            );
            self.as_jast($load_block);
            my $load_meth := JAST::Method.new( :name('loadQbid'), :returns('I'), :static(0) );
            $load_meth.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($load_block.cuid)) ));
            $load_meth.append($IRETURN);
            $*JCLASS.add_method($load_meth);
        }

        # Compile and include main-time logic, if any, and then add a Java
        # main that will lead to its invocation.
        if nqp::defined($cu.main) {
            my $main_block := QAST::Block.new(
                :blocktype('raw'),
                $cu.main,
                QAST::Op.new( :op('null') )
            );
            self.as_jast($main_block);
            my $main_meth := JAST::Method.new( :name('main'), :returns('Void') );
            $main_meth.add_argument('argv', "[$TYPE_STR");
            $main_meth.append(JAST::PushCVal.new( :value('L' ~ $*JCLASS.name ~ ';') ));
            $main_meth.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($main_block.cuid)) ));
            $main_meth.append($ALOAD_0);
            $main_meth.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_CU, 'enterFromMain',
                'Void', 'Ljava/lang/Class;', 'I', "[$TYPE_STR"));
            $main_meth.append($RETURN);
            $*JCLASS.add_method($main_meth);
            my $entry_cuid_meth := JAST::Method.new( :name('entryQbid'), :returns('I'), :static(0) );
            $entry_cuid_meth.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($main_block.cuid)) ));
            $entry_cuid_meth.append($IRETURN);
            $*JCLASS.add_method($entry_cuid_meth);
        }

        # Add method that returns HLL name.
        my $hll_meth := JAST::Method.new( :name('hllName'), :returns($TYPE_STR), :static(0) );
        $hll_meth.append(JAST::PushSVal.new( :value($*HLL) ));
        $hll_meth.append($ARETURN);
        $*JCLASS.add_method($hll_meth);

        # Add method that returns the mainline block.
        my $mainline_meth := JAST::Method.new( :name('mainlineQbid'), :returns('I'), :static(0) );
        $mainline_meth.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($cu[0].cuid)) ));
        $mainline_meth.append($IRETURN);
        $*JCLASS.add_method($mainline_meth);

        # Engine programs collected from jar-bound blocks travel as one
        # sidecar entry; joined here, last, so every block -- the
        # deserialize and load methods included -- has had its say.
        if nqp::elems(@*ENGINE_PROGRAMS) {
            my @joined := [~nqp::elems(@*ENGINE_PROGRAMS)];
            for @*ENGINE_PROGRAMS -> str $p {
                nqp::push(@joined, ' ' ~ nqp::chars($p) ~ ':' ~ $p);
            }
            $*JCLASS.codeprograms(nqp::join('', @joined));
        }

        return $*JCLASS;
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
        # classfiles ride along in the jar, and the deserialization code
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
            $*JCLASS.nested_classes(@nested_class_names);
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
            $*JCLASS.serialized($serialized);
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

        # Which of our methods need to be serialized?
        my $count_meth := JAST::Method.new( :name('serializedCodeRefCount'), :returns('I'), :static(0) );
        $count_meth.append(JAST::PushIndex.new( :value(+@code_ref_blocks) ));
        $count_meth.append($IRETURN);
        $*JCLASS.add_method($count_meth);

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

    my $ARG_EXP_USE_BINDER := 0;
    my $ARG_EXP_NO_ARGS    := 1;
    my $ARG_EXP_OBJ        := 2;
    my $ARG_EXP_OBJ_OBJ    := 3;
    # The children of a param node are the extra work its binding implies: a
    # type check, or the code that moves the bound value where the body wants
    # it. MoarVM runs them right after taking the parameter, and a block whose
    # only use of its argument is one of these tasks - a regex thunk binding
    # its cursor to `self`, say - never sees the argument at all without them.
    method emit_param_tasks($il, $block, $var) {
        for $var.list {
            my $*BLOCK := $block;
            # A type check evaluates to a flag that assertparamcheck turns
            # into a bind failure rather than a throw, so a multi can try the
            # next candidate. MoarVM compiles the node the same way.
            my $task := self.as_jast(
                nqp::istype($_, QAST::ParamTypeCheck)
                    ?? QAST::Op.new( :op('assertparamcheck'), $_[0] )
                    !! $_,
                :want($RT_VOID));
            $il.append($task.jast);
            $*STACK.obtain($il, $task);
        }
    }

    # Does binding this parameter run a check that can fail? Reporting such a
    # failure means handing the arguments to the HLL, which only the args
    # array route keeps hold of.
    method param_can_bind_fail($var) {
        for $var.list {
            return 1 if nqp::istype($_, QAST::ParamTypeCheck);
        }
        0
    }

    method try_setup_args_expectation($jmeth, $block, $il) {
        # Needing an args array forces the binder.
        if $*NEED_ARGS_ARRAY {
            return $ARG_EXP_USE_BINDER;
        }

        # So does a parameter whose binding can fail, since the arguments have
        # to survive for the failure to be reported against.
        for $block.params {
            return $ARG_EXP_USE_BINDER if self.param_can_bind_fail($_);
        }

        # Otherwise, go by arity, then look at particular cases.
        my int $num_params := +$block.params;
        if $num_params == 0 {
            # Easy; just don't add any extra args in.
            $jmeth.args_expectation($ARG_EXP_NO_ARGS);
            return $ARG_EXP_NO_ARGS;
        }
        elsif $num_params == 1 {
            # Look for one required positional case. Methods with no params
            # beyond the invocant are always this.
            my $param := $block.params[0];
            if !$param.named && !$param.slurpy && !$param.default {
                if nqp::objprimspec($param.returns) == 0 {
                    $jmeth.add_argument('__arg_0', $TYPE_SMO);
                    $il.append(JAST::Instruction.new( :op('aload'), '__arg_0' ));
                    if $param.scope eq 'local' {
                        $il.append(JAST::Instruction.new( :op('astore'), $param.name ));
                    }
                    else {
                        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                        $il.append(JAST::PushIndex.new( :value($block.lexical_idx($param.name)) ));
                        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            'bindlex_o', $TYPE_SMO, $TYPE_SMO, $TYPE_CF, 'Integer' ));
                        $il.append($POP);
                    }
                    self.emit_param_tasks($il, $block, $param);
                    $jmeth.args_expectation($ARG_EXP_OBJ);
                    return $ARG_EXP_OBJ;
                }
                else {
                    return $ARG_EXP_USE_BINDER;
                }
            }
            else {
                return $ARG_EXP_USE_BINDER;
            }
        }
        elsif $num_params == 2 {
            my $is_obj_obj := 1;
            for $block.params {
                if $_.named || $_.slurpy || $_.default || nqp::objprimspec($_.returns) != 0 {
                    $is_obj_obj := 0;
                    last;
                }
            }
            if $is_obj_obj {
                my int $i := 0;
                for $block.params {
                    $jmeth.add_argument("__arg_$i", $TYPE_SMO);
                    $il.append(JAST::Instruction.new( :op('aload'), "__arg_$i" ));
                    if $_.scope eq 'local' {
                        $il.append(JAST::Instruction.new( :op('astore'), $_.name ));
                    }
                    else {
                        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                        $il.append(JAST::PushIndex.new( :value($block.lexical_idx($_.name)) ));
                        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            'bindlex_o', $TYPE_SMO, $TYPE_SMO, $TYPE_CF, 'Integer' ));
                        $il.append($POP);
                    }
                    self.emit_param_tasks($il, $block, $_);
                    $i++;
                }
                $jmeth.args_expectation($ARG_EXP_OBJ_OBJ);
                return $ARG_EXP_OBJ_OBJ;
            }
            else {
                return $ARG_EXP_USE_BINDER;
            }
        }
        else {
            return $ARG_EXP_USE_BINDER;
        }
    }

    multi method as_jast(QAST::Block $node, :$want) {
        # Do block compilation in a nested block, so we can produce a result based on
        # the containing block's stack.
        my int $args_expectation;
        if !$*CODEREFS.know_cuid($node.cuid) {
            # Block gets fresh BlockInfo.
            my $*BINDVAL  := 0;
            my $outer     := $*BLOCK;
            my $block     := BlockInfo.new($node, $outer);

            # This array will contain any catch/control exception handlers the
            # block gets. A contextual lets us track nesting of handlers.
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

            # Create JAST method and register it with the block's compilation unit
            # unique ID and name. (Note, always void return here as return values
            # are handled out of band).
            my $*JMETH := JAST::Method.new( :name('qb_'~self.cuid_to_qbid($node.cuid)), :returns('Void'), :static(1) );
            $*JMETH.cr_name($node.name);
            $*JMETH.cr_cuid($node.cuid) unless $*COMP_MODE && !$*EMIT_CUIDS;

            # Note the block's source location so nqp::getcodelocation has
            # something to answer with at runtime. A node that knows its own
            # file and line (RakuAST origins) is believed outright, the same
            # way the MoarVM backend does; otherwise the position is computed
            # from the orig, honoring #line directives.
            if $node.node && nqp::can($node.node, 'file') && nqp::can($node.node, 'line') {
                my $loc-file := $node.node.file;
                if $loc-file {
                    $*JMETH.cr_file(~$loc-file);
                    $*JMETH.cr_line($node.node.line);
                    # The unmapped line pairs with the LineNumberTable rows,
                    # which come from orig-line/lineof without directives.
                    $*JMETH.cr_rawline(nqp::can($node.node, 'orig-line')
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
                    $*JMETH.cr_file(~$loc-file);
                    $*JMETH.cr_line($line-file[0]);
                    $*JMETH.cr_rawline(HLL::Compiler.lineof(
                        $node.node.orig(), $node.node.from(), :cache(1), :directives(0)));
                }
            }
            $*CODEREFS.register_method($*JMETH, $node.cuid);

            # Set outer if we have one.
            if nqp::istype($outer, BlockInfo) {
                $*JMETH.cr_outer(self.cuid_to_qbid($outer.qast.cuid));
            } else {
                $*JMETH.cr_outer(-1); # marks as coderef
            }

            # Various common arguments we always take; actual args are sorted
            # out later after looking through the block for params.
            $*JMETH.add_argument('cu', $TYPE_CU);
            $*JMETH.add_argument('tc', $TYPE_TC);
            $*JMETH.add_argument('cr', $TYPE_CR);
            $*JMETH.add_argument('csd', $TYPE_CSD);
            $*JMETH.add_argument('resume', $TYPE_RESUME);

            # Set up temporaries allocator.
            my $*BLOCK_TA := BlockTempAlloc.new();
            my $*TA := $*BLOCK_TA;

            # Compile method body -- or hand it whole to the code engine.
            # The choice is made here, at compile time, exactly as it is
            # for regexes: an encoded block's body is one codeRun call
            # (parameter binding included), and there is no bytecode body
            # to fall back to. Precompiled units are eligible when the
            # encoder's NQP_CODE_PRECOMP knob says so: the program bakes
            # into the class file as a string constant, exactly as an rx
            # descriptor does.
            my $body;
            my int $engine_body := 0;
            my $*STACK := StackState.new();
            my $*NEED_ARGS_ARRAY := 0;
            {
                my $*BLOCK := $block;
                my $*WANT;
                my str $engine_prog := '';
                unless $node.custom_args {
                    $engine_prog := QAST::TruffleEncoder.encode_block($node, $block, self,
                        :comp_mode($*COMP_MODE));
                }
                if $engine_prog ne '' {
                    $engine_body := 1;
                    my $il := JAST::InstructionList.new();
                    # A jar-bound unit's programs travel in one sidecar,
                    # referenced by index -- one string constant per
                    # program overflowed CORE.c's constant pool (71010
                    # entries against the 65535 limit). Everything else
                    # keeps the string road.
                    my int $as_index := $*COMP_MODE
                        && %*COMPILING<%?OPTIONS><target> eq 'jar';
                    if $as_index {
                        my int $pidx := nqp::elems(@*ENGINE_PROGRAMS);
                        nqp::push_s(@*ENGINE_PROGRAMS, $engine_prog);
                        $il.append(JAST::PushIndex.new( :value($pidx) ));
                    }
                    else {
                        $il.append(JAST::PushSVal.new( :value($engine_prog) ));
                    }
                    $il.append($ALOAD_0);
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
                    $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'),
                        'Lorg/raku/nqp/runtime/CodeEngines;',
                        $as_index ?? 'codeRunIdx' !! 'codeRun', 'Void',
                        ($as_index ?? 'Integer' !! $TYPE_STR),
                        $TYPE_CU, $TYPE_TC, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ" ));
                    $body := result($il, $RT_VOID);
                    $*STACK.obtain(NQPMu, $body);
                }
                else {
                    $body := self.compile_all_the_stmts($node.list, :node($node.node));
                    $*STACK.obtain(NQPMu, $body);
                }
            }

            # Stash lexical names.
            my @lex_names := $block.lexical_names_by_type();
            $*JMETH.cr_olex(@lex_names[$RT_OBJ]);
            $*JMETH.cr_ilex(@lex_names[$RT_INT]);
            $*JMETH.cr_nlex(@lex_names[$RT_NUM]);
            $*JMETH.cr_slex(@lex_names[$RT_STR]);

            # If we have custom args processing, we always take an args array.
            # An engine body does too: arity check and parameter binding
            # happen inside the program, from the raw csd and args.
            my $il := JAST::InstructionList.new();
            if $node.custom_args || $engine_body {
                $*JMETH.add_argument('__args', "[$TYPE_OBJ");
            }
            elsif !self.try_setup_args_expectation($*JMETH, $block, $il) {
                # Analyze parameters to get count of required/optional and make sure
                # all is in order.
                my int $pos_required := 0;
                my int $pos_optional := 0;
                my int $pos_slurpy   := 0;
                for $block.params {
                    if $_.named {
                        # Don't count.
                    }
                    elsif $_.slurpy {
                        $pos_slurpy := 1;
                    }
                    elsif $_.default {
                        if $pos_slurpy {
                            nqp::die("Optional positionals must come before all slurpy positionals");
                        }
                        $pos_optional++;
                    }
                    else {
                        if $pos_optional {
                            nqp::die("Required positionals must come before all optional positionals");
                        }
                        if $pos_slurpy {
                            nqp::die("Required positionals must come before all slurpy positionals");
                        }
                        $pos_required++;
                    }
                }

                # Take args array and emit arity check instruction.
                $*JMETH.add_argument('__args', "[$TYPE_OBJ");
                $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
                $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
                $il.append(JAST::PushIndex.new( :value($pos_required) ));
                $il.append(JAST::PushIndex.new( :value($pos_slurpy ?? -1 !! $pos_required + $pos_optional) ));
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "checkarity", $TYPE_CSD, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ", 'Integer', 'Integer' ));
                $il.append(JAST::Instruction.new( :op('astore'), 'csd' ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('getfield'), $TYPE_TC, 'flatArgs', "[$TYPE_OBJ" ));
                $il.append(JAST::Instruction.new( :op('astore'), '__args' ));

                # Emit instructions to load each parameter.
                my int $param_idx := 0;
                for $block.params {
                    my $type;
                    if $_.slurpy {
                        $type := $RT_OBJ;
                        $il.append($ALOAD_1);
                        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                        $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
                        $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
                        if $_.named {
                            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "namedslurpy", $TYPE_SMO, $TYPE_TC, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ" ));
                        }
                        else {
                            $il.append(JAST::PushIndex.new( :value($pos_required + $pos_optional) ));
                            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "posslurpy", $TYPE_SMO, $TYPE_TC, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ", 'Integer' ));
                        }
                    }
                    else {
                        $type    := rttype_from_typeobj($_.returns);
                        my $jt   := jtype($type);
                        my $tc   := typechar($type);
                        my $opt  := $_.default ?? "opt_" !! "";
                        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                        $il.append(JAST::Instruction.new( :op('aload'), 'csd' ));
                        $il.append(JAST::Instruction.new( :op('aload'), '__args' ));
                        if $_.named {
                            $il.append(JAST::PushSVal.new( :value($_.named) ));
                            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "namedparam_$opt$tc", $jt, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ", $TYPE_STR ));
                        }
                        else {
                            $il.append(JAST::PushIndex.new( :value($param_idx) ));
                            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "posparam_$opt$tc", $jt, $TYPE_CF, $TYPE_CSD, "[$TYPE_OBJ", 'Integer' ));
                        }
                        if $opt {
                            my $lbl := JAST::Label.new( :name(self.unique("opt_param")) );
                            $il.append($ALOAD_1);
                            $il.append(JAST::Instruction.new( :op('getfield'), $TYPE_TC,
                                'lastParameterExisted', "Integer" ));
                            $il.append(JAST::Instruction.new( :op('ifne'), $lbl ));
                            $il.append(pop_ins($type));
                            {
                                my $*BLOCK := $block;
                                my $default := self.as_jast($_.default, :want($type));
                                $il.append($default.jast);
                                $*STACK.obtain($il, $default);
                            }
                            $il.append($lbl);
                        }
                    }
                    # MoarVM fetches a sized parameter into a sized register,
                    # so the argument arrives already truncated. Every local
                    # here is full width, so do it explicitly. A slurpy or a
                    # full-width parameter emits nothing.
                    emit_sized_native_trunc($il, $_.returns, $type);
                    if $_.scope eq 'local' {
                        $il.append(JAST::Instruction.new( :op(store_ins($type)), $_.name ));
                    }
                    else {
                        my $jtype := jtype($type);
                        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                        $il.append(JAST::PushIndex.new( :value($block.lexical_idx($_.name)) ));
                        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            'bindlex_' ~ typechar($type), $jtype, $jtype, $TYPE_CF, 'Integer' ));
                        $il.append(pop_ins($type));
                    }
                    self.emit_param_tasks($il, $block, $_);
                    $param_idx++;
                }
            }
            $args_expectation := $*JMETH.args_expectation();

            # Add all the locals.
            my @all_locals;
            for $block.locals { nqp::push(@all_locals, $block.local_info($_.name)) }
            $*BLOCK_TA.add_temps_to_set(@all_locals);

            for @all_locals {
                my $name := $_[0];
                my $type := $_[1];
                $*JMETH.add_local($name, jtype($type));
                # Use $*JMETH so it goes into the prelude section and doesn't clobber the assignments above...
                if $type == $RT_INT {
                    $*JMETH.append(JAST::PushIVal.new( :value(0) ));
                }
                elsif $type == $RT_UINT {
                    $*JMETH.append(JAST::PushIVal.new( :value(0) ));
                }
                elsif $type == $RT_NUM {
                    $*JMETH.append(JAST::PushNVal.new( :value(0) ));
                }
                else {
                    $*JMETH.append($ACONST_NULL);
                }
                $*JMETH.append(JAST::Instruction.new( :op(store_ins($type)), $name ));
            }

            # Flatten handlers and store.
            my @flat_handlers := [nqp::elems(@handlers)];
            for @handlers {
                nqp::push(@flat_handlers, nqp::elems($_));
                for $_ { nqp::push(@flat_handlers, $_) }
            }
            $*JMETH.cr_handlers(@flat_handlers);

            # Add method body JAST.
            $il.append($body.jast);

            # Store return value. An engine body already stored it, typed,
            # inside codeRun.
            unless $engine_body {
                $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    'return_' ~ typechar($body.type), 'Void', jtype($body.type), $TYPE_CF ));
            }

            # Make sure this goes before the body.
            my int $save_sites := $block.num_save_sites;
            if $save_sites {
                $*JMETH.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $*JMETH.append(JAST::Instruction.new( :op('ifnonnull'), JAST::Label.new( :name('RESUME') ) ));
            }

            # Emit prelude (after checking for resume). This creates and stashes the CallFrame.
            $*JMETH.add_local('cf', $TYPE_CF);
            $*JMETH.append(JAST::Instruction.new( :op('new'), $TYPE_CF ));
            $*JMETH.append($DUP);
            $*JMETH.append($ALOAD_1);
            $*JMETH.append(JAST::Instruction.new( :op('aload'), 'cr' ));
            $*JMETH.append(JAST::Instruction.new( :op('invokespecial'), $TYPE_CF, '<init>',
                'Void', $TYPE_TC, $TYPE_CR ));
            $*JMETH.append(JAST::Instruction.new( :op('astore'), 'cf' ));

            # Add redo label. We might need to handle a control exception.
            my $meth_redo_lab := JAST::Label.new( :name(self.unique('meth_redo_lab_')) );
            $*JMETH.append($meth_redo_lab);

            # Emit the postlude. We catch any exceptions. Control ones are
            # rethrown, after calling CallFrame.leave. Others are passed on to
            # dieInternal. Finally, if there's no exception, we also need to
            # call CallFrame.leave.
            my $posthan := JAST::InstructionList.new();
            my $nclab   := JAST::Label.new( :name('non_cont_ex') );
            $posthan.append($DUP);
            $posthan.append(JAST::Instruction.new( :op('instanceof'), $TYPE_EX_CONT ));
            $posthan.append(JAST::Instruction.new( :op('ifeq'), $nclab ));
            $posthan.append(JAST::Instruction.new( :op('aload'), 'cf' ));
            $posthan.append(JAST::Instruction.new( :op('invokevirtual'),
                $TYPE_CF, 'leave', 'Void' ));
            $posthan.append($ATHROW);
            $posthan.append($nclab);
            $posthan.append($ALOAD_1);
            $posthan.append($SWAP);
            $posthan.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_EH, 'dieInternal', $TYPE_EX_RT, $TYPE_TC, $TYPE_THROWABLE ));
            $posthan.append($ATHROW);
            $*JMETH.append(JAST::TryCatch.new( :try($il), :catch($posthan),
                :type($TYPE_THROWABLE) ));
            $*JMETH.append(JAST::Instruction.new( :op('aload'), 'cf' ));
            $*JMETH.append(JAST::Instruction.new( :op('invokevirtual'),
                $TYPE_CF, 'leave', 'Void' ));
            $*JMETH.append($RETURN);

            if $save_sites {
                my $saver := JAST::InstructionList.new;
                my $resume := JAST::InstructionList.new;

                $saver.append(JAST::Label.new( :name( 'SAVER' ) ));
                $saver.append($ACONST_NULL);

                my @merged;
                # don't save/reload the resume pointer (could get messy :p) or the thread context (restored separately since we can change threads)
                # or the callframe (can also change)
                # also self doesn't get saved/restored, but that's OK because the resume handle is primed with it.
                for $*JMETH.arguments { nqp::push(@merged, $_) unless $_[0] eq 'resume' || $_[0] eq 'tc' || $_[0] eq 'cu' }
                for $*JMETH.locals { nqp::push(@merged, $_) unless $_[0] eq 'cf' }

                my int $i := 0;
                my int $ict := +@merged;

                $saver.append(JAST::PushIndex.new( :value(+@merged) ));
                $saver.append(JAST::Instruction.new( :op('anewarray'), $TYPE_OBJ ));

                $resume.append(JAST::Label.new( :name( 'RESUME' ) ));
                $resume.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $resume.append(JAST::Instruction.new( :op('getfield'), $TYPE_RESUME, 'tc', $TYPE_TC ));
                $resume.append(JAST::Instruction.new( :op('astore'), 'tc' ));
                $resume.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $resume.append(JAST::Instruction.new( :op('getfield'), $TYPE_RESUME, 'callFrame', $TYPE_CF ));
                $resume.append(JAST::Instruction.new( :op('astore'), 'cf' ));
                $resume.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $resume.append(JAST::Instruction.new( :op('getfield'), $TYPE_RESUME, 'saveSpace', '['~$TYPE_OBJ ));

                for @merged {
                    $saver.append($DUP);
                    $saver.append(JAST::PushIndex.new( :value($i) ));

                    $resume.append($DUP) if $i+1 != $ict; # assuming @merged > 0, which is guaranteed currently
                    $resume.append(JAST::PushIndex.new( :value($i) ));
                    $resume.append($AALOAD);
                    $i++;

                    # later, we might want to consolidate multiple doubles/longs into a single part of the save record
                    if $_[1] eq 'Double' {
                        $saver.append(JAST::Instruction.new( :op('dload'), $_[0] ));
                        $saver.append(JAST::Instruction.new( :op('invokestatic'),
                            $TYPE_DOUBLE, 'valueOf', $TYPE_DOUBLE, 'Double' ));

                        $resume.append(JAST::Instruction.new( :op('checkcast'), $TYPE_DOUBLE ));
                        $resume.append(JAST::Instruction.new( :op('invokevirtual'),
                            $TYPE_DOUBLE, 'doubleValue', 'Double' ));
                        $resume.append(JAST::Instruction.new( :op('dstore'), $_[0] ));
                    }
                    elsif $_[1] eq 'Long' {
                        $saver.append(JAST::Instruction.new( :op('lload'), $_[0] ));
                        $saver.append(JAST::Instruction.new( :op('invokestatic'),
                            $TYPE_LONG, 'valueOf', $TYPE_LONG, 'Long' ));

                        $resume.append(JAST::Instruction.new( :op('checkcast'), $TYPE_LONG ));
                        $resume.append(JAST::Instruction.new( :op('invokevirtual'),
                            $TYPE_LONG, 'longValue', 'Long' ));
                        $resume.append(JAST::Instruction.new( :op('lstore'), $_[0] ));
                    }
                    else {
                        $saver.append(JAST::Instruction.new( :op('aload'), $_[0] ));
                        $resume.append(JAST::Instruction.new( :op('checkcast'), $_[1] ));
                        $resume.append(JAST::Instruction.new( :op('astore'), $_[0] ));
                    }

                    $saver.append($AASTORE);
                }

                $saver.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $saver.append(JAST::Instruction.new( :op('invokevirtual'),
                    $TYPE_EX_SAVE, 'pushFrame', $TYPE_EX_SAVE, "Integer", $TYPE_MH, '['~$TYPE_OBJ, $TYPE_CF ));
                $saver.append($ATHROW);

                # Wrap call to resumeNextSave in try/catch to catch UnwindExceptions.
                my $tryil := JAST::InstructionList.new();
                $tryil.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $tryil.append(JAST::Instruction.new( :op('invokevirtual'), $TYPE_RESUME, 'resumeNextSave', 'Void' ));

                my $last_lab := JAST::Label.new( :name(self.unique('last_lab_')) );
                my $next_lab := JAST::Label.new( :name(self.unique('next_lab_')) );
                my $redo_lab := JAST::Label.new( :name(self.unique('redo_lab_')) );
                my $catchil := JAST::InstructionList.new();
                $catchil.append($DUP);
                $catchil.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'category', 'Long' ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_LAST) ));
                $catchil.append(JAST::Instruction.new( :op('land') ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_LAST) ));
                $catchil.append($LCMP);
                $catchil.append(JAST::Instruction.new( :op('ifeq'), $last_lab ));
                $catchil.append($DUP);
                $catchil.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'category', 'Long' ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_REDO) ));
                $catchil.append(JAST::Instruction.new( :op('land') ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_REDO) ));
                $catchil.append($LCMP);
                $catchil.append(JAST::Instruction.new( :op('ifeq'), $redo_lab ));
                $catchil.append($DUP);
                $catchil.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'category', 'Long' ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_NEXT) ));
                $catchil.append(JAST::Instruction.new( :op('land') ));
                $catchil.append(JAST::PushIVal.new( :value($EX_CAT_NEXT) ));
                $catchil.append($LCMP);
                $catchil.append(JAST::Instruction.new( :op('ifeq'), $next_lab ));
                # Throw if not branched before.
                $catchil.append($ATHROW);
                # Handle last, redo and next; TODO: support labels
                $catchil.append($last_lab);
                $catchil.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $catchil.append(JAST::Instruction.new( :op('invokevirtual'), $TYPE_CF, 'leave', 'Void' ));
                $catchil.append($RETURN);
                $catchil.append($redo_lab);
                $catchil.append($POP);
                $catchil.append(JAST::Instruction.new( :op('goto'), $meth_redo_lab ));
                $catchil.append($next_lab);
                $catchil.append($POP);
                $resume.append(JAST::TryCatch.new( :try($tryil), :catch($catchil), :type($TYPE_EX_UNWIND) ));

                $resume.append(JAST::Instruction.new( :op('aload'), 'resume' ));
                $resume.append(JAST::Instruction.new( :op('getfield'), $TYPE_RESUME, 'resumePoint', 'Integer' ));
                my $switch := JAST::Instruction.new( :op('tableswitch'), JAST::Label.new( :name('reenter_0') ) );
                my int $labelno := 0;
                while ($labelno < $save_sites) {
                    $switch.push( JAST::Label.new( :name('reenter_'~$labelno) ) );
                    $labelno++;
                }
                $resume.append($switch);

                $*JMETH.append($saver);
                $*JMETH.append($resume);
            }

            # Set exit handler and thunk flags if needed.
            if $node.has_exit_handler {
                $*JMETH.has_exit_handler(1);
            }
            if $node.is_thunk {
                $*JMETH.is_thunk(1);
            }

            # Finalize method and add it to the class.
            $*JCLASS.add_method($*JMETH);
        }
        else {
            # A block already compiled into this unit still has to be called
            # with the descriptor its method really has: an immediate call
            # below picks the signature from $args_expectation, and leaving it
            # at the default would emit a call to an overload that does not
            # exist.
            $args_expectation := $*CODEREFS.cuid_to_args_expectation($node.cuid);
        }

        # Now go by block type for producing a result; also need to special-case
        # the top-level, where we need no result.
        if nqp::istype($*STACK, StackState) {
            my $blocktype := $node.blocktype;
            if $blocktype eq '' || $blocktype eq 'declaration' || $blocktype eq 'declaration_static' {
                return self.as_jast(QAST::BVal.new( :value($node) ));
            }
            elsif $blocktype eq 'immediate' || $blocktype eq 'immediate_static' {
                # Can emit a direct JVM level call. First, get self, TC,
                # code ref, and callsite descriptor (empty) onto the stack.
                # May or may not need args array.
                my $il := JAST::InstructionList.new();
                $*STACK.spill_to_locals($il);
                $il.append($ALOAD_0);
                $il.append($ALOAD_1);
                $il.append($ALOAD_0);
                $il.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($node.cuid)) ));
                $il.append(JAST::Instruction.new( :op('invokevirtual'),
                    $TYPE_CU, 'lookupCodeRef', $TYPE_CR, 'I' ));
                $il.append(JAST::Instruction.new( :op('getstatic'),
                    $TYPE_OPS, 'emptyCallSite', $TYPE_CSD ));
                $il.append($ACONST_NULL);
                if $args_expectation != $ARG_EXP_NO_ARGS {
                    $il.append(JAST::Instruction.new( :op('getstatic'),
                        $TYPE_OPS, 'emptyArgList', "[$TYPE_OBJ" ));
                }

                # Emit the virtual call.
                if $args_expectation == $ARG_EXP_NO_ARGS {
                    $il.append(savesite(JAST::Instruction.new( :op('invokestatic'),
                        'L' ~ $*JCLASS.name ~ ';',
                        $*CODEREFS.cuid_to_jastmethname($node.cuid),
                        'V', $TYPE_CU, $TYPE_TC, $TYPE_CR, $TYPE_CSD, $TYPE_RESUME )));
                }
                else {
                    $il.append(savesite(JAST::Instruction.new( :op('invokestatic'),
                        'L' ~ $*JCLASS.name ~ ';',
                        $*CODEREFS.cuid_to_jastmethname($node.cuid),
                        'V', $TYPE_CU, $TYPE_TC, $TYPE_CR, $TYPE_CSD, $TYPE_RESUME, "[$TYPE_OBJ" )));
                }

                # Load result onto the stack, unless in void context.
                if $*WANT != $RT_VOID {
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        'result_' ~ typechar($RT_OBJ), jtype($RT_OBJ), $TYPE_CF ));
                    return result($il, $RT_OBJ)
                }
                else {
                    return result($il, $RT_VOID)
                }
            }
            elsif $blocktype eq 'raw' {
                return self.as_jast(QAST::Op.new( :op('null') ));
            }
            else {
                nqp::die("Unrecognized block type '$blocktype'");
            }
        }
    }

    multi method as_jast(QAST::Stmts $node, :$want) {
        self.compile_all_the_stmts($node.list, $node.resultchild, :node($node.node))
    }

    multi method as_jast(QAST::Stmt $node, :$want) {
        my $*TA := StmtTempAlloc.new();
        my $result := self.compile_all_the_stmts($node.list, $node.resultchild, :node($node.node));
        $*TA.release();
        $result
    }

    method compile_all_the_stmts(@stmts, $resultchild?, :$node) {
        unless @stmts {
            # Empty statement list will break things.
            @stmts[0] := QAST::Op.new( :op('null') );
        }
        my $last_res;
        my $il := JAST::InstructionList.new();
        my int $i := 0;
        my int $n := +@stmts;
        my $all_void := $*WANT == $RT_VOID;
        my $res_temp;
        my $res_type;
        unless nqp::defined($resultchild) {
            $resultchild := $n - 1;
        }

        for @stmts {
            if $_.node && nqp::can($_.node,'orig') {
                my $node := $_.node;
                my $line;
                if nqp::can($node, 'orig-line') {
                    $line := $node.orig-line();
                }
                else {
                    $line := HLL::Compiler.lineof($node.orig(), $node.from(), :cache(1), :directives(0));
                }
                $il.append(JAST::Annotation.new( :line($line) ));

                # A #line directive between this method's statements changes
                # what the raw line maps to; record the section so backtraces
                # can honor it (the method-level mapping only covers the
                # block's declaration point).
                if $*JMETH.cr_file {
                    if nqp::can($node, 'file') && nqp::can($node, 'line') {
                        my $mfile := $node.file;
                        $*JMETH.cr_add_section($line, $node.line, ~$mfile) if $mfile;
                    }
                    else {
                        my $line-file := HLL::Compiler.linefileof(
                            $node.orig(), $node.from(), :cache(1), :directives(1));
                        $*JMETH.cr_add_section($line, $line-file[0], ~$line-file[1])
                            if $line-file[1];
                    }
                }
            }

            my $void := $all_void || $i != $resultchild;
            if $void {
                if nqp::istype($_, QAST::Want) {
                    $_ := want($_, $RT_VOID);
                }
                $last_res := self.as_jast($_, :want($RT_VOID));
            }
            else {
                $last_res := self.as_jast($_);
            }
            # variables with fallback can have side effects and cannot be elided,
            # nor can a local contvar declaration: unlike a lexical one, which
            # the frame sets up from its lex-value table, it is initialized by
            # the code compiled for the declaration itself
            $il.append($last_res.jast)
                unless $void && nqp::istype($_, QAST::Var)
                    && !nqp::istype($_, QAST::VarWithFallback)
                    && !($_.decl eq 'contvar' && $_.scope eq 'local');
            $*STACK.obtain($il, $last_res);
            if !$all_void && $resultchild == $i && $resultchild != $n - 1 {
                $res_type := $last_res.type;
                $res_temp := fresh($res_type);
                $il.append(JAST::Instruction.new( :op(store_ins($res_type)), $res_temp ));
            }
            $i := $i + 1;
        }
        if $res_temp {
            $il.append(JAST::Instruction.new( :op(load_ins($res_type)), $res_temp ));
            result($il, $res_type)
        }
        else {
            result($il, $last_res.type)
        }
    }

    multi method as_jast(QAST::Op $node, :$want) {
        my $hll := $*HLL // '';
        QAST::OperationsJAST.compile_op(self, $hll, $node);
    }

    multi method as_jast(QAST::VM $node, :$want) {
        if $node.supports('jvm') {
            return nqp::defined($want)
                ?? self.as_jast($node.alternative('jvm'), :$want)
                !! self.as_jast($node.alternative('jvm'));
        }
        else {
            nqp::die("To compile on the JVM backend, QAST::VM must have an alternative 'jvm'" ~
                ($node.supports('pirop')
                    ?? ' (got pirop = ' ~ $node.alternative('pirop') ~ ')'
                    !! ''));
        }
    }

    multi method as_jast(QAST::Var $node, :$want) {
        self.compile_var($node)
    }

    multi method as_jast(QAST::VarWithFallback $node, :$want) {
        if $*BINDVAL {
            self.compile_var($node)
        }
        else {
            my $var_res := self.compile_var($node);
            if ($var_res.type != $RT_OBJ) {
                return $var_res;
            }

            my $il := JAST::InstructionList.new();
            $il.append($var_res.jast);
            $*STACK.spill_to_locals($il);
            $*STACK.obtain($il, $var_res);

            my $lbl := JAST::Label.new(:name($node.unique('fallback')));
            $il.append($DUP);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'isnull', 'Long', $TYPE_SMO));
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $lbl ));

            my $fallback_res := self.as_jast($node.fallback, :want($RT_OBJ));
            $il.append($POP);
            $il.append($fallback_res.jast);
            $*STACK.obtain($il, $fallback_res);
            $il.append($lbl);

            result($il, $RT_OBJ);
        }
    }

    method compile_var($node) {
        my $scope := $node.scope;
        my $decl  := $node.decl;

        # Handle any declarations; after this, we fall through to the
        # lookup code.
        if $decl {
            # If it's a parameter, add it to the things we should bind
            # at block entry.
            if $decl eq 'param' {
                if $scope eq 'local' || $scope eq 'lexical' {
                    $*BLOCK.add_param($node);
                }
                else {
                    nqp::die("Parameter cannot have scope '$scope'; use 'local' or 'lexical'");
                }
            }
            elsif $decl eq 'var' {
                if $scope eq 'local' {
                    $*BLOCK.add_local($node);
                }
                elsif $scope eq 'lexical' {
                    $*BLOCK.add_lexical($node);
                }
                elsif $scope eq 'lexicalref' {
                    $*BLOCK.add_lexicalref($node);
                }
                else {
                    nqp::die("Cannot declare variable with scope '$scope'; use 'local' or 'lexical' or 'lexicalref'");
                }
            }
            elsif $decl eq 'static' {
                if $scope ne 'lexical' {
                    nqp::die("Can only use 'static' decl with scope 'lexical'");
                }
                $*BLOCK.add_lexical($node, :is_static);
            }
            elsif $decl eq 'contvar' {
                if $scope eq 'local' {
                    # A lexical contvar is set up from the block's lex-value
                    # table when the frame is entered; a local has no such
                    # table, so clone the prototype container where the
                    # declaration itself appears. Emitters put these in the
                    # block's declaration prologue, ahead of any use.
                    $*BLOCK.add_local($node);
                    # Only when nothing has put a container there already.
                    # Parameters are bound in the frame prologue, ahead of the
                    # body this declaration sits in, so a lowered parameter's
                    # local arrives holding the argument -- overwriting it with
                    # a fresh container would throw the argument away. Locals
                    # start out null, so anything else still vivifies.
                    return self.as_jast(QAST::Op.new(
                        :op('if'),
                        QAST::Op.new( :op('isnull'),
                            QAST::Var.new( :name($node.name), :scope('local') ) ),
                        QAST::Op.new(
                            :op('bind'),
                            QAST::Var.new( :name($node.name), :scope('local') ),
                            QAST::Op.new(
                                # clone_nd, not clone: the prototype *is* a
                                # container, and clone decontainerizes first.
                                :op('clone_nd'),
                                QAST::WVal.new( :value($node.value) )
                            )
                        ),
                        QAST::Var.new( :name($node.name), :scope('local') )
                    ), :want($RT_OBJ));
                }
                elsif $scope ne 'lexical' {
                    nqp::die("Can only use 'contvar' decl with scope 'lexical' or 'local', got scope '$scope'");
                }
                $*BLOCK.add_lexical($node, :is_cont);
            }
            elsif $decl eq 'statevar' {
                if $scope ne 'lexical' {
                    nqp::die("Can only use 'statevar' decl with scope 'lexical'");
                }
                $*BLOCK.add_lexical($node, :is_state);
            }
            else {
                nqp::die("Don't understand declaration type '$decl'");
            }
        }

        # If there's no scope, figure it out from the symbol tables if
        # possible.
        my $name := $node.name;
        if $scope eq '' {
            my $cur_block := $*BLOCK;
            while nqp::istype($cur_block, BlockInfo) {
                my %sym := $cur_block.qast.symbol($name);
                if %sym {
                    $scope := %sym<scope>;
                    $cur_block := NQPMu;
                }
                else {
                    $cur_block := $cur_block.outer();
                }
            }
            if $scope eq '' {
                nqp::die("No scope specified or locatable in the symbol table for '$name'");
            }
        }

        my $want := $*WANT;
        # If we know what we're after, some opts:
        if nqp::isconcrete($want) {
            if $want == $RT_VOID {
                return result(JAST::Instruction.new(:op('nop')), $RT_VOID);
            }

            # Both lexicalref and attributeref in the context we want a
            # non-object devolve to lexical and attribute, since we'd only
            # de-ref right away anyway.
            if nqp::defined($want) && $want != $RT_OBJ {
                $scope := 'lexical'   if $scope eq 'lexicalref';
                $scope := 'attribute' if $scope eq 'attributeref';
            }
        }

        # Now go by scope.
        if $scope eq 'local' {
            my $info := $*BLOCK.local_info($name);
            my $type := $info[1];
            if nqp::defined($type) {
                my $il := JAST::InstructionList.new();
                if $*BINDVAL {
                    my $valres := self.as_jast_clear_bindval($*BINDVAL, :want($type));
                    $il.append($valres.jast);
                    $*STACK.obtain($il, $valres);
                    # No truncation here: locals match MoarVM registers,
                    # which are full width whatever the declared type; only
                    # lexical slots, attributes and boxes store sized.
                    $il.append(dup_ins($type));
                    $il.append(JAST::Instruction.new( :op(store_ins($type)), $info[0] ));
                }
                else {
                    $il.append(JAST::Instruction.new( :op(load_ins($type)), $info[0] ));
                }
                return result($il, $type);
            }
            else {
                nqp::die("Cannot reference undeclared local '$name'");
            }
        }
        elsif $scope eq 'lexical' || $scope eq 'typevar' || $scope eq 'contextual' {
            # See if it's declared in the local scope.
            my int $local  := 0;
            my int $ref    := 0;
            my int $scopes := 0;
            my $type       := $*BLOCK.lexical_type($name);
            my $reftype    := $*BLOCK.lexicalref_type($name);
            my $declarer;
            if nqp::defined($type) {
                # It is. Nothing more to do.
                $local := 1;
            }
            elsif nqp::defined($reftype) {
                # It is, and also a ref. Nothing more to do.
                $local := 1;
                $ref := 1;
                $type := $RT_OBJ;
            }
            elsif $scope eq 'lexical' || $scope eq 'typevar' {
                # Try to find it in an outer scope.
                my int $i := 1;
                my $cur_block := $*BLOCK.outer();
                while nqp::istype($cur_block, BlockInfo) {
                    if $cur_block.qast.ann('DYN_COMP_WRAPPER') {
                        $cur_block := NQPMu;
                    }
                    else {
                        $type := $cur_block.lexical_type($name);
                        $reftype := $cur_block.lexicalref_type($name);
                        if nqp::defined($type) {
                            $scopes := $i;
                            $declarer := $cur_block;
                            $cur_block := NQPMu;
                        }
                        elsif nqp::defined($reftype) {
                            $scopes := $i;
                            $declarer := $cur_block;
                            $ref := 1;
                            $cur_block := NQPMu;
                            $type := $RT_OBJ;
                        }
                        else {
                            $cur_block := $cur_block.outer();
                            $i++;
                        }
                    }
                }
            }

            # If we didn't find it anywhere, it musta been explicitly marked as
            # lexical. Take the type from .returns and rewrite to a more dynamic
            # lookup.
            unless $local || $scopes {
                if $scope eq 'lexical' || $scope eq 'typevar' {
                    $type := rttype_from_typeobj($node.returns);
                    my $char := $type == $RT_OBJ ?? '' !! '_' ~ typechar($type);
                    my $name_sval := QAST::SVal.new( :value($name) );
                    return self.as_jast_clear_bindval($*BINDVAL
                        ?? QAST::Op.new( :op("bindlex$char"), $name_sval, $*BINDVAL )
                        !! QAST::Op.new( :op("getlex$char"), $name_sval ));
                }
                else {
                    my $il := JAST::InstructionList.new();
                    if $*BINDVAL {
                        my $valres := self.as_jast_clear_bindval($*BINDVAL, :want($RT_OBJ));
                        $il.append($valres.jast);
                        $*STACK.obtain($il, $valres);
                    }
                    $il.append(JAST::PushSVal.new( :value($name) ));
                    $il.append($SWAP) if $*BINDVAL;
                    $il.append($ALOAD_1);
                    $il.append($*BINDVAL
                        ?? JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "bindlexdyn", $TYPE_SMO, $TYPE_STR, $TYPE_SMO, $TYPE_TC )
                        !! JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                                "getlexdyn", $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
                    return result($il, $RT_OBJ);
                }
            }

            # Map type in a couple of ways we'll need.
            my $jtype := $ref ?? $TYPE_SMO !! jtype($type);
            my $c     := $ref ?? 'o' !! typechar($type);

            # If binding, always want the thing we're binding evaluated.
            my $il := JAST::InstructionList.new();
            if $*BINDVAL {
                if $ref {
                    nqp::die('Cannot bind to QAST::Var resolving to a lexicalref');
                }
                my $valres := self.as_jast_clear_bindval($*BINDVAL, :want($type));
                $il.append($valres.jast);
                $*STACK.obtain($il, $valres);
                my $decl-block := $local ?? $*BLOCK !! $declarer;
                emit_sized_native_trunc($il,
                    nqp::ifnull($decl-block.lexical_returns($name), nqp::null()), $type);
            }

            # If it's declared in the local scope...
            if $local {
                $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
                $il.append($*BINDVAL
                    ?? JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            "bindlex_$c", $jtype, $jtype, $TYPE_CF, 'Integer' )
                    !! JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            "getlex_$c", $jtype, $TYPE_CF, 'Integer' ));
            }

            # Otherwise it must be a known number of scopes out.
            else {
                $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                $il.append(JAST::PushIndex.new( :value($declarer.lexical_idx($name)) ));
                $il.append(JAST::PushIndex.new( :value($scopes) ));
                $il.append($*BINDVAL
                    ?? JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            "bindlex_{$c}_si", $jtype, $jtype, $TYPE_CF, 'Integer', 'Integer' )
                    !! JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            "getlex_{$c}_si", $jtype, $TYPE_CF, 'Integer', 'Integer' ));
            }

            return result($il, $type);
        }
        elsif $scope eq 'lexicalref' {
            # See if it's declared in the local scope.
            my int $local  := 0;
            my int $ref    := 0;
            my int $scopes := 0;
            my $type       := $*BLOCK.lexical_type($name);
            my $reftype    := $*BLOCK.lexicalref_type($name);
            my $declarer;
            if nqp::defined($type) {
                # It is. Nothing more to do.
                $local := 1;
            }
            elsif nqp::defined($reftype) {
                # It is, and also a ref. Nothing more to do.
                $local := 1;
                $ref := 1;
            }
            else {
                # Try to find it in an outer scope.
                my int $i := 1;
                my $cur_block := $*BLOCK.outer();
                while nqp::istype($cur_block, BlockInfo) {
                    if $cur_block.qast.ann('DYN_COMP_WRAPPER') {
                        $cur_block := NQPMu;
                    }
                    else {
                        $type := $cur_block.lexical_type($name);
                        $reftype := $cur_block.lexicalref_type($name);
                        if nqp::defined($type) {
                            $scopes := $i;
                            $declarer := $cur_block;
                            $cur_block := NQPMu;
                        }
                        elsif nqp::defined($reftype) {
                            $scopes := $i;
                            $declarer := $cur_block;
                            $ref := 1;
                            $cur_block := NQPMu;
                        }
                        else {
                            $cur_block := $cur_block.outer();
                            $i++;
                        }
                    }
                }
            }

            # Can only bind if it's a ref.
            if $*BINDVAL {
                unless $local || $scopes {
                    nqp::die('Cannot bind to late-bound QAST::Var with scope lexicalref');
                }
                unless $ref {
                    nqp::die("Cannot bind to non-reference QAST::Var '{$name}'");
                }
                my $il := JAST::InstructionList.new();
                my $valres := self.as_jast_clear_bindval($*BINDVAL, :want($RT_OBJ));
                $il.append($valres.jast);
                $*STACK.obtain($il, $valres);
                if $local {
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                            "bindlex_o", $TYPE_SMO, $TYPE_SMO, $TYPE_CF, 'Integer' ));
                }
                else {
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::PushIndex.new( :value($declarer.lexical_idx($name)) ));
                    $il.append(JAST::PushIndex.new( :value($scopes) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        "bindlex_o_si", $TYPE_SMO,  $TYPE_SMO, $TYPE_CF, 'Integer', 'Integer' ));
                }
                return result($il, $RT_OBJ);
            }

            # If we didn't find it anywhere, it musta been explicitly marked as
            # lexicalref. Take the type from .returns and rewrite to a more dynamic
            # lookup.
            unless $local || $scopes {
                $type := rttype_from_typeobj($node.returns);
                if $type == $RT_OBJ {
                    nqp::die('Cannot take a reference to a non-native lexical');
                }
                my $char := typechar($type);
                my $name_sval := QAST::SVal.new( :value($name) );
                return self.as_jast(QAST::Op.new( :op("getlexref_$char"), $name_sval ));
            }

            # If it's a ref and we want a ref, just look it up as an object
            # lexical.
            my $il := JAST::InstructionList.new();
            if $ref {
                if $local {
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        "getlex_o", $TYPE_SMO, $TYPE_CF, 'Integer' ));
                }
                else {
                    $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
                    $il.append(JAST::PushIndex.new( :value($declarer.lexical_idx($name)) ));
                    $il.append(JAST::PushIndex.new( :value($scopes) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        "getlex_o_si", $TYPE_SMO, $TYPE_CF, 'Integer', 'Integer' ));
                }
                return result($il, $RT_OBJ);
            }

            # Otherwise, need to take the reference.
            else {
                my $c := typechar($type);
                if $local {
                    $il.append($ALOAD_1);
                    $il.append(JAST::PushIndex.new( :value($*BLOCK.lexical_idx($name)) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        "getlexref_$c", $TYPE_SMO, $TYPE_TC, 'Integer' ));
                }
                else {
                    $il.append($ALOAD_1);
                    $il.append(JAST::PushIndex.new( :value($declarer.lexical_idx($name)) ));
                    $il.append(JAST::PushIndex.new( :value($scopes) ));
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        "getlexref_{$c}_si", $TYPE_SMO, $TYPE_TC, 'Integer', 'Integer' ));
                }
                my $decl-block := $local ?? $*BLOCK !! $declarer;
                my int $szspec := sized_native_ref_spec($decl-block.lexical_returns($name));
                if $szspec {
                    # The long slots carry no width, so a reference to a
                    # sized lexical is told the declared one; its stores
                    # then truncate the way MoarVM's sized registers do.
                    $il.append(JAST::PushIVal.new( :value($szspec) ));
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                        'sizedref', $TYPE_SMO, $TYPE_SMO, 'Long', $TYPE_TC ));
                }
                return result($il, $RT_OBJ);
            }
        }
        elsif $scope eq 'attribute' {
            # Ensure we have object and class handle.
            my @args := $node.list;
            if +@args != 2 {
                nqp::die("An attribute lookup needs an object and a class handle");
            }

            # Compile object, handle and name.
            my $il := JAST::InstructionList.new();
            my $obj_res := self.as_jast_clear_bindval(@args[0], :want($RT_OBJ));
            $il.append($obj_res.jast);
            my $han_res := self.as_jast_clear_bindval(@args[1], :want($RT_OBJ));
            $il.append($han_res.jast);
            my $name_res := self.as_jast_clear_bindval(QAST::SVal.new( :value($name) ), :want($RT_STR));
            $il.append($name_res.jast);

            # Get lookup hint if possible.
            my int $hint := -1;
            if @args[1].has_compile_time_value {
                $hint := nqp::attrhintfor(@args[1].compile_time_value, $name);
            }

            # Go by whether it's a bind or lookup.
            my $type := rttype_from_typeobj($node.returns);
            my $jtype := jtype($type);
            my $suffix := $type == $RT_OBJ ?? '' !! '_' ~ typechar($type);
            if $*BINDVAL {
                my $val_res := self.as_jast_clear_bindval($*BINDVAL, :want($type));
                $il.append($val_res.jast);
                $*STACK.obtain($il, $obj_res, $han_res, $name_res, $val_res);
                $il.append(JAST::PushIVal.new( :value($hint) ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "bindattr$suffix", $jtype, $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $jtype, 'Long', $TYPE_TC ));
            }
            else {
                $*STACK.obtain($il, $obj_res, $han_res, $name_res);
                $il.append(JAST::PushIVal.new( :value($hint) ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "getattr$suffix", $jtype, $TYPE_SMO, $TYPE_SMO, $TYPE_STR, 'Long', $TYPE_TC ));
            }

            return result($il, $type);
        }
        elsif $scope eq 'attributeref' {
            # Ensure we have object and class handle, and aren't binding.
            my @args := $node.list;
            if +@args != 2 {
                nqp::die("An attribute lookup needs an object and a class handle");
            }
            if $*BINDVAL {
                nqp::die("Cannot bind to QAST::Var '{$name}' with scope attributeref");
            }

            # Ensure we've a natively typed attribute to take a ref to.
            my $type := rttype_from_typeobj($node.returns);
            if $type == $RT_OBJ {
                nqp::die("Attribute references can only be to native types");
            }

            # Compile object, handle and name.
            my $il := JAST::InstructionList.new();
            my $obj_res := self.as_jast_clear_bindval(@args[0], :want($RT_OBJ));
            $il.append($obj_res.jast);
            my $han_res := self.as_jast_clear_bindval(@args[1], :want($RT_OBJ));
            $il.append($han_res.jast);
            my $name_res := self.as_jast_clear_bindval(QAST::SVal.new( :value($name) ), :want($RT_STR));
            $il.append($name_res.jast);

            # Emit lookup.
            my $char  := typechar($type);
            $*STACK.obtain($il, $obj_res, $han_res, $name_res);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "getattrref_$char", $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));

            return result($il, $RT_OBJ);
        }
        elsif $scope eq 'positional' {
            return self.as_jast_clear_bindval($*BINDVAL
                ?? QAST::Op.new( :op('positional_bind'), |$node.list, $*BINDVAL)
                !! QAST::Op.new( :op('positional_get'), |$node.list));
        }
        elsif $scope eq 'associative' {
            return self.as_jast_clear_bindval($*BINDVAL
                ?? QAST::Op.new( :op('associative_bind'), |$node.list, $*BINDVAL)
                !! QAST::Op.new( :op('associative_get'), |$node.list));
        }
        else {
            nqp::die("QAST::Var with scope '$scope' NYI");
        }
    }

    method as_jast_clear_bindval($node, :$want) {
        my $*BINDVAL := 0;
        nqp::defined($want) ?? self.as_jast($node, :$want) !! self.as_jast($node)
    }

    method as_jast_in_handler($node, $*HANDLER_IDX, :$want) {
        my $*BINDVAL := 0;
        nqp::defined($want) ?? self.as_jast($node, :$want) !! self.as_jast($node)
    }

    multi method as_jast(QAST::Want $node, :$want) {
        # If we're not in a coercive context, take the default.
        self.as_jast($node[0])
    }

    multi method as_jast(QAST::IVal $node, :$want) {
        result(JAST::PushIVal.new( :value($node.value) ), $RT_INT)
    }

    multi method as_jast(QAST::NVal $node, :$want) {
        result(JAST::PushNVal.new( :value($node.value) ), $RT_NUM)
    }

    my int $split_point := 21845;
    multi method as_jast(QAST::SVal $node, :$want) {
        if nqp::chars($node.value) <= $split_point {
            result(JAST::PushSVal.new( :value($node.value) ), $RT_STR)
        }
        else {
            my @chunks;
            my $value := $node.value;
            while nqp::chars($value) > $split_point {
                nqp::push(@chunks, nqp::substr($value, 0, $split_point));
                $value := nqp::substr($value, $split_point);
            }
            nqp::push(@chunks, $value);
            my $il := JAST::InstructionList.new();
            $il.append(JAST::PushIndex.new( :value(nqp::elems(@chunks)) ));
            $il.append(JAST::Instruction.new( :op('anewarray'), $TYPE_STR ));
            my int $i := 0;
            for @chunks {
                $il.append($DUP);
                $il.append(JAST::PushIndex.new( :value($i++) ));
                $il.append(JAST::PushSVal.new( :value($_) ));
                $il.append($AASTORE);
            }
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'join_literal', $TYPE_STR, "[$TYPE_STR" ));
            result($il, $RT_STR)
        }
    }

    multi method as_jast(QAST::BVal $node, :$want) {
        my $il := JAST::InstructionList.new();
        $il.append($ALOAD_0);
        $il.append(JAST::PushIndex.new( :value(self.cuid_to_qbid($node.value.cuid)) ));
        $il.append(JAST::Instruction.new( :op('invokevirtual'),
            $TYPE_CU, 'lookupCodeRef', $TYPE_CR, 'I' ));
        result($il, $RT_OBJ)
    }

     multi method as_jast(QAST::WVal $node, :$want) {
        my $val    := $node.value;
        my $sc     := nqp::getobjsc($val);
        my $handle := nqp::scgethandle($sc);
        my $idx    := nqp::scgetobjidx($sc, $val);
        my $il     := JAST::InstructionList.new();
        $il.append(JAST::PushSVal.new( :value($handle) ));
        # Push the index as an int and widen: an ldc2_w long would cost a
        # 2-slot constant pool entry per distinct index, which overflows the
        # 65535-entry pool on CORE.c.setting.
        $il.append(JAST::PushIndex.new( :value($idx) ));
        $il.append($I2L);
        $il.append($ALOAD_1);
        # A plain invokestatic, not invokedynamic: WVals are the most common
        # callsite kind by far, and HotSpot (observed on 25.0.3) silently
        # corrupts per-class invokedynamic resolution state once a class has
        # more than 65535 indy instructions — which CORE.c.setting exceeds
        # nearly 3x with indy wvals included.
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'wval', $TYPE_SMO, $TYPE_STR, 'Long', $TYPE_TC ));
        result($il, $RT_OBJ);
    }

    method coerce($res, $desired) {
        my $got := $res.type;
        if $got == $desired {
            return $res;
        }
        else {
            my $coerced := JAST::InstructionList.new();
            $coerced.append($res.jast);
            $*STACK.obtain($coerced, $res);
            $coerced.append(self.coercion($res, $desired));
            return result($coerced, $desired);
        }
    }

    # Expects that the value in need of coercing has already been
    # obtained (and thus is on the stack top). Produces instructions
    # to coerce it. Doesn't touch the stack tracking.
    method coercion($res, $desired) {
        my $il := JAST::InstructionList.new();
        my $got := $res.type;
        if $got == $desired {
            # Nothing to do.
        }
        elsif $desired == $RT_VOID {
            $il.append(pop_ins($got));
        }
        elsif $desired == $RT_OBJ {
            my $hll := $*HLL // '';
            return QAST::OperationsJAST.box(self, $hll, $got);
        }
        elsif $got == $RT_OBJ {
            my $hll := $*HLL // '';
            return QAST::OperationsJAST.unbox(self, $hll, $desired);
        }
        elsif $desired == $RT_INT {
            if $got == $RT_UINT {
            }
            elsif $got == $RT_NUM {
                $il.append($D2L);
            }
            elsif $got == $RT_STR {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_s2i', 'Long', $TYPE_STR ));
            }
            else {
                nqp::die("Unknown coercion case for int");
            }
        }
        elsif $desired == $RT_UINT {
            if $got == $RT_INT {
            }
            elsif $got == $RT_NUM {
                $il.append($D2L);
            }
            elsif $got == $RT_STR {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_s2i', 'Long', $TYPE_STR ));
            }
            else {
                nqp::die("Unknown coercion case for uint");
            }
        }
        elsif $desired == $RT_NUM {
            if $got == $RT_INT {
                $il.append($L2D);
            }
            elsif $got == $RT_UINT {
                $il.append($L2D);
            }
            elsif $got == $RT_STR {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_s2n', 'Double', $TYPE_STR ));
            }
            else {
                nqp::die("Unknown coercion case for num got $got desired $desired");
            }
        }
        elsif $desired == $RT_STR {
            if $got == $RT_INT {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_i2s', $TYPE_STR, 'Long' ));
            }
            elsif $got == $RT_UINT {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_u2s', $TYPE_STR, 'Long' ));
            }
            elsif $got == $RT_NUM {
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'coerce_n2s', $TYPE_STR, 'Double' ));
            }
            else {
                nqp::die("Unknown coercion case for str");
            }
        }
        else {
            nqp::die("Coercion from type '$got' to '$desired' NYI");
        }
        $il
    }

    # Checks if we have reached the correct unwind target. If not, does a
    # rethrow of the handler. Assumes the exception is on the stack top,
    # and that we will not swallow it.
    my $unwind_lbl := 0;
    method unwind_check($il, $desired, :$label, :$outer = 0, :$handler_cares) {
        my $lbl_i := JAST::Label.new( :name('unwind_' ~ $unwind_lbl++) );
        my $lbl_c := JAST::Label.new( :name('unwind_' ~ $unwind_lbl++) );
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'unwindTarget', 'Long' ));
        $il.append(JAST::PushIVal.new( :value($desired) ));
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifeq'), $lbl_i ));
        $il.append($ATHROW);
        $il.append($lbl_i);
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('getfield'), $TYPE_EX_UNWIND, 'unwindCompUnit', $TYPE_CU ));
        $il.append($ALOAD_0);
        $il.append(JAST::Instruction.new( :op('if_acmpeq'), $lbl_c ));
        $il.append($ATHROW);
        $il.append($lbl_c);

        unless $handler_cares {
            $il.append($DUP);
            if $label {
                my $labjast := self.as_jast($label, :want($RT_OBJ));
                $il.append($labjast.jast);
                $*STACK.obtain($il, $labjast);
                $il.append(JAST::PushIVal.new( :value($outer) ));
                $il.append(JAST::Instruction.new( :op('aload'), 'tc' ));
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, '_is_same_label',
                    'Void', $TYPE_EX_UNWIND, $TYPE_SMO, 'Long', $TYPE_TC ));
            }
            else {
                $il.append(JAST::PushIVal.new( :value($outer) ));
                $il.append(JAST::Instruction.new( :op('aload'), 'tc' ));
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, '_rethrow_label',
                    'Void', $TYPE_EX_UNWIND, 'Long', $TYPE_TC ));
            }
        }
    }

    # Wraps a handler with code to set/clear the current handler.
    method delimit_handler($wrap_il, $outer, $inner) {
        my $il := JAST::InstructionList.new();
        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
        $il.append(JAST::PushIVal.new( :value($inner) ));
        $il.append(JAST::Instruction.new( :op('putfield'), $TYPE_CF, 'curHandler', 'Long' ));
        $il.append($wrap_il);
        $il.append(JAST::Instruction.new( :op('aload'), 'cf' ));
        $il.append(JAST::PushIVal.new( :value($outer) ));
        $il.append(JAST::Instruction.new( :op('putfield'), $TYPE_CF, 'curHandler', 'Long' ));
        $il
    }

    # Emits an exception throw.
    sub emit_throw($il, $type = 'Ljava/lang/Exception;') {
        $il.append(JAST::Instruction.new( :op('new'), $type ));
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('invokespecial'), $type, '<init>', 'Void' ));
        $il.append($ATHROW);
    }

    multi method as_jast(QAST::Regex $node, :$want) {
        # A rule the engine covers is handed to it whole, and no matcher is
        # emitted for it at all. The choice is made here, at compile time,
        # because the descriptor either describes the rule faithfully or does
        # not exist -- there is no half of a rule to fall back to.
        my $desc := self.rx_descriptor($node);
        return self.engine_jast($node, $desc) unless nqp::isnull($desc);

        # build the list of (unique) locals we need
        my %*REG;
        my $prefix := self.unique('rx') ~ '_';
        my $reglist := nqp::split(' ', 'start o tgt s pos i off i eos i rep i cur o curclass o bstack o cstack o restart i itemp i altmarks o subcur o');
        while $reglist {
            my $reg := nqp::shift($reglist);
            my $tc := nqp::shift($reglist);
            my $type := $tc eq 'i' ?? int !! $tc eq 's' ?? str !! NQPMu;
            %*REG{$reg} := $prefix ~ $reg;
            $*BLOCK.add_local(QAST::Var.new( :name($prefix ~ $reg), :scope('local'), :returns($type), :decl('var') ));
        }

        # create our labels
        my $startlabel   := JAST::Label.new( :name($prefix ~ 'start') );
        my $endlabel     := JAST::Label.new( :name($prefix ~ 'end') );
        my $donelabel    := JAST::Label.new( :name($prefix ~ 'done') );
        my $restartlabel := JAST::Label.new( :name($prefix ~ 'restart') );
        my $faillabel    := JAST::Label.new( :name($prefix ~ 'fail') );
        my $jumplabel    := JAST::Label.new( :name($prefix ~ 'jump' ));
        my $cutlabel     := JAST::Label.new( :name($prefix ~ 'cut') );
        my $cstacklabel  := JAST::Label.new( :name($prefix ~ 'cstack_done') );
        %*REG<fail>      := $faillabel;

        # label to index mapping, for the jump table
        my @mark_labels;
        my %mark_lookup;
        my &*REGISTER_MARK := sub ($label) {
            my $idx := nqp::elems(@mark_labels);
            nqp::push(@mark_labels, $label);
            %mark_lookup{$label.name} := $idx
        }
        &*REGISTER_MARK($faillabel);

        # common prologue
        my $il := JAST::InstructionList.new();
        my $csa_res := self.as_jast(QAST::Stmt.new(
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                QAST::Op.new(
                    :op('callmethod'), :name('!cursor_start_all'),
                    QAST::Var.new( :name('self'), :scope('local') )
                )),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name("\$\xa2"), :scope('lexical') ),
                QAST::Op.new(
                    :op('bind'),
                    QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(0) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) ),
                QAST::Op.new(
                    :op('unbox_i'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(2) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<curclass>), :scope('local') ),
                QAST::Op.new(
                    :op('atpos'),
                    QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                    QAST::IVal.new( :value(3) )
                )),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<bstack>), :scope('local') ),
                QAST::Op.new(
                    :op('atpos'),
                    QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                    QAST::IVal.new( :value(4) )
                )),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<altmarks>), :scope('local') ),
                QAST::Op.new( :op('create'), QAST::Op.new( :op('bootintarray') ) )
            ),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<restart>), :scope('local'), :returns(int) ),
                QAST::Op.new(
                    :op('unbox_i'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(5) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<tgt>), :scope('local'), :returns(str) ),
                QAST::Op.new(
                    :op('unbox_s'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(1) )
                    )))));
        $il.append($csa_res.jast);
        $*STACK.obtain($il, $csa_res);
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'chars', 'Long', $TYPE_STR ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<eos> ));
        $il.append($ACONST_NULL);
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<cstack> ));
        $il.append($ACONST_NULL);
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<subcur> ));
        $il.append($IVAL_ZERO);
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<restart> ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifne'), $restartlabel ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifgt'), %*REG<fail> ));

        # Compile the regex body itself; if we make it through it, we go to
        # the end and are finished.
        $il.append(self.regex_jast($node));
        $il.append(JAST::Instruction.new( :op('goto'), $endlabel ));

        # Restart.
        $il.append($restartlabel);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cur> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::PushSVal.new( :value('$!cstack') ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "getattr", $TYPE_SMO, $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<cstack> ));

        # Failure/backtrack handling. If no bstack, we're done.
        $il.append($faillabel);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "elems", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifeq'), $donelabel ));

        # Otherwise, start handling the cstack, if it's not empty.
        # The setup done here is used when we backtrack into subrules.
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "pop_i", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "islist", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifeq'), $cstacklabel ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "elems", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifeq'), $cstacklabel ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
        $il.append($IVAL_ONE);
        $il.append($LSUB);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "atpos", $TYPE_SMO, $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<subcur> ));
        $il.append($cstacklabel);

        # Pop rep, pos and mark off the stack and store them.
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($ALOAD_1);
        $il.append($DUP2);
        $il.append($DUP2);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "pop_i", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "pop_i", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "pop_i", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));

        # Handle position and mark special cases.
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($IVAL_MINUSONE);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('iflt'), $donelabel ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('iflt'), $faillabel ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifeq'), $faillabel ));

        # Backtrack the cursor stack.
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append(JAST::Instruction.new( :op('invokestatic'),
            $TYPE_OPS, 'isnull', 'Long', $TYPE_SMO));
        $il.append($IVAL_ONE);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifeq'), $jumplabel ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "islist", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifeq'), $jumplabel ));

        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "elems", 'Long', $TYPE_SMO, $TYPE_TC ));
        $il.append($DUP2);
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('ifeq'), $cutlabel ));

        $il.append($IVAL_ONE);
        $il.append($LSUB);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($DUP_X2);
        $il.append($POP);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));

        $il.append($cutlabel);
        $il.append(JAST::Instruction.new( :op('invokevirtual'), $TYPE_SMO,
            "set_elems", 'Void', $TYPE_TC, 'Long' ));

        # Otherwise, we need to jump to the appropriate label. Emit the
        # jump table.
        $il.append($jumplabel);
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
        $il.append($L2I);
        my $ts := JAST::Instruction.new( :op('tableswitch'), $donelabel );
        for @mark_labels {
            $ts.push($_);
        }
        $il.append($ts);

        # If we make it to here, we failed to match.
        $il.append($donelabel);
        my $fail_res := self.as_jast(QAST::Op.new(
            :op('callmethod'), :name('!cursor_fail'),
            QAST::Var.new( :name(%*REG<cur>), :scope('local') )
        ));
        $il.append($fail_res.jast);
        $*STACK.obtain($il, $fail_res);
        $il.append($POP);

        # Evaluate to the cursor.
        $il.append($endlabel);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cur> ));
        result($il, $RT_OBJ)
    }

    method regex_jast($node) {
        my $rxtype := $node.rxtype() || 'concat';
        self."$rxtype"($node);
    }

    # The descriptor for this rule, or null to keep the bytecode path.
    #
    # There is no whole-engine toggle: every rule the descriptor can encode
    # runs on the engine, and the bytecode path exists only for the rules
    # that still bail (NQP_RX_SURVEY names them). The NQP_RX_* triage knobs
    # below narrow the encodable set per rule or per feature for bisection.
    method rx_descriptor($node) {
        my $desc := QAST::RxDescriptor.encode($node);
        return nqp::null() if nqp::isnull($desc);

        # Triage knobs. Which rules the engine takes over is otherwise decided
        # entirely by what it can encode, and when one of them is wrong the
        # only symptom is a grammar that parses the wrong language somewhere
        # far away. These narrow the set by hand so the wrong one can be found
        # by bisection rather than by staring.
        #
        #   NQP_RX_SKIP=a,b   refuse these rules by name
        #   NQP_RX_SKIP_ANON  refuse rules that do not reduce (no pass name)
        #   NQP_RX_ONLY=a,b   refuse everything except these
        my %env := nqp::getenvhash();
        my str $name := $desc.pass_name;
        if nqp::existskey(%env, 'NQP_RX_SKIP_ANON') && $name eq '' {
            return nqp::null();
        }
        if nqp::existskey(%env, 'NQP_RX_SKIP') {
            for nqp::split(',', %env<NQP_RX_SKIP>) {
                return nqp::null() if $_ eq $name;
            }
        }
        if nqp::existskey(%env, 'NQP_RX_ONLY') {
            my int $found := 0;
            for nqp::split(',', %env<NQP_RX_ONLY>) {
                $found := 1 if $_ eq $name;
            }
            return nqp::null() unless $found;
        }
        if nqp::existskey(%env, 'NQP_RX_ENCODED') {
            nqp::say('rx engine: ' ~ ($name eq '' ?? '<anon>' !! $name));
        }

        # The descriptor travels as a string constant, and the class file
        # format caps those at 65535 bytes of UTF-8. A rule that big is rare
        # and matters little; refusing it here beats emitting a class that
        # will not load.
        my str $encoded := $desc.encoded;
        return nqp::null() if nqp::chars($encoded) > 20000;

        $desc
    }

    # Hands the whole rule to the grammar engine.
    #
    # The prologue is the bytecode path's, cut down to what the engine needs:
    # !cursor_start_all answers the new cursor, the target, and the position
    # to start from. The position has to come from that list rather than from
    # the cursor, whose $!pos is -3 until the rule finishes -- reading it
    # there would start every match at a negative offset.
    #
    # The restart slot IS consulted, though the engine cannot honor it: a
    # rule that passed with :backtrack can be resumed for its next match,
    # and the engine's choice points were gone when the first match
    # returned. rxmatch answers a restart by failing the cursor -- "no
    # further match" -- which keeps a resumption from re-answering the
    # first match forever.
    method engine_jast($node, $desc) {
        my %*REG;
        my $prefix := self.unique('rxe') ~ '_';
        my $reglist := nqp::split(' ',
            'start o cur o curclass o tgt s pos i selffrom i restart i callback o');
        while $reglist {
            my $reg := nqp::shift($reglist);
            my $rt  := nqp::shift($reglist);
            my $type := $rt eq 'i' ?? int !! $rt eq 's' ?? str !! NQPMu;
            %*REG{$reg} := $prefix ~ $reg;
            $*BLOCK.add_local(QAST::Var.new(
                :name($prefix ~ $reg), :scope('local'), :returns($type), :decl('var') ));
        }

        my $il := JAST::InstructionList.new();
        my $pro := self.as_jast(QAST::Stmts.new(
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                QAST::Op.new(
                    :op('callmethod'), :name('!cursor_start_all'),
                    QAST::Var.new( :name('self'), :scope('local') )
                )),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name("\$\xa2"), :scope('lexical') ),
                QAST::Op.new(
                    :op('bind'),
                    QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(0) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<tgt>), :scope('local'), :returns(str) ),
                QAST::Op.new(
                    :op('unbox_s'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(1) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) ),
                QAST::Op.new(
                    :op('unbox_i'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(2) )
                    ))),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<curclass>), :scope('local') ),
                QAST::Op.new(
                    :op('atpos'),
                    QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                    QAST::IVal.new( :value(3) )
                )),
            # The INVOCANT's $!from, which is what decides whether a scanning
            # rule may scan: -1 means a top-level parse looking for its first
            # match, anything else means a subrule called at a position.
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<selffrom>), :scope('local'), :returns(int) ),
                QAST::Op.new(
                    :op('getattr_i'),
                    QAST::Var.new( :name('self'), :scope('local') ),
                    QAST::Var.new( :name(%*REG<curclass>), :scope('local') ),
                    QAST::SVal.new( :value('$!from') )
                )),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<restart>), :scope('local'), :returns(int) ),
                QAST::Op.new(
                    :op('unbox_i'),
                    QAST::Op.new(
                        :op('atpos'),
                        QAST::Var.new( :name(%*REG<start>), :scope('local') ),
                        QAST::IVal.new( :value(5) )
                    )))
        ), :want($RT_VOID));
        $il.append($pro.jast);
        $*STACK.obtain(NQPMu, $pro);

        # The block the engine comes back into for anything it cannot express
        # itself. It is a value here, not a call: what is pushed is the static
        # code object, so a rule that never reaches a callback pays a constant
        # load and nothing else. The engine closes it over this frame at the
        # moment a callback actually fires, which is the only moment the frame
        # is known to be the right one.
        if nqp::elems($desc.callbacks) {
            my $cb := self.as_jast(self.rx_callback_block($desc), :want($RT_OBJ));
            $il.append($cb.jast);
            $*STACK.obtain($il, $cb);
        }
        else {
            $il.append($ACONST_NULL);
        }
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<callback> ));

        $il.append(JAST::PushSVal.new( :value($desc.encoded) ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cur> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<selffrom> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<restart> ));
        $il.append(JAST::Instruction.new( :op('aload'), 'self' ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<callback> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_RXENGINE,
            'rxmatch', $TYPE_SMO, $TYPE_STR, $TYPE_SMO, $TYPE_SMO, $TYPE_STR,
            'Long', 'Long', 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_TC ));

        result($il, $RT_OBJ)
    }

    # One block holding every piece of the rule the engine has to come back
    # for, chosen by index.
    #
    # One block rather than one per callback: a block used as a value is a
    # closure, and building a list of them would allocate per rule call --
    # where a grammar calls its rules millions of times. This way the rule
    # pushes a constant and only a callback that actually fires costs
    # anything.
    #
    # The cursor and its declaring class are parameters rather than lexicals
    # because they are LOCALS of the rule's frame, which a nested block cannot
    # see -- the same reason a rule whose code reads a lowered local is
    # refused outright.
    method rx_callback_block($desc) {
        self.rx_callback_block_for($desc.callbacks, 0)
    }

    # A rule with very many pieces cannot dispatch them all in one block:
    # each generated method is capped at 64KB of bytecode, and comp_unit's
    # sixty-odd `:my` pieces broke emission outright. Above the group size
    # the pieces split into nested blocks of at most that many, the outer
    # block dispatching on index range and forwarding its arguments. The
    # pieces then run one frame deeper, which is safe for the same reason
    # the callback block itself is: compile-time nesting and run-time frame
    # nesting gain the same level in the same place, and the ops that could
    # tell the difference already refuse the rule (reads_frame_ops).
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
                    # What the bytecode path does before the code runs: the
                    # position it is looking at goes on the cursor, and $¢
                    # names the cursor, because the code is written to read
                    # both.
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

    method alt($node) {
        unless $node.name {
            return self.altseq($node);
        }

        # Calculate all the branches to try, which populates the bstack
        # with the options. Then immediately fail to start iterating it.
        my $prefix   := self.unique('alt') ~ '_';
        my $endlabel := JAST::Label.new( :name($prefix ~ 'end') );
        my $il_marks := JAST::InstructionList.new();
        my $il_alts  := JAST::InstructionList.new();
        $il_marks.append(JAST::Instruction.new( :op('aload'), %*REG<altmarks> ));
        $il_marks.append($ALOAD_1);
        $il_marks.append($IVAL_ZERO);
        $il_marks.append(JAST::Instruction.new( :op('invokevirtual'), $TYPE_SMO,
            "set_elems", 'Void', $TYPE_TC, 'Long' ));

        my $mark_endlabel := &*REGISTER_MARK($endlabel);
        self.regex_mark($il_alts, $mark_endlabel,
            $IVAL_MINUSONE,
            $IVAL_ZERO);

        my $altmeth := QAST::Op.new(
            :op('callmethod'), :name('!alt'),
            QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
            QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) ),
            QAST::SVal.new( :value($node.name) ),
            QAST::Var.new( :name(%*REG<altmarks>), :scope('local') )
        );
        my $altres := self.as_jast($altmeth, :want($RT_VOID));
        $il_alts.append($altres.jast);
        $*STACK.obtain($il_alts, $altres);
        $il_alts.append(JAST::Instruction.new( :op('goto'), %*REG<fail> ));

        # Emit all the possible alternations.
        my $altcount := 0;
        my $iter     := nqp::iterator($node.list);
        while $iter {
            my $altlabel := JAST::Label.new( :name($prefix ~ $altcount) );
            my $ajast    := self.regex_jast(nqp::shift($iter));
            $il_alts.append($altlabel);
            $il_alts.append($ajast);
            $il_alts.append(JAST::Instruction.new( :op('goto'), $endlabel ));
            my $altmark := &*REGISTER_MARK($altlabel);
            $il_marks.append(JAST::Instruction.new( :op('aload'), %*REG<altmarks> ));
            $il_marks.append(JAST::PushIVal.new( :value($altmark) ));
            $il_marks.append($ALOAD_1);
            $il_marks.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il_marks.append($POP2);
            $altcount++;
        }

        $il_alts.append($endlabel);
        self.regex_commit($il_alts, $mark_endlabel) if $node.backtrack eq 'r';

        my $il := JAST::InstructionList.new();
        $il.append($il_marks);
        $il.append($il_alts);
        $il;
    }

    method altseq($node) {
        my $il       := JAST::InstructionList.new();
        my $prefix   := self.unique('alt') ~ '_';
        my $altcount := 0;
        my $iter     := nqp::iterator($node.list);
        my $endlabel := JAST::Label.new( :name($prefix ~ 'end') );
        my $altlabel := JAST::Label.new( :name($prefix ~ $altcount) );
        my $ajast    := self.regex_jast(nqp::shift($iter));

        my $mark_endlabel := &*REGISTER_MARK($endlabel);
        self.regex_mark($il, $mark_endlabel,
            $IVAL_MINUSONE,
            $IVAL_ZERO);

        while $iter {
            $il.append($altlabel);
            $altcount++;
            $altlabel := JAST::Label.new( :name($prefix ~ $altcount) );
            my $mark := &*REGISTER_MARK($altlabel);
            self.regex_mark($il, $mark,
                JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                $IVAL_ZERO);
            $il.append($ajast);
            self.regex_commit($il, $mark_endlabel) if $node.backtrack eq 'r';
            $il.append(JAST::Instruction.new( :op('goto'), $endlabel ));
            $ajast := self.regex_jast(nqp::shift($iter));
        }
        $il.append($altlabel);
        $il.append($ajast);
        $il.append($endlabel);
        $il;
    }

    method anchor($node) {
        my $il        := JAST::InstructionList.new();
        my $subtype   := $node.subtype;
        my $donelabel := JAST::Label.new( :name(self.unique('rxanchor') ~ '_done') );
        if $subtype eq 'bos' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));
        }
        elsif $subtype eq 'eos' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('iflt'), %*REG<fail> ));
        }
        elsif $subtype eq 'lwb' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_WORD) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifeq'), %*REG<fail> ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_WORD) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($IVAL_ONE);
            $il.append($LSUB);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));
        }
        elsif $subtype eq 'rwb' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifle'), %*REG<fail> ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_WORD) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_WORD) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($IVAL_ONE);
            $il.append($LSUB);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifeq'), %*REG<fail> ));
        }
        elsif $subtype eq 'bol' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifeq'), $donelabel ));

            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_NEWLINE) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($IVAL_ONE);
            $il.append($LSUB);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifeq'), %*REG<fail> ));

            $il.append($donelabel);
        }
        elsif $subtype eq 'eol' {
            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_NEWLINE) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), $donelabel ));

            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));

            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifeq'), $donelabel ));

            $il.append(JAST::PushIVal.new( :value(nqp::const::CCLASS_NEWLINE) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($IVAL_ONE);
            $il.append($LSUB);
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));

            $il.append($donelabel);
        }
        elsif $subtype eq 'fail' {
            $il.append(JAST::Instruction.new( :op('goto'), %*REG<fail> ));
        }

        $il
    }

    method concat($node) {
        my $il := JAST::InstructionList.new();
        for $node.list {
            $il.append(self.regex_jast($_))
        }
        $il
    }

    method goal($node) {
        self.regex_jast(QAST::Regex.new(
            :rxtype<concat>,
            $node[1],
            QAST::Regex.new( :rxtype<altseq>, $node[0], $node[2] )
        ))
    }

    method conj($node) { self.conjseq($node) }

    method conjseq($node) {
        my $il         := JAST::InstructionList.new();
        my $prefix     := self.unique('rxconj') ~ '_';
        my $conjlabel  := JAST::Label.new( :name($prefix ~ 'fail') );
        my $firstlabel := JAST::Label.new( :name($prefix ~ 'first') );
        my $iter       := nqp::iterator($node.list);

        # make a mark that holds our starting position in the pos slot
        my $mark := &*REGISTER_MARK($conjlabel);
        self.regex_mark($il, $mark,
             JAST::Instruction.new( :op('lload'), %*REG<pos> ),
             $IVAL_ZERO);

        $il.append(JAST::Instruction.new( :op('goto'), $firstlabel ));
        $il.append($conjlabel);
        $il.append(JAST::Instruction.new( :op('goto'), %*REG<fail> ));

        # call the first child
        $il.append($firstlabel);
        $il.append(self.regex_jast(nqp::shift($iter)));

        # use previous mark to make one with pos=start, rep=end
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($DUP);
        $il.append(JAST::PushIVal.new( :value($mark) ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "rxpeek", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append($IVAL_ONE);
        $il.append($LADD);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));
        self.regex_mark($il, $mark,
             JAST::Instruction.new( :op('lload'), %*REG<itemp> ),
             JAST::Instruction.new( :op('lload'), %*REG<pos> ));

        while $iter {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
            $il.append(self.regex_jast(nqp::shift($iter)));

            $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
            $il.append(JAST::PushIVal.new( :value($mark) ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "rxpeek", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));

            $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
            $il.append(JAST::PushIVal.new( :value(2) ));
            $il.append($LADD);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifne'), %*REG<fail> ));

            $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
            $il.append($IVAL_ONE);
            $il.append($LADD);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));
        }

        if $node.subtype eq 'zerowidth' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
        }

        $il
    }

    my %cclass_code;
    INIT {
        # Codes match constants in Ops.java.
        %cclass_code<.>  := nqp::const::CCLASS_ANY;
        %cclass_code<d>  := nqp::const::CCLASS_NUMERIC;
        %cclass_code<s>  := nqp::const::CCLASS_WHITESPACE;
        %cclass_code<w>  := nqp::const::CCLASS_WORD;
        %cclass_code<n>  := nqp::const::CCLASS_NEWLINE;
    }

    method cclass($node) {
        my $il := JAST::InstructionList.new();

        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));

        my $subtype := nqp::lc($node.name);
        nqp::die("Unrecognized subtype '$subtype' in QAST::Regex cclass")
            unless nqp::existskey(%cclass_code, $subtype);
        my $cclass := %cclass_code{$subtype};
        if $subtype ne '.' {
            $il.append(JAST::PushIVal.new( :value($cclass) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'),
                $TYPE_OPS, 'iscclass', 'Long', 'Long', $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifne' !! 'ifeq'), %*REG<fail> ));

            if $cclass == nqp::const::CCLASS_NEWLINE && !$node.negate && $node.subtype ne 'zerowidth' {
                $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
                $il.append(JAST::Instruction.new( :op('invokestatic'),
                    $TYPE_OPS, 'checkcrlf', 'Long', $TYPE_STR, 'Long', 'Long' ));
                $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
            }
        }

        unless $node.subtype eq 'zerowidth' {
            self.regex_advance_char($il);
        }

        $il
    }

    method dba($node) {
        my $qast := QAST::Op.new(
            :op('callmethod'), :name('!dba'),
            QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
            QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) ),
            QAST::SVal.new( :value($node.name()) )
        );
        my $res := self.as_jast($qast, :want($RT_VOID));
        $*STACK.obtain(NQPMu, $res);
        $res.jast
    }

    method enumcharlist($node) {
        my $il := JAST::InstructionList.new();
        my $donelabel;

	$il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
	$il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
	$il.append($LCMP);
        if $node.subtype eq 'zerowidth' && $node.negate {
	    my $prefix     := self.unique('enumcharlist');
	    $donelabel := JAST::Label.new( :name($prefix ~ '_done') );
	    $il.append(JAST::Instruction.new( :op('ifge'), $donelabel ));
	}
	else {
	    $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));
	}

        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('iflt'), %*REG<fail> ));

        $il.append(JAST::PushSVal.new( :value($node[0]) ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('invokevirtual'),
            $TYPE_STR, 'charAt', 'Char', 'Integer' ));
        $il.append(JAST::Instruction.new( :op('invokevirtual'),
            $TYPE_STR, 'indexOf', 'Integer', 'Integer' ));
        $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifge' !! 'iflt'), %*REG<fail> ));

        unless $node.subtype eq 'zerowidth' {
            self.regex_advance_char($il);
        }
	$il.append($donelabel) if $donelabel;

        $il;
    }

    method charrange($node) {
        my $il := JAST::InstructionList.new();

        my $succeed := JAST::Label.new(:name(self.unique('charrange_succeed_')));

        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));

        $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op('invokevirtual'),
            $TYPE_STR, 'codePointAt', 'Integer', 'Integer' ));
        $il.append($I2L);
        $il.append($DUP2);

        $il.append(JAST::PushIVal.new( :value($node[1].value) ));
        $il.append($LCMP);

        if $node.negate {
            my $succeed_and_pop := JAST::Label.new(:name(self.unique('charrange_succeed_pop_')));
            $il.append(JAST::Instruction.new( :op('iflt'), $succeed_and_pop ));
            $il.append(JAST::PushIVal.new( :value($node[2].value) ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifgt'), $succeed ));
            $il.append(JAST::Instruction.new( :op('goto'), %*REG<fail> ));
            $il.append($succeed_and_pop);
            $il.append($POP2);
            $il.append($succeed);
        } else {
            $il.append(JAST::Instruction.new( :op('ifge'), $succeed ));
            $il.append($POP2);
            $il.append(JAST::Instruction.new( :op('goto'), %*REG<fail>));

            $il.append($succeed);
            $il.append(JAST::PushIVal.new( :value($node[2].value) ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifgt'), %*REG<fail> ));
        }

        unless $node.subtype eq 'zerowidth' {
            self.regex_advance_char($il);
        }

        $il;
    }

    method literal($node) {
        my $il := JAST::InstructionList.new();
        my $litconst := $node[0];
        my $litlen := nqp::chars($litconst);

        # Only worth asking when a match is what we want. A negated literal
        # with no room left cannot be there, which is exactly the assertion
        # succeeding -- failing here would reject `\C[...]` against a target
        # shorter than the long character it rules out. The comparisons below
        # all answer false for a range past the end, so nothing needs the
        # guard for correctness.
        unless $node.negate {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::PushIVal.new( :value($litlen) ));
            $il.append($LADD);
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifgt'), %*REG<fail> ));
        }

        my str $subtype := $node.subtype;
        if $subtype eq 'ignoremark' || $subtype eq 'ignorecase+ignoremark' {
            # The mark-insensitive comparisons live in Ops; regionMatches
            # below only knows exact and case-insensitive.
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::PushSVal.new( :value($litconst) ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                ($subtype eq 'ignoremark' ?? 'eqatim' !! 'eqaticim'),
                'Long', $TYPE_STR, $TYPE_STR, 'Long' ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifne' !! 'ifeq'), %*REG<fail> ));
        }
        else {
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::PushIndex.new(
                :value($subtype eq 'ignorecase' ?? 1 !! 0) ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append($L2I);
            $il.append(JAST::PushSVal.new( :value($litconst) ));
            $il.append(JAST::PushIndex.new( :value(0) ));
            $il.append(JAST::PushIndex.new( :value($litlen) ));
            $il.append(JAST::Instruction.new( :op('invokevirtual'),
                $TYPE_STR, 'regionMatches', 'Z', 'Z', 'Integer', $TYPE_STR, 'Integer', 'Integer' ));
            $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifne' !! 'ifeq'), %*REG<fail> ));
        }

        unless $node.subtype eq 'zerowidth' {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::PushIVal.new( :value($litlen) ));
            $il.append($LADD);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
        }

        $il;
    }

    method pass($node) {
        my $qast := QAST::Op.new(
            :op('callmethod'), :name('!cursor_pass'),
            QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
            QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) )
        );
        if $node.name() {
            $qast.push(QAST::SVal.new( :value($node.name()) ));
        }
        elsif nqp::elems(@($node)) == 1 {
            $qast.push($node[0]);
        }
        if $node.backtrack ne 'r' {
            $qast.push(QAST::IVal.new( :value(1), :named('backtrack') ));
        }
        my $res := self.as_jast($qast, :want($RT_VOID));
        $*STACK.obtain(NQPMu, $res);
        $res.jast;
    }

    method qastnode($node) {
        my $il := JAST::InstructionList.new();

        $il.append(JAST::PushSVal.new( :value("\$\xa2") ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cur> ));
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::PushSVal.new( :value('$!pos') ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "bindattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, 'Long', $TYPE_TC ));
        $il.append($POP2);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "bindlex", $TYPE_SMO, $TYPE_STR, $TYPE_SMO, $TYPE_TC ));
        $il.append($POP);

        my $node_res := self.as_jast($node[0], :want($RT_OBJ));
        $il.append($node_res.jast);
        $*STACK.obtain($il, $node_res);

        if $node.subtype eq 'zerowidth' {
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "istrue", 'Long', $TYPE_SMO, $TYPE_TC ));
            $il.append($L2I);
            $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifne' !! 'ifeq'), %*REG<fail> ));
        }
        else {
            $il.append($POP);
        }

        $il;
    }

    method dynquant($node) {
        my $il         := JAST::InstructionList.new();

        my $backtrack  := $node.backtrack || 'g';
        my $sep        := $node[2];
        my $prefix     := self.unique('rxdynquant' ~ $backtrack);
        my $looplabel  := JAST::Label.new( :name($prefix ~ '_loop') );
        my $donelabel  := JAST::Label.new( :name($prefix ~ '_done') );
        my $skip0label := JAST::Label.new( :name($prefix ~ '_skip0'));
        my $skip1label := JAST::Label.new( :name($prefix ~ '_skip1'));
        my $skip2label := JAST::Label.new( :name($prefix ~ '_skip2'));
        my $skip3label := JAST::Label.new( :name($prefix ~ '_skip3'));
        my $skip4label := JAST::Label.new( :name($prefix ~ '_skip4'));
        my $skip5label := JAST::Label.new( :name($prefix ~ '_skip5'));
        my $skip6label := JAST::Label.new( :name($prefix ~ '_skip6'));
        my $skip7label := JAST::Label.new( :name($prefix ~ '_skip7'));
        my $skip8label := JAST::Label.new( :name($prefix ~ '_skip8'));
        my $skip9label := JAST::Label.new( :name($prefix ~ '_skip9'));
        my $needrep    := $*TA.fresh_i();
        my $needmark   := $*TA.fresh_i();

        my $minmax     := $node[1];
        my $min_reg    := $*TA.fresh_i();
        my $max_reg    := $*TA.fresh_i();

        my $res_reg := self.as_jast($minmax, :want($RT_OBJ));
        $il.append($res_reg.jast);
        $*STACK.obtain($il, $res_reg);
        $il.append($DUP);
        $il.append($IVAL_ZERO);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), $min_reg ));
        $il.append($IVAL_ONE);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), $max_reg ));

        # return if $min == 0 && $max == 0;
        $il.append(JAST::Instruction.new( :op('lload'), $min_reg ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifne'), $skip0label ));
        $il.append(JAST::Instruction.new( :op('lload'), $max_reg ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifeq'), $skip1label ));
        $il.append($skip0label);

        # $needrep := $min > 1 || $max > 1;
        $il.append(JAST::Instruction.new( :op('lload'), $min_reg ));
        $il.append($IVAL_ONE);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('iflt'), $skip2label ));
        $il.append($IVAL_ONE);
        $il.append(JAST::Instruction.new( :op('lstore'), $needrep ));
        $il.append($skip2label);
        $il.append(JAST::Instruction.new( :op('lload'), $max_reg ));
        $il.append($IVAL_ONE);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('iflt'), $skip3label ));
        $il.append($IVAL_ONE);
        $il.append(JAST::Instruction.new( :op('lstore'), $needrep ));
        $il.append($skip3label);

        # $needmark := $needrep || $backtrack eq 'r';
        if $backtrack eq 'r' {
            $il.append($IVAL_ONE);
            $il.append(JAST::Instruction.new( :op('lstore'), $needmark ));
        }
        else {
            $il.append(JAST::Instruction.new( :op('lload'), $needrep ));
            $il.append(JAST::Instruction.new( :op('lstore'), $needmark ));
        }

        if $backtrack eq 'f' {
            my $seplabel := JAST::Label.new( :name($prefix ~ '_sep'));
            my $mark     := &*REGISTER_MARK($looplabel);
            $il.append($IVAL_ZERO);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

            $il.append(JAST::Instruction.new( :op('lload'), $min_reg )); # if $min < 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifge'), $skip4label ));
            self.regex_mark($il, $mark,
                JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('goto'), $donelabel ));
            $il.append($skip4label);                                     # }

            $il.append(JAST::Instruction.new( :op('goto'), $seplabel )) if $sep;
            $il.append($looplabel);
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));
            if $sep {
                $il.append(self.regex_jast($sep));
                $il.append($seplabel);
            }
            $il.append(self.regex_jast($node[0]));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<itemp> ));
            $il.append($IVAL_ONE);
            $il.append($LADD);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

            $il.append(JAST::Instruction.new( :op('lload'), $min_reg )); # if $min > 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifle'), $skip5label ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lload'), $min_reg ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('iflt'), $looplabel ));
            $il.append($skip5label);                                     # }

            $il.append(JAST::Instruction.new( :op('lload'), $max_reg )); # if $max > 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifle'), $skip6label ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lload'), $max_reg ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifge'), $donelabel ));
            $il.append($skip6label);                                     # }

            $il.append(JAST::Instruction.new( :op('lload'), $max_reg )); # if $max != 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $skip7label ));
            self.regex_mark($il, $mark,
                JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append($skip7label);                                     # }

            $il.append($donelabel);
        }
        else {
            my $mark := &*REGISTER_MARK($donelabel);

            $il.append(JAST::Instruction.new( :op('lload'), $min_reg )); # if $min == 0 {
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifne'), $skip4label ));
            self.regex_mark($il, $mark,
                JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                $IVAL_ZERO);
            $il.append($skip4label);                                     # }

            $il.append(JAST::Instruction.new( :op('lload'), $min_reg )); # elsif $needmark {
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $skip5label ));
            $il.append(JAST::Instruction.new( :op('lload'), $needmark ));
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $skip5label ));
            self.regex_mark($il, $mark, $IVAL_MINUSONE, $IVAL_ZERO);
            $il.append($skip5label);                                     # }

            $il.append($looplabel);
            $il.append(self.regex_jast($node[0]));

            $il.append(JAST::Instruction.new( :op('lload'), $needmark )); # if $needmark {
            $il.append($IVAL_ZERO);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $skip6label ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
            $il.append($DUP);
            $il.append(JAST::PushIVal.new( :value($mark) ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "rxpeek", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append(JAST::PushIVal.new( :value(2) ));
            $il.append($LADD);
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));
            self.regex_commit($il, $mark) if $backtrack eq 'r';
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append($IVAL_ONE);
            $il.append($LADD);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

            $il.append(JAST::Instruction.new( :op('lload'), $max_reg ));      # if $max > 1
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifle'), $skip7label ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lload'), $max_reg ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifge'), $donelabel ));
            $il.append($skip7label);                                          # }
            $il.append($skip6label);                                      # }

            $il.append(JAST::Instruction.new( :op('lload'), $max_reg )); # unless $max == 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), $skip8label ));
            self.regex_mark($il, $mark,
                JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(self.regex_jast($sep)) if $sep;
            $il.append(JAST::Instruction.new( :op('goto'), $looplabel ));
            $il.append($skip8label);                                     # }
            $il.append($donelabel);

            $il.append(JAST::Instruction.new( :op('lload'), $min_reg )); # if $min > 1 {
            $il.append($IVAL_ONE);
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('iflt'), $skip9label ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lload'), $min_reg ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('iflt'), %*REG<fail> ));
            $il.append($skip9label);                                     # }
        }
        $il.append($skip1label);
        $il
    }

    method quant($node) {
        my $il        := JAST::InstructionList.new();
        my $backtrack := $node.backtrack || 'g';
        my $sep       := $node[1];
        my $prefix    := self.unique('rxquant' ~ $backtrack);
        my $looplabel := JAST::Label.new( :name($prefix ~ '_loop') );
        my $donelabel := JAST::Label.new( :name($prefix ~ '_done') );
        my $min       := $node.min;
        my $max       := $node.max;
        my $needrep   := $min > 1 || $max > 1;
        my $needmark  := $needrep || $backtrack eq 'r';

        if $min == 0 && $max == 0 {
            # Nothing to do, and nothing to backtrack into.
        }
        elsif $backtrack eq 'f' {
            my $seplabel := JAST::Label.new( :name($prefix ~ '_sep') );
            my $mark     := &*REGISTER_MARK($looplabel);
            my $tmp_rep  := $*TA.fresh_i();

            $il.append($IVAL_ZERO);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));
            if $min < 1 {
                self.regex_mark($il, $mark,
                    JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                    JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append(JAST::Instruction.new( :op('goto'), $donelabel ));
            }
            if $sep {
                $il.append($IVAL_ZERO);
                $il.append(JAST::Instruction.new( :op('lstore'), $tmp_rep ));
                $il.append(JAST::Instruction.new( :op('goto'), $seplabel ));
            }
            $il.append($looplabel);
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            $il.append(JAST::Instruction.new( :op('lstore'), $tmp_rep ));
            $il.append(self.regex_jast($sep)) if $sep;
            $il.append($seplabel) if $sep;
            $il.append(self.regex_jast($node[0]));
            $il.append(JAST::Instruction.new( :op('lload'), $tmp_rep ));
            $il.append($IVAL_ONE);
            $il.append($LADD);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

            if $min > 1 {
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append(JAST::PushIVal.new( :value($min) ));
                $il.append($LCMP);
                $il.append(JAST::Instruction.new( :op('iflt'), $looplabel ));
            }
            if $max > 1 {
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append(JAST::PushIVal.new( :value($max) ));
                $il.append($LCMP);
                $il.append(JAST::Instruction.new( :op('ifge'), $donelabel ));
            }
            if $max != 1 {
                self.regex_mark($il, $mark,
                    JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                    JAST::Instruction.new( :op('lload'), %*REG<rep> ));
            }

            $il.append($donelabel);
        }
        else {
            my $mark := &*REGISTER_MARK($donelabel);

            if $min == 0 {
                self.regex_mark($il, $mark,
                    JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                    $IVAL_ZERO);
            }
            elsif $needmark {
                self.regex_mark($il, $mark,
                    $IVAL_MINUSONE,
                    $IVAL_ZERO);
            }

            $il.append($looplabel);
            $il.append(self.regex_jast($node[0]));

            if $needmark {
                $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
                $il.append($DUP);
                $il.append(JAST::PushIVal.new( :value($mark) ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "rxpeek", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append(JAST::PushIVal.new( :value(2) ));
                $il.append($LADD);
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

                if $backtrack eq 'r' {
                    self.regex_commit($il, $mark);
                }

                $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append($IVAL_ONE);
                $il.append($LADD);
                $il.append(JAST::Instruction.new( :op('lstore'), %*REG<rep> ));

                if $max > 1 {
                    $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                    $il.append(JAST::PushIVal.new( :value($node.max) ));
                    $il.append($LCMP);
                    $il.append(JAST::Instruction.new( :op('ifge'), $donelabel ));
                }
            }

            unless $max == 1 {
                self.regex_mark($il, $mark,
                    JAST::Instruction.new( :op('lload'), %*REG<pos> ),
                    JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append(self.regex_jast($sep)) if $sep;
                $il.append(JAST::Instruction.new( :op('goto'), $looplabel ));
            }

            $il.append($donelabel);
            if $min > 1 {
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<rep> ));
                $il.append(JAST::PushIVal.new( :value(+$node.min) ));
                $il.append($LCMP);
                $il.append(JAST::Instruction.new( :op('iflt'), %*REG<fail> ));
            }
        }

        $il;
    }

    method scan($node) {
        my $il := JAST::InstructionList.new();

        my $prefix := self.unique('rxscan');
        my $looplabel := JAST::Label.new( :name($prefix ~ '_loop') );
        my $scanlabel := JAST::Label.new( :name($prefix ~ '_scan') );
        my $donelabel := JAST::Label.new( :name($prefix ~ '_done') );

        $il.append(JAST::Instruction.new( :op('aload'), 'self' ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::PushSVal.new( :value('$!from') ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "getattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
        $il.append($IVAL_MINUSONE);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifne'), $donelabel ));
        $il.append(JAST::Instruction.new( :op('goto'), $scanlabel ));

        $il.append($looplabel);
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($IVAL_ONE);
        $il.append($LADD);
        $il.append($DUP2);
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
        if nqp::elems($node) {
            # Pick the index variant matching the literal's semantics, the
            # same way the MoarVM backend does.
            my str $subtype := $node.subtype;
            my str $indexop :=
                $subtype eq 'ignorecase'            ?? 'indexic'   !!
                $subtype eq 'ignoremark'            ?? 'indexim'   !!
                $subtype eq 'ignorecase+ignoremark' ?? 'indexicim' !!
                                                       'indexfrom';
            # shuffle the stack variables into place for the index op.
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::PushSVal.new( :value($node[0]) ));
            $il.append($DUP2_X2);
            $il.append($POP2);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    $indexop, 'Long', $TYPE_STR, $TYPE_STR, 'Long'));
            $il.append($DUP2);
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
            $il.append(JAST::PushIVal.new( :value(-1) ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifeq'), %*REG<fail> ));
        } else {
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
            $il.append($LCMP);
            $il.append(JAST::Instruction.new( :op('ifgt'), %*REG<fail> ));
        }
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<cur> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::PushSVal.new( :value('$!from') ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "bindattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, 'Long', $TYPE_TC ));
        $il.append($POP2);

        $il.append($scanlabel);

        my $mark := &*REGISTER_MARK($looplabel);
        self.regex_mark($il, $mark,
            JAST::Instruction.new( :op('lload'), %*REG<pos> ),
            $IVAL_ZERO);
        $il.append($donelabel);

        $il;
    }

    method subcapture($node) {
        my $il        := JAST::InstructionList.new();
        my $prefix    := self.unique('rxcap');
        my $donelabel := JAST::Label.new( :name($prefix ~ '_done') );
        my $faillabel := JAST::Label.new( :name($prefix ~ '_fail') );

        my $mark := &*REGISTER_MARK($faillabel);
        self.regex_mark($il, $mark,
            JAST::Instruction.new( :op('lload'), %*REG<pos> ),
            $IVAL_ZERO);
        $il.append(self.regex_jast($node[0]));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append($DUP);
        $il.append(JAST::PushIVal.new( :value($mark) ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "rxpeek", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append($IVAL_ONE);
        $il.append($LADD);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "atpos_i", 'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<itemp> ));

        my $temp := QAST::Node.unique('rx_subcapture_cur_');
        my $methqast := QAST::Stmts.new(
            QAST::Op.new(
                :op('bindattr_i'),
                QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                QAST::Var.new( :name(%*REG<curclass>), :scope('local') ),
                QAST::SVal.new( :value('$!pos') ),
                QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) )
            ),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name($temp), :scope('local'), :decl('var') ),
                QAST::Op.new(
                    :op('callmethod'), :name('!cursor_start_subcapture'),
                    QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                    QAST::Var.new( :name(%*REG<itemp>), :scope('local'), :returns(int) )
                )),
            QAST::Op.new(
                :op('callmethod'), :name('!cursor_pass'),
                QAST::Var.new( :name($temp), :scope('local') ),
                QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) )
            ),
            QAST::Op.new(
                :op('bind'),
                QAST::Var.new( :name(%*REG<cstack>), :scope('local') ),
                QAST::Op.new(
                    :op('callmethod'), :name('!cursor_capture'),
                    QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                    QAST::Var.new( :name($temp), :scope('local') ),
                    QAST::SVal.new( :value($node.name) )
                )));
        my $methres := self.as_jast($methqast, :want($RT_VOID));
        $il.append($methres.jast);
        $*STACK.obtain($il, $methres);

        $il.append(JAST::Instruction.new( :op('goto'), $donelabel ));
        $il.append($faillabel);
        $il.append(JAST::Instruction.new( :op('goto'), %*REG<fail> ));
        $il.append($donelabel);

        $il;
    }

    method subrule($node) {
        my $il := JAST::InstructionList.new();
        my $name := nqp::defined($node.name) ?? $node.name !! '';
        my $subtype := $node.subtype;
        my $captured := 0;

        my $callqast := QAST::Stmts.new(
            QAST::Op.new(
                :op('bindattr_i'),
                QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                QAST::Var.new( :name(%*REG<curclass>), :scope('local') ),
                QAST::SVal.new( :value('$!pos') ),
                QAST::Var.new( :name(%*REG<pos>), :scope('local'), :returns(int) )
            ));
        if nqp::istype($node[0][0], QAST::SVal) {
            # Method call.
            my @callargs := nqp::clone($node[0].list);
            my $name := @callargs.shift().value;
            $callqast.push(QAST::Op.new(
                :op('callmethod'), :name($name),
                QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                |@callargs
            ));
        }
        else {
            # Normal invocation (probably positional capture).
            my @callargs := nqp::clone($node[0].list);
            my $target := @callargs.shift();
            $callqast.push(QAST::Op.new(
                :op('call'),
                $target,
                QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                |@callargs
            ));
        }
        my $invres := self.as_jast($callqast, :want($RT_OBJ));
        $il.append($invres.jast);
        $*STACK.obtain($il, $invres);
        $il.append($DUP);
        $il.append(JAST::Instruction.new( :op('astore'), %*REG<subcur> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
        $il.append(JAST::PushSVal.new( :value('$!pos') ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "getattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
        $il.append($IVAL_ZERO);
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifge' !! 'iflt'), %*REG<fail> ));

        if $subtype ne 'zerowidth' {
            my $rxname := self.unique('rxsubrule');
            my $passlabel := JAST::Label.new( :name($rxname ~ '_pass') );
            if $node.backtrack eq 'r' {
                unless $subtype eq 'method' {
                    my $mark := &*REGISTER_MARK($passlabel);
                    self.regex_mark($il, $mark,
                        $IVAL_MINUSONE,
                        $IVAL_ZERO);
                    $il.append($passlabel);
                }
            }
            else {
                my $backlabel := JAST::Label.new( :name($rxname ~ '_back') );
                $il.append(JAST::Instruction.new( :op('goto'), $passlabel ));

                $il.append($backlabel);
                my $nextres := self.as_jast(QAST::Op.new(
                    :op('callmethod'), :name('!cursor_next'),
                    QAST::Var.new( :name(%*REG<subcur>), :scope('local') )
                ), :want($RT_OBJ));
                $il.append($nextres.jast);
                $*STACK.obtain($il, $nextres);
                $il.append($DUP);
                $il.append(JAST::Instruction.new( :op('astore'), %*REG<subcur> ));
                $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
                $il.append(JAST::PushSVal.new( :value('$!pos') ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                    "getattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
                $il.append($IVAL_ZERO);
                $il.append($LCMP);
                $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifge' !! 'iflt'), %*REG<fail> ));

                my $mark := &*REGISTER_MARK($backlabel);
                $il.append($passlabel);
                if $subtype eq 'capture' {
                    my $capres := self.as_jast(QAST::Op.new(
                        :op('callmethod'), :name('!cursor_capture'),
                        QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                        QAST::Var.new( :name(%*REG<subcur>), :scope('local') ),
                        QAST::SVal.new( :value($name) )
                    ), :want($RT_OBJ));
                    $il.append($capres.jast);
                    $*STACK.obtain($il, $capres);
                    $il.append(JAST::Instruction.new( :op('astore'), %*REG<cstack> ));
                    $captured := 1;

                    # Record a mark on the bstack saying how many captures we
                    # had before pushing this one, so we can remove it upon
                    # backtracking (otherwise we end up keeping backtracked
                    # over subrule captures around).
                    $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
                    $il.append($DUP);
                    $il.append($DUP2);
                    $il.append(JAST::PushIVal.new( :value($mark) ));
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                        'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                    $il.append($POP2);
                    $il.append($IVAL_MINUSONE);
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                        'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                    $il.append($POP2);
                    $il.append($IVAL_MINUSONE);
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                        'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                    $il.append($POP2);
                    $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'elems',
                        'Long', $TYPE_SMO, $TYPE_TC ));
                    $il.append($IVAL_MINUSONE);
                    $il.append($LADD);
                    $il.append($ALOAD_1);
                    $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                        'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                    $il.append($POP2);
                }
                else {
                    my $pushres := self.as_jast(QAST::Op.new(
                        :op('callmethod'), :name('!cursor_push_cstack'),
                        QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                        QAST::Var.new( :name(%*REG<subcur>), :scope('local') ),
                    ), :want($RT_OBJ));
                    $il.append($pushres.jast);
                    $*STACK.obtain($il, $pushres);
                    $il.append(JAST::Instruction.new( :op('astore'), %*REG<cstack> ));
                }

                $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
                $il.append($DUP);
                $il.append($DUP2);
                $il.append(JAST::PushIVal.new( :value($mark) ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                    'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append($POP2);
                $il.append($IVAL_ZERO);
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                    'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append($POP2);
                $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                    'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append($POP2);
                $il.append(JAST::Instruction.new( :op('aload'), %*REG<cstack> ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'elems',
                    'Long', $TYPE_SMO, $TYPE_TC ));
                $il.append($ALOAD_1);
                $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS, 'push_i',
                    'Long', $TYPE_SMO, 'Long', $TYPE_TC ));
                $il.append($POP2);
            }
        }

        if !$captured && $subtype eq 'capture' {
            my $capres := self.as_jast(QAST::Op.new(
                :op('callmethod'), :name('!cursor_capture'),
                QAST::Var.new( :name(%*REG<cur>), :scope('local') ),
                QAST::Var.new( :name(%*REG<subcur>), :scope('local') ),
                QAST::SVal.new( :value($name) )
            ), :want($RT_OBJ));
            $il.append($capres.jast);
            $*STACK.obtain($il, $capres);
            $il.append(JAST::Instruction.new( :op('astore'), %*REG<cstack> ));
        }

        unless $subtype eq 'zerowidth' {
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<subcur> ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<curclass> ));
            $il.append(JAST::PushSVal.new( :value('$!pos') ));
            $il.append($ALOAD_1);
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "getattr_i", 'Long', $TYPE_SMO, $TYPE_SMO, $TYPE_STR, $TYPE_TC ));
            $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
        }

        $il;
    }

    method uniprop($node) {
        my $il := JAST::InstructionList.new();

        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<eos> ));
        $il.append($LCMP);
        $il.append(JAST::Instruction.new( :op('ifge'), %*REG<fail> ));

        if nqp::elems(@($node)) > 1 {
            # A property pair, <:Block("Basic Latin")>: the property's value
            # at this position is smartmatched against the given matcher,
            # which the cursor delegates so a grammar can override it.
            $il.append(self.uniprop_pair($node));
        }
        else {
            $il.append(JAST::PushSVal.new( :value($node[0]) ));
            $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
            $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
            $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
                "ischarprop", 'Long', $TYPE_STR, $TYPE_STR, 'Long' ));
        }
        $il.append($L2I);
        $il.append(JAST::Instruction.new( :op($node.negate ?? 'ifne' !! 'ifeq'), %*REG<fail> ));

        unless $node.subtype eq 'zerowidth' {
            self.regex_advance_char($il);
        }

        $il
    }

    # Step over the character at pos. MoarVM counts a position per codepoint,
    # where a position here is a UTF-16 unit and a non-BMP codepoint occupies
    # two of them, so a bare increment would land between the halves of one
    # character and match a lone surrogate.
    method regex_advance_char($il) {
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<tgt> ));
        $il.append(JAST::Instruction.new( :op('lload'), %*REG<pos> ));
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "cpwidth", 'Long', $TYPE_STR, 'Long' ));
        $il.append($LADD);
        $il.append(JAST::Instruction.new( :op('lstore'), %*REG<pos> ));
    }

    # The value side of a property pair, left on the stack as a long that is
    # 1 when the matcher accepted it. A property carries either a string
    # value or a numeric one, and which it is shows in the reading: an empty
    # string means the string reading found nothing, so ask for the number.
    # <:name(...)> reads the codepoint's own name instead of a property.
    method uniprop_pair($node) {
        my $prop    := ~$node[0];
        my $by_name := $prop eq 'name' || $prop eq 'Name';
        my $ord     := self.unique('rxuniprop_ord');
        my $val     := self.unique('rxuniprop_val');
        my $matcher := self.unique('rxuniprop_matcher');

        my sub localvar($name, *%opts) {
            QAST::Var.new( :name($name), :scope('local'), |%opts )
        }
        my sub propcode() {
            QAST::Op.new( :op('unipropcode'), QAST::SVal.new( :value($prop) ) )
        }
        # Unboxed here rather than left to the caller's :want, so that both
        # arms of the choice below already agree on the int type. This is
        # what MoarVM's int result register does for the same call.
        my sub accepts($arg) {
            QAST::Op.new( :op('unbox_i'),
                QAST::Op.new(
                    :op('callmethod'), :name('!DELEGATE_ACCEPTS'),
                    localvar(%*REG<cur>),
                    localvar($matcher),
                    $arg
                ))
        }

        my $qast := QAST::Stmts.new(
            QAST::Op.new( :op('bind'),
                localvar($ord, :decl('var'), :returns(int)),
                QAST::Op.new( :op('ordat'),
                    localvar(%*REG<tgt>, :returns(str)),
                    localvar(%*REG<pos>, :returns(int)) )),
            QAST::Op.new( :op('bind'),
                localvar($val, :decl('var'), :returns(str)),
                $by_name
                    ?? QAST::Op.new( :op('getuniname'), localvar($ord, :returns(int)) )
                    !! QAST::Op.new( :op('getuniprop_str'),
                           localvar($ord, :returns(int)), propcode() )),
            QAST::Op.new( :op('bind'),
                localvar($matcher, :decl('var')),
                $node[1] ));

        $qast.push($by_name
            ?? accepts(localvar($val, :returns(str)))
            !! QAST::Op.new( :op('if'),
                   QAST::Op.new( :op('chars'), localvar($val, :returns(str)) ),
                   accepts(localvar($val, :returns(str))),
                   accepts(QAST::Op.new( :op('getuniprop_int'),
                       localvar($ord, :returns(int)), propcode() )) ));

        my $il  := JAST::InstructionList.new();
        my $res := self.as_jast($qast, :want($RT_INT));
        $il.append($res.jast);
        $*STACK.obtain($il, $res);
        $il
    }

    # a :rxtype<ws> node is a normal subrule call
    method ws($node) { self.subrule($node) }

    method regex_mark($il, $mark, $pos, $rep) {
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append(JAST::PushIVal.new( :value($mark) ));
        $il.append($pos);
        $il.append($rep);
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "rxmark", 'Void', $TYPE_SMO, 'Long', 'Long', 'Long', $TYPE_TC ));
    }

    method regex_commit($il, $mark) {
        $il.append(JAST::Instruction.new( :op('aload'), %*REG<bstack> ));
        $il.append(JAST::PushIVal.new( :value($mark) ));
        $il.append($ALOAD_1);
        $il.append(JAST::Instruction.new( :op('invokestatic'), $TYPE_OPS,
            "rxcommit", 'Void', $TYPE_SMO, 'Long', $TYPE_TC ));
    }

    multi method as_jast($unknown, :$want) {
        nqp::die("Unknown QAST node type " ~ $unknown.HOW.name($unknown));
    }

    method result($il, $type) { result($il, $type) }

    method operations() { QAST::OperationsJAST }
}

# Register as the QAST compiler.
if nqp::isnull(nqp::getcomp('QAST')) {
    nqp::bindcomp('QAST', QAST::CompilerJAST);
}
