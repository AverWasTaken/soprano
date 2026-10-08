package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.RunAwayFromCreepersTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.Baritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
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
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static java.lang.Math.abs;

public class MobDefenseChain extends SingleTaskChain {
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;//4;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;//15;
    private static final double SAFE_KEEP_DISTANCE = 8;
    private static boolean _shielding = false;
    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private final KillAura _killAura = new KillAura();
    private Entity _targetEntity;
    private boolean _doingFunkyStuff = false;
    private boolean _wasPuttingOutFire = false;
    private CustomBaritoneGoalTask _runAwayTask;

    private float _cachedLastPriority;

    // everything below the line is worked out once per game tick by snapshot(), no matter how many things ask
    private long _snapshotTick = Long.MIN_VALUE;
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    private int _lastHurtTime;
    // far enough in the past that it is not a recent hit, close enough to the present that subtracting is not an overflow
    private long _lastCombatHurtTick = Long.MIN_VALUE / 2;
    // any damage at all, from anything. a stroll past a zombie stops being one when something bites
    private long _lastHurtTick = Long.MIN_VALUE / 2;
    // the crit timing and the weapon pick want the plain version: any hit at all, and how many melee mobs are on us / around us
    private long _lastAnyHurtTick = Long.MIN_VALUE / 2;
    private int _meleeNear;
    private int _meleeAround;

    private final CombatPolicy _policy = new CombatPolicy();
    private final CombatPolicy.TravelTracker _travel = new CombatPolicy.TravelTracker();
    private Task _travelUserTask;
    private CombatPolicy.Decision _decision = NO_POLICY;
    private boolean _lowHpLatched;
    // the ones we are actually dealing with this tick (not the ones walking past), nearest first
    private List<Mob> _dealWith = List.of();
    private String _lastVerdictLog = "";

    // what it looks like when nobody is in charge: shield whenever it fits, nothing ignored, the old way
    private static final CombatPolicy.Decision NO_POLICY = new CombatPolicy.Decision(CombatPolicy.Verdict.STAND, Set.of(), 0, 0, false);
    // how far a retreat goes before it checks in. far enough to string a crowd out, near enough that we do not leave town
    private static final double KITE_DISTANCE = 12;
    // faster than this (movement speed attribute) and a sprinting player is not outrunning it: vindicators, spiders, hoglins, babies
    private static final double FAST_SPEED = 0.3;
    private static final int PATH_LOOKAHEAD = 10;
    // a run away from danger holds this long once it starts. the kill branch used to be asked first on the very next tick,
    // so a skeleton at 9 blocks was a run and a skeleton at 8.9 was a fight and we danced between the two
    private static final long RUN_LATCH_TICKS = 40;
    // there is no cheap way to find cover from a shooter (the search runs on another thread and cannot raycast the world),
    // so a run from shooters only is a short hop out of their range, not the 30 block march a crowd gets
    private static final double RANGED_RUN_DISTANCE = 12;
    // how long a dodge keeps the fight it interrupted "on" for the flee checks
    private static final long PARK_TICKS = 40;
    // the half hearts the dodge gate looks at. same number the food chain and isInDanger use for "should be eating"
    private static final float LOW_HEALTH = 10;

    private RunAwayFromHostilesTask _dangerRun;
    private long _runLatchUntil = Long.MIN_VALUE / 2;
    private long _parkedUntil = Long.MIN_VALUE / 2;
    private boolean _wasCharging;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    public static double getCreeperSafety(Vec3 pos, Creeper creeper) {
        Vec3 at = creeper.position();
        return getCreeperSafety(pos.x, pos.y, pos.z, at.x, at.y, at.z, creeper.getSwelling(1));
    }

    // the creeper as plain numbers, so the pathfinder thread can score nodes without touching the entity
    public static double getCreeperSafety(double x, double y, double z, double creeperX, double creeperY, double creeperZ, float fuse) {
        double dx = creeperX - x, dy = creeperY - y, dz = creeperZ - z;
        double distance = dx * dx + dy * dy + dz * dz;

        // Not fusing.
        if (fuse <= 0.001f) return distance;
        return distance * 0.2; // less is WORSE
    }

    private static void startShielding(AltoClef mod) {
        startShielding(mod, true);
    }

    // standing still is the default. a charge raises the shield on the move: no sneak (it is walking at a third of the
    // speed), no pause (a paused approach is how the skeleton ended up marked as stalled), and the caller lowers it the
    // moment the arrow is gone
    private static void startShielding(AltoClef mod, boolean standStill) {
        _shielding = true;
        if (standStill) {
            mod.getInputControls().hold(Input.SNEAK);
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        if (standStill) {
            mod.getClientBaritone().getPathingBehavior().requestPause();
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        }
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

        long now = mod.getWorld().getGameTime();
        boolean charging = _decision.charging();
        if (_wasCharging && !charging && mod.getPlayer().getHealth() <= CombatRules.FLEE_HEALTH) {
            // the charge ended on health, not on skeleton. the kill task is still holding the target, and a held target is
            // what stops the flee below from ever firing
            _targetEntity = null;
            _parkedUntil = Long.MIN_VALUE / 2;
        }
        _wasCharging = charging;


        // Tell baritone to avoid mobs if we're vulnurable.
        // Costly.
        //mod.getClientBaritoneSettings().avoidance.value = isVulnurable(mod);

        // Run away if a weird mob is close by.
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent() && mod.getPlayer().getHealth() <= 10) {
            runFrom(now, new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true));
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
        boolean hasShield = mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
        boolean shielded = false;
        // a charge does not stop for the shield, so it does not need the path to be in a spot where stopping is allowed
        // either. a run does not shield: that is feet's job
        if (!mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && isProjectileClose(mod) &&
                hasShield && !mod.getEntityTracker().entityFound(ThrownPotion.class) && !isRunLatched(now)
                && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                && (charging || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            shielded = true;
            if (_runAwayTask instanceof DodgeProjectilesTask) {
                // we are ducking behind the shield now, the dodge's fallback must not drag us out of it
                _runAwayTask = null;
            }
            ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
            if (shieldSlot.getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else {
                startShielding(mod, !charging);
            }
        } else {
            if (blowingUp == null) {
                stopShielding(mod);
            }
        }
        // Dodge projectiles
        // (a run in progress used to count as a reason here, which latched: one run and every arrow after it was a dodge,
        // and a dodge dropped the kill target. a charge never dodges, it shields or eats the arrow, the dodge goes
        // sideways and the skeleton gets another volley)
        if (!shielded && !charging && (mod.getPlayer().getHealth() <= LOW_HEALTH || mod.getEntityTracker().entityFound(ThrownPotion.class) || !hasShield)) {
            if (!mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && isProjectileClose(mod)) {
                _doingFunkyStuff = true;
                //Debug.logMessage("DODGING");
                _runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                setTask(_runAwayTask);
                return 65;
            }
        }
        // a danger run that is still inside its hold keeps the wheel, whatever the kill branch below would like
        if (_dangerRun != null && _dangerRun.isFinished(mod)) {
            _dangerRun = null;
        }
        if (isRunLatched(now)) {
            _runAwayTask = _dangerRun;
            setTask(_dangerRun);
            return 70;
        }
        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod) && !escapeDragonBreath(mod) && !mod.getFoodChain().isShouldStop()) {
            if (!fightOn(now)) {
                runFrom(now, dangerRunTask());
                return 70;
            }
        }

        // losing a fight is not the time to trade blows or to chew. leave, the food chain eats once we are out of it.
        // (the food chain only lets go of its bite when it sees this stance, see FoodChain.needsToEat)
        if (!fightOn(now) && getCombatStance(mod) == CombatRules.Stance.FLEE) {
            runFrom(now, dangerRunTask());
            return 80;
        }

        if (Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value) {
            // Deal with hostiles because they are annoying.
            // the weapon we would swing, the same pick the kill tasks make (swords and axes, worn ones last). the kit makes
            // axes now, and a sword only list called a bot with an iron axe unarmed
            Item bestWeapon = AbstractKillEntityTask.bestWeapon(mod);

            // who is a problem was settled in snapshot(): angry, in the engage zone, able to hurt us, not excluded by a
            // task that is fighting it itself, and not just somebody we are walking past
            List<Entity> toDealWith = new ArrayList<>(_dealWith);
            if (_decision.hold()) {
                // we just ran from a crowd, the ones still coming get to come to us. walking back into the pile to say
                // hello is how this ends up as a dance
                // (shooters are exempt, they are not in the pile and they do not come to us, they stand back and shoot)
                toDealWith.removeIf(entity -> !(entity instanceof Mob mob && MobReachability.isRanged(mob))
                        && entity.distanceTo(mod.getPlayer()) > CombatPolicy.HOLD_CHASE);
            }

            // a crowd is backed away from, not fought on the spot. no kill task, no shield, the force field holds still too
            if (_decision.kiting()) {
                _runAwayTask = new RunAwayFromHostilesTask(KITE_DISTANCE, true, this::getKiteThreats);
                _dangerRun = null;
                setTask(_runAwayTask);
                return 80;
            }
            // one or two shooters are walked up to and killed. this goes before the gear maths below, which is for piles
            // of melee mobs and used to tell a naked bot to run from a lone skeleton (standCapacity is 1, one mob is not
            // less than one)
            if (_decision.charging()) {
                Mob shooter = nearestShooter(toDealWith);
                if (shooter != null) {
                    _runAwayTask = null;
                    _dangerRun = null;
                    // no reachability test on purpose: a skeleton we stood and shielded at for two seconds reads as
                    // "stalled", which is the chain dropping the target we are about to hit
                    Predicate<Entity> valid = entity -> EntityHelper.isAngryAtPlayer(mod, entity);
                    Predicate<Entity> leash = entity -> !(entity instanceof Mob mob) || EntityHelper.isMobInLeash(mod, mob);
                    setTask(new KillEntitiesTask(valid, leash, shooter.getClass()));
                    return 65;
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
                float damage = bestWeapon == null ? 0 : (ItemHelper.getAttackDamage(bestWeapon) - 3);
                // (the shield used to be +20 here, which made a shielded bot stand in the middle of any crowd)
                int canDealWith = CombatPolicy.standCapacity(armor, damage, hasShield);
                // a crowd we could not run from (or were told to stand against) is fought whatever the gear says, running
                // blind with a pile of them on our heels is worse than the shield
                boolean crowd = _decision.swarm() >= Baritone.settings().altoSwarmThreshold.value || _decision.cornered();
                if (canDealWith > numberOfProblematicEntities || crowd) {
                    // We can deal with it.
                    for (Entity ToDealWith : toDealWith) {
                        // the ones that can only shoot at us are the dodge logic's problem. walking out of our safe spot
                        // to chase something that cannot walk to us is how this used to end up in the open
                        if (!EntityHelper.canMobReachPlayer(mod, (Mob) ToDealWith)) continue;
                        _runAwayTask = null;
                        _dangerRun = null;
                        Predicate<Entity> valid = entity -> EntityHelper.isAngryAtPlayer(mod, entity)
                                && (!(entity instanceof Mob mob) || EntityHelper.canMobReachPlayer(mod, mob));
                        // the leash: past it the target is dropped and we go back to our day, chasing it round the
                        // map is how this used to eat whole tasks
                        Predicate<Entity> leash = entity -> !(entity instanceof Mob mob) || EntityHelper.isMobInLeash(mod, mob);
                        // Prioritize ranged enemies first.
                        if (ToDealWith instanceof Skeleton || ToDealWith instanceof Witch ||
                                ToDealWith instanceof Pillager || ToDealWith instanceof Piglin ||
                                ToDealWith instanceof Stray) {
                            setTask(new KillEntitiesTask(valid, leash, ToDealWith.getClass()));
                            return 65;
                        }
                        setTask(new KillEntitiesTask(valid, leash, ToDealWith.getClass()));
                        return 65;
                    }
                    // nothing left that we can walk to, so no takeover. keep whatever we were doing
                } else {
                    // We can't deal with it
                    runFrom(now, dangerRunTask());
                    return 80;
                }
            }
        }
        // By default if we aren't "immediately" in danger but were running away, keep running away until we're good.
        if (_runAwayTask != null && !_runAwayTask.isFinished(mod)) {
            setTask(_runAwayTask);
            return _cachedLastPriority;
        }
        _runAwayTask = null;
        _dangerRun = null;
        return 0;
    }

    // start (or keep) a run away from danger. the same run asked for again is the same run, so the hold on it is only
    // started once. a different one is a new thing and gets its own
    private void runFrom(long now, RunAwayFromHostilesTask run) {
        if (_dangerRun != null && _dangerRun.equals(run)) {
            run = _dangerRun;
        } else {
            _dangerRun = run;
            _runLatchUntil = now + RUN_LATCH_TICKS;
        }
        _runAwayTask = run;
        setTask(run);
    }

    private boolean isRunLatched(long now) {
        return _dangerRun != null && now < _runLatchUntil;
    }

    // what to run with. a crowd (or anything we cannot tell) gets the long march, shooters only get a short hop out of range
    private RunAwayFromHostilesTask dangerRunTask() {
        if (!_dealWith.isEmpty() && _dealWith.stream().allMatch(MobReachability::isRanged)) {
            return new RunAwayFromHostilesTask(RANGED_RUN_DISTANCE, true, () -> new ArrayList<>(_dealWith));
        }
        return new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
    }

    // is somebody being fought right now. a dodge that cut a kill short parks the target for a moment instead of
    // dropping it, so this stays true through the dodge
    private boolean fightOn(long now) {
        // at the low hp latch nothing is worth finishing, a held kill target used to be the reason the flee never fired
        if (_lowHpLatched) return false;
        return _targetEntity != null || now <= _parkedUntil;
    }

    // the target stays "in the fight" for a bit but the task is gone. only the dodge in AbstractDoToEntityTask does this
    public void parkTarget(long now) {
        _targetEntity = null;
        _parkedUntil = now + PARK_TICKS;
    }

    // closest shooter worth walking at, from a list that is already nearest first
    private static Mob nearestShooter(List<Entity> nearestFirst) {
        for (Entity entity : nearestFirst) {
            if (entity instanceof Mob mob && MobReachability.isRanged(mob) && !isFast(mob)) return mob;
        }
        return null;
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

        snapshot(mod);
        _killAura.tickStart();
        // the shield is for standing in a pile, not for the first zombie that wanders up
        _killAura.setPolicy(_decision.kiting(), _decision.shield());

        // Hit all hostiles close to us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        try {
            if (!entities.isEmpty()) {
                for (Entity entity : entities) {
                    boolean shouldForce = false;
                    if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                    // walking past it, it is not in reach (that is the rule) so the aura has no business with it either
                    if (_decision.ignored().contains(entity.getId())) continue;
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
                            // (a charge keeps walking: no pause, no turning round to face the shooter, we are already going
                            // at it)
                            if (!_decision.charging() && _runAwayTask == null && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
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
        // the melee only ones (no wither skeleton arrows, no hoglin spit) are not a danger if they cannot walk to us.
        // fleeing is cheap but fleeing from something stuck behind a wall is how you leave the safe hole. distance first
        // so the far ones do not get a reachability verdict they did not ask for
        Predicate<Entity> meleeThreat = entity -> entity.distanceToSqr(mod.getPlayer()) < (SAFE_KEEP_DISTANCE - 2) * (SAFE_KEEP_DISTANCE - 2)
                && EntityHelper.isAngryAtPlayer(mod, entity)
                && (!(entity instanceof Mob mob) || EntityHelper.canMobReachPlayer(mod, mob));
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
        Optional<Entity> witherSkeleton = mod.getEntityTracker().getClosestEntity(meleeThreat, WitherSkeleton.class);
        if (witherSkeleton.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (witherSkeleton.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, witherSkeleton.get())) {
                return witherSkeleton;
            }
        }
        // Hoglins are dangerous because we can't push them with the force field.
        // If we merely force field them and stand still our health will slowly be chipped away until we die
        Optional<Entity> hoglin = mod.getEntityTracker().getClosestEntity(meleeThreat, Hoglin.class);
        if (hoglin.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (hoglin.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, hoglin.get())) {
                return hoglin;
            }
        }
        Optional<Entity> zoglin = mod.getEntityTracker().getClosestEntity(meleeThreat, Zoglin.class);
        if (zoglin.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (zoglin.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, zoglin.get())) {
                return zoglin;
            }
        }
        Optional<Entity> piglinBrute = mod.getEntityTracker().getClosestEntity(meleeThreat, PiglinBrute.class);
        if (piglinBrute.isPresent()) {
            double range = SAFE_KEEP_DISTANCE - 2;
            if (piglinBrute.get().distanceToSqr(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, piglinBrute.get())) {
                return piglinBrute;
            }
        }
        Optional<Entity> vindicator = mod.getEntityTracker().getClosestEntity(meleeThreat, Vindicator.class);
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
        // (one or two skeletons at hp 9 or 10 are a charge, the policy says so. the policy already checked we are above
        // the flee line, so hp 8 and under still gets here)
        if (health <= LOW_HEALTH && hasFood && witch.isEmpty() && !_decision.charging()) {
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
                    // things that walk at us, and things that shoot at us: one skeleton is not a reason to run
                    int melee = 0;
                    int shooters = 0;
                    synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                        for (Entity entity : hostiles) {
                            if (entity.closerThan(player, SAFE_KEEP_DISTANCE) && !mod.getBehaviour().shouldExcludeFromForcefield(entity) && EntityHelper.isAngryAtPlayer(mod, entity)
                                    && (!(entity instanceof Mob mob) || EntityHelper.canMobHarmPlayer(mod, mob))) {
                                if (entity instanceof Mob shooter && MobReachability.isRanged(shooter) && !isFast(shooter)) {
                                    shooters++;
                                } else {
                                    melee++;
                                }
                            }
                        }
                    }
                    return CombatPolicy.dangerousCompany(melee, shooters);
                }
            } catch (Exception e) {
                Debug.logWarning("Weird multithread exception. Will fix later.");
            }
        }
        return false;
    }

    private boolean isVulnurable(AltoClef mod) {
        return CombatPolicy.vulnerable(mod.getPlayer().getArmorValue(), mod.getPlayer().getHealth());
    }

    // fight / flee / eat, worked out once per tick no matter how many things ask (the food chain asks a lot, needsToEat
    // is everywhere). the answer lives here because the hostile list and the reach rules do
    public CombatRules.Stance getCombatStance(AltoClef mod) {
        if (!AltoClef.inGame()) return CombatRules.Stance.CALM;
        snapshot(mod);
        return _stance;
    }

    // the whole picture, built once per game tick from the one pass over the hostile list: who is a problem, who is just
    // being walked past, what the policy says to do about it, and the combat stance that falls out. the engage zone
    // goes first, it is the cheap question and it keeps the closing in history fed
    private void snapshot(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now == _snapshotTick) return;
        _snapshotTick = now;

        LocalPlayer player = mod.getPlayer();
        // a fresh hit shows up as hurtTime jumping back up
        boolean freshHit = player.hurtTime > _lastHurtTime;
        _lastHurtTime = player.hurtTime;
        if (freshHit) _lastAnyHurtTick = now;
        // an arrow in the back is not somebody walking up to us. it used to reset the walking-past grace for every zombie on
        // the hillside, so one skeleton made us stop and fight all of them. below the low line it counts as before, at that
        // health the whole thing is a different conversation. no damage source on the client (it is only filled in by the
        // damage event) reads as not an arrow, which is the old behaviour
        if (freshHit && (player.getHealth() <= LOW_HEALTH || !hitByProjectile(player))) _lastHurtTick = now;

        boolean policyOn = Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value;
        boolean travelling = policyOn && updateTravel(mod, now);
        MobReachability reach = mod.getEntityTracker().getMobReachability();

        List<Mob> engaged = new ArrayList<>();
        List<Mob> dealable = new ArrayList<>();
        List<CombatPolicy.Mob> policyMobs = new ArrayList<>();
        double nearestAny = Double.POSITIVE_INFINITY;
        long grace = Math.max(0, Baritone.settings().altoPassByGraceTicks.value);
        boolean shield = mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
        int meleeNear = 0;
        int meleeAround = 0;
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob)) continue;
                if (!reach.shouldEngage(mod, mob, travelling) || !EntityHelper.canMobHarmPlayer(mod, mob)) continue;
                engaged.add(mob);
                nearestAny = Math.min(nearestAny, mob.distanceTo(player));
                // a task that is fighting this one itself (golem on its pillar) does not want a second opinion. and angry
                // is not the same as dangerous: one in the wall of our hole screaming at us is not a fight
                if (!policyOn || mod.getBehaviour().shouldExcludeFromMobDefense(mob) || !EntityHelper.isAngryAtPlayer(mod, mob)) continue;
                dealable.add(mob);
                CombatPolicy.Mob policyMob = policyMob(mod, reach, player, mob, now, grace);
                policyMobs.add(policyMob);
                if (policyMob.melee()) {
                    double distance = policyMob.distance();
                    if (distance <= CombatPolicy.CONTACT_RANGE) meleeNear++;
                    if (distance <= CombatPolicy.SWARM_RANGE) meleeAround++;
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, one tick of stale combat state is fine
        }
        _meleeNear = meleeNear;
        _meleeAround = meleeAround;

        CombatPolicy.Decision decision = NO_POLICY;
        if (policyOn) {
            if (policyMobs.isEmpty()) {
                decision = _policy.idle();
            } else {
                if (travelling) _travel.setUpcoming(upcomingPath(mod));
                decision = _policy.decide(now, new CombatPolicy.Scene(policyMobs, now - _lastHurtTick, travelling,
                        relativePath(player), player.getX(), player.getZ(),
                        Math.max(1, Baritone.settings().altoSwarmThreshold.value),
                        grace, player.getHealth(), shield));
            }
        }
        _decision = decision;
        _lowHpLatched = policyOn && _policy.lowHpLatched(now);
        logVerdict(mod, decision, dealable);

        List<Mob> dealWith = new ArrayList<>(dealable.size());
        for (Mob mob : dealable) {
            if (!decision.ignored().contains(mob.getId())) dealWith.add(mob);
        }
        // the closest one first, it is the one that is about to hit us
        dealWith.sort(Comparator.comparingDouble(mob -> mob.distanceToSqr(player)));
        _dealWith = dealWith;

        // the ones we are walking past do not make this a fight, as far as eating goes they are not even around
        double nearest = Double.POSITIVE_INFINITY;
        for (Mob mob : engaged) {
            if (!decision.ignored().contains(mob.getId())) nearest = Math.min(nearest, mob.distanceTo(player));
        }
        // whatever hit us, if something hostile is around it is a fight
        if (freshHit && !Double.isInfinite(nearestAny)) _lastCombatHurtTick = now;

        Creeper fusing = getClosestFusingCreeper(mod);
        boolean creeperClose = fusing != null && fusing.distanceTo(player) <= CombatRules.CREEPER_RANGE;
        // backing away from a crowd is a fight too, the crowd being a few blocks behind us does not make it lunch time
        // and so is the low hp latch (see CombatPolicy.LOW_HP): the user task getting the wheel back for a tick because
        // the crowd looked "ignored" or "far" is how we walked into the pile at hp 3
        boolean inCombat = CombatRules.inCombat(nearest, now - _lastCombatHurtTick, creeperClose) || decision.kiting()
                || (_lowHpLatched && !Double.isInfinite(nearestAny));
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean hasGapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        _stance = CombatRules.stance(inCombat, player.getHealth(), nearest, hasGapple);
        // the quick bite at hp 4 is for "nothing next to us", and mob defense standing down for it hands the wheel to the
        // user task for as long as we chew. with the latch on something is still within 8, so feet first, bite later
        if (_lowHpLatched && _stance == CombatRules.Stance.EAT) _stance = CombatRules.Stance.FLEE;
    }

    // everything the policy wants to know about one mob, as plain numbers
    private static CombatPolicy.Mob policyMob(AltoClef mod, MobReachability reach, LocalPlayer player, Mob mob, long now, long grace) {
        double dx = mob.getX() - player.getX(), dy = mob.getY() - player.getY(), dz = mob.getZ() - player.getZ();
        boolean ranged = MobReachability.isRanged(mob);
        // the line of sight raycast is not free, only the ones that could shoot us from where they are get one
        boolean sees = ranged && dx * dx + dy * dy + dz * dz <= CombatPolicy.RANGED_NO_IGNORE * CombatPolicy.RANGED_NO_IGNORE
                && reach.seesPlayer(mod, mob);
        // the grudge book has this one if it hit us (or we hit it) lately. that is who the pass-by grace is about
        boolean hitUs = mod.getEntityTracker().getProvocations().recent(mob.getId(), now, grace);
        return new CombatPolicy.Mob(mob.getId(), dx, dy, dz, ranged, mob instanceof Creeper, isFast(mob), sees, hitUs);
    }

    // how long since anything hurt us, for the crit timing. the plain version of _lastHurtTick (arrows count)
    public long ticksSinceHurt(AltoClef mod) {
        if (!AltoClef.inGame()) return Long.MAX_VALUE / 2;
        snapshot(mod);
        return mod.getWorld().getGameTime() - _lastAnyHurtTick;
    }

    // melee mobs we are dealing with within contact range, and within the swarm range
    public int meleeNear(AltoClef mod) {
        if (!AltoClef.inGame()) return 0;
        snapshot(mod);
        return _meleeNear;
    }

    public int meleeAround(AltoClef mod) {
        if (!AltoClef.inGame()) return 0;
        snapshot(mod);
        return _meleeAround;
    }

    // not the kind you stroll past: outruns a sprinting player, or is one of the oddballs (flyers, endermen, bosses, a lit
    // creeper, anything riding something) that the engage zone does not even apply to
    private static boolean isFast(Mob mob) {
        if (mob.isBaby() || mob instanceof Spider || mob instanceof Hoglin || mob instanceof Zoglin || mob instanceof PiglinBrute) return true;
        return mob.getAttributeValue(Attributes.MOVEMENT_SPEED) >= FAST_SPEED || MobReachability.isUngated(mob);
    }

    // is the user task the one walking us somewhere. latches what it was about to walk, because the moment mob defense
    // takes over the goal is gone and "was that zombie on my way" has no answer
    private boolean updateTravel(AltoClef mod, long now) {
        Task user = mod.getUserTaskChain().getCurrentTask();
        if (user != _travelUserTask) {
            _travel.clear();
            _travelUserTask = user;
        }
        boolean userDriving = mod.getTaskRunner().getCurrentTaskChain() == mod.getUserTaskChain()
                && mod.getClientBaritone().getPathingBehavior().getCurrent() != null;
        LocalPlayer player = mod.getPlayer();
        _travel.update(now, player.getX(), player.getZ(), userDriving);
        return _travel.travelling(now, player.getX(), player.getZ());
    }

    // the next ten or so spots of the path we are on, as absolute coordinates
    private static List<CombatPolicy.Point> upcomingPath(AltoClef mod) {
        PathExecutor current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (current == null) return List.of();
        var positions = current.getPath().positions();
        int from = Math.max(0, current.getPosition());
        int to = Math.min(positions.size(), from + PATH_LOOKAHEAD);
        List<CombatPolicy.Point> out = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            out.add(new CombatPolicy.Point(positions.get(i).x + 0.5, positions.get(i).y, positions.get(i).z + 0.5));
        }
        return out;
    }

    private List<CombatPolicy.Point> relativePath(LocalPlayer player) {
        List<CombatPolicy.Point> latched = _travel.upcoming();
        if (latched.isEmpty()) return latched;
        List<CombatPolicy.Point> out = new ArrayList<>(latched.size());
        for (CombatPolicy.Point p : latched) {
            out.add(new CombatPolicy.Point(p.x() - player.getX(), p.y() - player.getY(), p.z() - player.getZ()));
        }
        return out;
    }

    // the crowd the kite runs from: whoever is a problem right now, nearest first. shooters are not part of it, running
    // from a skeleton in a straight line is a free target, and they never made the crowd count anyway
    private List<Entity> getKiteThreats() {
        List<Entity> out = new ArrayList<>(_dealWith.size());
        for (Mob mob : _dealWith) {
            if (!MobReachability.isRanged(mob)) out.add(mob);
        }
        return out;
    }

    // one line per change of mind, not one per tick
    private void logVerdict(AltoClef mod, CombatPolicy.Decision decision, List<Mob> dealable) {
        String key = decision.verdict().name();
        if (decision.verdict() == CombatPolicy.Verdict.IGNORE) {
            if (decision.ignored().isEmpty()) {
                _lastVerdictLog = key;
                return;
            }
            key += "+passing";
        }
        // the reason is part of the change of mind: "kiting, no shield" turning into "kiting, low hp" is worth a line
        key += "/" + decision.why();
        if (key.equals(_lastVerdictLog)) return;
        _lastVerdictLog = key;
        // the numbers the call was made on, so a log reader can tell "ran at hp 17 with nothing on" from "ran at hp 6"
        String gear = " (hp " + Math.round(mod.getPlayer().getHealth()) + ", armor " + mod.getPlayer().getArmorValue()
                + ", " + (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD)
                ? "shield" : "no shield") + ")";
        String why = decision.why().isEmpty() ? "" : " (" + decision.why() + ")";
        switch (decision.verdict()) {
            case KITE -> Debug.logInternal("kiting " + describe(dealable, decision, false) + why + gear);
            case STAND -> Debug.logInternal("standing against " + describe(dealable, decision, false) + why + gear);
            case FIGHT_ONE -> Debug.logInternal("fighting " + describe(dealable, decision, false) + why + gear);
            case CHARGE -> Debug.logInternal("charging " + describeShooters(dealable, decision) + why + gear);
            case IGNORE -> Debug.logInternal("passing " + describe(dealable, decision, true) + ", " + decision.why() + gear);
        }
    }

    // "1 skeleton": just the shooters, they are who the charge is about
    private static String describeShooters(List<Mob> mobs, CombatPolicy.Decision decision) {
        List<Mob> shooters = new ArrayList<>();
        for (Mob mob : mobs) {
            if (MobReachability.isRanged(mob) && !isFast(mob)) shooters.add(mob);
        }
        return describe(shooters, decision, false);
    }

    // arrows, tridents, fireballs. the client only has a source once the damage event for it arrived, and forgets it after
    // two seconds, which is plenty: this is asked on the tick the hit shows up
    private static boolean hitByProjectile(LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        return source != null && source.is(DamageTypeTags.IS_PROJECTILE);
    }

    // "3 zombies, 1 skeleton"
    private static String describe(List<Mob> mobs, CombatPolicy.Decision decision, boolean ignored) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mob mob : mobs) {
            if (decision.ignored().contains(mob.getId()) == ignored) counts.merge(MobReachability.shortName(mob), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (out.length() > 0) out.append(", ");
            out.append(e.getValue()).append(' ').append(e.getKey()).append(e.getValue() == 1 ? "" : "s");
        }
        return out.length() == 0 ? "nothing" : out.toString();
    }

    public void setTargetEntity(Entity entity) {
        _targetEntity = entity;
    }

    public void resetTargetEntity() {
        _targetEntity = null;
        // the kill task running again and finding nothing to hit means the fight is over, not paused
        _parkedUntil = Long.MIN_VALUE / 2;
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

    @Override
    public String getHudName() {
        return "Defending";
    }
}