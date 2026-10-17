package adris.altoclef.tasks.squashed;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.BotBehaviour;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.CollectIronIngotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CatalogueBook;
import adris.altoclef.util.helpers.RecipeBill;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// get things the way you'd do it by hand: work out the whole shopping list first (RecipeBill), grab the tools the job calls
// for, fetch every raw material in one go, then smelt and craft bottom up. the old way (CataloguedResourceTask straight
// away) had each recipe level replan from the bag by itself and kept going back out for the iron somebody forgot about.
// anything the bill can't plan (mob drops, nether stuff, custom tasks) or anything still short at the end is the old way
// anyway: the last stage is the plain catalogue task for the targets, so this is never worse than before
public class GatherBillTask extends Task {

    // "craft as many as you can" targets are 99999999. nobody is mining that, the plain task handles those
    private static final int BILL_CAP = 4096;
    // room left over for whatever the crafts spit out while the leaves are in the bag
    private static final int SLOTS_KEPT_FREE = 2;
    private static final Block[] STATIONS = {Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE};
    // how long the first bill waits for the tracker's first look at the stations. before it, "no table around" only means
    // "haven't looked", and that bill would send us off for a pickaxe and 8 cobble for a furnace that's standing right there
    private static final double FIRST_SCAN_SECONDS = 3;

    private enum Stage {TOOLS, LEAVES, LEVELS, FINAL}

    private final ItemTarget[] _targets;
    private final Map<String, Integer> _wanted = new LinkedHashMap<>();
    private final boolean _billable;
    private Stage _stage = Stage.TOOLS;
    private Task _current;
    // each tool and each made thing gets its one go, a stage that's done is never looked at again
    private final Set<String> _triedTools = new HashSet<>();
    private final Set<String> _made = new HashSet<>();
    private final Map<Item, Integer> _reserved = new HashMap<>();
    // the leaves we're out fetching right now, so progress() can see the bag fill up before anything is crafted
    private List<RecipeBill.Fetch> _fetching = List.of();
    // same for the tool or the level being made right now. 128 stone out of a furnace is a long time with no new stage
    private List<ItemTarget> _making = List.of();
    private BotBehaviour.State _level;
    private final TimerGame _scanWait = new TimerGame(FIRST_SCAN_SECONDS);
    private boolean _looked;

    public GatherBillTask(ItemTarget... targets) {
        _targets = targets;
        boolean billable = targets.length > 0;
        for (ItemTarget target : targets) {
            String name = target == null ? null : CatalogueBook.canonical(target);
            if (name == null || target.getTargetCount() > BILL_CAP) {
                billable = false;
                break;
            }
            _wanted.merge(name, target.getTargetCount(), Integer::sum);
        }
        _billable = billable;
        if (!billable) {
            _stage = Stage.FINAL;
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        _level = mod.getBehaviour().push();
        // back from a fight or a meal: the stage picks up where it was, so does what it was keeping safe
        if (!_reserved.isEmpty()) {
            mod.getBehaviour().reserveProtectedItems(_level, _reserved);
        }
        mod.getBlockTracker().trackBlock(STATIONS);
        _scanWait.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_looked && _stage != Stage.FINAL) {
            if (!mod.getBlockTracker().hasBeenScanned(STATIONS) && !_scanWait.elapsed()) {
                setDebugState("Looking around for a table and a furnace");
                return null;
            }
            _looked = true;
        }
        // a few stages can be empty in a row (no tools needed, nothing to fetch), skip through them in the same tick
        for (int i = 0; i < 8; i++) {
            if (_current != null && !_current.isFinished(mod)) {
                return _current;
            }
            _current = next(mod);
            if (_current == null) {
                return null;
            }
        }
        return _current;
    }

    private Task next(AltoClef mod) {
        // the up-front list is overworld stuff: the leaves are overworld mines, and gold in the nether comes from
        // CollectGoldIngotTask's nugget route, not raw gold. anywhere else it's the plain task like before
        if (_stage != Stage.FINAL && WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            _stage = Stage.FINAL;
        }
        switch (_stage) {
            case TOOLS -> {
                Task tool = nextTool(mod);
                if (tool != null) return tool;
                _stage = Stage.LEAVES;
                return next(mod);
            }
            case LEAVES -> {
                _stage = Stage.LEVELS;
                Task leaves = leaves(mod);
                return leaves != null ? leaves : next(mod);
            }
            case LEVELS -> {
                Task level = nextLevel(mod);
                if (level != null) return level;
                _stage = Stage.FINAL;
                return next(mod);
            }
            default -> {
                setDebugState("Finishing up the old way");
                return _targets.length == 1 ? TaskCatalogue.getItemTask(_targets[0]) : TaskCatalogue.getSquashedItemTask(_targets);
            }
        }
    }

    // the bill is worked out fresh at the start of every stage, from whatever the bag holds by then. a death or a fight
    // in between just means a different bill
    private RecipeBill.Bill bill(AltoClef mod) {
        CatalogueBook book = new CatalogueBook(items -> mod.getItemStorage().getItemCount(items));
        RecipeBill.World world = new RecipeBill.World(stationNear(mod, Blocks.CRAFTING_TABLE), stationNear(mod, Blocks.FURNACE),
                otherFuelSmelts(mod), n -> CollectIronIngotTask.wouldBuildBlast(n, mod));
        return RecipeBill.of(_wanted, book, world);
    }

    // tools first, so a pickaxe craft halfway through the gathering doesn't eat the cobble and sticks we just fetched
    private Task nextTool(AltoClef mod) {
        RecipeBill.Bill bill = bill(mod);
        for (String tool : RecipeBill.tools(bill.leaves, toolBag(mod))) {
            if (!_triedTools.add(tool) || !TaskCatalogue.taskExists(tool)) continue;
            reserve(mod, tool, 1);
            _making = List.of(new ItemTarget(tool, 1));
            setDebugState("Getting a " + tool + " for the job");
            return TaskCatalogue.getItemTask(tool, 1);
        }
        return null;
    }

    private Task leaves(AltoClef mod) {
        RecipeBill.Bill bill = bill(mod);
        List<RecipeBill.Fetch> fetch = RecipeBill.fitBag(bill.leaves, freeSlots(mod) - SLOTS_KEPT_FREE, GatherBillTask::stackSize);
        if (fetch.isEmpty()) {
            return null;
        }
        _fetching = fetch;
        List<ItemTarget> targets = new ArrayList<>();
        for (RecipeBill.Fetch f : fetch) {
            targets.add(new ItemTarget(f.name(), f.target()));
            reserve(mod, f.name(), f.target());
        }
        setDebugState("Fetching the raw materials");
        // not squashed: the order is the bill's, lowest pickaxe first
        return new CataloguedResourceTask(false, targets.toArray(ItemTarget[]::new));
    }

    // the lowest level with something still to make. a target nobody else wants waits for the final stage
    private Task nextLevel(AltoClef mod) {
        RecipeBill.Bill bill = bill(mod);
        for (List<RecipeBill.Node> level : bill.levels) {
            List<ItemTarget> todo = new ArrayList<>();
            for (RecipeBill.Node node : level) {
                if (_made.contains(node.name()) || node.gross() <= _wanted.getOrDefault(node.name(), 0)) continue;
                todo.add(new ItemTarget(node.name(), node.gross()));
            }
            if (todo.isEmpty()) continue;
            for (ItemTarget t : todo) _made.add(t.getCatalogueName());
            _making = todo;
            setDebugState("Making the in-betweens");
            return TaskCatalogue.getSquashedItemTask(todo.toArray(ItemTarget[]::new));
        }
        return null;
    }

    // a table or furnace we'd use instead of making one: standing within STATION_NEAR, reachable, not coming down.
    // same line DoStuffInContainerTask goes by for a world one, close enough for a shopping list
    private static boolean stationNear(AltoClef mod, Block block) {
        Vec3 me = mod.getPlayer().position();
        return mod.getBlockTracker().getNearestTracking(me,
                p -> WorldHelper.canReach(mod, p) && !StationHook.pickingUp(p)
                        && WalkCost.stationDistance(p.getX(), p.getY(), p.getZ(), me.x, me.y, me.z) <= WalkCost.STATION_NEAR,
                block).isPresent();
    }

    // fuel in the bag that isn't coal, in smelts. the coal is a node of the bill and gets netted there
    private static int otherFuelSmelts(AltoClef mod) {
        int all = (int) Math.floor(StorageHelper.calculateInventoryFuelCount(mod));
        int coal = AltoSettings.isSupportedFuel(Items.COAL) ? 8 * mod.getItemStorage().getItemCount(Items.COAL) : 0;
        return Math.max(0, all - coal);
    }

    private static RecipeBill.ToolBag toolBag(AltoClef mod) {
        int pick = StorageHelper.getCurrentMiningRequirement(mod).ordinal();
        int axe = tier(mod, Items.WOODEN_AXE, Items.GOLDEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
        int shovel = tier(mod, Items.WOODEN_SHOVEL, Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL);
        return new RecipeBill.ToolBag() {
            @Override
            public int pickTier() {
                return pick;
            }

            @Override
            public int axeTier() {
                return axe;
            }

            @Override
            public int shovelTier() {
                return shovel;
            }
        };
    }

    // wood, gold, stone, iron, diamond, netherite -> 1, 1, 2, 3, 4, 5. -1 for none
    private static int tier(AltoClef mod, Item... byTier) {
        int[] tiers = {1, 1, 2, 3, 4, 5};
        int best = -1;
        for (int i = 0; i < byTier.length; i++) {
            if (mod.getItemStorage().hasItem(byTier[i])) best = Math.max(best, tiers[i]);
        }
        return best;
    }

    private static int freeSlots(AltoClef mod) {
        int free = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        return free;
    }

    private static int stackSize(String name) {
        Item[] matches = TaskCatalogue.getItemMatches(name);
        return matches.length == 0 ? 64 : new ItemStack(matches[0]).getMaxStackSize();
    }

    // the junk sweep leaves the fetched stuff and the tools alone till we're done. a count and not the whole item, so the
    // pather can still pillar with cobble past what the bill wants
    private void reserve(AltoClef mod, String name, int count) {
        for (Item item : TaskCatalogue.getItemMatches(name)) {
            _reserved.merge(item, count, Math::max);
        }
        mod.getBehaviour().reserveProtectedItems(_level, _reserved);
    }

    // a number that goes up while the bill gets somewhere, for a caller with a "stuck" clock (SchematicBuildTask's
    // GatherGuard). the targets only show up at the very end now, a whole batch's worth of ore and cobble comes first,
    // and that trip is not being stuck. stages and the two sets only ever grow, so does this, minus crafting eating leaves
    public long progress(AltoClef mod) {
        long held = 0;
        for (RecipeBill.Fetch f : _fetching) {
            held += Math.min(mod.getItemStorage().getItemCount(TaskCatalogue.getItemMatches(f.name())), f.target());
        }
        for (ItemTarget t : _making) {
            held += Math.min(mod.getItemStorage().getItemCount(t.getMatches()), t.getTargetCount());
        }
        return (_stage.ordinal() + _triedTools.size() + _made.size()) * 1_000_000L + held;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // not counting the cursor, the same as the plain ResourceTask: done means put away
        return StorageHelper.itemTargetsMetInventoryNoCursor(mod, _targets);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(STATIONS);
        mod.getBehaviour().pop();
        _level = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GatherBillTask task && Arrays.equals(task._targets, _targets);
    }

    @Override
    protected String toDebugString() {
        return "Gather bill (" + _stage + "): " + Arrays.toString(_targets);
    }

    @Override
    protected String toHudString() {
        return "Getting " + HudText.items(_targets);
    }
}
