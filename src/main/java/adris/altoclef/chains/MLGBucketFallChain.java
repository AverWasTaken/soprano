package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.LadderClutchFallTask;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.BucketPour;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.path.PathExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("UnnecessaryLocalVariable")
public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    // how long after landing we keep trying to get our water back, timed from the first tick on the ground
    private final TimerGame _cleanupTimer = new TimerGame(4);
    // the cells we really poured into (MLGBucketTask.placedCells), copied every tick so a restarted task loses nothing
    private final List<BlockPos> _ourWater = new ArrayList<>();
    private final PickUpOurWaterTask _pickup = new PickUpOurWaterTask();
    private MLGBucketTask _lastMLG = null;
    // the mlg task had the fall last tick. only this blocks defense and food, the cleanup after it doesn't
    private boolean _clutching = false;
    private boolean _cleanupLive = false;
    private int _lastWaterBuckets;
    private boolean _doingChorusFruit = false;

    // further than this from a cell and we walked off (or got knocked off), the water's not worth a walk back
    static final double CLEANUP_REACH = 3;

    enum CellEnd {
        KEEP, PICKED_UP, GONE, TOO_FAR
    }

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
            _clutching = false;
            return falling ? 100 : 60;
        }
        // no water, hay or vines dropped into a build, and nothing to save in creative. a survival fall that kills us
        // still gets the task, losing the whole inventory is worse than one wrong block the builder breaks again
        boolean hasClutchItem = falling && mod.getClientBaritone().getInventoryBehavior().clutchWanted() && MLGBucketTask.hasClutchItem(mod);
        boolean deadly = falling && !hasClutchItem && !mod.getPlayer().getAbilities().invulnerable && MLGBucketTask.fallIsDeadly(mod);
        if (claimsFall(falling, hasClutchItem, deadly)) {
            setTask(new MLGBucketTask());
            _lastMLG = (MLGBucketTask) _mainTask;
            _clutching = true;
            // a new fall restarts the clock, it only starts once we're down again
            _cleanupLive = false;
            takePours();
            return 100;
        }
        _clutching = false;
        // the click on the tick we landed shows up here, one tick after the task's last
        takePours();
        _lastMLG = null;
        float cleanup = cleanUp(mod);
        if (cleanup != Float.NEGATIVE_INFINITY) {
            return cleanup;
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
        return Float.NEGATIVE_INFINITY;
    }

    private void takePours() {
        if (_lastMLG == null) {
            return;
        }
        for (BlockPos cell : _lastMLG.placedCells()) {
            if (!_ourWater.contains(cell)) {
                _ourWater.add(cell);
            }
        }
    }

    // after landing: pick up every cell we poured into, nearest first. a tick where we can't (no aim yet, no empty bucket,
    // somebody else has the wheel) is just a tick, only the per cell ends in cellEnd or the clock finish it. the clicking
    // lives in a task so it only runs when we win: mob defense (65 and up) and world survival's own pickup (60, ahead of
    // our 59) never get a camera fight out of us
    private float cleanUp(AltoClef mod) {
        if (_ourWater.isEmpty()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        if (!MLGBucketTask.landed(mod)) {
            return Float.NEGATIVE_INFINITY; // baritone took the rest of the fall, or a bounce. the clock waits for the floor
        }
        int buckets = mod.getItemStorage().getItemCount(Items.WATER_BUCKET);
        if (!_cleanupLive) {
            _cleanupLive = true;
            _cleanupTimer.reset();
            _lastWaterBuckets = buckets;
        }
        if (_cleanupTimer.elapsed()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        boolean gained = buckets > _lastWaterBuckets;
        _lastWaterBuckets = buckets;
        Vec3 me = mod.getPlayer().position();
        _ourWater.removeIf(cell -> cellEnd(BucketPour.isWaterSource(mod.getWorld(), cell), gained, Math.sqrt(cell.distToCenterSqr(me))) != CellEnd.KEEP);
        if (_ourWater.isEmpty()) {
            endCleanup();
            return Float.NEGATIVE_INFINITY;
        }
        // eating holds right click with the food in hand, a bucket swap mid bite wastes the bite and maybe the water
        if (!mod.getItemStorage().hasItem(Items.BUCKET) || mod.getFoodChain().isTryingToEat()) {
            return Float.NEGATIVE_INFINITY;
        }
        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos cell : _ourWater) {
            double d = cell.distToCenterSqr(me);
            if (d < best && !baritonePicksUp(mod, cell)) {
                best = d;
                target = cell;
            }
        }
        if (target == null) {
            return Float.NEGATIVE_INFINITY;
        }
        _pickup.target = target;
        setTask(_pickup);
        return 59;
    }

    private void endCleanup() {
        _ourWater.clear();
        _cleanupLive = false;
        _pickup.target = null;
        if (_mainTask == _pickup) {
            setTask(null);
        }
    }

    // what happens to one of our cells this tick. the source going away with a bucket gained is the pickup, going away
    // without one is somebody else (or it flowed off a ledge), either way there's nothing left to fetch. pure for the tests
    static CellEnd cellEnd(boolean stillSource, boolean gainedBucket, double distance) {
        if (!stillSource) {
            return gainedBucket ? CellEnd.PICKED_UP : CellEnd.GONE;
        }
        return distance > CLEANUP_REACH ? CellEnd.TOO_FAR : CellEnd.KEEP;
    }

    // an mlg with nothing to place is a task that stares at the ground saying "no clutch item" while it cancels the path
    // at priority 100, so all it buys us is a replan. no bucket and no ladder (that case is handled before this) means
    // there's nothing to take over for. except a fall that would kill us: the task still steers toward lava or water then,
    // and that's worth the interrupt. planned falls never get here, FallCover hands them to baritone first.
    // pure so it's testable without a game, same idea as FallCover
    static boolean claimsFall(boolean falling, boolean hasClutchItem, boolean deadly) {
        return falling && (hasClutchItem || deadly);
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

    // false only while the clutch itself runs. the pickup after it doesn't count, a zombie at the landing is a bigger
    // problem than a bucket of water
    public boolean doneMLG() {
        return !_clutching && ladderTask() == null;
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

    // the right click that picks our water back up. the chain picks the cell, this just aims and clicks, and only on the
    // ticks the chain wins (that's the whole reason it's a task and not more getPriority)
    private static final class PickUpOurWaterTask extends Task {
        private final TimerGame _repeat = new TimerGame(0.25);
        private BlockPos target;

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (target == null) {
                return null;
            }
            // the ray has to see source water for isLookingAt to mean anything
            mod.getBehaviour().push();
            mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
            Optional<Rotation> reach = LookHelper.getReach(target, Direction.UP);
            if (reach.isPresent()) {
                mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                if (mod.getClientBaritone().getPlayerContext().isLookingAt(target) && mod.getSlotHandler().forceEquipItem(Items.BUCKET) && _repeat.elapsed()) {
                    _repeat.reset();
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                }
            }
            // out of reach just waits, the chain ends the cell once we're too far. no walk back, that's how the old
            // fallback went off to some other pond
            mod.getBehaviour().pop();
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return false; // the chain decides, by not asking anymore
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PickUpOurWaterTask;
        }

        @Override
        protected String toDebugString() {
            return "Picking up our water at " + target;
        }
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
