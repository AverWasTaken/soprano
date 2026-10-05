package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// GamerFacts for the real game. refresh() is called once per tick by the engine and walks the inventory once, everything
// else is a field read, so the handlers' isDone checks stay cheap no matter how many times they are asked
public final class MinecraftFacts implements GamerFacts {
    private final AltoClef mod;
    // fastutil so a tick of counting does not box a pile of integers
    private final Object2IntOpenHashMap<Item> counts = new Object2IntOpenHashMap<>(64);
    private final ItemStack[] worn = new ItemStack[5];

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
        return true;
    }

    private void countItems(Player player) {
        counts.clear();
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
        for (int i = 0; i < 4; i++) {
            worn[i] = inv.getArmor(i);
        }
        worn[4] = inv.getItem(Inventory.SLOT_OFFHAND);
        summarize();
    }

    private void add(ItemStack stack) {
        if (!stack.isEmpty()) {
            counts.addTo(stack.getItem(), stack.getCount());
        }
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

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return counts.getInt(item);
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
