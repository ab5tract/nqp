package org.raku.nqp.truffle;

import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.sixmodel.SixModelObject;

/**
 * The engine's view of a real NQP Cursor.
 *
 * <p>Every step here is the one the bytecode path takes, so the two agree
 * about what a match does to a cursor:
 *
 * <ul>
 *   <li>a subrule is {@code $!pos := pos} followed by a method call, and the
 *       position it reached is {@code $!pos} on the cursor it answered,
 *       negative meaning no match;
 *   <li>a span becomes a capture through
 *       {@code !cursor_start_subcapture}, {@code !cursor_pass} and
 *       {@code !cursor_capture} -- a cursor is built for the span, passed at
 *       its end, and then captured, because a capture in a match tree is a
 *       cursor rather than a pair of offsets;
 *   <li>a rule's own result is captured directly, since it is a cursor
 *       already.
 * </ul>
 */
public final class NqpCursor implements RxCursor {

    private static final CallSiteDescriptor INVOCANT =
        new CallSiteDescriptor(new byte[] { CallSiteDescriptor.ARG_OBJ }, null);
    private static final CallSiteDescriptor INVOCANT_INT =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT }, null);
    private static final CallSiteDescriptor INVOCANT_OBJ_STR =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ,
                         CallSiteDescriptor.ARG_STR }, null);

    private final ThreadContext tc;
    private final SixModelObject cursor;
    private final SixModelObject cursorClass;
    private final String target;

    public NqpCursor(ThreadContext tc, SixModelObject cursor, String target) {
        this.tc = tc;
        this.cursor = cursor;
        this.cursorClass = cursor.st.WHAT;
        this.target = target;
    }

    @Override public String target() { return target; }

    @Override public int eos() { return target.length(); }

    /**
     * Calls a rule of the grammar.
     *
     * <p>This is where partial evaluation stops: the callee is compiled NQP
     * bytecode rather than Truffle nodes, so there is nothing on the far
     * side to fold into the caller. It is the same boundary that makes the
     * dispatch work follow code generation moving to Truffle rather than
     * precede it.
     */
    @Override public Object callSubrule(String name, int pos) {
        Ops.bindattr_i(cursor, cursorClass, "$!pos", pos, tc);
        SixModelObject method = Ops.findmethod(cursor, name, tc);
        Ops.invokeDirect(tc, method, INVOCANT, new Object[] { cursor });
        return Ops.result_o(tc.curFrame);
    }

    @Override public int reached(Object subCursor) {
        if (!(subCursor instanceof SixModelObject sub)) return RxVmNode.NO_MATCH;
        long pos = Ops.getattr_i(sub, sub.st.WHAT, "$!pos", tc);
        return pos < 0 ? RxVmNode.NO_MATCH : (int) pos;
    }

    @Override public void captureSpan(String name, int from, int to) {
        /* No rule produced this, so there is no cursor for it yet: build one
         * over the span and pass it, which is what makes it a capture NQP
         * can put in the match tree. */
        SixModelObject start = Ops.findmethod(cursor, "!cursor_start_subcapture", tc);
        Ops.invokeDirect(tc, start, INVOCANT_INT, new Object[] { cursor, (long) from });
        SixModelObject sub = Ops.result_o(tc.curFrame);

        SixModelObject pass = Ops.findmethod(sub, "!cursor_pass", tc);
        Ops.invokeDirect(tc, pass, INVOCANT_INT, new Object[] { sub, (long) to });

        captureCursor(name, sub);
    }

    @Override public void captureCursor(String name, Object subCursor) {
        SixModelObject capture = Ops.findmethod(cursor, "!cursor_capture", tc);
        Ops.invokeDirect(tc, capture, INVOCANT_OBJ_STR,
            new Object[] { cursor, subCursor, name });
    }
}
