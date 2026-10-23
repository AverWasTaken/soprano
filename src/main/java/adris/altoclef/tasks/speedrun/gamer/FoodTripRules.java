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

    // why a trip started. each reason has its own trip counter, so a blocks trip that came back empty handed does not eat the food
    // budget and the other way round
    public enum Reason {
        FOOD, BLOCKS
    }

    // under this many throwaways the next bridge is a coin flip (NetherRestock.LOW), and a dig that found nothing twice running
    // is not about to
    public static final int BLOCKS_LOW = 8;
    public static final int BLOCKS_EXHAUSTED_ENDS = 2;
    // one need gets MAX_SECONDS, food and blocks together get this
    public static final double TWO_NEED_SECONDS = 600;

    // the restock dig ran dry (nothing left to mine in its band) twice with a cooldown between, and the bag is still empty. the
    // dig would have been the cheap way out, so this is the lava pool case: the bridges ate the stack and the band holds nothing
    public static boolean goHomeForBlocks(int held, int exhaustedEnds, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        return portalKnown && !deathTripLive && tripsDone < MAX_TRIPS && held < BLOCKS_LOW && exhaustedEnds >= BLOCKS_EXHAUSTED_ENDS;
    }

    // the overworld half's clock for the needs that were short when we got up there
    public static double seconds(boolean food, boolean blocks) {
        return food && blocks ? TWO_NEED_SECONDS : MAX_SECONDS;
    }

    // the same end check with the trip's own clock. stillShort = any need left (food under the minimum, blocks under the entry stack)
    public static boolean tripOver(boolean stillShort, double secondsOnTrip, double limit) {
        return !stillShort || secondsOnTrip >= limit;
    }

    private FoodTripRules() {
    }
}
