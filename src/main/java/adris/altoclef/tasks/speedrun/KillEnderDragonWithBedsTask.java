package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.speedrun.gamer.end.BedSafety;
import adris.altoclef.tasks.speedrun.gamer.end.DragonDeadLatch;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class KillEnderDragonWithBedsTask extends Task {
    public static final double DEFAULT_CLICK_RANGE = 5.3;
    private static final double DRAGON_GONE_SECONDS = 5;
    // the exit portal is a 3x3 ring around the fountain at (0,0), a tracked END_PORTAL block this close to it is the real one
    private static final int EXIT_PORTAL_RADIUS = 8;

    private final Task _whenNotPerchingTask;
    private final double _clickRange;
    // instance state: this used to be two statics shared by every instance (and Playground), reset only in onStart
    private final DragonDeadLatch _deadLatch = new DragonDeadLatch(DRAGON_GONE_SECONDS);

    private BlockPos _endPortalTop;
    private Task _positionTask;
    private boolean _perching;

    public KillEnderDragonWithBedsTask(IDragonWaiter notPerchingOverride) {
        this(notPerchingOverride, DEFAULT_CLICK_RANGE);
    }

    public KillEnderDragonWithBedsTask(IDragonWaiter notPerchingOverride, double clickRange) {
        _whenNotPerchingTask = (Task) notPerchingOverride;
        _clickRange = clickRange;
    }

    private static BlockPos locateExitPortalTop(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0))) return null;
        int height = WorldHelper.getGroundHeight(mod, 0, 0, Blocks.BEDROCK);
        if (height != -1) return new BlockPos(0, height, 0);
        return null;
    }

    // a tracked END_PORTAL next to (0,0). the tracker could still hold the stronghold portal from the overworld
    public static boolean exitPortalExists(AltoClef mod) {
        Optional<BlockPos> portal = mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL);
        return portal.isPresent() && Math.abs(portal.get().getX()) <= EXIT_PORTAL_RADIUS && Math.abs(portal.get().getZ()) <= EXIT_PORTAL_RADIUS;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _deadLatch.reset();
        _perching = false;
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL);
    }

    // no beds left in the inventory and none placed and waiting to be clicked. the caller swaps to the sword when this
    // holds and the dragon is not perched (see isFinished), this task alone would pillar and wait forever
    public boolean outOfBeds(AltoClef mod) {
        if (mod.getItemStorage().hasItem(ItemHelper.BED)) {
            return false;
        }
        return _endPortalTop == null || !mod.getBlockTracker().blockIsValid(_endPortalTop.above(), ItemHelper.itemsToBlocks(ItemHelper.BED));
    }

    public boolean isDragonDead() {
        return _deadLatch.isDead();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        /*
            If dragon is perching:
                If we're not in position (XZ):
                    Get in position (XZ)
                If there's no bed:
                    If we can't "reach" the top of the pillar:
                        Jump
                    Place a bed
                If the dragon's head hitbox is close enough to the bed:
                    Right click the bed
            Else:
                // Perform "Default Wander" mode and avoid dragon breath.
         */
        Optional<Entity> dragon = mod.getEntityTracker().getClosestEntity(EnderDragon.class);
        _deadLatch.update(exitPortalExists(mod), dragon.isPresent(),
                mod.getChunkTracker().isChunkLoaded(new BlockPos(0, 64, 0)), mod.getWorld().getGameTime() / 20.0);
        _perching = false;
        if (!_deadLatch.hasSeenDragon() && !_deadLatch.isDead()) {
            setDebugState("Waiting for dragon to spawn.", "Waiting for the dragon");
            return null;
        }
        if (_endPortalTop == null) {
            _endPortalTop = locateExitPortalTop(mod);
            if (_endPortalTop != null) {
                ((IDragonWaiter) _whenNotPerchingTask).setExitPortalTop(_endPortalTop);
            }
        }

        if (_endPortalTop == null) {
            setDebugState("Searching for end portal top.");
            return new GetToXZTask(0, 0);
        }

        if (_deadLatch.isDead()) {
            setDebugState("Waiting for overworld portal to spawn.");
            if (mod.getPlayer().getXRot() != -90) {
                mod.getPlayer().setXRot(-90);
            }
            return null;
        }

        if (dragon.isEmpty()) {
            setDebugState("No dragon found.");
            // not in the latch until it has been gone a while: walk to the middle meanwhile, that is where it ends up anyway
            if (!WorldHelper.inRangeXZ(mod.getPlayer(), _endPortalTop, 0.25)) {
                setDebugState("Going to end portal top at " + _endPortalTop.toString() + ".");
                return new GetToXZTask(_endPortalTop.getX(), _endPortalTop.getZ());
            }
        } else {
            EnderDragon dragonEntity = (EnderDragon) dragon.get();
            DragonPhaseInstance dragonPhase = dragonEntity.getPhaseManager().getCurrentPhase();

            boolean perching = dragonPhase.getPhase() == EnderDragonPhase.LANDING || dragonPhase.isSitting() || dragonPhase.getPhase() == EnderDragonPhase.LANDING_APPROACH;
            if (dragonEntity.getY() < _endPortalTop.getY() + 2) {
                // Dragon is already perched.
                perching = false;
            }
            _perching = perching;
            ((IDragonWaiter) _whenNotPerchingTask).setPerchState(perching);
            // When the dragon is not perching...
            if (_whenNotPerchingTask.isActive() && !_whenNotPerchingTask.isFinished(mod)) {
                setDebugState("Dragon not perching, performing special behavior...", "Waiting for the dragon to perch");
                return _whenNotPerchingTask;
            }
            if (perching) {
                mod.getFoodChain().shouldStop(true);
                BlockPos targetStandPosition = _endPortalTop.offset(-1, -1, 0);
                // If we're not positioned (above is OK), go there and make sure we're at the right height.
                if (_positionTask != null && _positionTask.isActive() && !_positionTask.isFinished(mod)) {
                    setDebugState("Going to position for bed cycle...", "Getting into position");
                    return _positionTask;
                }
                if ((!WorldHelper.inRangeXZ(WorldHelper.toVec3d(targetStandPosition), mod.getPlayer().position(), 0.50))
//                            && mod.getPlayer().getVelocity().getX() == 0 && mod.getPlayer().getVelocity().getY() == 0 && mod.getPlayer().getVelocity().getZ() == 0
                ) {
                    _positionTask = new GetToBlockTask(targetStandPosition);
                    Debug.logInternal("Going to position for bed cycle...");
                    setDebugState("Moving to target stand position", "Getting into position");
                    return _positionTask;
                }
                // We're positioned. Perform bed strats!
                return bedCycle(mod, dragonEntity);
            }
        }
        mod.getFoodChain().shouldStop(false);
        // Start our "Not perching task"
        return _whenNotPerchingTask;
    }

    private Task bedCycle(AltoClef mod, EnderDragon dragonEntity) {
        BlockPos bedTargetPosition = _endPortalTop.above();
        boolean bedPlaced = mod.getBlockTracker().blockIsValid(bedTargetPosition, ItemHelper.itemsToBlocks(ItemHelper.BED));
        if (!bedPlaced) {
            setDebugState("Placing bed", "Placing a bed");
            // If no bed, place bed.
            // Fire messes up our "reach" so we just assume we're good when we're above a height.
            boolean canPlace = LookHelper.getCameraPos(mod).y > bedTargetPosition.getY();
            if (canPlace) {
                // Look at and place!
                if (mod.getSlotHandler().forceEquipItem(ItemHelper.BED, true)) {
                    LookHelper.lookAt(mod, bedTargetPosition.below(), Direction.UP, true);
                    // There could be fire so eh place right away
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                }
            } else if (mod.getPlayer().onGround()) {
                // Jump
                mod.getInputControls().tryPress(Input.JUMP);
            }
            return null;
        }
        setDebugState("Wait for it...", "Waiting for the dragon");
        // Make sure we're standing on the ground so we don't blow ourselves up lmfao
        if (!mod.getPlayer().onGround()) {
            // Wait to fall
            return null;
        }
        // Wait for dragon head to be close enough to the bed's head...
        BlockPos bedfoot = WorldHelper.getBedFoot(mod, bedTargetPosition);
        if (bedfoot == null) {
            return null;
        }
        Vec3 headPos = dragonEntity.head.getBoundingBox().getCenter(); // dragon.head.getPos();
        double dist = headPos.distanceTo(WorldHelper.toVec3d(bedfoot));
        // this logged every tick for the whole perch
        Debug.logInternal("Dist: " + dist + " Health: " + dragonEntity.getHealth());

        if (dist < _clickRange) {
            // our own explosion is the likeliest thing to kill us here: with too little health for it, do not click, let
            // the food chain heal (it was told to stop for the perch) and wait for the next lap
            if (!BedSafety.canClick(mod.getPlayer().getHealth(), mod.getPlayer().getAbsorptionAmount(), mod.getPlayer().getArmorValue())) {
                mod.getFoodChain().shouldStop(false);
                setDebugState("Too hurt for a bed explosion, healing first", "Healing before the next bed");
                return null;
            }
            // Interact with the bed.
            return new InteractWithBlockTask(bedTargetPosition);
        }
        // Wait for it...
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getFoodChain().shouldStop(false);
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL);
    }

    // finished = out of beds and the dragon is not perched, the phase swaps to KillEnderDragonTask then. while perched we
    // stay: the last bed may be on the pillar waiting for its click
    @Override
    public boolean isFinished(AltoClef mod) {
        return isActive() && !_perching && outOfBeds(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof KillEnderDragonWithBedsTask;
    }

    @Override
    protected String toHudString() {
        return "Fighting the Ender Dragon with beds";
    }

    @Override
    protected String toDebugString() {
        return "Bedding the Ender Dragon";
    }
}
