/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.BlockRange;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

// the other way round from StoreInStashTask: takes items out of the containers in a box until the inventory holds the
// targets. Effortless Soprano's storage source fetches a place job's blocks with it. every container gets looked into
// at most once a run (unless the cache says it still has some of what's wanted), and when they've all been looked into
// and something is still short, it finishes with failure() naming what. a short storage isn't a dead end: the job
// builds what it got
public class FetchFromStashTask extends Task {

    private static final Block[] TO_SCAN = Stream.concat(Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL}),
            Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))).toArray(Block[]::new);

    private final BlockRange range;
    private final ItemTarget[] wanted;
    // containers read since this started count as looked into, whatever opened them
    private long runStart;
    private String failure;

    // the targets are how many the inventory should hold at the end, not how many to take
    public FetchFromStashTask(BlockRange range, ItemTarget... wanted) {
        this.range = range;
        this.wanted = wanted;
    }

    // null when everything came, else what's still short
    public String failure() {
        return failure;
    }

    private ItemTarget[] remaining(AltoClef mod) {
        List<ItemTarget> out = new ArrayList<>();
        for (ItemTarget t : wanted) {
            // the player's own slots only: getItemCount also counts an open chest, and with the chest open every item in
            // it looked like ours already, so the task finished with nothing taken
            if (mod.getItemStorage().getItemCountInventoryOnly(t.getMatches()) < t.getTargetCount()) {
                out.add(t);
            }
        }
        return out.toArray(new ItemTarget[0]);
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (runStart == 0) {
            runStart = System.nanoTime();
        }
        mod.getBlockTracker().trackBlock(TO_SCAN);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemTarget[] left = remaining(mod);
        if (left.length == 0) {
            return null;
        }
        Predicate<BlockPos> inRange = pos -> range.contains(pos, WorldHelper.getCurrentDimension());
        Predicate<BlockPos> worthIt = pos -> {
            if (!inRange.test(pos)) {
                return false;
            }
            Optional<ContainerCache> cache = mod.getItemStorage().getContainerAtPosition(pos);
            boolean seenThisRun = cache.isPresent() && cache.get().seenAt() >= runStart;
            return worthALook(seenThisRun, cache.map(c -> Arrays.stream(left).anyMatch(t -> c.hasItem(t.getMatches()))).orElse(null));
        };
        if (mod.getBlockTracker().anyFound(worthIt, TO_SCAN)) {
            setDebugState("Taking " + HudText.some(left) + " out of the storage");
            return new DoToClosestBlockTask(pos -> new PickupFromContainerTask(pos, remaining(mod)), worthIt, TO_SCAN);
        }
        if (mod.getBlockTracker().anyFound(inRange, TO_SCAN)) {
            // been in every one and it's still short
            failure = "the storage is short of " + HudText.some(left);
            return null;
        }
        setDebugState("Going to the storage");
        BlockPos center = range.getCenter();
        return new GetToXZTask(center.getX(), center.getZ());
    }

    // never opened this run: look. opened and it has some of what's left: look again. opened and it has none: skip
    static boolean worthALook(boolean lookedIntoThisRun, Boolean hasSomeLastTime) {
        return !lookedIntoThisRun || hasSomeLastTime == null || hasSomeLastTime;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(TO_SCAN);
        // the last chest we looked into can still be open (a full one is left open while we decide it's full). whoever
        // runs next, the builder or a person, gets the game back without it in the way. only a container screen: the
        // click gui or a chat the player opened meanwhile stay
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return failure != null || remaining(mod).length == 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FetchFromStashTask t && t.range.equals(range) && Arrays.equals(t.wanted, wanted);
    }

    @Override
    protected String toHudString() {
        return "Fetching " + HudText.some(wanted) + " from the storage";
    }

    @Override
    protected String toDebugString() {
        return "Fetching from stash " + range + ": " + Arrays.toString(wanted);
    }
}
