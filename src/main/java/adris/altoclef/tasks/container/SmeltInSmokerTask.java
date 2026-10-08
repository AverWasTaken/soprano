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
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmokerSlot;
import adris.altoclef.ui.HudText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a smoker, placing a smoker and collecting fuel as needed.
 */
public class SmeltInSmokerTask extends ResourceTask implements AsyncSmelting.Handoff {

    private final SmeltTarget[] _targets;

    private final DoSmeltInSmokerTask _doTask;

    public SmeltInSmokerTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        boolean ignoreMaterials = false;
        _doTask = new DoSmeltInSmokerTask(targets[0], ignoreMaterials);
    }

    public SmeltInSmokerTask(SmeltTarget target) {
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

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.SMOKER);
        mod.getBehaviour().push();
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Optional<BlockPos> smokerPos = mod.getBlockTracker().getNearestTracking(Blocks.SMOKER);
        smokerPos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        return _doTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.SMOKER);
        mod.getBehaviour().pop();
        // Close smoker screen
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
    public boolean recordLeftBehind(AltoClef mod) {
        return _doTask.recordStranded(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInSmokerTask task) {
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
    static class DoSmeltInSmokerTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private final SmokerCache _smokerCache = new SmokerCache();
        private final ItemTarget _allMaterials;
        private boolean _ignoreMaterials;
        // async cooking: everything is in the smoker and we walked away, this task is done and must not walk back to it
        private boolean _loaded;
        // the outer check (bag short of what the smoker still needs) and the one inside the open screen, see FuelShortage
        private final FuelShortage _fuelShort = new FuelShortage();
        private final FuelShortage _dryInside = new FuelShortage();

        public DoSmeltInSmokerTask(SmeltTarget target, boolean ignoreMaterials) {
            super(Blocks.SMOKER, new ItemTarget(Items.SMOKER));
            _target = target;
            _ignoreMaterials = ignoreMaterials;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInSmokerTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().containerMenu instanceof SmokerMenu);
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
            tryUpdateOpenSmoker(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - materialsKnownInSmoker(mod)
                    - (outputTarget.matches(_smokerCache.outputSlot.getItem()) ? _smokerCache.outputSlot.getCount() : 0);
            double totalFuelInSmoker = ItemHelper.getFuelAmount(_smokerCache.fuelSlot) + _smokerCache.burningFuelCount + _smokerCache.burnPercentage
                    + fuelKnownInSmoker(mod);
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            // the fuel already in the smoker comes off in both modes. the cook mode (ignoreMaterials) used to skip it, so the coal
            // that had just moved into the slot (not lit yet, bag empty) still read as the whole batch short: "Getting Fuel" the
            // tick after "Filling fuel", twice in the live log, and a full batch of coal asked for with the slot already covering it
            double fuelNeeded = FuelShortage.needed(_ignoreMaterials,
                    materialTarget.matches(_smokerCache.materialSlot.getItem()) ? _smokerCache.materialSlot.getCount() : 0,
                    materialTarget.getTargetCount(), mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches()),
                    outputTarget.matches(_smokerCache.outputSlot.getItem()) ? _smokerCache.outputSlot.getCount() : 0, totalFuelInSmoker);

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
            boolean lacking = _smokerCache.burningFuelCount <= 0 && bagFuel < fuelNeeded;
            if (_fuelShort.confirmed(lacking, mod.getWorld().getGameTime())) {
                setDebugState("Getting Fuel");
                // the exact shortfall, no + 1: the go-back test above and CollectFuelTask's finish test are the same number now, so
                // there is nothing to round up for. 8 items is one coal, not two
                return new CollectFuelTask(fuelNeeded);
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

            // We have fuel and materials. Get to our container and smelt!
            return super.onTick(mod);
        }

        // dropped with the meat in the smoker and no job (the coal trip outlasted the cook's patience): leave the job for the
        // gamer so somebody goes back and takes it out. the cache is the last look at the slots, the visit reads the real ones
        boolean recordStranded(AltoClef mod) {
            BlockPos at = getTargetContainerPosition();
            ItemStack material = _smokerCache.materialSlot;
            if (_loaded || at == null || material.isEmpty() || !AsyncSmelting.wants(_target.getItem())) {
                return false;
            }
            Debug.logInternal("smoker at " + at.toShortString() + " still holds " + material.getCount() + " of our meat and the cook is leaving, "
                    + "recording it so it gets picked up");
            AsyncSmelting.leftBehind(mod, at, Blocks.SMOKER, material, _target.getItem());
            return true;
        }

        // what the screen showed, or when this task never had it open (an interrupt restarts us with empty caches) what the
        // container tracker saw last time. same story as the furnace, see StationMemory
        private int materialsKnownInSmoker(AltoClef mod) {
            int shown = _allMaterials.matches(_smokerCache.materialSlot.getItem()) ? _smokerCache.materialSlot.getCount() : 0;
            if (shown > 0 || isContainerOpen(mod)) {
                return shown;
            }
            return (int) StationMemory.known(false, shown, StationMemory.materialsRemembered(mod, rememberedSmoker(mod), _allMaterials));
        }

        private double fuelKnownInSmoker(AltoClef mod) {
            boolean seen = !_smokerCache.fuelSlot.isEmpty() || _smokerCache.burningFuelCount > 0;
            if (seen || isContainerOpen(mod)) {
                return 0;
            }
            return StationMemory.fuelRemembered(mod, rememberedSmoker(mod), _allMaterials);
        }

        // the smoker DoStuffInContainerTask would walk to: the one it already picked, or the closest the tracker knows
        private BlockPos rememberedSmoker(AltoClef mod) {
            BlockPos picked = getTargetContainerPosition();
            if (picked != null && mod.getBlockTracker().blockIsValid(picked, Blocks.SMOKER)) {
                return picked;
            }
            BlockPos loaded = StationMemory.ourLoaded(mod, Blocks.SMOKER);
            if (loaded != null) {
                return loaded;
            }
            return mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(),
                    p -> adris.altoclef.util.helpers.WorldHelper.canReach(mod, p), Blocks.SMOKER).orElse(null);
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
            ItemStack output = StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_FUEL);

            // Receive from output if present
            double currentlyCachedWhileCooking = StorageHelper.getSmokerFuel() + StorageHelper.getSmokerCookPercent();
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
                    mod.getSlotHandler().clickSlot(SmokerSlot.INPUT_SLOT_FUEL, 0, ClickType.PICKUP);
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
                mod.getSlotHandler().clickSlot(SmokerSlot.OUTPUT_SLOT, 0, ClickType.PICKUP);
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
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), SmokerSlot.INPUT_SLOT_MATERIALS);
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
                double currentlyCached = StorageHelper.getSmokerFuel() + StorageHelper.getSmokerCookPercent();
                double needs = material.getCount() - currentlyCached;
                if (needs > 0) {
                    // best fuel to fill, FuelPolicy keeps the wood the run still has plans for
                    var pick = adris.altoclef.util.helpers.FuelPolicy.choose(mod.getItemStorage().getItemStacksPlayerInventory(true), needs,
                            AltoSettings::isSupportedFuel, ItemHelper::getFuelAmount);
                    if (pick != null) {
                        setDebugState("Filling fuel");
                        return new MoveItemToSlotFromInventoryTask(new ItemTarget(pick.stack().getItem(), pick.count()), SmokerSlot.INPUT_SLOT_FUEL);
                    }
                }
            }

            // nothing in the bag may burn and the smoker is short: standing here saying "Waiting..." never ends (the burning check
            // out in onTick only fires with nothing lit, and a smoker with a bit of fire left is lit). shut the screen and go get
            // it, and the trip is not talked out of by a lit reading from the last look (FuelShortage.fetchUntil)
            double lit = StorageHelper.getSmokerFuel();
            double progress = StorageHelper.getSmokerCookPercent();
            double slotFuel = fuel.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuel);
            if (_dryInside.confirmed(FuelShortage.dry(material.getCount(), lit, progress, slotFuel,
                    StorageHelper.calculateInventoryFuelCount(mod)), mod.getWorld().getGameTime())) {
                setDebugState("Out of fuel, going to get some");
                _dryInside.fetchUntil(FuelShortage.missing(material.getCount(), lit, progress, slotFuel));
                _dryInside.reset();
                StorageHelper.closeScreen();
                return null;
            }

            // fully loaded and fueled: 35 seconds of mutton does not need us staring at the gui. the screen closes and the
            // gamer comes back for it (the same trick as the furnace, AsyncSmelting has the story)
            BlockPos at = getTargetContainerPosition();
            if (at != null && !material.isEmpty() && AsyncSmelting.wants(_target.getItem())
                    && AsyncSmelting.fuelCovers(fuel, StorageHelper.getSmokerFuel(), material.getCount())) {
                setDebugState("Loaded, leaving it to cook");
                AsyncSmelting.loaded(mod, at, Blocks.SMOKER, material, _target.getItem());
                _loaded = true;
                return null;
            }
            setDebugState("Waiting...");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            // this compared the cache slots to null, they start as EMPTY stacks and never are, so every smoker we knew about was
            // "never make a new one" and the bot walked to it from anywhere. same fix as the furnace (FurnaceReuse): a smoker we
            // put stuff in stays ours, otherwise the walk is priced against a fresh one
            if (hasStartedSmelting() || _smokerCache.burnPercentage > 0) {
                return NEVER_MAKE_NEW;
            }
            BlockPos known = rememberedSmoker(mod);
            if (known == null) {
                return NEVER_MAKE_NEW;
            }
            var me = mod.getPlayer().position();
            boolean cheap = FurnaceReuse.canMakeSmokerCheaply(mod.getItemStorage().hasItem(Items.SMOKER), mod.getItemStorage().hasItem(Items.FURNACE),
                    StationMemory.cobbleish(mod), mod.getItemStorage().getItemCount(ItemHelper.LOG), StationMemory.tableAround(mod));
            boolean ours = AsyncSmelting.isOurFurnace(known);
            // ore of ours sitting in it (the screen was closed on it half loaded) is not a smoker to walk away from
            boolean holdsOurStuff = ours && mod.getItemStorage().getContainerAtPosition(known).map(ContainerCache::holdsAnything).orElse(false);
            // 0 = any walk at all costs more, so DoStuffInContainerTask places one here instead
            return FurnaceReuse.makeNew(true, cheap, known.getX() + 0.5 - me.x, known.getY() - me.y, known.getZ() + 0.5 - me.z, ours, holdsOurStuff)
                    ? 0.0 : NEVER_MAKE_NEW;
        }

        private static final double NEVER_MAKE_NEW = 9999999.0;

        // the caches start as EMPTY stacks and only change while the screen is open, so anything in them means we really did
        // put stuff in (or take stuff out of) a smoker
        public boolean hasStartedSmelting() {
            return !_smokerCache.materialSlot.isEmpty() || !_smokerCache.fuelSlot.isEmpty()
                    || !_smokerCache.outputSlot.isEmpty() || _smokerCache.burningFuelCount > 0;
        }

        @Override
        protected BlockPos overrideContainerPosition(AltoClef mod) {
            // If we have a valid container position, KEEP it. otherwise a smoker of ours with our stuff in it beats the nearest
            return getTargetContainerPosition() != null ? getTargetContainerPosition() : StationMemory.ourLoaded(mod, Blocks.SMOKER);
        }

        private void tryUpdateOpenSmoker(AltoClef mod) {
            if (isContainerOpen(mod)) {
                // Update current furnace cache
                _smokerCache.burnPercentage = StorageHelper.getSmokerCookPercent();
                _smokerCache.burningFuelCount = StorageHelper.getSmokerFuel();
                _smokerCache.fuelSlot = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_FUEL);
                _smokerCache.materialSlot = StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS);
                _smokerCache.outputSlot = StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT);
            }
        }

        @Override
        protected String toHudString() {
            return "Cooking " + HudText.items(_target.getItem());
        }

    }

    static class SmokerCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }
}
