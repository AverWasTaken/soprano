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
}
