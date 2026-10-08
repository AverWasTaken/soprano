package adris.altoclef.tasks.container;

import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a furnace, placing a furnace and collecting fuel as needed.
 */
public class SmeltInFurnaceTask extends ResourceTask implements AsyncSmelting.Handoff {
    private final SmeltTarget[] _targets;

    private final DoSmeltInFurnaceTask _doTask;

    public SmeltInFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInFurnaceTask(targets[0]);
    }

    public SmeltInFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        _doTask.ignoreMaterials();
    }

    // has anything gone in the furnace (as far as the last open screen showed us)
    public boolean hasStartedSmelting() {
        return _doTask.hasStartedSmelting();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.FURNACE);
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Optional<BlockPos> furnacePos = mod.getBlockTracker().getNearestTracking(Blocks.FURNACE);
        furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.FURNACE);
        mod.getBehaviour().pop();
        // Close furnace screen
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) || _doTask.isFinished(mod);
    }

    @Override
    public boolean handedOff() {
        return _doTask._loaded;
    }

    @Override
    public void recordLeftBehind(AltoClef mod) {
        _doTask.recordStranded(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInFurnaceTask task) {
            return task._doTask.isEqual(_doTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _doTask.toDebugString();
    }

    @Override
    protected String toHudString() {
        return _doTask.getHudName();
    }

    public SmeltTarget[] getTargets() {
        return _targets;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    static class DoSmeltInFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final FurnaceCache _furnaceCache = new FurnaceCache();
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;
        // async smelting: everything is in the furnace and we walked away, this task is done and must not walk back to it
        private boolean _loaded;
        // the outer check (bag short of what the furnace still needs) and the one inside the open screen, see FuelShortage
        private final FuelShortage _fuelShort = new FuelShortage();
        private final FuelShortage _dryInside = new FuelShortage();

        public DoSmeltInFurnaceTask(SmeltTarget target) {
            super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof FurnaceMenu);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _loaded;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (_loaded) {
                return null;
            }
            mod.getBehaviour().addProtectedItems(ItemHelper.PLANKS);
            mod.getBehaviour().addProtectedItems(Items.COAL);
            mod.getBehaviour().addProtectedItems(_allMaterials.getMatches());
            mod.getBehaviour().addProtectedItems(_target.getMaterial().getMatches());
            tryUpdateOpenFurnace(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - materialsKnownInFurnace(mod)
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0);
            double totalFuelInFurnace = ItemHelper.getFuelAmount(_furnaceCache.fuelSlot) + _furnaceCache.burningFuelCount + _furnaceCache.burnPercentage
                    + fuelKnownInFurnace(mod);
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            // the fuel already in the furnace comes off in both modes (the cook mode skipped it, see the smoker: coal in the slot, not
            // lit yet, read as the whole batch short)
            double fuelNeeded = FuelShortage.needed(_ignoreMaterials,
                    materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0,
                    materialTarget.getTargetCount(), mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches()),
                    outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0, totalFuelInFurnace);

            // We don't have enough materials...
            if (mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                return getMaterialTask(_target.getMaterial());
            }

            // We don't have enough fuel...
            double bagFuel = StorageHelper.calculateInventoryFuelCount(mod);
            double trip = _dryInside.fetchTarget(bagFuel);
            if (trip > 0) {
                setDebugState("Getting Fuel");
                return new CollectFuelTask(trip);
            }
            boolean lacking = _furnaceCache.burningFuelCount <= 0 && bagFuel < fuelNeeded;
            if (_fuelShort.confirmed(lacking, mod.getWorld().getGameTime())) {
                setDebugState("Getting Fuel");
                // the exact shortfall, no + 1: the go-back test above and CollectFuelTask's finish test are the same number now
                return new CollectFuelTask(fuelNeeded);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick(mod);
        }

        // dropped with the input in the furnace and no job (the fuel trip outlasted the cook's patience): leave the job for the
        // gamer so somebody goes back and takes it out. the cache is the last look at the slots, the visit reads the real ones
        void recordStranded(AltoClef mod) {
            BlockPos at = getTargetContainerPosition();
            ItemStack material = _furnaceCache.materialSlot;
            if (_loaded || at == null || material.isEmpty() || !AsyncSmelting.wants(_target.getItem())) {
                return;
            }
            Debug.logInternal("furnace at " + at.toShortString() + " still holds " + material.getCount() + " of our input and the cook is leaving, "
                    + "recording it so it gets picked up");
            AsyncSmelting.leftBehind(mod, at, Blocks.FURNACE, material, _target.getItem());
        }

        // what the screen showed, or when this task never had it open (an interrupt restarts us and the cache is empty) what the
        // container tracker saw last time. without that a restart read the ore we had loaded as "no materials", and went
        // mining three more. a stale number is fine, the real slots decide once the screen is open
        private int materialsKnownInFurnace(AltoClef mod) {
            int shown = _allMaterials.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0;
            if (shown > 0 || isContainerOpen(mod)) {
                return shown;
            }
            return (int) StationMemory.known(false, shown, StationMemory.materialsRemembered(mod, rememberedFurnace(mod), _allMaterials));
        }

        // same for the fuel: the coal we had already put in the fuel slot when the load got cut off is not coal to go and mine
        private double fuelKnownInFurnace(AltoClef mod) {
            boolean seen = !_furnaceCache.fuelSlot.isEmpty() || _furnaceCache.burningFuelCount > 0;
            if (seen || isContainerOpen(mod)) {
                return 0;
            }
            return StationMemory.fuelRemembered(mod, rememberedFurnace(mod), _allMaterials);
        }

        // Override this if our materials must be acquired in a special way.
        // virtual
        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            // the station pickup reads this, it must not break a table under a half done load
            AsyncSmelting.working(mod.getWorld().getGameTime());
            // We have appropriate materials/fuel.
            /*
             * - If output slot has something, receive it.
             * - Calculate needed material input. If we don't have, put it in.
             * - Calculate needed fuel input. If we don't have, put it in.
             * - Wait lol
             */
            ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);

            // Receive from output if present
            double currentlyCachedWhileCooking = StorageHelper.getFurnaceFuel() + StorageHelper.getFurnaceCookPercent();
            double needsWhileCooking = material.getCount() - currentlyCachedWhileCooking;
            if (needsWhileCooking <= 0) {
                if (!fuel.isEmpty()) {
                    ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                    if (!ItemHelper.canStackTogether(fuel, cursor)) {
                        Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                        if (toFit.isPresent()) {
                            mod.getSlotHandler().clickSlot(toFit.get(), 0, ClickType.PICKUP);
                            return null;
                        } else {
                            // Eh screw it
                            if (ItemHelper.canThrowAwayStack(mod, cursor)) {
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
                                return null;
                            }
                        }
                    }
                    mod.getSlotHandler().clickSlot(FurnaceSlot.INPUT_SLOT_FUEL, 0, ClickType.PICKUP);
                    return null;
                }
            }
            if (!output.isEmpty()) {
                setDebugState("Receiving Output");
                // Ensure our cursor is empty/can receive our item
                ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                if (!ItemHelper.canStackTogether(output, cursor)) {
                    Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                    if (toFit.isPresent()) {
                        mod.getSlotHandler().clickSlot(toFit.get(), 0, ClickType.PICKUP);
                        return null;
                    } else {
                        // Eh screw it
                        if (ItemHelper.canThrowAwayStack(mod, cursor)) {
                            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
                            return null;
                        }
                    }
                }
                // Pick up
                mod.getSlotHandler().clickSlot(FurnaceSlot.OUTPUT_SLOT, 0, ClickType.PICKUP);
                return null;
                // return new MoveItemToSlotTask(new ItemTarget(output.getItem(), output.getCount()), toMoveTo.get(), mod -> FurnaceSlot.OUTPUT_SLOT);
            }

            // Fill in input if needed
            // Materials needed in slot = (mat_target - out_in_inventory - out_in_furnace)
            ItemTarget materialTarget = _allMaterials;

            int neededMaterialsInSlot = materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(_target.getItem().getMatches())
                    - (_target.getItem().matches(output.getItem()) ? output.getCount() : 0);
            // We don't have the right material or we need more
            if (!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount()) {
                int materialsAlreadyIn = (materialTarget.matches(material.getItem()) ? material.getCount() : 0);
                setDebugState("Moving Materials");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), FurnaceSlot.INPUT_SLOT_MATERIALS);
            }

            /*
            double currentFuel = _ignoreMaterials
                    ? (Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                    : materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;
             */
            // Fill in fuel if needed
            if (fuel.isEmpty() || ItemHelper.isFuel(fuel.getItem())) {
                double currentlyCached = StorageHelper.getFurnaceFuel() + StorageHelper.getFurnaceCookPercent();
                double needs = material.getCount() - currentlyCached;
                if (needs > 0) {
                    // best fuel to fill, FuelPolicy keeps the wood the run still has plans for
                    var pick = adris.altoclef.util.helpers.FuelPolicy.choose(mod.getItemStorage().getItemStacksPlayerInventory(true), needs,
                            AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
                    if (pick != null) {
                        setDebugState("Filling fuel");
                        return new MoveItemToSlotFromInventoryTask(new ItemTarget(pick.stack().getItem(), pick.count()), FurnaceSlot.INPUT_SLOT_FUEL);
                    }
                }
            }

            // nothing in the bag may burn and the furnace is short: "Waiting..." would never end, see the smoker. shut the screen and
            // go get it (the trip target is remembered, FuelShortage.fetchUntil)
            double lit = StorageHelper.getFurnaceFuel();
            double progress = StorageHelper.getFurnaceCookPercent();
            double slotFuel = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
            if (_dryInside.confirmed(FuelShortage.dry(material.getCount(), lit, progress, slotFuel,
                    StorageHelper.calculateInventoryFuelCount(mod)), mod.getWorld().getGameTime())) {
                setDebugState("Out of fuel, going to get some");
                _dryInside.fetchUntil(FuelShortage.missing(material.getCount(), lit, progress, slotFuel));
                _dryInside.reset();
                StorageHelper.closeScreen();
                return null;
            }

            // fully loaded and fueled: the cook does not need us. the screen closes and whoever asked comes back later
            BlockPos at = getTargetContainerPosition();
            if (at != null && !material.isEmpty() && AsyncSmelting.wants(_target.getItem())
                    && AsyncSmelting.fuelCovers(fuel, StorageHelper.getFurnaceFuel(), material.getCount())) {
                setDebugState("Loaded, leaving it to cook");
                AsyncSmelting.loaded(mod, at, Blocks.FURNACE, material, _target.getItem());
                _loaded = true;
                return null;
            }
            setDebugState("Waiting...");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            // this used to compare the cache slots to null. they start as EMPTY stacks, never null, so it was "never make a
            // new one" for every smelt and the cobble math below it was dead code. a furnace we put stuff in stays ours
            if (hasStartedSmelting() || _furnaceCache.burnPercentage > 0) {
                return NEVER_MAKE_NEW;
            }
            BlockPos known = rememberedFurnace(mod);
            if (known == null) {
                return NEVER_MAKE_NEW;
            }
            var me = mod.getPlayer().position();
            boolean cheap = FurnaceReuse.canMakeCheaply(mod.getItemStorage().hasItem(Items.FURNACE),
                    StationMemory.cobbleish(mod), StationMemory.tableAround(mod));
            boolean ours = AsyncSmelting.isOurFurnace(known);
            // ore of ours sitting in it (the screen was closed on it half loaded) is not a furnace to walk away from
            boolean holdsOurStuff = ours && mod.getItemStorage().getContainerAtPosition(known).map(ContainerCache::holdsAnything).orElse(false);
            // 0 = any walk at all costs more, so DoStuffInContainerTask places one here instead
            return FurnaceReuse.makeNew(true, cheap, known.getX() + 0.5 - me.x, known.getY() - me.y, known.getZ() + 0.5 - me.z, ours, holdsOurStuff)
                    ? 0.0 : NEVER_MAKE_NEW;
        }

        private static final double NEVER_MAKE_NEW = 9999999.0;

        // the furnace DoStuffInContainerTask would walk to: the one it already picked, or the closest the tracker knows
        private BlockPos rememberedFurnace(AltoClef mod) {
            BlockPos picked = getTargetContainerPosition();
            if (picked != null && mod.getBlockTracker().blockIsValid(picked, Blocks.FURNACE)) {
                return picked;
            }
            BlockPos loaded = StationMemory.ourLoaded(mod, Blocks.FURNACE);
            if (loaded != null) {
                return loaded;
            }
            return mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(),
                    p -> adris.altoclef.util.helpers.WorldHelper.canReach(mod, p), Blocks.FURNACE).orElse(null);
        }

        @Override
        protected BlockPos overrideContainerPosition(AltoClef mod) {
            // If we have a valid container position, KEEP it. otherwise a furnace of ours with our stuff in it beats the nearest
            return getTargetContainerPosition() != null ? getTargetContainerPosition() : StationMemory.ourLoaded(mod, Blocks.FURNACE);
        }

        // the caches start as EMPTY stacks and only change while the screen is open, so anything in them means we
        // really did put stuff in (or take stuff out of) a furnace
        public boolean hasStartedSmelting() {
            return !_furnaceCache.materialSlot.isEmpty() || !_furnaceCache.fuelSlot.isEmpty()
                    || !_furnaceCache.outputSlot.isEmpty() || _furnaceCache.burningFuelCount > 0;
        }

        private void tryUpdateOpenFurnace(AltoClef mod) {
            if (isContainerOpen(mod)) {
                // Update current furnace cache
                _furnaceCache.burnPercentage = StorageHelper.getFurnaceCookPercent();
                _furnaceCache.burningFuelCount = StorageHelper.getFurnaceFuel();
                _furnaceCache.fuelSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
                _furnaceCache.materialSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
                _furnaceCache.outputSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            }
        }

        @Override
        protected String toHudString() {
            return "Smelting " + HudText.items(_target.getItem());
        }

    }

    static class FurnaceCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
