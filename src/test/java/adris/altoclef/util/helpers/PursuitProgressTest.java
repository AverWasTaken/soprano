package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PursuitProgressTest {
    private static final Object LOG = "log at 1 64 1";
    private static final Object OTHER = "log at 3 64 3";

    private static void standStill(PursuitProgress p, Object on, double dist, int ticks) {
        for (int i = 0; i < ticks; i++) {
            p.tick(on, dist, false);
        }
    }

    @Test
    public void nothingPursuedIsNeverStalled() {
        assertFalse(new PursuitProgress().stalled());
    }

    @Test
    public void sevenSecondsWithoutGettingCloserIsStalled() {
        assertEquals(7 * 20, PursuitProgress.STALL_TICKS);
        PursuitProgress p = new PursuitProgress();
        // the first tick is where the clock starts, not a tick of getting nowhere
        standStill(p, LOG, 10, PursuitProgress.STALL_TICKS);
        assertFalse(p.stalled());
        p.tick(LOG, 10, false);
        assertTrue(p.stalled());
    }

    @Test
    public void halfABlockCloserStartsTheClockOverAndLessDoesNot() {
        PursuitProgress p = new PursuitProgress();
        p.tick(LOG, 10, false);
        standStill(p, LOG, 9.6, PursuitProgress.STALL_TICKS - 1);
        // creeping up a few centimetres at a time is not a walk that works
        p.tick(LOG, 9.6, false);
        assertTrue(p.stalled());
        p.tick(LOG, 9.4, false);
        assertFalse(p.stalled());
    }

    @Test
    public void walkingAwayAndBackDoesNotCountAsProgress() {
        PursuitProgress p = new PursuitProgress();
        p.tick(LOG, 10, false);
        standStill(p, LOG, 14, 60);
        // back where we were is still not closer than ever
        standStill(p, LOG, 10, PursuitProgress.STALL_TICKS - 60);
        assertTrue(p.stalled());
    }

    @Test
    public void aBlockBrokenOnTheWayIsProgress() {
        PursuitProgress p = new PursuitProgress();
        p.tick(LOG, 10, false);
        standStill(p, LOG, 10, PursuitProgress.STALL_TICKS - 1);
        p.tick(LOG, 10, true);
        assertFalse(p.stalled());
        standStill(p, LOG, 10, PursuitProgress.STALL_TICKS - 1);
        assertFalse(p.stalled());
    }

    @Test
    public void inReachIsWorkingOnItNotGettingNowhere() {
        PursuitProgress p = new PursuitProgress();
        p.tick(LOG, PursuitProgress.IN_REACH, false);
        standStill(p, LOG, PursuitProgress.IN_REACH, PursuitProgress.STALL_TICKS * 3);
        assertFalse(p.stalled());
        // a step out of reach and the clock runs again
        standStill(p, LOG, PursuitProgress.IN_REACH + 0.1, PursuitProgress.STALL_TICKS);
        assertTrue(p.stalled());
    }

    @Test
    public void aNewTargetGetsAFreshClock() {
        PursuitProgress p = new PursuitProgress();
        p.tick(LOG, 10, false);
        standStill(p, LOG, 10, PursuitProgress.STALL_TICKS);
        assertTrue(p.stalled());
        p.tick(OTHER, 30, false);
        assertFalse(p.stalled());
        p.clear();
        assertFalse(p.stalled());
    }
}
