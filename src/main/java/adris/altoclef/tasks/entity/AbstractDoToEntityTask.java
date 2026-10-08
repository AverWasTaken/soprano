package adris.altoclef.tasks.entity;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.EntityBlockerRules;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import baritone.api.pathing.goals.GoalRunAway;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
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
        _blocker = null;
        _blockerTarget = null;
        _blockerDigs = 0;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        } // Kinda duct tape but it should be future proof ish
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progress.reset();
        }

        Optional<Entity> checkEntity = getEntityTarget(mod);
        if (checkEntity.isEmpty()) _blocker = null;

        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetTargetEntity();
            mod.getMobDefenseChain().resetForceField();
        } else {
            mod.getMobDefenseChain().setTargetEntity(checkEntity.get());
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

            boolean tooClose = sqDist < maintainDistance * maintainDistance;

            // Step away if we're too close
            if (tooClose) {
                //setDebugState("Maintaining distance");
                if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalRunAway(maintainDistance, entity.blockPosition()));
                }
            }

            if (mod.getControllerExtras().inRange(entity) && result != null &&
                    result.getType() == HitResult.Type.ENTITY && !mod.getFoodChain().needsToEat() &&
                    !mod.getMLGBucketChain().isFallingOhNo(mod) && mod.getMLGBucketChain().doneMLG() &&
                    !mod.getMLGBucketChain().isChorusFruiting() &&
                    mod.getClientBaritone().getPathingBehavior().isSafeToCancel() &&
                    mod.getPlayer().onGround()) {
                _progress.reset();
                _blocker = null;
                return onEntityInteract(mod, entity);
            } else if (!tooClose) {
                return approach(mod, entity, maintainDistance);
            }
        }
        _blocker = null;
        if (!mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            return null;
        }
        return new TimeoutWanderTask();
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
        if (Double.isInfinite(a) == Double.isInfinite(b)) return true;
        return Math.abs(a - b) < 0.1;
    }

    protected abstract boolean isSubEqual(AbstractDoToEntityTask other);

    protected abstract Task onEntityInteract(AltoClef mod, Entity entity);

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (interruptTask instanceof DodgeProjectilesTask) {
            // an arrow cut in, the fight is still on. clearing the target here is how a dodge used to turn into a run:
            // no target means the flee checks think nobody is being fought
            mod.getMobDefenseChain().parkTarget(mod.getWorld().getGameTime());
        } else {
            mod.getMobDefenseChain().setTargetEntity(null);
        }
        mod.getMobDefenseChain().resetForceField();
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}
