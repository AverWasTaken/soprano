package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

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
}
