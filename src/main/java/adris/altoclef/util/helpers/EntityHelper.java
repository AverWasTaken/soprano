package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.util.Objects;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {
    public static final double ENTITY_GRAVITY = 0.08; // per second
    // the spider target goal gives up at this light level or brighter (Spider.SpiderTargetGoal.canUse)
    private static final float SPIDER_BRIGHT = 0.5f;

    public static boolean isAngryAtPlayer(AltoClef mod, Entity entity) {
        return isGenerallyHostileToPlayer(mod, entity);
    }

    public static boolean isGenerallyHostileToPlayer(AltoClef mod, Entity entity) {
        if (entity instanceof OwnableEntity tameable && tameable.getOwnerUUID() != null
                && tameable.getOwnerUUID().equals(mod.getPlayer().getUUID())) return false;
        if (entity instanceof Slime slime && mod.getPlayer().closerThan(slime, 3)) return true;
        if (entity instanceof Raider raider && mod.getPlayer().closerThan(raider, 16)) return true;
        if (entity instanceof Warden warden && mod.getPlayer().closerThan(warden, 16)) return true;
        if (entity instanceof EnderMan enderman && enderman.hasLineOfSight(mod.getPlayer()) && enderman.isAggressive())
            return true;
        if (entity instanceof Blaze blaze && isBlazeThreat(mod, blaze)) return true;
        if (entity instanceof Slime || entity instanceof Raider || entity instanceof Warden
                || entity instanceof EnderMan || entity instanceof Blaze) return false;
        if (entity instanceof Mob mob && !isAfterUs(mod, mob)) return false;
        return !isTradingPiglin(entity);
    }

    // the aggressive flag alone made every poked zombified piglin in the nether a fight, and a group of them killed us.
    // a neutral mob has to have a reason (see NeutralMobs), everything else is still just "is it aggressive"
    private static boolean isAfterUs(AltoClef mod, Mob mob) {
        LocalPlayer player = mod.getPlayer();
        boolean neutral = isNeutralNow(mod, mob);
        // the grudge book is only worth opening for the ones that need a reason
        boolean provoked = neutral && mod.getEntityTracker().getProvocations().recent(mob.getId(), mod.getWorld().getGameTime());
        // Mob.target is server side, so this is usually null for a remote mob. free to ask though
        return NeutralMobs.hostile(neutral, mob.isAggressive(), provoked, mob.getTarget() == player, mob.distanceTo(player));
    }

    // a neutral kind that has not been given a reason to come for us. what the force field and the kill aura check
    // as their own second guard, so a bad call upstream still does not end in us hitting a wolf
    public static boolean isCalmNeutral(AltoClef mod, Mob mob) {
        return isNeutralNow(mod, mob) && !isAfterUs(mod, mob);
    }

    private static boolean isNeutralNow(AltoClef mod, Mob mob) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
        boolean bright = mob instanceof Spider spider && spider.getLightLevelDependentMagicValue() >= SPIDER_BRIGHT;
        boolean gold = mob instanceof Piglin && PiglinAi.isWearingSafeArmor(mod.getPlayer());
        return NeutralMobs.neutralNow(type, bright, gold);
    }

    // a blaze sitting 10 blocks off throwing fireballs used to count as harmless (only 3 blocks and closer was hostile),
    // so the bot stood there and ate through the volley. the blaze syncs a "charged" flag while it winds up, that is the
    // honest signal. cheap checks first, the line of sight raycast only for one that is actually winding up
    private static boolean isBlazeThreat(AltoClef mod, Blaze blaze) {
        double distance = mod.getPlayer().distanceTo(blaze);
        if (distance <= BlazeFightRules.MELEE_RANGE) return true;
        if (distance > BlazeFightRules.SIGHT_RANGE || !blaze.isOnFire()) return false;
        return BlazeFightRules.isThreat(distance, true, LookHelper.seesPlayer(blaze, mod.getPlayer(), BlazeFightRules.SIGHT_RANGE));
    }

    // can it walk up and hit us (so chasing it is not a waste of a trip). see MobReachability for how that gets decided
    public static boolean canMobReachPlayer(AltoClef mod, Mob mob) {
        return mod.getEntityTracker().getMobReachability().canWalkToPlayer(mod, mob);
    }

    // is it close enough, or coming at us, to be worth dropping the task for. see MobReachRules.shouldEngage
    public static boolean shouldEngageMob(AltoClef mod, Mob mob) {
        return mod.getEntityTracker().getMobReachability().shouldEngage(mod, mob);
    }

    // still inside the zone a fight is allowed to chase in
    public static boolean isMobInLeash(AltoClef mod, Mob mob) {
        return mod.getEntityTracker().getMobReachability().inLeash(mod, mob);
    }

    // can it hurt us at all, reachable or with a clear line for arrows. false means ignore it
    public static boolean canMobHarmPlayer(AltoClef mod, Mob mob) {
        return mod.getEntityTracker().getMobReachability().canHarmPlayer(mod, mob);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof Piglin pig) {
            if (pig.getHandSlots() != null) {
                for (ItemStack stack : pig.getHandSlots()) {
                    if (stack.getItem().equals(Items.GOLD_INGOT)) {
                        // We're trading with this one, ignore it.
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(Player player, DamageSource source, double damageAmount) {
        // Copied logic from `PlayerEntity.applyDamage`

        // 1.21.2 wants a ServerLevel for Player#isInvulnerableTo and the client has none, this is the part of it that matters
        if (player.isInvulnerable() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))
            return 0;

        // Armor Base
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            damageAmount = CombatRules.getDamageAfterAbsorb(player, (float) damageAmount, source, (float) player.getArmorValue(), (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }

        // Enchantments & Potions
        if (!source.is(DamageTypeTags.BYPASSES_SHIELD)) {
            float k;
            if (player.hasEffect(MobEffects.DAMAGE_RESISTANCE) && source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
                //noinspection ConstantConditions
                k = (player.getEffect(MobEffects.DAMAGE_RESISTANCE).getAmplifier() + 1) * 5;
                float j = 25 - k;
                double f = damageAmount * (double) j;
                double g = damageAmount;
                damageAmount = Math.max(f / 25.0F, 0.0F);
            }

            if (damageAmount <= 0.0) {
                damageAmount = 0.0;
            } else {
                k = getEnchantmentProtection(player, source);
                if (k > 0) {
                    damageAmount = CombatRules.getDamageAfterMagicAbsorb((float) damageAmount, k);
                }
            }
        }

        // Absorption
        damageAmount = Math.max(damageAmount - player.getAbsorptionAmount(), 0.0F);
        return damageAmount;
    }

    /**
     * Protection points from enchantments (the "EPF" the old damage formula ate).
     */
    private static float getEnchantmentProtection(Player player, DamageSource source) {
        // 1.21.2 evaluates enchantment effects on a ServerLevel. singleplayer has one, a remote server leaves us nothing
        var server = player.getServer();
        if (server != null) {
            var level = server.getLevel(player.level().dimension());
            if (level != null) {
                return EnchantmentHelper.getDamageProtection(level, player, source);
            }
        }
        float total = 0;
        for (ItemStack stack : player.getArmorSlots()) {
            ItemEnchantments enchantments = stack.getEnchantments();
            for (var entry : enchantments.entrySet()) {
                Holder<Enchantment> enchantment = entry.getKey();
                int level = entry.getIntValue();
                if (enchantment.is(Enchantments.PROTECTION)) {
                    total += level;
                } else if (enchantment.is(Enchantments.FIRE_PROTECTION) && source.is(DamageTypeTags.IS_FIRE)) {
                    total += 2 * level;
                } else if (enchantment.is(Enchantments.BLAST_PROTECTION) && source.is(DamageTypeTags.IS_EXPLOSION)) {
                    total += 2 * level;
                } else if (enchantment.is(Enchantments.PROJECTILE_PROTECTION) && source.is(DamageTypeTags.IS_PROJECTILE)) {
                    total += 2 * level;
                } else if (enchantment.is(Enchantments.FEATHER_FALLING) && source.is(DamageTypeTags.IS_FALL)) {
                    total += 3 * level;
                }
            }
        }
        return Math.min(total, 20f);
    }
}
