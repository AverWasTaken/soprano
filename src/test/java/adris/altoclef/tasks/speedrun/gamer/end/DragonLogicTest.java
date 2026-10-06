package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the strat pick and the dead latch, both pure
public class DragonLogicTest {
    private final EndConfig cfg = new EndConfig();

    @Test
    public void firstPickNeedsBedsAndArmor() {
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(null, 8, 15, false, 0, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(null, 0, 15, false, 0, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(null, 8, 9, false, 0, cfg));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(null, 1, 10, false, 0, cfg));
    }

    @Test
    public void everyDeathRaisesTheArmorLine() {
        // full iron is 15, full diamond 20. line is 10, 14, 18, 22
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(null, 8, 15, false, 1, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(null, 8, 15, false, 2, cfg));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(null, 8, 20, false, 2, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(null, 8, 20, false, 3, cfg));
    }

    @Test
    public void runningOutOfBedsFallsBackToTheSword() {
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.BEDS, 0, 15, false, 0, cfg));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.BEDS, 1, 15, false, 0, cfg));
    }

    @Test
    public void neverSwapsMidPerch() {
        // the last bed is placed (so the count is 0) and waiting for its click
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.BEDS, 0, 15, true, 0, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.SWORD, 8, 15, true, 0, cfg));
    }

    @Test
    public void armorWobbleDoesNotDropBeds() {
        // pieces swapping for a moment: 2 under the line is within the slack
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.BEDS, 8, 8, false, 0, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.BEDS, 8, 7, false, 0, cfg));
    }

    @Test
    public void comingBackToBedsNeedsARealStack() {
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.SWORD, 2, 15, false, 0, cfg));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.SWORD, DragonStrat.RESUME_BEDS, 15, false, 0, cfg));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.SWORD, 8, 5, false, 0, cfg));
    }

    @Test
    public void exitPortalMeansDeadEvenIfWeNeverSawTheDragon() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertTrue(latch.update(true, false, true, 0));
        assertTrue(latch.isDead());
    }

    @Test
    public void aDragonThatWasNeverSeenIsNotDeadJustBecauseItIsMissing() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertFalse(latch.update(false, false, true, 0));
        assertFalse(latch.update(false, false, true, 100));
        assertFalse(latch.hasSeenDragon());
    }

    @Test
    public void goneForFiveSecondsWithTheCenterLoadedIsDead() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        assertFalse(latch.update(false, true, true, 0));
        assertTrue(latch.hasSeenDragon());
        assertFalse(latch.update(false, false, true, 1));
        assertFalse(latch.update(false, false, true, 5.9));
        assertTrue(latch.update(false, false, true, 6));
        assertTrue(latch.isDead());
        // soft: nothing hard happened, so this one is NOT what RunState may save
        assertFalse(latch.exitPortalSeen());
    }

    @Test
    public void theSoftGuessIsUndoneWhenTheDragonShowsUpAgain() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertFalse(latch.update(false, false, true, 1));
        assertTrue(latch.update(false, false, true, 6));
        // out of tracking range for a while, back in range: still alive
        assertFalse(latch.update(false, true, true, 7));
        assertFalse(latch.isDead());
    }

    @Test
    public void theExitPortalIsHardAndStaysEvenWhenTheDragonIsSeen() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertTrue(latch.update(true, true, true, 1));
        assertTrue(latch.exitPortalSeen());
        assertTrue(latch.update(false, true, true, 2));
    }

    @Test
    public void softGuessWithAnUnloadedChunkClearsInsteadOfStaying() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        latch.update(false, false, true, 1);
        assertTrue(latch.update(false, false, true, 6));
        assertFalse(latch.update(false, false, false, 7));
    }

    @Test
    public void lowHealthKeepsBedsOffTheStartAndOnlyCriticalHealthDropsThem() {
        // full iron: expected self damage 10, margin 4 => 14 hp wanted before a bed
        assertEquals(14, BedSafety.safePool(15), 1e-9);
        assertTrue(BedSafety.canClick(14, 0, 15));
        assertTrue(BedSafety.canClick(10, 4, 15));
        assertFalse(BedSafety.canClick(8, 0, 15));
        // unarmored: more than a full bar, never
        assertFalse(BedSafety.canClick(20, 0, 0));
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(null, 8, 15, false, 0, cfg, 8));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(null, 8, 15, false, 0, cfg, 14));
        // a dent mid fight is not a swap (the bed task waits and heals)...
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.BEDS, 8, 15, false, 0, cfg, 8));
        // ...but near death a melee fight is no better
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.BEDS, 8, 15, false, 0, cfg, 5));
        // and never mid perch
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.BEDS, 8, 15, true, 0, cfg, 5));
        // coming back to beds needs the health too
        assertEquals(DragonStrat.SWORD, DragonStrat.choose(DragonStrat.SWORD, 8, 15, false, 0, cfg, 9));
        assertEquals(DragonStrat.BEDS, DragonStrat.choose(DragonStrat.SWORD, 8, 15, false, 0, cfg, 20));
    }

    @Test
    public void anUnloadedCenterChunkResetsTheTimer() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        assertFalse(latch.update(false, false, true, 1));
        // render distance blip: the dragon is not "gone", we just cannot see it
        assertFalse(latch.update(false, false, false, 4));
        assertFalse(latch.update(false, false, true, 5));
        assertFalse(latch.update(false, false, true, 9.9));
        assertTrue(latch.update(false, false, true, 10));
    }

    @Test
    public void seeingTheDragonAgainRestartsTheTimer() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.update(false, true, true, 0);
        latch.update(false, false, true, 1);
        latch.update(false, true, true, 4);
        assertFalse(latch.update(false, false, true, 6));
        assertFalse(latch.update(false, false, true, 10.9));
        assertTrue(latch.update(false, false, true, 11));
    }

    @Test
    public void resetAndMarkDead() {
        DragonDeadLatch latch = new DragonDeadLatch(5);
        latch.markDead();
        assertTrue(latch.update(false, false, false, 0));
        latch.reset();
        assertFalse(latch.isDead());
        assertFalse(latch.hasSeenDragon());
    }
}
