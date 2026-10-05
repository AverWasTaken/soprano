package adris.altoclef.tasks.speedrun.gamer.tasks;

// the timers around CollectBlazeRodsTask, which never gives up on its own. pure: the phase feeds it the clock, the rod
// count and "are we parked next to a spawner", it answers keep going / try another fortress / forget the rods
public final class RodsWatch {
    public enum Verdict {
        KEEP_GOING,
        // camped at the spawner for too long without a rod
        GIVE_UP_SPAWNER,
        // the whole rods step took too long
        GIVE_UP_RODS
    }

    private final double campSeconds;
    private final double budgetSeconds;

    private double startedAt = Double.NaN;
    private double lastGain;
    private double campSince = Double.NaN;
    private int lastRods = -1;

    public RodsWatch(double campSeconds, double budgetSeconds) {
        this.campSeconds = campSeconds;
        this.budgetSeconds = budgetSeconds;
    }

    public Verdict update(double now, int rods, boolean camping) {
        if (Double.isNaN(startedAt)) {
            startedAt = now;
            lastGain = now;
        }
        if (rods > lastRods && lastRods >= 0) {
            lastGain = now;
            campSince = camping ? now : Double.NaN;
        }
        lastRods = rods;
        if (now - startedAt >= budgetSeconds) {
            return Verdict.GIVE_UP_RODS;
        }
        if (!camping) {
            campSince = Double.NaN;
            return Verdict.KEEP_GOING;
        }
        if (Double.isNaN(campSince)) {
            campSince = now;
        }
        return now - Math.max(campSince, lastGain) >= campSeconds ? Verdict.GIVE_UP_SPAWNER : Verdict.KEEP_GOING;
    }

    // we left that spawner, the next one starts a fresh camp
    public void spawnerGivenUp(double now) {
        campSince = Double.NaN;
        lastGain = now;
    }
}
