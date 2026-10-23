package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoodTripRulesTest {
    @Test
    public void lowFoodGoesHome() {
        assertTrue(FoodTripRules.goHome(10, 20, 20, true, true, false, 0));
        assertFalse(FoodTripRules.goHome(40, 20, 20, true, true, false, 0));
    }

    @Test
    public void noFoodAndNoRegenGoesHome() {
        assertTrue(FoodTripRules.goHome(0, 7.3, 17, false, true, false, 0));
    }

    @Test
    public void hurtButCanStillEatStays() {
        assertFalse(FoodTripRules.goHome(40, 7, 10, true, true, false, 0));
    }

    @Test
    public void noPortalOrDeathTripOrSpentTripsStays() {
        assertFalse(FoodTripRules.goHome(0, 7, 3, false, false, false, 0));
        assertFalse(FoodTripRules.goHome(0, 7, 3, false, true, true, 0));
        assertFalse(FoodTripRules.goHome(0, 7, 3, false, true, false, FoodTripRules.MAX_TRIPS));
    }

    @Test
    public void tripEndsOnFullBagOrClock() {
        assertTrue(FoodTripRules.tripOver(false, 5));
        assertFalse(FoodTripRules.tripOver(true, 5));
        assertTrue(FoodTripRules.tripOver(true, FoodTripRules.MAX_SECONDS));
    }
}
