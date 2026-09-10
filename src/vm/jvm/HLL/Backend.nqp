# Backend class for the JVM.
use JASTNodes;

class HLL::Backend::JVM {
    our %jvm_config   := nqp::backendconfig();
    my $compile_count := 0;
    
    method config() {
        %jvm_config
    }
    
    method force_gc() {
        nqp::force_gc()
    }
    
    method name() {
        'jvm'
    }

    method nqpevent($spec?) {
        # Doesn't do anything just yet
    }
    
    method run_profiled($what, $kind, $filename?) {
        stderr().print("Attach a profiler (e.g. JVisualVM) and press enter");
        stdin().get;
        $what();
    }
    
    method run_traced($level, $what) {
        nqp::die("No tracing support");
    }
    
    method version_string() {
        "JVM"
    }
    
    method stages() {
        'jast classfile jar jvm'
    }
    
    method is_precomp_stage($stage) {
        $stage eq 'classfile' || $stage eq 'jar'
    }
    
    method is_textual_stage($stage) {
        0
    }
    
    method classname($source, *%adverbs) {
        unless %*COMPILING<%?OPTIONS><javaclass> {
            %*COMPILING<%?OPTIONS><javaclass> := nqp::sha1(nqp::sha1($source) ~ nqp::time() ~ $compile_count++);
        }
        $source
    }
    
    method jast($qast, *%adverbs) {
        my $classname := %*COMPILING<%?OPTIONS><javaclass> || nqp::sha1('eval-at-' ~ nqp::time() ~ $compile_count++);
        nqp::getcomp('QAST').jast($qast, :$classname);
    }

    method classfile($jast, *%adverbs) {
        # TODO: Direct compile ops have to take a hash of name-to-typeobj
        my %jastnodes := hash();
        %jastnodes<JAST::Class>  := JAST::Class;
        %jastnodes<JAST::Field>  := JAST::Field;
        %jastnodes<JAST::Method> := JAST::Method;
        %jastnodes<JAST::Label> := JAST::Label;
        %jastnodes<JAST::Instruction> := JAST::Instruction;
        %jastnodes<JAST::InvokeDynamic> := JAST::InvokeDynamic;
        %jastnodes<JAST::InstructionList> := JAST::InstructionList;
        %jastnodes<JAST::PushIVal> := JAST::PushIVal;
        %jastnodes<JAST::PushNVal> := JAST::PushNVal;
        %jastnodes<JAST::PushSVal> := JAST::PushSVal;
        %jastnodes<JAST::PushCVal> := JAST::PushCVal;
        %jastnodes<JAST::PushIndex> := JAST::PushIndex;
        %jastnodes<JAST::TryCatch> := JAST::TryCatch;
        %jastnodes<JAST::Annotation> := JAST::Annotation;
        # The unit road: a jar-bound unit with an output file is written as
        # a zip; every other unit -- a script, an EVAL, a BEGIN-time unit,
        # a --target=jar with no --output -- is built in memory as a
        # record, which the jvm stage (nqp::loadcompunit) turns into a
        # ProgramUnit. No class file either way; Compiler.nqp refuses
        # --target=classfile.
        if %adverbs<target> eq 'jar' && %adverbs<output> {
            # The syscall's argument kinds are checked at the call
            # site; %adverbs<output> arrives boxed.
            my str $unit_output := %adverbs<output>;
            nqp::syscall('jvm-write-unit', $jast, %jastnodes, $unit_output);
            nqp::null()
        }
        else {
            nqp::syscall('jvm-build-unit', $jast, %jastnodes)
        }
    }

    method jar($cu, *%adverbs) {
        $cu; # the actual work is done in classfile and compilejast...
    }
    
    method jvm($cu, *%adverbs) {
        nqp::loadcompunit($cu, , %adverbs<bootstrap> ?? 1 !! 0)
    }
    
    method is_compunit($cuish) {
        nqp::iscompunit($cuish)
    }
    
    method compunit_mainline($cu) {
        nqp::compunitmainline($cu)
    }
    
    method compunit_coderefs($cu) {
        nqp::compunitcodes($cu)
    }

    method supports-op($opname) {
        # MoarVM spells its dispatch ops with a result-kind suffix, and that
        # is the spelling HLL code asks about (NativeCall probes for
        # 'dispatch_v' to choose the dispatcher-based implementation). This
        # backend registers the family under the bare name, so map those
        # spellings before consulting the op table.
        my $mapped := $opname eq 'dispatch_v' || $opname eq 'dispatch_o'
                   || $opname eq 'dispatch_i' || $opname eq 'dispatch_n'
                   || $opname eq 'dispatch_s'
            ?? 'dispatch'
            !! $opname;
        # This unit compiles before the QAST compiler's, so its classes are
        # only reachable through the compiler registry at runtime, the same
        # way the jast stage reaches it above.
        my $qastcomp := nqp::getcomp('QAST');
        nqp::isnull($qastcomp) || !nqp::can($qastcomp, 'operations')
            ?? 0
            !! $qastcomp.operations.core_op_supported($mapped)
    }
}

# Role specifying the default backend for this build.
role HLL::Backend::Default {
    method default_backend() { HLL::Backend::JVM }
}
