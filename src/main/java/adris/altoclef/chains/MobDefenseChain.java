package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.RunAwayFromCreepersTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.Baritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.lang.Math.abs;

public class MobDefenseChain extends SingleTaskChain {
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;//4;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;//15;
    private static final double SAFE_KEEP_DISTANCE = 8;
    private static final Item[] SWORDS = new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
            Items.STONE_SWORD, Items.WOODEN_SWORD};
    private static boolean _shielding = false;
    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private final KillAura _killAura = new KillAura();
    private Entity _targetEntity;
    private boolean _doingFunkyStuff = false;
    private boolean _wasPuttingOutFire = false;
    private CustomBaritoneGoalTask _runAwayTask;

    private float _cachedLastPriority;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    public static double getCreeperSafety(Vec3 pos, Creeper creeper) {
        double distance = creeper.distanceToSqr(pos);
        float fuse = creeper.getSwelling(1);

        // Not fusing.
        if (fuse <= 0.001f) return distance;
        return distance * 0.2; // less is WORSE
    }

    private static void startShielding(AltoClef mod) {
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

    @Override
    public float getPriority(AltoClef mod) {
        _cachedLastPriority = getPriorityInner(mod);
        return _cachedLastPriority;
    }

    private void stopShielding(AltoClef mod) {
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
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _shielding = false;
        }
    }

    private boolean escapeDragonBreath(AltoClef mod) {
        _dragonBreathTracker.updateBreath(mod);
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                return true;
            }
        }
        return false;
    }

    public float getPriorityInner(AltoClef mod) {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (!Baritone.settings().altoMobDefense.value) {
            return Float.NEGATIVE_INFINITY;
        }

        // Apply avoidance if we're vulnerable, avoiding mobs if at all possible.
        // mod.getClientBaritoneSettings().avoidance.value = isVulnurable(mod);
        // Doing you a favor by disabling avoidance


        // Pause if we're not loaded into a world.
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        // Put out fire if we're standing on one like an idiot
        BlockPos fireBlock = isInsideFireAndOnFire(mod);
        if (fireBlock != null) {
            putOutFire(mod, fireBlock);
            _wasPuttingOutFire = true;
        } else {
            // Stop putting stuff out if we no longer need to put out a fire.
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            _wasPuttingOutFire = false;
        }

        if (mod.getFoodChain().needsToEat() || mod.getMLGBucketChain().isFallingOhNo(mod) ||
                !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()) {
            _killAura.stopShielding(mod);
            stopShielding(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // Force field
        doForceField(mod);


        // Tell baritone to avoid mobs if we're vulnurable.
        // Costly.
        //mod.getClientBaritoneSettings().avoidance.value = isVulnurable(mod);

        // Run away if a weird mob is close by.
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent() && mod.getPlayer().getHealth() <= 10) {
            _runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            setTask(_runAwayTask);
            return 70;
        }

        _doingFunkyStuff = false;
        PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
        Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
        // Run away from creepers
        Creeper blowingUp = getClosestFusingCreeper(mod);
        if (blowingUp != null) {
            if (!mod.getFoodChain().needsToEat() && (mod.getItemStorage().hasItem(Items.SHIELD) ||
                    mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) &&
                    !mod.getEntityTracker().entityFound(ThrownPotion.class) && _runAwayTask == null
                    && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                _doingFunkyStuff = true;
                LookHelper.lookAt(mod, blowingUp.getEyePosition());
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            } else {
                _doingFunkyStuff = true;
                //Debug.logMessage("RUNNING AWAY!");
                _runAwayTask = new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
                setTask(_runAwayTask);
                return 50 + blowingUp.getSwelling(1) * 50;
            }
        } else {
            if (!isProjectileClose(mod)) {
                stopShielding(mod);
            }
        }
        // Block projectiles with shield
        if (!mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && isProjectileClose(mod) &&
                (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) &&
                !mod.getEntityTracker().entityFound(ThrownPotion.class) && _runAwayTask == null
                && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
            if (shieldSlot.getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else {
                startShielding(mod);
            }
        } else {
            if (blowingUp == null) {
                stopShielding(mod);
            }
        }
        // Dodge projectiles
        if (mod.getPlayer().getHealth() <= 10 || _runAwayTask != null || mod.getEntityTracker().entityFound(ThrownPotion.class) ||
                (!mod.getItemStorage().hasItem(Items.SHIELD) && !mod.getItemStorage().hasItemInOffhand(Items.SHIELD))) {
            if (!mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && isProjectileClose(mod)) {
                _doingFunkyStuff = true;
                //Debug.logMessage("DODGING");
                _runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                setTask(_runAwayTask);
                return 65;
            }
        }
        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod) && !escapeDragonBreath(mod) && !mod.getFoodChain().isShouldStop()) {
            if (_targetEntity == null) {
                _runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                setTask(_runAwayTask);
                return 70;
            }
        }

        if (Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value) {
            // Deal with hostiles because they are annoying.
            List<Entity> hostiles = mod.getEntityTracker().getHostiles();
            // TODO: I don't think this lock is necessary at all.

            // pick by real attack damage, not by list order (the old loop kept overwriting and ended on the worst sword owned)
            List<Item> ownedSwords = new ArrayList<>();
            for (Item item : SWORDS) {
                if (mod.getItemStorage().hasItem(item)) {
                    ownedSwords.add(item);
                }
            }
            Item bestSword = ItemHelper.getBestSword(ownedSwords);

            List<Entity> toDealWith = new ArrayList<>();
            // TODO: I don't think this lock is necessary at all.
            if (!hostiles.isEmpty()) {
                for (Entity entity : hostiles) {
                    if (entity instanceof Mob mob) {
                        boolean isAttackingPlayer = EntityHelper.isAngryAtPlayer(mod, mob);
                        if (isAttackingPlayer) {
                            toDealWith.add(mob);
                        }
                    }
                }
            }
            int numberOfProblematicEntities = toDealWith.size();
            if (!toDealWith.isEmpty()) {
                for (Entity ToDealWith : toDealWith) {
                    if (ToDealWith instanceof Slime) {
                        numberOfProblematicEntities = 1;
                        break;
                    }
                }
            }
            if (numberOfProblematicEntities > 0) {

                // Depending on our weapons/armor, we may chose to straight up kill hostiles if we're not dodging their arrows.

                // wood 0 : 1 skeleton
                // stone 1 : 1 skeleton
                // iron 2 : 2 hostiles
                // diamond 3 : 3 hostiles
                // netherite 4 : 4 hostiles

                // Armor: (do the math I'm not boutta calculate this)
                // leather: ?1 skeleton
                // iron: ?2 hostiles
                // diamond: ?3 hostiles

                // 7 is full set of leather
                // 15 is full set of iron.
                // 20 is full set of diamond.
                // Diamond+netherite have bonus "toughness" parameter (we can simply add them I think, for now.)
                // full diamond has 8 bonus toughness
                // full netherite has 12 bonus toughness
                int armor = mod.getPlayer().getArmorValue();
                // the formula below was tuned on "1 + tier bonus" (wood 1 ... netherite 5), real damage is 3 higher than that
                float damage = bestSword == null ? 0 : (ItemHelper.getAttackDamage(bestSword) - 3);
                boolean hasShield = mod.getItemStorage().hasItem(Items.SHIELD) ||
                        mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
                int shield = hasShield ? 20 : 0;
                int canDealWith = (int) Math.ceil((armor * 3.6 / 20.0) + (damage * 0.8) + (shield));
                canDealWith += 1;
                if (canDealWith > numberOfProblematicEntities) {
                    // We can deal with it.
                    _runAwayTask = null;
                    for (Entity ToDealWith : toDealWith) {
                        Predicate<Entity> valid = entity -> EntityHelper.isAngryAtPlayer(mod, entity);
                        // Prioritize ranged enemies first.
                        if (ToDealWith instanceof Skeleton || ToDealWith instanceof Witch ||
                                ToDealWith instanceof Pillager || ToDealWith instanceof Piglin ||
                                ToDealWith instanceof Stray) {
                            setTask(new KillEntitiesTask(valid, ToDealWith.getClass()));
                            return 65;
                        }
                        setTask(new KillEntitiesTask(valid, ToDealWith.getClass()));
                        return 65;
                    }
                }
                // We can't deal with it
                _runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                setTask(_runAwayTask);
                return 80;
            }
        }
        // By default if we aren't "immediately" in danger but were running away, keep running away until we're good.
        if (_runAwayTask != null && !_runAwayTask.isFinished(mod)) {
            setTask(_runAwayTask);
            return _cachedLastPriority;
        }
        _runAwayTask = null;
        return 0;
    }

    private BlockPos isInsideFireAndOnFire(AltoClef mod) {
        boolean onFire = mod.getPlayer().isOnFire();
        if (!onFire) return null;
        BlockPos p = mod.getPlayer().blockPosition();
        BlockPos[] toCheck = new BlockPos[]{
                p,
                p.offset(1, 0, 0),
                p.offset(1, 0, -1),
                p.offset(0, 0, -1),
                p.offset(-1, 0, -1),
                p.offset(-1, 0, 0),
                p.offset(-1, 0, 1),
                p.offset(0, 0, 1),
                p.offset(1, 0, 1)
        };
        for (BlockPos check : toCheck) {
            Block b = mod.getWorld().getBlockState(check).getBlock();
            if (b instanceof BaseFireBlock) {
                return check;
            }
        }
        return null;
    }

    private void putOutFire(AltoClef mod, BlockPos pos) {
        Optional<Rotation> reach = LookHelper.getReach(pos);
        if (reach.isPresent()) {
            Baritone b = mod.getClientBaritone();
            if (LookHelper.isLookingAt(mod, pos)) {
                b.getPathingBehavior().requestPause();
                b.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                return;
            }
            LookHelper.lookAt(mod, reach.get());
        }
    }

    private void doForceField(AltoClef mod) {

        _killAura.tickStart();

        // Hit all hostiles close to us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        try {
            if (!entities.isEmpty()) {
                for (Entity entity : entities) {
                    boolean shouldForce = false;
                    if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                    if (entity instanceof Mob) {
                        if (EntityHelper.isGenerallyHostileToPlayer(mod, entity)) {
                            if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10)) {
                                shouldForce = true;
                            }
                        }
                    } else if (entity instanceof LargeFireball) {
                        // Ghast ball
                        shouldForce = true;
                    } else if (entity instanceof Player player && mod.getBehaviour().shouldForceFieldPlayers()) {
                        if (!player.equals(mod.getPlayer())) {
                            String name = player.getName().getString();
                            if (!mod.getButler().isUserSpared(name)) {
                                shouldForce = true;
                            }
                        }
                    }
                    if (shouldForce) {
                        applyForceField(entity);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        _killAura.tickEnd(mod);
    }

    private void applyForceField(Entity entity) {
        _killAura.applyAura(entity);
    }

    private Creeper getClosestFusingCreeper(AltoClef mod) {
        double worstSafety = Float.POSITIVE_INFINITY;
        Creeper target = null;
        try {
            // every creeper, not just the closest one: a lit fuse further away used to be hidden behind a calm one next to us
            for (Creeper creeperEntity : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (creeperEntity.getSwelling(1) < 0.001) continue;
                // We want to pick the closest creeper, but FIRST pick creepers about to blow
                // At max fuse, the cost goes to basically zero.
                double safety = getCreeperSafety(mod.getPlayer().position(), creeperEntity);
                if (safety < worstSafety) {
                    // (worstSafety never got updated, so it was always the last one in line that won)
                    worstSafety = safety;
                    target = creeperEntity;
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // IDK why but these exceptions happen sometimes. It's extremely bizarre and I have no idea why.
            Debug.logWarning("Weird Exception caught and ignored while scanning for creepers: " + e.getMessage());
            return target;
        }
        return target;
    }

    private boolean isProjectileClose(AltoClef mod) {
        List<CachedProjectile> projectiles = mod.getEntityTracker().getProjectiles();
        try {
            if (!projectiles.isEmpty()) {
                for (CachedProjectile projectile : projectiles) {
                    if (projectile.position.distanceToSqr(mod.getPlayer().position()) < 150) {
                        boolean isGhastBall = projectile.projectileType == LargeFireball.class;
                        if (isGhastBall) {
                            Optional<Entity> ghastBall = mod.getEntityTracker().getClosestEntity(LargeFireball.class);
                            Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(Ghast.class);
                            if (ghastBall.isPresent() && ghast.isPresent() && _runAwayTask == null
                                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                                mod.getClientBaritone().getPathingBehavior().requestPause();
                                LookHelper.lookAt(mod, ghast.get().getEyePosition());
                            }
                            return false;
                            // Ignore ghast balls
                        }
                        if (projectile.projectileType == DragonFireball.class) {
                            // Ignore dragon fireballs
                            return false;
                        }
                        if (projectile.projectileType == Arrow.class || projectile.projectileType == SpectralArrow.class || projectile.projectileType == SmallFireball.class) {
                            // check if the velocity of the projectile is going away from us
                            // oh no fancy math
                            Vec3 velocity = projectile.velocity;
                            Vec3 delta = mod.getPlayer().position().subtract(projectile.position);
                            double epsilon = 0.25;
                            // signed on purpose: abs() ignored the arrows sailing sideways past us and kept dodging the ones already flying away
                            if (velocity.dot(delta) <= epsilon) {
                                // Arrow is going away from us, ignore it.
                                continue;
                            }
                        }

                        Vec3 expectedHit = ProjectileHelper.calculateArrowClosestApproach(projectile, mod.getPlayer());

                        Vec3 delta = mod.getPlayer().position().subtract(expectedHit);

                        //Debug.logMessage("EXPECTED HIT OFFSET: " + delta + " ( " + projectile.gravity + ")");

                        double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                        double verticalDistance = abs(delta.y);
                        if (horizontalDistanceSq < ARROW_KEEP_DISTANCE_HORIZONTAL * ARROW_KEEP_DISTANCE_HORIZONTAL && verticalDistance < ARROW_KEEP_DISTANCE_VERTICAL) {
                            if (_runAwayTask == null && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                                mod.getClientBaritone().getPathingBehavior().requestPause();
                                if (projectile.projectileType instanceof Projectile projectileEntity) {
                                    Entity owner = projectileEntity.getOwner();
                                    if (owner != null) {
                                        LookHelper.lookAt(mod, owner.getEyePosition());
                                        return true;
                                    }
                                    LookHelper.lookAt(mod, projectile.position);
                                    return true;
                                }
                                LookHelper.lookAt(mod, projectile.position);
                                return true;
                            }
                            return true;
                        }
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
        }
        return false;
    }

    private Optional<Entity> getUniversallyDangerousMob(AltoClef mod) {
        // Wither skeletons are dangerous because of the wither effect. Oof kinda obvious.
        // If we merely force field them, we will run into them and get the wither effect which will kill us.
        Optional<Entity> warden = mod.getEntityTracker().getClosestEntity(Warden.class);
        if (warden.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (warden.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, warden.get())) {
                return warden;
            }
        }
        Optional<Entity> wither = mod.getEntityTracker().getClosestEntity(WitherBoss.class);
        if (wither.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (wither.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, wither.get())) {
                return wither;
            }
        }
        Optional<Entity> witherSkeleton = mod.getEntityTracker().getClosestEntity(WitherSkeleton.class);
        if (witherSkeleton.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (witherSkeleton.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, witherSkeleton.get())) {
                return witherSkeleton;
            }
        }
        // Hoglins are dangerous because we can't push them with the force field.
        // If we merely force field them and stand still our health will slowly be chipped away until we die
        Optional<Entity> hoglin = mod.getEntityTracker().getClosestEntity(Hoglin.class);
        if (hoglin.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (hoglin.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, hoglin.get())) {
                return hoglin;
            }
        }
        Optional<Entity> zoglin = mod.getEntityTracker().getClosestEntity(Zoglin.class);
        if (zoglin.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (zoglin.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, zoglin.get())) {
                return zoglin;
            }
        }
        Optional<Entity> piglinBrute = mod.getEntityTracker().getClosestEntity(PiglinBrute.class);
        if (piglinBrute.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (piglinBrute.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, piglinBrute.get())) {
                return piglinBrute;
            }
        }
        Optional<Entity> vindicator = mod.getEntityTracker().getClosestEntity(Vindicator.class);
        if (vindicator.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (vindicator.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, vindicator.get())) {
                return vindicator;
            }
        }
        return Optional.empty();
    }

    private boolean isInDanger(AltoClef mod) {
        Optional<Entity> witch = mod.getEntityTracker().getClosestEntity(Witch.class);
        boolean hasFood = mod.getFoodChain().hasFood();
        float health = mod.getPlayer().getHealth();
        if (health <= 10 && hasFood && witch.isEmpty()) {
            return true;
        }
        if (mod.getPlayer().hasEffect(MobEffects.WITHER) ||
                (mod.getPlayer().hasEffect(MobEffects.POISON) && witch.isEmpty())) {
            return true;
        }
        if (isVulnurable(mod)) {
            // If hostile mobs are nearby...
            try {
                LocalPlayer player = mod.getPlayer();
                List<Entity> hostiles = mod.getEntityTracker().getHostiles();
                if (!hostiles.isEmpty()) {
                    synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                        for (Entity entity : hostiles) {
                            if (entity.closerThan(player, SAFE_KEEP_DISTANCE) && !mod.getBehaviour().shouldExcludeFromForcefield(entity) && EntityHelper.isAngryAtPlayer(mod, entity)) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Weird multithread exception. Will fix later.");
            }
        }
        return false;
    }

    private boolean isVulnurable(AltoClef mod) {
        int armor = mod.getPlayer().getArmorValue();
        float health = mod.getPlayer().getHealth();
        if (armor <= 15 && health < 3) return true;
        if (armor < 10 && health < 10) return true;
        return armor < 5 && health < 18;
    }

    public void setTargetEntity(Entity entity) {
        _targetEntity = entity;
    }

    public void resetTargetEntity() {
        _targetEntity = null;
    }

    public void setForceFieldRange(double range) {
        _killAura.setRange(range);
    }

    public void resetForceField() {
        _killAura.setRange(Double.POSITIVE_INFINITY);
    }

    public boolean isDoingAcrobatics() {
        return _doingFunkyStuff;
    }

    public boolean isPuttingOutFire() {
        return _wasPuttingOutFire;
    }

    @Override
    public boolean isActive() {
        // We're always checking for mobs
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Task is done, so I guess we move on?
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}