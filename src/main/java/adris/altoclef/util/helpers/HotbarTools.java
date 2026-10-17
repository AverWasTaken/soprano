package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

// baritone's ToolSet only looks at the hotbar and InventoryBehavior only ever brings a pickaxe down, so the axe or shovel
// GatherBillTask made for the job sat in the bag and every log got punched. one swap into a hotbar slot nobody else
// shuffles: 0 is the pickaxe's, 1 is forceEquipItem's, 7 and 8 are the throwaways. 2-6 only see temp swaps, and those
// take empty slots first
public final class HotbarTools {

    private HotbarTools() {
    }

    // the axe for logs and planks-y stuff, the shovel for dirt and sand. pickaxes are InventoryBehavior's job already
    public static void forBlocks(AltoClef mod, Block... blocks) {
        boolean axe = false;
        boolean shovel = false;
        for (Block block : blocks) {
            axe |= block.defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE);
            shovel |= block.defaultBlockState().is(BlockTags.MINEABLE_WITH_SHOVEL);
        }
        if (axe) bringDown(mod, "axe");
        if (shovel) bringDown(mod, "shovel");
    }

    // the best one of this ItemHelper.toolKind from the bag into the hotbar, unless the hotbar has one already
    public static void bringDown(AltoClef mod, String kind) {
        LocalPlayer player = mod.getPlayer();
        // a swap click goes to whatever menu is open, with a chest up the slot numbers would hit the chest
        if (player == null || player.containerMenu != player.inventoryMenu || mod.getExtraBaritoneSettings().isInteractionPaused()) {
            return;
        }
        Inventory inv = player.getInventory();
        int best = -1;
        int bestUses = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !kind.equals(ItemHelper.toolKind(stack.getItem()))) continue;
            if (i < 9) return;
            int uses = ItemHelper.getToolDurability(stack.getItem());
            if (uses > bestUses) {
                best = i;
                bestUses = uses;
            }
        }
        if (best < 0) return;
        int to = slotFor(inv);
        if (to < 0) return;
        // SWAP with the hotbar index as the button, the selected slot stays where it is. through baritone's gate so
        // allowInventory, the move delay and standing still hold here too (2b2t does not love a swap mid sprint)
        int from = best;
        mod.getClientBaritone().getInventoryBehavior().inventoryMoveIfAllowed(
                () -> mod.getSlotHandler().clickSlot(new PlayerSlot(from), to, ClickType.SWAP));
    }

    // an empty one of 2-6 that isn't in our hand, else one of those that isn't holding a tool (so the axe and the shovel
    // don't take turns kicking each other out). -1 when 2-6 are all tools
    private static int slotFor(Inventory inv) {
        int selected = inv.selected;
        for (int h = 2; h <= 6; h++) {
            if (h != selected && inv.getItem(h).isEmpty()) return h;
        }
        for (int h = 6; h >= 2; h--) {
            if (h != selected && !ItemHelper.isTool(inv.getItem(h).getItem())) return h;
        }
        return -1;
    }
}
