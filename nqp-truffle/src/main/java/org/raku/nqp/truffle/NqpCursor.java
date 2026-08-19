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
    private static final CallSiteDescriptor INVOCANT_INT_STR_OBJ =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_INT,
                         CallSiteDescriptor.ARG_STR, CallSiteDescriptor.ARG_OBJ }, null);
    private static final CallSiteDescriptor INVOCANT_OBJ_STR =
        new CallSiteDescriptor(
            new byte[] { CallSiteDescriptor.ARG_OBJ, CallSiteDescriptor.ARG_OBJ,
                         CallSiteDescriptor.ARG_STR }, null);

    private final ThreadContext tc;
    private final SixModelObject cursor;
    private final SixModelObject cursorClass;
    private final String target;

    /**
     * @param cursorClass the class the cursor's attributes are DECLARED in --
     *     `$?CLASS` from `!cursor_start_all`, which is what the bytecode path
     *     uses. The cursor's own WHAT is a subclass for any real grammar, and
     *     looking an attribute up through it fails with "No such attribute".
     */
    public NqpCursor(ThreadContext tc, SixModelObject cursor, SixModelObject cursorClass,
                     String target) {
        this.tc = tc;
        this.cursor = cursor;
        this.cursorClass = cursorClass;
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
        long pos = Ops.getattr_i(sub, cursorClass, "$!pos", tc);
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

    /**
     * Asks the grammar's NFA which branches of a named alternation to try.
     *
     * <p>`!alt` is the bytecode path's own entry point, and it answers by
     * pushing four ints per branch onto the cursor's bstack -- mark, pos,
     * rep, capture height -- best LAST, because the bytecode engine reaches
     * them by popping. The marks it pushes are whatever was handed to it, so
     * passing the branch indices themselves makes the answer come back in
     * the engine's own terms.
     *
     * <p>The bstack is left as it was found. The engine keeps its choice
     * points in its own stack and this rule is ratcheted anyway, so entries
     * left behind would be read later as backtracking that never happened.
     */
    @Override public int[] altOrder(String name, int pos, int branches) {
        SixModelObject bstack = Ops.getattr(cursor, cursorClass, "$!bstack", tc);
        if (bstack == null || Ops.isnull(bstack) != 0) return RxCursor.NO_BRANCHES;
        int before = (int) bstack.elems(tc);

        SixModelObject marks = Ops.create(Ops.bootintarray(tc), tc);
        for (int i = 0; i < branches; i++) {
            tc.nativeI = i;
            marks.push_native(tc);
        }

        /* No $!pos binding here: !alt takes the position as an argument and
         * the bytecode path does not touch the attribute either. Setting it
         * would leave a mid-match value behind for !cursor_capture to record. */
        SixModelObject alt = Ops.findmethod(cursor, "!alt", tc);
        Ops.invokeDirect(tc, alt, INVOCANT_INT_STR_OBJ,
            new Object[] { cursor, (long) pos, name, marks });

        int after = (int) bstack.elems(tc);
        int found = (after - before) / 4;
        int[] order = found <= 0 ? RxCursor.NO_BRANCHES : new int[found];
        /* Reversed: !alt pushes the best branch last so that popping finds
         * it first, and the engine wants them best first. */
        for (int i = 0; i < found; i++) {
            bstack.at_pos_native(tc, before + 4L * (found - 1 - i));
            order[i] = (int) tc.nativeI;
        }
        bstack.set_elems(tc, before);
        return order;
    }

    @Override public void captureCursor(String name, Object subCursor) {
        SixModelObject capture = Ops.findmethod(cursor, "!cursor_capture", tc);
        Ops.invokeDirect(tc, capture, INVOCANT_OBJ_STR,
            new Object[] { cursor, subCursor, name });
    }
}
