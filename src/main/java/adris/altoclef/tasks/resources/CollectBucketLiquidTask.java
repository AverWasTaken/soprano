package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectBucketLiquidTask extends ResourceTask {

    private final HashSet<BlockPos> _blacklist = new HashSet<>();
    private final TimerGame _tryImmediatePickupTimer = new TimerGame(3);
    private final TimerGame _pickedUpTimer = new TimerGame(0.5);
    private final int _count;

    //private IProgressChecker<Double> _checker = new LinearProgressChecker(5, 0.1);
    private final Item _target;
    private final Block _toCollect;
    private final String _liquidName;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();

    private boolean wasWandering = false;

    private static final int SCAN_WAIT_TICKS = 60;
    // a stream is followed once in a while, not every tick of a wander. the cube it starts from is 49^3 cells
    private static final int CLIMB_GAP_TICKS = 60;
    private static final double CLIMB_SEARCH_RANGE = 24;
    private static final int CLIMB_MAX_MOVES = 16;
    private int _scanWaitStart = -1;
    private int _lastClimbTick = -1000;
    private boolean _loggedWander = false;
    // sources the stream climb has already handed over
    private final HashSet<BlockPos> _climbed = new HashSet<>();

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        super(filledBucket, targetCount);
        _liquidName = liquidName;
        _target = filledBucket;
        _count = targetCount;
        _toCollect = toCollect;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(_toCollect);
        // Track fluids
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> Minecraft.getInstance().level.getBlockState(pos).getBlock() == _toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> Minecraft.getInstance().level.getBlockState(pos).getBlock() == _toCollect);

        //_blacklist.clear();

        _progressChecker.reset();
    }


    @Override
    protected Task onTick(AltoClef mod) {
        Task result = super.onTick(mod);
        // Reset our "first time" timeout/wander flag.
        if (!thisOrChildAreTimedOut()) {
            wasWandering = false;
        }
        return result;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
        }
        // If we're standing inside a liquid, go pick it up.
        if (_tryImmediatePickupTimer.elapsed() && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            Block standingInside = mod.getWorld().getBlockState(mod.getPlayer().blockPosition()).getBlock();
            if (standingInside == _toCollect) {
                setDebugState("Trying to collect (we are in it)");
                mod.getInputControls().forceLook(0, 90);
                //mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(0, 90), true);
                //Debug.logMessage("Looking at " + _toCollect + ", picking up right away.");
                _tryImmediatePickupTimer.reset();
                if (mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _pickedUpTimer.reset();
                    _progressChecker.reset();
                }
                return null;
            }
        }

        if (!_pickedUpTimer.elapsed()) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _progressChecker.reset();
            // Wait for force pickup
            return null;
        }

        // Get buckets if we need em
        int bucketsNeeded = _count - mod.getItemStorage().getItemCount(Items.BUCKET) - mod.getItemStorage().getItemCount(_target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }

        Predicate<BlockPos> isSourceLiquid = blockPos -> {
            if (_blacklist.contains(blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos.above())) return false; // We may try reaching the block above.
            assert Minecraft.getInstance().level != null;
            // We break the block above. If it's bedrock, ignore.
            if (mod.getWorld().getBlockState(blockPos.above()).getBlock() == Blocks.BEDROCK) {
                return false;
            }
            return WorldHelper.isSourceBlock(mod, blockPos, false);
        };

        // Find nearest water and right click it
        if (mod.getBlockTracker().isTracking(_toCollect)) {
            Optional<BlockPos> nearestSource = mod.getBlockTracker().getNearestTracking(isSourceLiquid, _toCollect);
            if (nearestSource.isPresent()) {
                _loggedWander = false;
                Block nearestSourceBlock = mod.getWorld().getBlockState(nearestSource.get()).getBlock();
                // We want to MINIMIZE this distance to liquid.
                setDebugState("Trying to collect...");
                //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);
                return new DoToClosestBlockTask(blockPos -> {
                    // Clear above if lava because we can't enter.
                    // but NOT if we're standing right above.
                    if (WorldHelper.isSolid(mod, blockPos.above())) {
                        if (!_progressChecker.check(mod)) {
                            mod.getClientBaritone().getPathingBehavior().cancelEverything();
                            mod.getClientBaritone().getPathingBehavior().forceCancel();
                            mod.getClientBaritone().getExploreProcess().onLostControl();
                            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                            Debug.logMessage("Failed to break, blacklisting.");
                            mod.getBlockTracker().requestBlockUnreachable(blockPos);
                            _blacklist.add(blockPos);
                        }
                        return new DestroyBlockTask(blockPos.above());
                    }
                    // We can reach the block.
                    if (LookHelper.getReach(blockPos).isPresent() &&
                            mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                        return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), blockPos, _toCollect != Blocks.LAVA);
                    }
                    // Get close enough.
                    // up because if we go below we'll try to move next to the liquid (for lava, not a good move)
                    if (this.thisOrChildAreTimedOut() && !wasWandering) {
                        mod.getBlockTracker().requestBlockUnreachable(blockPos.above());
                        wasWandering = true;
                    }
                    return new GetCloseToBlockTask(blockPos.above());
                }, isSourceLiquid, nearestSourceBlock);
            }
        }

        // Dimension
        if (_toCollect == Blocks.WATER && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // a scan for this liquid that hasn't landed yet says nothing about whether there is any. same cap and idea as
        // AbstractDoToClosestObjectTask's wait
        int now = WorldHelper.getTicks();
        if (mod.getBlockTracker().scanPending(_toCollect)) {
            if (_scanWaitStart < 0) _scanWaitStart = now;
            if (now - _scanWaitStart < SCAN_WAIT_TICKS) {
                setDebugState("Waiting for the first scan before wandering");
                return null;
            }
        } else {
            _scanWaitStart = -1;
        }

        // the scan only reports sources. a waterfall in view with its source out of the scan's sight is still a
        // way to the source, so follow it up and hand what it finds to the tracker
        if (tryClimbToSource(mod, now)) {
            setDebugState("Followed a stream up to its source");
            return null;
        }

        // Oof, no liquid found.
        if (!_loggedWander) {
            _loggedWander = true;
            List<BlockPos> tracked = mod.getBlockTracker().getKnownLocations(_toCollect);
            long passing = tracked.stream().filter(isSourceLiquid).count();
            Debug.logMessage("No " + _liquidName + " to collect: tracker knows " + tracked.size() + " positions, " + passing + " passed, wandering");
        }
        setDebugState("Searching for liquid by wandering around aimlessly");

        return new TimeoutWanderTask();
    }

    // true if it found a source and told the tracker, so the next tick's query can pick it up
    private boolean tryClimbToSource(AltoClef mod, int now) {
        if (now - _lastClimbTick < CLIMB_GAP_TICKS) return false;
        _lastClimbTick = now;
        Optional<BlockPos> stream = mod.getBlockTracker().getNearestWithinRange(mod.getPlayer().position(), CLIMB_SEARCH_RANGE, _toCollect);
        if (stream.isEmpty()) return false;
        Optional<BlockPos> source = FluidSources.climbToSource(
                pos -> mod.getWorld().getBlockState(pos).getFluidState(), stream.get(), CLIMB_MAX_MOVES);
        // already tried it and the predicate didn't like it, climbing again would just find it again
        if (source.isEmpty() || !_climbed.add(source.get())) return false;
        mod.getBlockTracker().addBlock(_toCollect, source.get());
        return true;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_toCollect);
        mod.getBehaviour().pop();
        //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBucketLiquidTask task) {
            if (task._count != _count) return false;
            return task._toCollect == _toCollect;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Filling " + HudText.count(_count, "Bucket") + " with " + _liquidName;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " " + _liquidName + " buckets";
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }

    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

}
