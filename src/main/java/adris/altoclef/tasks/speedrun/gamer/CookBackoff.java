package adris.altoclef.tasks.speedrun.gamer;

// the cook task cannot fail out of a need (nothing in the kit loop knows how), so when it has been going nowhere for a couple of
// minutes it parks the cook here and the planner stops emitting the need for a while. static for the same reason AsyncSmelting is:
// the task cannot see the gamer, the facts can see this
public final class CookBackoff {
    // 4 minutes: long enough to get on with the run, short enough that a coal found meanwhile still cooks before the nether
    public static final long BACKOFF_TICKS = 4 * 60 * 20;

    private static volatile long until = -1;

    private CookBackoff() {
    }

    public static void suspend(long now) {
        until = now + BACKOFF_TICKS;
    }

    public static boolean active(long now) {
        return now < until;
    }

    public static void clear() {
        until = -1;
    }
}
