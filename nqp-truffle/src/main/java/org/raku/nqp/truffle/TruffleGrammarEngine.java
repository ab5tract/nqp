package org.raku.nqp.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import org.raku.nqp.runtime.GrammarEngine;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.sixmodel.SixModelObject;

/**
 * The engine as the rest of NQP sees it.
 *
 * <p>This is the class {@code GrammarEngines} looks up by name, and the only
 * one it needs to know about: everything below here is free to change without
 * the boot-classpath side being able to name any of it.
 *
 * <p>One polyglot context is created for the process and never closed. It has
 * to be a context rather than loose RootNodes -- outside one, a root node runs
 * correctly and is never queued for compilation, so the partial evaluation
 * this whole design exists for silently does not happen.
 */
public final class TruffleGrammarEngine implements GrammarEngine {

    private static final CallSiteDescriptor INVOCANT =
        new CallSiteDescriptor(new byte[] { CallSiteDescriptor.ARG_OBJ }, null);
    private static final CallSiteDescriptor INVOCANT_INT =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT }, null);
    private static final CallSiteDescriptor INVOCANT_INT_STR =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT,
                         CallSiteDescriptor.ARG_STR }, null);

    /**
     * Built on first use rather than in the constructor: the runtime looks the
     * engine up while deciding whether there is one at all, and starting a
     * polyglot context is far too much to do just to answer that.
     */
    private static final class Holder {
        static final Context CONTEXT = Context.newBuilder(RxLanguage.ID)
            .allowExperimentalOptions(true)
            .build();

        static {
            String runtime = Truffle.getRuntime().getName();
            if (!runtime.contains("GraalVM")) {
                /* The fallback interpreter does no partial evaluation, so the
                 * engine would be a slower bytecode path with extra steps.
                 * Saying so beats measuring it later and blaming Truffle. */
                System.err.println("nqp: Truffle runtime is '" + runtime
                    + "', not an optimizing one; grammar matching will be slow."
                    + " Check --module-path and --add-modules on the runner.");
            }
        }
    }

    /**
     * A compiled regex.
     *
     * <p>The pass name rides alongside the call target rather than inside it:
     * matching answers a position, and what the cursor is then told is the
     * caller's business, exactly as it is on the bytecode path.
     */
    private record Program(CallTarget target, String passName, boolean scan) { }

    @Override
    public Object compile(String encoded) {
        /* The eval is what makes the language parse the descriptor; its result
         * is a polyglot Value, and calling through one would box every
         * argument of every match, so the call target is collected from where
         * parse left it instead. */
        Holder.CONTEXT.eval(Source.newBuilder(RxLanguage.ID, encoded, "rx").buildLiteral());
        CallTarget target = RxLanguage.PARSED.get(encoded);
        if (target == null) {
            throw new IllegalStateException("the language parsed no matcher for: " + encoded);
        }
        RxWire.Descriptor d = RxWire.decode(encoded);
        return new Program(target, d.passName(), d.scan());
    }

    @Override
    public SixModelObject match(Object program, ThreadContext tc, SixModelObject cursor,
                                SixModelObject cursorClass, String target,
                                int from, int invocantFrom) {
        Program p = (Program) program;
        NqpCursor rx = new NqpCursor(tc, cursor, cursorClass, target);

        int at = from;
        int end;
        /* A scan retries the whole body one character further along until it
         * matches. It applies only when the invocant's $!from is -1, which is
         * a top-level parse; a subrule is called at a position and must match
         * there or not at all. Getting that backwards makes every subrule
         * silently search forward, which parses -- just not the language. */
        if (p.scan() && invocantFrom == -1) {
            int eos = target.length();
            for (end = RxVmNode.NO_MATCH; at <= eos; at++) {
                end = (Integer) p.target().call(target, at, rx);
                if (end >= 0) break;
            }
            if (end < 0) at = from;
        } else {
            end = (Integer) p.target().call(target, at, rx);
        }

        /* Where the match began is the cursor's $!from, and the scan is what
         * moves it: !cursor_start_all set it to the position the rule was
         * called at, which is no longer where the match starts. */
        if (end >= 0 && at != from) {
            Ops.bindattr_i(cursor, cursorClass, "$!from", at, tc);
        }

        /* The bytecode path ends the same two ways, and a cursor that was
         * neither passed nor failed is not a usable match result. The name is
         * what makes !cursor_pass reduce; without it a rule would match the
         * right span and build nothing. */
        if (end < 0) {
            call(tc, cursor, "!cursor_fail");
        } else if (p.passName().isEmpty()) {
            call(tc, cursor, "!cursor_pass", end);
        } else {
            call(tc, cursor, "!cursor_pass", end, p.passName());
        }
        return cursor;
    }

    private static void call(ThreadContext tc, SixModelObject cursor, String name) {
        SixModelObject method = Ops.findmethod(cursor, name, tc);
        Ops.invokeDirect(tc, method, INVOCANT, new Object[] { cursor });
    }

    private static void call(ThreadContext tc, SixModelObject cursor, String name, int arg) {
        SixModelObject method = Ops.findmethod(cursor, name, tc);
        Ops.invokeDirect(tc, method, INVOCANT_INT, new Object[] { cursor, (long) arg });
    }

    private static void call(ThreadContext tc, SixModelObject cursor, String name, int pos, String arg) {
        SixModelObject method = Ops.findmethod(cursor, name, tc);
        Ops.invokeDirect(tc, method, INVOCANT_INT_STR, new Object[] { cursor, (long) pos, arg });
    }
}
