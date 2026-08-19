package org.raku.nqp.truffle;

/**
 * The mutable part of one match.
 *
 * <p>Everything a match needs that is not the pattern lives here rather
 * than being threaded through parameters, so the node protocol stays
 * {@code match(state, pos)} and every call site in the engine has a fixed
 * shape. One of these is allocated per match; partial evaluation is
 * expected to see it does not escape and keep its fields in registers.
 *
 * <p>The per-quantifier arrays are indexed by a slot handed out when the
 * pattern is built, which is why they can be flat arrays rather than a
 * stack: a quantifier holds exactly one live repetition count at a time
 * within a single match, and a nested one has a slot of its own.
 */
public final class RxState {

    public final RxCursor cursor;
    public final String target;
    public final int eos;

    /*
     * One array rather than three, and none at all for a pattern with no
     * quantifiers or captures: a match is entered once per position when
     * scanning, so an allocation here is an allocation per character.
     */
    private static final int[] NO_SLOTS = new int[0];
    private static final int COUNT = 0;
    private static final int LAST_POS = 1;
    private static final int CAPTURE_START = 2;
    private static final int PER_SLOT = 3;

    private final int[] slotData;

    public RxState(RxCursor cursor, int slots) {
        this.cursor = cursor;
        this.target = cursor.target();
        this.eos = cursor.eos();
        this.slotData = slots == 0 ? NO_SLOTS : new int[slots * PER_SLOT];
    }

    int count(int slot) { return slotData[slot * PER_SLOT + COUNT]; }

    void setCount(int slot, int v) { slotData[slot * PER_SLOT + COUNT] = v; }

    int lastPos(int slot) { return slotData[slot * PER_SLOT + LAST_POS]; }

    void setLastPos(int slot, int v) { slotData[slot * PER_SLOT + LAST_POS] = v; }

    int captureStart(int slot) { return slotData[slot * PER_SLOT + CAPTURE_START]; }

    void setCaptureStart(int slot, int v) { slotData[slot * PER_SLOT + CAPTURE_START] = v; }
}
