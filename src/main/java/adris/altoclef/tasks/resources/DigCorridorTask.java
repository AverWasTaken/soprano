package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;

// one straight stretch of tunnel. pathing and not clearArea: the builder skips liquid cells and never asks avoidBreaking.
// no wander either, a miner that gets bored and strolls off is how you lose the pattern, the parent's watchdog handles boredom.
// left alone A* would happily dig around a wall or through something softer, so the rule below says the line or nothing
public class DigCorridorTask extends CustomBaritoneGoalTask {

    private final BranchPlan.Segment _segment;
    // one cell at a time. with the far end as the goal A* gets to shop around: a cave up top plus digging straight down the line
    // to the end can beat tunnelling, and then we've got a very nice hole and no tunnel
    private BlockPos _aim;

    public DigCorridorTask(BranchPlan.Segment segment) {
        super(false);
        _segment = segment;
        _aim = segment.cells().get(0);
    }

    // the parent moves this along as cells clear. a path already running finishes first, the new goal goes in when it's done
    void aimAt(BlockPos cell) {
        if (!_aim.equals(cell)) {
            _aim = cell;
            _cachedGoal = new GoalBlock(cell);
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        // our own level, same pair as DestroyBlockTask. a tool trip stops this task first, so the rule can't follow it to the surface.
        // the segment is a record of immutable stuff, baritone's thread reading it is fine
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(pos -> !_segment.inCorridor(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        mod.getBehaviour().pop();
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(_aim);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DigCorridorTask task && task._segment.equals(_segment);
    }

    @Override
    protected String toDebugString() {
        return "Digging to " + _aim.toShortString();
    }

    @Override
    protected String toHudString() {
        return "Digging a tunnel";
    }
}
