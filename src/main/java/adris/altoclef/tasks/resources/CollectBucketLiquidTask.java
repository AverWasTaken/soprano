package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BucketFillRules;
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
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectBucketLiquidTask extends ResourceTask {

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
    // made when a fill has no slot to land in. a finished one is stuck (nothing to throw), and _noRoom remembers that
    // until the bag stops needing room
    private EnsureFreeInventorySlotTask _roomTask = null;
    private boolean _noRoom = false;
    private boolean _loggedNoRoom = false;
    // when the current hold started (-1 = not holding), and when we started looking down for the spare bucket throw
    private int _holdSince = -1;
    private int _aimedSince = -1;
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

        _progressChecker.reset();
        // a restart gets a fresh look at whether anything can be thrown, the bag may have changed while we were away
        _roomTask = null;
        _noRoom = false;
        _loggedNoRoom = false;
        _holdSince = -1;
        _aimedSince = -1;
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
        // before either fill below (the one we're standing in and the one at the shore), not between a click and its wait
        if (_pickedUpTimer.elapsed()) {
            BucketFillRules.Fill fill = fillPlan(mod);
            if (fill == BucketFillRules.Fill.MAKE_ROOM) {
                setDebugState("Making room for the full bucket");
                return roomTask();
            }
            if (fill != BucketFillRules.Fill.HOLD) {
                _holdSince = -1;
            } else if (!holdOrDropSpare(mod)) {
                // not filling is the answer. nothing tells the caller, it just sees no lava bucket arrive; the portal phase's
                // budget is what ends a stalled cast, and a plain `get lava_bucket` idles until somebody frees a slot (or
                // until the spare buckets below are gone and the one left fills in place)
                return null;
            }
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
            if (mod.getBlockTracker().unreachable(blockPos)) return false;
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
                            // was a set of this task's own that never emptied, now a ban with a clock
                            BanPolicy.bucketLidStuck(mod.getBans(), WorldHelper.getCurrentDimension(), blockPos.getX(), blockPos.getY(), blockPos.getZ());
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

    // what the next fill should do about the slot the full bucket needs (see BucketFillRules)
    private BucketFillRules.Fill fillPlan(AltoClef mod) {
        NonNullList<ItemStack> slots = mod.getPlayer().getInventory().items;
        int[] bucketStacks = new int[slots.size()];
        boolean freeSlot = false;
        for (int i = 0; i < bucketStacks.length; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                freeSlot = true;
            } else if (stack.is(Items.BUCKET)) {
                bucketStacks[i] = stack.getCount();
            }
        }
        if (!BucketFillRules.needsFreeSlot(bucketStacks, freeSlot)) {
            _roomTask = null;
            _noRoom = false;
            return BucketFillRules.Fill.GO;
        }
        // nothing to throw and nowhere to put it. latched, because handing the room task back next tick restarts it (stuck
        // flag reset) and the fill path would get cancelled and restarted every other tick
        if (_roomTask != null && _roomTask.isFinished(mod)) {
            _noRoom = true;
        }
        BucketFillRules.Fill fill = BucketFillRules.plan(bucketStacks, freeSlot, _noRoom, _toCollect == Blocks.LAVA);
        if (_noRoom && !_loggedNoRoom) {
            _loggedNoRoom = true;
            if (fill == BucketFillRules.Fill.HOLD) {
                // in chat too: an idle bot with nothing on screen reads as frozen
                Debug.logInternal("bucket: not filling " + _liquidName + ", no free slot");
                Debug.logMessage("bag is full and nothing can go, can't fill " + _liquidName);
            } else {
                Debug.logInternal("bucket: filling " + _liquidName + " anyway, no free slot");
            }
        }
        return fill;
    }

    // true when the fill may go ahead anyway (a lone bucket in hand fills in place, no new slot). false keeps holding, with
    // the state set. after the wait it throws every spare bucket of the stack in hand at once, keeping one. they land at
    // our feet with a 2 s pickup delay and are left to the pickup rules; the lone bucket is filled and we are off to the
    // lake long before then, and if they do find us first they merge back and the hold starts over from scratch
    private boolean holdOrDropSpare(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_holdSince < 0) {
            _holdSince = now;
        }
        int held = 0;
        if (now - _holdSince >= BucketFillRules.HOLD_DROP_TICKS && mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
            ItemStack hand = mod.getPlayer().getMainHandItem();
            held = hand.is(Items.BUCKET) ? hand.getCount() : 0;
        }
        BucketFillRules.Hold step = BucketFillRules.holdStep(held, now - _holdSince);
        if (step == BucketFillRules.Hold.FILL_IN_PLACE) {
            return true;
        }
        if (step == BucketFillRules.Hold.DROP_SPARES) {
            setDebugState("Dropping spare buckets to make room", "Dropping spare buckets");
            // straight down, so they land at our feet and not in the lake we are standing next to. the look has to reach
            // the server before the drop does (drop sends its packet right now, the rotation goes out with the tick), so
            // look now and throw two ticks later. the same wait lets the hotbar slot change from the equip reach the server,
            // drop does not sync the selected slot itself, so do not cut it to 1
            mod.getInputControls().forceLook(mod.getPlayer().getYRot(), 90);
            if (_aimedSince < 0) {
                _aimedSince = now;
            }
            if (now - _aimedSince >= 2) {
                _aimedSince = -1;
                for (int i = BucketFillRules.spares(held); i > 0; i--) {
                    mod.getPlayer().drop(false);
                }
                Debug.logInternal("bucket: held " + BucketFillRules.HOLD_DROP_TICKS / 20 + " s with no free slot, dropped "
                        + BucketFillRules.spares(held) + " spare buckets, filling the last one in place");
            }
        } else {
            _aimedSince = -1;
            setDebugState("Waiting for a free slot to fill " + _liquidName, "Waiting for a free slot to fill " + _liquidName);
        }
        return false;
    }

    private Task roomTask() {
        if (_roomTask == null) {
            _roomTask = new EnsureFreeInventorySlotTask();
        }
        return _roomTask;
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
