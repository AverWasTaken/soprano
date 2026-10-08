package adris.altoclef.tasks.movement;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.FreeSlotPlan;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.progresscheck.WaterPickupWatchdog;
import adris.altoclef.ui.HudText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.phys.Vec3;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PickupDroppedItemTask extends AbstractDoToClosestObjectTask<ItemEntity> implements ITaskRequiresGrounded {
    private static final Task getPickaxeFirstTask = new SatisfyMiningRequirementTask(MiningRequirement.STONE);
    // Not clean practice, but it helps keep things self contained I think.
    private static boolean isGettingPickaxeFirstFlag = false;
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final WaterPickupWatchdog _waterWatchdog = new WaterPickupWatchdog();
    private final ItemTarget[] _itemTargets;

    // This happens all the time in mineshafts and swamps/jungles
    private final Set<ItemEntity> _blacklist = new HashSet<>();
    private final boolean _freeInventoryIfFull;
    private Task _unstuckTask = null;
    // Am starting to regret not making this a singleton
    private AltoClef _mod;
    private boolean _collectingPickaxeForThisResource = false;
    private ItemEntity _currentDrop = null;

    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull) {
        _itemTargets = itemTargets;
        _freeInventoryIfFull = freeInventoryIfFull;
    }

    public PickupDroppedItemTask(ItemTarget target, boolean freeInventoryIfFull) {
        this(new ItemTarget[]{target}, freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount, boolean freeInventoryIfFull) {
        this(new ItemTarget(item, targetCount), freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount) {
        this(item, targetCount, true);
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

    public static boolean isIsGettingPickaxeFirst(AltoClef mod) {
        return isGettingPickaxeFirstFlag && Baritone.settings().altoCollectPickaxeFirst.value;
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

    public boolean isCollectingPickaxeForThis() {
        return _collectingPickaxeForThisResource;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _wanderTask.reset();
        _progressChecker.reset();
        stuckCheck.reset();
        _waterWatchdog.reset();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            return _wanderTask;
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_progressChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        _mod = mod;

        // wet and not getting closer to the thing we are chasing: let it go, whatever the reason. this stays on even
        // with altoPickupItemsInWater, that setting only says whether we try at all
        if (_currentDrop != null && isValid(mod, _currentDrop)
                && _waterWatchdog.update(_currentDrop, mod.getPlayer().isInWater(), mod.getPlayer().distanceTo(_currentDrop), WorldHelper.getTicks())) {
            Debug.logInternal("Giving up on " + _currentDrop.getItem().getItem().getDescriptionId() + ", in water and not getting closer");
            _blacklist.add(_currentDrop);
            mod.getEntityTracker().banEntity(_currentDrop);
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _currentDrop = null;
            _waterWatchdog.reset();
            _progressChecker.reset();
            setDebugState("Skipping items in water");
            return null;
        }

        // If we're getting a pickaxe for THIS resource...
        if (isIsGettingPickaxeFirst(mod) && _collectingPickaxeForThisResource && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            _progressChecker.reset();
            setDebugState("Collecting pickaxe first");
            return getPickaxeFirstTask;
        } else {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                isGettingPickaxeFirstFlag = false;
            }
            _collectingPickaxeForThisResource = false;
        }

        if (!_progressChecker.check(mod)) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            if (_currentDrop != null && !_currentDrop.getItem().isEmpty()) {
                // We might want to get a pickaxe first.
                if (!isGettingPickaxeFirstFlag && Baritone.settings().altoCollectPickaxeFirst.value && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                    Debug.logMessage("Failed to pick up drop, will try to collect a stone pickaxe first and try again!");
                    _collectingPickaxeForThisResource = true;
                    isGettingPickaxeFirstFlag = true;
                    return getPickaxeFirstTask;
                }
                Debug.logMessage(StlHelper.toString(_blacklist, element -> element == null ? "(null)" : element.getItem().getItem().getDescriptionId()));
                Debug.logMessage("Failed to pick up drop, suggesting it's unreachable.");
                _blacklist.add(_currentDrop);
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                return _wanderTask;
            }
        }

        return super.onTick(mod);
    }


    // the drop we were walking to is gone because it went into our bag. that is the happy ending, but the search below reads
    // "nothing left to pick up" as lost, wanders for a tick and the parent takes the wander for a failure (the log showed a
    // Wander task start and die after every single kill, the pickups all worked). a parent that asks gets a plain yes instead
    @Override
    public boolean isFinished(AltoClef mod) {
        if (_currentDrop == null || _blacklist.contains(_currentDrop)) {
            return false;
        }
        boolean gone = !_currentDrop.isAlive() || _currentDrop.getItem().isEmpty();
        return ItemPickupRules.collected(gone, mod.getPlayer().distanceTo(_currentDrop)) && getClosestTo(mod, getOriginPos(mod)).isEmpty();
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof PickupDroppedItemTask task) {
            return Arrays.equals(task._itemTargets, _itemTargets) && task._freeInventoryIfFull == _freeInventoryIfFull;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append("Pickup Dropped Items: [");
        int c = 0;
        for (ItemTarget target : _itemTargets) {
            result.append(target.toString());
            if (++c != _itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected String toHudString() {
        return "Picking up " + HudText.some(_itemTargets);
    }

    @Override
    protected Vec3 getPos(AltoClef mod, ItemEntity obj) {
        if (!obj.onGround() && !obj.isInWater()) {
            // Assume we'll land down one or two blocks from here. We could do this more advanced but whatever.
            BlockPos p = obj.blockPosition();
            if (!WorldHelper.isSolid(mod, p.below(3))) {
                return obj.position().subtract(0, 2, 0);
            }
            return obj.position().subtract(0, 1, 0);
        }
        return obj.position();
    }

    @Override
    protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
        return mod.getEntityTracker().getClosestItemDrop(
                pos,
                _itemTargets);
    }

    @Override
    protected Vec3 getOriginPos(AltoClef mod) {
        return mod.getPlayer().position();
    }

    @Override
    protected Task getGoalTask(ItemEntity itemEntity) {
        if (!itemEntity.equals(_currentDrop)) {
            _currentDrop = itemEntity;
            _progressChecker.reset();
            if (isGettingPickaxeFirstFlag && _collectingPickaxeForThisResource) {
                Debug.logMessage("New goal, no longer collecting a pickaxe.");
                _collectingPickaxeForThisResource = false;
                isGettingPickaxeFirstFlag = false;
            }
        }
        // Ensure our inventory is free if we're close
        boolean touching = _mod.getEntityTracker().isCollidingWithPlayer(itemEntity);
        if (touching) {
            if (_freeInventoryIfFull) {
                boolean fits = hasRoomFor(_mod, itemEntity.getItem());
                ItemPickupRules.Room room = ItemPickupRules.room(fits, canMakeRoom(_mod));
                if (room == ItemPickupRules.Room.MAKE_ROOM) {
                    return new EnsureFreeInventorySlotTask();
                }
                if (room == ItemPickupRules.Room.GIVE_UP) {
                    Debug.logMessage("Giving up on " + itemEntity.getItem().getItem().getDescriptionId() + ", the bag is full of things we keep.");
                    _blacklist.add(itemEntity);
                    _mod.getEntityTracker().banEntity(itemEntity);
                    _currentDrop = null;
                    _progressChecker.reset();
                    return null;
                }
            }
        }
        return new GetToEntityTask(itemEntity);
    }

    private static boolean hasRoomFor(AltoClef mod, ItemStack drop) {
        NonNullList<ItemStack> slots = mod.getPlayer().getInventory().items;
        int[] counts = new int[slots.size()];
        boolean[] sameKind = new boolean[slots.size()];
        for (int i = 0; i < counts.length; i++) {
            ItemStack there = slots.get(i);
            counts[i] = there.isEmpty() ? 0 : there.getCount();
            sameKind[i] = ItemStack.isSameItemSameComponents(there, drop);
        }
        return ItemPickupRules.hasRoom(counts, sameKind, drop.getMaxStackSize());
    }

    // same question EnsureFreeInventorySlotTask asks, no container because we are standing in the open
    private static boolean canMakeRoom(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        return FreeSlotPlan.plan(cursor.isEmpty(), ItemHelper.canThrowAwayStack(mod, cursor),
                StorageHelper.getGarbageSlot(mod).isPresent(), false) != FreeSlotPlan.Action.STUCK;
    }

    @Override
    protected boolean isValid(AltoClef mod, ItemEntity obj) {
        if (!obj.isAlive() || _blacklist.contains(obj) || !mod.getEntityTracker().isEntityReachable(obj)) return false;
        // it can end up in the water after we picked it as the target (broke the block over a lake), the tracker has
        // already forgotten it by then so we have to drop it ourselves
        return Baritone.settings().altoPickupItemsInWater.value || ItemPickupRules.isPickupSafe(obj);
    }

}
