package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

public class ReceiveCraftingOutputSlotTask extends Task implements ITaskUsesCraftingGrid {

    private final int _toTake;
    private final Slot _slot;

    public ReceiveCraftingOutputSlotTask(Slot slot, int toTake) {
        _slot = slot;
        _toTake = toTake;
    }

    public ReceiveCraftingOutputSlotTask(Slot slot, boolean all) {
        this(slot, all ? Integer.MAX_VALUE : 1);
    }

    // How many multiples of the current crafting recipe can we craft?
    private static int getCraftMultipleCount(AltoClef mod) {
        int minNonZero = Integer.MAX_VALUE;
        boolean found = false;
        for (Slot check : (StorageHelper.isBigCraftingOpen() ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(check);
            if (!stack.isEmpty()) {
                minNonZero = Math.min(stack.getCount(), minNonZero);
                found = true;
            }
        }
        if (!found)
            return 0;
        return minNonZero;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    // how many more of this stack the main inventory and hotbar can swallow. armor and the offhand don't count, a
    // quick move out of the output never goes there. this is what a quick move actually needs: it crafts the whole
    // multiple in one go, and the old "can ONE stack partially fit somewhere" check said yes with a single free
    // slot of room left, then vanilla ran out of space halfway and left ingredients stranded in the grid
    private static int inventoryRoomFor(AltoClef mod, ItemStack stack) {
        int max = stack.getMaxStackSize();
        int room = 0;
        for (ItemStack inSlot : mod.getPlayer().getInventory().items) {
            if (inSlot.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
                room += Math.max(0, max - inSlot.getCount());
            }
        }
        return room;
    }

    // the cursor and the output can share a stack, as long as they are the same thing and it fits. ItemHelper's
    // canStackTogether says no to a stack that lands exactly on the max (strict <), which broke the cursor for nothing
    private static boolean cursorTakesOutput(ItemStack cursor, ItemStack output) {
        if (cursor.isEmpty() || output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(cursor, output) && cursor.getCount() + output.getCount() <= cursor.getMaxStackSize();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack inOutput = StorageHelper.getItemStackInSlot(_slot);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursorTakesOutput(cursor, inOutput)) {
            return new EnsureFreeCursorSlotTask();
        }
        int craftCount = inOutput.getCount() * getCraftMultipleCount(mod);
        int weWantToAddToInventory = _toTake - mod.getItemStorage().getItemCountInventoryOnly(inOutput.getItem());
        boolean takeAll = weWantToAddToInventory >= craftCount;
        // no room for the whole multiple means the quick move would stop halfway, so go through the cursor instead
        if (takeAll && inventoryRoomFor(mod, inOutput) >= craftCount) {
            setDebugState("Quick moving output");
            mod.getSlotHandler().clickSlot(_slot, 0, ClickType.QUICK_MOVE);
            return null;
        }
        setDebugState("Picking up output");
        mod.getSlotHandler().clickSlot(_slot, 0, ClickType.PICKUP);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ReceiveCraftingOutputSlotTask task) {
            return task._slot.equals(_slot) && task._toTake == _toTake;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Receiving output";
    }

    @Override
    protected String toHudString() {
        return "Taking the result";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
