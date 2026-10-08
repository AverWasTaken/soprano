package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.container.FurnaceReuse;
import adris.altoclef.util.helpers.WalkCost;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

// leaving the mine with a furnace or smoker still cooking at the bottom of it. the jobs are a memory that survives the walk,
// but the walk back down is the cost: this is the one chance to take the contents (and the station) along while we are
// still standing next to it. pure (jobs and numbers in, a choice out), IronPhase does the walking
public final class PackUp {
    // a block of climbing at about the speed the bot gets out of a mine, in ticks. the wait for a nearly done job is measured
    // against the time the walk away costs anyway
    private static final long TICKS_PER_DEPTH = 20;
    private static final long MIN_WAIT_TICKS = 200;
    private static final long MAX_WAIT_TICKS = 600;

    private PackUp() {
    }

    // a job this deep below the open sky is one we would be climbing away from
    public static boolean stranded(int jobDepth) {
        return jobDepth > SmeltSurface.GO_UP_DEPTH;
    }

    // how long waiting for a job is no worse than leaving: the climb out takes this long anyway, and never less than 10 s
    public static long waitTicks(int depth) {
        return Math.max(MIN_WAIT_TICKS, Math.min(MAX_WAIT_TICKS, depth * TICKS_PER_DEPTH));
    }

    // WAIT_ALL when it is about to be done (stand there and take it), TAKE_ALL when it is not (the unfinished input comes back
    // out with the station, the planner smelts it again on top)
    public static Mode mode(long remainingTicks, int depth) {
        return remainingTicks <= waitTicks(depth) ? Mode.WAIT_ALL : Mode.TAKE_ALL;
    }

    // the stranded job to go and get, the one that is ready first. `tried` are the spots we already made a trip for: a trip
    // that comes back with input still cooking (capped) must not send us round again for ever. `depthOf` is how deep its
    // furnace is, `walkOf` what the walk to it costs from here (WalkCost units), one that is a long way off waits for the
    // plain due-collect like it always did
    public static RunState.FurnaceJob pick(List<RunState.FurnaceJob> jobs, Set<RunState.Pos> tried, ToIntFunction<RunState.FurnaceJob> depthOf,
                                           java.util.function.ToDoubleFunction<RunState.FurnaceJob> walkOf) {
        RunState.FurnaceJob best = null;
        for (RunState.FurnaceJob job : jobs) {
            if (tried.contains(job.pos) || !stranded(depthOf.applyAsInt(job)) || walkOf.applyAsDouble(job) > worthWalking()) {
                continue;
            }
            if (best == null || job.doneTick < best.doneTick) {
                best = job;
            }
        }
        return best;
    }

    // the same stretched walk a furnace of ours gets everywhere else (FurnaceReuse.OURS_BUDGET)
    public static double worthWalking() {
        return FurnaceReuse.OURS_BUDGET;
    }

    // walk cost from us to the job's furnace
    public static double walk(RunState.FurnaceJob job, double px, double py, double pz) {
        return WalkCost.estimate(job.pos.x + 0.5 - px, job.pos.y - py, job.pos.z + 0.5 - pz);
    }
}
