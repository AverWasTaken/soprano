package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.AnnoyingBlocks;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.ToolSwap;
import baritone.utils.ToolSet;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.ui.HudText;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
import adris.altoclef.util.baritone.GoalReachBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker();
    private final BlockPos _pos;
    private final BlockPos _above;
    private Task _unstuckTask = null;
    private boolean isMining;
    // once this block has been judged dangerous from above it stays "mine from the side" for the
    // whole task, so a re-plan can't quietly go back to the goal that stands on top of it.
    // volatile because the place-avoid predicate is read from baritone's thread
    private volatile boolean _fromSide;
    private boolean _dangerChecked;
    private boolean _pushedBehaviour;
    // step off -> baritone walks right back on -> step off... more than this and we give the block up
    private final StepOffGuard _stepOffs = new StepOffGuard(3);
    private final ToolSwap _toolSwap = new ToolSwap();
    // ticks the swap was held off for pathing / eating. a stuck isPathing() is the same never-swings stall
    private int _swapBlockedTicks;
    private static final int SWAP_BLOCKED_CAP = 20;
    private boolean _swapGiveUpLogged;
    // the swinging distance goal didn't work out here, stand next to it
    private boolean closeIn;
    // ticks we've been soaked with the block in reach. see WaterBreakGuard
    private int _wetTicks;
    // the dry spots to get to first, looked up every so often instead of every tick. null = none (or not looked yet)
    private Goal _landGoal;
    private int _landLookAge;
    private boolean _landGoalSet;
    private int _landPathAt;

    // a column hangs on the block, so the shaft under it is as bad a place to stand as the top
    private boolean _sidesOnly;
    // ticks spent waiting for a column to come down into the block, so a stack that never lands (held up by something we
    // can't see, somebody's sand cannon) doesn't park us here for good
    private int _fallWaited;
    private int _dropPending;
    private static final int FALL_WAIT_MAX = 60;
    private static final int DROP_PENDING_MAX = 10;

    public DestroyBlockTask(BlockPos pos) {
        _pos = pos;
        _above = pos.above();
    }

    // true while something is falling into the block's cell, or is about to. the second half is the two ticks between the
    // block under a stack going and the stack turning into entities: the cell is open, nothing is falling yet, and a
    // task that checked "is it air" in there would call itself done with the sand still in the sky
    private boolean fallingStillComing(AltoClef mod) {
        boolean inFlight = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos);
        boolean pending = !inFlight && WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        _fallWaited = inFlight ? _fallWaited + 1 : 0;
        _dropPending = pending ? _dropPending + 1 : 0;
        return inFlight && _fallWaited <= FALL_WAIT_MAX || pending && _dropPending <= DROP_PENDING_MAX;
    }

    // decides (once, and only when the chunks around it are really loaded) if this block has to be
    // mined from the side. sticky: true stays true
    private boolean mustMineFromSide(AltoClef mod) {
        if (_fromSide) {
            return true;
        }
        if (_dangerChecked || !_pos.closerToCenterThan(mod.getPlayer().position(), 16)) {
            return false;
        }
        _dangerChecked = true;
        if (WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
            markFromSide(mod);
        }
        return _fromSide;
    }

    // air cells straight under the block, so GoalMineFromSide knows how far down the column we can stand and still swing up
    private static int openBelow(AltoClef mod, BlockPos pos) {
        int open = 0;
        while (open < GoalMineFromSide.MAX_UNDER && mod.getWorld().getBlockState(pos.below(open + 1)).isAir()) {
            open++;
        }
        return open;
    }

    private void markFromSide(AltoClef mod) {
        if (!_fromSide) {
            _fromSide = true;
            Debug.logInternal("Destroy block at " + _pos.toShortString() + " is dangerous from above, mining it from the side");
            // whatever plain goal is running would happily stand on top of it
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
    }

    // where to stand. next to it, unless it's wrapped in vines: then the cells touching it are vines, and getting into
    // those off the ground means climbing (or hopping, the planner finds them cheap) for a log we could swing at from
    // the bottom. closeIn is for when swinging distance didn't turn out to see the block
    public static Goal pickGoal(BlockGetter world, BlockPos pos, boolean closeIn) {
        if (world.getBlockState(pos.above()).getBlock() == Blocks.SNOW) {
            return new GoalBlock(pos);
        }
        return !closeIn && vineNextTo(world, pos) ? new GoalReachBlock(pos) : new GoalNear(pos, 1);
    }

    private static boolean vineNextTo(BlockGetter world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (world.getBlockState(pos.relative(side)).is(Blocks.VINE)) {
                return true;
            }
        }
        return false;
    }

    // how far around the block we look for somewhere dry, and how many we hand the pathfinder. a composite goal's
    // heuristic asks every one of them for every node, so a whole beach of them is for people who want slow code
    private static final int LAND_RADIUS = 4;
    private static final int LAND_SPOTS_MAX = 24;
    private static final int LAND_LOOK_TICKS = 20;
    private static final double EYE_HEIGHT = 1.62;

    // squares to stand on instead of swimming: solid dry ground under, two dry clear squares for us, and the block in
    // reach and in plain sight from the eyes there. closest to `from` first, so the cap drops the far ones
    public static List<BlockPos> landSpots(BlockGetter world, BlockPos target, double reach, BlockPos from) {
        List<BlockPos> spots = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double reachSq = reach * reach;
        Vec3 center = Vec3.atCenterOf(target);
        for (int dx = -LAND_RADIUS; dx <= LAND_RADIUS; dx++) {
            for (int dy = -LAND_RADIUS; dy <= LAND_RADIUS; dy++) {
                for (int dz = -LAND_RADIUS; dz <= LAND_RADIUS; dz++) {
                    int x = target.getX() + dx;
                    int y = target.getY() + dy;
                    int z = target.getZ() + dz;
                    // most of the cube is air or water and dies on the floor check
                    pos.set(x, y - 1, z);
                    if (!dryFloor(world, pos)) {
                        continue;
                    }
                    if (pos.equals(target) || (x == target.getX() && z == target.getZ() && y > target.getY())) {
                        continue; // on top of it is the one square we'd rather not be on
                    }
                    pos.set(x, y, z);
                    if (!dryAndClear(world, pos)) {
                        continue;
                    }
                    pos.set(x, y + 1, z);
                    if (!dryAndClear(world, pos)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, y + EYE_HEIGHT, z + 0.5);
                    if (eye.distanceToSqr(center) > reachSq) {
                        continue;
                    }
                    BlockHitResult hit = world.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) {
                        continue; // something is in the way, a wall isn't a place to mine from
                    }
                    spots.add(new BlockPos(x, y, z));
                }
            }
        }
        spots.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return spots.size() > LAND_SPOTS_MAX ? new ArrayList<>(spots.subList(0, LAND_SPOTS_MAX)) : spots;
    }

    private static boolean dryFloor(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isFaceSturdy(world, pos, Direction.UP);
    }

    private static boolean dryAndClear(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty();
    }

    // the dry spots as one goal for baritone, or null when there aren't any
    private static Goal landGoal(BlockGetter world, BlockPos target, double reach, BlockPos from) {
        List<BlockPos> spots = landSpots(world, target, reach, from);
        if (spots.isEmpty()) {
            return null;
        }
        Goal[] goals = new Goal[spots.size()];
        for (int i = 0; i < goals.length; i++) {
            goals[i] = new GoalBlock(spots.get(i));
        }
        return new GoalComposite(goals);
    }

    // soaked as in vanilla's mining speed is cut: feet in water and then eyes under it or no ground to stand on.
    // wading with your head up isn't slowed, so that isn't waited out
    private static boolean slowedByWater(AltoClef mod) {
        return mod.getPlayer().isInWater() && (!mod.getPlayer().onGround() || mod.getPlayer().isEyeInFluid(FluidTags.WATER));
    }

    // vanilla's getDestroyProgress already has the water and air penalties in it, so 1 or more is one tick no matter what
    private static boolean breaksInstantly(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        return state.getDestroyProgress(mod.getPlayer(), mod.getWorld(), pos) >= 1f;
    }

    private Goal landGoalNow(AltoClef mod) {
        if (_landLookAge-- <= 0) {
            _landLookAge = LAND_LOOK_TICKS;
            double reach = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance() - 0.5;
            _landGoal = landGoal(mod.getWorld(), _pos, reach, mod.getPlayer().blockPosition());
        }
        return _landGoal;
    }

    // the vine the first thing in line between the eyes and the middle of the block, if there is one and it's in reach.
    // anything else in the way (leaves, a wall) isn't ours to clear from here
    static BlockPos vineInTheWay(BlockGetter world, Vec3 eye, BlockPos target, double reach) {
        BlockHitResult hit = world.clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        return world.getBlockState(pos).is(Blocks.VINE) && eye.distanceTo(hit.getLocation()) <= reach ? pos : null;
    }

    /**
     * Generates the surrounding BlockPos based on the given position.
     *
     * @param pos The center BlockPos
     * @return An array of surrounding BlockPos
     */
    private static BlockPos[] generateSides(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        return new BlockPos[]{
                new BlockPos(x + 1, y, z),
                new BlockPos(x - 1, y, z),
                new BlockPos(x, y, z + 1),
                new BlockPos(x, y, z - 1),
                new BlockPos(x + 1, y, z - 1),
                new BlockPos(x + 1, y, z + 1),
                new BlockPos(x - 1, y, z - 1),
                new BlockPos(x - 1, y, z + 1)
        };
    }

    /**
     * Checks if the block at the specified position is annoying.
     *
     * @param mod the AltoClef instance
     * @param pos the position to check
     * @return true if the block is annoying, false otherwise
     */
    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return AnnoyingBlocks.isAnnoying(mod.getWorld().getBlockState(pos).getBlock());
    }

    /**
     * Finds the position where the player is stuck in a block.
     *
     * @param mod the mod instance
     * @return the position where the player is stuck, or null if not stuck
     */
    private BlockPos stuckInBlock(AltoClef mod) {
        // Check if player is stuck in their current position
        if (isAnnoying(mod, mod.getPlayer().blockPosition())) {
            return mod.getPlayer().blockPosition();
        }

        // Check if player is stuck when moving up
        if (isAnnoying(mod, mod.getPlayer().blockPosition().above())) {
            return mod.getPlayer().blockPosition().above();
        }

        // Check for stuck positions in the sides of the player's current position
        for (BlockPos check : generateSides(mod.getPlayer().blockPosition())) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }

        // Check for stuck positions in the sides of the player's position when moving up
        for (BlockPos check : generateSides(mod.getPlayer().blockPosition().above())) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }

        return null; // Player is not stuck
    }

    /**
     * Gets a task to unstick a fence.
     *
     * @return the task to unstick the fence, or null if an exception is caught
     */
    private Task getFenceUnstuckTask() {
        try {
            // Create a safe random shimmy task
            Task task = createSafeRandomShimmyTask();

            // Return the task
            return task;
        } catch (Exception e) {
            e.printStackTrace();
            // Handle the exception or rethrow it as needed
            return null;
        }
    }

    /**
     * Creates and returns a new instance of SafeRandomShimmyTask.
     *
     * @return a new SafeRandomShimmyTask instance
     */
    private Task createSafeRandomShimmyTask() {
        return new SafeRandomShimmyTask();
    }

    /**
     * This method is called when the AltoClef mod starts.
     * It cancels any ongoing pathing behavior, resets move checker and stuck check,
     * and handles the item stack in the cursor slot.
     * If the cursor stack is not empty, it calls handleNonEmptyCursorStack,
     * otherwise, it closes the screen.
     */
    @Override
    protected void onStart(AltoClef mod) {
        // Cancel any ongoing pathing behavior.
        mod.getClientBaritone().getPathingBehavior().forceCancel();

        // Reset move checker and stuck check.
        _moveChecker.reset();
        stuckCheck.reset();
        _stepOffs.reset();

        // never scaffold onto the square above the block we're here to break. only bites once
        // _fromSide is set, so ordinary blocks path exactly like before
        if (!_pushedBehaviour && mod.getBehaviour() != null) {
            mod.getBehaviour().push();
            _pushedBehaviour = true;
            mod.getBehaviour().avoidBlockPlacing(pos -> _fromSide && _above.equals(pos));
        }

        // Get the item stack in the cursor slot.
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();

        // If the cursor stack is not empty, handle it.
        if (!cursorStack.isEmpty()) {
            handleNonEmptyCursorStack(mod, cursorStack);
        } else {
            // If the cursor stack is empty, close the screen.
            StorageHelper.closeScreen();
        }
    }

    /**
     * Handles the non-empty cursor stack by performing various actions.
     * @param mod the AltoClef mod
     * @param cursorStack the cursor stack
     */
    private void handleNonEmptyCursorStack(AltoClef mod, ItemStack cursorStack) {
        // Get the slot that can fit the cursor stack in the player inventory and click it to pick up the item.
        mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false)
                .ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));

        // If the cursor stack can be thrown away, click an undefined slot to pick up the item.
        if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
        }

        // Get the garbage slot and click it to pick up the item.
        StorageHelper.getGarbageSlot(mod)
                .ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));

        // Click an undefined slot to pick up the item.
        mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Check if there is white wool at the specified position
        if (mod.getWorld().getBlockState(_pos).getBlock() == Blocks.WHITE_WOOL) {
            // Iterate over all entities in the world
            Iterable<Entity> entities = mod.getWorld().entitiesForRendering();
            for (Entity entity : entities) {
                // Check if the entity is a PillagerEntity and is within a distance of 144 blocks from the position
                if (entity instanceof Pillager && _pos.closerToCenterThan(entity.position(), 144)) {
                    // Request the block at the position to be marked as unreachable
                    mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
                }
            }
        }

        // Reset the move checker if Baritone is currently pathing
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _moveChecker.reset();
        }

        // Check if the player is in a Nether portal
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                // Hold the sneak and move forward inputs to exit the Nether portal
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }

        // Check if there is an active unstuck task and the player is stuck in a block
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.", "Stuck, wiggling free");
            stuckCheck.reset();
            // Release control of Baritone's custom goal process and explore process
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }

        // Check if the move checker or the stuck check failed
        if (!_moveChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }

        // Check if the move checker failed
        if (!_moveChecker.check(mod)) {
            _moveChecker.reset();
            // Request the block at the position to be marked as unreachable
            mod.getBlockTracker().requestBlockUnreachable(_pos);
        }

        // Check if the block above the position is not solid, the player is above the position,
        // and the player is within a distance of 0.89 blocks from the position
        boolean steppingOff = false;
        if (!WorldHelper.isSolid(mod, _pos.above()) && mod.getPlayer().position().y > _pos.getY() && _pos.closerToCenterThan(mod.getPlayer().onGround() ? mod.getPlayer().position() : mod.getPlayer().position().add(0, -1, 0), 0.89)) {
            if (_fromSide || WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
                markFromSide(mod);
                steppingOff = true;
            }
        }
        if (_stepOffs.tick(steppingOff)) {
            // we keep ending up on top of it. a different ore is a better idea than a tenth lap
            Debug.logInternal("Destroy block at " + _pos.toShortString() + ": stepped off it too many times, giving it up");
            mod.getBlockTracker().requestBlockUnreachable(_pos, 0);
        }
        if (steppingOff) {
            setDebugState("It's dangerous to break as we're right above it, moving away and trying again.", "Stepping off it first");
            return new RunAwayFromPositionTask(3, _pos.getY(), _pos);
        }

        // sand, gravel and the like on top of it (or a stalactite under it) come down the hole when it goes. we never
        // swing from where that lands on us, and we never swing at a block while the stack is still falling into it
        boolean looseColumn = WorldHelper.letsFallingLoose(mod.getWorld(), _pos);
        // already buried in it is the one case where the answer is to dig, there is no better place to be
        boolean buried = _pos.equals(WorldHelper.buriedInFallenBlock(mod));
        boolean dropsOnUs = looseColumn && !buried && WorldHelper.breakingDropsOnUs(mod, _pos);
        // buried, waiting is just suffocating with extra steps. dig now, the rest lands in the hole we make
        if (!buried && fallingStillComing(mod)) {
            setDebugState("Waiting for the falling blocks to land.", "Letting the sand settle");
            stuckCheck.reset();
            _moveChecker.reset();
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return null;
        }
        Optional<Rotation> reach = LookHelper.getReach(_pos);
        if (reach.isEmpty() && buried) {
            // the ray from an eye inside the block can come back empty, and the "get to block" branch would then walk us off
            // toward a side goal with our head still in the sand. we're in it, it's in reach, just look at it
            reach = Optional.of(RotationUtils.calcRotationFromVec3d(mod.getPlayer().getEyePosition(), Vec3.atCenterOf(_pos),
                    mod.getClientBaritone().getPlayerContext().playerRotations()));
        }
        BlockPos swingAt = _pos;
        if (reach.isEmpty()) {
            // vines count as a hit for the ray even though you walk through them, so a log behind a curtain of them is
            // "out of reach" until the curtain is gone. they break instantly, so take the one in the way down first
            BlockPos vine = vineInTheWay(mod.getWorld(), mod.getPlayer().getEyePosition(), _pos, mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance());
            Optional<Rotation> vineReach = vine == null ? Optional.empty() : LookHelper.getReach(vine);
            if (vineReach.isPresent()) {
                reach = vineReach;
                swingAt = vine;
            }
        }
        // breaking while soaked is up to 25x slower, so we get somewhere dry first unless that's the worse deal
        boolean slowed = slowedByWater(mod);
        if (!slowed) {
            // out of the water, or only wading in it. the clock starts over and the spots get looked up fresh next time
            _wetTicks = 0;
            _landLookAge = 0;
            _landGoal = null;
        }
        boolean waitForLand = false;
        boolean mayBreak = false;
        if (reach.isPresent()) {
            if (slowed) {
                _wetTicks++;
                double air = mod.getPlayer().getAirSupply() / (double) Math.max(1, mod.getPlayer().getMaxAirSupply());
                mayBreak = WaterBreakGuard.mayBreak(mod.getPlayer().onGround(), true, breaksInstantly(mod, swingAt), _wetTicks, air, landGoalNow(mod) != null);
                waitForLand = !mayBreak;
            } else {
                mayBreak = WaterBreakGuard.mayBreak(mod.getPlayer().onGround(), false, false, 0, 1, false);
            }
        }
        if (!waitForLand) {
            _landGoalSet = false;
        }
        if (looseColumn && !_sidesOnly) {
            // whatever plain goal is running would happily stand under it
            _sidesOnly = true;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
        // buried skips the ground and water patience too: in the air, on a ladder or wet, the head still has to come out
        if (reach.isPresent() && (mayBreak || buried) && !dropsOnUs
                && (buried || !mod.getFoodChain().needsToEat()) && !WorldHelper.isInNetherPortal(mod)
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            setDebugState("Block in range, mining...");
            stuckCheck.reset();
            _stepOffs.reset();
            isMining = true;
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            if (!LookHelper.isLookingAt(mod, reach.get())) {
                LookHelper.lookAt(mod, reach.get());
            }
            BlockState state = mod.getWorld().getBlockState(swingAt);
            Optional<Slot> bestToolSlot = StorageHelper.getBestToolSlot(mod, state);
            Slot currentEquipped = PlayerSlot.getEquipSlot();
            // if baritone is running, only accept tools OUTSIDE OF HOTBAR!
            // Baritone will take care of tools inside the hotbar.
            // (and not with a crack on the block: a different item in hand starts the break over)
            boolean crackedAlready = MineStick.toolSwapWouldReset(mod.getControllerExtras().isBreakingBlock(),
                    mod.getControllerExtras().getBreakingBlockPos(), mod.getControllerExtras().getBreakingBlockProgress(), swingAt);
            // a block that goes in one swing doesn't care what is in the hand
            if (bestToolSlot.isPresent() && !crackedAlready && !breaksInstantly(mod, swingAt)) {
                ItemStack held = StorageHelper.getItemStackInSlot(currentEquipped);
                ItemStack best = StorageHelper.getItemStackInSlot(bestToolSlot.get());
                // by item and by speed, not by slot: the slot object is new every tick, and two tools that are equally
                // fast would trade places forever (see ToolSwap)
                if (ToolSwap.worthSwapping(false, held.getItem(), held.isCorrectToolForDrops(state), ToolSet.calculateSpeedVsBlock(held, state),
                        best.getItem(), ToolSet.calculateSpeedVsBlock(best, state))) {
                    boolean isAllowedToManage = !mod.getClientBaritone().getPathingBehavior().isPathing()
                            && !mod.getFoodChain().isTryingToEat();
                    if (!isAllowedToManage && _swapBlockedTicks++ < SWAP_BLOCKED_CAP) {
                        return null;
                    }
                    Item bestToolItem = best.getItem();
                    // three misses and we swing with what we hold, a hand we can't change is no reason to never swing
                    if (isAllowedToManage && _toolSwap.mayTry(bestToolItem)) {
                        // the log file only: mining swaps pick and shovel every other block and chat drowned in it
                        Debug.logInternal("Found better tool in inventory, equipping " + bestToolItem.getDescriptionId() + ".");
                        if (mod.getSlotHandler().forceEquipItem(bestToolItem)) {
                            _toolSwap.landed();
                        } else {
                            _toolSwap.missed(bestToolItem);
                        }
                        return null;
                    }
                    // the log the next stall needs: what we mined with, what we wanted, where it was
                    if (!_swapGiveUpLogged) {
                        _swapGiveUpLogged = true;
                        Debug.logInternal("tool swap given up on " + state.getBlock().getDescriptionId() + ": holding " + held.getItem().getDescriptionId()
                            + ", wanted " + bestToolItem.getDescriptionId() + " from slot " + bestToolSlot.get().getWindowSlot());
                    }
                } else {
                    _toolSwap.landed();
                }
            }
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        } else {
            boolean fromSide = mustMineFromSide(mod);
            if (dropsOnUs) {
                setDebugState("Breaking it from here would bring the column down on us, stepping aside.", "Mining it from the side");
            } else if (waitForLand) {
                setDebugState("Getting out of the water before mining", "Getting out of the water first");
            } else if (fromSide) {
                setDebugState("Getting to block...", "Mining it from the side");
            } else {
                setDebugState("Getting to block...", "Walking to " + HudText.pos(_pos));
            }
            // we were swinging in the water (that only happens when WaterBreakGuard ran out of patience) and the current
            // took the block out of reach: it isn't worth chasing across a lake, let someone pick another. when it's
            // still in reach and we just stopped for dry land that's not a reason to give up on it
            if (isMining && reach.isEmpty() && mod.getPlayer().isInWater()) {
                isMining = false;
                mod.getBlockTracker().requestBlockUnreachable(_pos);
            } else {
                isMining = false;
            }
            if (waitForLand) {
                // we're doing what we meant to, the progress checkers don't get a say. the clock in WaterBreakGuard is the way out
                _moveChecker.reset();
                stuckCheck.reset();
                // swimming around with the button held down is the exact thing we're not doing
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                // the block is in reach, so whatever goal we had is probably satisfied right where we float and wouldn't budge
                if (!_landGoalSet) {
                    _landGoalSet = true;
                    _landPathAt = _wetTicks;
                    mod.getClientBaritone().getBuilderProcess().onLostControl();
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_landGoal);
                } else if (!mod.getClientBaritone().getCustomGoalProcess().isActive() && _wetTicks - _landPathAt >= LAND_LOOK_TICKS) {
                    // that path ended and we're still wet (no route, or the current won). try again, but not every tick
                    _landPathAt = _wetTicks;
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_landGoal);
                }
                return null;
            }
            boolean isCloseToMoveBack = _pos.closerToCenterThan(mod.getPlayer().position(), 2);
            if (isCloseToMoveBack) {
                if (!mod.getClientBaritone().getPathingBehavior().isPathing() && !mod.getPlayer().isInWater() &&
                        !mod.getFoodChain().needsToEat()) {
                    mod.getInputControls().hold(Input.MOVE_BACK);
                    mod.getInputControls().hold(Input.SNEAK);
                } else {
                    mod.getInputControls().release(Input.MOVE_BACK);
                    mod.getInputControls().release(Input.SNEAK);
                }
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                // GoalNear and GoalBlock both count standing on top of the block as arrived, which is the
                // one place we refuse to break it from (and the step-off task shoves us off of)
                Goal goal;
                BlockPos feet = mod.getClientBaritone().getPlayerContext().playerFeet();
                if (fromSide || looseColumn) {
                    // with a stack hanging on it the shaft two below is out as well, it comes down that shaft
                    goal = new GoalMineFromSide(_pos, !looseColumn, openBelow(mod, _pos));
                    if (goal.isInGoal(feet) && reach.isEmpty()) {
                        // "from the side" only looks at offsets, so a spot by the trunk with leaves in the way counts as
                        // arrived. the goal finished instantly every tick and we stood at the edge sneaking backwards for
                        // 49 seconds. same way out as the branch below: walk up and touch it
                        closeIn = true;
                        goal = pickGoal(mod.getWorld(), _pos, true);
                    }
                } else {
                    goal = pickGoal(mod.getWorld(), _pos, closeIn);
                    if (goal instanceof GoalReachBlock && goal.isInGoal(feet) && reach.isEmpty()) {
                        // already there and it still can't be hit (leaves, other logs, a wall), so touch it like before
                        closeIn = true;
                        goal = pickGoal(mod.getWorld(), _pos, true);
                    }
                }
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            }
        }
        return null;
    }

    /**
     * This method is called when the task is interrupted.
     * It cancels Baritone pathing and releases input controls if in game.
     *
     * @param mod The AltoClef mod instance
     * @param interruptTask The interrupting task
     */
    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Cancel Baritone pathing
        mod.getClientBaritone().getPathingBehavior().forceCancel();

        if (_pushedBehaviour) {
            _pushedBehaviour = false;
            if (mod.getBehaviour() != null) {
                mod.getBehaviour().pop();
            }
        }

        // If not in game, return
        if (!AltoClef.inGame()) {
            return;
        }

        // Release input controls
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_FORWARD);
    }

    /**
     * Check if the specified position is finished.
     *
     * @param mod The AltoClef instance
     * @return True if the block at the specified position is air, false otherwise.
     */
    @Override
    public boolean isFinished(AltoClef mod) {
        // Check if the world or position is null
        if (mod.getWorld() == null || _pos == null) {
            return false;
        }
        // Get the block state at the specified position and check if it's air
        BlockState blockState = mod.getWorld().getBlockState(_pos);
        if (!blockState.isAir()) {
            return false;
        }
        // air with a stack about to land in it is not a cleared block, it's a block that is about to have sand in it.
        // onTick owns the counters, so a column that never lands runs out of patience there
        boolean inFlight = WorldHelper.fallingInFlightOver(mod.getWorld(), _pos);
        boolean pending = !inFlight && WorldHelper.fallingAboutToDrop(mod.getWorld(), _pos);
        return !(inFlight && _fallWaited <= FALL_WAIT_MAX || pending && _dropPending <= DROP_PENDING_MAX);
    }

    /**
     * Overrides the isEqual method to compare with another Task object.
     *
     * @param other The other Task object to compare with.
     * @return true if the other object is a DestroyBlockTask and has the same position, false otherwise.
     */
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DestroyBlockTask destroyBlockTask) {
            return Objects.equals(destroyBlockTask._pos, _pos);
        }
        return false;
    }

    /**
     * Returns a debug string describing the block destruction action.
     * If the position is known, it includes the position in the string, otherwise it indicates an unknown position.
     */
    @Override
    protected String toDebugString() {
        if (_pos != null) {
            return "Destroy block at " + _pos.toShortString();
        } else {
            return "Destroy block at unknown position";
        }
    }

    @Override
    protected String toHudString() {
        try {
            Level level = Minecraft.getInstance().level;
            if (level != null && _pos != null && level.isLoaded(_pos)) {
                return "Breaking " + HudText.block(level.getBlockState(_pos).getBlock());
            }
        } catch (Throwable ignored) {
        }
        return "Breaking the block at " + HudText.pos(_pos);
    }
}
