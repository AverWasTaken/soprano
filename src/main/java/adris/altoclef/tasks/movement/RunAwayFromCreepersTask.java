package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import baritone.api.pathing.goals.Goal;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;

public class RunAwayFromCreepersTask extends CustomBaritoneGoalTask {

    private final double _distanceToRun;

    public RunAwayFromCreepersTask(double distance) {
        _distanceToRun = distance;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromCreepersTask task) {
            //if (task._mob.getPos().squaredDistanceTo(_mob.getPos()) > 0.5) return false;
            if (Math.abs(task._distanceToRun - _distanceToRun) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Backing away from a Creeper";
    }

    @Override
    protected String toDebugString() {
        return "Run " + _distanceToRun + " blocks away from creepers";
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // We want to run away NOW
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalRunAwayFromCreepers(mod, _distanceToRun);
    }

    private static class GoalRunAwayFromCreepers extends GoalRunAwayFromEntities {

        public GoalRunAwayFromCreepers(AltoClef mod, double distance) {
            super(mod, distance, false, 10);
        }

        @Override
        protected Optional<Entity> getEntities(AltoClef mod) {
            return mod.getEntityTracker().getClosestEntity(Creeper.class);
        }

        @Override
        protected double getCostOfEntity(Target creeper, int x, int y, int z) {
            // the snapshot already holds the fuse, no need to poke the creeper from the pathfinder thread
            return MobDefenseChain.getCreeperSafety(x + 0.5, y + 0.5, z + 0.5, creeper.x(), creeper.y(), creeper.z(), creeper.swell());
        }
    }
}
