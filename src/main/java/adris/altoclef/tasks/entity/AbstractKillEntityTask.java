package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WeaponPick;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.behavior.CombatAim;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
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
        return WeaponPick.best(candidates(mod));
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

    public static boolean equipWeapon(AltoClef mod) {
        return equipWeapon(mod, false);
    }

    // the last pick we said out loud, so the log gets a line per change and not one per swing
    private static Item _loggedPick;
    // swings that had gone out the last time the hand changed (see WeaponPick.hold)
    private static long _swingsAtSwap;

    // toolFallback: no sword or axe, so the hardest hitting tool still beats a bare hand. only a fight the bot picked for
    // itself asks for it (see useToolsWhenUnarmed), hunting a cow or a pearl keeps its pickaxe's durability
    public static boolean equipWeapon(AltoClef mod, boolean toolFallback) {
        return equipWeapon(mod, toolFallback, null);
    }

    // target: who we are about to hit. a calm neutral mob near it means the axe, which never sweeps (the sword swing would)
    public static boolean equipWeapon(AltoClef mod, boolean toolFallback, Entity target) {
        // an odd item is in hand to throw the charge away, the sword comes back once it has been there long enough
        if (mod.getControllerExtras().chargeResetActive()) {
            return true;
        }
        Item axe = target != null && mod.getControllerExtras().calmNeutralNear(target) ? WeaponPick.bestAxe(candidates(mod)) : null;
        Item bestWeapon = axe != null ? axe : bestWeapon(mod);
        if (bestWeapon == null && toolFallback) bestWeapon = WeaponPick.bestTool(candidates(mod));
        Item equipedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        logPick(mod, bestWeapon, equipedWeapon);
        // (the hand only moves once a swing landed or the charge is nearly empty: see WeaponPick.hold)
        long swings = mod.getControllerExtras().swings();
        // the swap to the axe is the point: it throws the charge away, so the cooldown is no reason to hold the sword
        Item hold = axe != null ? axe : WeaponPick.hold(equipedWeapon, bestWeapon, mod.getControllerExtras().chargedTicks(), swings != _swingsAtSwap);
        if (hold != equipedWeapon) {
            _swingsAtSwap = swings;
            // the offhand counts as a candidate but the equip can't fetch from it. a failed axe falls back to the plain pick
            // instead of asking for it every tick and never swinging
            // (and a swap that failed both ways is no reason to skip the swing: melee goes on with what is in hand)
            return WeaponPick.swapHand(item -> mod.getSlotHandler().forceEquipItem(item), hold, equipedWeapon, axe != null,
                    () -> WeaponPick.hold(equipedWeapon, bestWeapon(mod), mod.getControllerExtras().chargedTicks(), true));
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

    // the eye to box gap inside the swing's own reach (ReachRules.killReach), the same number the swing is gated on
    @Override
    protected boolean inReach(AltoClef mod, Entity entity) {
        return mod.getControllerExtras().inKillReach(entity);
    }

    @Override
    protected boolean isKillTask() {
        return true;
    }

    // the plan gave up waiting for a swing that the gates never let out: weapon in hand, face it, hit it on a full cooldown
    @Override
    protected Task onStalledSwing(AltoClef mod, Entity entity) {
        // the path was told to go but may be mid step, the head is ours anyway (see CombatAim.wantOverPath)
        mod.getControllerExtras().aimAtOverPath(entity);
        if (equipWeapon(mod, _toolFallback, entity)) {
            noteBlazeSwap(mod, entity);
        } else {
            noteBlazeMiss(mod, entity, mod.getControllerExtras().swingNow(entity));
        }
        return null;
    }

    // a blaze in reach, the charge full, and still no swing for this many ticks in a row is worth a line. one tick is normal
    // (the sprint stop packet goes first), three is something holding it
    private static final int BLAZE_MISS_TICKS = 3;
    private static final long BLAZE_NOTE_GAP = 20;
    private static int _blazeMissStreak;
    private static long _blazeMissAt = Long.MIN_VALUE / 2;
    private static long _blazeNotedAt = Long.MIN_VALUE / 2;

    // the swap tick has no swing in it on purpose: vanilla resets the cooldown on any change of item, a swing now is a weak
    // one. so it is not lost, but a fight that swaps a lot is a fight that hits a lot less, and that is what the line is for
    private static void noteBlazeSwap(AltoClef mod, Entity entity) {
        if (!(entity instanceof Blaze)) return;
        _blazeMissStreak = 0;
        long now = mod.getWorld().getGameTime();
        if (now - _blazeNotedAt < BLAZE_NOTE_GAP) return;
        _blazeNotedAt = now;
        Debug.logInternal("swing: blaze #" + entity.getId() + " in reach, skipped for a weapon swap (hand "
                + itemName(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem()) + ")");
    }

    private static void noteBlazeMiss(AltoClef mod, Entity entity, boolean swung) {
        if (!(entity instanceof Blaze)) return;
        if (swung || !mod.getControllerExtras().attackReady()) {
            _blazeMissStreak = 0;
            return;
        }
        long now = mod.getWorld().getGameTime();
        // only back to back ticks make a streak, a leftover from the last time we stood here is not one
        _blazeMissStreak = now - _blazeMissAt == 1 ? _blazeMissStreak + 1 : 1;
        _blazeMissAt = now;
        if (_blazeMissStreak < BLAZE_MISS_TICKS || now - _blazeNotedAt < BLAZE_NOTE_GAP) return;
        _blazeNotedAt = now;
        String why = mod.getControllerExtras().chargeResetActive() ? "charge reset swap in hand"
                : !mod.getControllerExtras().onTarget(entity) ? "aim not on it" : "a swing gate";
        Debug.logInternal("swing: blaze #" + entity.getId() + " in reach at full charge, no swing for " + _blazeMissStreak
                + " ticks: " + why);
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        // every tick, swap ticks and charging ticks too: the turn takes a few ticks and should be done by the time the
        // swing is due, not start then
        mod.getControllerExtras().aimAt(entity, CombatAim.Priority.KILL_TASK);
        // Equip weapon
        if (equipWeapon(mod, _toolFallback, entity)) {
            noteBlazeSwap(mod, entity);
        } else {
            boolean swung = false;
            // a crit hop starts a few ticks before the cooldown is full, so those ticks count while jumping is on
            if (mod.getControllerExtras().attackReady() || mod.getControllerExtras().wantsCritTick()) {
                swung = mod.getControllerExtras().melee(entity);
            }
            noteBlazeMiss(mod, entity, swung);
        }
        return null;
    }
}