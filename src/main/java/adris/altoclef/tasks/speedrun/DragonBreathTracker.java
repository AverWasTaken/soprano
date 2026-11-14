package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AreaEffectCloud;

public class DragonBreathTracker {
    // clouds further than this cannot matter this tick and the box to block expansion is the only cost here
    private static final double CLOUD_RANGE = 24;
    // how often a running dodge may swap its goal for the clouds of now. a growing cloud changes its cells all the time, and a new
    // path every tick is a path nobody ever walks
    static final int REGOAL_TICKS = 10;
    private final HashSet<BlockPos> _breathBlocks = new HashSet<>();
    private long _lastTick = -1;

    public void updateBreath(AltoClef mod) {
        // the chains and the dragon phase all ask every tick, the answer cannot change inside one game tick
        long tick = mod.getWorld() == null ? -1 : mod.getWorld().getGameTime();
        if (tick >= 0 && tick == _lastTick) {
            return;
        }
        _lastTick = tick;
        _breathBlocks.clear();
        // every cloud close by, not just the nearest: a perched dragon leaves a whole trail of them and stepping
        // out of the nearest one into the next is not dodging anything
        for (AreaEffectCloud cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloud.class)) {
            if (!cloud.closerThan(mod.getPlayer(), CLOUD_RANGE)) {
                continue;
            }
            for (BlockPos bad : WorldHelper.getBlocksTouchingBox(mod, cloud.getBoundingBox())) {
                _breathBlocks.add(bad);
            }
        }
    }

    public boolean isNearDragonBreath(BlockPos pos, int blocks) {
        return anyWithin(_breathBlocks, pos, blocks);
    }

    static boolean anyWithin(Iterable<BlockPos> cells, BlockPos pos, int blocks) {
        for (BlockPos bad : cells) {
            if (bad.closerThan(pos, blocks)) return true;
        }
        return false;
    }

    public boolean isTouchingDragonBreath(BlockPos pos) {
        return _breathBlocks.contains(pos);
    }

    // the goal is a copy of the cells it was made from, so once the clouds move on it is running from ghosts. the same run object
    // lives through several clouds (see RunAwayFromDragonsBreathTask.onStart), so it looks again every so often. a clock that went
    // backwards (new world) counts as long enough ago, and no clouds at all keeps the old goal (GoalRunAway can't run from nothing)
    static boolean wantsNewGoal(Set<BlockPos> goalCells, Set<BlockPos> now, long ticksSinceGoal) {
        if (now.isEmpty() || now.equals(goalCells)) return false;
        return ticksSinceGoal < 0 || ticksSinceGoal >= REGOAL_TICKS;
    }

    public Task getRunAwayTask() {
        return new RunAwayFromDragonsBreathTask();
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {
        private Set<BlockPos> _goalCells = Set.of();
        private long _goalTick;

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            mod.getBehaviour().push();
            mod.getBehaviour().setBlockPlacePenalties(Double.POSITIVE_INFINITY);
            // do NOT ever wander
            _checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);
            // an interrupt only pauses us, and the chain's next run is equal by class, so the runner hands this same object back
            // (SingleTaskChain.shouldInstall). its goal is the clouds of the last run, around a spot we already left
            if (!_breathBlocks.isEmpty()) {
                _cachedGoal = null;
            }
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (_cachedGoal != null && wantsNewGoal(_goalCells, _breathBlocks, mod.getWorld().getGameTime() - _goalTick)) {
                _cachedGoal = newGoal(mod);
                // the goal process only gets a goal when it has none, so a path that is already running would finish the old one
                // first. setting it again revalidates: a path that doesn't end clear of the new cells gets dropped when it's safe
                if (mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
                }
            }
            return super.onTick(mod);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            mod.getBehaviour().pop();
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            _goalCells = new HashSet<>(_breathBlocks);
            _goalTick = mod.getWorld() == null ? 0 : mod.getWorld().getGameTime();
            return new GoalRunAway(10, _breathBlocks.toArray(BlockPos[]::new));
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromDragonsBreathTask;
        }

        @Override
        protected String toHudString() {
            return "Dodging dragon's breath";
        }

        @Override
        protected String toDebugString() {
            return "ESCAPE Dragons Breath";
        }
    }
}
