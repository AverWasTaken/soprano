package adris.altoclef.control;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controls and applies killaura
 */
public class KillAura {
    // Smart aura data
    private final List<Entity> _targets = new ArrayList<>();
    private final TimerGame _hitDelay = new TimerGame(0.2);
    boolean _shielding = false;
    private double _forceFieldRange = Double.POSITIVE_INFINITY;
    private Entity _forceHit = null;
    // the combat policy's say, set every tick before tickEnd. defaults are the old behavior: shield whenever it fits
    private boolean _kiting = false;
    private boolean _shieldAllowed = true;

    public static void equipWeapon(AltoClef mod) {
        // same pick as the kill tasks use. this loop used to equip a sword per inventory sword, every call
        AbstractKillEntityTask.equipWeapon(mod);
    }

    public void tickStart() {
        _targets.clear();
        _forceHit = null;
    }

    public void applyAura(Entity entity) {
        _targets.add(entity);
        // Always hit ghast balls.
        if (entity instanceof LargeFireball) _forceHit = entity;
    }

    public void setRange(double range) {
        _forceFieldRange = range;
    }

    // kiting is all feet and no hands: a swing turns our head and the path goes where the head points. the shield is
    // for standing our ground, which is the only time it earns its sneak speed and its paused pathing
    public void setPolicy(boolean kiting, boolean shieldAllowed) {
        _kiting = kiting;
        _shieldAllowed = shieldAllowed;
    }

    public void tickEnd(AltoClef mod) {
        if (_kiting) {
            stopShielding(mod);
            // ghast balls are still worth the turn
            if (_forceHit != null) {
                attack(mod, _forceHit, true);
            }
            return;
        }
        Optional<Entity> entities = _targets.stream().min(StlHelper.compareValues(entity -> entity.distanceToSqr(mod.getPlayer())));
        if (entities.isPresent() && CombatRules.aboveFleeLine(mod.getPlayer().getHealth()) &&
                !mod.getEntityTracker().entityFound(ThrownPotion.class) && !mod.getFoodChain().needsToEat() &&
                (Double.isInfinite(_forceFieldRange) || entities.get().distanceToSqr(mod.getPlayer()) < _forceFieldRange * _forceFieldRange ||
                        entities.get().distanceToSqr(mod.getPlayer()) < 40) &&
                !mod.getMLGBucketChain().isFallingOhNo(mod) && mod.getMLGBucketChain().doneMLG() &&
                !mod.getMLGBucketChain().isChorusFruiting()) {
            PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
            Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
            // (one zombie does not need the shield, and the shield stops us walking)
            if (!_shieldAllowed) {
                stopShielding(mod);
            } else if (entities.get().getClass() != Creeper.class && entities.get().getClass() != Hoglin.class &&
                    entities.get().getClass() != Zoglin.class && entities.get().getClass() != Warden.class &&
                    entities.get().getClass() != WitherBoss.class
                    && (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD))
                    && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                LookHelper.lookAt(mod, entities.get().getEyePosition());
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            }
            performDelayedAttack(mod);
        } else {
            stopShielding(mod);
        }
        // Run force field on map
        switch (Baritone.settings().altoForceFieldStrategy.value) {
            case FASTEST:
                performFastestAttack(mod);
                break;
            case SMART:
                if (_targets.size() <= 2 || _targets.stream().allMatch(entity -> entity instanceof Skeleton) ||
                        _targets.stream().allMatch(entity -> entity instanceof Witch) ||
                        _targets.stream().allMatch(entity -> entity instanceof Pillager) ||
                        _targets.stream().allMatch(entity -> entity instanceof Piglin) ||
                        _targets.stream().allMatch(entity -> entity instanceof Stray) ||
                        _targets.stream().allMatch(entity -> entity instanceof Blaze)) {
                    performDelayedAttack(mod);
                } else {
                    if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                            mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
                        // Attack force mobs ALWAYS.
                        if (_forceHit != null) {
                            attack(mod, _forceHit, true);
                        }
                        if (_hitDelay.elapsed()) {
                            _hitDelay.reset();

                            Optional<Entity> toHit = _targets.stream().min(StlHelper.compareValues(entity -> entity.distanceToSqr(mod.getPlayer())));

                            toHit.ifPresent(entity -> attack(mod, entity, true));
                        }
                    }
                }
                break;
            case DELAY:
                performDelayedAttack(mod);
                break;
            case OFF:
                break;
        }
    }

    private void performDelayedAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            if (_forceHit != null) {
                attack(mod, _forceHit, true);
            }
            // wait for the attack delay
            if (_targets.isEmpty()) {
                return;
            }

            Optional<Entity> toHit = _targets.stream().min(StlHelper.compareValues(entity -> entity.distanceToSqr(mod.getPlayer())));

            if (mod.getPlayer() == null) {
                return;
            }
            // a not yet full swing is a quiet tick, unless a crit hop is about to start or is in the air (never while
            // baritone is walking, so travelling past a mob still doesn't turn our head every tick)
            if (!mod.getControllerExtras().attackReady() && !mod.getControllerExtras().wantsCritTick()) {
                return;
            }

            toHit.ifPresent(entity -> attack(mod, entity, true, true));
        }
    }

    private void performFastestAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            // Just attack whenever you can
            for (Entity entity : _targets) {
                attack(mod, entity);
            }
        }
    }

    private void attack(AltoClef mod, Entity entity) {
        attack(mod, entity, false);
    }

    private void attack(AltoClef mod, Entity entity, boolean equipSword) {
        attack(mod, entity, equipSword, false);
    }

    // crit is only for the paced swings of the delayed aura. the crowd mode swings every 0.2 s whether the cooldown is back
    // or not, and a machine that gets asked every fourth tick can't steer a jump
    private void attack(AltoClef mod, Entity entity, boolean equipSword, boolean crit) {
        if (entity == null) return;
        // second guard: a wolf nobody poked is not our enemy, whatever put it in the target list
        if (entity instanceof Mob mob && EntityHelper.isCalmNeutral(mod, mob)) return;
        if (!(entity instanceof LargeFireball)) {
            LookHelper.lookAt(mod, entity.getEyePosition());
        }
        if (Double.isInfinite(_forceFieldRange) || entity.distanceToSqr(mod.getPlayer()) < _forceFieldRange * _forceFieldRange ||
                entity.distanceToSqr(mod.getPlayer()) < 40) {
            if (entity instanceof LargeFireball) {
                mod.getControllerExtras().attack(entity);
            }
            boolean canAttack;
            if (equipSword) {
                equipWeapon(mod);
                canAttack = true;
            } else {
                // Equip non-tool
                canAttack = mod.getSlotHandler().forceDeequipHitTool();
            }
            if (canAttack) {
                if (crit && !(entity instanceof LargeFireball)) {
                    mod.getControllerExtras().melee(entity);
                } else if (mod.getPlayer().onGround() || mod.getPlayer().getDeltaMovement().y() < 0 || mod.getPlayer().isInWater()) {
                    mod.getControllerExtras().attack(entity);
                }
            }
        }
    }

    public void startShielding(AltoClef mod) {
        _shielding = true;
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        if (!mod.getPlayer().isBlocking()) {
            ItemStack handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
            if (handItem.getItem().components().has(DataComponents.FOOD)) {
                List<ItemStack> spaceSlots = mod.getItemStorage().getItemStacksPlayerInventory(false);
                if (!spaceSlots.isEmpty()) {
                    for (ItemStack spaceSlot : spaceSlots) {
                        if (spaceSlot.isEmpty()) {
                            mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, ClickType.QUICK_MOVE);
                            return;
                        }
                    }
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                garbage.ifPresent(slot -> mod.getSlotHandler().forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
            }
        }
    }

    public void stopShielding(AltoClef mod) {
        if (_shielding) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (cursor.getItem().components().has(DataComponents.FOOD)) {
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                if (toMoveTo.isPresent()) {
                    Slot garbageSlot = toMoveTo.get();
                    mod.getSlotHandler().clickSlot(garbageSlot, 0, ClickType.PICKUP);
                }
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getInputControls().release(Input.JUMP);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _shielding = false;
        }
    }

    public enum Strategy {
        OFF,
        FASTEST,
        DELAY,
        SMART
    }
}
