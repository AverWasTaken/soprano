package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BlazeFightRules.Mode.CAMP;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.COVER;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.HUNT;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.KILL;
import static adris.altoclef.util.helpers.BlazeFightRules.Mode.RETREAT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the fortress run that nearly killed the bot: blazes over the lava, fireballs in the face, bot standing there camping
public class BlazeFightRulesTest {

    @Test
    public void quietRoomMeansWaitForTheSpawner() {
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0));
    }

    @Test
    public void blazesWeCannotHitButCanSeeUsMeanCoverNotCamping() {
        assertEquals(COVER, BlazeFightRules.decide(20, false, 3, 0));
    }

    @Test
    public void aReachableBlazeGetsHit() {
        assertEquals(KILL, BlazeFightRules.decide(20, false, 0, 1));
        assertEquals(KILL, BlazeFightRules.decide(20, false, 2, 1));
    }

    @Test
    public void lowHealthWithSomethingShootingMeansLeave() {
        assertEquals(RETREAT, BlazeFightRules.decide(12, false, 1, 0));
        assertEquals(RETREAT, BlazeFightRules.decide(12, false, 1, 1));
        assertEquals(KILL, BlazeFightRules.decide(13, false, 1, 1));
        assertEquals(COVER, BlazeFightRules.decide(13, false, 1, 0));
    }

    @Test
    public void lowHealthWithNothingLookingIsStillNotGoodEnough() {
        assertEquals(CAMP, BlazeFightRules.decide(12, false, 0, 0));
        assertEquals(RETREAT, BlazeFightRules.decide(8, false, 0, 0));
        assertEquals(RETREAT, BlazeFightRules.decide(8, false, 0, 2));
    }

    @Test
    public void onceLeftWeStayLeftUntilHealed() {
        assertEquals(RETREAT, BlazeFightRules.decide(13, true, 2, 1));
        assertEquals(RETREAT, BlazeFightRules.decide(13.9f, true, 0, 0));
        assertEquals(KILL, BlazeFightRules.decide(14, true, 0, 1));
        assertEquals(COVER, BlazeFightRules.decide(20, true, 2, 0));
        assertEquals(CAMP, BlazeFightRules.decide(20, true, 0, 0));
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
        assertTrue(BlazeFightRules.isReachable(1, false, 5, true));
        assertTrue(BlazeFightRules.isReachable(3.5, false, 5, true));
        assertFalse(BlazeFightRules.isReachable(3.6, false, 5, true));
        assertFalse(BlazeFightRules.isReachable(Double.POSITIVE_INFINITY, false, 5, true));
        assertFalse(BlazeFightRules.isReachable(1, true, 5, true));
        assertFalse(BlazeFightRules.isReachable(1, false, 33, true));
    }

    // the nether brick fence: low, dry, close, and the swing hits the fence
    @Test
    public void aBlazeBehindAFenceIsNotReachableAndSoNotKill() {
        boolean reachable = BlazeFightRules.isReachable(1, false, 3, false);
        assertFalse(reachable);
        int reachableCount = reachable ? 1 : 0;
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, reachableCount));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, reachableCount));
    }


    @Test
    public void coverHoldsUntilNoBlazeHasSeenUsForAWhile() {
        int hold = BlazeFightRules.COVER_EXIT_TICKS;
        assertEquals(COVER, BlazeFightRules.decide(20, false, false, 0, 0, 0, hold - 1, COVER, 500));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, 0, 0, 0, hold, COVER, 500));
        // hunting waits on the longer HIDE_HOLD_TICKS by itself, and COVER does not hold past either
        assertEquals(COVER, BlazeFightRules.decide(20, false, false, 0, 0, 1, hold - 1, COVER, 500));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, false, 0, 0, 1, hold, COVER, 500));
        assertEquals(HUNT, BlazeFightRules.decide(20, false, false, 0, 0, 1, BlazeFightRules.HIDE_HOLD_TICKS, COVER, 500));
        // entering cover is instant, kill preempts it on the old dwell, retreat preempts at once
        assertEquals(COVER, BlazeFightRules.decide(20, false, false, 1, 0, 0, 0, CAMP, 0));
        assertEquals(KILL, BlazeFightRules.decide(20, false, false, 1, 1, 0, 0, COVER, BlazeFightRules.MIN_DWELL_TICKS));
        assertEquals(RETREAT, BlazeFightRules.decide(5, false, false, 0, 0, 0, 0, COVER, 0));
        // the short overloads say Long.MAX_VALUE and never hold
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, COVER, 0));
    }
    @Test
    public void killAndCoverHoldForTheDwellBeforeFlipping() {
        int dwell = BlazeFightRules.MIN_DWELL_TICKS;
        assertEquals(KILL, BlazeFightRules.decide(20, false, 1, 0, KILL, 0));
        assertEquals(KILL, BlazeFightRules.decide(20, false, 1, 0, KILL, dwell - 1));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, 0, KILL, dwell));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, 0, COVER, 0));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, 1, COVER, dwell - 1));
        assertEquals(KILL, BlazeFightRules.decide(20, false, 1, 1, COVER, dwell));
    }

    @Test
    public void theDwellNeverHoldsBackRetreatOrCamp() {
        assertEquals(RETREAT, BlazeFightRules.decide(12, false, 1, 1, KILL, 0));
        assertEquals(RETREAT, BlazeFightRules.decide(8, false, 0, 0, COVER, 0));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, KILL, 0));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, COVER, 0));
        assertEquals(KILL, BlazeFightRules.decide(20, false, 0, 1, CAMP, 0));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 2, 0, CAMP, 0));
        // coming back from a retreat is the heal logic's call too, no timer on top
        assertEquals(KILL, BlazeFightRules.decide(14, true, 0, 1, RETREAT, 0));
        assertEquals(COVER, BlazeFightRules.decide(20, true, 2, 0, RETREAT, 0));
    }

    // hiding works too well: none of them can see us, so none of them come. go let one see us
    @Test
    public void hiddenBlazesGetVisitedOnceTheHoldIsUp() {
        int hold = BlazeFightRules.HIDE_HOLD_TICKS;
        assertEquals(HUNT, BlazeFightRules.decide(20, false, 0, 0, 3, hold));
        assertEquals(HUNT, BlazeFightRules.decide(20, false, 0, 0, 1, Long.MAX_VALUE));
        // just hid, give them a few seconds to come round the corner first
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, 3, hold - 1));
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, 3, 0));
        // nobody worth the walk
        assertEquals(CAMP, BlazeFightRules.decide(20, false, 0, 0, 0, Long.MAX_VALUE));
    }

    @Test
    public void everythingElseBeatsHunting() {
        long quiet = Long.MAX_VALUE;
        assertEquals(KILL, BlazeFightRules.decide(20, false, 0, 1, 3, quiet));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, 0, 3, quiet));
        assertEquals(RETREAT, BlazeFightRules.decide(8, false, 0, 0, 3, quiet));
        assertEquals(RETREAT, BlazeFightRules.decide(13, true, 0, 0, 3, quiet));
    }

    @Test
    public void theDwellNeverHoldsBackHunting() {
        long quiet = Long.MAX_VALUE;
        assertEquals(HUNT, BlazeFightRules.decide(20, false, 0, 0, 2, quiet, COVER, 0));
        assertEquals(HUNT, BlazeFightRules.decide(20, false, 0, 0, 2, quiet, KILL, 0));
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, 0, 2, quiet, HUNT, 0));
        assertEquals(KILL, BlazeFightRules.decide(20, false, 0, 1, 2, quiet, HUNT, 0));
        // the old KILL and COVER hold still applies with hunting in the mix
        assertEquals(KILL, BlazeFightRules.decide(20, false, 1, 0, 2, quiet, KILL, 0));
    }

    @Test
    public void huntableNeedsAFloorDryLandAndToStayNearTheSpawner() {
        double noSpawner = Double.NaN;
        assertTrue(BlazeFightRules.isHuntable(true, false, 25, false, noSpawner));
        assertTrue(BlazeFightRules.isHuntable(true, false, 25, false, BlazeFightRules.HUNT_SPAWNER_RADIUS));
        assertFalse(BlazeFightRules.isHuntable(true, false, 25, false, BlazeFightRules.HUNT_SPAWNER_RADIUS + 0.5));
        assertFalse(BlazeFightRules.isHuntable(false, false, 10, false, noSpawner));
        assertFalse(BlazeFightRules.isHuntable(false, true, 10, false, noSpawner));
        assertFalse(BlazeFightRules.isHuntable(true, false, 10, true, noSpawner));
        assertFalse(BlazeFightRules.isHuntable(true, false, BlazeFightRules.MAX_CHASE_DISTANCE + 1, false, noSpawner));
    }

    @Test
    public void theHuntGoalOnlyMovesForANewBlazeOrABigDrift() {
        assertFalse(BlazeFightRules.needsNewHuntGoal(false, 0));
        assertFalse(BlazeFightRules.needsNewHuntGoal(false, BlazeFightRules.HUNT_REGOAL_DRIFT));
        assertTrue(BlazeFightRules.needsNewHuntGoal(false, BlazeFightRules.HUNT_REGOAL_DRIFT + 0.5));
        assertTrue(BlazeFightRules.needsNewHuntGoal(true, 0));
    }

    // a blaze bobbing across 3.5 used to flip KILL and COVER about once a second
    @Test
    public void gettingInReachNeedsTheStrictRule() {
        assertTrue(BlazeFightRules.reachableNow(false, 3.5, false, 5, true, 0));
        assertFalse(BlazeFightRules.reachableNow(false, 3.6, false, 5, true, 0));
        assertFalse(BlazeFightRules.reachableNow(false, 1, false, 5, false, 1));
    }

    @Test
    public void onceInReachABobbingBlazeStaysInReach() {
        assertTrue(BlazeFightRules.reachableNow(true, 4, false, 5, true, 0));
        assertTrue(BlazeFightRules.reachableNow(true, BlazeFightRules.STAY_REACHABLE_HEIGHT, false, 5, true, 0));
        assertFalse(BlazeFightRules.reachableNow(true, BlazeFightRules.STAY_REACHABLE_HEIGHT + 0.1, false, 5, true, 0));
    }

    @Test
    public void oneBlockedRayIsAFencePostTwoIsAFence() {
        assertTrue(BlazeFightRules.reachableNow(true, 2, false, 5, false, 1));
        assertFalse(BlazeFightRules.reachableNow(true, 2, false, 5, false, BlazeFightRules.BLOCKED_CHECKS_TO_DROP));
        // and back in once the line clears, the streak resets with it
        assertTrue(BlazeFightRules.reachableNow(false, 2, false, 5, true, 0));
    }

    @Test
    public void lavaAndDistanceAreNeverSticky() {
        assertFalse(BlazeFightRules.reachableNow(true, 1, true, 5, true, 0));
        assertFalse(BlazeFightRules.reachableNow(true, 1, false, BlazeFightRules.MAX_CHASE_DISTANCE + 0.1, true, 0));
    }

    // what KILL goes after and what counts as reachable are the same question now
    @Test
    public void fightableIsReachableNotBannedAndNearTheSpawner() {
        double noSpawner = Double.NaN;
        assertTrue(BlazeFightRules.isFightable(true, false, noSpawner));
        assertTrue(BlazeFightRules.isFightable(true, false, BlazeFightRules.HUNT_SPAWNER_RADIUS));
        assertFalse(BlazeFightRules.isFightable(true, false, BlazeFightRules.HUNT_SPAWNER_RADIUS + 0.5));
        assertFalse(BlazeFightRules.isFightable(true, true, noSpawner));
        assertFalse(BlazeFightRules.isFightable(false, false, noSpawner));
        // a banned blaze in reach is not a reason to KILL with nothing to hit
        int reachable = BlazeFightRules.isFightable(true, true, 3) ? 1 : 0;
        assertEquals(COVER, BlazeFightRules.decide(20, false, 1, reachable));
    }

    // HUNT, it sees us, COVER for the hide hold, HUNT again: that lap must not restart the give up clock
    @Test
    public void aCoverBreakIsNotAFreshHunt() {
        assertFalse(BlazeFightRules.huntBreakIsFreshStart(BlazeFightRules.HIDE_HOLD_TICKS + 1));
        assertFalse(BlazeFightRules.huntBreakIsFreshStart(BlazeFightRules.HUNT_GIVE_UP_TICKS));
        assertTrue(BlazeFightRules.huntBreakIsFreshStart(BlazeFightRules.HUNT_GIVE_UP_TICKS + 1));
    }

    @Test
    public void aRetreatThatStoppedHealingGivesUp() {
        assertFalse(BlazeFightRules.retreatStalled(BlazeFightRules.RETREAT_GIVE_UP_TICKS - 1));
        assertTrue(BlazeFightRules.retreatStalled(BlazeFightRules.RETREAT_GIVE_UP_TICKS));
        long quiet = Long.MAX_VALUE;
        // still waiting for 14 without the give up
        assertEquals(RETREAT, BlazeFightRules.decide(13, true, false, 0, 0, 0, quiet));
        // with it, 13 hp and nothing looking is back to work
        assertEquals(CAMP, BlazeFightRules.decide(13, true, true, 0, 0, 0, quiet));
        assertEquals(KILL, BlazeFightRules.decide(13, true, true, 0, 1, 0, quiet));
        assertEquals(HUNT, BlazeFightRules.decide(13, true, true, 0, 0, 2, quiet));
        // the fresh hp rules still send us back
        assertEquals(RETREAT, BlazeFightRules.decide(12, true, true, 1, 0, 0, quiet));
        assertEquals(RETREAT, BlazeFightRules.decide(8, true, true, 0, 0, 0, quiet));
        assertEquals(RETREAT, BlazeFightRules.decide(8, true, true, 0, 0, 0, quiet, CAMP, 0));
        assertEquals(CAMP, BlazeFightRules.decide(13, true, true, 0, 0, 0, quiet, RETREAT, 0));
    }

    @Test
    public void theRetreatGiveUpLastsOnlyUntilWeAreOut() {
        assertTrue(BlazeFightRules.retreatGaveUpNext(false, true, false, true));
        assertTrue(BlazeFightRules.retreatGaveUpNext(true, true, false, false));
        assertFalse(BlazeFightRules.retreatGaveUpNext(false, true, false, false));
        // healing again or out of the retreat, the next one gets the normal wait
        assertFalse(BlazeFightRules.retreatGaveUpNext(true, true, true, false));
        assertFalse(BlazeFightRules.retreatGaveUpNext(true, false, false, false));
    }

    @Test
    public void aHuntThatGoesNowhereGetsDropped() {
        assertFalse(BlazeFightRules.huntTakingTooLong(0));
        assertFalse(BlazeFightRules.huntTakingTooLong(BlazeFightRules.HUNT_GIVE_UP_TICKS - 1));
        assertTrue(BlazeFightRules.huntTakingTooLong(BlazeFightRules.HUNT_GIVE_UP_TICKS));
    }

    @Test
    public void coverWithNoSpotOutHereWalksBackToTheSpawner() {
        // the spots are all by the spawner, so a healthy bot out past it goes back there instead of shielding in place
        assertEquals(BlazeFightRules.NoCover.BACK_TO_SPAWNER, BlazeFightRules.noCoverSpot(true, false, 20));
        assertEquals(BlazeFightRules.NoCover.BACK_TO_SPAWNER,
                BlazeFightRules.noCoverSpot(true, false, BlazeFightRules.RETREAT_HEALTH + 0.5f));
    }

    @Test
    public void coverWithNoSpotOutHereAndLowHpRetreats() {
        assertEquals(BlazeFightRules.NoCover.RETREAT, BlazeFightRules.noCoverSpot(true, false, BlazeFightRules.RETREAT_HEALTH));
    }

    @Test
    public void coverWithNoSpotByTheSpawnerOrNoSpawnerShieldsInPlace() {
        // by the spawner the walk is already done, standing there with no shield is worse. no spawner is nowhere to walk to,
        // and running off at full hp is not what the rods want
        assertEquals(BlazeFightRules.NoCover.SHIELD_HERE, BlazeFightRules.noCoverSpot(true, true, 20));
        assertEquals(BlazeFightRules.NoCover.SHIELD_HERE, BlazeFightRules.noCoverSpot(false, false, 20));
    }

    @Test
    public void coverOnlyHappensAboveTheNoCoverRetreatLine() {
        // decide never says COVER at a health noCoverSpot would retreat at, so out past the spawner the fallback is the walk
        for (float hp = 1; hp <= 20; hp += 0.5f) {
            if (BlazeFightRules.decide(hp, false, 2, 0) == COVER) {
                assertEquals("hp " + hp, BlazeFightRules.NoCover.BACK_TO_SPAWNER, BlazeFightRules.noCoverSpot(true, false, hp));
            }
        }
    }

    // burning with a looking blaze: leave at 15, stay left until 17, and the 2 hp gap means 14-15 does not flip
    @Test
    public void burningLeavesEarlierAndStaysLeftLonger() {
        assertEquals(RETREAT, BlazeFightRules.decide(15, false, false, true, 1, 0, 0, 0L));
        assertEquals(COVER, BlazeFightRules.decide(15.5f, false, false, true, 1, 0, 0, 0L));
        assertEquals(COVER, BlazeFightRules.decide(15, false, false, false, 1, 0, 0, 0L));
        assertEquals(RETREAT, BlazeFightRules.decide(16.5f, true, false, true, 1, 0, 0, 0L));
        assertEquals(COVER, BlazeFightRules.decide(17, true, false, true, 1, 0, 0, 0L));
        // fire out while retreating: the normal exit line takes over
        assertEquals(COVER, BlazeFightRules.decide(14, true, false, false, 1, 0, 0, 0L));
        assertEquals(RETREAT, BlazeFightRules.decide(13.9f, true, false, false, 1, 0, 0, 0L));
    }

    @Test
    public void burningWithNothingLookingDoesNotLeaveEarly() {
        assertEquals(CAMP, BlazeFightRules.decide(15, false, false, true, 0, 0, 0, 0L));
    }

    @Test
    public void burningNoFlipBetweenFourteenAndFifteen() {
        BlazeFightRules.Mode mode = RETREAT;
        for (float hp : new float[]{14, 15, 14, 15, 16, 14.5f}) {
            mode = BlazeFightRules.decide(hp, mode == RETREAT, false, true, 1, 0, 0, 0L);
            assertEquals(RETREAT, mode);
        }
    }

    @Test
    public void notBurningIsUnchanged() {
        assertEquals(BlazeFightRules.decide(12, false, 1, 0), BlazeFightRules.decide(12, false, false, false, 1, 0, 0, 0L));
        assertEquals(BlazeFightRules.decide(14, true, 0, 1), BlazeFightRules.decide(14, true, false, false, 0, 1, 0, 0L));
    }

    @Test
    public void noCoverSpotUsesTheBurningLine() {
        assertEquals(BlazeFightRules.NoCover.BACK_TO_SPAWNER, BlazeFightRules.noCoverSpot(true, false, 14, false));
        assertEquals(BlazeFightRules.NoCover.RETREAT, BlazeFightRules.noCoverSpot(true, false, 14, true));
        assertEquals(BlazeFightRules.NoCover.RETREAT, BlazeFightRules.noCoverSpot(true, false, 15, true));
        assertEquals(BlazeFightRules.NoCover.BACK_TO_SPAWNER, BlazeFightRules.noCoverSpot(true, false, 16, true));
        assertEquals(BlazeFightRules.NoCover.RETREAT, BlazeFightRules.noCoverSpot(true, false, 12));
    }

    @Test
    public void heldSpotToleratesOneShooterPeekingIn() {
        assertTrue(BlazeFightRules.heldSpotHolds(0, 3));
        assertTrue(BlazeFightRules.heldSpotHolds(1, 3));
        assertFalse(BlazeFightRules.heldSpotHolds(2, 3));
        assertTrue(BlazeFightRules.heldSpotHolds(0, 1));
        // a lone shooter that can see the spot means the spot hides nothing
        assertFalse(BlazeFightRules.heldSpotHolds(1, 1));
    }

    @Test
    public void heldSpotDropsOnlyAfterTwoMissesInARow() {
        assertFalse(BlazeFightRules.heldSpotGivenUp(1));
        assertTrue(BlazeFightRules.heldSpotGivenUp(2));
        assertTrue(BlazeFightRules.emptySearchKeeps(2));
        assertFalse(BlazeFightRules.emptySearchKeeps(BlazeFightRules.HELD_SPOT_MISSES_TO_DROP + BlazeFightRules.HELD_SPOT_EMPTY_KEEP_PICKS));
    }

    @Test
    public void nearHeldSpotIsNeverTradedForAFartherOne() {
        assertFalse(BlazeFightRules.swapAllowed(1.5, 5, true));
        assertFalse(BlazeFightRules.swapAllowed(2, 2.5, true));
        assertTrue(BlazeFightRules.swapAllowed(1.5, 1, true));
        assertTrue(BlazeFightRules.swapAllowed(4, 6, true));
        assertTrue(BlazeFightRules.swapAllowed(2.5, 5, true));
    }

    // standing on a spot that hides us from nobody is not worth keeping, however close the better one is not
    @Test
    public void nearHeldSpotThatHidesNothingIsSwapped() {
        assertTrue(BlazeFightRules.swapAllowed(0.5, 3, false));
    }

    @Test
    public void shortCoverWalkUnderFireHoldsTheBite() {
        assertTrue(BlazeFightRules.holdBiteForCover(true, true, 3, false));
        assertTrue(BlazeFightRules.holdBiteForCover(true, true, 4, false));
        assertFalse(BlazeFightRules.holdBiteForCover(true, true, 5, false));
        assertFalse(BlazeFightRules.holdBiteForCover(true, true, 3, true));
        assertFalse(BlazeFightRules.holdBiteForCover(true, false, 3, false));
        assertFalse(BlazeFightRules.holdBiteForCover(false, true, 3, false));
    }

    @Test
    public void coverHookHoldsFreshStampsOnly() {
        try {
            BlazeCoverHook.clear();
            assertFalse(BlazeCoverHook.holdsBite(100));
            BlazeCoverHook.set(100, 2, false, true);
            assertTrue(BlazeCoverHook.holdsBite(100));
            assertTrue(BlazeCoverHook.holdsBite(100 + BlazeCoverHook.STALE_TICKS));
            assertFalse(BlazeCoverHook.holdsBite(101 + BlazeCoverHook.STALE_TICKS));
            // game time going backwards is a world change, not a walk
            assertFalse(BlazeCoverHook.holdsBite(50));
            BlazeCoverHook.set(100, 2, true, true);
            assertFalse(BlazeCoverHook.holdsBite(100));
        } finally {
            BlazeCoverHook.clear();
        }
    }
}
