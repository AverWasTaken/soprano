package adris.altoclef.tasks;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import baritone.api.utils.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * The parent for all "collect an item" tasks.
 * <p>
 * If the target item is on the ground or in a chest, will grab from those sources first.
 */
public abstract class ResourceTask extends Task implements ITaskCanForce {

    protected final ItemTarget[] _itemTargets;

    private final PickupDroppedItemTask _pickupTask;
    private final EnsureFreePlayerCraftingGridTask _ensureFreeCraftingGridTask = new EnsureFreePlayerCraftingGridTask();
    private ContainerCache _currentContainer;
    // Extra resource parameters
    private Block[] _mineIfPresent = null;
    private boolean _forceDimension = false;
    private Dimension _targetDimension;
    private BlockPos _mineLastClosest = null;
    // every item any target matches, built once. onTick used to rebuild this (twice, in two different ways) per tick
    // (lazy, a subclass is allowed to fill its targets in after super())
    private Item[] _allMatches;
    // and how many of each we're collecting, so the pathing side knows how much of it is ours to keep. same laziness
    private Map<Item, Integer> _reserve;
    // our own behaviour level. the reserve goes in here and not in whatever is on top, a child task pushes its own on every
    // tick of ours and the numbers used to land in it (and count twice)
    private BotBehaviour.State _level;
    private static final int CONTAINER_LOOKUP_TICKS = 20;
    private int _containerLookupCooldown = 0;

    public ResourceTask(ItemTarget[] itemTargets) {
        _itemTargets = itemTargets;
        _pickupTask = new PickupDroppedItemTask(_itemTargets, true);
    }

    public ResourceTask(ItemTarget target) {
        this(new ItemTarget[]{target});
    }

    public ResourceTask(Item item, int targetCount) {
        this(new ItemTarget(item, targetCount));
    }

    // a target that matches several items (any planks) saves its count for each of them, over-saving is the safe way. the
    // infinite ones are 99999999 and the sum stops there, which is as good as all of it
    private static Map<Item, Integer> reserveFor(ItemTarget[] targets) {
        Map<Item, Integer> out = new HashMap<>();
        for (ItemTarget target : targets) {
            for (Item match : target.getMatches()) {
                out.merge(match, target.getTargetCount(), (a, b) -> (int) Math.min((long) a + b, 1L << 30));
            }
        }
        return out;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.itemTargetsMetInventoryNoCursor(mod, _itemTargets);
    }

    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        // We have an important item target in our cursor.
        return StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && !isFinished(mod)
                // This _should_ be redundant, but it'll be a guard just to make 100% sure.
                && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()));
    }

    @Override
    protected void onStart(AltoClef mod) {
        _level = mod.getBehaviour().push();
        _containerLookupCooldown = 0;
        //removeThrowawayItems(_itemTargets);
        if (_mineIfPresent != null) {
            mod.getBlockTracker().trackBlock(_mineIfPresent);
        }
        onResourceStart(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_allMatches == null) {
            _allMatches = ItemTarget.getMatches(_itemTargets);
            _reserve = reserveFor(_itemTargets);
        }
        // protected, but only up to the count we're after: the walk out of the hole may build with cobble above it
        mod.getBehaviour().reserveProtectedItems(_level, _reserve);
        // If we have an item in an INACCESSIBLE inventory slot
        if (!ITaskUsesCraftingGrid.isUsingGrid(this) || _ensureFreeCraftingGridTask.isActive()) {
            for (ItemTarget target : _itemTargets) {
                if (StorageHelper.isItemInaccessibleToContainer(mod, target)) {
                    setDebugState("Moving from SPECIAL inventory slot");
                    return new MoveInaccessibleItemToInventoryTask(target);
                }
            }
        }
        // We have enough items COUNTING the cursor slot, we just need to move an item from our cursor.
        if (StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()))) {
            setDebugState("Moving from cursor");
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, ClickType.PICKUP);
                return null;
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
                return null;
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ClickType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            return null;
        }

        if (!shouldAvoidPickingUp(mod)) {
            // Check if items are on the floor. If so, pick em up.
            if (mod.getEntityTracker().itemDropped(_itemTargets)) {

                // If we're picking up a pickaxe (we can't go far underground or mine much)
                if (PickupDroppedItemTask.isIsGettingPickaxeFirst(mod)) {
                    if (_pickupTask.isCollectingPickaxeForThis()) {
                        setDebugState("Picking up (pickaxe first!)");
                        // Our pickup task is the one collecting the pickaxe, keep it going.
                        return _pickupTask;
                    }
                    // Only get items that are CLOSE to us.
                    Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), _itemTargets);
                    if (closest.isPresent() && !closest.get().closerThan(mod.getPlayer(), 10)) {
                        return onResourceTick(mod);
                    }
                }

                double range = Baritone.settings().altoResourcePickupDropRange.value;
                // the tracker remembers drops from anywhere, only the ones worth the walk count (a far drop of something
                // we can craft right now is not worth leaving the hole for). asking for the closest of the OK ones also
                // means the pickup task stops chasing once the near drops are gone
                Vec3 me = mod.getPlayer().position();
                boolean craftable = craftableFromHeld(mod);
                Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(me,
                        drop -> WalkCost.dropWorthWalking(drop.getX() - me.x, drop.getY() - me.y, drop.getZ() - me.z, craftable), _itemTargets);
                if (closest.isPresent() && (range < 0 || closest.get().closerThan(mod.getPlayer(), range) || (_pickupTask.isActive() && !_pickupTask.isFinished(mod)))) {
                    setDebugState("Picking up");
                    return _pickupTask;
                }
            }
        }

        // Check for chests and grab resources from them.
        // no containers known is the common case, and the lookup walks every cached one, so ask once a second at most
        if (_currentContainer == null && --_containerLookupCooldown <= 0 && mod.getItemStorage().hasAnyContainers()) {
            _containerLookupCooldown = CONTAINER_LOOKUP_TICKS;
            List<ContainerCache> containersWithItem = mod.getItemStorage().getContainersWithItem(_allMatches).stream()
                    .filter(cache -> cache.getContainerType().lootable()).toList();
            if (!containersWithItem.isEmpty()) {
                ContainerCache closest = containersWithItem.stream().min(StlHelper.compareValues(container -> container.getBlockPos().distToCenterSqr(mod.getPlayer().position()))).get();
                if (closest.getBlockPos().closerToCenterThan(mod.getPlayer().position(), Baritone.settings().altoResourceChestLocateRange.value)) {
                    _currentContainer = closest;
                }
            }
        }
        if (_currentContainer != null) {
            Optional<ContainerCache> container = mod.getItemStorage().getContainerAtPosition(_currentContainer.getBlockPos());
            if (container.isPresent()) {
                if (Arrays.stream(_itemTargets).noneMatch(target -> container.get().hasItem(target.getMatches()))) {
                    _currentContainer = null;
                } else {
                    // We have a current chest, grab from it.
                    setDebugState("Picking up from container");
                    return new PickupFromContainerTask(_currentContainer.getBlockPos(), _itemTargets);
                }
            } else {
                _currentContainer = null;
            }
        }

        // We may just mine if a block is found.
        if (_mineIfPresent != null) {
            ArrayList<Block> satisfiedReqs = new ArrayList<>(Arrays.asList(_mineIfPresent));
            satisfiedReqs.removeIf(block -> !StorageHelper.miningRequirementMet(mod, MiningRequirement.getMinimumRequirementForBlock(block)));
            if (!satisfiedReqs.isEmpty()) {
                if (mod.getBlockTracker().anyFound(satisfiedReqs.toArray(Block[]::new))) {
                    Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), _mineIfPresent);
                    if (closest.isPresent() && closest.get().closerToCenterThan(mod.getPlayer().position(), Baritone.settings().altoResourceMineRange.value)) {
                        _mineLastClosest = closest.get();
                    }
                    if (_mineLastClosest != null) {
                        if (_mineLastClosest.closerToCenterThan(mod.getPlayer().position(), Baritone.settings().altoResourceMineRange.value * 1.5 + 20)) {
                            return new MineAndCollectTask(_itemTargets, _mineIfPresent, MiningRequirement.HAND);
                        }
                    }
                }
            }
        }
        // Make sure that items don't get stuck in the player crafting grid. May be an issue if a future task isn't a resource task.
        if (StorageHelper.isPlayerInventoryOpen()) {
            boolean gridHasItems = false;
            for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                if (!StorageHelper.getItemStackInSlot(slot).isEmpty()) {
                    gridHasItems = true;
                    break;
                }
            }
            if (EnsureFreePlayerCraftingGridTask.shouldClear(ITaskUsesCraftingGrid.isUsingGrid(this),
                    _ensureFreeCraftingGridTask.isActive(), gridHasItems, !StorageHelper.getItemStackInCursorSlot().isEmpty())) {
                return _ensureFreeCraftingGridTask;
            }
        }
        return onResourceTick(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        _level = null;
        if (_mineIfPresent != null) {
            mod.getBlockTracker().stopTracking(_mineIfPresent);
        }
        onResourceStop(mod, interruptTask);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof ResourceTask t) {
            if (!isEqualResource(t)) return false;
            return Arrays.equals(t._itemTargets, _itemTargets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append(toDebugStringName()).append(": [");
        int c = 0;
        if (_itemTargets != null) {
            for (ItemTarget target : _itemTargets) {
                result.append(target != null ? target.toString() : "(null)");
                if (++c != _itemTargets.length) {
                    result.append(", ");
                }
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected String toHudString() {
        // the verb is a guess at this level, subclasses that mine or craft say so
        return "Getting " + HudText.items(_itemTargets);
    }

    protected boolean isInWrongDimension(AltoClef mod) {
        if (_forceDimension) {
            return WorldHelper.getCurrentDimension() != _targetDimension;
        }
        return false;
    }

    protected Task getToCorrectDimensionTask(AltoClef mod) {
        return new DefaultGoToDimensionTask(_targetDimension);
    }

    public ResourceTask mineIfPresent(Block[] toMine) {
        _mineIfPresent = toMine;
        return this;
    }

    public ResourceTask forceDimension(Dimension dimension) {
        _forceDimension = true;
        _targetDimension = dimension;
        return this;
    }

    // we could make the thing we are after right now from what is in the bag, so a drop of it is only worth a short walk
    // (WalkCost.dropWorthWalking). crafting tasks say yes when the materials are held
    protected boolean craftableFromHeld(AltoClef mod) {
        return false;
    }

    protected abstract boolean shouldAvoidPickingUp(AltoClef mod);

    protected abstract void onResourceStart(AltoClef mod);

    protected abstract Task onResourceTick(AltoClef mod);

    protected abstract void onResourceStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqualResource(ResourceTask other);

    protected abstract String toDebugStringName();

    public ItemTarget[] getItemTargets() {
        return _itemTargets;
    }
}
