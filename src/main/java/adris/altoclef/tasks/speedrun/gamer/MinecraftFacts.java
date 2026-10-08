package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.container.FurnaceReuse;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

// GamerFacts for the real game. refresh() is called once per tick by the engine and walks the inventory once, everything
// else is a field read, so the handlers' isDone checks stay cheap no matter how many times they are asked
public final class MinecraftFacts implements GamerFacts {
    private final AltoClef mod;
    // fastutil so a tick of counting does not box a pile of integers
    private final Object2IntOpenHashMap<Item> counts = new Object2IntOpenHashMap<>(64);
    private final Object2IntOpenHashMap<Item> spent = new Object2IntOpenHashMap<>(4);
    private final ItemStack[] worn = new ItemStack[5];

    private RunState state;
    private Dimension dimension = Dimension.OVERWORLD;
    private int armorPoints;
    private int foodUnits;
    private int junkFoodUnits;
    // food in the input and output slots of the furnace-like screen that is open right now, at planned value
    private int stationFood;
    // the block that screen is, null when nothing says
    private RunState.Pos stationAt;
    private int buildBlocks;
    private int fingerprint;
    private int x;
    private int y;
    private int z;
    private long gameTime;
    // sticky: once the credits showed up the run is over, the screen going away again must not undo that
    private boolean credits;
    private boolean tableNearby;
    // a table is recorded and was out of budget last tick, see OwnTables.returnBudget
    private boolean tableFar;
    private boolean furnaceNearby;
    private boolean smokerNearby;

    public MinecraftFacts(AltoClef mod) {
        this.mod = mod;
        java.util.Arrays.fill(worn, ItemStack.EMPTY);
    }

    // false when there is no player to read (loading screen), the old numbers stay
    public boolean refresh() {
        Player player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) {
            return false;
        }
        dimension = WorldHelper.getCurrentDimension();
        x = player.getBlockX();
        y = player.getBlockY();
        z = player.getBlockZ();
        gameTime = mod.getWorld().getGameTime();
        armorPoints = player.getArmorValue();
        if (Minecraft.getInstance().screen instanceof WinScreen) {
            credits = true;
        }
        countItems(player);
        // once the table has gone out of the budget it only comes back when we are well inside it (OwnTables.returnBudget), or
        // the plan flips between a log trip and the cobble at the line (the walk down to the cobble and back up to a tree)
        tableNearby = state != null && standingNearby(player, state.placedTables, Blocks.CRAFTING_TABLE,
                OwnTables.returnBudget(tableFar, WalkCost.STATION_BUDGET));
        tableFar = state != null && !tableNearby && !state.placedTables.isEmpty();
        // furnaces keep no distance test: their reuse rule lives in FurnaceReuse and a far one is forgotten at a boundary
        furnaceNearby = state != null && standingNearby(player, state.placedFurnaces, Blocks.FURNACE, Double.POSITIVE_INFINITY);
        smokerNearby = state != null && smokerWorthWalking(player);
        return true;
    }

    // a smoker of ours the cook should walk back to. unlike the table this one does have a distance test: the cook need is a
    // choice between this smoker and a new station, and a smoker 80 blocks below us (the 22:09 run: 40 s of walking down a
    // cave) is not a reason to pick it. the line is FurnaceReuse's, with a margin once counted so it cannot flap
    private boolean smokerWorthWalking(Player player) {
        if (dimension != Dimension.OVERWORLD || state.placedSmokers.isEmpty()) {
            return false;
        }
        double best = Double.MAX_VALUE;
        for (RunState.Pos pos : state.placedSmokers) {
            BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
            if (mod.getChunkTracker().isChunkLoaded(at) && !mod.getWorld().getBlockState(at).is(Blocks.SMOKER)) {
                continue;
            }
            best = Math.min(best, OwnTables.walkCost(pos, player.getX(), player.getY(), player.getZ()));
        }
        return best != Double.MAX_VALUE && FurnaceReuse.smokerWorthWalking(smokerNearby, best);
    }

    // one of the stations this run placed (tables, furnaces) that we still own, and close enough to walk back to. the budget
    // is the table's: WalkCost.STATION_BUDGET, the line CraftInTableTask and StationPickup use, so the plan only counts
    // a table as held when crafting will really walk to it (a recorded one 60 blocks off meant no planks in the plan and then
    // a second table crafted mid-cave). this used to have no distance test at all because a budget of 10 flipped the plan
    // on a walk down to the cobble; the latch in refresh() is what keeps the line from flapping now. an unloaded chunk keeps
    // its station if it is in budget
    private boolean standingNearby(Player player, List<RunState.Pos> placed, Block kind, double budget) {
        if (dimension != Dimension.OVERWORLD || placed.isEmpty()) {
            return false;
        }
        return OwnTables.anyHeld(placed, pos -> {
            BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
            return !mod.getChunkTracker().isChunkLoaded(at) || mod.getWorld().getBlockState(at).is(kind);
        }, player.getX(), player.getY(), player.getZ(), budget);
    }

    @Override
    public boolean tablePlacedNearby() {
        return tableNearby;
    }

    @Override
    public boolean furnacePlacedNearby() {
        return furnaceNearby;
    }

    @Override
    public boolean smokerPlacedNearby() {
        return smokerNearby;
    }

    @Override
    public boolean cookSuspended() {
        return CookTrip.suspended(gameTime);
    }

    @Override
    public String cookStation() {
        return CookTrip.committed(gameTime);
    }

    private void countItems(Player player) {
        counts.clear();
        spent.clear();
        Inventory inv = player.getInventory();
        // main + hotbar (0..35), armor (36..39) and the offhand (40) are all in the container
        for (int i = 0; i < inv.getContainerSize(); i++) {
            add(inv.getItem(i));
        }
        // the cursor and the 2x2 grid are "ours" for a moment while we craft, not gone
        add(player.containerMenu.getCarried());
        for (int slot = 1; slot <= 4 && slot < player.inventoryMenu.slots.size(); slot++) {
            add(player.inventoryMenu.getSlot(slot).getItem());
        }
        // same for the 3x3 of an open crafting table. this was missing and it made a smoker craft thrash: the furnace is
        // a single item, so the moment it moved into the grid we owned 0 furnaces, the kit said "make a furnace" and
        // swept the grid, and the food task put it right back. ~55 times. stacked ingredients never hit it
        if (player.containerMenu instanceof CraftingMenu table) {
            // slot 0 is the output, which we do NOT own until it is taken (see InventorySubTracker)
            for (int slot = 1; slot <= 9 && slot < table.slots.size(); slot++) {
                add(table.getSlot(slot).getItem());
            }
        }
        // meat on its way into a smoker (or cooked on its way out) is not in the bag for a few ticks and not a job yet. the
        // slots are not added to counts: ore in a furnace is the early load's business (earlyLoadInFlight), and a raw iron that
        // is not "in the bag" is how that latch works. only the food sum reads them
        stationFood = 0;
        stationAt = null;
        if (player.containerMenu instanceof AbstractFurnaceMenu furnace) {
            stationFood = foodIn(furnace.getSlot(0).getItem()) + foodIn(furnace.getSlot(2).getItem());
            // which block the screen is, so only that station's own job hides its food (FoodGate.inStation)
            stationAt = mod.getItemStorage().getLastBlockPosInteraction().map(p -> new RunState.Pos(p.getX(), p.getY(), p.getZ())).orElse(null);
        }
        for (int i = 0; i < 4; i++) {
            worn[i] = inv.getArmor(i);
        }
        worn[4] = inv.getItem(Inventory.SLOT_OFFHAND);
        summarize();
    }

    private static int foodIn(ItemStack stack) {
        if (stack.isEmpty() || FoodHelper.kindOf(stack.getItem()) != FoodHelper.Kind.NORMAL) {
            return 0;
        }
        return FoodHelper.plannedNutrition(stack.getItem()) * stack.getCount();
    }

    private void add(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // a pick on its last legs is not a pick we own, the planner makes the next one while there is still a table (the
        // catalogue still sees it in the bag, hence the separate count)
        if (KitPlanner.wornOut(stack.getItem(), stack.getDamageValue(), stack.getMaxDamage())) {
            spent.addTo(stack.getItem(), stack.getCount());
            return;
        }
        counts.addTo(stack.getItem(), stack.getCount());
    }

    // one pass over the distinct item types: food, building blocks and the "did anything change" number together
    private void summarize() {
        int food = 0;
        int junk = 0;
        int build = 0;
        int fp = 0;
        for (Object2IntMap.Entry<Item> e : counts.object2IntEntrySet()) {
            Item item = e.getKey();
            int n = e.getIntValue();
            fp += (BuiltInRegistries.ITEM.getId(item) + 1) * 7919 + n * 31 * (BuiltInRegistries.ITEM.getId(item) + 17);
            FoodHelper.Kind kind = FoodHelper.kindOf(item);
            if (kind == FoodHelper.Kind.NORMAL) {
                // raw meat at its cooked value (FoodHelper.plannedNutrition), inventory only: food in a furnace is not here
                food += FoodHelper.plannedNutrition(item) * n;
            } else if (kind != FoodHelper.Kind.NOT_FOOD) {
                junk += item.components().get(DataComponents.FOOD).nutrition() * n;
            }
            // same rule as StorageHelper.getBuildingMaterialCount: throwaway blocks, minus the ones that fall over
            if (item instanceof BlockItem && item != Items.GRAVEL && item != Items.SAND && AltoSettings.isThrowaway(item)) {
                build += n;
            }
        }
        foodUnits = food + FoodGate.inStation(stationFood, furnaceJobs(), stationAt, dimension.name());
        junkFoodUnits = junk;
        buildBlocks = build;
        fingerprint = fp;
    }

    // the run state the jobs live in, set once it is loaded (the facts exist before the state does)
    public void useState(RunState state) {
        this.state = state;
    }

    // RunState keeps every dimension's jobs, the planner only cares about the ones we can walk to
    @Override
    public List<RunState.FurnaceJob> furnaceJobs() {
        if (state == null || state.furnaceJobs.isEmpty()) {
            return List.of();
        }
        List<RunState.FurnaceJob> here = new ArrayList<>();
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if (job.dimension.equals(dimension.name())) {
                here.add(job);
            }
        }
        return here;
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return counts.getInt(item);
    }

    @Override
    public int spent(Item item) {
        return spent.getInt(item);
    }

    @Override
    public boolean armorEquipped(Item item) {
        for (ItemStack stack : worn) {
            if (stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int armorPoints() {
        return armorPoints;
    }

    @Override
    public int foodUnits() {
        return foodUnits;
    }

    @Override
    public int junkFoodUnits() {
        return junkFoodUnits;
    }

    @Override
    public int buildBlocks() {
        return buildBlocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public boolean earlyIronPick() {
        return baritone.Baritone.settings().altoEarlyIronPick.value;
    }

    @Override
    public boolean earlyLoadInFlight() {
        return state != null && EarlyIronPick.inFlight(state.earlyLoadTick, gameTime);
    }

    @Override
    public boolean creditsShown() {
        return credits;
    }

    @Override
    public int inventoryFingerprint() {
        return fingerprint;
    }
}
