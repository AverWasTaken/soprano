package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.WalkCost;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

// leaving the mine with a furnace or smoker still cooking at the bottom of it. the jobs are a memory that survives the walk,
// but the walk back down is the cost: this is the one chance to take the contents (and the station) along while we are
// still standing next to it. pure (jobs and numbers in, a choice out), IronPhase does the walking. the trip itself is a TAKE_ALL
// (FurnacePlan.leaving): it waits when the job is nearly done (FurnacePlan.leaveWindow) and otherwise takes the unfinished input
// back out, so nothing is left behind either way
public final class PackUp {
    private PackUp() {
    }

    // a job this deep below the open sky is one we would be climbing away from
    public static boolean stranded(int jobDepth) {
        return jobDepth > SmeltSurface.GO_UP_DEPTH;
    }

    // the stranded job to go and get, the one that is ready first. `tried` are the spots we already made a trip for, so one
    // that cannot be emptied must not send us round again for ever. `depthOf` is how deep its furnace is, `walkOf` what the
    // walk to it costs from here (WalkCost units), one that is a long way off waits for the plain due-collect like it always did
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

    // 180 of walk cost (WalkCost units, the path-ish kind: this is a trip choice, not the station reuse line). not about reusing it, it is the only
    // chance to take the contents without a second trip down, so a furnace 10 down and 20 across (60) is still worth it, and
    // only one in another area waits for the plain due-collect
    public static double worthWalking() {
        return 180;
    }

    // walk cost from us to the job's furnace
    public static double walk(RunState.FurnaceJob job, double px, double py, double pz) {
        return WalkCost.estimate(job.pos.x + 0.5 - px, job.pos.y - py, job.pos.z + 0.5 - pz);
    }
}
