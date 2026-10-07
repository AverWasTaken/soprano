package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.util.helpers.FoodHelper;
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
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private int buildBlocks;
    private int fingerprint;
    private int x;
    private int y;
    private int z;
    private long gameTime;
    // sticky: once the credits showed up the run is over, the screen going away again must not undo that
    private boolean credits;
    private boolean tableNearby;

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
        tableNearby = findTableNearby(player);
        return true;
    }

    // one of the tables this run placed, still standing, and not further than the pickup would walk (the same WalkCost
    // budget StationPickup forgets them at). overworld only, that is where placedTables are recorded
    private boolean findTableNearby(Player player) {
        if (state == null || dimension != Dimension.OVERWORLD || state.placedTables.isEmpty()) {
            return false;
        }
        double budget = GamerConfigs.get().overworld.tableRecoverRadius;
        for (RunState.Pos pos : state.placedTables) {
            if (OwnTables.walkCost(pos, player.getX(), player.getY(), player.getZ()) > budget) {
                continue;
            }
            BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
            if (mod.getChunkTracker().isChunkLoaded(at) && mod.getWorld().getBlockState(at).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean tablePlacedNearby() {
        return tableNearby;
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
        for (int i = 0; i < 4; i++) {
            worn[i] = inv.getArmor(i);
        }
        worn[4] = inv.getItem(Inventory.SLOT_OFFHAND);
        summarize();
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
                food += item.components().get(DataComponents.FOOD).nutrition() * n;
            } else if (kind != FoodHelper.Kind.NOT_FOOD) {
                junk += item.components().get(DataComponents.FOOD).nutrition() * n;
            }
            // same rule as StorageHelper.getBuildingMaterialCount: throwaway blocks, minus the ones that fall over
            if (item instanceof BlockItem && item != Items.GRAVEL && item != Items.SAND && AltoSettings.isThrowaway(item)) {
                build += n;
            }
        }
        foodUnits = food;
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
    public boolean creditsShown() {
        return credits;
    }

    @Override
    public int inventoryFingerprint() {
        return fingerprint;
    }
}
