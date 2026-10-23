package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.GamerPhase;

// topping the water bucket back up when there is a pond right there. the mlg clutch spends it, and the next time it is
// needed (lava, a fall) is usually twenty minutes later and nowhere near water. pure, the chain hands in what it sees
public final class WaterUpkeep {

    private WaterUpkeep() {
    }

    // a pond further than this is a trip, and trips belong to the kit planner
    public static final double RANGE = 16;
    // one go at it, then the pond gets a rest. 15 s is a walk of 16 blocks plus the scoop with room to spare
    public static final int TIMEOUT_TICKS = 300;
    // after any failure, 60 s before the next go
    public static final int COOLDOWN_TICKS = 1200;
    // looking for the pond walks a cube of blocks, once in a while is plenty
    public static final int LOOK_TICKS = 40;

    // only inside a gamer run, and not where it juggles buckets on purpose: the portal cast (water and lava buckets in and
    // out), and the end where EndGear does its own water. outside a run (null) the user is driving and gets no surprise detours
    public static boolean phaseAllows(GamerPhase phase) {
        if (phase == null) {
            return false;
        }
        return switch (phase) {
            case PORTAL, END_PREP, DRAGON, DONE, STUCK -> false;
            default -> true;
        };
    }

    // calm = nothing else has the bot (no clutch, cleanup, swim up, meal, fire). room = the full bucket has a slot to land in
    public static boolean wanted(boolean emptyBucket, boolean waterBucket, boolean evaporates, boolean threat, boolean calm,
                                 boolean room, GamerPhase phase) {
        return emptyBucket && !waterBucket && !evaporates && !threat && calm && room && phaseAllows(phase);
    }

    public static boolean coolingDown(int now, int failedAt) {
        // the tick counter restarts on a reconnect, a stamp from before it is old news
        return now >= failedAt && now - failedAt < COOLDOWN_TICKS;
    }

    public static boolean lookedRecently(int now, int lookedAt) {
        return now >= lookedAt && now - lookedAt < LOOK_TICKS;
    }

    public static boolean timedOut(int now, int startedAt) {
        return now < startedAt || now - startedAt >= TIMEOUT_TICKS;
    }
}
