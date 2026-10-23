package adris.altoclef.tasks.entity;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.CritTiming;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.ApproachRules;
import adris.altoclef.util.helpers.EntityHelper;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import adris.altoclef.util.helpers.BackOffRules;
import adris.altoclef.util.helpers.EntityBlockerRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StandCap;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.helpers.LavaShore;
import adris.altoclef.util.helpers.MobReachability;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.utils.Dimension;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cells;
import baritone.behavior.CombatAim;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Interacts with an entity while maintaining distance.
 * <p>
 * The interaction is abstract.
 */
public abstract class AbstractDoToEntityTask extends Task implements ITaskRequiresGrounded {
    protected final MovementProgressChecker _progress = new MovementProgressChecker();
    private final double _maintainDistance;
    private final double _combatGuardLowerRange;
    private final double _combatGuardLowerFieldRadius;

    // ten seconds to chew through one block. a dig that takes longer than this is not going to get better
    private static final int BLOCKER_TICKS = 200;

    // kept while the target and distance stay the same, so its own stuck handling gets to accumulate across ticks
    private GetToEntityTask _approach;
    private Entity _approachEntity;
    private double _approachDistance;
    // the block we are digging out of the way (fence, wall, whatever won the raycast), and how many digs this target has cost
    private DestroyBlockTask _blocker;
    private int _blockerTicks;
    private Entity _blockerTarget;
    private int _blockerDigs;
    private GoalRunAway _backOffGoal;
    // the approach child was in the tree last tick (it is walking, a stop now would cut the path), see ApproachRules
    private boolean _approachLive;
    // the wander we handed back with nothing to target was the child last tick, see ApproachRules.wanderWhenNoTarget
    private boolean _wanderLive;
    // ticks in a row spent standing in reach with the swing gated, see ApproachRules.WAIT_TICKS. the kill tasks use _standCap
    // instead, which counts ticks since the last swing that really went out whatever the plan flickered through
    private int _waitTicks;
    private final StandCap _standCap = new StandCap();
    private Entity _waitEntity;
    private long _swingsSeen;
    // the back-off we started has to be getting somewhere, and a stuck one is not started again for a while
    private final BackOffRules.Watch _backOffWatch = new BackOffRules.Watch();
    private long _backOffMutedUntil = Long.MIN_VALUE;

    public AbstractDoToEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        _maintainDistance = maintainDistance;
        _combatGuardLowerRange = combatGuardLowerRange;
        _combatGuardLowerFieldRadius = combatGuardLowerFieldRadius;
    }

    public AbstractDoToEntityTask(double maintainDistance) {
        this(maintainDistance, 0, Double.POSITIVE_INFINITY);
    }

    public AbstractDoToEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        this(-1, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progress.reset();
        _approach = null;
        _approachLive = false;
        _wanderLive = false;
        _waitTicks = 0;
        _standCap.reset();
        _waitEntity = null;
        _swingsSeen = mod.getControllerExtras().swings();
        _backOffWatch.clear();
        _backOffMutedUntil = Long.MIN_VALUE;
        _blocker = null;
        _blockerTarget = null;
        _blockerDigs = 0;
        _backOffGoal = null;
        _unaimedTicks = 0;
        _unaimedEntity = null;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canDropStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        } // Kinda duct tape but it should be future proof ish
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progress.reset();
        }
        boolean wasWalking = _approachLive;
        _approachLive = false;
        boolean wasWandering = _wanderLive;
        _wanderLive = false;

        Optional<Entity> checkEntity = getEntityTarget(mod);
        if (checkEntity.isEmpty()) _blocker = null;

        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetForceField();
        }
        if (checkEntity.isPresent()) {
            Entity entity = checkEntity.get();

            double playerReach = Baritone.settings().altoEntityReachRange.value;

            // TODO: This is basically useless.
            EntityHitResult result = LookHelper.raycast(mod.getPlayer(), entity, playerReach);

            double sqDist = entity.distanceToSqr(mod.getPlayer());

            if (sqDist < _combatGuardLowerRange * _combatGuardLowerRange) {
                mod.getMobDefenseChain().setForceFieldRange(_combatGuardLowerFieldRadius);
            } else {
                mod.getMobDefenseChain().resetForceField();
            }

            // If we don't specify a maintain distance, default to within 1 block of our reach.
            double maintainDistance = _maintainDistance >= 0 ? _maintainDistance : playerReach - 1;

            long now = mod.getWorld().getGameTime();
            // a kill task with nowhere to step back to (a shaft, a low tunnel), or whose last step back went nowhere, stands
            // and hits: the run-away would only own the keys, and "too close" is not a problem we can fix there
            boolean backOffOk = !isKillTask() || (now >= _backOffMutedUntil && mod.getControllerExtras().roomToBackOff());
            // a kill task only minds being right on top of the mob, see ReachRules for how its distances nest
            double backOffLine = isKillTask() ? ReachRules.BACK_OFF_START : maintainDistance;
            boolean tooClose = backOffOk && sqDist < backOffLine * backOffLine;
            if (isKillTask()) {
                watchBackOff(mod, now);
                killBackOff(mod, entity, tooClose);
            } else if (tooClose && !mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                //setDebugState("Maintaining distance");
                _backOffGoal = new GoalRunAway(maintainDistance, entity.blockPosition());
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_backOffGoal);
            }

            boolean ready = !mod.getFoodChain().needsToEat() &&
                    !mod.getMLGBucketChain().isFallingOhNo(mod) && mod.getMLGBucketChain().doneMLG() &&
                    !mod.getMLGBucketChain().isChorusFruiting() &&
                    (mod.getPlayer().onGround() || mod.getControllerExtras().critInFlight());
            // a child that was walking to some other mob is not the one to keep
            boolean inReach = inReach(mod, entity);
            boolean shot = result != null && result.getType() == HitResult.Type.ENTITY;
            if (isKillTask()) {
                shot = shot && !aimStalled(mod, entity, inReach && shot);
                countStandingTicks(mod, entity, inReach && shot);
            }
            boolean gatesOpen = !mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFallingOhNo(mod)
                    && mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting();
            ApproachRules.Policy policy = !isKillTask() ? ApproachRules.Policy.OTHER
                    : gatesOpen ? ApproachRules.Policy.KILL : ApproachRules.Policy.KILL_GATED;
            ApproachRules.Plan plan = ApproachRules.plan(wasWalking && _approachEntity == entity, inReach, shot, ready,
                    mod.getClientBaritone().getPathingBehavior().isSafeToCancel(), tooClose,
                    isKillTask() ? _standCap.waitedLong() : _waitTicks >= ApproachRules.WAIT_TICKS, policy);
            if (!isKillTask()) {
                _waitTicks = plan == ApproachRules.Plan.WAIT ? _waitTicks + 1 : 0;
            }
            switch (plan) {
                case INTERACT:
                    _progress.reset();
                    _blocker = null;
                    return onEntityInteract(mod, entity);
                case APPROACH:
                    // lava on the foe, under us or between us (just the foe, for an animal): it comes to us. the walk in is what put us in the lava, and a
                    // foe that never comes just stalls out. returning null also lets go of an approach already walking
                    if (isKillTask() && lavaHold(mod, entity)) {
                        _progress.reset();
                        _blocker = null;
                        setDebugState("Lava by the foe, letting it come");
                        return null;
                    }
                    // a kill task walks in to hop range, not to the edge of reach: the swing plan kicks in at the kill
                    // reach anyway and the crit walk-up closes the rest, this is for when it doesn't (no clear shot yet)
                    return approach(mod, entity, isKillTask() ? ReachRules.KILL_APPROACH : maintainDistance);
                case KEEP_CHILD:
                    // halfway through a step: let it land, the swing goes next tick that is safe. stopping the child here
                    // cut the path mid stride and the next tick started it again
                    _progress.reset();
                    _blocker = null;
                    setDebugState("Finishing the step before the swing");
                    _approachLive = true;
                    return _approach;
                case SWING_NOW:
                    // stood in reach this long with no swing: whatever path is in the way goes, the hit is what is owed
                    _progress.reset();
                    _blocker = null;
                    _standCap.forced();
                    dropBackOff(mod);
                    mod.getClientBaritone().getPathingBehavior().cancelEverything();
                    setDebugState("In reach, swinging through the stall");
                    return onStalledSwing(mod, entity);
                case WAIT:
                    _progress.reset();
                    _blocker = null;
                    // the shuffle we started (plan WANDER) puts a path up that makes the next plan WAIT, and null cut it every
                    // other tick. same rule as with no target: a wander that is already our child finishes its step
                    if (wasWandering && !mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                        setDebugState("Finishing the shuffle");
                        _wanderLive = true;
                        return new TimeoutWanderTask();
                    }
                    setDebugState("In reach, waiting for the swing");
                    return null;
                default:
                    // the shuffle is a walk too
                    if (plan == ApproachRules.Plan.WANDER && isKillTask() && lavaHold(mod, entity)) {
                        setDebugState("Lava by the foe, not shuffling");
                        return null;
                    }
                    break;
            }
        }
        _blocker = null;
        if (!ApproachRules.wanderWhenNoTarget(wasWandering, mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
            return null;
        }
        // a fresh one is isEqual to the running one, so the running one stays
        _wanderLive = true;
        return new TimeoutWanderTask();
    }

    // waiting on one foe by the lava. an animal is let go of at once (the picker takes the next one), anything that can bite
    // gets LavaShore.WAIT_CAP_TICKS to come to us. a ban takes it out of getClosestEntity, so the next tick has another target.
    // the count is per mob id and static: callers rebuild this task whenever their pick changes, and two foes swapping places
    // as the closest would restart a count that lives in the task forever
    private static final java.util.Map<Integer, int[]> LAVA_WAITS = new java.util.HashMap<>();
    // a gap this long between two holds on one mob is a different wait
    private static final int LAVA_WAIT_GAP = 100;

    private boolean lavaHold(AltoClef mod, Entity entity) {
        if (!lavaInTheWay(mod, entity)) {
            return false;
        }
        int now = mod.getPlayer().tickCount;
        if (LAVA_WAITS.size() > 64) {
            LAVA_WAITS.clear();
        }
        int[] wait = LAVA_WAITS.computeIfAbsent(entity.getId(), k -> new int[]{0, now});
        if (now < wait[1] || now - wait[1] > LAVA_WAIT_GAP) {
            wait[0] = 0;
        }
        wait[0]++;
        wait[1] = now;
        if (LavaShore.giveUp(isAnimal(mod, entity), wait[0])) {
            BanPolicy.lavaShore(mod.getBans(), entity.getId(), EntityTracker.describe(entity));
            LAVA_WAITS.remove(entity.getId());
        }
        return true;
    }

    // by type, not by mood: an enderman nobody has angered yet or a day spider reads as not hostile until the first hit, and
    // that hit is what throws us into the pool
    private static boolean isAnimal(AltoClef mod, Entity entity) {
        return !(entity instanceof Enemy) && !(entity instanceof NeutralMob) && !EntityHelper.isGenerallyHostileToPlayer(mod, entity);
    }

    // the approach walk near lava, see LavaShore. every dimension: a cave lava pool behind a spider is the overworld's version
    private static boolean lavaInTheWay(AltoClef mod, Entity entity) {
        // only the ones that walk up and hit. a blaze hovers over the lava on purpose, and waiting for it to come is no rods
        if (entity instanceof Blaze || entity instanceof Ghast || (entity instanceof Mob mob && MobReachability.isRanged(mob))) return false;
        Cells cells = CliffAvoidance.of(mod.getClientBaritone().bsi);
        var us = mod.getPlayer();
        return LavaShore.blockedFor(isAnimal(mod, entity), cells, us.getX(), us.getY(), us.getZ(), entity.getX(), entity.getY(), entity.getZ());
    }

    // close enough to interact. the feet line for everyone (trading, shearing, frames), the kill tasks go by the eye to box
    // gap inside the swing's reach instead (ReachRules.killReach)
    protected boolean inReach(AltoClef mod, Entity entity) {
        return mod.getControllerExtras().inRange(entity);
    }

    // the kill tasks are committed to the mob in front of them: they stand and hit what is in reach and do not shuffle off it
    protected boolean isKillTask() {
        return false;
    }

    // the swing for a stall the plan gave up waiting out (kill tasks only)
    protected Task onStalledSwing(AltoClef mod, Entity entity) {
        return null;
    }

    // the raycast in onTick goes through blocks and the swing gate doesn't, and the gap is shorter than the aimed ray. so
    // "in reach with a clear shot" can be true for good while the swing never is (a wall, a slab lip, a mob at an awkward
    // angle), and the stall breaker can't help, it swings through the same gate. after this many ticks where the aim had
    // the head and still isn't on the mob, the shot counts as not clear: the plan walks closer, and the approach's own
    // stuck check digs or blacklists if walking can't fix it
    private static final int AIM_STALL_TICKS = 30;
    private int _unaimedTicks;
    private Entity _unaimedEntity;

    private boolean aimStalled(AltoClef mod, Entity entity, boolean shotInReach) {
        if (_unaimedEntity != entity) {
            _unaimedEntity = entity;
            _unaimedTicks = 0;
        }
        if (!inReach(mod, entity)) {
            _unaimedTicks = 0;
            return false;
        }
        // in reach we want the head on it whatever the plan says: waiting out a gate (eating, a ladder, the water) with the
        // head turned away is a stall of its own, and the stall breaker should start with the head already there. a
        // walking path keeps the head anyway, so asking during a step costs nothing
        mod.getControllerExtras().aimAt(entity, CombatAim.Priority.KILL_TASK);
        if (mod.getControllerExtras().onTarget(entity)) {
            _unaimedTicks = 0;
            return false;
        }
        if (_unaimedTicks >= AIM_STALL_TICKS) {
            return true;
        }
        // only ticks the aim could have used count: a walking path has the head
        if (shotInReach && !mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _unaimedTicks++;
        }
        return false;
    }

    // feeds the stand cap: a swing, a new target or the mob leaving reach starts it over
    private void countStandingTicks(AltoClef mod, Entity entity, boolean standingInReach) {
        long swings = mod.getControllerExtras().swings();
        boolean swung = swings != _swingsSeen;
        _swingsSeen = swings;
        if (_waitEntity != entity) {
            _waitEntity = entity;
            _standCap.reset();
        }
        _standCap.tick(standingInReach, swung);
    }

    // the run-away we started has to be moving us, or it is dropped and left alone for a bit
    private void watchBackOff(AltoClef mod, long now) {
        var goalProcess = mod.getClientBaritone().getCustomGoalProcess();
        if (_backOffGoal == null || goalProcess.getGoal() != _backOffGoal || !goalProcess.isActive()) {
            _backOffWatch.clear();
            return;
        }
        if (_backOffWatch.stuck(now, mod.getPlayer().getX(), mod.getPlayer().getZ())) {
            dropBackOff(mod);
            _backOffMutedUntil = now + BackOffRules.MUTE_TICKS;
        }
    }

    // only the kill tasks swing, so only they have a hop for the back-off to get out of the way of. shearing a sheep right
    // after equipping shears also has a recharging cooldown, and that is no reason to skip its back-off
    protected boolean wantsCritHops() {
        return false;
    }

    // the kill tasks' back-off: a step out from a mob that is right on top of us, only while there is nothing better to do
    // with the time. a swing due or a hop planned for this cooldown wants us close, and a path alive then is what vetoes
    // the hop and takes the head off the mob, so the back-off is dropped the moment either shows up. a hop being planned
    // is asked for the whole charge, not just the lead: a back-off that starts mid charge is still walking when the lead
    // comes. (cancelSegmentIfSafe can take a few ticks mid step, that is fine, a ledge is worse)
    private void killBackOff(AltoClef mod, Entity entity, boolean tooClose) {
        if (!tooClose && _backOffGoal == null) {
            return;
        }
        boolean stayClose = mod.getControllerExtras().attackReady()
                || (wantsCritHops() && mod.getControllerExtras().hopWantsToStayClose(entity));
        if (CritTiming.shouldBackOff(tooClose, stayClose)) {
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                _backOffGoal = new GoalRunAway(ReachRules.BACK_OFF_TO, entity.blockPosition());
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_backOffGoal);
            }
        } else if (stayClose) {
            dropBackOff(mod);
        }
    }

    // the run-away we started, so the hop can stop exactly that and nobody else's path. a hop only works while baritone is
    // not walking (the jump press is ignored under its movement input), and the back-off is the walking that was always
    // there. cancel the path too, not just the goal, or isPathing() still says yes for the tick we wanted to jump in
    private void dropBackOff(AltoClef mod) {
        if (_backOffGoal == null) {
            return;
        }
        var goalProcess = mod.getClientBaritone().getCustomGoalProcess();
        if (goalProcess.getGoal() == _backOffGoal) {
            goalProcess.onLostControl();
            mod.getClientBaritone().getPathingBehavior().cancelSegmentIfSafe();
        }
        _backOffGoal = null;
    }

    private Task approach(AltoClef mod, Entity entity, double maintainDistance) {
        if (_blockerTarget != entity) {
            _blockerTarget = entity;
            _blockerDigs = 0;
            _blocker = null;
        }
        if (_blocker != null) {
            if (!_blocker.isFinished(mod) && ++_blockerTicks <= BLOCKER_TICKS) {
                setDebugState("Breaking what is in the way");
                _progress.reset();
                return _blocker;
            }
            _blocker = null;
            _progress.reset();
        }
        setDebugState("Approaching target");
        if (!_progress.check(mod)) {
            _progress.reset();
            BlockPos blocker = findBlocker(mod, entity);
            if (blocker != null) {
                Debug.logMessage("Something is in the way of the target, breaking it.");
                _blockerDigs++;
                _blockerTicks = 0;
                _blocker = new DestroyBlockTask(blocker);
                return _blocker;
            }
            Debug.logMessage("Failed to get to target, blacklisting.");
            mod.getEntityTracker().requestEntityUnreachable(entity);
        }
        // Move to target
        return approachTask(entity, maintainDistance);
    }

    // a fresh GetToEntityTask every tick never let its stuck checks build up anything, same target means same task
    private GetToEntityTask approachTask(Entity entity, double maintainDistance) {
        boolean same = _approach != null && _approachEntity == entity && Math.abs(_approachDistance - maintainDistance) < 0.1;
        if (!same) {
            _approach = new GetToEntityTask(entity, maintainDistance);
            _approachEntity = entity;
            _approachDistance = maintainDistance;
        }
        _approachLive = true;
        return _approach;
    }

    // the first solid thing between our eye and the middle of the target, if digging it is allowed. collision shapes, because
    // that is what stops a swing: a fence is see through and still wins the raycast
    private BlockPos findBlocker(AltoClef mod, Entity entity) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, entity.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        double distance = eye.distanceTo(hit.getLocation());
        boolean ok = EntityBlockerRules.shouldMine(isBreakable(mod, pos), _blockerDigs, distance, holdsUsUp(player, pos), opensLavaNextToUs(level, player, pos));
        return ok ? pos : null;
    }

    // canBreak covers bedrock and the avoid lists. block entities are out because that is chests and furnaces and, worst of
    // all, the blaze spawner we are standing here for
    private static boolean isBreakable(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || state.is(Blocks.SPAWNER) || state.is(Blocks.BEDROCK)) return false;
        if (mod.getClientBaritoneSettings().blocksToAvoidBreaking.value.contains(state.getBlock())) return false;
        return WorldHelper.canBreak(mod, pos);
    }

    // any cell under the footprint of our feet
    private static boolean holdsUsUp(LocalPlayer player, BlockPos pos) {
        var box = player.getBoundingBox();
        int y = (int) Math.floor(box.minY - 0.001);
        if (pos.getY() != y) return false;
        return pos.getX() >= (int) Math.floor(box.minX) && pos.getX() <= (int) Math.floor(box.maxX)
                && pos.getZ() >= (int) Math.floor(box.minZ) && pos.getZ() <= (int) Math.floor(box.maxZ);
    }

    // lava touching the hole we would make, close enough to our feet that it would come to us
    private static boolean opensLavaNextToUs(Level level, LocalPlayer player, BlockPos pos) {
        BlockPos feet = player.blockPosition();
        if (Math.abs(pos.getX() - feet.getX()) > 2 || Math.abs(pos.getZ() - feet.getZ()) > 2) return false;
        int dy = pos.getY() - feet.getY();
        if (dy < -2 || dy > 1) return false;
        for (Direction side : Direction.values()) {
            if (level.getFluidState(pos.relative(side)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof AbstractDoToEntityTask task) {
            if (!doubleCheck(task._maintainDistance, _maintainDistance)) return false;
            if (!doubleCheck(task._combatGuardLowerFieldRadius, _combatGuardLowerFieldRadius)) return false;
            if (!doubleCheck(task._combatGuardLowerRange, _combatGuardLowerRange)) return false;
            return isSubEqual(task);
        }
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean doubleCheck(double a, double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.isInfinite(a) == Double.isInfinite(b);
        return Math.abs(a - b) < 0.1;
    }

    protected abstract boolean isSubEqual(AbstractDoToEntityTask other);

    protected abstract Task onEntityInteract(AltoClef mod, Entity entity);

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getMobDefenseChain().resetForceField();
        // a back-off we started is ours to stop, or the bot keeps running from a mob nobody is fighting any more
        dropBackOff(mod);
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}
