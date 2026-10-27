package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoodTripRulesTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void lowFoodGoesHome() {
        assertTrue(FoodTripRules.goHome(0, 0, true, false, 0));
        assertTrue(FoodTripRules.goHome(FoodTripRules.GO_HOME_BASE - 1, 0, true, false, 0));
        assertFalse(FoodTripRules.goHome(FoodTripRules.GO_HOME_BASE, 0, true, false, 0));
    }


    @Test
    public void aTripRunsToItsEndNotTheMinimum() {
        // 70 is the overworld minimum, 130 the trip's stop: the bag at 70 is fine for the floor and short for a trip
        assertTrue(FoodTripRules.tripFoodShort(70, 130));
        assertTrue(FoodTripRules.tripFoodShort(129, 130));
        assertFalse(FoodTripRules.tripFoodShort(130, 130));
        // a full bag settles for the minimum (tripEnd falls back to it)
        assertFalse(FoodTripRules.tripFoodShort(70, 70));
    }

    @Test
    public void aTripThatJustEndedHoldsTheNextOneOffUnlessHungry() {
        long end = 5000;
        assertTrue(FoodTripRules.coolingDown(end + 1, end));
        assertTrue(FoodTripRules.coolingDown(end + FoodTripRules.COOLDOWN_TICKS - 1, end));
        assertFalse(FoodTripRules.coolingDown(end + FoodTripRules.COOLDOWN_TICKS, end));
        assertFalse(FoodTripRules.coolingDown(end + 1, -1));
    }

    @Test
    public void theLinesMoveOutWithTheWalkHome() {
        assertEquals(36, FoodTripRules.goHomeLine(0), 0);
        assertEquals(46, FoodTripRules.goHomeLine(400), 0);
        assertEquals(12, FoodTripRules.floorLine(0), 0);
        assertEquals(22, FoodTripRules.floorLine(400), 0);
        // 40 units is fine at the portal and due 400 blocks out
        assertFalse(FoodTripRules.goHome(40, 0, true, false, 0));
        assertTrue(FoodTripRules.goHome(40, 400, true, false, 0));
        assertFalse(FoodTripRules.goHome(46, 400, true, false, 0));
        assertTrue(FoodTripRules.underFloor(21, 400));
        assertFalse(FoodTripRules.underFloor(22, 400));
        assertTrue(FoodTripRules.underFloor(11, 0));
        assertFalse(FoodTripRules.underFloor(12, 0));
    }

    @Test
    public void unsafeIsABlazeFireOrLowHp() {
        assertFalse(FoodTripRules.unsafe(false, false, 20));
        assertFalse(FoodTripRules.unsafe(false, false, FoodTripRules.CALM_HEALTH));
        assertTrue(FoodTripRules.unsafe(true, false, 20));
        assertTrue(FoodTripRules.unsafe(false, true, 20));
        assertTrue(FoodTripRules.unsafe(false, false, FoodTripRules.CALM_HEALTH - 0.5f));
    }

    @Test
    public void aDueTripWaitsForSixtyCalmTicksUnlessUnderTheFloor() {
        long now = 5000;
        assertTrue("never unsafe", FoodTripRules.mayStart(false, now, -1));
        assertFalse("unsafe this tick", FoodTripRules.mayStart(false, now, now));
        assertFalse(FoodTripRules.mayStart(false, now, now - (FoodTripRules.CALM_TICKS - 1)));
        assertTrue(FoodTripRules.mayStart(false, now, now - FoodTripRules.CALM_TICKS));
        assertTrue("under the floor it goes anyway", FoodTripRules.mayStart(true, now, now));
        assertTrue("a clock that jumped back is not a reason to wait", FoodTripRules.mayStart(false, now, now + 5000));
    }

    @Test
    public void theCountIsHeldWithoutWhatIsStillInASmoker() {
        // 108 in the bag as cooked value, 3 raw porkchops that nothing out here will cook, and 40 in an overworld smoker
        FakeFacts f = new FakeFacts();
        f.foodUnits = 108;
        f.give(Items.PORKCHOP, 3);
        f.cookingFood("cooked_porkchop", 5, 8, 20);
        FoodPlan plan = FoodPlan.of(f, new adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig(),
                new adris.altoclef.tasks.speedrun.gamer.config.EndConfig(), false);
        int gap = CookGate.rawGap(f);
        assertTrue(gap > 0);
        assertEquals(108 - gap, FoodTripRules.units(plan));
    }
    @Test
    public void noPortalStays() {
        assertFalse(FoodTripRules.goHome(0, 0, false, false, 0));
    }

    @Test
    public void deathTripStays() {
        assertFalse(FoodTripRules.goHome(0, 0, true, true, 0));
    }

    @Test
    public void spentTripsStay() {
        assertTrue(FoodTripRules.goHome(0, 0, true, false, FoodTripRules.MAX_TRIPS - 1));
        assertFalse(FoodTripRules.goHome(0, 0, true, false, FoodTripRules.MAX_TRIPS));
    }

    @Test
    public void tripEndsOnFullBagOrClock() {
        assertTrue(FoodTripRules.tripOver(false, 5));
        assertFalse(FoodTripRules.tripOver(true, 5));
        assertTrue(FoodTripRules.tripOver(true, FoodTripRules.MAX_SECONDS));
    }

    @Test
    public void emptyBagAndTwoDryDigsGoesHomeForBlocks() {
        assertTrue(FoodTripRules.goHomeForBlocks(0, 2, true, false, 0));
        assertTrue(FoodTripRules.goHomeForBlocks(FoodTripRules.BLOCKS_LOW - 1, 3, true, false, 0));
        assertFalse("enough to bridge with", FoodTripRules.goHomeForBlocks(FoodTripRules.BLOCKS_LOW, 2, true, false, 0));
        assertFalse("one dry dig could still be a bad corner", FoodTripRules.goHomeForBlocks(0, 1, true, false, 0));
        assertFalse(FoodTripRules.goHomeForBlocks(0, 2, false, false, 0));
        assertFalse(FoodTripRules.goHomeForBlocks(0, 2, true, true, 0));
    }

    @Test
    public void blockAndFoodTripsHaveSeparateBudgets() {
        // food spent, blocks fresh: the blocks trip still goes, and the other way round
        assertFalse(FoodTripRules.goHome(0, 0, true, false, FoodTripRules.MAX_TRIPS));
        assertTrue(FoodTripRules.goHomeForBlocks(0, 2, true, false, 0));
        assertFalse(FoodTripRules.goHomeForBlocks(0, 2, true, false, FoodTripRules.MAX_TRIPS));
        assertTrue(FoodTripRules.goHome(0, 0, true, false, 0));
    }

    @Test
    public void twoNeedTripGetsTheLongerClock() {
        assertEquals(FoodTripRules.MAX_SECONDS, FoodTripRules.seconds(true, false), 0);
        assertEquals(FoodTripRules.MAX_SECONDS, FoodTripRules.seconds(false, true), 0);
        assertEquals(FoodTripRules.TWO_NEED_SECONDS, FoodTripRules.seconds(true, true), 0);
        // over when nothing is short, or when the clock of that trip runs out (480 is not the end of a two need trip)
        assertTrue(FoodTripRules.tripOver(false, 5, FoodTripRules.TWO_NEED_SECONDS));
        assertFalse(FoodTripRules.tripOver(true, FoodTripRules.MAX_SECONDS, FoodTripRules.TWO_NEED_SECONDS));
        assertTrue(FoodTripRules.tripOver(true, FoodTripRules.TWO_NEED_SECONDS, FoodTripRules.TWO_NEED_SECONDS));
    }

    @Test
    public void runReserveNeverDropsUnderTheEntryStack() {
        assertEquals(128, BuildBlocks.reserveFor(64, 128));
        assertEquals(200, BuildBlocks.reserveFor(200, 128));
    }

    // ---- the walk home: stall, cap, restock, counters

    private static final long SEC = 20;

    @Test
    public void walkKeepsGoingWhileItClosesInAndStallsAfterNinetySeconds() {
        FoodTripRules.Mark m = FoodTripRules.advance(null, 400, 0, 0, false);
        long t = 0;
        double dist = 400;
        // 1 block a second: 8 blocks closer every 8 s, never stalled
        for (int s = 1; s <= 300; s++) {
            t = s * SEC;
            dist = 400 - s;
            m = FoodTripRules.advance(m, dist, t - 1, t, false);
            assertFalse("s=" + s, FoodTripRules.stalled(m, t));
        }
        // stops dead: stalled exactly 90 s after the last mark
        long markTick = m.tick();
        for (long k = 1; k <= 90 * SEC; k++) {
            m = FoodTripRules.advance(m, dist, t + k - 1, t + k, false);
        }
        assertTrue(FoodTripRules.stalled(m, markTick + 90 * SEC));
        assertFalse(FoodTripRules.stalled(m, markTick + 90 * SEC - 1));
    }

    @Test
    public void aGapInTicksRestartsTheMarkAndTheDigPushesItForward() {
        FoodTripRules.Mark m = FoodTripRules.advance(null, 100, 0, 0, false);
        // a fight had the wheel for 200 ticks: the clock starts over
        m = FoodTripRules.advance(m, 100, 0, 200, false);
        assertEquals(200, m.tick());
        // digging every tick for 60 s moves the mark with it
        for (long t = 201; t <= 200 + 60 * SEC; t++) {
            m = FoodTripRules.advance(m, 100, t - 1, t, true);
        }
        assertEquals(200 + 60 * SEC, m.tick());
        assertFalse(FoodTripRules.stalled(m, 200 + 60 * SEC + 80 * SEC));
        assertTrue(FoodTripRules.stalled(m, 200 + 60 * SEC + 90 * SEC));
    }

    @Test
    public void hardCapEndsAWalkThatIsStillMoving() {
        assertFalse(FoodTripRules.walkOver(false, FoodTripRules.HOME_HARD_SECONDS - 1));
        assertTrue(FoodTripRules.walkOver(false, FoodTripRules.HOME_HARD_SECONDS));
        // the old 480 s clock no longer ends it
        assertFalse(FoodTripRules.walkOver(false, FoodTripRules.MAX_SECONDS + 1));
        assertTrue(FoodTripRules.walkOver(true, 5));
        assertEquals(900, FoodTripRules.HOME_HARD_SECONDS, 0);
    }

    @Test
    public void restockIsDueTwentySecondsIntoAStallWithAnEmptyBagOncePerStall() {
        FoodTripRules.Mark m = new FoodTripRules.Mark(100, 1000, false);
        assertFalse(FoodTripRules.restockDue(m, 1000 + 20 * SEC - 1, 0));
        assertTrue(FoodTripRules.restockDue(m, 1000 + 20 * SEC, 15));
        // enough blocks already
        assertFalse(FoodTripRules.restockDue(m, 1000 + 20 * SEC, 16));
        // asked once this stall
        assertFalse(FoodTripRules.restockDue(new FoodTripRules.Mark(100, 1000, true), 5000, 0));
        // progress starts a fresh mark that may ask again
        FoodTripRules.Mark moved = FoodTripRules.advance(new FoodTripRules.Mark(100, 1000, true), 90, 1999, 2000, false);
        assertFalse(moved.asked());
        assertFalse(FoodTripRules.restockDue(null, 5000, 0));
    }

    @Test
    public void hungryDigIsShorter() {
        assertEquals(32, FoodTripRules.restockStopAt(false));
        assertEquals(16, FoodTripRules.restockStopAt(true));
    }

    // a trip budget belongs to one visit of the nether phase: a retry (attempt 2+) keeps it, only a fresh entry refills it.
    // nothing about the dimension is an input, so the trip coming back down to the nether cannot reset it. the only callers
    // of NetherPhase.onEnter are PhaseMachine (begin, retry, moveTo) and the planner == null fallback in NetherPhase.tick, and
    // none of them runs on a dimension change. a death trip stays in the phase (counters kept), a regress that sends the run
    // to GATHER/IRON comes back through moveTo with attempt 1, which is a new visit and refills the budget
    @Test
    public void counterResetOnlyOnAFreshEntry() {
        assertTrue(FoodTripRules.countersReset(1));
        assertFalse(FoodTripRules.countersReset(2));
        assertFalse(FoodTripRules.countersReset(3));
        // one trip used, the phase retried: still one used, so a second is allowed and a third is not
        int foodTrips = 1;
        if (FoodTripRules.countersReset(2)) {
            foodTrips = 0;
        }
        assertEquals(1, foodTrips);
        assertTrue(FoodTripRules.goHome(0, 0, true, false, foodTrips));
        assertFalse(FoodTripRules.goHome(0, 0, true, false, foodTrips + 1));
    }

    @Test
    public void aRetryInTheOverworldResumesTheTripWhileTheBagIsShort() {
        assertTrue(FoodTripRules.resume(true, true, false, true));
        assertFalse(FoodTripRules.resume(false, true, false, true));
        assertFalse(FoodTripRules.resume(true, false, false, true));
        // bag is back at the line: the trip is done, nothing to resume
        assertFalse(FoodTripRules.resume(true, true, false, false));
        assertFalse(FoodTripRules.resume(true, true, true, true));
    }

    @Test
    public void aStalledWalkCoolsDownForAMinute() {
        assertFalse(FoodTripRules.coolingDown(5000, -1));
        assertTrue(FoodTripRules.coolingDown(5000, 5000));
        assertTrue(FoodTripRules.coolingDown(5000 + FoodTripRules.COOLDOWN_TICKS - 1, 5000));
        assertFalse(FoodTripRules.coolingDown(5000 + FoodTripRules.COOLDOWN_TICKS, 5000));
        // clock went backwards = restart
        assertFalse(FoodTripRules.coolingDown(100, 5000));
    }

    @Test
    public void gainedOnlyOnARiseAfterAFirstLook() {
        assertFalse(FoodTripRules.gained(-1, 10));
        assertFalse(FoodTripRules.gained(10, 10));
        assertFalse(FoodTripRules.gained(10, 8));
        assertTrue(FoodTripRules.gained(10, 12));
    }

    @Test
    public void theRestockWaitsOutCampingAndBlazeFights() {
        assertTrue(FoodTripRules.restockAllowed(false, false, false));
        assertTrue(FoodTripRules.restockAllowed(false, false, true));
        assertTrue(FoodTripRules.restockAllowed(false, true, false));
        assertFalse(FoodTripRules.restockAllowed(true, false, false));
        assertFalse(FoodTripRules.restockAllowed(false, true, true));
    }

    @Test
    public void aFreshRestockHasNoDryEndsSoNoTripHome() {
        assertFalse(FoodTripRules.goHomeForBlocks(0, new adris.altoclef.tasks.speedrun.gamer.tasks.NetherBlockRestock().exhaustedEnds(),
                true, false, 0));
        assertTrue(FoodTripRules.goHomeForBlocks(0, FoodTripRules.BLOCKS_EXHAUSTED_ENDS, true, false, 0));
    }

    @Test
    public void aFoodTripThatBroughtFoodDoesNotUseUpTheCap() {
        assertTrue(FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 4, 4 + FoodTripRules.TRIP_GAIN_UNITS));
        assertTrue(FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 0, 70));
        assertFalse(FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 4, 4 + FoodTripRules.TRIP_GAIN_UNITS - 1));
        // came back with less than it left with (eaten on the way) or the same
        assertFalse(FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 30, 30));
        assertFalse(FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 30, 10));
        // a blocks trip is paid for in blocks
        assertFalse(FoodTripRules.refundsTrip(FoodTripRules.Reason.BLOCKS, 0, 70));
    }

    @Test
    public void tripsThatKeepGainingStayUnderTheCapAndEmptyOnesHitIt() {
        int used = 0;
        for (int trip = 0; trip < 5; trip++) {
            assertTrue(FoodTripRules.goHome(5, 0, true, false, used));
            used++;
            if (FoodTripRules.refundsTrip(FoodTripRules.Reason.FOOD, 5, 60)) {
                used--;
            }
        }
        assertEquals(0, used);
        for (int trip = 0; trip < FoodTripRules.MAX_TRIPS; trip++) {
            assertTrue(FoodTripRules.goHome(5, 0, true, false, trip));
        }
        assertFalse(FoodTripRules.goHome(5, 0, true, false, FoodTripRules.MAX_TRIPS));
    }
}
