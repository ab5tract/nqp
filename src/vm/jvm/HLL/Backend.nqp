# Backend class for the JVM.

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
        'unit jar jvm'
    }

    method is_precomp_stage($stage) {
        $stage eq 'unit' || $stage eq 'jar'
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
    
    method unit($qast, *%adverbs) {
        my $unit_id := %*COMPILING<%?OPTIONS><javaclass> || nqp::sha1('eval-at-' ~ nqp::time() ~ $compile_count++);
        my $unit := nqp::getcomp('QAST').unit($qast, :$unit_id);
        # A jar-bound unit with an output file is written as a zip; every
        # other unit -- a script, an EVAL, a BEGIN-time unit, a
        # --target=jar with no --output -- is built in memory as a record,
        # which the jvm stage (nqp::loadcompunit) turns into a ProgramUnit.
        if %adverbs<target> eq 'jar' && %adverbs<output> {
            my str $unit_output := %adverbs<output>;
            nqp::syscall('jvm-write-unit-record', $unit, $unit_output);
            nqp::null()
        }
        else {
            nqp::syscall('jvm-build-unit-record', $unit)
        }
    }

    method jar($cu, *%adverbs) {
        $cu   # the unit stage wrote or built it; this stage names the target
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
        # way the unit stage reaches it above.
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
