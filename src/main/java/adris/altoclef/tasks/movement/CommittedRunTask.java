package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalCommittedRun;
import adris.altoclef.util.helpers.CombatCommit;
import baritone.api.pathing.goals.Goal;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;

// the run CombatCommit asked for. it never finishes on its own: the commitment knows when 50 blocks and two quiet seconds
// have happened, a task that decides for itself is how a run ends early and the wheel goes back for a tick. stands still once it
// is at its goal (that is when eating is allowed) and walks again when something comes back within reach
public class CommittedRunTask extends CustomBaritoneGoalTask {

    // a bit more than the commitment's "clear" line, so a mob sitting right on the 16 does not make the goal flicker
    private static final double CROWD_CLEAR = CombatCommit.RUN_CLEAR + 2;

    private final double _originX;
    private final double _originZ;
    private final Supplier<List<Entity>> _crowd;

    public CommittedRunTask(double originX, double originZ, Supplier<List<Entity>> crowd) {
        // no wander: standing at the goal waiting out the quiet seconds is not "failed to make progress"
        super(false);
        _originX = originX;
        _originZ = originZ;
        _crowd = crowd;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // run NOW, not after the user's path finishes its segment
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalCommittedRun(_originX, _originZ, CombatCommit.RUN_DISTANCE, CROWD_CLEAR, _crowd);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CommittedRunTask task && task._originX == _originX && task._originZ == _originZ;
    }

    @Override
    protected String toHudString() {
        return "Running from monsters";
    }

    @Override
    protected String toDebugString() {
        return "Running " + (int) CombatCommit.RUN_DISTANCE + " blocks from " + Math.round(_originX) + ", " + Math.round(_originZ);
    }
}
