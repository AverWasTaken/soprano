package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.CreeperStep;
import baritone.api.pathing.goals.Goal;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;

// a couple of blocks away from one lit creeper, through baritone's own planner (so no walking off ledges or into lava),
// then stand there. never finishes by itself: CreeperStep says when the fuse is over and the chain lets go
public class CreeperStepTask extends CustomBaritoneGoalTask {

    // a goal we are already standing in comes back from baritone at once, asking again every tick is a search per tick
    // for nothing. same idea as CommittedRunTask.REISSUE_GAP_TICKS, just shorter, the whole step is 2.5 s
    static final long REISSUE_GAP_TICKS = 10;

    private final int _creeperId;
    private long _lastIssue = Long.MIN_VALUE / 2;

    public CreeperStepTask(int creeperId) {
        // no wander: standing at the goal is the point
        super(false);
        _creeperId = creeperId;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // step NOW, not after the user's path finishes its segment
        cancelPath(mod);
        return new GoalAwayFromCreeper(mod, _creeperId);
    }

    // soft in the air, same as the run: a hard cancel mid fall drops the clutch or the bucket
    @Override
    protected void cancelPath(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (CommittedRunTask.mayCancelHard(player.onGround(), player.isInWater(), player.onClimbable())) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } else {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _lastIssue = Long.MIN_VALUE / 2;
    }

    @Override
    protected boolean mayIssueGoal(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now >= _lastIssue && now - _lastIssue < REISSUE_GAP_TICKS) return false;
        _lastIssue = now;
        return true;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CreeperStepTask task && task._creeperId == _creeperId;
    }

    @Override
    protected String toHudString() {
        return "Stepping out of a creeper blast";
    }

    @Override
    protected String toDebugString() {
        return "Step " + (int) CreeperStep.SAFE + " blocks from creeper " + _creeperId;
    }

    private static class GoalAwayFromCreeper extends GoalRunAwayFromEntities {
        private final int _id;

        GoalAwayFromCreeper(AltoClef mod, int id) {
            // 3d: a creeper on the ledge above is still a creeper next to us
            super(mod, CreeperStep.SAFE, false, 10);
            _id = id;
        }

        @Override
        protected Optional<Entity> getEntities(AltoClef mod) {
            Entity e = mod.getWorld().getEntity(_id);
            return e instanceof Creeper ? Optional.of(e) : Optional.empty();
        }
    }
}
