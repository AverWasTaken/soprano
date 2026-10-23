package adris.altoclef.tasks.speedrun.gamer;

// the nether has no food of its own, so when the bag runs dry out there the way back is the portal we came by and the overworld's
// own food floor (FoodFloor). pure, the phase only feeds it numbers
public final class FoodTripRules {
    // the line moves out with the walk home: a trip from 400 blocks away eats food on the way, so it has to start with more in the bag.
    // 36 at the portal (a fortress fight burns well over the old 24 just healing), plus a unit per 40 blocks of walk
    public static final int GO_HOME_BASE = 36;
    // under this the bag is nearly empty and the trip goes whatever is going on around us. same slope as the line
    public static final int FLOOR_BASE = 12;
    public static final double BLOCKS_PER_UNIT = 40;
    // nothing hostile in the way (a blaze that sees us, fire, low hp) for this long before the trip starts, a walk out of cover into
    // a fireball is how a trip kills. 3 s
    public static final int CALM_TICKS = 60;
    // below this hp the trip waits for the regen, same as burning or a blaze looking at us
    public static final float CALM_HEALTH = 14;
    // trips per nether phase entry. a world with no food in reach would otherwise ping pong forever
    public static final int MAX_TRIPS = 2;
    // each half (the walk home, the hunt up top) gets this long, then we carry on with what we have
    public static final double MAX_SECONDS = 480;

    public static double goHomeLine(double distToPortal) {
        return GO_HOME_BASE + distToPortal / BLOCKS_PER_UNIT;
    }

    public static double floorLine(double distToPortal) {
        return FLOOR_BASE + distToPortal / BLOCKS_PER_UNIT;
    }

    // the one count the line is read against: FoodPlan's held with nothing pending. out here raw meat is raw (FoodPlan already
    // knows nothing cooks past PORTAL) and a smoker still cooking up top is not food we can reach
    public static int units(FoodPlan food) {
        return food.held() - food.pending();
    }

    // no food in the bag at all is just the same line: nothing to eat means no regen once hunger drops, and the floor is far over that
    public static boolean goHome(double foodUnits, double distToPortal, boolean portalKnown, boolean deathTripLive, int tripsDone) {
        return portalKnown && !deathTripLive && tripsDone < MAX_TRIPS && foodUnits < goHomeLine(distToPortal);
    }

    public static boolean underFloor(double foodUnits, double distToPortal) {
        return foodUnits < floorLine(distToPortal);
    }

    // something is trying to hurt us right now. pure so the wait below can be tested
    public static boolean unsafe(boolean blazeSeesUs, boolean onFire, float health) {
        return blazeSeesUs || onFire || health < CALM_HEALTH;
    }

    // the line is crossed, may the trip start. under the floor it goes anyway, else it waits until nothing unsafe has held for
    // CALM_TICKS. lastUnsafe < 0 = never seen, a clock that went backwards is a restart (same as the other timers)
    public static boolean mayStart(boolean underFloor, long now, long lastUnsafe) {
        return underFloor || lastUnsafe < 0 || now < lastUnsafe || now - lastUnsafe >= CALM_TICKS;
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
