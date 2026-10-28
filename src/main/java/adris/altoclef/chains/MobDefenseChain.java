package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.CreeperStepTask;
import adris.altoclef.tasks.movement.HealUpTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.BiteRules;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.CreeperCap;
import adris.altoclef.util.helpers.CreeperEscape;
import adris.altoclef.util.helpers.CreeperStep;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.GameClock;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.NightSleepHook;
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
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.altoclef.AltoClefSettings;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

import static java.lang.Math.abs;

// the mob defense chain. mobs are scenery until the commitment machine (CombatBrain, CombatCommit) says one really needs
// dealing with, in every dimension, and then the chain holds the wheel with a fight or a run until the machine lets go.
// nothing else here takes the wheel for a mob except a creeper within a few blocks, lit or not (CreeperStep, a few steps and
// straight back). fire, falls and the arrow shield were never about the wheel
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
    // the block over our head (CreeperCap). when the step started, whether it is done with, and the creeper above us in a shaft
    private long _capSince = Long.MIN_VALUE;
    private boolean _capDone;
    // the cell over our head is solid (we put it there, or it always was)
    private boolean _capPlaced;
    private boolean _capAbove;
    // the wall beside us (CreeperCap.besideCell): the cell picked for this step, kept once picked so the block landing in it
    // does not make the wall look done-and-forgotten
    private BlockPos _capBeside;
    // CreeperEscape found nowhere to run, as of this tick's stepCreepers
    private boolean _creeperNoHeading;
    // sneak while clicking, same reason as PlaceStationTask: a chest or a table in the wall opens instead of taking the block
    // game time of the last tick the projectile shield was called for. the food chain asks projectileDodging() and keeps its hands off
    // the use key for it: its swap back to the food and release on the way out is what dropped the shield mid volley
    private long _dodgeTick = Long.MIN_VALUE;
    // ticks until the closest incoming projectile reaches us, as of the last isProjectileClose. a bite in progress only gives
    // way to the shield when this is short (a held shield swaps the food out of the hand, which throws the bite away)
    private double _impactTicks = Double.POSITIVE_INFINITY;
    // a close projectile of the last isProjectileClose was a ghast ball (the escape bridge keeps its shield down for those)
    private boolean _closeFireball;
    private boolean _capSneak;
    private final Object _capOwner = new Object();
    // anything that is not a block, for the main hand while the shield is up
    private static final Item[] SHIELD_HAND = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE,
            Items.WOODEN_PICKAXE, Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD,
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE,
            Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL};
    private int _capCreeper = -1;

    // everything below is worked out once per game tick by snapshot(), no matter how many things ask
    private long _snapshotTick = Long.MIN_VALUE;
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    // the crit timing and the weapon pick want how many melee mobs are on us / around us
    private int _meleeNear;
    private int _meleeAround;

    private final CombatBrain _brain = new CombatBrain();
    private final LavaKnockGuard _lavaGuard = new LavaKnockGuard();

    // ---- for the gamer card: the commitment as it stands, plain field reads, nothing here moves the machine

    public CombatCommit.Mode combatMode() {
        return _brain.mode();
    }

    // the heal-up hold is on (CombatCommit.recovering), a re-run from it included
    public boolean isRecovering() {
        return _brain.recovering();
    }

    // the wheel is the hold's stand still right now, not a re-run or a creeper step that came up in the middle of it
    public boolean isHealingUp() {
        return _brain.recovering() && _mainTask instanceof HealUpTask;
    }

    // game time the last combat run ended (far in the past for never)
    public long lastCombatRunEnd() {
        return _brain.lastRunEnd();
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
    // speed), no pause, and the caller lowers it the moment the arrow is gone.
    // the keys carry our name, same as KillAura's: the two shields come and go in the same tick, and a plain release from
    // one used to lift the other's (5 ticks before a raised shield blocks, all over again)
    private void startShielding(AltoClef mod, boolean standStill) {
        _shielding = true;
        if (standStill) {
            mod.getInputControls().hold(Input.SNEAK, this);
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT, this);
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
                            mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, ContainerInput.QUICK_MOVE);
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

    @Override
    protected void onStop(AltoClef mod) {
        _lavaGuard.release(mod);
        super.onStop(mod);
    }

    private void stopShielding(AltoClef mod) {
        if (_shielding) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (cursor.getItem().components().has(DataComponents.FOOD)) {
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                if (toMoveTo.isPresent()) {
                    Slot garbageSlot = toMoveTo.get();
                    mod.getSlotHandler().clickSlot(garbageSlot, 0, ContainerInput.PICKUP);
                }
            }
            // (only ours: the aura's shield may be the one up)
            mod.getInputControls().releaseOwnedBy(this);
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
            _lavaGuard.reset(mod);
            return Float.NEGATIVE_INFINITY;
        }

        if (!Baritone.settings().altoMobDefense.value) {
            // (nothing moves the machine while we are off, so a hold would read as on until the next respawn, and the night
            // trip asks it)
            _brain.dropHold();
            dropCapSneak(mod);
            _lavaGuard.release(mod);
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
        // (a creeper step does not stand down for a meal either: the line says we stepped, so we step. nor does the heal-up
        // hold: the meal is half of what it is holding for)
        boolean committed = _brain.mode() != CombatCommit.Mode.NONE || _creeperStep.active() || _brain.recovering();
        if ((!committed && mod.getFoodChain().needsToEat()) || mod.getMLGBucketChain().isFallingOhNo(mod) ||
                !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()) {
            _killAura.stopShielding(mod);
            stopShielding(mod);
            dropCapSneak(mod);
            _lavaGuard.release(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // up on a pillar with a bed (NightSleep): a zombie at the foot of it is the reason we are up here, not a fight, and a run
        // would walk us off the top. same stand down as a meal, minus the lit creeper (it can still blow the pillar up) and minus
        // low health, which hands the wheel back inside standsDown
        LocalPlayer self = mod.getPlayer();
        var hitBy = self.getLastDamageSource();
        // (the mob swung at us itself, an arrow on the pillar is not "still in reach of the ground")
        if (self.hurtTime > 0 && hitBy != null && hitBy.getEntity() instanceof Mob && hitBy.getDirectEntity() == hitBy.getEntity()) {
            NightSleepHook.noteMeleeHit(mod.getWorld().getGameTime());
        }
        if (!_creeperStep.active() && NightSleepHook.standsDown(self.getHealth(), mod.getWorld().getGameTime())) {
            _killAura.stopShielding(mod);
            stopShielding(mod);
            _lavaGuard.release(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // Force field
        doForceField(mod);
        _lavaGuard.tick(mod, _brain);

        return commitPriority(mod);
    }

    // the wheel. mobs are scenery until the commitment says otherwise, so what takes it is whatever the machine is holding,
    // and a creeper close enough to hurt. the old step away fired for any lit creeper within 7 and the bot spent half its
    // life backing off from creepers it could have walked past, this one is 4 and a creeper that close is not walked past. fire, lava and falls were
    // settled before we got here
    private float commitPriority(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        CombatCommit.Mode mode = _brain.mode();
        float hold = _brain.holdPriority();
        Item offhandItem = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem();

        // a creeper right next to us, lit or not: a few steps out of the blast, or the shield when boxed in. not a commitment,
        // the machine only hears of one that keeps coming back, and whatever it was holding (a fight, a run) gets the wheel
        // back after the bang
        boolean creeperShield = false;
        float stepPriority = Float.NaN;
        if (_creeperStep.active()) {
            Entity creeper = mod.getWorld().getEntity(_creeperStep.creeperId());
            // a block over our head first, when the creeper is up the shaft with us. it takes the wheel (nothing else may swap
            // the pickaxe back in under the click), and gives up to the shield once its share of the fuse is gone or a click
            // is refused
            if ((_capAbove || _capBeside != null) && !_capDone
                    && CreeperCap.inWindow(GameClock.since(_capSince, mod.getWorld().getGameTime())) && placeCap(mod)) {
                setTask(new IdleTask());
                _killAura.stopShielding(mod);
                stopShielding(mod);
                return Math.max(CREEPER_STEP_PRIORITY, hold);
            }
            dropCapSneak(mod);
            // capped: the wheel stays ours and goes nowhere. the step's own task would walk
            // straight through the block we just put up
            // (a wall beside us is no door in the way: with a way out open the step runs, it does not sit behind a block)
            boolean capped = _capPlaced && (_capBeside == null || _creeperNoHeading);
            if (_creeperStep.cornered()) {
                if (creeper != null) LookHelper.lookAt(mod, creeper.getEyePosition());
                if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    // a block in the main hand eats the right click: the placement fails on our own body and vanilla never
                    // tries the off hand after that
                    if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() instanceof BlockItem) {
                        mod.getSlotHandler().forceEquipItem(SHIELD_HAND, true);
                    }
                    startShielding(mod, true);
                }
                creeperShield = true;
                if (capped) {
                    setTask(new IdleTask());
                    stepPriority = Math.max(CREEPER_STEP_PRIORITY, hold);
                }
            } else if (capped) {
                setTask(new IdleTask());
                mod.getClientBaritone().getPathingBehavior().requestPause();
                stepPriority = Math.max(CREEPER_STEP_PRIORITY, hold);
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
        } else if (!_creeperStep.active() && mode != CombatCommit.Mode.RUN && Baritone.settings().altoDodgeProjectiles.value && hasShield(mod)
                && isProjectileClose(mod) && !(_closeFireball && mod.getBehaviour().noFireballShield())
                && BiteRules.shieldMayStart(mod.getFoodChain().biting(), _impactTicks)
                && !mod.getEntityTracker().entityFound(ThrownSplashPotion.class, ThrownLingeringPotion.class)
                && !player.getCooldowns().isOnCooldown(new ItemStack(offhandItem))
                && (fighting || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            _dodgeTick = mod.getWorld().getGameTime();
            if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            } else {
                startShielding(mod, !fighting);
            }
        } else {
            stopShielding(mod);
        }
        if (!Float.isNaN(stepPriority)) return stepPriority;

        // (with nothing committed this is the heal-up hold's stand still, or nothing)
        Task wheel = _brain.wheelTask(mod);
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
        // all feet and no hands on a run, the shield only in a committed melee fight or when boxed in. a committed fight keeps the aura going under the flee
        // line and raises the shield mid move, the machine already decided this is a fight
        _killAura.setPolicy(_brain.auraKiting(), _brain.auraShield(), _brain.auraFighting(), _brain.fightTarget());
        _killAura.setRimGuard(_brain.auraRimGuard());

        // hit what is on us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        int fightId = _brain.fightTarget();
        boolean fightApplied = false;
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
                        if (entity.getId() == fightId) fightApplied = true;
                    }
                }
            }
            // the fight target is the aura's for the whole fight. the close list ends at 4 blocks (our own swing knocks it out
            // that far) and the hostile test drops a cube past 3 or an enderman between hits, and every tick it is missing the
            // aura has nothing to shield against: down comes the shield and its 5 tick raise starts over, right as it walks back in.
            // (still only one with a line to us: behind a wall the shield would pause the walk round to it, for nothing)
            if (fightId != -1 && !fightApplied && mod.getWorld().getEntity(fightId) instanceof Mob target && target.isAlive()
                    && !mod.getBehaviour().shouldExcludeFromForcefield(target) && LookHelper.seesPlayer(target, mod.getPlayer(), 10)) {
                applyForceField(target);
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
        boolean close = false;
        _closeFireball = false;
        _impactTicks = Double.POSITIVE_INFINITY;
        try {
            if (!projectiles.isEmpty()) {
                for (CachedProjectile projectile : projectiles) {
                    if (projectile.position.distanceToSqr(mod.getPlayer().position()) < 150) {
                        if (projectile.projectileType == DragonFireball.class) {
                            // dragon fireballs are the dragon fight's problem, not the shield's. (continue, not return: the
                            // rest of the list still has arrows in it)
                            continue;
                        }
                        // (a ghast ball goes through the same closest approach as the rest: the shield is the answer to it, and
                        // the pause and the turn to face it below only happen for one that is actually coming at us)
                        if (projectile.projectileType == Arrow.class || projectile.projectileType == SpectralArrow.class
                                || projectile.projectileType == SmallFireball.class || projectile.projectileType == LargeFireball.class) {
                            // check if the velocity of the projectile is going away from us
                            // oh no fancy math
                            Vec3 velocity = projectile.velocity;
                            Vec3 delta = mod.getPlayer().position().subtract(projectile.position);
                            // the dot product scales with the fireball's speed and our distance, so 0.25 of slack can throw out one
                            // that is coming straight at us. only a real "away" counts for those
                            double epsilon = projectile.projectileType == Arrow.class || projectile.projectileType == SpectralArrow.class ? 0.25 : 0;
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
                            // every close one counts for the impact clock, so a far arrow listed first does not hide a near fireball
                            _impactTicks = Math.min(_impactTicks, ProjectileHelper.ticksToClosest(projectile.position, projectile.velocity, mod.getPlayer().position()));
                            _closeFireball |= projectile.projectileType == LargeFireball.class;
                            if (close) continue;
                            close = true;
                            // (a fight keeps walking at its target: no pause, no turning round to face the shooter)
                            if (!committed
                                    && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                                mod.getClientBaritone().getPathingBehavior().requestPause();
                                LookHelper.lookAt(mod, projectile.position);
                            }
                        }
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
        }
        return close;
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
                && Baritone.settings().altoCommitCombat.value
                // (the night trip's stand down: nothing gets committed to up there, and one already held is let go of)
                && !NightSleepHook.standsDown(player.getHealth(), mod.getWorld().getGameTime());
        // the step goes first so a creeper that came back is a run on this same tick, not one tick of nobody at the wheel
        // (it reads the mode the machine left last tick, which is the one that had the wheel anyway)
        stepCreepers(mod, now, enabled);
        _brain.tick(mod, now, fuseDistance <= CombatRules.CREEPER_RANGE, enabled, _creeperStep.escalate());
        // a jump that lands short next to a mob has nobody to catch it, so while we're committed or sidestepping a creeper the
        // planner gets no jumps. a two tick stamp and not a flag, so it lapses on its own if this stops running
        if (_brain.mode() != CombatCommit.Mode.NONE || _creeperStep.active()) {
            AltoClefSettings.getInstance().noJumpsFor(now);
        }
        _stance = _brain.stance();
        _meleeNear = _brain.meleeNear();
        _meleeAround = _brain.meleeAround();
    }

    // the sneak the cap click holds. every way out of the step (and every stand down) has to let go of it
    private void dropCapSneak(AltoClef mod) {
        if (_capSneak) {
            _capSneak = false;
            mod.getInputControls().releaseOwnedBy(_capOwner);
        }
    }

    // the projectile shield was wanted this tick or last. the food chain reads this before mob defense has run for the tick, so last
    // tick's answer counts, and a stamp from before a world change (the clock went backwards) does not
    public boolean projectileDodging(AltoClef mod) {
        return Baritone.settings().altoMobDefense.value && BiteRules.dodgeHolds(mod.getWorld().getGameTime(), _dodgeTick);
    }
    // a creeper step is running: the crit module keeps its hands off the jump and the sprint until it is over
    public boolean creeperStepActive() {
        return Baritone.settings().altoMobDefense.value && _creeperStep.active();
    }

    // boxed in by a lit creeper: the shield is up or going up, or a block is going over our head. the food chain keeps its hands
    // off the use key for this (its release on the way out drops a raised shield, and a bite is no answer to a blast)
    public boolean creeperBoxedIn() {
        // (the snapshot does not run with mob defense off, so a step frozen mid way must not hold the food chain hostage)
        return Baritone.settings().altoMobDefense.value && _creeperStep.active()
                && (_creeperStep.cornered() || ((_capAbove || _capBeside != null) && !_capDone));
    }

    // one tick of putting a block in the cell over our head, clicked against the shaft wall beside it (or the roof). true =
    // still at it (equipping, aiming, or just clicked and waiting to see it land), false = nothing more to do here: it is there,
    // there is nothing to place or nothing to click, or the click was refused. same click as PlaceStationTask.aim
    private boolean placeCap(AltoClef mod) {
        if (_capBeside != null) return placeCell(mod, _capBeside, BESIDE_SUPPORT);
        LocalPlayer player = mod.getPlayer();
        // the cell over the top of the head, not feet + 2: standing on a slab the body reaches into that one
        return placeCell(mod, BlockPos.containing(player.getX(), player.getBoundingBox().maxY, player.getZ()).above(), CAP_SUPPORT);
    }

    // the cap clicks the shaft walls and then the roof. the wall beside us goes on the floor first: in a one wide tunnel the
    // side walls are grazing clicks that never pass the ray check, and the floor's top face one block ahead is in plain view
    private static final Direction[] CAP_SUPPORT = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final Direction[] BESIDE_SUPPORT = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private boolean placeCell(AltoClef mod, BlockPos cell, Direction[] supportOrder) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        if (!level.getBlockState(cell).canBeReplaced()) {
            _capDone = true;
            _capPlaced = true;
            return false;
        }
        Item[] blocks = capBlocks(mod);
        if (blocks.length == 0) {
            _capDone = true;
            return false;
        }
        // which neighbour of the cell has a sturdy, dry face to click: the four shaft walls, then the roof
        Direction clickFace = null;
        BlockPos support = null;
        for (Direction d : supportOrder) {
            BlockPos n = cell.relative(d);
            Direction face = d.getOpposite();
            if (level.getFluidState(n).isEmpty() && level.getBlockState(n).isFaceSturdy(level, n, face)) {
                support = n;
                clickFace = face;
                break;
            }
        }
        if (support == null) {
            _capDone = true;
            return false;
        }
        // sneak goes down a tick before the click, the equip tick is that tick
        _capSneak = true;
        mod.getInputControls().hold(Input.SNEAK, _capOwner);
        if (!StorageHelper.isEquipped(mod, blocks)) {
            mod.getSlotHandler().forceEquipItem(blocks, true);
            return true;
        }
        Vec3 face = Vec3.atCenterOf(support).add(clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, face);
        // our own ray along the look we just set, the crosshair is a frame behind it
        HitResult hit = RayTraceUtils.rayTraceTowards(player, LookHelper.getLookRotation(player), 4.5);
        if (!(hit instanceof BlockHitResult bhit) || !bhit.getBlockPos().equals(support) || bhit.getDirection() != clickFace) {
            return true;
        }
        InteractionResult result = Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhit);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            // the block shows up next tick, and then the cell is not replaceable and we are done
            return true;
        }
        _capDone = true;
        return false;
    }

    // the first block in the bag that is ours to throw away and stays where it is put. sand and gravel are on the throwaway
    // list and would land on our head
    private static Item[] capBlocks(AltoClef mod) {
        for (Item item : AltoSettings.getThrowawayItems(mod, true)) {
            if (item instanceof BlockItem block && !(block.getBlock() instanceof FallingBlock) && mod.getItemStorage().hasItem(item)) {
                return new Item[]{item};
            }
        }
        return new Item[0];
    }

    // the creeper preempt moves once per tick too, wheel or not, so its 2.5 s clock is a real 2.5 s
    private void stepCreepers(AltoClef mod, long now, boolean enabled) {
        LocalPlayer player = mod.getPlayer();
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (player != _creeperPlayer || dimension != _creeperDimension) {
            _creeperStep.reset();
            _creeperPlayer = player;
            _creeperDimension = dimension;
        }
        CombatCommit.Mode mode = _brain.mode();
        boolean running = mode == CombatCommit.Mode.RUN;
        List<CreeperStep.Seen> seen = new ArrayList<>();
        List<int[]> ahead = null;
        try {
            for (Creeper creeper : mod.getEntityTracker().getTrackedEntities(Creeper.class)) {
                if (!creeper.isAlive()) continue;
                double distance = creeper.distanceTo(player);
                // the one we hold stays in the list further out, or it reads as gone the moment the step gets us to 8
                if (distance > CreeperStep.PATH_RANGE && !(_creeperStep.active() && creeper.getId() == _creeperStep.creeperId()
                        && distance <= CreeperStep.PATH_RANGE + 4)) continue;
                // swell dir and ignited are both synced to the client, so the fuse counts from its very first tick
                boolean lit = creeper.getSwellDir() > 0 || creeper.isIgnited();
                boolean pathNear = false;
                // a run only steps for its own path walking into one, close or not (see CreeperStep.step)
                if (lit && (running || distance > CreeperStep.TRIGGER)) {
                    if (ahead == null) ahead = pathAhead(mod, running);
                    pathNear = CreeperStep.pathPassesNear(creeper.getX(), creeper.getY(), creeper.getZ(), ahead);
                }
                // swell progress above zero = the fuse still has charge, even when it is deflating (swell dir -1)
                seen.add(new CreeperStep.Seen(creeper.getId(), lit, distance, pathNear, lit || creeper.getSwelling(1) > 0.001f,
                        CreeperStep.inView(creeper.getY() - player.getY(), creeper.hasLineOfSight(player))));
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // same weirdness as getClosestFusingCreeper. a tick with no creepers ends a step, the next tick starts it again
            seen.clear();
        }
        boolean shieldReady = hasShield(mod) && !mod.getEntityTracker().entityFound(ThrownSplashPotion.class, ThrownLingeringPotion.class)
                && !player.getCooldowns().isOnCooldown(new ItemStack(StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem()));
        boolean noHeading = noWayOut(mod, seen);
        CreeperStep.Event event = _creeperStep.step(now, seen, shieldReady, mode, enabled, noHeading);
        // (a step that starts boxed in comes back as CORNERED, it is still the start of the step)
        if ((event == CreeperStep.Event.START || event == CreeperStep.Event.CORNERED) && _creeperStep.shouldLog()) {
            Debug.logInternal(CombatLog.creeperStep(dimension, _creeperStep.startedAt()));
        }
        // the cap's clock runs from the step's first tick, and a creeper that is gone resets it
        if (!_creeperStep.active() || _capCreeper != _creeperStep.creeperId()) {
            _capSince = Long.MIN_VALUE;
            _capDone = false;
            _capPlaced = false;
            _capBeside = null;
            _capCreeper = _creeperStep.active() ? _creeperStep.creeperId() : -1;
            dropCapSneak(mod);
        }
        _capAbove = false;
        if (_creeperStep.active() && noHeading) {
            Entity held = mod.getWorld().getEntity(_creeperStep.creeperId());
            _capAbove = held != null && CreeperCap.creeperAbove(true, held.getY() - player.getY(),
                    Math.hypot(held.getX() - player.getX(), held.getZ() - player.getZ()));
        }
        _creeperNoHeading = noHeading;
        // a way out opened before the block went up: nobody needs the wall
        if (!noHeading && !_capPlaced) _capBeside = null;
        // no way out and the creeper down the tunnel with us: a wall beside us, picked once per step
        if (_creeperStep.active() && noHeading && !_capAbove && _capBeside == null && !_capDone) {
            Entity held = mod.getWorld().getEntity(_creeperStep.creeperId());
            _capBeside = held == null ? null : besideTarget(mod, held);
        }
        // the cap's share of the fuse counts from the first tick there is something to cap (we may have been in the air
        // for the start of the step, and then there was no answer to give)
        if ((_capAbove || _capBeside != null) && _capSince == Long.MIN_VALUE) _capSince = now;
        // out of time: the shield has it, and the food chain gets its hands back if there is no shield
        if (_capSince != Long.MIN_VALUE && !CreeperCap.inWindow(GameClock.since(_capSince, now))) _capDone = true;
    }

    // the cell for the wall beside us, or null when there is no point: only a cell a block can still go in, with nobody
    // standing in it. whether there is anything to click on is placeCell's problem
    private BlockPos besideTarget(AltoClef mod, Entity creeper) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        int[] off = CreeperCap.besideCell(true, creeper.getX() - player.getX(), player.getY(), creeper.getY(),
                creeper.getZ() - player.getZ());
        if (off == null) return null;
        BlockPos cell = player.blockPosition().offset(off[0], off[1], off[2]);
        if (!level.getBlockState(cell).canBeReplaced()
                || !level.isUnobstructed(Blocks.STONE.defaultBlockState(), cell, CollisionContext.of(player))) {
            return null;
        }
        return cell;
    }

    // the same question CreeperStepTask asks before it sprints: is there anywhere to run. asked about the creeper the step
    // holds, or the nearest one inside TRIGGER when there is no step yet, with every other lit or close one counted in the
    // heading. airborne is nobody's answer (the task keeps what it was doing there too)
    private boolean noWayOut(AltoClef mod, List<CreeperStep.Seen> seen) {
        LocalPlayer player = mod.getPlayer();
        if (!player.onGround() && !player.isInWater()) return false;
        int id = -1;
        double best = Double.POSITIVE_INFINITY;
        for (CreeperStep.Seen s : seen) {
            boolean ask = _creeperStep.active() ? s.id() == _creeperStep.creeperId()
                    : s.distance() <= CreeperStep.TRIGGER && CreeperStep.triggers(s) && s.distance() < best;
            if (ask) {
                id = s.id();
                best = s.distance();
            }
        }
        if (id < 0) return false;
        Entity creeper = mod.getWorld().getEntity(id);
        if (creeper == null) return false;
        return CreeperEscape.choose(player.getX(), player.getY(), player.getZ(), CreeperStepTask.threats(mod, creeper),
                CreeperStepTask.cells(mod.getWorld())) == null;
    }

    // the next few nodes of the path we are walking, as block corners. empty when nothing is pathing. skipCurrent leaves out
    // the node we are standing on: for a run that only asks where it is going, and we stand next to the creeper either way
    private static List<int[]> pathAhead(AltoClef mod, boolean skipCurrent) {
        List<int[]> nodes = new ArrayList<>();
        IPathExecutor current = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (current == null) return nodes;
        IPath path = current.getPath();
        List<BetterBlockPos> positions = path.positions();
        int from = Math.max(0, current.getPosition()) + (skipCurrent ? 1 : 0);
        for (int i = from; i < positions.size() && i <= from + CreeperStep.PATH_LOOKAHEAD; i++) {
            BetterBlockPos p = positions.get(i);
            nodes.add(new int[]{p.x, p.y, p.z});
        }
        return nodes;
    }

    // a shooter has a line on us right now (the heal meal does not sit down in front of one)
    public boolean isShotAt(AltoClef mod) {
        if (!AltoClef.inGame()) return false;
        snapshot(mod);
        return _brain.shotAt();
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
