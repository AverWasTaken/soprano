package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.world.StrongholdEstimator;

// turns "where the thrown eye was each tick" into one ray. pure on purpose, the task feeds it entity positions.
// the eye flies the exact straight line from the throw point toward the stronghold, so the farthest point it
// reaches is the best bearing sample: a 12 block lever arm beats the 1 block it covers in the first 8 ticks
// (it speeds up by 0.0025 * distance per tick, it is not fast off the line)
public final class EyeThrowObserver {
    public enum Outcome {
        PENDING,
        RAY,
        // never saw an eye entity in time: the throw did not happen, the eye is still in the bag
        EMPTY,
        // an eye showed up but never got far enough from the throw spot to say anything about direction
        UNUSABLE
    }

    public record Result(Outcome outcome, StrongholdEstimator.Ray ray, boolean dived, String why) {
    }

    // 3 s, then it was an empty throw
    static final int EMPTY_TICKS = 60;
    // below this the direction is mostly rounding noise
    static final double MIN_RAY_BLOCKS = 2.0;
    // the eye lives 80 ticks, we stop looking well before so we never race the despawn
    static final int MAX_AGE_TICKS = 70;
    // it normally hovers at 12 blocks by tick ~35, no point waiting for the rest
    static final int EARLY_AGE_TICKS = 35;
    static final double EARLY_BLOCKS = 11.0;
    // gone this many ticks in a row = it despawned or we lost it
    static final int GONE_TICKS = 3;
    // dive: it stays inside 12 blocks of the target (horizontal) and sinks instead of rising 8
    static final double DIVE_MAX_HORIZONTAL = 12.5;
    static final double RISE_NOT_A_DIVE = 3.0;
    static final double FALL_IS_A_DIVE = 1.0;
    static final double SPAWN_SNAP_BLOCKS = 1.5;

    private double originX;
    private double originZ;
    private final long throwTime;

    private long firstSeen = -1;
    private int goneRun;
    private double firstY;
    private double maxY;
    private double minY;
    private double bestDist = -1;
    private double bestX;
    private double bestZ;
    private Result result = new Result(Outcome.PENDING, null, false, "waiting for the eye");

    public EyeThrowObserver(double originX, double originZ, long throwTime) {
        this.originX = originX;
        this.originZ = originZ;
        this.throwTime = throwTime;
    }

    public Result result() {
        return result;
    }

    public boolean isDone() {
        return result.outcome() != Outcome.PENDING;
    }

    public boolean sawEye() {
        return firstSeen >= 0;
    }

    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    // one call per game tick. present = an EyeOfEnder entity is in the tracker right now
    public Result observe(long now, boolean present, double x, double y, double z) {
        if (isDone()) {
            return result;
        }
        if (present) {
            sample(now, x, y, z);
        } else if (firstSeen >= 0) {
            goneRun++;
        }
        if (firstSeen < 0) {
            if (now - throwTime >= EMPTY_TICKS) {
                result = new Result(Outcome.EMPTY, null, false, "no eye showed up within 3 s");
            }
            return result;
        }
        long age = now - firstSeen;
        boolean finished = goneRun >= GONE_TICKS
                || age >= MAX_AGE_TICKS
                || (age >= EARLY_AGE_TICKS && bestDist >= EARLY_BLOCKS);
        if (finished) {
            finish();
        }
        return result;
    }

    private void sample(long now, double x, double y, double z) {
        goneRun = 0;
        if (firstSeen < 0) {
            firstSeen = now;
            // the eye spawns where the server had us when it handled the use, which can be a step off where we
            // stood when we sent it. the first sighting is the true spawn point (it creeps ~0.03 blocks a tick),
            // and an error of 0.2 blocks over a 12 block lever arm is already a degree
            if (Math.hypot(x - originX, z - originZ) <= SPAWN_SNAP_BLOCKS) {
                originX = x;
                originZ = z;
            }
            firstY = y;
            maxY = y;
            minY = y;
        }
        maxY = Math.max(maxY, y);
        minY = Math.min(minY, y);
        double dist = Math.hypot(x - originX, z - originZ);
        if (dist > bestDist) {
            bestDist = dist;
            bestX = x;
            bestZ = z;
        }
    }

    private void finish() {
        boolean dived = bestDist <= DIVE_MAX_HORIZONTAL
                && maxY - firstY < RISE_NOT_A_DIVE
                && firstY - minY >= FALL_IS_A_DIVE;
        // a dive close to the target barely moves sideways, the ray is junk but the "dived" bit is the whole point
        if (bestDist < MIN_RAY_BLOCKS && !dived) {
            result = new Result(Outcome.UNUSABLE, null, false, "the eye never left the throw spot");
            return;
        }
        StrongholdEstimator.Ray ray = StrongholdEstimator.rayFromPoints(originX, originZ, bestX, bestZ, dived);
        result = new Result(Outcome.RAY, ray, dived, dived ? "the eye dived" : "the eye flew " + Math.round(bestDist) + " blocks");
    }
}
