package adris.altoclef.tasks.speedrun.gamer;

// what the running cook task tells the planner. static for the same reason AsyncSmelting is: the task cannot see the gamer, the
// facts can see this. two things:
// the backoff. the cook cannot fail out of a need (nothing in the kit loop knows how), so when it has been going nowhere for a
// couple of minutes it parks itself here and the planner stops asking for a while.
// the station it picked. making a smoker eats the logs and cobble that made the planner say "smoker" in the first place, and
// placing one is a tick where it is neither in the bag nor in placedSmokers. without this the need flipped to furnace (or to
// nothing) halfway through and the task got rebuilt from scratch
public final class CookTrip {
    // 4 minutes: long enough to get on with the run, short enough that a coal found meanwhile still cooks before the nether
    public static final long BACKOFF_TICKS = 4 * 60 * 20;

    private static volatile long until = -1;
    // null = no cook running, else "smoker" or "furnace"
    private static volatile String station;

    private CookTrip() {
    }

    public static void suspend(long now) {
        until = now + BACKOFF_TICKS;
        station = null;
    }

    public static boolean suspended(long now) {
        return now < until;
    }

    // the task stamps it every tick it runs. one that stopped being ticked without a stop (dropped under a chain, a crash in a
    // sibling) goes stale on its own instead of pinning the planner to a smoker forever
    private static volatile long stamp;
    private static final long STALE_TICKS = 200;

    public static void commit(boolean smoker, long now) {
        station = smoker ? "smoker" : "furnace";
        stamp = now;
    }

    public static void release() {
        station = null;
    }

    public static String committed(long now) {
        String s = station;
        return s != null && now - stamp <= STALE_TICKS ? s : null;
    }

    public static void clear() {
        until = -1;
        station = null;
    }
}
