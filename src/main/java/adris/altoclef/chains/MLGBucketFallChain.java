package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.LadderClutchFallTask;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnnecessaryLocalVariable")
public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    private final TimerGame _tryCollectWaterTimer = new TimerGame(4);
    private final TimerGame _pickupRepeatTimer = new TimerGame(0.25);
    private MLGBucketTask _lastMLG = null;
    private boolean _wasPickingUp = false;
    private boolean _doingChorusFruit = false;

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        //_lastMLG = null;
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        boolean falling = isFallingOhNo(mod) && !baritoneHasTheFall(mod);
        LadderClutchFallTask ladder = falling ? ladderClutch(mod) : ladderTask();
        if (ladder != null) {
            // in the air it's the clutch, and once we're down it stays until the ladder is back in the inventory
            setTask(ladder);
            _lastMLG = null;
            return falling ? 100 : 60;
        }
        if (claimsFall(falling, falling && MLGBucketTask.hasClutchItem(mod))) {
            _tryCollectWaterTimer.reset();
            setTask(new MLGBucketTask());
            _lastMLG = (MLGBucketTask) _mainTask;
            return 100;
        } else if (!_tryCollectWaterTimer.elapsed()) { // Why -0.5? Cause it's slower than -0.7.
            // We just placed water, try to collect it.
            if (mod.getItemStorage().hasItem(Items.BUCKET) && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                if (_lastMLG != null) {
                    BlockPos placed = _lastMLG.getWaterPlacedPos();
                    boolean isPlacedWater;
                    try {
                        isPlacedWater = mod.getWorld().getBlockState(placed).getBlock() == Blocks.WATER;
                    } catch (Exception e) {
                        isPlacedWater = false;
                    }
                    //Debug.logInternal("PLACED: " + placed);
                    if (placed != null && placed.closerToCenterThan(mod.getPlayer().position(), 5.5) && isPlacedWater && !baritonePicksUp(mod, placed)) {
                        BlockPos toInteract = placed;
                        // Allow looking at fluids
                        mod.getBehaviour().push();
                        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
                        Optional<Rotation> reach = LookHelper.getReach(toInteract, Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(toInteract)) {
                                if (mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                                    if (_pickupRepeatTimer.elapsed()) {
                                        // Pick up
                                        _pickupRepeatTimer.reset();
                                        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                        _wasPickingUp = true;
                                    } else if (_wasPickingUp) {
                                        // Stop picking up, wait and try again.
                                        _wasPickingUp = false;
                                    }
                                }
                            }
                        } else {
                            // Eh just try collecting water the regular way if all else fails.
                            setTask(TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1));
                        }
                        mod.getBehaviour().pop();
                        return 60;
                    }
                }
            }
        }
        if (_wasPickingUp) {
            _wasPickingUp = false;
            _lastMLG = null;
        }
        if (mod.getPlayer().hasEffect(MobEffects.LEVITATION) &&
                !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(Items.CHORUS_FRUIT)) &&
                mod.getPlayer().getActiveEffectsMap().get(MobEffects.LEVITATION).getDuration() <= 70 &&
                mod.getItemStorage().hasItemInventoryOnly(Items.CHORUS_FRUIT) &&
                !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            _doingChorusFruit = true;
            mod.getSlotHandler().forceEquipItem(Items.CHORUS_FRUIT);
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else if (_doingChorusFruit) {
            _doingChorusFruit = false;
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        _lastMLG = null;
        return Float.NEGATIVE_INFINITY;
    }

    // an mlg with nothing to place is a task that stares at the ground saying "no clutch item" while it cancels the path
    // at priority 100, so all it buys us is a replan. no bucket and no ladder (that case is handled before this) means
    // there's nothing to take over for. pure so it's testable without a game, same idea as FallCover
    static boolean claimsFall(boolean falling, boolean hasClutchItem) {
        return falling && hasClutchItem;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket Fall Chain";
    }

    @Override
    public String getHudName() {
        return "Breaking a fall";
    }

    @Override
    public boolean isActive() {
        // We're always checking for mlg.
        return true;
    }

    public boolean doneMLG() {
        return _lastMLG == null && ladderTask() == null;
    }

    public boolean isChorusFruiting() {
        return _doingChorusFruit;
    }

    // the ladder clutch we're in the middle of, unfinished, if any
    private LadderClutchFallTask ladderTask() {
        return _mainTask instanceof LadderClutchFallTask task && !task.finished() ? task : null;
    }

    // what to do about an unplanned fall with a ladder or vine: the one already going, or a new one if there's no water
    // to do it with (water doesn't need a wall, so it wins) and something to hang it on in time. null means the plain mlg
    private LadderClutchFallTask ladderClutch(AltoClef mod) {
        LadderClutchFallTask going = ladderTask();
        if (going != null) {
            return going;
        }
        boolean water = !mod.getWorld().dimensionType().ultraWarm() && mod.getItemStorage().hasItem(Items.WATER_BUCKET);
        return water ? null : LadderClutchFallTask.probe(mod);
    }

    // the fall movement baritone is running right now, if it is one
    private MovementFall currentFall(AltoClef mod) {
        PathingBehavior pathing = mod.getClientBaritone().getPathingBehavior();
        PathExecutor executor = pathing.getCurrent();
        if (executor == null || !pathing.isPathing()) {
            return null; // paused or no path, nobody is driving
        }
        List<IMovement> movements = executor.getPath().movements();
        int at = executor.getPosition();
        return at >= 0 && at < movements.size() && movements.get(at) instanceof MovementFall fall ? fall : null;
    }

    // a fall baritone planned (a safe one, a clutch, or a bucket it has on the hotbar) is baritone's. our mlg task cancels
    // the path and fights it for the rotation, which turns a free fall into a replan. isFallingOhNo stays as is, the
    // other chains still want to know we're in the air (no eating mid fall)
    private boolean baritoneHasTheFall(AltoClef mod) {
        MovementFall fall = currentFall(mod);
        return fall != null && fall.ownsFall(mod.getPlayer().position(), mod.getPlayer().getDeltaMovement());
    }

    // the water baritone's fall movement is about to pick up itself. our click on top of its click places it again
    private boolean baritonePicksUp(AltoClef mod, BlockPos placed) {
        MovementFall fall = currentFall(mod);
        return fall != null && fall.picksUpWaterAt(placed);
    }

    public boolean isFallingOhNo(AltoClef mod) {
        if (!Baritone.settings().altoAutoMLGBucket.value) {
            return false;
        }
        if (mod.getPlayer().isSwimming() || mod.getPlayer().isInWater() || mod.getPlayer().onGround() || mod.getPlayer().onClimbable()) {
            // We're grounded.
            return false;
        }
        double ySpeed = mod.getPlayer().getDeltaMovement().y;
        return ySpeed < -0.7;
    }
}
