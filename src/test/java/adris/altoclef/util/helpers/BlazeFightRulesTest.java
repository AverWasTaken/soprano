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

    @Test
    public void aHuntThatGoesNowhereGetsDropped() {
        assertFalse(BlazeFightRules.huntTakingTooLong(0));
        assertFalse(BlazeFightRules.huntTakingTooLong(BlazeFightRules.HUNT_GIVE_UP_TICKS - 1));
        assertTrue(BlazeFightRules.huntTakingTooLong(BlazeFightRules.HUNT_GIVE_UP_TICKS));
    }
}
