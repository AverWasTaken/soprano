package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the "standing on it and the bucket can't get at it" clock. 20 ticks a second
public class ScoopStallTest {

    private static final long A = 1L;
    private static final long B = 2L;
    private static final double ON_TOP = 1.5 * 1.5;
    private static final int LIMIT_TICKS = (int) (ScoopStall.GIVE_UP_SECONDS * 20);

    // ticks until it says give up, or -1 if it never does within `max`
    private static int ticksToGiveUp(ScoopStall stall, long source, double distSq, boolean pathing, int max) {
        for (int i = 1; i <= max; i++) {
            if (stall.tick(source, distSq, pathing, false)) return i;
        }
        return -1;
    }

    @Test
    public void standingOnACoveredSourceGivesUpAfterTheLimit() {
        ScoopStall stall = new ScoopStall();
        // strict over, so the tick past the limit is the one
        assertEquals(LIMIT_TICKS + 1, ticksToGiveUp(stall, A, ON_TOP, false, 10_000));
    }

    @Test
    public void theGiveUpClearsTheClockSoTheNextSourceStartsFresh() {
        ScoopStall stall = new ScoopStall();
        ticksToGiveUp(stall, A, ON_TOP, false, 10_000);
        assertEquals(0, stall.spentSeconds(), 0);
        assertEquals(LIMIT_TICKS + 1, ticksToGiveUp(stall, B, ON_TOP, false, 10_000));
    }

    @Test
    public void aScoopThatFiresResetsIt() {
        ScoopStall stall = new ScoopStall();
        for (int i = 0; i < LIMIT_TICKS - 5; i++) assertFalse(stall.tick(A, ON_TOP, false, false));
        assertFalse(stall.tick(A, ON_TOP, false, true));
        assertEquals(0, stall.spentSeconds(), 0);
        assertEquals(LIMIT_TICKS + 1, ticksToGiveUp(stall, A, ON_TOP, false, 10_000));
    }

    @Test
    public void aDifferentSourceResetsIt() {
        ScoopStall stall = new ScoopStall();
        for (int i = 0; i < LIMIT_TICKS - 5; i++) assertFalse(stall.tick(A, ON_TOP, false, false));
        // a new pick is not billed for the old one
        assertEquals(LIMIT_TICKS + 1, ticksToGiveUp(stall, B, ON_TOP, false, 10_000));
    }

    @Test
    public void walkingThereIsNeverBilled() {
        ScoopStall stall = new ScoopStall();
        assertEquals(-1, ticksToGiveUp(stall, A, ON_TOP, true, LIMIT_TICKS * 5));
        assertEquals(0, stall.spentSeconds(), 0);
    }

    @Test
    public void farAwayIsNeverBilled() {
        ScoopStall stall = new ScoopStall();
        assertEquals(-1, ticksToGiveUp(stall, A, ScoopStall.NEAR_SQ + 0.01, false, LIMIT_TICKS * 5));
        // right at the edge counts as near
        assertEquals(LIMIT_TICKS + 1, ticksToGiveUp(stall, A, ScoopStall.NEAR_SQ, false, 10_000));
    }

    @Test
    public void pathingTicksPauseTheClockButDontClearIt() {
        ScoopStall stall = new ScoopStall();
        for (int i = 0; i < LIMIT_TICKS - 5; i++) assertFalse(stall.tick(A, ON_TOP, false, false));
        // the no-path loop has a path for a moment now and then, that must not hand out a fresh budget
        for (int i = 0; i < 100; i++) assertFalse(stall.tick(A, ON_TOP, true, false));
        assertTrue(ticksToGiveUp(stall, A, ON_TOP, false, 10) > 0);
    }
}
