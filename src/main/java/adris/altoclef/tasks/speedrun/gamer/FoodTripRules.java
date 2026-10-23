package adris.altoclef.tasks.speedrun.gamer;

// the nether has no food of its own, so when the bag runs dry out there the way back is the portal we came by and the overworld's
// own food floor (FoodFloor). pure, the phase only feeds it numbers
public final class FoodTripRules {
    // under this many food units in the bag the nether run stops and goes home. FoodPlan's floor (24) in spirit: past it one fortress
    // fight eats the rest
    public static final int GO_HOME_UNITS = 24;
    // trips per nether phase entry. a world with no food in reach would otherwise ping pong forever
    public static final int MAX_TRIPS = 2;
    // each half (the walk home, the hunt up top) gets this long, then we carry on with what we have
    public static final double MAX_SECONDS = 480;

    // no food in the bag at all is just the same line: nothing to eat means no regen once hunger drops, and the floor is far over that
    public static boolean goHome(int foodUnits, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        return portalKnown && !deathTripLive && tripsDone < MAX_TRIPS && foodUnits < GO_HOME_UNITS;
    }

    // the overworld half is over: the bag is back at the line, or the clock ran out
    public static boolean tripOver(boolean stillShort, double secondsOnTrip) {
        return !stillShort || secondsOnTrip >= MAX_SECONDS;
    }

    private FoodTripRules() {
    }
}
