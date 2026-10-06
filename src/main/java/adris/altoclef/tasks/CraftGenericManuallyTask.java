package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.CraftMath;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import net.minecraft.world.item.Item;
import java.util.Optional;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Assuming a crafting screen is open, crafts a recipe.
 * <p>
 * Not useful for custom tasks.
 */
// the grid is full of our stuff for as long as this runs, so ResourceTask must not sweep it. the recipe book
// version of this used to carry the marker, and then the recipe book left in 1.21.2 and took it with it
public class CraftGenericManuallyTask extends Task implements ITaskUsesCraftingGrid {

    private final RecipeTarget _target;

    public CraftGenericManuallyTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {

        boolean bigCrafting = StorageHelper.isBigCraftingOpen();

        if (!bigCrafting && !StorageHelper.isPlayerInventoryOpen()) {
            // Make sure we're not in another screen before we craft,
            // otherwise crafting won't work
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ClickType.PICKUP);
                    return null;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
                    return null;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ClickType.PICKUP);
                    return null;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            // Just to be safe
        }

        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;

        // Example:
        // We need 9 sticks
        // plank recipe results in 4 sticks
        // this means 3 planks per slot
        int wantedCrafts = CraftMath.craftsFor(_target.getTargetCount(), _target.getRecipe().outputCount());
        // "craft as many as possible" is a target of 99999999, so the per slot number has to be clamped to what we
        // really own or the slot is never satisfied and we keep reaching for items that are gone (that was a
        // MoveItemToSlotTask error in chat every single tick). grid contents count so this doesn't shrink as we fill it
        int craftsPossible = StorageHelper.craftsPossible(mod, _target.getRecipe(), wantedCrafts);
        // and a grid slot holds one stack, 100 hay blocks are two rounds
        int requiredPerSlot = Math.min(craftsPossible, stackLimit(_target));
        if (craftsPossible <= 0) {
            // nothing left to put in. whatever is still sitting in the output is ours, then we are done
            if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
                setDebugState("Out of materials: grabbing from output.");
                return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
            }
            setDebugState("Out of materials.");
            return null;
        }

        // For each slot in table
        for (int craftSlot = 0; craftSlot < _target.getRecipe().getSlotCount(); ++craftSlot) {
            ItemTarget toFill = _target.getRecipe().getSlot(craftSlot);
            Slot currentCraftSlot;
            if (bigCrafting) {
                // Craft in table
                currentCraftSlot = CraftingTableSlot.getInputSlot(craftSlot, _target.getRecipe().isBig());
            } else {
                // Craft in window
                currentCraftSlot = PlayerSlot.getCraftInputSlot(craftSlot);
            }
            ItemStack present = StorageHelper.getItemStackInSlot(currentCraftSlot);
            if (toFill == null || toFill.isEmpty()) {
                if (present.getItem() != Items.AIR) {
                    // Move this item OUT if it should be empty
                    setDebugState("Found INVALID slot");
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 0, ClickType.PICKUP);
                }
            } else {
                boolean correctItem = toFill.matches(present.getItem());
                boolean isSatisfied = correctItem && present.getCount() >= requiredPerSlot;
                if (!isSatisfied) {
                    // We have items that satisfy, but we CAN NOT fill in the current slot!
                    // In that case, just grab from the output.
                    // this used to ask about present.getItem(). an empty slot is AIR, and an inventory with any free
                    // slot "has" AIR, so every empty slot sent us off to move an item we did not own
                    if (!mod.getItemStorage().hasItemInventoryOnly(toFill.getMatches())) {
                        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
                            setDebugState("NO MORE to fit: grabbing from output.");
                            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
                        } else {
                            // Move on to the NEXT slot, we can't fill this one anymore.
                            continue;
                        }
                    }

                    setDebugState("Moving item to slot...");
                    return new MoveItemToSlotFromInventoryTask(new ItemTarget(toFill, requiredPerSlot), currentCraftSlot);
                }
                // We could be OVER satisfied
                boolean oversatisfies = present.getCount() > requiredPerSlot;
                if (oversatisfies) {
                    setDebugState("OVER SATISFIED slot! Right clicking slot to extract half and spread it out more.");
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 0, ClickType.PICKUP);
                }
            }
        }

        // Ensure our cursor is empty/can receive our item
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!ItemHelper.canStackTogether(StorageHelper.getItemStackInSlot(outputSlot), cursor)) {
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
            if (toFit.isPresent()) {
                mod.getSlotHandler().clickSlot(toFit.get(), 0, ClickType.PICKUP);
            } else {
                // Eh screw it
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            }
        }

        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
        } else {
            // Wait
            return null;
        }
    }

    // the smallest stack any ingredient of this recipe can form, one grid slot never holds more than that
    private static int stackLimit(RecipeTarget target) {
        int limit = 64;
        for (int i = 0; i < target.getRecipe().getSlotCount(); ++i) {
            ItemTarget slot = target.getRecipe().getSlot(i);
            if (slot == null || slot.isEmpty()) continue;
            for (Item item : slot.getMatches()) {
                limit = Math.min(limit, Math.max(1, new ItemStack(item).getMaxStackSize()));
            }
        }
        return limit;
    }

    /**
     * True when the recipe can not run again: nothing left to feed it and nothing waiting in the output. Crafting
     * "as many as possible" (a target of 99999999) is only ever done through this, the parent drops us when it flips.
     */
    public static boolean isOutOfMaterials(AltoClef mod, RecipeTarget target) {
        return StorageHelper.craftsPossible(mod, target.getRecipe(), 1) <= 0 && !StorageHelper.craftOutputWaiting();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return isOutOfMaterials(mod, _target);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + _target;
    }

    @Override
    protected String toHudString() {
        // same words as the craft task above it on purpose, the hud folds the two into one line
        return "Crafting " + HudText.items(new Item[]{_target.getOutputItem()}, _target.getTargetCount());
    }
}
