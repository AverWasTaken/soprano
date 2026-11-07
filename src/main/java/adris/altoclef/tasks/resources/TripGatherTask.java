package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;

// what a yes to "may I collect some blocks?" turns into (baritone's TripBehavior asks, TripGatherBridge starts this).
// the pickaxe first when one is wanted, cobble comes a lot faster after. then blocks until the bag holds `target` of
// the items. the blocks to mine are handed in because the items' own blocks are the wrong answer: nobody finds
// cobblestone lying around, they find stone
public class TripGatherTask extends Task {

    // GatherFeasibility.SCAN_CHUNKS in blocks, the range the question promised there was dirt in
    private static final int HAND_RANGE = 96;

    private final Item[] _items;
    private final Block[] _sources;
    // the sources that come out by hand (dirt, grass). no pickaxe means these first
    private final Block[] _hand;
    private final int _target;
    // the deficit the question asked about. _target is held + this, so held at the start is _target - _need
    private final int _need;
    private final boolean _pickaxe;
    // once wide, always wide. otherwise a pickaxe snapping mid gather flips us back to dirt and restarts the mining
    private boolean _wide;

    public TripGatherTask(Item[] items, Block[] sources, Block[] hand, int target, boolean pickaxe) {
        this(items, sources, hand, target, target, pickaxe);
    }

    public TripGatherTask(Item[] items, Block[] sources, Block[] hand, int target, int need, boolean pickaxe) {
        _items = items;
        _sources = sources;
        _hand = hand;
        _target = target;
        _need = Math.max(0, Math.min(need, target));
        _pickaxe = pickaxe;
    }

    // for the corner toast's "12/24". blocks placed or thrown since the start count against it, same as haveBlocks does
    public int need() {
        return _need;
    }

    public int have(AltoClef mod) {
        return collected(mod.getItemStorage().getItemCount(_items), _target, _need);
    }

    static int collected(int held, int target, int need) {
        return Math.max(0, Math.min(need, held - (target - need)));
    }

    public boolean makingPickaxe(AltoClef mod) {
        return _pickaxe && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE);
    }

    // still on the dirt by hand, or off to the trees and stone
    public boolean mining() {
        return _wide;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // our own hold on the hand blocks, so "none around" is a real answer and not just "nobody asked yet". _wide is not
        // reset here on purpose: after an interrupt the tracker has forgotten, "no dirt" turns back into "haven't looked"
        // and we'd be off digging for dirt that isn't there all over again
        if (_hand.length > 0) {
            mod.getBlockTracker().trackBlock(_hand);
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_pickaxe && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            return new SatisfyMiningRequirementTask(MiningRequirement.STONE);
        }
        if (!haveBlocks(mod)) {
            _wide = wide(_wide, _hand.length > 0, handGone(mod));
            if (!_wide) {
                // the target still counts every item, dirt included. we just don't go make a pickaxe to get them
                return new MineAndCollectTask(new ItemTarget(_items, _target), _hand, MiningRequirement.HAND).noReserve();
            }
            // wood is enough for stone and netherrack, MineAndCollectTask goes and makes one itself if it has to
            return new MineAndCollectTask(new ItemTarget(_items, _target), _sources, MiningRequirement.WOOD).noReserve();
        }
        return null;
    }

    // dirt by hand until there's none left to dig, pickaxe or not, and only then the tree and the stone (the nether,
    // mostly: netherrack wants a pickaxe and there's no dirt). a pickaxe used to mean wide on the spot, which on the
    // first tick is before the dirt scan lands, so "nearest" was whatever stone the tracker knew of, grass underfoot or not
    static boolean wide(boolean wasWide, boolean anyHandSources, boolean handGone) {
        return wasWide || !anyHandSources || handGone;
    }

    // scanned and nothing reachable. before the first scan lands an empty tracker means "haven't looked", not "none"
    private boolean handGone(AltoClef mod) {
        if (_hand.length == 0) {
            return true;
        }
        if (!mod.getBlockTracker().hasBeenScanned(_hand)) {
            return false;
        }
        // same idea as GatherFeasibility: dirt a hundred blocks off is not quicker than a pickaxe and the stone underfoot.
        // and only what MineAndCollectTask would actually dig, or we'd sit in this phase while it wanders
        BlockPos me = mod.getPlayer().blockPosition();
        return !mod.getBlockTracker().anyFound(pos -> pos.distSqr(me) <= HAND_RANGE * HAND_RANGE && WorldHelper.canBreak(mod, pos), _hand);
    }

    private boolean haveBlocks(AltoClef mod) {
        return _target <= 0 || _items.length == 0 || mod.getItemStorage().getItemCount(_items) >= _target;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_hand.length > 0) {
            mod.getBlockTracker().stopTracking(_hand);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TripGatherTask task && task._target == _target && task._need == _need && task._pickaxe == _pickaxe
                && Arrays.equals(task._items, _items) && Arrays.equals(task._sources, _sources) && Arrays.equals(task._hand, _hand);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return (!_pickaxe || StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) && haveBlocks(mod);
    }

    @Override
    protected String toHudString() {
        return _pickaxe && _target > 0 ? "Getting blocks and a stone pickaxe for the path" : _pickaxe ? "Getting a stone pickaxe for the path" : "Getting blocks for the path";
    }

    @Override
    protected String toDebugString() {
        return "Trip gather: " + _target + " of " + Arrays.toString(_items) + (_pickaxe ? " + stone pickaxe" : "");
    }
}
