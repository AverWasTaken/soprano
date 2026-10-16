package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;

// what a yes to "may I collect some blocks?" turns into (baritone's TripBehavior asks, TripGatherBridge starts this).
// the pickaxe first when one is wanted, cobble comes a lot faster after. then blocks until the bag holds `target` of
// the items. the blocks to mine are handed in because the items' own blocks are the wrong answer: nobody finds
// cobblestone lying around, they find stone
public class TripGatherTask extends Task {

    private final Item[] _items;
    private final Block[] _sources;
    private final int _target;
    private final boolean _pickaxe;

    public TripGatherTask(Item[] items, Block[] sources, int target, boolean pickaxe) {
        _items = items;
        _sources = sources;
        _target = target;
        _pickaxe = pickaxe;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_pickaxe && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            return new SatisfyMiningRequirementTask(MiningRequirement.STONE);
        }
        if (!haveBlocks(mod)) {
            // wood is enough for stone and netherrack, MineAndCollectTask goes and makes one itself if it has to
            return new MineAndCollectTask(new ItemTarget(_items, _target), _sources, MiningRequirement.WOOD);
        }
        return null;
    }

    private boolean haveBlocks(AltoClef mod) {
        return _target <= 0 || _items.length == 0 || mod.getItemStorage().getItemCount(_items) >= _target;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TripGatherTask task && task._target == _target && task._pickaxe == _pickaxe
                && Arrays.equals(task._items, _items) && Arrays.equals(task._sources, _sources);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return (!_pickaxe || StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) && haveBlocks(mod);
    }

    @Override
    protected String toHudString() {
        return _pickaxe && _target > 0 ? "Getting blocks and a pickaxe for the path" : _pickaxe ? "Getting a pickaxe for the path" : "Getting blocks for the path";
    }

    @Override
    protected String toDebugString() {
        return "Trip gather: " + _target + " of " + Arrays.toString(_items) + (_pickaxe ? " + stone pickaxe" : "");
    }
}
