package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.HotbarTools;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.FallingColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// #branchmine: a strip mine for players who don't want the bot pathing straight at ores it can't see. adapted from
// BranchMiningTask in Hackerokuz's altoclef fork (LegitMineFeature, https://github.com/Hackerokuz/altoclef/tree/LegitMineFeature, MIT).
// same pattern and same idea, rewritten for pathing instead of the builder, BranchPlan has the geometry
public class BranchMiningTask extends ResourceTask {
    // a new cell every 10 s is slow even for a wooden pick on deepslate with a gravel stack on it
    private static final double CELL_STALL_SECONDS = 10;
    // the stall clock starts over after every fight, so a cell nobody can dig with mobs that keep turning up would get a fresh 10 s
    // forever. this one doesn't start over until a cell actually clears
    private static final double CELL_TOTAL_SECONDS = 30;
    private static final double WALK_BACK_SECONDS = 120;
    // a shaft from the build limit to the bottom with some fights in it. only for when there's no way down at all
    private static final double DESCENT_SECONDS = 300;
    // DestroyBlockTask gives up by standing still (it bans the block and waits to be taken off it), this is for when it never does
    private static final double ORE_SECONDS = 45;
    private static final int BAD_SEGMENTS_TO_QUIT = 3;
    private static final int WALK_BACK_DISTANCE = 8;
    // walks back with not one new cell between them. a walk restarts its own clock, so without this a dig that keeps leading us away never ends
    private static final int WALKS_BACK_PER_CELL = 2;
    private static final int VEIN_LIMIT = 16;

    private final BranchOres.Ore _ore;
    private final int _count;
    private final int _y;
    private final Direction _facing;
    private final Block[] _blocks;
    private final MiningRequirement _tier;

    // null until we're standing at the right y. all of this has to outlive every interrupt, onStart runs again after each one
    private BranchPlan _plan;
    private BranchPlan.Segment _segment;
    // the segment had a bad cell in it and got cut there, so the line ends once this much is dug
    private boolean _cut;
    private DigCorridorTask _dig;
    private int _clearedBest;
    private int _walksBack;
    private final TaskClock _cellClock = new TaskClock();
    private final TaskClock _cellTotalClock = new TaskClock();

    private BlockPos _walkTo;
    private Task _walkTask;
    private final TaskClock _walkClock = new TaskClock();

    private final TaskClock _descentClock = new TaskClock();

    private final ArrayDeque<BlockPos> _vein = new ArrayDeque<>();
    private BlockPos _oreAt;
    private Task _oreTask;
    private final TaskClock _oreClock = new TaskClock();

    private int _badSegments;
    private int _oresMined;
    private boolean _startChecked;
    private boolean _done;

    public BranchMiningTask(BranchOres.Ore ore, int count, int y, Direction facing) {
        super(count > 0 ? new ItemTarget(itemsOf(ore), count) : new ItemTarget(itemsOf(ore)).infinite());
        _ore = ore;
        _count = count;
        _y = y;
        _facing = facing;
        _blocks = ore.blocks().stream().map(id -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id))).toArray(Block[]::new);
        _tier = MiningRequirement.valueOf(ore.tier().name());
    }

    // the drop, plus the ore blocks themselves: the tool chain (ToolPick) doesn't care about silk touch, so a silk pick hands back
    // the block and a count of diamonds alone would never be reached
    private static Item[] itemsOf(BranchOres.Ore ore) {
        List<Item> items = new ArrayList<>();
        items.add(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(ore.drop())));
        for (String block : ore.blocks()) {
            items.add(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(block)));
        }
        return items.toArray(Item[]::new);
    }

    // a known chest full of diamonds 400 blocks away is not what somebody asking for a strip mine had in mind
    @Override
    protected boolean usesContainers() {
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        boolean finished = super.isFinished(mod) || _done;
        // with altoRunsWhenIdle on, the chain drops a finished root without a stop. a diamond that lands in the bag mid tunnel would
        // leave the corridor rule up, and everything after us would find the whole world unbreakable. so we clean up our own mess
        if (finished && isActive()) {
            stop(mod);
        }
        return finished;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        // a fight doesn't count against the cell we were on
        _cellClock.reset();
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (_done) {
            return null;
        }
        // every tick: an iron pick is gone in about 3.5 branches
        if (!pickReady(mod)) {
            setDebugState("Getting a pick for " + _ore.name(), "Getting a pickaxe");
            return new SatisfyMiningRequirementTask(_tier);
        }
        HotbarTools.forBlocks(mod, _blocks);
        if (_plan == null) {
            return goToStart(mod);
        }
        Task ore = mineVein(mod);
        return ore != null ? ore : dig(mod);
    }

    // miningRequirementMet says a golden pick is good for the stone and iron tiers, and gold on diamond ore breaks it and drops nothing
    private boolean pickReady(AltoClef mod) {
        if (!StorageHelper.miningRequirementMet(mod, _tier)) {
            return false;
        }
        return switch (_ore.tier()) {
            case WOOD -> true;
            case STONE -> mod.getItemStorage().hasItem(Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
            case IRON -> mod.getItemStorage().hasItem(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        };
    }

    private Task goToStart(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        if (!_startChecked) {
            _startChecked = true;
            if (mod.getWorld().getBiome(new BlockPos(feet.getX(), _y, feet.getZ())).is(Biomes.DEEP_DARK)) {
                return finish("not branch mining, y " + _y + " right below here is the deep dark. try somewhere else");
            }
        }
        // GetToYTask's own isFinished is true in the middle of a fall through this y, and the first cell of the plan has to be real ground
        if (feet.getY() == _y && mod.getPlayer().onGround()) {
            _plan = new BranchPlan(feet, _facing);
            return null;
        }
        _descentClock.tick();
        if (_descentClock.over(DESCENT_SECONDS)) {
            return finish("not branch mining, couldn't get down to y " + _y);
        }
        setDebugState("Heading down to y " + _y, "Digging down");
        return new GetToYTask(_y);
    }

    // the walls we just dug past, block by block. asked again every tick since mining the block next door can open up lava
    private Task mineVein(AltoClef mod) {
        while (true) {
            if (_oreAt == null) {
                _oreAt = _vein.poll();
                if (_oreAt == null) {
                    return null;
                }
                _oreTask = new DestroyBlockTask(_oreAt);
                _oreClock.reset();
            }
            if (!isOre(mod, _oreAt)) {
                if (mod.getWorld().getBlockState(_oreAt).isAir()) {
                    _oresMined++;
                }
                _oreAt = null;
                continue;
            }
            _oreClock.tick();
            // canBreak has the ban in it, that's how a DestroyBlockTask that gave up gets taken off the block
            if (_oreClock.over(ORE_SECONDS) || !WorldHelper.canBreak(mod, _oreAt) || WorldHelper.dangerousToBreakIfRightAbove(mod, _oreAt)) {
                Debug.logInternal("branchmine: leaving " + _ore.name() + " at " + _oreAt.toShortString());
                _oreAt = null;
                continue;
            }
            setDebugState("Mining " + _ore.name() + " at " + _oreAt.toShortString(), "Mining the vein");
            return _oreTask;
        }
    }

    private boolean isOre(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return false;
        }
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        for (Block ore : _blocks) {
            if (block == ore) {
                return true;
            }
        }
        return false;
    }

    private Task dig(AltoClef mod) {
        if (_walkTo != null) {
            if (!mod.getPlayer().blockPosition().equals(_walkTo)) {
                return walkBack();
            }
            _walkTo = null;
            _cellClock.reset();
        }
        if (_segment == null) {
            return startSegment(mod);
        }
        List<BlockPos> cells = _segment.cells();
        int cleared = clearedCells(mod, cells);
        if (cleared >= cells.size()) {
            // before anything reads the world: the rule says everything off this line is unbreakable, and that includes the ores we want
            dropChild(mod);
            segmentOver(mod, cells, true);
            return null;
        }
        if (cleared > _clearedBest) {
            _clearedBest = cleared;
            _walksBack = 0;
            _cellClock.reset();
            _cellTotalClock.reset();
        }
        BlockPos next = cells.get(cleared);
        if (tooFar(mod, next)) {
            if (++_walksBack > WALKS_BACK_PER_CELL) {
                return dropSegment(mod, cells.subList(0, cleared), "kept ending up far from it");
            }
            // a fight, a fall or a tool trip took us off the line, and the corridor rule would box us out of walking back
            return startWalk(cleared == 0 ? _segment.anchor() : cells.get(cleared - 1));
        }
        _cellClock.tick();
        _cellTotalClock.tick();
        if (_cellClock.over(CELL_STALL_SECONDS) || _cellTotalClock.over(CELL_TOTAL_SECONDS)) {
            // pathing resets the movement checkers forever, and a path that keeps failing is still "pathing". no retries, the line is done
            return dropSegment(mod, cells.subList(0, cleared), "no new cell for too long");
        }
        setDebugState("Digging " + describe(_segment), "Digging a tunnel");
        _dig.aimAt(next);
        return _dig;
    }

    private Task dropSegment(AltoClef mod, List<BlockPos> dug, String why) {
        Debug.logInternal("branchmine: dropping " + describe(_segment) + ", " + why);
        dropChild(mod);
        segmentOver(mod, dug, false);
        return null;
    }

    private Task startSegment(AltoClef mod) {
        BranchPlan.Segment next = _plan.current();
        if (next == null) {
            return finish(endLine());
        }
        if (tooFar(mod, next.cells().get(0))) {
            return startWalk(next.anchor());
        }
        // no corridor rule is up here: the last one was dropped before we got to this point, so the world reads as itself
        int usable = usableCells(mod, next);
        if (usable == 0) {
            Debug.logInternal("branchmine: nothing usable in " + describe(next));
            segmentOver(mod, List.of(), false);
            return null;
        }
        _cut = usable < next.cells().size();
        _segment = next.firstCells(usable);
        _dig = new DigCorridorTask(_segment);
        _clearedBest = clearedCells(mod, _segment.cells());
        _walksBack = 0;
        _cellClock.reset();
        _cellTotalClock.reset();
        setDebugState("Digging " + describe(_segment), "Digging a tunnel");
        return _dig;
    }

    // how many cells from the front are fine to dig. the end's biome is asked first, a city's sculk sensors go off on the first swing
    private int usableCells(AltoClef mod, BranchPlan.Segment segment) {
        if (mod.getChunkTracker().isChunkLoaded(segment.end()) && mod.getWorld().getBiome(segment.end()).is(Biomes.DEEP_DARK)) {
            return 0;
        }
        int usable = 0;
        for (BlockPos cell : segment.cells()) {
            if (!cellIsGood(mod, cell)) {
                break;
            }
            usable++;
        }
        return usable;
    }

    private boolean cellIsGood(AltoClef mod, BlockPos cell) {
        // an unloaded chunk reads as air, which looks like a tunnel somebody already dug. the sides count too, unseen lava is still lava
        for (BlockPos near : List.of(cell, cell.east(), cell.west(), cell.north(), cell.south())) {
            if (!mod.getChunkTracker().isChunkLoaded(near)) {
                return false;
            }
        }
        BlockStateInterface bsi = mod.getClientBaritone().bsi;
        return bsi != null
                && mayDigHere(mod, bsi, cell)
                && mayDigHere(mod, bsi, cell.above())
                && stackAboveIsCalm(mod, bsi, cell.above())
                && MovementHelper.canWalkOn(bsi, cell.getX(), cell.getY() - 1, cell.getZ())
                && floorHolds(mod, cell.below());
    }

    // gravel over a hole counts as walkable, right up until digging the cell above it wakes it up and we go down with it
    private static boolean floorHolds(AltoClef mod, BlockPos floor) {
        BlockPos at = floor;
        while (at.getY() > mod.getWorld().getMinY() && FallingColumn.isFalling(mod.getWorld().getBlockState(at))) {
            at = at.below();
            if (FallingBlock.isFree(mod.getWorld().getBlockState(at))) {
                return false;
            }
        }
        return true;
    }

    private static boolean mayDigHere(AltoClef mod, BlockStateInterface bsi, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        return WorldHelper.pathMayDig(mod, pos) && !MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    // a gravel stack comes down the shaft we make, and the planner prices every block of it. one of them next to lava or under
    // water makes the whole cell infinite, so we find out now instead of staring at it for 10 s
    private static boolean stackAboveIsCalm(AltoClef mod, BlockStateInterface bsi, BlockPos head) {
        for (BlockPos at = head.above(); at.getY() < mod.getWorld().getMaxY(); at = at.above()) {
            BlockState state = mod.getWorld().getBlockState(at);
            if (!FallingColumn.isFalling(state)) {
                return true;
            }
            if (MovementHelper.avoidBreaking(bsi, at.getX(), at.getY(), at.getZ(), state)) {
                return false;
            }
        }
        return true;
    }

    // cells from the front with feet and head free. gravel landing in a cleared one takes the count back down, that's fine
    private static int clearedCells(AltoClef mod, List<BlockPos> cells) {
        int cleared = 0;
        for (BlockPos cell : cells) {
            if (!isFree(mod, cell) || !isFree(mod, cell.above())) {
                break;
            }
            cleared++;
        }
        return cleared;
    }

    // loaded first: after a long tool trip the whole segment reads as air, and that is not us having dug it. a cell full of lava that
    // flowed in later has no collision either and is even less dug
    private static boolean isFree(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return false;
        }
        BlockState state = mod.getWorld().getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(mod.getWorld(), pos).isEmpty();
    }

    private void segmentOver(AltoClef mod, List<BlockPos> dug, boolean finished) {
        _vein.addAll(BranchPlan.veinsAlong(dug, pos -> isOre(mod, pos), VEIN_LIMIT));
        boolean cut = _cut;
        _segment = null;
        _dig = null;
        _cut = false;
        if (finished && !cut) {
            _plan.segmentDone();
        } else {
            _plan.lineBlocked();
        }
        if (finished) {
            _badSegments = 0;
        } else if (++_badSegments >= BAD_SEGMENTS_TO_QUIT) {
            finish("branch mining stopped, " + BAD_SEGMENTS_TO_QUIT + " stretches in a row wouldn't dig. mined " + _oresMined + " " + _ore.name() + " ore");
        }
    }

    private boolean tooFar(AltoClef mod, BlockPos cell) {
        return mod.getPlayer().blockPosition().distSqr(cell) > WALK_BACK_DISTANCE * WALK_BACK_DISTANCE;
    }

    // plain walk with no corridor rule, the way back might need a block out of the way
    private Task startWalk(BlockPos to) {
        _walkTo = to;
        _walkTask = new GetToBlockTask(to);
        _walkClock.reset();
        return walkBack();
    }

    private Task walkBack() {
        _walkClock.tick();
        if (_walkClock.over(WALK_BACK_SECONDS)) {
            return finish("branch mining stopped, couldn't get back to the tunnel at " + _walkTo.toShortString() + ". mined " + _oresMined + " " + _ore.name() + " ore");
        }
        setDebugState("Walking back to " + _walkTo.toShortString(), "Walking back to the tunnel");
        return _walkTask;
    }

    private String endLine() {
        if (_plan.end() == BranchPlan.End.TRUNK_CAP) {
            return "branch mining done, dug the whole " + BranchPlan.TRUNK_CAP + " block trunk. mined " + _oresMined + " " + _ore.name() + " ore";
        }
        return "branch mining stopped, blocked in every direction after " + _plan.trunkCells() + " blocks of trunk. mined " + _oresMined + " " + _ore.name() + " ore";
    }

    private Task finish(String line) {
        if (!_done) {
            _done = true;
            Debug.logMessage(line);
        }
        return null;
    }

    private static String describe(BranchPlan.Segment s) {
        return s.kind().name().toLowerCase(Locale.ROOT) + " " + s.dir().getName() + " " + s.cells().get(0).toShortString() + " to " + s.end().toShortString();
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof BranchMiningTask task && task._ore.equals(_ore) && task._count == _count && task._y == _y && task._facing == _facing;
    }

    @Override
    protected String toDebugStringName() {
        return "Branch mining " + _ore.name() + " at y " + _y;
    }

    @Override
    protected String toHudString() {
        return "Branch mining for " + _ore.name().replace("raw_", "").replace('_', ' ');
    }
}
