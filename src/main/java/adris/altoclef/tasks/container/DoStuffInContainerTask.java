package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.construction.PlaceStationTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StationChoice;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;
    private final StationHook.Kind _stationKind;

    // tables and furnaces go down the way a player does it (PlaceStationTask, with the old placer inside as its last
    // resort), anything else (anvil, smithing table, chest) keeps the old one
    private final Task _placeTask;
    private BlockPos _cachedContainerPosition = null;
    // the walk and the click used to be decided in two places (our nearest here, a closest-block search over there) and with
    // two furnaces standing they disagreed. this is the one block we both walk to and click, rebuilt only when it changes
    private Task _openTask;
    private BlockPos _openTaskPos;
    // the choice is made once per game tick, the smelt tasks ask it too (stationInUse) and the tracker search is not free
    private long _pickTick = Long.MIN_VALUE;
    private StationChoice.Pick<BlockPos> _pick = new StationChoice.Pick<>(StationChoice.Use.NONE, null);
    private String _loggedChoice = "";
    private boolean _canMakeNow = true;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = PlaceStationTask.isStation(_containerBlocks)
                ? new PlaceStationTask(_containerBlocks)
                : new PlaceBlockNearbyTask(_containerBlocks);
        _stationKind = StationHook.kindOf(_containerBlocks);
    }

    // which workbench this task is for (the gamer's registry keeps those), null for chests, anvils and the like
    public StationHook.Kind stationKind() {
        return _stationKind;
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
        _pickTick = Long.MIN_VALUE;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // a station of this kind is coming down right now, so nothing may place or craft another one: the block we would find
        // missing is the one being broken, and the new one would stand where the old one was. the phase that owns the pickup
        // runs it ahead of the kit task, so this only holds in the gaps (an interrupted pickup, a queued place)
        if (_stationKind != null && StationHook.pickingUp(_stationKind)) {
            setDebugState("Waiting, the " + _stationKind.word() + " is being picked up");
            return null;
        }

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

        // the one decision: ours near, a world one near, the bag, craft. everything else (override, tracker lag, the force timer)
        // is a candidate in it or gone
        StationChoice.Pick<BlockPos> pick = choose(mod);
        noteChoice(mod, pick);
        switch (pick.use()) {
            case NONE -> {
                // we were told to use a container that already exists, so with none left we sit still and let whoever
                // picked us notice and pick something else. crafting one is how this task used to ruin that
                _cachedContainerPosition = null;
                setDebugState("No container to use");
                return null;
            }
            case BAG, MAKE -> {
                // We're no longer going to our previous container.
                _cachedContainerPosition = null;

                // Get if we don't have...
                if (!mod.getItemStorage().hasItem(_containerTarget)) {
                    setDebugState("Getting container item");
                    return TaskCatalogue.getItemTask(_containerTarget);
                }

                setDebugState("Placing container...");
                // Now place!
                return _placeTask;
            }
            default -> {
            }
        }
        BlockPos target = pick.key();
        _cachedContainerPosition = target;

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Waiting for eating...", "Eating first");
            return null;
        }
        setDebugState("Walking to container... " + target.toShortString(), "Walking to " + HudText.pos(target));

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
        if (_openTask == null || !target.equals(_openTaskPos)) {
            _openTaskPos = target;
            _openTask = new InteractWithBlockTask(_openTaskPos);
        }
        return _openTask;
    }

    static boolean keepPlacing(boolean haveItem, boolean placerActive, boolean placerFinished, boolean placerVerifying) {
        return (haveItem || placerVerifying) && placerActive && !placerFinished;
    }

    // what StationChoice gets to see: every station this task could be heading for, with who it belongs to and how far it is (a
    // straight line, height counted, from us to its middle). the registry and the tracker overlap on purpose, StationChoice merges
    private StationChoice.Pick<BlockPos> choose(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now == _pickTick) {
            return _pick;
        }
        Vec3 me = mod.getPlayer().position();
        List<StationChoice.Candidate<BlockPos>> seen = new ArrayList<>();

        // the one we are in the middle of using: our items are in it, or the subclass was told to use exactly this one. any
        // distance, and never left for a new one
        BlockPos pinned = pinnedStation(mod);
        if (pinned != null && mod.getBlockTracker().blockIsValid(pinned, _containerBlocks) && !StationHook.pickingUp(pinned)) {
            seen.add(candidate(pinned, me, StationChoice.Role.PINNED));
        }
        // the block our own placer put down, in the world before the tracker has caught up with it (a rescan behind the
        // table we just placed is how the second one got crafted)
        BlockPos placed = placedPos();
        if (usable(mod, placed)) {
            seen.add(candidate(placed, me, StationChoice.Role.OURS));
        }
        // the run's registry knows where its stations stand even when the tracker has not seen them or canReach says no
        BlockPos ours = _stationKind == null ? null : StationHook.standingNear(_stationKind, me.x, me.y, me.z);
        if (usable(mod, ours)) {
            seen.add(candidate(ours, me, StationChoice.Role.OURS));
        }
        // and the nearest of ours out to the forget line, for when making one is not on (StationChoice only walks that far when the bag
        // cannot make one). an unloaded chunk reads as air, so out there the registry's word is taken
        BlockPos oursFar = _stationKind == null ? null : StationHook.standingWithin(_stationKind, me.x, me.y, me.z, WalkCost.STATION_FORGET);
        if (oursFar != null && !oursFar.equals(ours) && walkBackUsable(mod, oursFar, _containerBlocks)) {
            seen.add(candidate(oursFar, me, StationChoice.Role.OURS));
        }
        // the tracker loses the one we were walking to now and then (a rescan after a fuel trip did it, and the next thing the
        // bot did was mine the smoker it had just put down). the world still has it, the world wins
        BlockPos previous = _cachedContainerPosition;
        if (usable(mod, previous)) {
            seen.add(candidate(previous, me, StationHook.ours(previous) ? StationChoice.Role.OURS : StationChoice.Role.WORLD));
        }
        // a world one (a village's, or one of ours the registry already forgot, never in the registry) that alto can see and reach,
        // the nearest in a straight line. out to the forget line: past worldReach it only counts when the bag cannot make one
        double reach = worldReach();
        double lookOut = Math.max(reach, WalkCost.STATION_FORGET);
        Optional<BlockPos> world = mod.getBlockTracker().getNearestTracking(me,
                p -> WorldHelper.canReach(mod, p) && !StationHook.pickingUp(p) && !StationHook.ours(p)
                        && WalkCost.stationDistance(p.getX(), p.getY(), p.getZ(), me.x, me.y, me.z) <= lookOut,
                (fx, fy, fz, tx, ty, tz) -> WalkCost.distance3d(tx - fx, ty - fy, tz - fz), _containerBlocks);
        world.ifPresent(p -> seen.add(candidate(p, me, StationChoice.Role.WORLD)));

        _canMakeNow = canMakeNow(mod);
        _pick = StationChoice.decide(seen, previous, mod.getItemStorage().hasItem(_containerTarget), canMakeNew(mod), _canMakeNow, reach);
        _pickTick = now;
        return _pick;
    }

    // what one more of this station takes is in the bag right now (StationChoice.canMakeFrom). chests, anvils and the like say yes,
    // their old behaviour
    private boolean canMakeNow(AltoClef mod) {
        return _stationKind == null || bagCanMake(mod, _stationKind);
    }

    // public for MinecraftFacts: the planner asks the same two questions so it counts a far furnace as held exactly when the walk
    // back would go to it
    public static boolean bagCanMake(AltoClef mod, StationHook.Kind kind) {
        var bag = mod.getItemStorage();
        int cobble = bag.getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
        return StationChoice.canMakeFrom(kind, cobble, bag.getItemCount(ItemHelper.LOG), bag.getItemCount(ItemHelper.PLANKS),
                bag.getItemCount(Items.FURNACE));
    }

    // the far one of ours the walk back may head for: not coming down, not one with our things cooking in it (that one is busy, a
    // second load does not go in) and not one alto already gave up on reaching. an unloaded chunk reads as air, so out there the
    // registry's word is taken
    public static boolean walkBackUsable(AltoClef mod, BlockPos pos, Block... blocks) {
        return !StationHook.pickingUp(pos) && !StationMemory.holdsOurStuff(mod, pos)
                && (!mod.getChunkTracker().isChunkLoaded(pos) || (isBlockIn(mod, pos, blocks) && WorldHelper.canReach(mod, pos)));
    }

    private static StationChoice.Candidate<BlockPos> candidate(BlockPos pos, Vec3 me, StationChoice.Role role) {
        return new StationChoice.Candidate<>(pos, WalkCost.stationDistance(pos.getX(), pos.getY(), pos.getZ(), me.x, me.y, me.z), role);
    }

    private boolean usable(AltoClef mod, BlockPos pos) {
        return pos != null && isContainerBlock(mod, pos) && !StationHook.pickingUp(pos);
    }

    // one line each time the answer changes, for the station kinds the registry keeps. these are the lines to read when a second
    // table shows up or a furnace walk looks wrong
    private void noteChoice(AltoClef mod, StationChoice.Pick<BlockPos> pick) {
        if (_stationKind == null) {
            return;
        }
        String key = pick.use() + (pick.key() == null ? "" : " " + pick.key().toShortString());
        if (key.equals(_loggedChoice)) {
            return;
        }
        _loggedChoice = key;
        Vec3 me = mod.getPlayer().position();
        String word = _stationKind.word();
        double away = pick.key() == null ? 0 : WalkCost.stationDistance(pick.key().getX(), pick.key().getY(), pick.key().getZ(), me.x, me.y, me.z);
        // past NEAR is the walk back StationChoice only takes when the bag cannot make one
        String back = away > WalkCost.STATION_NEAR ? ", walking back to it, the bag cannot make a " + word : "";
        String line = switch (pick.use()) {
            case OURS -> "using ours at " + pick.key().toShortString() + ", " + Math.round(away) + " blocks away" + back;
            case WORLD -> "using a " + word + " nobody here placed at " + pick.key().toShortString() + back;
            case BAG -> "none within " + Math.round(WalkCost.STATION_NEAR) + " blocks, placing the one from the bag";
            case MAKE -> "none within " + Math.round(WalkCost.STATION_NEAR) + " blocks and none in the bag, making one"
                    + (_canMakeNow ? " (the bag has what it takes)" : " (nothing standing within " + Math.round(WalkCost.STATION_FORGET)
                    + " either, gathering for it)");
            case NONE -> "none to use and not allowed to make one";
        };
        Debug.logInternal("bench: " + word + " choice: " + line);
    }

    private boolean isContainerBlock(AltoClef mod, BlockPos pos) {
        return isBlockIn(mod, pos, _containerBlocks);
    }

    private static boolean isBlockIn(AltoClef mod, BlockPos pos, Block[] blocks) {
        // blacklisted spots stay blacklisted, that is the whole point of the tracker's answer being empty
        if (mod.getBlockTracker().unreachable(pos)) {
            return false;
        }
        Block there = mod.getWorld().getBlockState(pos).getBlock();
        for (Block block : blocks) {
            if (block == there) {
                return true;
            }
        }
        return false;
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    // Virtual. a station the subclass is in the middle of using or was sent to: it is used from any distance and never left for a
    // new one (our ore in a half loaded furnace, the blast furnace the router picked). null = no such thing, the choice decides
    protected BlockPos pinnedStation(AltoClef mod) {
        return null;
    }

    // Virtual. false means only ever use a container that is already there, never get/place one
    protected boolean canMakeNew(AltoClef mod) {
        return true;
    }

    // Virtual. how far a tracked station nobody of ours placed is worth the walk (a straight line). NEAR for the table, furnace and
    // smoker; the ones that cost iron to make are worth more
    protected double worldReach() {
        return WalkCost.STATION_NEAR;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    // the block the choice would walk to right now, null when it would place or make one (or use nothing). the smelt tasks read
    // the last look at that station's slots, so they ask the same question the walk does
    protected final BlockPos stationInUse(AltoClef mod) {
        StationChoice.Pick<BlockPos> pick = choose(mod);
        return pick.walks() ? pick.key() : null;
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
}
