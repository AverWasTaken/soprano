package adris.altoclef.tasks.container;

import adris.altoclef.ui.HudText;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import baritone.api.utils.BlockRange;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class StoreInStashTask extends Task {

    // There's a lot of code duplication here...
    private static final Block[] TO_SCAN = Stream.concat(Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}), Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))).toArray(Block[]::new);
    private final ItemTarget[] _toStore;
    private final boolean _getIfNotPresent;
    private final BlockRange _stashRange;
    private ContainerStoredTracker _storedItems;
    // the containers we had open during this run. only these count as full: one the cache remembers full from an
    // earlier visit may have been emptied since (a hopper under it, a sorter), so it gets another look first
    private final Set<BlockPos> _lookedInto = new HashSet<>();
    // set once every container in the stash was looked into this run and none had room. null while there's hope
    private String _failure;

    public StoreInStashTask(boolean getIfNotPresent, BlockRange stashRange, ItemTarget... toStore) {
        _getIfNotPresent = getIfNotPresent;
        _stashRange = stashRange;
        _toStore = toStore;
    }

    // null when it stored everything (or is still at it)
    public String failure() {
        return _failure;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(TO_SCAN);
        if (_storedItems == null) {
            _storedItems = new ContainerStoredTracker(slot -> {
                Optional<BlockPos> currentContainer = mod.getItemStorage().getLastBlockPosInteraction();
                return currentContainer.isPresent() && _stashRange.contains(currentContainer.get(), WorldHelper.getCurrentDimension());
            });
        }
        _storedItems.startTracking();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Get more if we don't have & "get if not present" is true.
        if (_getIfNotPresent) {
            for (ItemTarget target : _toStore) {
                int inventoryNeed = target.getTargetCount() - _storedItems.getStoredCount(target.getMatches());
                if (inventoryNeed > mod.getItemStorage().getItemCount(target)) {
                    return TaskCatalogue.getItemTask(new ItemTarget(target, inventoryNeed));
                }
            }
        }

        // the last interaction is only there while the screen is open, so it gets caught every tick
        mod.getItemStorage().getLastBlockPosInteraction().ifPresent(pos -> {
            if (_stashRange.contains(pos, WorldHelper.getCurrentDimension())) {
                _lookedInto.add(pos.immutable());
            }
        });

        Predicate<BlockPos> inStash = blockPos -> _stashRange.contains(blockPos, WorldHelper.getCurrentDimension());
        Predicate<BlockPos> validContainer = blockPos -> {
            if (!inStash.test(blockPos))
                return false;
            Optional<ContainerCache> container = mod.getItemStorage().getContainerAtPosition(blockPos);
            return hasRoom(_lookedInto.contains(blockPos), container.map(ContainerCache::isFull).orElse(null));
        };

        // Store in valid container
        if (mod.getBlockTracker().anyFound(validContainer, TO_SCAN)) {
            setDebugState("Storing in closest stash container");
            return new DoToClosestBlockTask(
                    (BlockPos bpos) -> new StoreInContainerTask(bpos, false, _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore)),
                    validContainer,
                    TO_SCAN
            );
        }

        // there are containers here and we've been inside every one of them: all full. walking to the middle again
        // would just loop, so say so and let whoever started us decide (#stash ends, a build job carries on)
        if (mod.getBlockTracker().anyFound(inStash, TO_SCAN)) {
            _failure = "every container in the stash is full";
            Debug.logWarning("The stash is full, nothing more fits in it.");
            return null;
        }

        setDebugState("Traveling to stash (no containers found in stash range yet)");
        BlockPos centerStash = _stashRange.getCenter();
        return new GetToXZTask(centerStash.getX(), centerStash.getZ());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(TO_SCAN);
        _storedItems.stopTracking();
    }

    // worth walking to: never looked into this run, never opened at all, or it had room last time we looked
    static boolean hasRoom(boolean lookedIntoThisRun, Boolean fullLastTime) {
        return !lookedIntoThisRun || fullLastTime == null || !fullLastTime;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_failure != null) {
            return true;
        }
        return _storedItems != null && _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore).length == 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StoreInStashTask task) {
            return task._stashRange.equals(_stashRange) && task._getIfNotPresent == _getIfNotPresent && Arrays.equals(task._toStore, _toStore);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Storing " + HudText.some(_toStore) + " in the stash";
    }

    @Override
    protected String toDebugString() {
        return "Storing in stash" + _stashRange + ": " + Arrays.toString(_toStore);
    }
}
