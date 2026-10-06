package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CraftDragPlan;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// fills several grid slots that want the same ingredient with vanilla drag clicking instead of one right click per
// item. 3 slots of 20 wheat was ~60 clicks at 0.2s each, and the table craft watchdog gave up before it finished
public class DragSplitToSlotsTask extends Task {

    private final ItemTarget _item;
    private final List<Slot> _slots;
    private final int _perSlot;

    public DragSplitToSlotsTask(ItemTarget item, List<Slot> slots, int perSlot) {
        _item = item;
        _slots = slots;
        _perSlot = perSlot;
    }

    // the item we are spreading: whatever is on the cursor if it fits, else what a slot already holds, else anything
    // we own that matches. null if we don't have any
    private static Item chooseItem(AltoClef mod, ItemTarget item, List<Slot> slots) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && item.matches(cursor.getItem())) return cursor.getItem();
        for (Slot slot : slots) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty() && item.matches(stack.getItem())) return stack.getItem();
        }
        for (Item match : item.getMatches()) {
            if (mod.getItemStorage().hasItemInventoryOnly(match)) return match;
        }
        return null;
    }

    // how much more each slot wants. a slot holding some OTHER item (or already full) gets 0, a drag onto it would
    // just be rejected and the single slot path deals with it
    private static int[] deficits(List<Slot> slots, Item chosen, int perSlot) {
        int[] out = new int[slots.size()];
        for (int i = 0; i < out.length; ++i) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slots.get(i));
            if (stack.isEmpty()) {
                out[i] = perSlot;
            } else if (stack.getItem() == chosen) {
                out[i] = Math.max(0, perSlot - stack.getCount());
            }
        }
        return out;
    }

    /**
     * How many of these slots still want items and can take them by drag. The crafting task only hands the group to us
     * when this is two or more, a drag over one slot is just a click.
     */
    public static int activeCount(AltoClef mod, ItemTarget item, List<Slot> slots, int perSlot) {
        Item chosen = chooseItem(mod, item, slots);
        if (chosen == null) return 0;
        int n = 0;
        for (int d : deficits(slots, chosen, perSlot)) {
            if (d > 0) ++n;
        }
        return n;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!mod.getSlotHandler().canDoSlotAction()) return null;
        Item chosen = chooseItem(mod, _item, _slots);
        if (chosen == null) return null;
        int[] deficits = deficits(_slots, chosen, _perSlot);
        int n = 0;
        int dmin = Integer.MAX_VALUE;
        for (int d : deficits) {
            if (d > 0) {
                ++n;
                dmin = Math.min(dmin, d);
            }
        }
        if (n < 2) return null;

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && cursor.getItem() != chosen) {
            // somebody else's stack is in the way
            return new EnsureFreeCursorSlotTask();
        }
        if (cursor.isEmpty()) {
            pickUpSource(mod, chosen, n * dmin);
            return null;
        }

        Slot returnSlot = findReturnSlot(mod, cursor);
        int room = returnSlot == null ? 0 : roomFor(returnSlot, cursor);
        CraftDragPlan plan = CraftDragPlan.plan(deficits, cursor.getCount(), room);
        switch (plan.kind) {
            case PUT_BACK -> {
                setDebugState("Cursor too small to drag, putting it back");
                if (returnSlot == null) return new EnsureFreeCursorSlotTask();
                mod.getSlotHandler().clickSlot(returnSlot, 0, ClickType.PICKUP);
            }
            case DRAG -> {
                List<Slot> covered = new ArrayList<>();
                for (int i : plan.slots) covered.add(_slots.get(i));
                setDebugState((plan.evenSplit ? "Left" : "Right") + " dragging " + chosen.getDescriptionId() + " over " + covered.size() + " slots");
                mod.getSlotHandler().dragSplit(covered, plan.evenSplit, plan.rounds, returnSlot, plan.returnFirst);
            }
            default -> {
            }
        }
        return null;
    }

    private void pickUpSource(AltoClef mod, Item chosen, int need) {
        List<Slot> sources = new ArrayList<>();
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, chosen)) {
            if (!Slot.isCursor(slot)) sources.add(slot);
        }
        int[] counts = new int[sources.size()];
        for (int i = 0; i < counts.length; ++i) {
            counts[i] = StorageHelper.getItemStackInSlot(sources.get(i)).getCount();
        }
        int best = CraftDragPlan.pickSource(counts, need);
        if (best == -1) return;
        setDebugState("Picking up " + counts[best] + " " + chosen.getDescriptionId() + " to drag");
        mod.getSlotHandler().clickSlot(sources.get(best), 0, ClickType.PICKUP);
    }

    // the inventory slot with the most room for the cursor item, surplus and leftovers go back there
    private static Slot findReturnSlot(AltoClef mod, ItemStack cursor) {
        Slot best = null;
        int bestRoom = 0;
        for (Slot slot : mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(cursor, true)) {
            int room = roomFor(slot, cursor);
            if (room > bestRoom) {
                bestRoom = room;
                best = slot;
            }
        }
        return best;
    }

    private static int roomFor(Slot slot, ItemStack cursor) {
        ItemStack there = StorageHelper.getItemStackInSlot(slot);
        if (there.isEmpty()) return cursor.getMaxStackSize();
        if (!ItemStack.isSameItemSameComponents(there, cursor)) return 0;
        return Math.max(0, there.getMaxStackSize() - there.getCount());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return activeCount(mod, _item, _slots, _perSlot) < 2;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DragSplitToSlotsTask task) {
            return task._perSlot == _perSlot && task._slots.equals(_slots) && task._item.equals(_item);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Dragging " + _item + " over " + _slots.size() + " slots";
    }

    @Override
    protected String toHudString() {
        return "Filling the grid";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
