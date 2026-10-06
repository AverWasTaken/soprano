package adris.altoclef.tasks.construction;

// vanilla digs 5x slower with your eyes in water and another 5x when you're not on the ground, so swinging at
// something while swimming is up to 25x of a bad time. wait until we're standing on something, unless waiting
// is the worse idea. pulled out of DestroyBlockTask so the decision can be tested without a world
public final class WaterBreakGuard {

    // 5 seconds of wanting to break something while soaked and we stop being picky
    public static final int GIVE_UP_TICKS = 100;
    // below this much of the air bar the way out of the water is probably through the block, or at least not away from it
    public static final double LOW_AIR_FRACTION = 1.0 / 3.0;

    private WaterBreakGuard() {
    }

    // slowedByWater: feet in water and either eyes under it or off the ground. wading with your head dry is not slowed down
    // ticksWet: ticks we have been slowed while the block was in reach. airFraction: air supply over max, 1 when breathing fine
    public static boolean mayBreak(boolean onGround, boolean slowedByWater, boolean instantBreak, int ticksWet, double airFraction, boolean hasLandSpot) {
        if (!slowedByWater) {
            // dry (or wading). in the air, like jumping, was never ok and still isn't
            return onGround;
        }
        // soft things don't care what the water does to the speed
        if (instantBreak) {
            return true;
        }
        // we could be here forever if the land spot can't be reached, or the current keeps carrying us off it
        if (ticksWet >= GIVE_UP_TICKS) {
            return true;
        }
        // drowning is worse than slow, and the swim-up is WorldSurvivalChain's job, not ours to fight
        if (airFraction < LOW_AIR_FRACTION) {
            return true;
        }
        // nowhere dry to stand near it. mining it slowly beats not mining it
        return !hasLandSpot;
    }
}
