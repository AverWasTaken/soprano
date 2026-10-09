package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.CreeperStepTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.CreeperStep;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Dimension;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
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

import static java.lang.Math.abs;

// the mob defense chain. mobs are scenery until the commitment machine (CombatBrain, CombatCommit) says one really needs
// dealing with, in every dimension, and then the chain holds the wheel with a fight or a run until the machine lets go.
// nothing else here takes the wheel for a mob except a lit creeper within a few blocks (CreeperStep, a couple of steps and
// straight back). an unlit one is walked past like everything else. fire, falls and the arrow shield were never about the wheel
public class MobDefenseChain extends SingleTaskChain {
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;
    private static boolean _shielding = false;
    private final KillAura _killAura = new KillAura();
    private boolean _wasPuttingOutFire = false;
    // above a fight (65) and the food chain (55), under a run (80, which keeps its own number while it steps), lava and fire
    private static final float CREEPER_STEP_PRIORITY = 70;
    private final CreeperStep _creeperStep = new CreeperStep();
    // a new player or dimension is a new world, creeper ids from the old one mean nothing (same reset as CombatBrain's)
    private LocalPlayer _creeperPlayer;
    private Dimension _creeperDimension;

    // everything below is worked out once per game tick by snapshot(), no matter how many things ask
    private long _snapshotTick = Long.MIN_VALUE;
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    // the crit timing and the weapon pick want how many melee mobs are on us / around us
    private int _meleeNear;
    private int _meleeAround;

    private final CombatBrain _brain = new CombatBrain();

    // ---- for the gamer card: the commitment as it stands, plain field reads, nothing here moves the machine

    public CombatCommit.Mode combatMode() {
        return _brain.mode();
    }

    public String combatFightName() {
        return _brain.fightName();
    }

    public double combatRunOriginX() {
        return _brain.commit().originX();
    }

    public double combatRunOriginZ() {
        return _brain.commit().originZ();
    }

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    // squared distance, a lit one counts as five times closer. less is WORSE
    private static double getCreeperSafety(Vec3 pos, Creeper creeper) {
        double distance = creeper.position().distanceToSqr(pos);
        if (creeper.getSwelling(1) <= 0.001f) return distance;
        return distance * 0.2;
    }

    // standing still is the default. a fight raises the shield on the move: no sneak (it is walking at a third of the
    // speed), no pause, and the caller lowers it the moment the arrow is gone
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

    public float getPriorityInner(AltoClef mod) {
        float priority = computePriority(mod);
        if (priority <= 0) return priority;
        Task held = getCurrentTask();
        if (CombatRules.wheelPriority(priority, held != null, held != null && held.isFinished(mod)) > 0) return priority;
        // every branch that asks for the wheel just installed something to hold it with. if that something is gone or was
        // born finished (a run we are already outside of), the wheel is not ours. (shielding and the force field act without
        // a task, and never needed the wheel to do it)
        if (held != null) onTaskFinish(mod);
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
        // (a creeper step does not stand down for a meal either: the line says we stepped, so we step)
        boolean committed = _brain.mode() != CombatCommit.Mode.NONE || _creeperStep.active();
        if ((!committed && mod.getFoodChain().needsToEat()) || mod.getMLGBucketChain().isFallingOhNo(mod) ||
                !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()) {
            _killAura.stopShielding(mod);
            stopShielding(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // Force field
        doForceField(mod);

        return commitPriority(mod);
    }

    // the wheel. mobs are scenery until the commitment says otherwise, so what takes it is whatever the machine is holding,
    // and a lit creeper close enough to hurt. the old step away fired for any lit creeper within 7 and the bot spent half its
    // life backing off from creepers it could have walked past, this one is 4 and lit only. fire, lava and falls were
    // settled before we got here
    private float commitPriority(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        CombatCommit.Mode mode = _brain.mode();
        float hold = _brain.holdPriority();
        Item offhandItem = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem();

        // a lit creeper right next to us: a couple of steps out of the blast, or the shield when boxed in. not a commitment,
        // the machine never hears of it, and whatever it was holding (a fight, a run) gets the wheel back after the bang
        boolean creeperShield = false;
        float stepPriority = Float.NaN;
        if (_creeperStep.active()) {
            Entity creeper = mod.getWorld().getEntity(_creeperStep.creeperId());
            if (_creeperStep.cornered()) {
                if (creeper != null) LookHelper.lookAt(mod, creeper.getEyePosition());
                if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod, true);
                }
                creeperShield = true;
            } else {
                setTask(new CreeperStepTask(_creeperStep.creeperId()));
                stepPriority = Math.max(CREEPER_STEP_PRIORITY, hold);
            }
        }

        // arrows: a shield that does not take the wheel. a fight walks on with it up, nothing else stops for an arrow, and a
        // run is feet's job. (not mid creeper step either, the arrow shield pauses the path and that is the step gone)
        boolean fighting = mode == CombatCommit.Mode.FIGHT;
        if (creeperShield) {
            // the creeper's shield is up, leave it be
        } else if (!_creeperStep.active() && mode != CombatCommit.Mode.RUN && !mod.getFoodChain().needsToEat() && Baritone.settings().altoDodgeProjectiles.value && hasShield(mod)
                && isProjectileClose(mod) && !mod.getEntityTracker().entityFound(ThrownPotion.class)
                && !player.getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                && (fighting || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else {
                startShielding(mod, !fighting);
            }
        } else {
            stopShielding(mod);
        }
        if (!Float.isNaN(stepPriority)) return stepPriority;

        Task wheel = mode == CombatCommit.Mode.NONE ? null : _brain.wheelTask(mod);
        if (wheel == null) {
            // nothing committed: whatever we were holding belonged to the commitment that just ended
            if (_mainTask != null) onTaskFinish(mod);
            return 0;
        }
        setTask(wheel);
        return hold;
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
        // all feet and no hands on a run, the shield only when boxed in. the shield is for standing in a pile, not for the
        // first zombie that wanders up
        _killAura.setPolicy(_brain.auraKiting(), _brain.auraShield());

        // hit what is on us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        try {
            if (!entities.isEmpty()) {
                for (Entity entity : entities) {
                    boolean shouldForce = false;
                    if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                    if (entity instanceof Mob) {
                        if (EntityHelper.isGenerallyHostileToPlayer(mod, entity)) {
                            // the aura swings at the fight target and at what is hitting us in contact, nothing else. and at
                            // what a task kept out of mob defense without taking it off the aura (a blaze beside us in
                            // the rod task), that one is the aura's by the task's own say
                            if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10)
                                    && FoeRules.auraMaySwingAt(_brain.swingsAt(entity.getId()), mod.getBehaviour().shouldExcludeFromMobDefense(entity))) {
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

    // also stops the path and turns to face the shooter when one is about to land, so only ask with a shield to put up.
    // without one that is the user task standing still for no reason
    private boolean isProjectileClose(AltoClef mod) {
        List<CachedProjectile> projectiles = mod.getEntityTracker().getProjectiles();
        // a run keeps walking: stopping to turn round for a fireball is how the ghast gets a free volley on a bot that was
        // leaving. a fight keeps swinging at its target for the same reason, only an idle chain stops to face the shooter
        boolean committed = _brain.mode() != CombatCommit.Mode.NONE;
        try {
            if (!projectiles.isEmpty()) {
                for (CachedProjectile projectile : projectiles) {
                    if (projectile.position.distanceToSqr(mod.getPlayer().position()) < 150) {
                        boolean isGhastBall = projectile.projectileType == LargeFireball.class;
                        if (isGhastBall) {
                            Optional<Entity> ghastBall = mod.getEntityTracker().getClosestEntity(LargeFireball.class);
                            Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(Ghast.class);
                            if (ghastBall.isPresent() && ghast.isPresent() && !committed
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

                        double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                        double verticalDistance = abs(delta.y);
                        if (horizontalDistanceSq < ARROW_KEEP_DISTANCE_HORIZONTAL * ARROW_KEEP_DISTANCE_HORIZONTAL && verticalDistance < ARROW_KEEP_DISTANCE_VERTICAL) {
                            // (a fight keeps walking at its target: no pause, no turning round to face the shooter)
                            if (!committed
                                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
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

    private static boolean hasShield(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
    }

    // fight / flee / eat, worked out once per tick no matter how many things ask (the food chain asks a lot, needsToEat
    // is everywhere). the answer lives here because the hostile list and the reach rules do
    public CombatRules.Stance getCombatStance(AltoClef mod) {
        if (!AltoClef.inGame()) return CombatRules.Stance.CALM;
        snapshot(mod);
        return _stance;
    }

    // the whole picture, built once per game tick: the machine moves, and the stance and the crit counts follow from it
    private void snapshot(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now == _snapshotTick) return;
        _snapshotTick = now;

        LocalPlayer player = mod.getPlayer();
        Creeper fusing = getClosestFusingCreeper(mod);
        double fuseDistance = fusing == null ? Double.POSITIVE_INFINITY : fusing.distanceTo(player);
        // the off switch: no commitments, mobs are scenery. the safety stays (fire, falls, the arrow shield, the aura at what
        // is on us), and the foes are still counted for the stance and the crits
        boolean enabled = Baritone.settings().altoMobDefense.value && Baritone.settings().altoKillOrAvoidAnnoyingHostiles.value
                && Baritone.settings().altoCommitCombat.value;
        _brain.tick(mod, now, fuseDistance <= CombatRules.CREEPER_RANGE, enabled);
        stepCreepers(mod, now);
        _stance = _brain.stance();
        _meleeNear = _brain.meleeNear();
        _meleeAround = _brain.meleeAround();
    }

    // the creeper preempt moves once per tick too, wheel or not, so its 2.5 s clock is a real 2.5 s
    private void stepCreepers(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (player != _creeperPlayer || dimension != _creeperDimension) {
            _creeperStep.reset();
            _creeperPlayer = player;
            _creeperDimension = dimension;
        }
        List<CreeperStep.Seen> seen = new ArrayList<>();
        List<int[]> ahead = null;
        try {
            for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (!creeper.isAlive()) continue;
                double distance = creeper.distanceTo(player);
                if (distance > CreeperStep.PATH_RANGE) continue;
                // swell dir and ignited are both synced to the client, so the fuse counts from its very first tick
                boolean lit = creeper.getSwellDir() > 0 || creeper.isIgnited();
                boolean pathNear = false;
                if (lit && distance > CreeperStep.TRIGGER) {
                    if (ahead == null) ahead = pathAhead(mod);
                    pathNear = CreeperStep.pathPassesNear(creeper.getX(), creeper.getY(), creeper.getZ(), ahead);
                }
                seen.add(new CreeperStep.Seen(creeper.getId(), lit, distance, pathNear));
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // same weirdness as getClosestFusingCreeper. a tick with no creepers ends a step, the next tick starts it again
            seen.clear();
        }
        boolean shieldReady = hasShield(mod) && !mod.getEntityTracker().entityFound(ThrownPotion.class)
                && !player.getCooldowns().isOnCooldown(new ItemStack(StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem()));
        CreeperStep.Event event = _creeperStep.step(now, seen, shieldReady);
        if (event == CreeperStep.Event.START && _creeperStep.shouldLog()) {
            Debug.logInternal(CombatLog.creeperStep(dimension, _creeperStep.startedAt()));
        }
    }

    // the next few nodes of the path we are walking, as block corners. empty when nothing is pathing
    private static List<int[]> pathAhead(AltoClef mod) {
        List<int[]> nodes = new ArrayList<>();
        IPathExecutor current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (current == null) return nodes;
        IPath path = current.getPath();
        List<BetterBlockPos> positions = path.positions();
        int from = Math.max(0, current.getPosition());
        for (int i = from; i < positions.size() && i <= from + CreeperStep.PATH_LOOKAHEAD; i++) {
            BetterBlockPos p = positions.get(i);
            nodes.add(new int[]{p.x, p.y, p.z});
        }
        return nodes;
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

    public void setForceFieldRange(double range) {
        _killAura.setRange(range);
    }

    public void resetForceField() {
        _killAura.setRange(Double.POSITIVE_INFINITY);
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
