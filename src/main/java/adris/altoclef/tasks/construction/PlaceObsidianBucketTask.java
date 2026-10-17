package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Places obsidian at a position using buckets and a cast.
 */
public class PlaceObsidianBucketTask extends Task {

    public static final Vec3i[] CAST_FRAME = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, -1),
            new Vec3i(0, -1, 1),
            new Vec3i(-1, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(0, 0, -1),
            new Vec3i(0, 0, 1),
            new Vec3i(-1, 0, 0),
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0)
    };
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final BlockPos _pos;
    // cells cast before this one, whose water may still be sitting on top of them
    private final List<BlockPos> _earlierCells;

    private BlockPos _currentCastTarget;
    private BlockPos _currentDestroyTarget;
    // said once, and the ban handed out once
    private boolean _ruinSaid;
    // the lava click child is out there, so a pour we call off has a right click to let go of
    private boolean _pouring;
    // why the pour is on hold, so the log gets one line per reason and not one per tick
    private BucketPour.Verdict _heldFor;
    // the tick the lava cell started looking clear, MIN_VALUE while it doesn't
    private int _clearSince = Integer.MIN_VALUE;
    // we have SNEAK and MOVE_BACK down for the back-up next to the ledge, and whether this tick asked for them
    private boolean _backingUp;
    private boolean _backUpThisTick;

    public PlaceObsidianBucketTask(BlockPos pos) {
        this(pos, List.of());
    }

    public PlaceObsidianBucketTask(BlockPos pos, List<BlockPos> earlierCells) {
        _pos = pos;
        _earlierCells = earlierCells;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Don't break cast
        mod.getBehaviour().avoidBlockBreaking(block -> {
            for (Vec3i castPosRelativeToLava : PlaceObsidianBucketTask.CAST_FRAME) {
                BlockPos castPos = _pos.offset(castPosRelativeToLava);
                if (block.equals(castPos)) {
                    return true;
                }
            }
            return false;
        });
        // Don't place blocks inside our cast water/lava
        mod.getBehaviour().avoidBlockPlacing(block -> {
            BlockPos waterTarget = _pos.above();
            return block.equals(_pos) || block.equals(waterTarget);
        });

        _progressChecker.reset();
    }

    /**
     * This method is called periodically to perform a specific task.
     * It handles the logic for casting a spell using lava and water buckets.
     *
     * @param mod The mod instance
     * @return The next task to be executed
     */
    @Override
    protected Task onTick(AltoClef mod) {
        _backUpThisTick = false;
        Task next = castTick(mod);
        // the back-up below only let go in its own else, so standing right on the ledge (or any earlier return) kept the
        // keys down. any tick that didn't back up lets go of what we pressed
        if (_backingUp && !_backUpThisTick) {
            stopBackingUp(mod);
        }
        return next;
    }

    private void stopBackingUp(AltoClef mod) {
        _backingUp = false;
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_BACK);
    }

    private Task castTick(AltoClef mod) {
        watchLavaCell(mod);
        // Reset progress if pathing
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }

        // Clear leftover water
        if (mod.getBlockTracker().blockIsValid(_pos, Blocks.OBSIDIAN) && mod.getBlockTracker().blockIsValid(_pos.above(), Blocks.WATER)) {
            return new ClearLiquidTask(_pos.above());
        }

        // a block in the way that no tool we hold gets through in time (obsidian, under anything short of a diamond pick)
        // ruins this origin before we have fetched a single bucket for it. the parent moves the frame when it sees the ban
        if (ruined(mod)) {
            setDebugState("Cast ruined, waiting for the frame to move");
            return null;
        }

        // Make sure we have a water bucket
        // the cast in flight (lava down, water on top, bucket empty) is supposed to have no water bucket, the obsidian
        // is a tick away. asking for one here sent us wandering for a lake with the water sitting right above us
        boolean castInFlight = isWater(mod, _pos.above()) && mod.getWorld().getBlockState(_pos).getBlock() == Blocks.LAVA;
        if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET) && !castInFlight) {
            // the water we left on the cells before this one is a bucket we already own
            BlockPos leftover = leftoverWater(mod);
            if (leftover != null) {
                _progressChecker.reset();
                return new ClearLiquidTask(leftover);
            }
            _progressChecker.reset();
            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
        }

        // Make sure we have a lava bucket
        if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
            // The only excuse is that we have lava at our position.
            if (!mod.getBlockTracker().blockIsValid(_pos, Blocks.LAVA)) {
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
            }
        }

        // Check progress
        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            _progressChecker.reset();
            return new TimeoutWanderTask(5);
        }

        // Build cast frame if not already built
        if (_currentCastTarget != null) {
            if (WorldHelper.isSolid(mod, _currentCastTarget)) {
                _currentCastTarget = null;
            } else {
                return new PlaceStructureBlockTask(_currentCastTarget);
            }
        }

        // Destroy block if needed
        if (_currentDestroyTarget != null) {
            if (!needsClearing(mod, _currentDestroyTarget)) {
                _currentDestroyTarget = null;
            } else {
                return new DestroyBlockTask(_currentDestroyTarget);
            }
        }

        // Build the cast frame if not already built
        if (_currentCastTarget != null && WorldHelper.isSolid(mod, _currentCastTarget)) {
            // Current cast frame already built.
            _currentCastTarget = null;
        }
        for (Vec3i castPosRelative : CAST_FRAME) {
            BlockPos castPos = _pos.offset(castPosRelative);
            if (!WorldHelper.isSolid(mod, castPos)) {
                _currentCastTarget = castPos;
                return null;
            }
        }

        // Place lava
        if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {
            // Don't place lava at our position!
            // Would lead to an embarrassing death.
            BlockPos targetPos = _pos.offset(-1, 1, 0);
            if (!mod.getPlayer().blockPosition().equals(targetPos) && mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                if (!mod.getClientBaritone().getPathingBehavior().isPathing()
                        && targetPos.closerThan(mod.getPlayer().blockPosition(), 2)) {
                    mod.getInputControls().hold(Input.SNEAK);
                    mod.getInputControls().hold(Input.MOVE_BACK);
                    _backingUp = true;
                    _backUpThisTick = true;
                }
                return new GetToBlockTask(targetPos, false);
            }
            if (needsClearing(mod, _pos)) {
                _currentDestroyTarget = _pos;
                return null;
            }
            // Clear the upper two as well, to make placing more reliable.
            if (needsClearing(mod, _pos.above())) {
                _currentDestroyTarget = _pos.above();
                return null;
            }
            if (WorldHelper.isSolid(mod, _pos.above(2))) {
                _currentDestroyTarget = _pos.above(2);
                return null;
            }
            // asked every tick the click child runs too, this onTick comes first. returning null is what calls it off
            BucketPour.Verdict pour = pourVerdict(mod);
            if (pour != BucketPour.Verdict.POUR) {
                return holdThePour(mod, pour);
            }
            _heldFor = null;
            _pouring = true;
            return new InteractWithBlockTask(new ItemTarget(Items.LAVA_BUCKET, 1), Direction.WEST, _pos.offset(1, 0, 0), false);
        }
        _pouring = false;
        // Lava placed, Now, place water.
        BlockPos waterCheck = _pos.above();
        if (mod.getWorld().getBlockState(waterCheck).getBlock() != Blocks.WATER) {
            // Get to position to avoid weird stuck scenario
            BlockPos targetPos = _pos.offset(-1, 1, 0);
            if (!mod.getPlayer().blockPosition().equals(targetPos) && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                return new GetToBlockTask(targetPos, false);
            }
            if (needsClearing(mod, waterCheck)) {
                _currentDestroyTarget = waterCheck;
                return null;
            }
            if (WorldHelper.isSolid(mod, waterCheck.above())) {
                _currentDestroyTarget = waterCheck.above();
                return null;
            }
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1), Direction.WEST, _pos.offset(1, 1, 0), true);
        }
        return null;
    }

    // the cells the cast clears before it can put anything down: the lava cell, the water cell and the air over that. the
    // same three cells the destroy steps below go for, so a cell that only has water or lava in it is not a question
    private boolean ruined(AltoClef mod) {
        BlockPos[] cells = {_pos, _pos.above(), _pos.above(2)};
        String[] roles = {"lava", "water", "air over the water"};
        for (int i = 0; i < cells.length; i++) {
            if (!WorldHelper.isSolid(mod, cells[i]) || !WorldHelper.tooSlowToBreak(mod, cells[i])) {
                continue;
            }
            // the lava cell turning to obsidian is the cast working, the tracker just hasn't caught up with it yet
            if (i == 0 && mod.getWorld().getBlockState(cells[i]).getBlock() == Blocks.OBSIDIAN) {
                continue;
            }
            if (!_ruinSaid) {
                _ruinSaid = true;
                Debug.logMessage("cast ruined: " + BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(cells[i]).getBlock()).getPath()
                        + " at " + cells[i].toShortString() + " where the " + roles[i] + " cell goes, moving the frame");
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                // the parent drops its origin when the frame cell is unreachable, and the pool task gives up on it the same way
                mod.getBlockTracker().requestBlockUnreachable(_pos, 0, "something in the cast takes too long to break");
            }
            return true;
        }
        return false;
    }

    // the lava cell and the one over it are on the pour's ray from the ledge, so "not a full block" isn't clear enough for
    // those two: grass, a slab or a snow layer in the lava cell stops the ray on its top, and the lava goes on top of it,
    // which is the cell beside our feet. the rest only have to be out of the way
    private boolean needsClearing(AltoClef mod, BlockPos cell) {
        if (cell.equals(_pos) || cell.equals(_pos.above())) {
            return !BucketPour.clearForPour(mod.getWorld(), cell);
        }
        return WorldHelper.isSolid(mod, cell);
    }

    // every tick, not just at pour time: a break the server turned down and we broke again while walking back would
    // otherwise still count from the first time
    private void watchLavaCell(AltoClef mod) {
        if (!BucketPour.clearForPour(mod.getWorld(), _pos)) {
            _clearSince = Integer.MIN_VALUE;
        } else if (_clearSince == Integer.MIN_VALUE) {
            _clearSince = WorldHelper.getTicks();
        }
    }

    private BucketPour.Verdict pourVerdict(AltoClef mod) {
        boolean clear = BucketPour.clearForPour(mod.getWorld(), _pos);
        int clearTicks = clear && _clearSince != Integer.MIN_VALUE ? WorldHelper.getTicks() - _clearSince : 0;
        boolean fallingIn = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos) || WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        return BucketPour.check(clear, clearTicks, BucketPour.settleTicks(pingMs(mod)), fallingIn, mod.getPlayer().onGround(),
                mod.getPlayer().getBoundingBox(), _pos);
    }

    // our round trip from the tab list, 0 when there's nobody to ask (singleplayer has no lag to wait out anyway)
    private static int pingMs(AltoClef mod) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return 0;
        }
        PlayerInfo info = connection.getPlayerInfo(mod.getPlayer().getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // not now. the click child goes (we return null), and so does a right click it may have had down. standing here doing
    // nothing for long enough is the progress checker's to end, like any other stall
    private Task holdThePour(AltoClef mod, BucketPour.Verdict why) {
        if (_pouring) {
            _pouring = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }
        if (why != _heldFor) {
            _heldFor = why;
            Debug.logInternal("cast: holding the lava pour into " + _pos.toShortString() + ", " + why);
        }
        setDebugState("Holding the lava pour: " + why);
        if (why == BucketPour.Verdict.IN_THE_COLUMN) {
            // on the ledge cell but hanging off its east edge, so nothing above walks us anywhere and the progress checker
            // would ban the whole frame for it. face the frame and back off it, crouched so the west edge can't drop us
            LookHelper.lookAt(mod, Vec3.atCenterOf(_pos.offset(1, 0, 0)));
            mod.getInputControls().hold(Input.SNEAK);
            mod.getInputControls().hold(Input.MOVE_BACK);
            _backingUp = true;
            _backUpThisTick = true;
        }
        return null;
    }

    private static boolean isWater(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() == Blocks.WATER;
    }

    // the world, not the tracker: the tracker is a few ticks behind a cast and this runs right after one
    private BlockPos leftoverWater(AltoClef mod) {
        if (mod.getWorld().getBlockState(_pos).getBlock() == Blocks.OBSIDIAN && WorldHelper.isSourceBlock(mod, _pos.above(), true)) {
            return _pos.above();
        }
        for (BlockPos cell : _earlierCells) {
            if (cell.equals(_pos) || mod.getWorld().getBlockState(cell).getBlock() != Blocks.OBSIDIAN) {
                continue;
            }
            if (WorldHelper.isSourceBlock(mod, cell.above(), true)) {
                return cell.above();
            }
        }
        return null;
    }

    /**
     * This method is called when the task is interrupted.
     *
     * @param mod           The instance of the AltoClef class.
     * @param interruptTask The task that caused the interruption.
     */
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_pouring) {
            _pouring = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
        }
        if (_backingUp) {
            stopBackingUp(mod);
        }
        // Check if the mod's behaviour is not null
        if (mod.getBehaviour() != null) {
            // Pop the behaviour from the stack
            mod.getBehaviour().pop();
        }
    }

    /**
     * Check if the current task is finished.
     * The task is considered finished if the block at the specified position is obsidian
     * and there is no water block above it.
     *
     * @param mod The AltoClef mod instance.
     * @return True if the task is finished, False otherwise.
     */
    @Override
    public boolean isFinished(AltoClef mod) {
        // Get the BlockTracker instance from the mod
        BlockTracker blockTracker = mod.getBlockTracker();

        // Get the position of the block to check
        BlockPos pos = _pos;

        // Check if the block at the specified position is obsidian
        boolean isObsidian = blockTracker.blockIsValid(pos, Blocks.OBSIDIAN);

        // Check if there is no water block above the specified position
        boolean isNotWaterAbove = !blockTracker.blockIsValid(pos.above(), Blocks.WATER);

        // The task is considered finished if the block is obsidian and there is no water above
        boolean isFinished = isObsidian && isNotWaterAbove;

        return isFinished;
    }

    /**
     * Checks if the given task is equal to this PlaceObsidianBucketTask.
     * Two PlaceObsidianBucketTasks are considered equal if their positions are equal.
     * Overrides the isEqual() method from the parent class.
     *
     * @param other the task to compare with
     * @return true if the tasks are equal, false otherwise
     */
    @Override
    protected boolean isEqual(Task other) {
        // Check if the other task is an instance of PlaceObsidianBucketTask
        if (other instanceof PlaceObsidianBucketTask task) {
            // Check if the positions are equal
            boolean isEqual = task.getPos().equals(getPos());
            // Return the result
            return isEqual;
        }
        // Return false
        return false;
    }

    @Override
    protected String toHudString() {
        return "Making obsidian at " + HudText.pos(_pos);
    }

    @Override
    protected String toDebugString() {
        return "Placing obsidian at " + _pos + " with a cast";
    }

    /**
     * Retrieves the position of the object.
     *
     * @return The position of the object.
     */
    public BlockPos getPos() {

        return _pos;
    }
}
