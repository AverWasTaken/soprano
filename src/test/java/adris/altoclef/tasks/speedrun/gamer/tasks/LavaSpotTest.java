package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
        assertEquals(LavaSpot.Armed.NEW, spot.arm(100, 60, 100, 1000));
        assertTrue(spot.covers(110, 60, 110, 1001));
        assertTrue(spot.covers(100 + LavaSpot.RADIUS, 60, 100, 1001));
        assertFalse(spot.covers(100 + LavaSpot.RADIUS + 1, 60, 100, 1001));
        long last = 1000 + LavaSpot.LIFETIME_TICKS - 1;
        assertTrue(spot.covers(100, 60, 100, last));
        assertFalse(spot.covers(100, 60, 100, last + 1));
    }

    @Test
    public void aSecondTriggerElsewhereAddsASpotAndKeepsTheFirst() {
        LavaSpot spot = new LavaSpot();
        assertEquals(LavaSpot.Armed.NEW, spot.arm(0, 0, 0, 0));
        assertEquals(LavaSpot.Armed.NEW, spot.arm(500, 0, 500, 10));
        assertEquals(2, spot.liveCount(10));
        assertTrue(spot.covers(0, 0, 0, 11));
        assertTrue(spot.covers(500, 0, 500, 11));
        assertEquals("500,0,500", spot.lastCentre());
        // a walk through either one is blocked
        assertTrue(spot.blocksWalk(-50, 0, 50, 0, 11));
        assertTrue(spot.blocksWalk(450, 500, 550, 500, 11));
    }

    @Test
    public void aWalkMissingEveryLiveSpotIsAllowed() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        spot.arm(200, 60, 0, 1);
        // between them, well clear of both
        assertFalse(spot.blocksWalk(100, -50, 100, 50, 2));
        assertFalse(spot.covers(100, 60, 0, 2));
    }

    @Test
    public void oneSpotRunningOutLeavesTheOthersLive() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 0);
        spot.arm(500, 0, 500, 100);
        long firstGone = LavaSpot.LIFETIME_TICKS;
        assertFalse(spot.covers(0, 0, 0, firstGone));
        assertTrue(spot.covers(500, 0, 500, firstGone));
        assertEquals(1, spot.liveCount(firstGone));
        assertTrue(spot.active(firstGone));
        assertFalse(spot.active(100 + LavaSpot.LIFETIME_TICKS));
    }

    @Test
    public void aFourthSpotDropsTheOldest() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 0);
        spot.arm(500, 0, 0, 10);
        spot.arm(1000, 0, 0, 20);
        assertEquals(LavaSpot.Armed.NEW, spot.arm(1500, 0, 0, 30));
        assertEquals(LavaSpot.MAX_SPOTS, spot.liveCount(31));
        assertFalse(spot.covers(0, 0, 0, 31));
        assertTrue(spot.covers(500, 0, 0, 31));
        assertTrue(spot.covers(1000, 0, 0, 31));
        assertTrue(spot.covers(1500, 0, 0, 31));
    }

    @Test
    public void anExpiredSlotIsReusedBeforeALiveOneIsDropped() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 0);
        long later = LavaSpot.LIFETIME_TICKS - 100;
        spot.arm(500, 0, 0, later);
        spot.arm(1000, 0, 0, later + 1);
        // the first one ran out, the new spot takes its slot and both newer ones stay
        long now = LavaSpot.LIFETIME_TICKS + 5;
        assertEquals(LavaSpot.Armed.NEW, spot.arm(1500, 0, 0, now));
        assertEquals(3, spot.liveCount(now));
        assertTrue(spot.covers(500, 0, 0, now));
        assertTrue(spot.covers(1000, 0, 0, now));
        assertTrue(spot.covers(1500, 0, 0, now));
        assertFalse(spot.covers(0, 0, 0, now));
    }

    @Test
    public void runningOutOfOneThenArmingThereAgainStartsFresh() {
        LavaSpot spot = new LavaSpot();
        assertEquals(LavaSpot.Armed.NEW, spot.arm(0, 0, 0, 0));
        assertEquals(LavaSpot.Armed.NEW, spot.arm(500, 0, 500, LavaSpot.LIFETIME_TICKS));
        assertTrue(spot.covers(500, 0, 500, LavaSpot.LIFETIME_TICKS + 1));
        assertFalse(spot.covers(0, 0, 0, LavaSpot.LIFETIME_TICKS + 1));
    }

    @Test
    public void standingInsideALiveSpotRestartsItsClock() {
        LavaSpot spot = new LavaSpot();
        assertEquals(LavaSpot.Armed.NEW, spot.arm(0, 0, 0, 0));
        long late = LavaSpot.LIFETIME_TICKS - 5;
        assertEquals(LavaSpot.Armed.RESTARTED, spot.arm(10, 0, 10, late));
        // the clock moved: it would have been over by now
        assertTrue(spot.active(LavaSpot.LIFETIME_TICKS + 100));
        assertFalse(spot.active(late + LavaSpot.LIFETIME_TICKS));
        // and it stayed where it was, the bot only had to be inside
        assertTrue(spot.covers(0, 0, 0, late + 1));
        assertEquals("0,0,0", spot.lastCentre());
        assertEquals(1, spot.liveCount(late + 1));
    }

    @Test
    public void insideTwoOverlappingSpotsTheCloserOneRestarts() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 0);
        spot.arm(30, 0, 0, 10);
        // 20 from the first and 10 from the second, inside both
        assertEquals(LavaSpot.Armed.RESTARTED, spot.arm(20, 0, 0, 1000));
        assertEquals("30,0,0", spot.lastCentre());
        assertEquals(2, spot.liveCount(1000));
        // the first kept its old clock
        assertFalse(spot.covers(-10, 0, 0, LavaSpot.LIFETIME_TICKS));
        assertTrue(spot.covers(40, 0, 0, LavaSpot.LIFETIME_TICKS));
    }

    @Test
    public void aWalkThroughTheSpotIsBlocked() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        // us on one side, the enderman on the other, both well outside it
        assertTrue(spot.blocksWalk(-50, 0, 50, 0, 1));
        // grazing the edge counts, one block further off does not
        assertTrue(spot.blocksWalk(-50, LavaSpot.RADIUS, 50, LavaSpot.RADIUS, 1));
        assertFalse(spot.blocksWalk(-50, LavaSpot.RADIUS + 1, 50, LavaSpot.RADIUS + 1, 1));
    }

    @Test
    public void anEndermanOnOurSideOfTheSpotIsFine() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        // the line carried on would hit it, the walk stops short
        assertFalse(spot.blocksWalk(-60, 0, -30, 0, 1));
        // and walking off away from it never gets close
        assertFalse(spot.blocksWalk(40, 0, 80, 0, 1));
    }

    @Test
    public void everyWalkFromInsideTheSpotIsBlocked() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 60, 0, 0);
        assertTrue(spot.blocksWalk(10, 0, 80, 0, 1));
        // standing right on the enderman, no walk at all, the zero length line must not blow up
        assertTrue(spot.blocksWalk(10, 0, 10, 0, 1));
        assertFalse(spot.blocksWalk(80, 0, 80, 0, 1));
    }

    @Test
    public void aSpotThatRanOutBlocksNothing() {
        LavaSpot spot = new LavaSpot();
        assertFalse(spot.blocksWalk(-50, 0, 50, 0, 1));
        spot.arm(0, 60, 0, 0);
        assertTrue(spot.blocksWalk(-50, 0, 50, 0, LavaSpot.LIFETIME_TICKS - 1));
        assertFalse(spot.blocksWalk(-50, 0, 50, 0, LavaSpot.LIFETIME_TICKS));
    }

    @Test
    public void aClockThatWentBackwardsIsNotLive() {
        LavaSpot spot = new LavaSpot();
        spot.arm(0, 0, 0, 5000);
        assertFalse(spot.active(10));
    }
}
