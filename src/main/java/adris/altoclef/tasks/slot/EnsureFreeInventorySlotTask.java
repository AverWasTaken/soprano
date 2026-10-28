package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.ThrowAim;
import adris.altoclef.util.slots.Slot;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public class EnsureFreeInventorySlotTask extends Task {

    private boolean _stuck = false;
    // where the next throw points, picked once so a tunnel's two equal ends can't flip us back and forth. cleared only once
    // the cursor is really empty (a click can be refused during the slot handler's move delay)
    private boolean _hasAim = false;
    private float _aimYaw;
    private int _aimSince;

    @Override
    protected void onStart(AltoClef mod) {
        _stuck = false;
        _hasAim = false;
    }

    private void pickAim(AltoClef mod) {
        if (_hasAim) return;
        _hasAim = true;
        _aimYaw = ThrowAim.pickYaw(mod, ThreadLocalRandom.current());
        _aimSince = WorldHelper.getTicks();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        // only plain storage takes things back. a furnace input or a crafting grid would eat the stack (or hand it back
        // on close into a bag with no room), which is the loss this is here to stop
        boolean storageOpen = ContainerType.screenHandlerMatches(ContainerType.CHEST) || ContainerType.screenHandlerMatches(ContainerType.SHULKER);
        Optional<Slot> containerSlot = cursorStack.isEmpty() || !storageOpen ? Optional.empty()
                : mod.getItemStorage().getSlotThatCanFitInOpenContainer(cursorStack, false);
        FreeSlotPlan.Action action = FreeSlotPlan.plan(cursorStack.isEmpty(), ItemHelper.canDropStack(mod, cursorStack),
                garbage.isPresent(), containerSlot.isPresent());
        _stuck = action == FreeSlotPlan.Action.STUCK;
        // an empty cursor means whatever we threw is gone (a refused click leaves the stack in hand, so no re-pick there)
        if (cursorStack.isEmpty()) {
            _hasAim = false;
        }
        switch (action) {
            case CLICK_GARBAGE -> {
                setDebugState("Picking up garbage");
                pickAim(mod);
                mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
            }
            case THROW_CURSOR -> {
                setDebugState("Throwing the held stack");
                // a 1x1 pocket has no run to throw along, so the stack still comes back there. the bucket task has a
                // backstop for that
                pickAim(mod);
                // what the server last heard from us, not the camera: the click goes out ahead of this tick's rotation packet,
                // and baritone's free look can send one angle and put another on the camera
                var player = mod.getPlayer();
                var sent = mod.getClientBaritone().getLookBehavior().serverRotation();
                float sentYaw = sent.map(r -> r.getYaw()).orElse(player.getYRot());
                float sentPitch = sent.map(r -> r.getPitch()).orElse(player.getXRot());
                boolean aligned = ThrowAim.isAligned(sentYaw, sentPitch, _aimYaw, ThrowAim.PITCH);
                if (ThrowAim.shouldThrow(aligned, WorldHelper.getTicks() - _aimSince)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                }
            }
            case PUT_BACK -> {
                setDebugState("Putting the held stack back");
                mod.getSlotHandler().clickSlot(containerSlot.get(), 0, ContainerInput.PICKUP);
            }
            default -> setDebugState("All items are protected.");
        }
        // held every tick, something else (baritone's look) may have turned us since
        if (_hasAim) {
            mod.getInputControls().forceLook(_aimYaw, ThrowAim.PITCH);
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
