package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WeaponPick;
import adris.altoclef.util.slots.PlayerSlot;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Attacks an entity, but the target entity must be specified.
 */
public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {
    private static final double OTHER_FORCE_FIELD_RANGE = 2;
    private boolean _toolFallback;

    // Not the "striking" distance, but the "ok we're close enough, lower our guard for other mobs and focus on this one" range.
    private static final double CONSIDER_COMBAT_RANGE = 10;

    public AbstractKillEntityTask() {
        this(CONSIDER_COMBAT_RANGE, OTHER_FORCE_FIELD_RANGE);
    }

    public AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    // per swing, the pick everything that sizes up a fight with one target uses (the chain's stand capacity, the golem)
    public static Item bestWeapon(AltoClef mod) {
        return bestWeapon(mod, false, false);
    }

    // the one we swing: damage per second once two or more melee mobs are around us, the axe against a raised shield
    private static Item weaponForNow(AltoClef mod) {
        boolean crowd = mod.getMobDefenseChain().meleeAround(mod) >= 2;
        return bestWeapon(mod, crowd, crowd && shieldUp(mod));
    }

    // a hostile close by that is blocking. only worth asking in a crowd, it walks the hostile list
    private static boolean shieldUp(AltoClef mod) {
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (entity instanceof LivingEntity living && living.isBlocking() && living.distanceTo(mod.getPlayer()) <= CombatRules.SWARM_RANGE) {
                    return true;
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread, no shield seen this tick is fine
        }
        return false;
    }

    // the hand first, so it wins ties and we don't swap between two equal weapons
    private static List<WeaponPick.Candidate> candidates(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
        List<WeaponPick.Candidate> candidates = new ArrayList<>();
        candidates.add(WeaponPick.Candidate.of(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot())));
        for (ItemStack invStack : invStacks) {
            candidates.add(WeaponPick.Candidate.of(invStack));
        }
        return candidates;
    }

    private static Item bestWeapon(AltoClef mod, boolean crowd, boolean shieldedTarget) {
        List<WeaponPick.Candidate> candidates = candidates(mod);
        // swords and axes by damage per swing, worn ones last (see WeaponPick). equipping goes by item, so a fresh and a
        // worn copy of the same axe are the slot handler's coin flip. the mining tool picker never sees any of this, it
        // only runs while we mine and an axe in hand is the right tool for a log anyway
        return WeaponPick.best(candidates, crowd, shieldedTarget);
    }

    public static boolean equipWeapon(AltoClef mod) {
        return equipWeapon(mod, false);
    }

    // toolFallback: no sword or axe, so the hardest hitting tool still beats a bare hand. only a fight the bot picked for
    // itself asks for it (see useToolsWhenUnarmed), hunting a cow or a pearl keeps its pickaxe's durability
    // the last pick we said out loud, so the log gets a line per change and not one per swing
    private static Item _loggedPick;

    public static boolean equipWeapon(AltoClef mod, boolean toolFallback) {
        Item bestWeapon = weaponForNow(mod);
        if (bestWeapon == null && toolFallback) bestWeapon = WeaponPick.bestTool(candidates(mod));
        Item equipedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        logPick(mod, bestWeapon, equipedWeapon);
        // (the pick can flip with the crowd, the hand does not: see WeaponPick.hold)
        Item hold = WeaponPick.hold(equipedWeapon, bestWeapon, mod.getControllerExtras().chargedTicks());
        if (hold != equipedWeapon) {
            mod.getSlotHandler().forceEquipItem(hold);
            return true;
        }
        return false;
    }

    // a swap resets the cooldown (vanilla does it for any change of item type), so every change of mind is worth a line
    private static void logPick(AltoClef mod, Item pick, Item inHand) {
        if (pick == null || pick == _loggedPick) return;
        Item from = _loggedPick == null ? inHand : _loggedPick;
        _loggedPick = pick;
        Debug.logInternal("weapon: " + itemName(from) + " -> " + itemName(pick) + " (crowd " + mod.getMobDefenseChain().meleeAround(mod)
                + ", hand " + itemName(inHand) + ")");
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    // a defensive fight swings a pickaxe rather than punch when there is no sword or axe
    public void useToolsWhenUnarmed() {
        _toolFallback = true;
    }

    @Override
    protected boolean wantsCritHops() {
        return true;
    }

    @Override
    protected boolean isKillTask() {
        return true;
    }

    // the plan gave up waiting for a swing that the gates never let out: weapon in hand, face it, hit it on a full cooldown
    @Override
    protected Task onStalledSwing(AltoClef mod, Entity entity) {
        if (!equipWeapon(mod, _toolFallback)) {
            LookHelper.lookAt(mod, entity.getEyePosition());
            mod.getControllerExtras().swingNow(entity);
        }
        return null;
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        // Equip weapon
        if (!equipWeapon(mod, _toolFallback)) {
            // a crit hop starts a few ticks before the cooldown is full, so those ticks count while jumping is on
            if (mod.getControllerExtras().attackReady() || mod.getControllerExtras().wantsCritTick()) {
                LookHelper.lookAt(mod, entity.getEyePosition());
                mod.getControllerExtras().melee(entity);
            }
        }
        return null;
    }
}