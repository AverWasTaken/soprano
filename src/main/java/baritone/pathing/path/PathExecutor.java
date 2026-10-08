/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.*;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

import static baritone.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
public class PathExecutor implements IPathExecutor, Helper {

    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final double MAX_DIST_FROM_PATH = 2;
    // anybody holding one of these needs the player exactly where the movement put them, so smoothing and shortcuts stay out
    private static final Input[] PRECISE_STEERING_INPUTS = {Input.CLICK_LEFT, Input.CLICK_RIGHT,
            Input.SNEAK, Input.JUMP, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT};

    /**
     * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
     * For more information, see issue #102.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/102">Issue #102</a>
     * @see <a href="https://i.imgur.com/5s5GLnI.png"></a>
     */
    private static final double MAX_TICKS_AWAY = 200;

    private final IPath path;
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean recalcBP = true;
    private HashSet<BlockPos> toBreak = new HashSet<>();
    private HashSet<BlockPos> toPlace = new HashSet<>();
    private HashSet<BlockPos> toWalkInto = new HashSet<>();

    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    private boolean sprintNextTick;
    // see carefulSprint
    private int carefulAt = -1;
    private boolean carefulLava;
    private SprintJump flight;
    private GroundShortcut groundShortcut;
    private int ticksOnShortcut;

    // the boat crossing we're in the middle of, if any. it owns the tick while it's alive
    private BoatTrip boat;
    // the last node of a run a trip gave up on, so we don't try again at every node while swimming it
    private int boatRefusedUntil = -1;
    // the stretches the renderer paints in the boat color, refreshed now and then since they depend on the world
    private List<int[]> boatRuns;
    private List<List<Vec3>> boatLanes;
    private long boatRunsComputedAt;

    public PathExecutor(PathingBehavior behavior, IPath path) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
    }

    /**
     * Tick this executor
     *
     * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
     * not sneaking out over lava), false otherwise
     */
    public boolean onTick() {
        if (flight != null && fly()) {
            return false; // mid air is not a stable state, no matter what the movement we left behind thinks
        }
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true; // stop bugging me, I'm done
        }
        // the boat goes first, and a shortcut already under way is never cut off for one. a shortcut can't carry on into water
        // either (clearSmoothingColumn says no to it) so it ends on land and the next tick gets to ask about the boat
        if (boat == null && groundShortcut == null) {
            boat = BoatTrip.plan(behavior.baritone, path, pathPosition, boatRefusedUntil);
        }
        if (boat != null) {
            // everything below assumes feet on the path. in a boat the feet are wherever the boat says, so
            // the trip does all the steering and we only come back here once it's over
            switch (boat.tick()) {
                case CONTINUE:
                    sprintNextTick = false; // nobody's sprinting in a boat
                    // keep the position moving with the boat: plan ahead measures the ticks left in this
                    // segment from here, and with it stuck on the shore node the next segment never got
                    // calculated until we'd already stopped at the end of this one
                    pathPosition = Math.max(pathPosition, Math.min(boat.currentPosition(), path.length() - 2));
                    return boat.safeToCancel();
                case DONE: {
                    int end = boat.endPosition();
                    for (int j = pathPosition; j <= end && j < path.length() - 1; j++) {
                        path.movements().get(j).reset();
                    }
                    pathPosition = end;
                    boat = null;
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                case ABORT:
                    boatRefusedUntil = boat.endPosition();
                    boat = null;
                    onChangeInPathPosition();
                    break; // carry on on foot from wherever we ended up, the resync below sorts out where that is
                default:
                    throw new IllegalStateException();
            }
        }
        if (groundShortcut != null) {
            return tickGroundShortcut();
        }
        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos whereAmI = ctx.playerFeet();
        if (!movement.getValidPositions().contains(whereAmI)) {
            for (int i = 0; i < pathPosition && i < path.length(); i++) {//this happens for example when you lag out and get teleported back a couple blocks
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    int previousPos = pathPosition;
                    pathPosition = i;
                    for (int j = pathPosition; j <= previousPos; j++) {
                        path.movements().get(j).reset();
                    }
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
            for (int i = pathPosition + 3; i < path.length() - 1; i++) { //dont check pathPosition+1. the movement tells us when it's done (e.g. sneak placing)
                // also don't check pathPosition+2 because reasons
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    if (i - pathPosition > 2) {
                        logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                    }
                    //System.out.println("Double skip sundae");
                    pathPosition = i - 1;
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
        }
        Tuple<Double, BlockPos> status = closestPathPos(path);
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.getA() + ". Threshold: " + MAX_DIST_FROM_PATH);
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) { // ok, stop right away, we're way too far.
            logDebug("too far from path");
            cancel();
            return false;
        }
        //long start = System.nanoTime() / 1000000L;
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = (Movement) path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(bsi);
            List<BlockPos> prevPlace = m.toPlace(bsi);
            List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(bsi))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(bsi))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = (Movement) path.movements().get(i);
                newBreak.addAll(m.toBreak(bsi));
                newPlace.addAll(m.toPlace(bsi));
                newWalkInto.addAll(m.toWalkInto(bsi));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        /*long end = System.nanoTime() / 1000000L;
        if (end - start > 0) {
            System.out.println("Recalculating break and place took " + (end - start) + "ms");
        }*/
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                clearKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
            currentMovementOriginalCostEstimate = movement.getCost();
            for (int i = 1; i < Baritone.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > Baritone.settings().maxCostIncrease.value && canCancel) {
            // don't do this if the movement was calculated while loaded
            // that means that this isn't a cache error, it's just part of the path interfering with a later part
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return true;
        }
        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            clearKeys();
            return true;
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
            logDebug("Movement returns status " + movementStatus);
            cancel();
            return true;
        }
        if (movementStatus == SUCCESS) {
            //System.out.println("Movement done, next path");
            pathPosition++;
            onChangeInPathPosition();
            onTick();
            return true;
        } else {
            if (movementStatus == RUNNING) {
                if (startGroundShortcut(movement, bsi)) {
                    return tickGroundShortcut();
                }
                smoothSteering(movement, bsi);
                cornerSteering(movement, bsi);
            }
            sprintNextTick = shouldSprintNextTick();
            if (!sprintNextTick) {
                ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate + Baritone.settings().movementTimeoutTicks.value) {
                // only cancel if the total time has exceeded the initial estimate
                // as you break the blocks required, the remaining cost goes down, to the point where
                // ticksOnCurrent is greater than recalculateCost + 100
                // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
                logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
                cancel();
                return true;
            }
        }
        return canCancel && flight == null; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }

    private void smoothSteering(Movement movement, BlockStateInterface bsi) {
        if (!ExperimentalMovement.preferFasterPathing() || !canSteerOnGround(movement)) {
            return;
        }
        Vec3 target = PathSmoothing.lookAhead(path.positions(), pathPosition, ctx.player().position(), i -> {
            IMovement next = path.movements().get(i);
            return next instanceof MovementTraverse || next instanceof MovementDiagonal;
        }, pos -> clearSmoothingColumn(bsi, pos));
        if (target != null) {
            behavior.baritone.getLookBehavior().updateTarget(
                    RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations())
                            .withPitch(ctx.playerRotations().getPitch()), false);
        }
    }

    // smoothing and shortcuts only ever touch plain walking. parkour, neos and climbs need the exact spot their sim was
    // run from, and anything holding the crosshair or a precise key needs it too
    private boolean canSteerOnGround(Movement movement) {
        if (!(movement instanceof MovementTraverse || movement instanceof MovementDiagonal) || flight != null
                || !ctx.player().onGround() || ctx.player().isInWater() || ctx.player().isPassenger() || ctx.player().onClimbable()
                || ctx.player().horizontalCollision || movement.isTargetingBlock()
                || !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)) {
            return false;
        }
        for (Input input : PRECISE_STEERING_INPUTS) {
            if (behavior.baritone.getInputOverrideHandler().isInputForcedDown(input)) {
                return false;
            }
        }
        return true;
    }

    private boolean startGroundShortcut(Movement movement, BlockStateInterface bsi) {
        if (!ExperimentalMovement.allowGroundShortcuts() || !canSteerOnGround(movement)
                || !movement.safeToCancel() || ctx.player().getBbWidth() > 0.6F
                || ctx.player().getBbHeight() > 1.8F) {
            return false;
        }
        Map<BlockPos, Boolean> clearance = new HashMap<>();
        groundShortcut = GroundShortcut.find(path.positions(), pathPosition, ctx.player().position(), i -> {
            Movement next = (Movement) path.movements().get(i);
            if (!(next instanceof MovementTraverse || next instanceof MovementDiagonal)) {
                return false;
            }
            next.resetBlockCache();
            return next.toBreak(bsi).isEmpty() && next.toPlace(bsi).isEmpty() && next.toWalkInto(bsi).isEmpty()
                    && clearSmoothingColumn(bsi, next.getSrc()) && clearSmoothingColumn(bsi, next.getDest());
        }, pos -> clearance.computeIfAbsent(pos, p -> clearSmoothingColumn(bsi, p)));
        if (groundShortcut != null && yieldsToSprintJump()) {
            groundShortcut = null; // asked last on purpose, planning a jump is the expensive question
        }
        ticksOnShortcut = 0;
        return groundShortcut != null;
    }

    // if a sprint jump would take off from right here, it is the faster way along this run and it gets the tick. same
    // rule the 1.19.4 version had, it just asks SprintJump now
    private boolean yieldsToSprintJump() {
        return ExperimentalMovement.sprintJumping()
                && !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)
                && SprintJump.plan(ctx, path, pathPosition) != null;
    }

    private boolean tickGroundShortcut() {
        Vec3 player = ctx.player().position();
        Vec3 velocity = ctx.player().getDeltaMovement();
        BlockStateInterface bsi = behavior.baritone.bsi;
        Map<BlockPos, Boolean> clearance = new HashMap<>();
        if (!ExperimentalMovement.allowGroundShortcuts() || !ctx.player().onGround()
                || ctx.player().isInWater() || ctx.player().onClimbable() || ctx.player().horizontalCollision
                || !groundShortcut.canContinue(player, velocity,
                pos -> clearance.computeIfAbsent(pos, p -> clearSmoothingColumn(bsi, p)))) {
            // we're between the original waypoints. replan from here instead of backtracking toward
            // a skipped node or handing a diagonal movement a position it cannot execute from.
            logDebug("Ground shortcut interrupted; recalculating from current position");
            cancel();
            return true;
        }
        if (groundShortcut.arrived(player, velocity)) {
            pathPosition = groundShortcut.endIndex;
            groundShortcut = null;
            recalcBP = true;
            onChangeInPathPosition();
            onTick();
            return true;
        }
        if (++ticksOnShortcut > groundShortcut.start.distanceTo(groundShortcut.target)
                * ActionCosts.WALK_ONE_BLOCK_COST + Baritone.settings().movementTimeoutTicks.value) {
            logDebug("Ground shortcut took too long");
            cancel();
            return true;
        }
        clearKeys();
        Rotation desired = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), groundShortcut.target,
                ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch());
        Rotation actual = behavior.baritone.getLookBehavior().getAimProcessor().peekRotation(desired);
        double yawError = Math.abs(Rotation.normalizeYaw(actual.getYaw() - desired.getYaw()));
        boolean forward = yawError < 10 && groundShortcut.shouldMoveForward(player, velocity);
        if (forward) {
            double yaw = Math.toRadians(actual.getYaw());
            Vec3 projected = player.add(velocity.x * 2.5 - Math.sin(yaw) * 0.5, 0,
                    velocity.z * 2.5 + Math.cos(yaw) * 0.5);
            forward = GroundShortcut.clearSegment(player, projected,
                    pos -> clearance.computeIfAbsent(pos, p -> clearSmoothingColumn(bsi, p)));
        }
        behavior.baritone.getLookBehavior().updateTarget(desired, false);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, forward);
        sprintNextTick = forward && player.distanceTo(groundShortcut.target) > 1.5
                && Baritone.settings().allowSprint.value && ctx.player().getFoodData().getFoodLevel() > 6;
        if (!sprintNextTick) {
            ctx.player().setSprinting(false);
        }
        return true; // all ground under our footprint was verified this tick, so pausing is safe
    }

    private boolean clearSmoothingColumn(BlockStateInterface bsi, BlockPos pos) {
        if (Baritone.settings().pathThroughCachedOnly.value || !bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
            return false;
        }
        BlockPos floor = pos.below();
        BlockState support = bsi.get0(floor);
        Block block = support.getBlock();
        if (!support.getFluidState().isEmpty() || !support.isCollisionShapeFullBlock(bsi.access, floor)
                || !MovementHelper.canWalkOn(bsi, floor.getX(), floor.getY(), floor.getZ(), support)
                || block == Blocks.MAGMA_BLOCK || block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE
                || block == Blocks.SLIME_BLOCK || block == Blocks.HONEY_BLOCK) {
            return false;
        }
        for (int y = 0; y < 2; y++) {
            BlockPos body = pos.above(y);
            BlockState state = bsi.get0(body);
            if (MovementHelper.avoidWalkingInto(state) || state.is(Blocks.WITHER_ROSE)
                    || state.is(Blocks.POWDER_SNOW) || MovementHelper.isClimbable(state.getBlock()) || !state.getFluidState().isEmpty()
                    || !state.getCollisionShape(bsi.access, body).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Tuple<Double, BlockPos> closestPathPos(IPath path) {
        double best = -1;
        BlockPos bestPos = null;
        for (IMovement movement : path.movements()) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Tuple<>(best, bestPos);
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (!current.isPresent()) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (!currentBest.isPresent()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Tuple<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.getA();
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof MovementFall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Regardless of current path position, snap to the current player feet if possible
     *
     * @return Whether or not it was possible to snap to the current player feet
     */
    public boolean snipsnapifpossible() {
        if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getDeltaMovement().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick() {
        boolean requested = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        // same thing CalculationContext.canSprint works out. this used to build a whole context every tick to read it,
        // which means a chunk provider, a ToolSet, an inventory scan and two enchantment scans for one boolean
        if (!Baritone.settings().allowSprint.value || ctx.player().getFoodData().getFoodLevel() <= 6) {
            return false;
        }
        IMovement current = path.movements().get(pathPosition);

        if (ExperimentalMovement.sprintJumping() && !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)
                && (flight = SprintJump.plan(ctx, path, pathPosition)) != null) {
            steer(true);
            return true;
        }

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend && sprintableAscend(ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
                if (skipNow(ctx, current)) {
                    logDebug("Skipping traverse to straight ascend");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    return true;
                } else {
                    logDebug("Too far to the side to safely sprint ascend");
                }
            }
        }

        // if the movement requested sprinting, then we're done
        if (requested) {
            if (ExperimentalMovement.headHitters()) {
                headHitJump(current);
            }
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
        if (current instanceof MovementDescend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since MovementDescend can't know the next movement we have to tell it
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway() && next instanceof MovementParkour; // traverse doesn't react fast enough
                        // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return false;
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend && current.getDirection().above().equals(next.getDirection().below())) {
                    // a descend then an ascend in the same direction
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return true;
                }
                if (canSprintFromDescendInto(ctx, current, next) || sprintsPastLanding(current, next, 1)) {

                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof MovementDescend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return false;
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick();
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())) {
                BlockPos center = current.getSrc().above();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().position().y >= center.getY() - 0.07) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse && sprintableAscend(ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall) {
            Tuple<Vec3, BlockPos> data = overrideFall((MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.getB());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException(String.format(
                            "Fall override at %s %s %s returned illegal destination %s %s %s",
                            current.getSrc(), fallDest));
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                clearKeys();
                // we are not ticking the movement, so we gotta do this ourselves
                BetterBlockPos src = current.getSrc();
                BetterBlockPos dest = current.getDest();
                MovementState fakeState = new MovementState();
                if (!MovementHelper.openDoors(ctx, fakeState, src, new BetterBlockPos(dest.x, src.y, dest.z))) {
                    boolean forceRotations = fakeState.getTarget().hasToForceRotations();
                    fakeState.getTarget().getRotation().ifPresent(rotation ->
                            behavior.baritone.getLookBehavior().updateTarget(rotation, forceRotations));
                    fakeState.getInputStates().forEach(behavior.baritone.getInputOverrideHandler()::setInputForceState);
                    fakeState.getInputStates().clear();
                    return true;
                }
                behavior.baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.getA(), ctx.playerRotations()), false);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
            // overrideFall only knows straight traverses, this is every other way the path can carry on from the landing
            return sprintsThroughFall((MovementFall) current);
        }
        return false;
    }

    // a small drop, no bucket and no clutch in sight, and the path goes on roughly the way we were going
    private boolean sprintsThroughFall(MovementFall fall) {
        int drop = -fall.getDirection().getY();
        if (!Baritone.settings().sprintThroughDescends.value || drop < 2 || drop > ExperimentalMovement.SAFE_FALL
                || drop > Baritone.settings().maxFallHeightNoWater.value || pathPosition >= path.length() - 3) {
            return false; // anything that is not a plain safe fall lands somewhere it wants a say in (it centers on the landing)
        }
        return sprintsPastLanding(fall, path.movements().get(pathPosition + 1), drop);
    }

    // the descend rules above only know "straight on". this one asks what the cells we would coast over actually are
    private boolean sprintsPastLanding(IMovement current, IMovement next, int plannedDrop) {
        if (!Baritone.settings().sprintThroughDescends.value || pathPosition >= path.length() - 3) {
            return false; // the last movement is the goal, and nobody wants to be sprinting past that
        }
        if (current instanceof MovementDescend && ((MovementDescend) current).skipToAscend()) {
            return false; // the weird overshoot glitch has its own handling
        }
        boolean walksOn = next instanceof MovementTraverse || next instanceof MovementDescend
                || next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
        BlockPos dir = current.getDirection();
        BlockPos nextDir = next.getDirection();
        if (!walksOn || nextDir.getY() > 0 || Math.abs(dir.getX()) + Math.abs(dir.getZ()) != 1 || carefulSprint()) {
            return false;
        }
        BlockPos land = current.getDest();
        double turn = SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ());
        return SprintPolicy.descendKeepsSprint(false, turn,
                SprintPolicy.overshootSafe(new WorldTerrain(behavior.baritone.bsi), land.getX(), land.getY(), land.getZ(), dir.getX(), dir.getZ(), plannedDrop));
    }

    // one answer to "should we do this the old careful way" for every rule that sprints where it used to stop: the nether,
    // lava near where we are going, low health, or something close by placing / breaking (bridging, pillaring, parkour places)
    private boolean carefulSprint() {
        if (carefulAt != pathPosition) {
            carefulAt = pathPosition; // 4 lava scans are a lot to do every tick, the path only changes under us once per movement
            List<BlockPos> centers = new ArrayList<>();
            centers.add(ctx.playerFeet());
            for (int i = pathPosition; i <= pathPosition + 2 && i < path.movements().size(); i++) {
                centers.add(path.movements().get(i).getDest());
            }
            carefulLava = SprintPolicy.lavaNear(new WorldTerrain(behavior.baritone.bsi), centers);
        }
        return SprintPolicy.careful(ctx.world().dimension() == Level.NETHER, carefulLava, ctx.player().getHealth(), placingNearby());
    }

    private boolean placingNearby() {
        BlockStateInterface bsi = behavior.baritone.bsi;
        for (int i = pathPosition; i <= pathPosition + 2 && i < path.movements().size(); i++) {
            Movement movement = (Movement) path.movements().get(i);
            if (movement instanceof MovementPillar || !movement.toPlace(bsi).isEmpty() || !movement.toBreak(bsi).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // turn a little before the corner instead of at it. a movement is done the moment we step into its dest block, and at
    // sprint speed the old heading carries a third of a block past that, so the hitbox leans on the wall on the inside of
    // the bend, vanilla calls that a collision, and a collision ends the sprint
    private void cornerSteering(Movement movement, BlockStateInterface bsi) {
        if (!Baritone.settings().sprintThroughCorners.value || pathPosition >= path.movements().size() - 1
                || !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT) || !canSteerOnGround(movement)) {
            return; // not sprinting into it means there is no momentum to steer, and the movement knows best
        }
        IMovement next = path.movements().get(pathPosition + 1);
        if (!flatWalk(movement, bsi) || !flatWalk(next, bsi)) {
            return;
        }
        BlockPos dir = movement.getDirection();
        BlockPos nextDir = next.getDirection();
        double turn = SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ());
        if (!SprintPolicy.preTurnable(turn)) {
            return;
        }
        Vec3 position = ctx.player().position();
        double[] aim = SprintPolicy.cornerAim(position.x, position.z, movement.getSrc(), movement.getDest(), next.getDest());
        if (aim == null || carefulSprint()) {
            return;
        }
        boolean tight = !SprintPolicy.cornerBoxClear(movement.getSrc(), movement.getDest(), next.getDest(), pos -> clearSmoothingColumn(bsi, pos));
        boolean hazards = SprintPolicy.hazardAround(new WorldTerrain(bsi), movement.getDest());
        if (!SprintPolicy.cornerKeepsSprint(false, turn, hazards, tight)) {
            return;
        }
        behavior.baritone.getLookBehavior().updateTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(aim[0], movement.getDest().y, aim[1]), ctx.playerRotations())
                        .withPitch(ctx.playerRotations().getPitch()), false);
    }

    // flat walking with nothing to break or place on the way
    private boolean flatWalk(IMovement movement, BlockStateInterface bsi) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().y == movement.getDest().y
                && ((Movement) movement).toPlace(bsi).isEmpty() && ((Movement) movement).toBreak(bsi).isEmpty();
    }

    // what the sprint rules want to know about blocks, answered from the live world
    private static final class WorldTerrain implements SprintPolicy.Terrain {

        private final BlockStateInterface bsi;

        private WorldTerrain(BlockStateInterface bsi) {
            this.bsi = bsi;
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            BlockState state = bsi.get0(x, y, z);
            return MovementHelper.avoidWalkingInto(state) || MovementHelper.isLava(state) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE);
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return MovementHelper.isLava(bsi.get0(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return MovementHelper.canWalkThrough(bsi, x, y, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return MovementHelper.canWalkOn(bsi, x, y, z);
        }
    }

    /**
     * @return true if we're still in the air and this tick is handled
     */
    private boolean fly() {
        if (!ctx.player().onGround() && !ctx.player().isInWater() && !ctx.player().isInLava() && !ctx.player().onClimbable()
                && flight.ticks < SprintJump.MAX_TICKS && ExperimentalMovement.sprintJumping()) {
            steer(false);
            sprintNextTick = true;
            return true;
        }
        // we probably flew over a few movements, pick up at whichever one we came down in (diagonal side cells count).
        // anywhere else and the usual valid positions / off path recovery takes it from here
        for (int i = pathPosition; i < Math.min(path.movements().size(), pathPosition + flight.floors.length); i++) {
            if (((Movement) path.movements().get(i)).getValidPositions().contains(ctx.playerFeet())) {
                pathPosition = i;
                break;
            }
        }
        flight = null;
        onChangeInPathPosition();
        return false;
    }

    private void steer(boolean takeoff) {
        // the movements steer at their own dest and drop W the moment our feet are a block up ("Wrong Y coordinate"),
        // which was the mid air stall, so while we're up here nobody else gets a say. space only on takeoff: letting go
        // in the air resets the vanilla 10 tick jump delay, holding it made a jump up a step (~9 ticks) wait on landing
        clearKeys();
        behavior.baritone.getLookBehavior().updateTarget(flight.steer(ctx), false);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, takeoff);
    }

    /**
     * Sprint jump into a low ceiling (1x2 corridors, overhangs, etc.) for a bit of extra speed.
     * The sprint jump boost applies on the first ticks of the jump, before we bonk our head on the ceiling.
     * This runs after movement.update() has cleared and reasserted the forced inputs,
     * so a jump forced here lasts exactly one tick.
     */
    private void headHitJump(IMovement current) {
        if (!canStartHeadHitting(current) || !underHeadBonkCeiling(current.getDirection(), ctx.playerFeet()) || !clearOfLedgesAhead(current.getDirection())) {
            return;
        }
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    }

    private boolean canStartHeadHitting(IMovement current) {
        if (!(current instanceof MovementTraverse || current instanceof MovementDiagonal) || current.getDirection().getY() != 0) {
            return false; // head hitting only applies to flat walking movements
        }
        if (current instanceof MovementDiagonal && !Baritone.settings().headHittersDiagonal.value) {
            return false;
        }
        if (!ctx.player().onGround() || MovementHelper.isLiquid(ctx, ctx.playerFeet())) {
            return false;
        }
        if (!ctx.player().onGround() || MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().isInWater()) {
            return false; // hopping in water or on a vine just sticks us to it instead
        }
        if (((Movement) current).toBreakCached == null || !((Movement) current).toBreakCached.isEmpty()) {
            return false; // breaking is like 5x slower when you're jumping
        }
        // not while sneaking either, e.g. walking on magma, a jump would break the sneak and the edge safety with it
        return !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK);
    }

    private boolean underHeadBonkCeiling(BlockPos dir, BetterBlockPos feet) {
        BlockPos ceiling = feet.above(2);
        if (MovementHelper.fullyPassable(ctx, ceiling) || !MovementHelper.isBlockNormalCube(ctx.world().getBlockState(ceiling))) {
            return false; // not under a ceiling yet, or the thing overhead is something like a trapdoor that we can't reliably bonk against
        }
        // diagonals can enter through either face, so clear each axis separately
        return clearOfCeilingEntrance(feet, dir.getX(), 0) && clearOfCeilingEntrance(feet, 0, dir.getZ());
    }

    private boolean clearOfCeilingEntrance(BetterBlockPos feet, int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return true;
        }
        // make sure we're fully inside the corridor before we start jumping, same idea as skipNow
        BlockPos behind = feet.offset(-dx, 0, -dz).above(2);
        if (MovementHelper.fullyPassable(ctx, behind)) {
            double flatDist = Math.abs(dx * (behind.getX() + 0.5D - ctx.player().position().x)) + Math.abs(dz * (behind.getZ() + 0.5D - ctx.player().position().z));
            return flatDist >= 0.8; // just entered, wait until we're clear of the entrance face
        }
        return true;
    }

    private boolean clearOfLedgesAhead(BlockPos dir) {
        // momentum from the head bonk can carry us an extra block or two, so don't headhit unless the next two blocks
        // in this direction are also part of the path, that way momentum can never send us off it
        for (int i = 1; i <= 2; i++) {
            if (pathPosition + i > path.length() - 2 || !path.movements().get(pathPosition + i).getDirection().equals(dir)) {
                return false; // the path turns or ends within two blocks, don't add any momentum
            }
            Movement next = (Movement) path.movements().get(pathPosition + i);
            if (next.toPlaceCached != null && !next.toPlaceCached.isEmpty()) {
                return false; // the movement is going to place its own support, and momentum can arrive before those blocks do
            }
            BlockPos floor = next.getDest().below();
            if (MovementHelper.isLiquid(ctx, floor) || !MovementHelper.canWalkOn(ctx, floor)) {
                return false; // no real support under the destination yet: liquid (frostwalker ice hasn't frozen yet) or passable (ladders/vines have no floor at all), and momentum can't wait for it to appear
            }
        }
        return true;
    }

    private Tuple<Vec3, BlockPos> overrideFall(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Tuple<>(
                new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current) {
        double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().position().z)) + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().position().x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
        if (!Baritone.settings().sprintAscends.value) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
            return false;
        }
        if (!next.toBreakCached.isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().above(3)) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().above(2))) {
            return false; // sprinting would carry us into somewhere altoclef wants us to stay out of
        }
        return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2))); // codacy smh my head
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
    }

    private void clearKeys() {
        // i'm just sick and tired of this snippet being everywhere lol
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
    }

    private void cancel() {
        groundShortcut = null;
        sprintNextTick = false;
        clearKeys();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (groundShortcut != null) {
            return this; // finish the short ground segment before replacing its executor and indices
        }
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after splicing",
                        path.getDest(), next.getPath().getDest()));
            }
            PathExecutor ret = new PathExecutor(behavior, path);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            ret.flight = flight; // a new path showing up doesn't make us any less airborne
            // a splice keeps every index of the first path, so the trip carries straight over. losing it
            // mid placement would have the new executor place a second boat
            if (boat != null) {
                boat.rebase(path, 0);
                ret.boat = boat;
            }
            ret.boatRefusedUntil = boatRefusedUntil;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    // the lane the renderer draws for each of boatRuns(), same order, cached alongside them
    public List<Vec3> boatLane(int[] run) {
        boatRuns();
        for (int i = 0; i < boatRuns.size(); i++) {
            if (boatRuns.get(i) == run) {
                return boatLanes.get(i);
            }
        }
        return BoatTrip.smoothLane(path.positions(), run[0], run[1]);
    }

    // the stretches of this path that will be crossed by boat, as [first land index, last water index] pairs.
    // for the renderer, which asks every frame, so it's only recomputed twice a second
    public List<int[]> boatRuns() {
        long now = System.currentTimeMillis();
        if (boatRuns == null || now - boatRunsComputedAt > 500) {
            boatRuns = BoatTrip.runs(behavior.baritone, path);
            // the drawn lanes too, the renderer was rebuilding them every frame
            boatLanes = new ArrayList<>();
            for (int[] run : boatRuns) {
                boatLanes.add(BoatTrip.smoothLane(path.positions(), run[0], run[1]));
            }
            boatRunsComputedAt = now;
        }
        if (boatRefusedUntil < 0) {
            return boatRuns;
        }
        // a run we gave up on goes back to being drawn as a swim, since that's what it is now
        List<int[]> stillOn = new ArrayList<>();
        for (int[] run : boatRuns) {
            if (run[1] > boatRefusedUntil) {
                stillOn.add(run);
            }
        }
        return stillOn;
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > Baritone.settings().maxPathHistoryLength.value) {
            int cutoffAmt = Baritone.settings().pathHistoryCutoffAmount.value;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after trimming its start",
                        newPath.getDest(), path.getDest()));
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            PathExecutor ret = new PathExecutor(behavior, newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            ret.flight = flight;
            if (boat != null) {
                boat.rebase(newPath, cutoffAmt);
                ret.boat = boat;
            }
            ret.boatRefusedUntil = boatRefusedUntil < 0 ? -1 : boatRefusedUntil - cutoffAmt;
            return ret;
        }
        return this;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }
}
