package adris.altoclef.tasks.speedrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KillEnderDragonWithBedsTaskTest {

    @Test
    public void aPlacedBedCountsAsOneLeft() {
        assertEquals(1, KillEnderDragonWithBedsTask.countBeds(0, true));
        assertEquals(0, KillEnderDragonWithBedsTask.countBeds(0, false));
        assertEquals(4, KillEnderDragonWithBedsTask.countBeds(3, true));
    }

    @Test
    public void nearlyWonLatchesAtTheLine() {
        // 3 beds x 25 = 75
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(false, true, 76, 3));
        assertTrue(KillEnderDragonWithBedsTask.nearlyWon(false, true, 75, 3));
        // 4 beds x 25 = 100, capped at 80
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(false, true, 81, 4));
        assertTrue(KillEnderDragonWithBedsTask.nearlyWon(false, true, 80, 4));
        // a full bag at a full dragon is not nearly won
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(false, true, 200, 9));
        // never with no beds, and never outside a landing
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(false, true, 1, 0));
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(false, false, 10, 4));
    }

    @Test
    public void nearlyWonSurvivesAHealAndAGoneBed() {
        boolean latch = KillEnderDragonWithBedsTask.nearlyWon(false, true, 40, 2);
        assertTrue(latch);
        // the dragon heals way over the line
        latch = KillEnderDragonWithBedsTask.nearlyWon(latch, true, 90, 2);
        assertTrue(latch);
        // the bed just went off and the bag is empty: none left for a moment
        latch = KillEnderDragonWithBedsTask.nearlyWon(latch, true, 90, 0);
        assertTrue(latch);
        // the landing ends, so does the latch
        latch = KillEnderDragonWithBedsTask.nearlyWon(latch, false, 90, 2);
        assertFalse(latch);
        assertFalse(KillEnderDragonWithBedsTask.nearlyWon(latch, true, 90, 2));
    }

    @Test
    public void endermanOnUsIsHeldOnlyWhenNearlyWonAndHealthy() {
        // on us (gap 2.3): today a fight, nearly won with health a hold
        assertFalse(KillEnderDragonWithBedsTask.holdEnderman(true, false, 20, 2.3));
        assertTrue(KillEnderDragonWithBedsTask.holdEnderman(true, true, 20, 2.3));
        // the health floor lets it go
        assertTrue(KillEnderDragonWithBedsTask.holdEnderman(true, true, 12.5, 2.3));
        assertFalse(KillEnderDragonWithBedsTask.holdEnderman(true, true, 12, 2.3));
        assertFalse(KillEnderDragonWithBedsTask.holdEnderman(true, true, 8, 2.3));
    }

    @Test
    public void endermanFarOffIsHeldLikeBeforeAndNothingOutsideTheStand() {
        assertTrue(KillEnderDragonWithBedsTask.holdEnderman(true, false, 5, 6));
        assertFalse(KillEnderDragonWithBedsTask.holdEnderman(false, true, 20, 2.3));
        assertFalse(KillEnderDragonWithBedsTask.holdEnderman(false, true, 20, 6));
    }

    @Test
    public void theAuraSparesEndermenOnlyUnderTheNearlyWonHold() {
        assertTrue(KillEnderDragonWithBedsTask.auraSparesEnderman(true, true, 20));
        assertFalse(KillEnderDragonWithBedsTask.auraSparesEnderman(true, true, 12));
        assertFalse(KillEnderDragonWithBedsTask.auraSparesEnderman(true, false, 20));
        assertFalse(KillEnderDragonWithBedsTask.auraSparesEnderman(false, true, 20));
    }

    @Test
    public void theLatchIsOnlyFreshWhileWeTickTheLanding() {
        assertTrue(KillEnderDragonWithBedsTask.latchFresh(100, 100));
        assertTrue(KillEnderDragonWithBedsTask.latchFresh(100 + KillEnderDragonWithBedsTask.NEARLY_WON_FRESH_TICKS, 100));
        assertFalse(KillEnderDragonWithBedsTask.latchFresh(101 + KillEnderDragonWithBedsTask.NEARLY_WON_FRESH_TICKS, 100));
        // never seen a landing, or a clock that went backwards
        assertFalse(KillEnderDragonWithBedsTask.latchFresh(100, Long.MIN_VALUE / 2));
        assertFalse(KillEnderDragonWithBedsTask.latchFresh(50, 100));
    }
}
