package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoodTripRulesTest {
    @Test
    public void lowFoodGoesHome() {
        assertTrue(FoodTripRules.goHome(0, true, false, 0));
        assertTrue(FoodTripRules.goHome(FoodTripRules.GO_HOME_UNITS - 1, true, false, 0));
        assertFalse(FoodTripRules.goHome(FoodTripRules.GO_HOME_UNITS, true, false, 0));
    }

    @Test
    public void noPortalStays() {
        assertFalse(FoodTripRules.goHome(0, false, false, 0));
    }

    @Test
    public void deathTripStays() {
        assertFalse(FoodTripRules.goHome(0, true, true, 0));
    }

    @Test
    public void spentTripsStay() {
        assertTrue(FoodTripRules.goHome(0, true, false, FoodTripRules.MAX_TRIPS - 1));
        assertFalse(FoodTripRules.goHome(0, true, false, FoodTripRules.MAX_TRIPS));
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
        assertFalse(FoodTripRules.goHome(0, true, false, FoodTripRules.MAX_TRIPS));
        assertTrue(FoodTripRules.goHomeForBlocks(0, 2, true, false, 0));
        assertFalse(FoodTripRules.goHomeForBlocks(0, 2, true, false, FoodTripRules.MAX_TRIPS));
        assertTrue(FoodTripRules.goHome(0, true, false, 0));
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
