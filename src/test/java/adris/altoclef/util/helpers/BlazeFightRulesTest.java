package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BlazeFightRules.Mode.CAMP;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.KILL;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.RUN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// three moods: camp, kill the one we picked, run. the old version had cover and hunts and flipped between them every second
public class BlazeFightRulesTest {

    @Test
    public void noTargetMeansCampWhateverIsLooking() {
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, false, false));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, true, false));
    }

    @Test
    public void aTargetMeansKillWithOrWithoutAThreat() {
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, true, true));
    }

    @Test
    public void tenHpWithABlazeLookingMeansRun() {
        assertEquals(RUN, BlazeFightRules.decide(10, false, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(10, false, false, true, false));
        assertEquals(RUN, BlazeFightRules.decide(14, false, false, true, true));
        assertEquals(KILL, BlazeFightRules.decide(15, false, false, true, true));
    }

    @Test
    public void tenHpWithNothingLookingKeepsWorking() {
        assertEquals(KILL, BlazeFightRules.decide(10, false, false, false, true));
        assertEquals(CAMP, BlazeFightRules.decide(10, false, false, false, false));
    }

    @Test
    public void sixHpRunsWhetherOrNotAnythingIsLooking() {
        assertEquals(RUN, BlazeFightRules.decide(6, false, false, false, false));
        assertEquals(RUN, BlazeFightRules.decide(6, false, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(7, false, false, false, true));
    }

    @Test
    public void onceRunningWeRunUntilEighteen() {
        assertEquals(RUN, BlazeFightRules.decide(11, true, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(17.9f, true, false, false, true));
        assertEquals(KILL, BlazeFightRules.decide(18, true, false, false, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, true, false, true, false));
    }

    @Test
    public void aRunHoldsWhileWeAreStillBurning() {
        assertEquals(RUN, BlazeFightRules.decide(20, true, false, false, true, true));
        assertEquals(KILL, BlazeFightRules.decide(20, true, false, false, true, false));
    }

    @Test
    public void burningAfterTheRunGaveUpDoesNotHoldIt() {
        assertEquals(KILL, BlazeFightRules.decide(20, true, true, false, true, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, true, true, false, false, true));
    }

    @Test
    public void burningAtFullHpDoesNotStartARun() {
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, true, true, true));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, true, false, true));
    }

    @Test
    public void theGapBetweenLeavingAndComingBackIsWideEnoughNotToFlap() {
        assertTrue(BlazeFightRules.RECOVERED_HEALTH - BlazeFightRules.RUN_HEALTH >= 4);
        // at 16 hp it depends only on which side we came from
        assertEquals(KILL, BlazeFightRules.decide(16, false, false, true, true));
        assertEquals(RUN, BlazeFightRules.decide(16, true, false, true, true));
    }

    @Test
    public void aRunThatStoppedHealingFallsBackToTheFreshHpRules() {
        // 12 hp, run given up and nothing looking: back to work
        assertEquals(KILL, BlazeFightRules.decide(12, true, true, false, true));
        // but 6 hp still runs
        assertEquals(RUN, BlazeFightRules.decide(6, true, true, false, true));
    }

    @Test
    public void aRunThatStoppedHealingGivesUp() {
        assertFalse(BlazeFightRules.runStalled(BlazeFightRules.RUN_GIVE_UP_TICKS - 1));
        assertTrue(BlazeFightRules.runStalled(BlazeFightRules.RUN_GIVE_UP_TICKS));
        // stalled while running: gives up, and keeps it while it stays that way
        assertTrue(BlazeFightRules.runGaveUpNext(false, true, false, true));
        assertTrue(BlazeFightRules.runGaveUpNext(true, true, false, false));
        // hp rising or not running any more clears it
        assertFalse(BlazeFightRules.runGaveUpNext(true, true, true, true));
        assertFalse(BlazeFightRules.runGaveUpNext(true, false, false, true));
    }

    @Test
    public void meleeRangeBlazeIsAlwaysAThreat() {
        assertTrue(BlazeFightRules.isThreat(2.5, false, false));
        assertTrue(BlazeFightRules.isThreat(3, false, false));
    }

    @Test
    public void farBlazeIsAThreatOnlyWhileWindingUpWithASightline() {
        assertFalse(BlazeFightRules.isThreat(10, false, true));
        assertFalse(BlazeFightRules.isThreat(10, true, false));
        assertTrue(BlazeFightRules.isThreat(10, true, true));
        assertTrue(BlazeFightRules.isThreat(20, true, true));
        assertFalse(BlazeFightRules.isThreat(21, true, true));
    }

    @Test
    public void reachabilityIsAboutHeightLavaAndDistance() {
        double range = BlazeFightRules.PICK_RANGE;
        assertTrue(BlazeFightRules.isReachable(1, false, 5, range, false));
        assertTrue(BlazeFightRules.isReachable(3.5, false, 5, range, false));
        assertFalse(BlazeFightRules.isReachable(3.6, false, 5, range, false));
        // the one we are already after is kept a bit higher
        assertTrue(BlazeFightRules.isReachable(4.5, false, 5, range, true));
        assertFalse(BlazeFightRules.isReachable(4.6, false, 5, range, true));
        assertFalse(BlazeFightRules.isReachable(Double.POSITIVE_INFINITY, false, 5, range, false));
        assertFalse(BlazeFightRules.isReachable(1, true, 5, range, false));
        assertTrue(BlazeFightRules.isReachable(1, false, 24, range, false));
        assertFalse(BlazeFightRules.isReachable(1, false, 24.1, range, false));
    }

    @Test
    public void disengageOnlyLooksInsideSixteen() {
        assertTrue(BlazeFightRules.isReachable(1, false, 16, BlazeFightRules.DISENGAGE_RANGE, false));
        assertFalse(BlazeFightRules.isReachable(1, false, 16.1, BlazeFightRules.DISENGAGE_RANGE, false));
    }

    @Test
    public void theTargetIsKeptWhileAliveTrackedAndNotOutOfReachForLong() {
        assertTrue(BlazeFightRules.keepTarget(true, true, 0));
        assertTrue(BlazeFightRules.keepTarget(true, true, BlazeFightRules.TARGET_GIVE_UP_TICKS - 1));
    }

    @Test
    public void theTargetIsDroppedWhenDeadUntrackedOrOutOfReachTooLong() {
        assertFalse(BlazeFightRules.keepTarget(false, true, 0));
        assertFalse(BlazeFightRules.keepTarget(true, false, 0));
        assertFalse(BlazeFightRules.keepTarget(true, true, BlazeFightRules.TARGET_GIVE_UP_TICKS));
    }

    @Test
    public void disengageStartsOnceForRodsDoneAndALookingBlaze() {
        assertTrue(BlazeFightRules.disengageWanted(true, true, false));
        assertFalse(BlazeFightRules.disengageWanted(false, true, false));
        assertFalse(BlazeFightRules.disengageWanted(true, false, false));
        // already ran for this batch: a blaze that follows us out does not start it again
        assertFalse(BlazeFightRules.disengageWanted(true, true, true));
    }

    @Test
    public void disengageEndsOnCalmOrOnTheCap() {
        assertFalse(BlazeFightRules.disengageOver(0, 10));
        assertFalse(BlazeFightRules.disengageOver(BlazeFightRules.DISENGAGE_CALM_TICKS - 1, 100));
        assertTrue(BlazeFightRules.disengageOver(BlazeFightRules.DISENGAGE_CALM_TICKS, 100));
        // still being looked at, but we have tried long enough
        assertFalse(BlazeFightRules.disengageOver(0, BlazeFightRules.DISENGAGE_CAP_TICKS - 1));
        assertTrue(BlazeFightRules.disengageOver(0, BlazeFightRules.DISENGAGE_CAP_TICKS));
    }

    @Test
    public void disengageCalmIsThreeSecondsAndTheCapIsFortyFive() {
        assertEquals(60, BlazeFightRules.DISENGAGE_CALM_TICKS);
        assertEquals(900, BlazeFightRules.DISENGAGE_CAP_TICKS);
    }

    @Test
    public void aBlazeWithALineInRangeIsWatchingWithoutHavingHitUs() {
        assertTrue(BlazeFightRules.watching(BlazeFightRules.SIGHT_RANGE, true));
        assertFalse(BlazeFightRules.watching(BlazeFightRules.SIGHT_RANGE + 0.1, true));
        assertFalse(BlazeFightRules.watching(5, false));
    }

    @Test
    public void aCombatRunThatEndedLatelyHasUsHealUntilEighteen() {
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 17.9f));
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 18));
        assertTrue(BlazeFightRules.healAfterRun(990 + BlazeFightRules.RUN_GIVE_UP_TICKS, 990, 10));
        assertFalse(BlazeFightRules.healAfterRun(991 + BlazeFightRules.RUN_GIVE_UP_TICKS, 990, 10));
        // never ran, or the clock went backwards: nothing to wait for
        assertFalse(BlazeFightRules.healAfterRun(1000, Long.MIN_VALUE / 2, 10));
        assertFalse(BlazeFightRules.healAfterRun(1000, 2000, 10));
    }

    @Test
    public void aBlazeInSwingingRangeKeepsTheKillAfterARun() {
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 16.9f, 1.5));
        assertFalse(BlazeFightRules.healAfterRun(1000, 990, 16.9f, BlazeFightRules.LATCH_SKIP_BLAZE_RANGE));
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 16.9f, BlazeFightRules.LATCH_SKIP_BLAZE_RANGE + 0.1));
        assertTrue(BlazeFightRules.healAfterRun(1000, 990, 16.9f, Double.POSITIVE_INFINITY));
    }

    @Test
    public void twoWitherSkeletonsAreEnoughForAZone() {
        java.util.List<double[]> two = java.util.List.of(new double[]{0, 0}, new double[]{4, 2});
        assertTrue(BlazeFightRules.packCenter(two) == null);
        double[] c = BlazeFightRules.packCenter(two, BlazeFightRules.ZONE_PACK_SIZE);
        assertEquals(2, c[0], 1e-9);
        assertEquals(1, c[1], 1e-9);
        assertTrue(BlazeFightRules.packCenter(java.util.List.of(new double[]{0, 0}), BlazeFightRules.ZONE_PACK_SIZE) == null);
    }

    @Test
    public void threeWitherSkeletonsMakeAPack() {
        assertFalse(BlazeFightRules.packNear(0));
        assertFalse(BlazeFightRules.packNear(2));
        assertTrue(BlazeFightRules.packNear(3));
        assertTrue(BlazeFightRules.packNear(5));
    }
}
