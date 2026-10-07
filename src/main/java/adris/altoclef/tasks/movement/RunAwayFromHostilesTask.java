package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromCrowd;
import adris.altoclef.util.helpers.EntityHelper;
import baritone.api.pathing.goals.Goal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Skeleton;

public class RunAwayFromHostilesTask extends CustomBaritoneGoalTask {

    // the hostiles that count as "the crowd" when nobody hands us one. past this they are not who we are running from
    private static final double CROWD_RADIUS = 20;
    private static final int CROWD_MAX = 12;

    private final double _distanceToRun;
    private final boolean _includeSkeletons;
    // whoever is kiting knows the crowd better than a scan does (it already threw out the ones walking past)
    private final Supplier<List<Entity>> _crowd;

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons) {
        this(distance, includeSkeletons, null);
    }

    public RunAwayFromHostilesTask(double distance) {
        this(distance, false, null);
    }

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons, Supplier<List<Entity>> crowd) {
        _distanceToRun = distance;
        _includeSkeletons = includeSkeletons;
        _crowd = crowd;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // We want to run away NOW
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalRunAwayFromHostiles(mod, _distanceToRun);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromHostilesTask task) {
            return Math.abs(task._distanceToRun - _distanceToRun) < 1 && (task._crowd == null) == (_crowd == null);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Running from monsters";
    }

    @Override
    protected String toDebugString() {
        return "NIGERUNDAYOO, SUMOOKEYY!";
    }

    private class GoalRunAwayFromHostiles extends GoalRunAwayFromCrowd {

        private final AltoClef _mod;

        public GoalRunAwayFromHostiles(AltoClef mod, double distance) {
            super(distance);
            _mod = mod;
        }

        @Override
        protected List<Entity> getCrowd() {
            if (_crowd != null) return _crowd.get();
            List<Entity> hostiles = _mod.getEntityTracker().getHostiles();
            List<Entity> crowd = new ArrayList<>();
            try {
                for (Entity hostile : hostiles) {
                    if (!_includeSkeletons && hostile instanceof Skeleton) continue;
                    if (hostile.distanceTo(_mod.getPlayer()) > CROWD_RADIUS) continue;
                    if (!EntityHelper.isAngryAtPlayer(_mod, hostile)) continue;
                    crowd.add(hostile);
                    if (crowd.size() >= CROWD_MAX) break;
                }
            } catch (java.util.ConcurrentModificationException ignored) {
                // the tracker rebuilds on another thread sometimes, we get the crowd again next tick
            }
            if (!crowd.isEmpty()) return crowd;
            // nothing angry close by, which used to be run from "the closest of the first hostile's kind" anyway
            Optional<Entity> lone = Optional.empty();
            if (!hostiles.isEmpty()) {
                for (Entity hostile : hostiles) {
                    Optional<Entity> closest = _mod.getEntityTracker().getClosestEntity(hostile.getClass());
                    if (closest.isPresent()) {
                        if (_includeSkeletons || !(closest.get() instanceof Skeleton)) lone = closest;
                        break;
                    }
                }
            }
            return lone.map(List::of).orElseGet(List::of);
        }
    }
}
