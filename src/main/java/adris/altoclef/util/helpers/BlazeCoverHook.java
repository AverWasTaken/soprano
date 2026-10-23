package adris.altoclef.util.helpers;

// the one fact the food chain needs from the blaze cover walk: is one going on, how far is the spot, are we getting anywhere.
// same shape as NightSleepHook, the cover task writes it every tick it is walking and clears it when it arrives or stops
public final class BlazeCoverHook {
    // a stamp nobody refreshed in this many game ticks is a walk that ended without saying so, not a walk
    public static final long STALE_TICKS = 5;

    private static volatile Stamp stamp;

    private record Stamp(long at, double spotDistance, boolean stalled, boolean threatInSight) {
    }

    private BlazeCoverHook() {
    }

    public static void set(long gameTime, double spotDistance, boolean stalled, boolean threatInSight) {
        stamp = new Stamp(gameTime, spotDistance, stalled, threatInSight);
    }

    public static void clear() {
        stamp = null;
    }

    // the food chain waits on this before it starts a bite
    public static boolean holdsBite(long gameTime) {
        Stamp s = stamp;
        if (s == null || gameTime < s.at || gameTime - s.at > STALE_TICKS) return false;
        return BlazeFightRules.holdBiteForCover(true, s.threatInSight, s.spotDistance, s.stalled);
    }
}
