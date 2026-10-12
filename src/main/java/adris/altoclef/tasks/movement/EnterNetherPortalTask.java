package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import baritone.altoclef.SettingsOverrides;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class EnterNetherPortalTask extends Task {
    private static final double FRAME_STUCK_SECONDS = 3;
    // a drop of 4 is 1 damage, and it is the drop off a standard frame top (3 tall interior + the obsidian above it)
    private static final int FRAME_DROP_FALL_HEIGHT = 5;

    private final Task _getPortalTask;
    private final Dimension _targetDimension;

    private final TimerGame _portalTimeout = new TimerGame(10);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);

    private final Predicate<BlockPos> _goodPortal;

    private boolean _leftPortal;

    // how long we have been standing up on the frame. see onTopOfFrame
    private final TimerGame _onFrameTimer = new TimerGame(FRAME_STUCK_SECONDS);
    // what the user had for maxFallHeightNoWater while we hold it higher, null when we are not holding it
    private Integer _userFallHeight;

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        if (targetDimension == Dimension.END)
            throw new IllegalArgumentException("Can't build a nether portal to the end.");
        _getPortalTask = getPortalTask;
        _targetDimension = targetDimension;
        _goodPortal = goodPortal;
    }

    public EnterNetherPortalTask(Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        this(null, targetDimension, goodPortal);
    }

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension) {
        this(getPortalTask, targetDimension, blockPos -> true);
    }

    public EnterNetherPortalTask(Dimension targetDimension) {
        this(null, targetDimension);
    }

    // a portal interior is at least three cells tall and feet only ever fit in the bottom one. aiming a GoalBlock at a
    // higher cell is impossible, so baritone settled for the closest node it could reach, and the top of the frame
    // can be that node. the bot then stood on the obsidian and looked at the portal like it owed it money
    public static boolean isFeetCell(BlockPos cell, Predicate<BlockPos> isPortal) {
        return !isPortal.test(cell.below());
    }

    // feet up on the frame: standing on the obsidian above a 3 tall interior, and close enough to the portal that this is the frame and not
    // a hill. the frame top is 4 above the ground next to it and baritone refuses a drop that big, so a bot that got up
    // there can be unable to path back down (the pick is fine, the goal is simply unreachable from up here)
    public static boolean onTopOfFrame(Vec3 feet, BlockPos feetCell) {
        double dx = feet.x - (feetCell.getX() + 0.5);
        double dz = feet.z - (feetCell.getZ() + 0.5);
        return feet.y >= feetCell.getY() + 4 && dx * dx + dz * dz <= 3.5 * 3.5;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        _leftPortal = false;
        _onFrameTimer.reset();
        _portalTimeout.reset();
        _wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Exiting portal for a bit.");
            _portalTimeout.reset();
            _leftPortal = true;
            return _wanderTask;
        }

        if (mod.getWorld().getBlockState(mod.getPlayer().blockPosition()).getBlock() == Blocks.NETHER_PORTAL) {

            if (_portalTimeout.elapsed() && !_leftPortal) {
                return _wanderTask;
            }
            setDebugState("Waiting inside portal", "Standing in the portal");
            releaseFallHeight(mod);
            mod.getClientBaritone().getExploreProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getMineProcess().onLostControl();
            mod.getClientBaritone().getFarmProcess().onLostControl();
            mod.getClientBaritone().getGetToBlockProcess();
            mod.getClientBaritone().getBuilderProcess();
            mod.getClientBaritone().getFollowProcess();
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return null;
        } else {
            _portalTimeout.reset();
        }

        Predicate<BlockPos> portalAt = blockPos -> mod.getWorld().getBlockState(blockPos).getBlock() == Blocks.NETHER_PORTAL;
        Predicate<BlockPos> standablePortal = blockPos -> {
            if (portalAt.test(blockPos)) {
                return isFeetCell(blockPos, portalAt);
            }
            // REQUIRE that there be solid ground beneath us, not more portal.
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                // Eh just assume it's good for now
                return true;
            }
            BlockPos below = blockPos.below();
            boolean canStand = WorldHelper.isSolid(mod, below) && !mod.getBlockTracker().blockIsValid(below, Blocks.NETHER_PORTAL);
            return canStand && _goodPortal.test(blockPos);
        };

        watchFrame(mod, standablePortal);

        if (mod.getBlockTracker().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Going to found portal");
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false), standablePortal, Blocks.NETHER_PORTAL);
        }
        if (!mod.getBlockTracker().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Making new nether portal.");
            if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                return new ConstructNetherPortalBucketTask();
            } else {
                return _getPortalTask != null ? _getPortalTask : new ConstructNetherPortalObsidianTask();
            }
        }
        setDebugState("Getting our portal");
        return _getPortalTask;
    }

    // the bot can end up on the frame top and baritone will not drop 4 blocks on its own. after a few seconds up there the
    // limit goes up to 5 for as long as we are up there (1 damage), so the path back down to the bottom cell exists
    private void watchFrame(AltoClef mod, Predicate<BlockPos> standablePortal) {
        Vec3 feet = mod.getPlayer().position();
        Optional<BlockPos> cell = mod.getBlockTracker().getNearestTracking(feet, standablePortal, Blocks.NETHER_PORTAL);
        if (!mod.getPlayer().onGround()) {
            // mid drop is no time to take the limit away from the path that is doing the dropping
            return;
        }
        if (cell.isEmpty() || !onTopOfFrame(feet, cell.get())) {
            _onFrameTimer.reset();
            releaseFallHeight(mod);
            return;
        }
        var fall = mod.getClientBaritoneSettings().maxFallHeightNoWater;
        if (_onFrameTimer.elapsed() && _userFallHeight == null && fall.value < FRAME_DROP_FALL_HEIGHT) {
            _userFallHeight = fall.value;
            SettingsOverrides.put(fall, FRAME_DROP_FALL_HEIGHT);
            // the debug state would be overwritten by the pick further down this same tick
            Debug.logMessage("Stuck on the portal frame, allowing a bigger drop.");
        }
    }

    // only hands it back if the hold is still ours, same idea as PortalPhase does with async smelting
    private void releaseFallHeight(AltoClef mod) {
        if (_userFallHeight == null) {
            return;
        }
        var fall = mod.getClientBaritoneSettings().maxFallHeightNoWater;
        if (SettingsOverrides.isHeld(fall) && fall.value == FRAME_DROP_FALL_HEIGHT) {
            SettingsOverrides.put(fall, _userFallHeight);
        }
        _userFallHeight = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        releaseFallHeight(mod);
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _targetDimension;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnterNetherPortalTask task) {
            return (Objects.equals(task._getPortalTask, _getPortalTask) && Objects.equals(task._targetDimension, _targetDimension));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering nether portal";
    }

    @Override
    protected String toHudString() {
        return "Going through a Nether portal";
    }
}
