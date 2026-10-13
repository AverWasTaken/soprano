package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class EquipArmorTask extends Task {

    private final ItemTarget[] _toEquip;

    public EquipArmorTask(ItemTarget... toEquip) {
        _toEquip = toEquip;
    }

    public EquipArmorTask(Item... toEquip) {
        this(Arrays.stream(toEquip).map(ItemTarget::new).toArray(ItemTarget[]::new));
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        // a piece riding the cursor (craft output with no room in the bag) is held too: the inventory count does not see it, and
        // asking the catalogue for "another" diamond piece is a trip to mine the diamonds for it
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        ItemTarget[] armorsNotEquipped = Arrays.stream(_toEquip).filter(target -> !StorageHelper.itemTargetsMetInventory(mod, target)
                && !cursorHolds(cursor, target) && !StorageHelper.isArmorEquipped(mod, target.getMatches())).toArray(ItemTarget[]::new);
        boolean armorMet = armorsNotEquipped.length == 0;
        if (!armorMet) {
            setDebugState("Obtaining armor");
            return new CataloguedResourceTask(armorsNotEquipped);
        }

        setDebugState("Equipping armor");

        // Now equip
        for (ItemTarget targetArmor : _toEquip) {
            Item[] targetArmorMatches = targetArmor.getMatches();
            if (Arrays.stream(targetArmorMatches).toList().contains(Items.SHIELD)) {
                ShieldItem shield = (ShieldItem) Objects.requireNonNull(targetArmor.getMatches())[0];
                if (shield == null) {
                    Debug.logWarning("Item " + targetArmor + " is not armor! Will not equip.");
                } else {
                    if (!StorageHelper.isArmorEquipped(mod, shield)) {
                        if (!(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
                            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
                            if (!cursorStack.isEmpty()) {
                                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                                if (moveTo.isPresent()) {
                                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                if (ItemHelper.canDropStack(mod, cursorStack)) {
                                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                                // Try throwing away cursor slot if it's garbage
                                if (garbage.isPresent()) {
                                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                            } else {
                                StorageHelper.closeScreen();
                            }
                        }
                        Slot toMove = PlayerSlot.getEquipSlot(EquipmentSlot.OFFHAND);
                        if (toMove == null) {
                            Debug.logWarning("Invalid armor equip slot for item " + shield.getDescriptionId());
                        }
                        return new MoveItemToSlotFromInventoryTask(targetArmor, toMove);
                    }
                }
            } else {
                ArmorItem item = (ArmorItem) Objects.requireNonNull(targetArmor.getMatches())[0];
                if (item == null) {
                    Debug.logWarning("Item " + targetArmor + " is not armor! Will not equip.");
                } else {
                    if (!StorageHelper.isArmorEquipped(mod, item)) {
                        if (!(mod.getPlayer().containerMenu instanceof InventoryMenu)) {
                            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
                            if (!cursorStack.isEmpty()) {
                                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                                if (moveTo.isPresent()) {
                                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                if (ItemHelper.canDropStack(mod, cursorStack)) {
                                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                                // Try throwing away cursor slot if it's garbage
                                if (garbage.isPresent()) {
                                    mod.getSlotHandler().clickSlot(garbage.get(), 0, ContainerInput.PICKUP);
                                    return null;
                                }
                                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                            } else {
                                StorageHelper.closeScreen();
                            }
                        }
                        Slot toMove = PlayerSlot.getEquipSlot(ItemHelper.getEquipSlot(item));
                        if (toMove == null) {
                            Debug.logWarning("Invalid armor equip slot for item " + item.getDescriptionId() + ": " + ItemHelper.getEquipSlot(item));
                        }
                        return new MoveItemToSlotFromInventoryTask(targetArmor, toMove);
                    }
                }
            }
        }

        // putting a better piece over one we already wear swaps them, and the old one comes up on the cursor. the slot click is
        // done the moment the new piece sits there, so park the old one in the bag before calling it a day
        Optional<Slot> park = cursorArmorSpot(mod);
        if (park.isPresent()) {
            setDebugState("Parking the swapped piece");
            mod.getSlotHandler().clickSlot(park.get(), 0, ContainerInput.PICKUP);
        }
        return null;
    }

    static boolean cursorHolds(ItemStack cursor, ItemTarget target) {
        return !cursor.isEmpty() && target.matches(cursor.getItem()) && cursor.getCount() >= target.getTargetCount();
    }

    // where the armor piece riding the cursor can go, empty when the cursor holds nothing of the sort (or there is no room, in
    // which case it stays put and we do not wait on it for ever)
    private static Optional<Slot> cursorArmorSpot(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty() || !(cursor.getItem() instanceof ArmorItem)) {
            return Optional.empty();
        }
        return mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return armorEquipped(mod) && cursorArmorSpot(mod).isEmpty();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EquipArmorTask task) {
            return Arrays.equals(task._toEquip, _toEquip);
        }
        return false;
    }

    @Override
    protected String toHudString() {
        Item[] items = Arrays.stream(_toEquip).flatMap(target -> Arrays.stream(target.getMatches())).distinct().toArray(Item[]::new);
        return "Putting on " + (items.length == 1 ? HudText.item(items[0]) : HudText.some(items));
    }

    @Override
    protected String toDebugString() {
        return "Equipping armor " + ArrayUtils.toString(_toEquip);
    }

    private boolean armorTestAll(Predicate<Item> armorSatisfies) {
        // If ALL item target has any match that is equipped...
        return Arrays.stream(_toEquip).allMatch(
                target -> Arrays.stream(target.getMatches()).anyMatch(armorSatisfies)
        );
    }

    public boolean armorEquipped(AltoClef mod) {
        return armorTestAll(item -> StorageHelper.isArmorEquipped(mod, item));
    }

}
