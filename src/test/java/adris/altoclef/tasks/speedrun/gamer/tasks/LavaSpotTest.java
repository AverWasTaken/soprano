package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LavaSpotTest {
    @Test
    public void theGiveUpOrTwentySecondsOfHoldingArmsIt() {
        assertTrue(LavaSpot.trigger(true, 0));
        assertTrue(LavaSpot.trigger(false, LavaSpot.HOLD_TRIGGER_TICKS));
        assertFalse(LavaSpot.trigger(false, LavaSpot.HOLD_TRIGGER_TICKS - 1));
    }

    @Test
    public void endermenNearItAreSkippedUntilItExpires() {
        LavaSpot spot = new LavaSpot();
        assertFalse(spot.covers(0, 0, 0, 0));
        assertTrue(spot.arm(100, 60, 100, 1000));
        assertTrue(spot.covers(110, 60, 110, 1001));
        assertTrue(spot.covers(100 + LavaSpot.RADIUS, 60, 100, 1001));
        assertFalse(spot.covers(100 + LavaSpot.RADIUS + 1, 60, 100, 1001));
        long last = 1000 + LavaSpot.LIFETIME_TICKS - 1;
        assertTrue(spot.covers(100, 60, 100, last));
        assertFalse(spot.covers(100, 60, 100, last + 1));
    }

    @Test
    public void aSecondTriggerWhileLiveDoesNotMoveIt() {
        LavaSpot spot = new LavaSpot();
        assertTrue(spot.arm(0, 0, 0, 0));
        assertFalse(spot.arm(500, 0, 500, 10));
        assertFalse(spot.covers(500, 0, 500, 10));
        // after it ran out a new one can go up somewhere else
        assertTrue(spot.arm(500, 0, 500, LavaSpot.LIFETIME_TICKS));
        assertTrue(spot.covers(500, 0, 500, LavaSpot.LIFETIME_TICKS + 1));
        assertFalse(spot.covers(0, 0, 0, LavaSpot.LIFETIME_TICKS + 1));
    }

    @Test
    public void standingInsideALiveSpotRestartsItsClock() {
        LavaSpot spot = new LavaSpot();
        assertTrue(spot.arm(0, 0, 0, 0));
        long late = LavaSpot.LIFETIME_TICKS - 5;
        assertTrue(spot.arm(10, 0, 10, late));
        // the clock moved: it would have been over by now
        assertTrue(spot.active(LavaSpot.LIFETIME_TICKS + 100));
        assertFalse(spot.active(late + LavaSpot.LIFETIME_TICKS));
        // and it stayed where it was, the bot only had to be inside
        assertTrue(spot.covers(0, 0, 0, late + 1));
        assertFalse(spot.arm(500, 0, 500, late + 2));
    }

    @Test
    public void aClockThatWentBackwardsIsNotLive() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 5000);
        assertFalse(spot.active(10));
    }
}
