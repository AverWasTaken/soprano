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
    // what the run in _runAwayTask asked for when it started. the "keep running" fallback used to hand back whatever the
    // last tick returned, which for a finished run was 80 forever
    private float _runPriority;

    // everything below the line is worked out once per game tick by snapshot(), no matter how many things ask
    private long _snapshotTick = Long.MIN_VALUE;
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    private int _lastHurtTime;
    // far enough in the past that it is not a recent hit, close enough to the present that subtracting is not an overflow
    private long _lastCombatHurtTick = Long.MIN_VALUE / 2;
    // any damage at all, from anything. a stroll past a zombie stops being one when something bites
    private long _lastHurtTick = Long.MIN_VALUE / 2;
    // the crit timing and the weapon pick want how many melee mobs are on us / around us
    private int _meleeNear;
    private int _meleeAround;

    // the overworld brain (OverworldCombat). while _commitPath is on, the latches and the policy below sit still
    private final OverworldCombat _overworld = new OverworldCombat();
    private boolean _commitPath;

    private final CombatPolicy _policy = new CombatPolicy();
    private final CombatPolicy.TravelTracker _travel = new CombatPolicy.TravelTracker();
    private Task _travelUserTask;
    private CombatPolicy.Decision _decision = NO_POLICY;
    private boolean _lowHpLatched;
    // low (latched, or the stance says leave) with a problem still within LOW_HP_RANGE: nothing here hands the wheel back
    private boolean _holdWheel;
    // when the last let go got a line, so a user task that keeps the wheel for a while is one line and not one per tick
    private long _letGoLogTick = Long.MIN_VALUE / 2;
    // the user task is the one pathing right now (so baritone's path is its route and not a run's)
    private boolean _userDriving;
    // we are on a route and nobody who is after us can get to it: a run from them is just carrying on
    private boolean _routeOutrun;
    // the ones we are actually dealing with this tick (not the ones walking past), nearest first
    private List<Mob> _dealWith = List.of();
    private String _lastVerdictLog = "";

    // what it looks like when nobody is in charge: shield whenever it fits, nothing ignored, the old way
    private static final CombatPolicy.Decision NO_POLICY = new CombatPolicy.Decision(CombatPolicy.Verdict.STAND, Set.of(), 0, 0, false);
    // and what it looks like in the overworld, where the machine decides: nothing ignored by the policy, no kite, no shield
    private static final CombatPolicy.Decision OVERWORLD_DECISION = new CombatPolicy.Decision(CombatPolicy.Verdict.IGNORE, Set.of(), 0, 0, false);
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
    private static final long LET_GO_LOG_TICKS = 40;
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
        return getPriorityInner(mod);
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
        float priority = computePriority(mod);
        if (priority <= 0) return priority;
        Task held = getCurrentTask();
        if (CombatRules.wheelPriority(priority, held != null, held != null && held.isFinished(mod)) > 0) return priority;
        noteLetGo(mod, "the run was over before it started, nothing left to run from");
        // every branch that asks for the wheel just installed something to hold it with. if that something is gone or was
        // born finished (a run from a crowd we are already outside of), the wheel is not ours, and neither is the latch
        // that was about to keep it for 40 ticks. (shielding and the force field act without a task, and never needed the
        // wheel to do it)
        if (held != null) onTaskFinish(mod);
        _runAwayTask = null;
        _dangerRun = null;
        _runLatchUntil = Long.MIN_VALUE / 2;
        return 0;
    }

    private float computePriority(AltoClef mod) {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (!Baritone.settings().altoMobDefense.value) {
            return Float.NEGATIVE_INFINITY;
        }

        // before anything can stand down below: the commitment moves once per tick whether or not we get to hold the wheel,
        // otherwise a stretch of eating would be a stretch of the machine not knowing what happened
        snapshot(mod);

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

        // a commitment does not stand down for a meal. eating is async (the food chain holds the use key and picks the food,
        // it never needed the wheel), so the run walks on at chewing speed and the fight just stops swinging while it
        // chews. handing the wheel to the user task for the bite is a cycle at every crossing of the 8 block line, and the
        // user task walks its own route, often straight back at what we ran from
        boolean committed = _commitPath && _overworld.mode() != CombatCommit.Mode.NONE;
        if ((!committed && mod.getFoodChain().needsToEat()) || mod.getMLGBucketChain().isFallingOhNo(mod) ||
                !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()) {
            _killAura.stopShielding(mod);
            stopShielding(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // Force field
        doForceField(mod);

        // the overworld has one brain with one way in and one way out, none of the latches below run there
        if (_commitPath) return commitPriority(mod);

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
            if (runFrom(mod, now, new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true), 70)) return 70;
        }

        _doingFunkyStuff = false;
        PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
        Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
        // Run away from creepers
        Creeper blowingUp = getClosestFusingCreeper(mod);
        if (blowingUp != null) {
            float creeperPriority = creeperStep(mod, blowingUp, offhandItem);
            if (!Float.isNaN(creeperPriority)) return creeperPriority;
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
            // low and shot at: the dodge sidesteps one arrow and the skeleton lines up the next one, leaving is the answer. this
            // is the same run the flee gates below would start, it just gets asked before a dodge can cut it off (a dodge and a
            // flee taking turns every second is neither). nowhere to run still dodges
            if (!mod.getFoodChain().needsToEat() && !_routeOutrun && CombatPolicy.fleeBeatsDodge(_lowHpLatched, getCombatStance(mod))
                    && runFrom(mod, now, dangerRunTask(), 80)) {
                return 80;
            }
            if (!mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && isProjectileClose(mod)) {
                _doingFunkyStuff = true;
                //Debug.logMessage("DODGING");
                startRun(new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL), 65);
                return 65;
            }
        }
        // a danger run that is still inside its hold keeps the wheel, whatever the kill branch below would like
        if (_dangerRun != null && _dangerRun.isFinished(mod)) {
            _dangerRun = null;
        }
        if (isRunLatched(now) && runFrom(mod, now, _dangerRun, 70)) {
            return 70;
        }
        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod) && !escapeDragonBreath(mod) && !mod.getFoodChain().isShouldStop()) {
            if (!fightOn(now)) {
                // the route we were walking is clear of them, so the way out is the way we were going. the user task has
                // the sprint, running to a random spot 30 blocks off is how the furnace trip became a lap of the map
                if (_routeOutrun) return handBack(mod, "the route is clear of them");
                if (runFrom(mod, now, dangerRunTask(), 70)) return 70;
            }
        }

        // losing a fight is not the time to trade blows or to chew. leave, the food chain eats once we are out of it.
        // (the food chain only lets go of its bite when it sees this stance, see FoodChain.needsToEat)
        if (!fightOn(now) && getCombatStance(mod) == CombatRules.Stance.FLEE) {
            if (_routeOutrun) return handBack(mod, "the route is clear of them");
            if (runFrom(mod, now, dangerRunTask(), 80)) return 80;
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
                startRun(new RunAwayFromHostilesTask(KITE_DISTANCE, true, this::getKiteThreats), 80);
                _dangerRun = null;
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
                int canDealWith = CombatPolicy.standCapacity(armor, damage, hasShield, bestWeapon != null);
                // a crowd we could not run from (or were told to stand against) is fought whatever the gear says, running
                // blind with a pile of them on our heels is worse than the shield
                boolean crowd = _decision.swarm() >= Baritone.settings().altoSwarmThreshold.value || _decision.cornered();
                // the policy already called "one at a time" on a lone zombie, and the gear maths below used to overrule it
                // (a naked bot has a capacity of 1, one mob is not less than one) and run. the verdict said fight, the
                // branch said run, and the run is what got us stuck. gear is for the piles the policy did not rule on
                boolean policyFights = CombatPolicy.fightsLoneMelee(_decision.verdict(), toDealWith.size(), soloSlowMelee(toDealWith));
                if (canDealWith > numberOfProblematicEntities || crowd || policyFights) {
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
                    if (_routeOutrun) return handBack(mod, "the route is clear of them");
                    if (runFrom(mod, now, dangerRunTask(), 80)) return 80;
                }
            }
        }
        // a run from the crowd that has since become "the route is clear, keep going" is a run to a random spot we no
        // longer need. the latch above still gets its 40 ticks, this is for after
        if (_routeOutrun && _runAwayTask instanceof RunAwayFromHostilesTask) {
            return handBack(mod, "the run turned into the route carrying on");
        }
        // By default if we aren't "immediately" in danger but were running away, keep running away until we're good.
        // only if that run is really the one installed and still going: a finished one does not tick, so holding the wheel
        // for it is holding it for nobody
        if (_runAwayTask != null && _runAwayTask == getCurrentTask() && !_runAwayTask.isFinished(mod)) {
            return _runPriority;
        }
        _runAwayTask = null;
        _dangerRun = null;
        noteLetGo(mod, "nothing to run from or fight");
        return 0;
    }

    // a lit fuse: shield up if we have one and can stand still, otherwise step away. NaN means the shield took it and the
    // wheel is not claimed, a number is the priority the run asked for. the old brain and the overworld one share this
    private float creeperStep(AltoClef mod, Creeper blowingUp, Item offhandItem) {
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
            return Float.NaN;
        }
        _doingFunkyStuff = true;
        float creeperPriority = 50 + blowingUp.getSwelling(1) * 50;
        startRun(new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE), creeperPriority);
        return creeperPriority;
    }

    // the overworld wheel. mobs are scenery until the commitment says otherwise, so the only things that take it unasked are
    // a lit fuse close by (a real signal, short) and whatever the machine is holding. fire, lava and falls were settled before
    // we got here
    private float commitPriority(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        CombatCommit.Mode mode = _overworld.mode();
        float hold = _overworld.holdPriority();
        _doingFunkyStuff = false;
        Item offhandItem = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem();
        Creeper blowingUp = getClosestFusingCreeper(mod);
        boolean fuseNear = blowingUp != null && blowingUp.distanceTo(player) <= CombatPolicy.CREEPER_NO_IGNORE;
        if (fuseNear) {
            float creeperPriority = creeperStep(mod, blowingUp, offhandItem);
            // (a fight in progress keeps its wheel while it steps away, it is not a release)
            if (!Float.isNaN(creeperPriority)) return Math.max(creeperPriority, hold);
        } else if (!isProjectileClose(mod, false)) {
            stopShielding(mod);
        }

        // arrows: a shield that does not take the wheel. a fight walks on with it up, nothing else stops for an arrow, and a
        // run is feet's job
        boolean fighting = mode == CombatCommit.Mode.FIGHT;
        if (mode != CombatCommit.Mode.RUN && !mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && hasShield(mod)
                && isProjectileClose(mod, true) && !mod.getEntityTracker().entityFound(ThrownPotion.class)
                && !player.getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                && (fighting || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else {
                startShielding(mod, !fighting);
            }
        } else if (!fuseNear) {
            stopShielding(mod);
        }

        Task wheel = mode == CombatCommit.Mode.NONE ? null : _overworld.wheelTask(mod);
        if (wheel == null) {
            // nothing committed: whatever we were holding belonged to the commitment that just ended
            _runAwayTask = null;
            _dangerRun = null;
            if (_mainTask != null) onTaskFinish(mod);
            return 0;
        }
        _runAwayTask = null;
        _dangerRun = null;
        setTask(wheel);
        return hold;
    }

    // which brain is on. the old latches and the new commitment never run in the same tick, so crossing over starts clean
    private void switchBrain(AltoClef mod, boolean commit) {
        _commitPath = commit;
        _overworld.reset();
        // whatever we were holding was the other brain's
        if (_mainTask != null) onTaskFinish(mod);
        _runAwayTask = null;
        _dangerRun = null;
        _runLatchUntil = Long.MIN_VALUE / 2;
        _parkedUntil = Long.MIN_VALUE / 2;
        _targetEntity = null;
        _lowHpLatched = false;
        _holdWheel = false;
        _routeOutrun = false;
        _dealWith = List.of();
        _decision = NO_POLICY;
        _policy.idle();
    }

    // a lone zombie-ish thing: walks (so it is melee), does not outrun us, and there is exactly one of it
    private static boolean soloSlowMelee(List<Entity> dealWith) {
        return dealWith.size() == 1 && dealWith.get(0) instanceof Mob mob && !MobReachability.isRanged(mob)
                && !(mob instanceof Creeper) && !isFast(mob);
    }

    // setTask keeps the task it already has when the new one is "equal", so the bookkeeping has to follow whatever got
    // installed and not the object we just built and threw away (the finished-run bug was exactly that: a field pointing
    // at a task that was never going to tick)
    private void startRun(CustomBaritoneGoalTask run, float priority) {
        setTask(run);
        _runAwayTask = getCurrentTask() instanceof CustomBaritoneGoalTask current ? current : null;
        _runPriority = priority;
    }

    // start (or keep) a run away from danger. the same run asked for again is the same run, so the hold on it is only
    // started once. a different one is a new thing and gets its own
    // false when there is nothing to run from (everybody is already as far as the run would take us), then nothing is
    // installed and no latch starts
    private boolean runFrom(AltoClef mod, long now, RunAwayFromHostilesTask run, float priority) {
        if (_dangerRun != null && _dangerRun.equals(run)) {
            run = _dangerRun;
        } else {
            if (run.alreadySafe(mod)) return false;
            _dangerRun = run;
            _runLatchUntil = now + RUN_LATCH_TICKS;
        }
        startRun(run, priority);
        _dangerRun = _runAwayTask instanceof RunAwayFromHostilesTask installed ? installed : null;
        return true;
    }

    // the run is the user task carrying on: let go of whatever we were running with and give the wheel back
    private float handBack(AltoClef mod, String why) {
        noteLetGo(mod, why);
        _runAwayTask = null;
        _dangerRun = null;
        _runLatchUntil = Long.MIN_VALUE / 2;
        return 0;
    }

    // the wheel is going back to the user task while we are low and something is still around, which is the one time it
    // should not be. asked every tick the user task has the wheel, so it only speaks every LET_GO_LOG_TICKS
    private void noteLetGo(AltoClef mod, String why) {
        float hp = mod.getPlayer().getHealth();
        if (hp > CombatRules.FLEE_HEALTH || (!_lowHpLatched && _dealWith.isEmpty())) return;
        long now = mod.getWorld().getGameTime();
        if (now - _letGoLogTick < LET_GO_LOG_TICKS) return;
        _letGoLogTick = now;
        String nearest = _dealWith.isEmpty() ? "" : ", nearest " + MobReachability.shortName(_dealWith.get(0)) + " "
                + Math.round(_dealWith.get(0).distanceTo(mod.getPlayer())) + " blocks";
        Debug.logInternal("handing the wheel back at hp " + Math.round(hp) + ", " + why + " (" + _dealWith.size() + " to deal with"
                + nearest + (_lowHpLatched ? ", latched" : "") + (_holdWheel ? ", should be holding" : "") + ")");
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
        if (_commitPath) {
            // all feet and no hands on a run, the shield only when boxed in
            _killAura.setPolicy(_overworld.auraKiting(), _overworld.auraShield());
        } else {
            _killAura.setPolicy(_decision.kiting(), _decision.shield());
        }

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
                            // the overworld aura swings at the fight target and at what is hitting us in contact, nothing else
                            if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10) && (!_commitPath || _overworld.swingsAt(entity.getId()))) {
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
        return isProjectileClose(mod, true);
    }

    // react: stop the path and turn to face the shooter when one is about to land. the overworld only wants that with a
    // shield to put up, without one it is the user task standing still for no reason
    private boolean isProjectileClose(AltoClef mod, boolean react) {
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
                            if (react && !_decision.charging() && _runAwayTask == null && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
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
        boolean status = mod.getPlayer().hasEffect(MobEffects.WITHER) ||
                (mod.getPlayer().hasEffect(MobEffects.POISON) && witch.isEmpty());
        boolean policyOn = Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value;
        // who is a real problem: with the policy on that is the list it settled on (the ones it is walking past are not
        // on it), with it off the raw list like it always was
        // healthy and well dressed is never in danger from a count, so nobody pays for one
        boolean worthCounting = health <= LOW_HEALTH || CombatPolicy.vulnerable(mod.getPlayer().getArmorValue(), health);
        int[] counts = worthCounting ? countThreats(mod, policyOn) : new int[2];
        int melee = counts[0];
        int shooters = counts[1];
        // only the stand verdict needs the gear maths, so only it pays for the inventory scan
        boolean standing = policyOn && _decision.verdict() == CombatPolicy.Verdict.STAND && melee > 0;
        int capacity = standing ? standCapacity(mod, hasShield(mod)) : 0;
        // (one or two skeletons at hp 9 or 10 are a charge, the policy says so. the policy already checked we are above
        // the flee line, so hp 8 and under still gets here)
        return CombatPolicy.inDanger(new CombatPolicy.Danger(health, mod.getPlayer().getArmorValue(), hasFood, witch.isPresent(),
                _decision.charging(), status, policyOn, _decision, melee, shooters, capacity));
    }

    // {melee, shooters} close enough to matter
    private int[] countThreats(AltoClef mod, boolean policyOn) {
        int melee = 0;
        int shooters = 0;
        try {
            LocalPlayer player = mod.getPlayer();
            List<? extends Entity> hostiles = policyOn ? _dealWith : mod.getEntityTracker().getHostiles();
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (Entity entity : hostiles) {
                    if (entity.closerThan(player, SAFE_KEEP_DISTANCE) && !mod.getBehaviour().shouldExcludeFromForcefield(entity) && EntityHelper.isAngryAtPlayer(mod, entity)
                            && (!(entity instanceof Mob mob) || EntityHelper.canMobHarmPlayer(mod, mob))) {
                        // things that walk at us, and things that shoot at us: one skeleton is not a reason to run
                        if (entity instanceof Mob shooter && MobReachability.isRanged(shooter) && !isFast(shooter)) {
                            shooters++;
                        } else {
                            melee++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Weird multithread exception. Will fix later.");
        }
        return new int[]{melee, shooters};
    }

    private static boolean hasShield(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
    }

    // how many mobs the gear can take, see CombatPolicy.standCapacity. the weapon's damage is the tuning's "3 higher than
    // it used to be"
    private static int standCapacity(AltoClef mod, boolean shield) {
        Item weapon = AbstractKillEntityTask.bestWeapon(mod);
        float damage = weapon == null ? 0 : (ItemHelper.getAttackDamage(weapon) - 3);
        return CombatPolicy.standCapacity(mod.getPlayer().getArmorValue(), damage, shield, weapon != null);
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
        // an arrow in the back is not somebody walking up to us. it used to reset the walking-past grace for every zombie on
        // the hillside, so one skeleton made us stop and fight all of them. below the low line it counts as before, at that
        // health the whole thing is a different conversation. no damage source on the client (it is only filled in by the
        // damage event) reads as not an arrow, which is the old behaviour
        if (freshHit && (player.getHealth() <= LOW_HEALTH || !hitByProjectile(player))) _lastHurtTick = now;

        boolean policyOn = Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value;
        // the overworld has its own brain, one commitment instead of a stack of latches. nether and end keep the rest of
        // this method exactly as it was
        boolean commit = CombatCommit.applies(Baritone.settings().altoCommitCombat.value && policyOn && Baritone.settings().altoMobDefense.value,
                WorldHelper.getCurrentDimension());
        if (commit != _commitPath) switchBrain(mod, commit);
        if (commit) {
            commitSnapshot(mod, now);
            return;
        }
        boolean travelling = policyOn && updateTravel(mod, now);
        MobReachability reach = mod.getEntityTracker().getMobReachability();
        // running (the latch from last tick, or a danger run that is the live task): a mob that keeps pace with us is the one we
        // are running from, not one that is stuck. asked before the loop, the loop is where the verdicts are made. the field
        // alone is not enough, it only gets cleared when the chain gets its turn, and mob defense can be off or standing down
        boolean liveRun = _dangerRun != null && _dangerRun == getCurrentTask() && !_dangerRun.isFinished(mod);
        reach.setFleeing(Baritone.settings().altoMobDefense.value && (liveRun || (policyOn && _policy.lowHpLatched(now))));

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
                // (only while the user task is the one pathing, or baritone's path is the run's and not the route)
                if (travelling && _userDriving) _travel.setUpcoming(upcomingPath(mod));
                decision = _policy.decide(now, new CombatPolicy.Scene(policyMobs, now - _lastHurtTick, travelling,
                        relativePath(player), player.getX(), player.getZ(),
                        Math.max(1, Baritone.settings().altoSwarmThreshold.value),
                        grace, player.getHealth(), shield, AbstractKillEntityTask.bestWeapon(mod) != null));
            }
        }
        _decision = decision;
        // (one already on us is not outrun by walking on, it walks on with us and keeps swinging)
        boolean routeClear = policyOn && meleeNear == 0
                && (decision.outrunning() || (travelling && routeClearOfThem(player, policyMobs, decision)));
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
        boolean inCombat = CombatRules.inCombat(nearest, now - _lastCombatHurtTick, creeperClose) || decision.kiting() || decision.outrunning()
                || (_lowHpLatched && !Double.isInfinite(nearestAny));
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean hasGapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        _stance = CombatRules.stance(inCombat, player.getHealth(), nearest, hasGapple);
        // the quick bite at hp 4 is for "nothing next to us", and mob defense standing down for it hands the wheel to the
        // user task for as long as we chew. with the latch on something is still within 8, so feet first, bite later
        if (_lowHpLatched && _stance == CombatRules.Stance.EAT) _stance = CombatRules.Stance.FLEE;

        // low with a problem still close: the wheel stays here, the user task carrying on is not a way out at this hp. every
        // hand back that is about the route goes through _routeOutrun, so this is the one place it is switched off (worked
        // out last because it wants the stance)
        double nearestProblem = dealWith.isEmpty() ? Double.POSITIVE_INFINITY : dealWith.get(0).distanceTo(player);
        _holdWheel = CombatPolicy.holdsTheWheel(_lowHpLatched, _stance, nearestProblem);
        _routeOutrun = routeClear && !_holdWheel;
    }

    // the overworld picture: the machine moves, the stance and the crit counts follow from it, the old policy and its
    // latches are not asked anything (so none of them can run, or hold a stale answer for when we come back)
    private void commitSnapshot(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        Creeper fusing = getClosestFusingCreeper(mod);
        double fuseDistance = fusing == null ? Double.POSITIVE_INFINITY : fusing.distanceTo(player);
        // the old rule ran from wither, warden and company below 10 hp and so does this one, it is a run like any other
        boolean danger = player.getHealth() <= LOW_HEALTH && getUniversallyDangerousMob(mod).isPresent();
        _overworld.tick(mod, now, danger, fuseDistance <= CombatPolicy.CREEPER_NO_IGNORE, fuseDistance <= CombatRules.CREEPER_RANGE);
        _decision = OVERWORLD_DECISION;
        _stance = _overworld.stance();
        _meleeNear = _overworld.meleeNear();
        _meleeAround = _overworld.meleeAround();
        _lowHpLatched = false;
        _holdWheel = false;
        _routeOutrun = false;
        _dealWith = List.of();
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
        boolean userHasWheel = mod.getTaskRunner().getCurrentTaskChain() == mod.getUserTaskChain();
        boolean userDriving = userHasWheel && mod.getClientBaritone().getPathingBehavior().getCurrent() != null;
        _userDriving = userDriving;
        LocalPlayer player = mod.getPlayer();
        // not having the wheel is a hand-off, and the latch rides it out (see TravelTracker.travelling)
        _travel.update(now, player.getX(), player.getZ(), userDriving, !userHasWheel);
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

    // the mobs the policy is dealing with, none of whom can get to the route we were on. the danger and flee runs ask this
    // before running anywhere: if it is yes, the run is the user task carrying on
    private boolean routeClearOfThem(LocalPlayer player, List<CombatPolicy.Mob> mobs, CombatPolicy.Decision decision) {
        List<CombatPolicy.Mob> after = new ArrayList<>(mobs.size());
        for (CombatPolicy.Mob mob : mobs) {
            if (!decision.ignored().contains(mob.id())) after.add(mob);
        }
        return CombatPolicy.canOutrun(relativePath(player), after);
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
            // "running from" when it is feet with no say in where, the route case below is the same crowd and a clear way on
            case KITE -> Debug.logInternal("running from " + describe(dealable, decision, false) + why + gear);
            case STAND -> Debug.logInternal("standing against " + describe(dealable, decision, false) + why + gear);
            case FIGHT_ONE -> Debug.logInternal("fighting " + describe(dealable, decision, false) + why + gear);
            case CHARGE -> Debug.logInternal("charging " + describeShooters(dealable, decision) + why + gear);
            case IGNORE -> Debug.logInternal(decision.outrunning()
                    ? "outrunning " + describe(dealable, decision, true) + " toward " + routeEnd(mod) + gear
                    : "passing " + describe(dealable, decision, true) + ", " + decision.why() + gear);
        }
    }

    // where the route we are carrying on along ends, for the log: the user task's name and the last spot it was about to
    // walk to
    private String routeEnd(AltoClef mod) {
        List<CombatPolicy.Point> route = _travel.upcoming();
        Task user = mod.getUserTaskChain().getCurrentTask();
        String where = route.isEmpty() ? "" : " (" + Math.round(route.get(route.size() - 1).x()) + ", " + Math.round(route.get(route.size() - 1).z()) + ")";
        return (user == null ? "where we were going" : user.getHudName()) + where;
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
        // this used to be "i guess we move on?" which is the whole bug: the finished task stayed installed, never ticked,
        // and the priority it was started with kept the wheel for over a minute. let go of it properly
        Task done = _mainTask;
        _mainTask = null;
        if (done != null) done.stop(mod);
        if (_runAwayTask == done) _runAwayTask = null;
        if (_dangerRun == done) _dangerRun = null;
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