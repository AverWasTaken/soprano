package adris.altoclef.tasks.speedrun.gamer.tasks;

// pure: when does the sweep stop walking to dig itself some blocks? the bridges over lava eat throwaways and nothing in the
// nether hands them back, so a bag that ran dry mid-walk turns every path after it into "0 blocks closer"
public final class NetherRestock {
    // under this many the next bridge is a coin flip
    static final int LOW = 8;
    // after a waypoint was given up on the bag is the first suspect, so the bar is higher
    static final int GIVE_UP_LOW = 24;
    public static final int TARGET = 64;
    // netherrack is everywhere but the digging can still go nowhere (lava all around): stop trying after this
    static final double MAX_SECONDS = 75;
    static final double COOLDOWN_SECONDS = 120;

    private boolean active;
    private double startedAt;
    private double coolUntil = Double.NEGATIVE_INFINITY;

    public boolean isActive() {
        return active;
    }

    // held = every throwaway in the whole bag. true = dig (until the target) instead of walking
    public boolean update(int held, boolean gaveUp, boolean hasPick, double now) {
        if (!hasPick) {
            active = false;
            return false;
        }
        if (active) {
            if (held >= TARGET) {
                active = false;
            } else if (now - startedAt >= MAX_SECONDS) {
                active = false;
                coolUntil = now + COOLDOWN_SECONDS;
            }
            return active;
        }
        if (now < coolUntil) {
            return false;
        }
        if (held < LOW || (gaveUp && held < GIVE_UP_LOW)) {
            active = true;
            startedAt = now;
        }
        return active;
    }
}
