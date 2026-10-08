package adris.altoclef.tasks.speedrun.gamer.end;

// the bed fight refuses to click below BedSafety.safePool, DragonStrat only leaves beds below CRITICAL_POOL, and in between
// the task just waits to heal. with no food (or nothing healing us) that is forever, so: this long hurt with the pool not
// going up and the strat falls back to the sword. pure, the task feeds it every time it would have clicked
public final class HealStall {
    // less than this is not healing, it is rounding (absorption wobbles)
    private static final double HEAL_EPS = 0.5;
    // samples only come while the dragon's head is in click range, a gap this long means the lap ended. the wait is per
    // perch, not per fight: hurt at the start of one lap and hurt again a minute later is not twenty seconds of not healing
    private static final double LAP_GAP_SECONDS = 10;

    private final double seconds;
    private double since = -1;
    private double lastSample;
    private double lowest;
    private boolean stalled;

    public HealStall(double seconds) {
        this.seconds = seconds;
    }

    // a fresh start of the bed task forgets the old verdict
    public void reset() {
        since = -1;
        lowest = 0;
        stalled = false;
    }

    // hurt = "we would click but the pool is too low". returns the latched verdict: once stalled it stays stalled until reset(),
    // the strat swap happens a tick later and healing in that tick must not undo it
    public boolean update(boolean hurt, double pool, double now) {
        if (!hurt) {
            since = -1;
            return stalled;
        }
        boolean lapOver = since >= 0 && now - lastSample > LAP_GAP_SECONDS;
        lastSample = now;
        if (since < 0 || lapOver) {
            since = now;
            lowest = pool;
        } else if (pool > lowest + HEAL_EPS) {
            // going up from the low point counts, damage and then healing back to where we were included
            since = now;
            lowest = pool;
        } else {
            lowest = Math.min(lowest, pool);
            if (now - since >= seconds) {
                stalled = true;
            }
        }
        return stalled;
    }

    public boolean stalled() {
        return stalled;
    }
}
