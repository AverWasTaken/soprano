package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.CropRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.SmokerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Salmon;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectFoodTask extends Task {

    // Represents order of preferred mobs to least preferred
    // (the order is only for the smelting and pickup loops now, FoodHunt decides who gets chased). fish are here so a
    // dropped one still gets picked up and cooked, but they have no hunt kind: nobody chases a cod
    private static final CookableFoodTarget[] COOKABLE_FOODS = new CookableFoodTarget[]{
            new CookableFoodTarget("porkchop", Pig.class, FoodHunt.Kind.PIG),
            new CookableFoodTarget("beef", Cow.class, FoodHunt.Kind.COW),
            new CookableFoodTarget("chicken", Chicken.class, FoodHunt.Kind.CHICKEN),
            new CookableFoodTarget("mutton", Sheep.class, FoodHunt.Kind.SHEEP),
            new CookableFoodTargetFish("cod", Cod.class),
            new CookableFoodTargetFish("salmon", Salmon.class)
    };

    private static final Item[] ITEMS_TO_PICK_UP = new Item[]{
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.GOLDEN_CARROT,
            Items.BREAD,
            Items.BAKED_POTATO
    };

    private static final CropTarget[] CROPS = new CropTarget[]{
            // unit is the food one item is worth, for how many to ask for. wheat is bread, 5 for 3 wheat
            new CropTarget(Items.WHEAT, Blocks.WHEAT, Items.WHEAT_SEEDS, 1.6),
            new CropTarget(Items.CARROT, Blocks.CARROTS, Items.CARROT, 3)
    };

    // every hoe, so whichever one we end up holding is protected and counts as "have one"
    private static final Item[] HOES = new Item[]{
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };

    private final double _unitsNeeded;
    private final TimerGame _checkNewOptionsTimer = new TimerGame(10);
    private SmeltInSmokerTask _smeltTask = null;
    private Task _currentResourceTask = null;

    // the animal we are after, kept so the next pick can stay on it (see FoodHunt.STICKY_FACTOR). _huntTask is the task
    // made for it, to tell "the hunt lost its animal" apart from every other cached task
    private Entity _hunted = null;
    private CookableFoodTarget _huntedFood = null;
    private Task _huntTask = null;

    // the hay sweep: null timer = not started, over = never again for this task. the sweep task is remembered so the
    // cached resource task can be dropped the moment the pile is gone instead of up to 10 s later
    private TimerGame _sweepTimer = null;
    private boolean _sweepOver = false;
    private Task _sweepTask = null;
    private int _hayLeft = 0;
    private final TimerGame _hoeCheckTimer = new TimerGame(HaySweep.HOE_CHECK_SECONDS);
    private Task _hoeTask = null;
    private TimerGame _hoeTimer = null;
    private boolean _hoeDone = false;

    public CollectFoodTask(double unitsNeeded) {
        _unitsNeeded = unitsNeeded;
    }

    private static double getFoodPotential(ItemStack food) {
        if (food == null) return 0;
        int count = food.getCount();
        if (count <= 0) return 0;
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (food.getItem() == cookable.getRaw()) {
                assert cookable.getCooked().components().get(DataComponents.FOOD) != null;
                return count * Objects.requireNonNull(cookable.getCooked().components().get(DataComponents.FOOD)).nutrition();
            }
        }
        // We're just an ordinary item.
        if (food.getItem().components().has(DataComponents.FOOD)) {
            assert food.getItem().components().get(DataComponents.FOOD) != null;
            return count * Objects.requireNonNull(food.getItem().components().get(DataComponents.FOOD)).nutrition();
        }
        return 0;
    }

    // Gets the units of food if we were to convert all of our raw resources to food.
    @SuppressWarnings("RedundantCast")
    private static double calculateFoodPotential(AltoClef mod) {
        double potentialFood = 0;
        for (ItemStack food : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            potentialFood += getFoodPotential(food);
        }
        int potentialBread = (int) (mod.getItemStorage().getItemCount(Items.WHEAT) / 3) + mod.getItemStorage().getItemCount(Items.HAY_BLOCK) * 3;
        potentialFood += Objects.requireNonNull(Items.BREAD.components().get(DataComponents.FOOD)).nutrition() * potentialBread;
        // Check smelting
        AbstractContainerMenu screen = mod.getPlayer().containerMenu;
        if (screen instanceof SmokerMenu) {
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.INPUT_SLOT_MATERIALS));
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(SmokerSlot.OUTPUT_SLOT));
        }
        // meat cooking in a smoker we walked away from (async cooking, only the gamer ever has any). it is food on the way,
        // not food we have: foodUnits() stays honest and this only stops the hunt from doubling up
        potentialFood += AsyncSmelting.pendingFoodUnits();
        return potentialFood;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Protect ALL food
        mod.getBehaviour().addProtectedItems(ITEMS_TO_PICK_UP);
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().trackBlock(crop.cropBlock);
        }

        // Allow us to consume food.
        /*
        for (CookableFoodTarget food : COOKABLE_FOODS)
            mod.getBehaviour().addProtectedItems(food.getRaw(), food.getCooked());
            mod.getBehaviour().addProtectedItems(crop.cropItem);
        }
         */
        mod.getBehaviour().addProtectedItems(Items.HAY_BLOCK, Items.SWEET_BERRIES);
        // altoThrowAwayUnusedItems would happily toss a hoe the second the bag gets full. this frame pops on stop so
        // it is fair game again once the food is done
        mod.getBehaviour().addProtectedItems(HOES);
        // a restart is a new pile, new budget
        _sweepTimer = null;
        _sweepOver = false;
        _sweepTask = null;
        _hoeTask = null;
        _hoeTimer = null;
        _hoeDone = false;
        _hunted = null;
        _huntedFood = null;
        _huntTask = null;

        mod.getBlockTracker().trackBlock(Blocks.HAY_BLOCK);
        mod.getBlockTracker().trackBlock(Blocks.SWEET_BERRY_BUSH);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getEntityTracker().entityFound(Chicken.class)) {
            Optional<Entity> chickens = mod.getEntityTracker().getClosestEntity(Chicken.class);
            if (chickens.isPresent()) {
                Iterable<Entity> entities = mod.getWorld().entitiesForRendering();
                for (Entity entity : entities) {
                    if (entity instanceof Monster || entity instanceof Slime) {
                        if (chickens.get().hasPassenger(entity)) {
                            if (mod.getEntityTracker().isEntityReachable(entity)) {
                                Debug.logMessage("Blacklisting chicken jockey.");
                                mod.getEntityTracker().requestEntityUnreachable(chickens.get());
                            }
                        }
                    }
                }
            }
        }
        if (mod.getBlockTracker().isTracking(Blocks.HAY_BLOCK)) {
            Optional<BlockPos> hay = mod.getBlockTracker().getNearestTracking(Blocks.HAY_BLOCK);
            if (hay.isPresent()) {
                if (isOutpostHay(mod, hay.get())) {
                    Debug.logMessage("Blacklisting pillage hay bales.");
                    mod.getBlockTracker().requestBlockUnreachable(hay.get(), 0);
                }
            }
        }
        // If we were previously smelting, keep on smelting.
        if (_smeltTask != null && _smeltTask.isActive() && !_smeltTask.isFinished(mod)) {
            // TODO: If we don't have cooking materials, cancel.
            setDebugState("Cooking...");
            return _smeltTask;
        }

        // the hoe is made once, nothing else gets a say until it is done (or fed up)
        if (_hoeTask != null) {
            if (_hoeTask.isActive() && !_hoeTask.isFinished(mod) && !_hoeTask.thisOrChildAreTimedOut() && !_hoeTimer.elapsed()) {
                setDebugState("Making a hoe for the hay");
                return _hoeTask;
            }
            _hoeTask = null;
            // crafting time is not pile time
            if (_sweepTimer != null) {
                _sweepTimer.reset();
            }
        }

        if (_checkNewOptionsTimer.elapsed()) {
            // Try a new resource task
            _checkNewOptionsTimer.reset();
            // the timer used to wipe a half dead pig off the list the moment a hay bale came into view
            if (!(_huntTask != null && _currentResourceTask == _huntTask && (huntCommitted(mod) || lootComing(mod)))) {
                _currentResourceTask = null;
            }
        }

        // the sweep ends when the pile does, not on the next 10 s check
        if (_sweepTask != null && _currentResourceTask == _sweepTask && !sweepWanted(mod)) {
            _currentResourceTask = null;
        }

        // a hunt whose animal died or got blacklisted has nothing to chase, no point idling until the 10 s check
        if (_huntTask != null && _currentResourceTask == _huntTask && !huntStillOn(mod) && !lootComing(mod)) {
            _currentResourceTask = null;
        }

        if (_currentResourceTask != null && _currentResourceTask.isActive() && !_currentResourceTask.isFinished(mod) && !_currentResourceTask.thisOrChildAreTimedOut()) {
            if (_currentResourceTask == _sweepTask) {
                setDebugState("Sweeping the hay pile (" + _hayLeft + " left)");
            }
            return _currentResourceTask;
        }

        // Calculate potential
        double potentialFood = calculateFoodPotential(mod);
        if (potentialFood >= _unitsNeeded) {
            // we could stop here, but the rest of the pile is one step away and the next bale costs a few ticks
            Task sweep = sweepHayOrNull(mod);
            if (sweep != null) {
                return sweep;
            }
            // Convert our raw foods
            // PLAN:
            // - If we have hay/wheat, make it into bread
            // - If we have raw foods, smelt all of them

            // Convert Hay+Wheat -> Bread
            if (mod.getItemStorage().getItemCount(Items.WHEAT) >= 3) {
                setDebugState("Crafting Bread");
                Item[] w = new Item[]{Items.WHEAT};
                Item[] o = null;
                // jank
                _currentResourceTask = new CraftInTableTask(new RecipeTarget(Items.BREAD, 99999999, CraftingRecipe.newShapedRecipe("bread", new Item[][]{w, w, w, o, o, o, o, o, o}, 1)), false, false);
                return _currentResourceTask;
            }
            if (mod.getItemStorage().getItemCount(Items.HAY_BLOCK) >= 1) {
                setDebugState("Crafting Wheat");
                Item[] o = null;
                _currentResourceTask = new CraftInInventoryTask(new RecipeTarget(Items.WHEAT, 99999999, CraftingRecipe.newShapedRecipe("wheat", new Item[][]{new Item[]{Items.HAY_BLOCK}, o, o, o}, 9)), false, false);
                return _currentResourceTask;
            }
            // Convert raw foods -> cooked foods

            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                int rawCount = mod.getItemStorage().getItemCount(cookable.getRaw());
                // one smoker, one input slot: the next batch waits for the one already cooking to be collected, loading it
                // on top would swap the first batch back out (the gamer collects, then asks for this food again)
                if (rawCount > 0 && AsyncSmelting.pendingFoodUnits() == 0) {
                    //Debug.logMessage("STARTING COOK OF " + cookable.getRaw().getTranslationKey());
                    int toSmelt = rawCount + mod.getItemStorage().getItemCount(cookable.getCooked());
                    _smeltTask = new SmeltInSmokerTask(new SmeltTarget(new ItemTarget(cookable.cookedFood, toSmelt), new ItemTarget(cookable.rawFood, rawCount)));
                    _smeltTask.ignoreMaterials();
                    return _smeltTask;
                }
            }
        } else {
            // Pick up food items from ground
            for (Item item : ITEMS_TO_PICK_UP) {
                Task t = this.pickupTaskOrNull(mod, item);
                if (t != null) {
                    setDebugState("Picking up Food: " + item.getDescriptionId());
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Pick up raw/cooked foods on ground
            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                Task t = this.pickupTaskOrNull(mod, cookable.getRaw(), 20);
                if (t == null) t = this.pickupTaskOrNull(mod, cookable.getCooked(), 40);
                if (t != null) {
                    setDebugState("Picking up Cookable food");
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // the animal we are already fighting beats any pile of hay, and one that wanders by is a few swings
            Task prey = huntCommitted(mod) ? huntTaskOrNull(mod) : nearbyPreyOrNull(mod, FoodHunt.ALONG_THE_WAY_RADIUS);
            if (prey != null) {
                _currentResourceTask = prey;
                return _currentResourceTask;
            }
            // Hay blocks. past ~96 the walk costs more than a hunt, and we have a hunt branch now (it was 300, which
            // sent the bot 84 blocks away for hay mid chest)
            Task hayTaskBlock = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 96);
            if (hayTaskBlock != null) {
                Task hoe = hoeTaskOrNull(mod, hayInReach(mod).size());
                if (hoe != null) {
                    return hoe;
                }
                setDebugState("Collecting Hay");
                _currentResourceTask = hayTaskBlock;
                return _currentResourceTask;
            }
            // Crops. only the grown ones count as food, for every kind: this used to ask wheat and let young carrots
            // through, so a farm of seedlings was "food" the bot stood in breaking one carrot back at a time
            for (CropTarget target : CROPS) {
                Task t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, blockPos -> ripeAndBreakable(mod, blockPos), 96);
                if (t != null) {
                    setDebugState("Harvesting " + target.cropItem.getDescriptionId());
                    // a drop already lying there is just a pickup. the field itself goes through CollectCropTask, which
                    // knows to wait for the drop and to replant what it breaks
                    _currentResourceTask = t instanceof PickupDroppedItemTask ? t : cropTask(mod, target, potentialFood);
                    return _currentResourceTask;
                }
            }
            // Cooked foods
            Task hunt = huntTaskOrNull(mod);
            if (hunt != null) {
                _currentResourceTask = hunt;
                return _currentResourceTask;
            }

            // Sweet berries (separate from crops because they should have a lower priority than everything else cause they suck)
            Task berryPickup = pickupBlockTaskOrNull(mod, Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, 96);
            if (berryPickup != null) {
                setDebugState("Getting sweet berries (no better foods are present)");
                _currentResourceTask = berryPickup;
                return _currentResourceTask;
            }
        }

        // Look for food.
        setDebugState("Searching...");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.HAY_BLOCK);
        mod.getBlockTracker().stopTracking(Blocks.SWEET_BERRY_BUSH);
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().stopTracking(crop.cropBlock);
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.calculateInventoryFoodScore(mod) >= _unitsNeeded;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFoodTask task) {
            return task._unitsNeeded == _unitsNeeded;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect " + _unitsNeeded + " units of food.";
    }

    @Override
    protected String toHudString() {
        return "Getting food";
    }

    /**
     * Returns a task that mines a block and picks up its output.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, accept, maxRange, Double.POSITIVE_INFINITY);
    }

    // dropRange is for the sweep, which must not wander off after a bale somebody dropped a chunk away
    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange, double dropRange) {
        Predicate<BlockPos> acceptPlus = (blockPos) -> {
            if (!WorldHelper.canBreak(mod, blockPos)) return false;
            return accept.test(blockPos);
        };
        Optional<BlockPos> nearestBlock = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), acceptPlus, blockToCheck);

        if (nearestBlock.isPresent() && !nearestBlock.get().closerToCenterThan(mod.getPlayer().position(), maxRange)) {
            nearestBlock = Optional.empty();
        }

        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), itemToGrab)
                    .filter(drop -> drop.closerThan(mod.getPlayer(), dropRange));
        }
        boolean spotted = nearestBlock.isPresent() || nearestDrop.isPresent();
        // Collect hay until we have enough.
        if (spotted) {
            if (nearestDrop.isPresent()) {
                return new PickupDroppedItemTask(itemToGrab, Integer.MAX_VALUE);
            } else {
                DoToClosestBlockTask dig = new DoToClosestBlockTask(DestroyBlockTask::new, acceptPlus, blockToCheck);
                // a bale always drops itself, so the next one waits for it. (berries don't always, and crops go through
                // CollectCropTask)
                return itemToGrab == Items.HAY_BLOCK ? dig.expectDrops() : dig;
            }
        }
        return null;
    }

    // grown and in a chunk we can read (the age of an unloaded one is a guess) and ours to break
    private static boolean ripeAndBreakable(AltoClef mod, BlockPos pos) {
        return mod.getChunkTracker().isChunkLoaded(pos) && CropRules.ripe(mod.getWorld().getBlockState(pos)) && WorldHelper.canBreak(mod, pos);
    }

    // the food still wanted, in items of this crop. one more than we hold at the very least, a farm run for nothing is not a run
    private Task cropTask(AltoClef mod, CropTarget target, double potentialFood) {
        int held = mod.getItemStorage().getItemCount(target.cropItem);
        int more = (int) Math.max(1, Math.ceil((_unitsNeeded - potentialFood) / target.unitFood));
        return new CollectCropTask(new ItemTarget(target.cropItem, held + more), new Block[]{target.cropBlock}, new Item[]{target.seed},
                pos -> WorldHelper.canBreak(mod, pos));
    }

    // the hunted animal is dead but the loot of the kill is still on its way or lying there: the hunt is not over (see
    // KillAndLootTask.awaitingDrop)
    private boolean lootComing(AltoClef mod) {
        return _huntTask instanceof KillAndLootTask kill && kill.awaitingDrop(mod);
    }

    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, toAccept -> true, maxRange);
    }

    // the pillager outpost bales have a carved pumpkin on top and a pillager nearby. no thanks
    private static boolean isOutpostHay(AltoClef mod, BlockPos hay) {
        return mod.getWorld().getBlockState(hay.above()).getBlock() == Blocks.CARVED_PUMPKIN;
    }

    private static boolean haySweepable(AltoClef mod, BlockPos pos) {
        return pos.closerToCenterThan(mod.getPlayer().position(), HaySweep.RADIUS) && !isOutpostHay(mod, pos);
    }

    // the bales the sweep would still take: near us, not blacklisted (blockIsValid says no to those), not an outpost's
    private List<BlockPos> hayInReach(AltoClef mod) {
        List<BlockPos> reach = new ArrayList<>();
        if (!mod.getBlockTracker().isTracking(Blocks.HAY_BLOCK)) {
            return reach;
        }
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.HAY_BLOCK)) {
            if (haySweepable(mod, pos) && mod.getBlockTracker().blockIsValid(pos, Blocks.HAY_BLOCK) && WorldHelper.canBreak(mod, pos)) {
                reach.add(pos);
            }
        }
        return reach;
    }

    private boolean hayDropNearby(AltoClef mod) {
        return mod.getEntityTracker().itemDropped(Items.HAY_BLOCK)
                && mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), Items.HAY_BLOCK)
                .filter(drop -> drop.closerThan(mod.getPlayer(), HaySweep.RADIUS)).isPresent();
    }

    // bales in reach plus one for a dropped one, so the sweep does not quit with a bale lying at our feet
    private int hayLeft(AltoClef mod) {
        return hayInReach(mod).size() + (hayDropNearby(mod) ? 1 : 0);
    }

    private boolean sweepWanted(AltoClef mod) {
        _hayLeft = hayLeft(mod);
        boolean keep = HaySweep.keepSweeping(true, _hayLeft, mod.getItemStorage().getItemCountInventoryOnly(Items.HAY_BLOCK), _sweepTimer.getDuration());
        if (!keep) {
            _sweepOver = true;
        }
        return keep;
    }

    // potential says we have enough, but the pile is right here. null when there is nothing (left) to sweep
    private Task sweepHayOrNull(AltoClef mod) {
        if (_sweepOver) {
            return null;
        }
        int held = mod.getItemStorage().getItemCountInventoryOnly(Items.HAY_BLOCK);
        _hayLeft = hayLeft(mod);
        double elapsed = _sweepTimer == null ? 0 : _sweepTimer.getDuration();
        if (!HaySweep.keepSweeping(true, _hayLeft, held, elapsed)) {
            // not having started yet is not the end, the pile might just not be loaded in
            _sweepOver = _sweepTimer != null;
            return null;
        }
        if (_sweepTimer == null) {
            _sweepTimer = new TimerGame(HaySweep.BUDGET_SECONDS);
            _sweepTimer.reset();
        }
        Task hoe = hoeTaskOrNull(mod, _hayLeft);
        if (hoe != null) {
            return hoe;
        }
        Task sweep = pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, pos -> haySweepable(mod, pos), HaySweep.RADIUS, HaySweep.RADIUS);
        if (sweep == null) {
            _sweepOver = true;
            return null;
        }
        setDebugState("Sweeping the hay pile (" + _hayLeft + " left)");
        _sweepTask = sweep;
        _currentResourceTask = sweep;
        return sweep;
    }

    // a hoe is ~2x the swing speed on hay, worth a craft for a pile but only from what we already carry and a table we
    // already have: nobody goes tree hunting for a hoe. once per task, win or lose
    private Task hoeTaskOrNull(AltoClef mod, int pile) {
        if (_hoeDone || pile < HaySweep.HOE_PILE_MIN || !_hoeCheckTimer.elapsed()) {
            return null;
        }
        _hoeCheckTimer.reset();
        boolean hasHoe = mod.getItemStorage().hasItemInventoryOnly(HOES);
        boolean tableHandy = mod.getItemStorage().hasItemInventoryOnly(Items.CRAFTING_TABLE)
                || mod.getBlockTracker().getNearestWithinRange(mod.getPlayer().position(), HaySweep.HOE_TABLE_RANGE, Blocks.CRAFTING_TABLE).isPresent();
        int cobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int planks = mod.getItemStorage().getItemCountInventoryOnly(ItemHelper.PLANKS);
        int sticks = mod.getItemStorage().getItemCountInventoryOnly(Items.STICK);
        HaySweep.Hoe hoe = HaySweep.pickHoe(pile, hasHoe, tableHandy, cobble, planks, sticks);
        if (hoe == HaySweep.Hoe.NONE) {
            return null;
        }
        _hoeDone = true;
        _hoeTimer = new TimerGame(HaySweep.HOE_BUDGET_SECONDS);
        _hoeTimer.reset();
        _hoeTask = TaskCatalogue.getItemTask(hoe == HaySweep.Hoe.STONE ? "stone_hoe" : "wooden_hoe", 1);
        setDebugState("Making a hoe for the hay");
        return _hoeTask;
    }

    private record Prey(Entity entity, CookableFoodTarget food) {
    }

    // not a baby (no meat), not carrying a passenger (chicken jockey, the blacklist in onTick only knows the closest one)
    private static boolean edible(Entity entity) {
        return entity instanceof LivingEntity living && !living.isBaby() && !entity.isVehicle();
    }

    // the nearest animal of EVERY kind goes into the pot, plus the one we are already after (it might not be the nearest
    // of its kind any more), and FoodHunt picks. null when nothing edible is loaded
    private Task huntTaskOrNull(AltoClef mod) {
        Task hunt = killTaskOrNull(mod, Double.POSITIVE_INFINITY);
        if (hunt == null) {
            _hunted = null;
        }
        return hunt;
    }

    // same pot as huntTaskOrNull but only what is within radius, and it leaves the current hunt alone when nothing
    // qualifies. a hit sets the hunt fields, so the commit rule protects this kill too
    private Task nearbyPreyOrNull(AltoClef mod, double radius) {
        return killTaskOrNull(mod, radius);
    }

    private Task killTaskOrNull(AltoClef mod, double radius) {
        Vec3 me = mod.getPlayer().position();
        Map<Integer, Prey> prey = new HashMap<>();
        List<FoodHunt.Candidate> candidates = new ArrayList<>();
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (cookable.kind == null) {
                continue;
            }
            Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(CollectFoodTask::edible, cookable.mobToKill);
            nearest.ifPresent(e -> addPrey(me, e, cookable, prey, candidates));
        }
        // dead or blacklisted is not a candidate, so it can not be sticky either
        if (_hunted != null && huntedOk(mod) && !prey.containsKey(_hunted.getId())) {
            addPrey(me, _hunted, _huntedFood, prey, candidates);
        }
        List<FoodHunt.Candidate> pot = radius == Double.POSITIVE_INFINITY ? candidates : FoodHunt.within(candidates, radius);
        FoodHunt.Candidate pick = FoodHunt.choose(pot, _hunted == null ? -1 : _hunted.getId(), FoodHunt.isWoolWanted());
        if (pick == null) {
            return null;
        }
        Prey chosen = prey.get(pick.id());
        _hunted = chosen.entity();
        _huntedFood = chosen.food();
        setDebugState("Killing " + chosen.entity().getType().getDescriptionId());
        // one more than we hold: with the old flat 1 a bag that already had a mutton called the sheep hunt finished before it began
        int held = mod.getItemStorage().getItemCount(chosen.food().getRaw());
        _huntTask = new KillAndLootTask(chosen.entity(), new ItemTarget(chosen.food().getRaw(), held + 1));
        return _huntTask;
    }

    // mid fight and close: finish it. the hay will still be there in thirty seconds
    private boolean huntCommitted(AltoClef mod) {
        if (_hunted == null) {
            return false;
        }
        Vec3 me = mod.getPlayer().position();
        Vec3 at = _hunted.position();
        double distance = FoodHunt.distance(at.x - me.x, at.y - me.y, at.z - me.z);
        return FoodHunt.keepHunting(huntedOk(mod), distance);
    }

    private boolean huntedOk(AltoClef mod) {
        return _hunted.isAlive() && edible(_hunted) && mod.getEntityTracker().isEntityReachable(_hunted);
    }

    private boolean huntStillOn(AltoClef mod) {
        return _hunted != null && huntedOk(mod);
    }

    private static void addPrey(Vec3 me, Entity e, CookableFoodTarget food, Map<Integer, Prey> prey, List<FoodHunt.Candidate> candidates) {
        Vec3 at = e.position();
        double distance = FoodHunt.distance(at.x - me.x, at.y - me.y, at.z - me.z);
        prey.put(e.getId(), new Prey(e, food));
        candidates.add(new FoodHunt.Candidate(e.getId(), food.kind, distance));
    }

    /**
     * Returns a task that picks up a dropped item.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab, double maxRange) {
        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().position(), itemToGrab);
        }
        if (nearestDrop.isPresent()) {
            if (nearestDrop.get().closerThan(mod.getPlayer(), maxRange)) {
                return new PickupDroppedItemTask(new ItemTarget(itemToGrab), true);
            }
            //return new GetToBlockTask(nearestDrop.getBlockPos(), false);
        }
        return null;
    }

    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab) {
        return pickupTaskOrNull(mod, itemToGrab, Double.POSITIVE_INFINITY);
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTarget {
        public String rawFood;
        public String cookedFood;
        public Class mobToKill;
        public FoodHunt.Kind kind;

        public CookableFoodTarget(String rawFood, String cookedFood, Class mobToKill, FoodHunt.Kind kind) {
            this.rawFood = rawFood;
            this.cookedFood = cookedFood;
            this.mobToKill = mobToKill;
            this.kind = kind;
        }

        public CookableFoodTarget(String rawFood, Class mobToKill, FoodHunt.Kind kind) {
            this(rawFood, "cooked_" + rawFood, mobToKill, kind);
        }

        private Item getRaw() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(rawFood))[0];
        }

        private Item getCooked() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(cookedFood))[0];
        }

        public int getCookedUnits() {
            assert getCooked().components().get(DataComponents.FOOD) != null;
            return Objects.requireNonNull(getCooked().components().get(DataComponents.FOOD)).nutrition();
        }

        public boolean isFish() {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTargetFish extends CookableFoodTarget {

        public CookableFoodTargetFish(String rawFood, Class mobToKill) {
            super(rawFood, mobToKill, null);
        }

        @Override
        public boolean isFish() {
            return true;
        }
    }

    private static class CropTarget {
        public Item cropItem;
        public Block cropBlock;
        public Item seed;
        public double unitFood;

        public CropTarget(Item cropItem, Block cropBlock, Item seed, double unitFood) {
            this.cropItem = cropItem;
            this.cropBlock = cropBlock;
            this.seed = seed;
            this.unitFood = unitFood;
        }
    }
}
