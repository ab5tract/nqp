package org.raku.nqp.truffle;

import org.raku.nqp.runtime.CallSiteDescriptor;
import org.raku.nqp.runtime.Ops;
import org.raku.nqp.runtime.ThreadContext;
import org.raku.nqp.sixmodel.SixModelObject;

/**
 * The engine's view of a real NQP Cursor.
 *
 * <p>The bytecode path works the same way, which is what this follows: a
 * subrule is a method call on the cursor, and the position it reached is
 * {@code $!pos} on the cursor it answers, negative meaning no match. The
 * target comes from the cursor's shared parse state rather than being
 * passed in, so the two paths agree about what is being matched.
 *
 * <h2>Captures are not faithful yet</h2>
 *
 * NQP does not capture spans, it captures <em>cursors</em>:
 * {@code !cursor_capture} is handed the sub-cursor a rule produced, and
 * that cursor is what ends up in the match tree with its own captures
 * inside it. The engine currently reports a name and a pair of positions,
 * which is enough for a matcher and not enough for a parser. Bridging that
 * means the engine tracking sub-cursors where it now tracks offsets --
 * a change to what {@link RxCursor} promises, not just to this class. Until
 * then a rule with captures has to stay on the bytecode path, which is what
 * the descriptor's fallback is for.
 */
public final class NqpCursor implements RxCursor {

    private static final CallSiteDescriptor INVOCANT_ONLY =
        new CallSiteDescriptor(new byte[] { CallSiteDescriptor.ARG_OBJ }, null);

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
     * Calls a rule of the grammar, the way the bytecode path does: put the
     * position on the cursor, invoke the method, and read the position off
     * whatever it answered.
     *
     * <p>This is the boundary partial evaluation stops at. The callee is
     * compiled NQP bytecode rather than Truffle nodes, so there is nothing
     * on the other side for PE to fold into the caller -- the same reason
     * the dispatch work has to follow code generation moving to Truffle.
     */
    @Override public int callSubrule(String name, int pos) {
        Ops.bindattr_i(cursor, cursorClass, "$!pos", pos, tc);
        SixModelObject method = Ops.findmethod(cursor, name, tc);
        Ops.invokeDirect(tc, method, INVOCANT_ONLY, new Object[] { cursor });
        SixModelObject sub = Ops.result_o(tc.curFrame);
        long reached = Ops.getattr_i(sub, sub.st.WHAT, "$!pos", tc);
        return reached < 0 ? RxVmNode.NO_MATCH : (int) reached;
    }

    @Override public void capture(String name, int from, int to) {
        throw new UnsupportedOperationException(
            "NQP captures cursors rather than spans; a rule with captures stays "
            + "on the bytecode path until the engine tracks sub-cursors");
    }
}
