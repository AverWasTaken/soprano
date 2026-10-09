package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.baritone.SnapshotGoal;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {
    private final Task _wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean _wander;
    protected MovementProgressChecker _checker = new MovementProgressChecker();
    protected Goal _cachedGoal = null;
    private Task _unstuckTask = null;

    // This happens all the time in mineshafts and swamps/jungles

    public CustomBaritoneGoalTask(boolean wander) {
        _wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.offset(1, 0, 0),
                pos.offset(-1, 0, 0),
                pos.offset(0, 0, 1),
                pos.offset(0, 0, -1),
                pos.offset(1, 0, -1),
                pos.offset(1, 0, 1),
                pos.offset(-1, 0, -1),
                pos.offset(-1, 0, 1)
        };
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().blockPosition();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.above())) return p.above();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.above());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart(AltoClef mod) {
        cancelPath(mod);
        _checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // goals that snapshot entities get their once-a-tick refresh here, on the main thread
        if (_cachedGoal instanceof SnapshotGoal snapshot) {
            snapshot.refresh();
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _checker.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        if (_cachedGoal == null) {
            _cachedGoal = makeGoal(mod);
        }

        if (_wander) {
            if (isFinished(mod)) {
                // Don't wander if we've reached our goal.
                _checker.reset();
            } else {
                if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
                    setDebugState("Wandering...");
                    _checker.reset();
                    return _wanderTask;
                }
                if (!_checker.check(mod)) {
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return _wanderTask;
                }
            }
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel() && mayIssueGoal(mod)) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    // asked on the ticks the goal is about to be handed to baritone again (it is not active: it was reached, or the search
    // came back empty and baritone dropped it). yes is the default, a task whose goal can be out of reach says no for a bit
    protected boolean mayIssueGoal(AltoClef mod) {
        return true;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_cachedGoal == null) {
            _cachedGoal = makeGoal(mod);
        }
        return _cachedGoal != null && _cachedGoal.isInGoal(mod.getPlayer().blockPosition());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        cancelPath(mod);
    }

    // how the path gets dropped when this task starts and stops: hard, mid movement or not. CommittedRunTask is softer in the air
    protected void cancelPath(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
    }

    // fresh goals have never seen the world, so give them their first look right away
    private Goal makeGoal(AltoClef mod) {
        Goal goal = newGoal(mod);
        if (goal instanceof SnapshotGoal snapshot) {
            snapshot.refresh();
        }
        return goal;
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {
    }
}
