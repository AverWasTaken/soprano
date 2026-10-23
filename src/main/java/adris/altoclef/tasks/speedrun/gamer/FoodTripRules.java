package adris.altoclef.tasks.speedrun.gamer;

// the nether has no food of its own, so when the bag runs dry out there the way back is the portal we came by and the overworld's
// own food floor (FoodFloor). pure, the phase only feeds it numbers
public final class FoodTripRules {
    // under this many food units in the bag the nether run stops and goes home. FoodPlan's floor (24) in spirit: past it one fortress
    // fight eats the rest
    public static final int GO_HOME_UNITS = 24;
    // hunger under this means no natural regen (the game wants 18), so hp below it with nothing to eat stays hurt for good
    public static final int REGEN_HUNGER = 18;
    public static final double HURT_HP = 18;
    // trips per nether phase entry. a world with no food in reach would otherwise ping pong forever
    public static final int MAX_TRIPS = 2;
    // the overworld half gets this long to fill the bag, then we go back with what we have
    public static final double MAX_SECONDS = 480;

    public static boolean goHome(int foodUnits, double hp, int hunger, boolean healFood, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        if (!portalKnown || deathTripLive || tripsDone >= MAX_TRIPS) {
            return false;
        }
        if (foodUnits < GO_HOME_UNITS) {
            return true;
        }
        return hp < HURT_HP && hunger < REGEN_HUNGER && !healFood;
    }

    // the overworld half is over: the bag is back at the line, or the clock ran out
    public static boolean tripOver(boolean stillShort, double secondsOnTrip) {
        return !stillShort || secondsOnTrip >= MAX_SECONDS;
    }

    private FoodTripRules() {
    }
}
