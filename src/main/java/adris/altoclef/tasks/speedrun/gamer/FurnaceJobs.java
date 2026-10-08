package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.List;

// the bookkeeping for furnaces we loaded and walked away from. pure (RunState.FurnaceJob in, numbers out) so the rules are
// testable without a game. the list itself lives in RunState.furnaceJobs, and a job is only a memory: when we get back,
// whatever is in the furnace is the truth and the job is rewritten or dropped to match
public final class FurnaceJobs {
    // furnace cooks an item in 10 s, blast furnace and smoker in 5
    public static final int FURNACE_TICKS = 200;
    public static final int FAST_TICKS = 100;

    private FurnaceJobs() {
    }

    // block registry path -> ticks per item
    public static int ticksPerItem(String kind) {
        return "blast_furnace".equals(kind) || "smoker".equals(kind) ? FAST_TICKS : FURNACE_TICKS;
    }

    // when the last item is done if it all started cooking at startTick. the fuel has to be loaded up front for this to hold,
    // which is the only way the smelt tasks ever call it
    public static long doneTick(String kind, long startTick, int count) {
        return startTick + (long) ticksPerItem(kind) * count;
    }

    // a job for this spot replaces the old one: the count was read off the furnace's input slot a moment ago, so it is
    // newer than whatever we remembered
    public static void record(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job) {
        jobs.removeIf(old -> sameSpot(old, job.pos, job.dimension));
        jobs.add(job);
    }

    private static boolean sameSpot(RunState.FurnaceJob job, RunState.Pos pos, String dimension) {
        return job.pos.equals(pos) && job.dimension.equals(dimension);
    }

    // the furnace at this spot has something of ours in it. fix-pickup-stations asks this before it takes a furnace back
    public static boolean isBusy(RunState state, RunState.Pos pos) {
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if (job.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBusy(RunState state, int x, int y, int z) {
        return isBusy(state, new RunState.Pos(x, y, z));
    }

    // what the jobs for this dimension will have given us by the time they are all done
    public static int pending(List<RunState.FurnaceJob> jobs, String outputName) {
        int total = 0;
        for (RunState.FurnaceJob job : jobs) {
            if (job.output.equals(outputName)) {
                total += job.count;
            }
        }
        return total;
    }

    // nutrition the food jobs will have given us once they are done. not in the bag yet, so not in foodUnits(): the planner
    // adds this on top so a loaded smoker is not a reason to go hunting again
    public static int pendingUnits(List<RunState.FurnaceJob> jobs) {
        int total = 0;
        for (RunState.FurnaceJob job : jobs) {
            total += job.count * job.unitsEach;
        }
        return total;
    }

    // the furnace-likes we put down ourselves and so may pick up again. village furnaces and blast furnaces are never in here
    public static boolean pickable(String kind) {
        return "furnace".equals(kind) || "smoker".equals(kind);
    }

    // the placed list a job's block belongs to, null for kinds we never take back
    public static List<RunState.Pos> placedFor(RunState state, String kind) {
        if ("furnace".equals(kind)) {
            return state.placedFurnaces;
        }
        return "smoker".equals(kind) ? state.placedSmokers : null;
    }

    // may the furnace-like of this finished job come down. inputLeft is what the collect trip saw still cooking: anything
    // in there (or another job still pointing at the spot) and breaking it would drop the food on the floor, so it waits. a
    // spare one in the bag means this one is not worth the trip, same as the furnace always was
    public static boolean mayTakeBack(RunState state, String kind, RunState.Pos pos, int inputLeft, boolean holdsSpare) {
        List<RunState.Pos> own = placedFor(state, kind);
        return own != null && inputLeft <= 0 && !holdsSpare && own.contains(pos) && !isBusy(state, pos);
    }

    public static boolean anyDue(List<RunState.FurnaceJob> jobs, long now, long slackTicks) {
        for (RunState.FurnaceJob job : jobs) {
            if (now + slackTicks >= job.doneTick) {
                return true;
            }
        }
        return false;
    }

    // the job that is ready first, null if there are none
    public static RunState.FurnaceJob soonest(List<RunState.FurnaceJob> jobs) {
        RunState.FurnaceJob best = null;
        for (RunState.FurnaceJob job : jobs) {
            if (best == null || job.doneTick < best.doneTick) {
                best = job;
            }
        }
        return best;
    }

    // ticks until the last job is done, 0 when they all are
    public static long ticksLeft(List<RunState.FurnaceJob> jobs, long now) {
        long left = 0;
        for (RunState.FurnaceJob job : jobs) {
            left = Math.max(left, job.doneTick - now);
        }
        return left;
    }

    // a job from a world that ran on without us (a relog days later) is not worth a walk, the contents are probably gone
    // or the furnace is. returns what was dropped
    public static List<RunState.FurnaceJob> dropStale(List<RunState.FurnaceJob> jobs, long now, double staleSeconds) {
        List<RunState.FurnaceJob> gone = new ArrayList<>();
        for (RunState.FurnaceJob job : jobs) {
            if (now - job.startTick > staleSeconds * 20) {
                gone.add(job);
            }
        }
        jobs.removeAll(gone);
        return gone;
    }

    // ticks left on `inputCount` items when the one cooking right now is `arrow` of the way done (0..1). the furnace's own slots
    // are the truth, this is the same sum CollectFromFurnaceTask uses to decide between waiting and leaving
    public static long remainingTicks(String kind, int inputCount, double arrow) {
        return Math.round(ticksPerItem(kind) * (inputCount - Math.min(1.0, Math.max(0.0, arrow))));
    }

    // what a job looks like after we visited: what is still cooking (input slot count) or nothing left to come back for.
    // the furnace sat in an unloaded chunk for who knows how long, so the old doneTick was a guess and this one is not
    public static void afterVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int inputLeft, long now) {
        afterVisit(jobs, job, inputLeft, (long) ticksPerItem(job.kind) * inputLeft, now);
    }

    // same with the time left read off the cook arrow instead of assuming the current item has not started
    public static void afterVisit(List<RunState.FurnaceJob> jobs, RunState.FurnaceJob job, int inputLeft, long remaining, long now) {
        if (inputLeft <= 0) {
            jobs.remove(job);
            return;
        }
        job.count = inputLeft;
        job.startTick = now;
        job.doneTick = now + Math.max(1, remaining);
    }
}
