package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.chains.WorldSurvivalChain;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.helpers.CombatCommit;
import baritone.Baritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.InventoryMenu;

// the 2x2 crafts click the inventory menu with no screen on top, which works fine and looks like nothing at all. this
// puts the real inventory screen up while the grid is being clicked so you can watch (altoShowInventoryWhenCrafting).
// it only ever opens on a grid click that went out, and only ever closes the screen it opened itself
public final class CraftScreenShow {

    // the ones InputControls maps to a key, anything else there throws
    private static final Input[] KEYS = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.CLICK_LEFT,
            Input.CLICK_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT};

    private InventoryScreen _ours;
    private boolean _clicked;
    private int _idleTicks;

    // every slot click the bot makes comes through SlotHandler.sendClick. the 2x2 grid is window slots 0 (the result)
    // to 4 of the inventory menu, and only while nothing else is the menu (a crafting table has its own id)
    public void noteClick(LocalPlayer player, int syncId, int windowSlot) {
        if (syncId == player.inventoryMenu.containerId && player.containerMenu == player.inventoryMenu
                && windowSlot >= InventoryMenu.RESULT_SLOT && windowSlot < InventoryMenu.CRAFT_SLOT_END) {
            _clicked = true;
        }
    }

    public boolean isOurs(Screen screen) {
        return screen != null && screen == _ours;
    }

    // once a tick from AltoClef.onClientTick, after the chains ran, so this tick's clicks are in
    public void tick(AltoClef mod) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean clicked = _clicked;
        _clicked = false;
        if (player == null || !AltoClef.inGame()) {
            _ours = null;
            return;
        }
        boolean showing = _ours != null;
        _idleTicks = clicked || !showing ? 0 : _idleTicks + 1;
        Screen screen = mc.gui.screen();
        // the runner going off (a #stop, the idle gate) counts as the setting going off: nobody is crafting anymore
        boolean on = Baritone.settings().altoShowInventoryWhenCrafting.value && mod.getTaskRunner().isActive();
        CraftScreenRules.Facts f = new CraftScreenRules.Facts(on, showing, showing && screen == _ours, screen != null, clicked,
                player.hasInfiniteMaterials() || player.isSpectator(), player.containerMenu == player.inventoryMenu,
                trouble(mod, player), keysHeld(mod), _idleTicks, gridEmpty(player));
        switch (CraftScreenRules.decide(f)) {
            case OPEN -> {
                _ours = new InventoryScreen(player);
                mc.gui.setScreen(_ours);
            }
            case CLOSE -> {
                _ours = null;
                // closeContainer and never setScreen(null): the screen going away runs InventoryMenu.removed on our side
                // only, which wipes the result slot here while the server keeps it, and the craft reads it from here.
                // the close packet has the server hand the grid back to the bag, same as a player pressing E
                player.closeContainer();
            }
            case FORGET -> {
                _ours = null;
                // somebody else's screen replacing ours runs InventoryMenu.removed on our side only, which wipes the result
                // slot here and not on the server. harmless with an empty grid, worth a line when there was a craft in it
                if (!gridEmpty(player)) {
                    Debug.logInternal("craft screen: replaced by " + (screen == null ? "nothing" : screen.getClass().getSimpleName())
                            + " with stuff still in the 2x2 grid, the next grid click resyncs it");
                }
            }
            case NOTHING -> {
            }
        }
    }

    // every key InputControls can hold, real hands on the keyboard included (a key mapping does not know who pressed it)
    private static boolean keysHeld(AltoClef mod) {
        for (Input input : KEYS) {
            if (mod.getInputControls().isHeldDown(input)) return true;
        }
        return false;
    }

    // the grid and the cursor, so the linger close never throws a stack around
    private static boolean gridEmpty(LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (!menu.getCarried().isEmpty()) return false;
        for (int i = InventoryMenu.RESULT_SLOT; i < InventoryMenu.CRAFT_SLOT_END; ++i) {
            if (!menu.getSlot(i).getItem().isEmpty()) return false;
        }
        return true;
    }

    // not "any chain but the user's": the food chain's own food run crafts in the 2x2 too, and the meal, the arrow shield
    // and the force field all happen while the user chain still holds the wheel. so it goes by what needs keys instead
    private static boolean trouble(AltoClef mod, LocalPlayer player) {
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        if (chain instanceof MobDefenseChain || chain instanceof MLGBucketFallChain || chain instanceof WorldSurvivalChain) {
            return true;
        }
        if (mod.getMobDefenseChain().combatMode() != CombatCommit.Mode.NONE) return true;
        if (mod.getFoodChain().isTryingToEat() || (mod.getFoodChain().needsToEat() && mod.getFoodChain().hasFood())) {
            return true;
        }
        if (mod.getInputControls().isHeldDown(Input.CLICK_RIGHT) || mod.getInputControls().isHeldDown(Input.CLICK_LEFT)) {
            return true;
        }
        return hurtOrFalling(player);
    }

    // the stuff that becomes a chain a tick later anyway, caught a tick early
    private static boolean hurtOrFalling(LocalPlayer player) {
        return player.isUsingItem() || player.hurtTime > 0 || player.isInLava() || player.isOnFire()
                || player.getAirSupply() < player.getMaxAirSupply() || (!player.onGround() && player.fallDistance > 3);
    }
}
