package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import java.util.Optional;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

public class EnsureFreeInventorySlotTask extends Task {

    private boolean _stuck = false;

    @Override
    protected void onStart(AltoClef mod) {
        _stuck = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        Optional<Slot> containerSlot = cursorStack.isEmpty() ? Optional.empty()
                : mod.getItemStorage().getSlotThatCanFitInOpenContainer(cursorStack, false);
        FreeSlotPlan.Action action = FreeSlotPlan.plan(cursorStack.isEmpty(), ItemHelper.canThrowAwayStack(mod, cursorStack),
                garbage.isPresent(), containerSlot.isPresent());
        _stuck = action == FreeSlotPlan.Action.STUCK;
        switch (action) {
            case CLICK_GARBAGE -> {
                setDebugState("Picking up garbage");
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ClickType.PICKUP);
            }
            case THROW_CURSOR -> {
                setDebugState("Throwing the held stack");
                LookHelper.randomOrientation(mod);
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            }
            case PUT_BACK -> {
                setDebugState("Putting the held stack back");
                mod.getSlotHandler().clickSlot(containerSlot.get(), 0, ClickType.PICKUP);
            }
            default -> setDebugState("All items are protected.");
        }
        return null;
    }

    // nothing to throw and nowhere to put the held stack. a parent that asks isFinished (PickupFromContainerTask) moves
    // on, the rest were already going to wait on their own watchdogs, only now we stop clicking at nothing
    @Override
    public boolean isFinished(AltoClef mod) {
        return _stuck;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof EnsureFreeInventorySlotTask;
    }

    @Override
    protected String toDebugString() {
        return "Ensuring inventory is free";
    }

    @Override
    protected String toHudString() {
        return "Making room in the inventory";
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}
