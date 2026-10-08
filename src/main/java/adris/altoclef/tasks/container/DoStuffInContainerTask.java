package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.construction.PlaceStationTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.ui.HudText;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;
    // a container this close is the one we are using, whatever the price of a new one says
    private static final double PLACED_CLOSE = 6.0;

    // tables and furnaces go down the way a player does it (PlaceStationTask, with the old placer inside as its last
    // resort), anything else (anvil, smithing table, chest) keeps the old one
    private final Task _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    private BlockPos _cachedContainerPosition = null;
    // the walk and the click used to be decided in two places (our nearest here, a closest-block search over there) and with
    // two furnaces standing they disagreed. this is the one block we both walk to and click, rebuilt only when it changes
    private Task _openTask;
    private BlockPos _openTaskPos;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = PlaceStationTask.isStation(_containerBlocks)
                ? new PlaceStationTask(_containerBlocks)
                : new PlaceBlockNearbyTask(_containerBlocks);
    }

    private BlockPos placedPos() {
        return _placeTask instanceof PlaceStationTask station ? station.getPlaced() : ((PlaceBlockNearbyTask) _placeTask).getPlaced();
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_containerBlocks);

        // Protect container since we might place it.
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(_containerBlocks));
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // If we're placing, keep on placing.
        // the click takes the table out of the bag a few ticks before the world shows it (VERIFY), and a walk that cut in
        // there stopped the placer mid-verify. so no item left is fine as long as the placer is still waiting on the block
        if (keepPlacing(mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_containerBlocks)), _placeTask.isActive(),
                _placeTask.isFinished(mod), _placeTask instanceof PlaceStationTask station && station.isVerifying())) {
            setDebugState("Placing container");
            return _placeTask;
        }

        if (isContainerOpen(mod)) {
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        Optional<BlockPos> nearest;

        Vec3 currentPos = mod.getPlayer().position();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockTracker().blockIsValid(override, _containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockTracker().getNearestTracking(currentPos, blockPos -> WorldHelper.canReach(mod, blockPos), _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, try using our placed task
            nearest = Optional.ofNullable(placedPos());
            if (nearest.isPresent() && !mod.getBlockTracker().blockIsValid(nearest.get(), _containerBlocks)) {
                nearest = Optional.empty();
            }
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }

        if (nearest.isEmpty() && _cachedContainerPosition != null && isContainerBlock(mod, _cachedContainerPosition)) {
            // the tracker lost the one we were walking to (a rescan after a fuel trip did it, and the next thing the bot did was
            // mine the smoker it had just put down to "get the container item"). the world still has it, the world wins
            nearest = Optional.of(_cachedContainerPosition);
        }

        nearest = stickToPreviousTarget(mod, nearest);

        boolean mayMakeNew = canMakeNew(mod);
        if (nearest.isEmpty() && !mayMakeNew) {
            // we were told to use a container that already exists, so with none left we sit still and let whoever
            // picked us notice and pick something else. crafting one is how this task used to ruin that
            _cachedContainerPosition = null;
            setDebugState("No container to use");
            return null;
        }

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        boolean cheaperNew = mayMakeNew && costToWalk > getCostToMakeNew(mod);
        if (cheaperNew) {
            _placeForceTimer.reset();
        }
        boolean placedStands = placedStands(mod);
        // the tracker lags a rescan behind the block we just put down, so "no table known, a new one is cheap" is wrong
        // while we are standing at the one we placed (a second way to get the 23:14 second table)
        boolean atPlaced = placedStands && WalkCost.within(placedPos().getX() + 0.5 - currentPos.x, placedPos().getY() - currentPos.y,
                placedPos().getZ() + 0.5 - currentPos.z, PLACED_CLOSE);
        if (mayMakeNew && makeNewNow(nearest.isPresent(), placedStands, cheaperNew && !atPlaced, !_placeForceTimer.elapsed(), _justPlacedTimer.elapsed())) {
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            _cachedContainerPosition = null;

            // Get if we don't have...
            if (!mod.getItemStorage().hasItem(_containerTarget)) {
                setDebugState("Getting container item");
                return TaskCatalogue.getItemTask(_containerTarget);
            }

            setDebugState("Placing container...");

            _justPlacedTimer.reset();
            // Now place!
            return _placeTask;
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        _cachedContainerPosition = nearest.get();

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Waiting for eating...", "Eating first");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString(), "Walking to " + HudText.pos(nearest.get()));

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (toMoveTo.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(toMoveTo.get(), 0, ClickType.PICKUP);
            return null;
        }
        if (_openTask == null || !nearest.get().equals(_openTaskPos)) {
            _openTaskPos = nearest.get();
            _openTask = new InteractWithBlockTask(_openTaskPos);
        }
        return _openTask;
    }

    static boolean keepPlacing(boolean haveItem, boolean placerActive, boolean placerFinished, boolean placerVerifying) {
        return (haveItem || placerVerifying) && placerActive && !placerFinished;
    }

    // the force timer is for being stuck with nowhere to put one. with ours standing in the world it was the "keep
    // placing" that crafted a second table 3 seconds after the first went down (the click missed once, the timer didn't care).
    // a leftover timer gives way to the standing block, a walk that is too far THIS tick (cheaperNew) does not: the placer
    // outlives the placement, and a table 30 blocks back is still standing and still not worth the walk
    static boolean makeNewNow(boolean haveNearest, boolean placedStands, boolean cheaperNew, boolean forceActive, boolean justPlacedElapsed) {
        return !haveNearest || (forceActive && justPlacedElapsed && (cheaperNew || !placedStands));
    }

    // the block our placer put down is in the world right now, whatever the tracker has caught up on
    private boolean placedStands(AltoClef mod) {
        BlockPos placed = placedPos();
        return placed != null && isContainerBlock(mod, placed);
    }

    // two containers about as far away trade places as we walk, and every trade is a new walk. keep the one we were heading
    // for unless the other is clearly closer (same 2x rule as the closest-block search), so what we walk to is what we click
    private Optional<BlockPos> stickToPreviousTarget(AltoClef mod, Optional<BlockPos> candidate) {
        BlockPos previous = _cachedContainerPosition;
        if (candidate.isEmpty() || previous == null || candidate.get().equals(previous) || !isContainerBlock(mod, previous)) {
            return candidate;
        }
        Vec3 me = mod.getPlayer().position();
        double now = WorldHelper.toVec3d(previous).distanceToSqr(me);
        double then = WorldHelper.toVec3d(candidate.get()).distanceToSqr(me);
        return MineStick.clearlyCloser(then, now) ? candidate : Optional.of(previous);
    }

    private boolean isContainerBlock(AltoClef mod, BlockPos pos) {
        // blacklisted spots stay blacklisted, that is the whole point of the tracker's answer being empty
        if (mod.getBlockTracker().unreachable(pos)) {
            return false;
        }
        Block there = mod.getWorld().getBlockState(pos).getBlock();
        for (Block block : _containerBlocks) {
            if (block == there) {
                return true;
            }
        }
        return false;
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    // Virtual
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        return null;
    }

    // Virtual. false means only ever use a container that is already there, never get/place one
    protected boolean canMakeNew(AltoClef mod) {
        return true;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(_containerBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task._containerBlocks, _containerBlocks)) return false;
            if (!task._containerTarget.equals(_containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + _containerTarget + " container";
    }

    @Override
    protected String toHudString() {
        return "Using " + HudText.items(_containerTarget);
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(AltoClef mod);

    protected abstract Task containerSubTask(AltoClef mod);

    protected abstract double getCostToMakeNew(AltoClef mod);
}
