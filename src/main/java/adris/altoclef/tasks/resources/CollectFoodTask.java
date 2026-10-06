package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
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
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarrotBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.PotatoBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectFoodTask extends Task {

    // Represents order of preferred mobs to least preferred
    private static final CookableFoodTarget[] COOKABLE_FOODS = new CookableFoodTarget[]{
            new CookableFoodTarget("porkchop", Pig.class),
            new CookableFoodTarget("beef", Cow.class),
            new CookableFoodTarget("chicken", Chicken.class),
            new CookableFoodTarget("mutton", Sheep.class),
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
            new CropTarget(Items.WHEAT, Blocks.WHEAT),
            new CropTarget(Items.CARROT, Blocks.CARROTS)
    };

    // every hoe, so whichever one we end up holding is protected and counts as "have one"
    private static final Item[] HOES = new Item[]{
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };

    private final double _unitsNeeded;
    private final TimerGame _checkNewOptionsTimer = new TimerGame(10);
    private SmeltInSmokerTask _smeltTask = null;
    private Task _currentResourceTask = null;

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
            _currentResourceTask = null;
        }

        // the sweep ends when the pile does, not on the next 10 s check
        if (_sweepTask != null && _currentResourceTask == _sweepTask && !sweepWanted(mod)) {
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
                if (rawCount > 0) {
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
            // Hay blocks
            Task hayTaskBlock = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 300);
            if (hayTaskBlock != null) {
                Task hoe = hoeTaskOrNull(mod, hayInReach(mod).size());
                if (hoe != null) {
                    return hoe;
                }
                setDebugState("Collecting Hay");
                _currentResourceTask = hayTaskBlock;
                return _currentResourceTask;
            }
            // Crops
            for (CropTarget target : CROPS) {
                // If crops are nearby. Do not replant cause we don't care.
                Task t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, (blockPos -> {
                    BlockState s = mod.getWorld().getBlockState(blockPos);
                    Block b = s.getBlock();
                    if (b instanceof CropBlock) {
                        boolean isWheat = !(b instanceof PotatoBlock || b instanceof CarrotBlock || b instanceof BeetrootBlock);
                        if (isWheat) {
                            // Chunk needs to be loaded for wheat maturity to be checked.
                            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                                return false;
                            }
                            // Prune if we're not mature/fully grown wheat.
                            CropBlock crop = (CropBlock) b;
                            return crop.isMaxAge(s);
                        }
                    }
                    // Unbreakable.
                    return WorldHelper.canBreak(mod, blockPos);
                    // We're not wheat so do NOT reject.
                }), 96);
                if (t != null) {
                    setDebugState("Harvesting " + target.cropItem.getDescriptionId());
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Cooked foods
            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity livingEntity && !livingEntity.isBaby();
                Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(notBaby, cookable.mobToKill);
                if (nearest.isPresent()) {
                    setDebugState("Killing " + nearest.get().getType().getDescriptionId());
                    _currentResourceTask = killTaskOrNull(nearest.get(), notBaby, cookable.getRaw());
                    return _currentResourceTask;
                }
//                if (nearest.isEmpty()) continue; // ?? This crashed once?
//                int hungerPerformance = cookable.getCookedUnits();
//                double sqDistance = nearest.get().squaredDistanceTo(mod.getPlayer());
//                double score = (double) 100 * hungerPerformance / (sqDistance);
//                if (cookable.isFish()) {
//                    score *= FISH_PENALTY;
//                }
//                if (score > bestScore) {
//                    bestScore = score;
//                    bestEntity = nearest.get();
//                    bestRawFood = cookable.getRaw();
//                }
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
                return new DoToClosestBlockTask(DestroyBlockTask::new, acceptPlus, blockToCheck);
            }
        }
        return null;
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

    private Task killTaskOrNull(Entity entity, Predicate<Entity> entityPredicate, Item itemToGrab) {
        return new KillAndLootTask(entity.getClass(), entityPredicate, new ItemTarget(itemToGrab, 1));
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

        public CookableFoodTarget(String rawFood, String cookedFood, Class mobToKill) {
            this.rawFood = rawFood;
            this.cookedFood = cookedFood;
            this.mobToKill = mobToKill;
        }

        public CookableFoodTarget(String rawFood, Class mobToKill) {
            this(rawFood, "cooked_" + rawFood, mobToKill);
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
            super(rawFood, mobToKill);
        }

        @Override
        public boolean isFish() {
            return true;
        }
    }

    private static class CropTarget {
        public Item cropItem;
        public Block cropBlock;

        public CropTarget(Item cropItem, Block cropBlock) {
            this.cropItem = cropItem;
            this.cropBlock = cropBlock;
        }
    }
}
