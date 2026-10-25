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

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;

    // how much the last calculate0 chewed through. written at the end because the loop likes its locals
    public int numNodesConsidered;
    public int numMovementsConsidered;

    // this many cells along a remembered way (RouteMemory), the search has a part path worth stopping for
    static final int ROUTE_PROGRESS = 12;
    // what landing on a block the remembered trail never stood on costs (CalculationContext.trailFloors): five seconds
    static final double OFF_TRAIL_FLOOR = 100;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        // the vertical part of the heuristic is a lower bound on what a descent costs, and that depends on whether we can
        // actually fall far (a bucket, a clutch item). goals don't get to see the context so the thread carries it
        GoalYLevel.setDescentTicksPerBlock(calcContext.descentTicksPerBlock());
        try {
            return search(primaryTimeout, failureTimeout);
        } finally {
            GoalYLevel.resetDescentTicksPerBlock();
        }
    }

    private Optional<IPath> search(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.minY;
        int maxY = calcContext.maxY;
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        double[] bestLowHeuristicSoFar = new double[COEFFICIENTS.length]; // the same over every node, however low (see partialFloorY)
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestLowHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
            bestLowSoFar[i] = startNode;
        }
        int partialFloorY = this.partialFloorY;
        int avoidBelowY = calcContext.avoidBelowY;
        baritone.behavior.RouteMemory.Guide guide = calcContext.routeGuide;
        java.util.Set<net.minecraft.world.level.block.Block> trailFloors = calcContext.trailFloors;
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = calcContext.worldBorder;
        long startTime = System.currentTimeMillis();
        boolean slowPath = Baritone.settings().slowPath.value;
        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int offFloorSkips = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.values();
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (!slowPath && isWaitedOn()) {
                    primaryTimeoutTime = capIfWaitedOn(primaryTimeoutTime, startTime, Baritone.settings().primaryTimeoutMS.value);
                    failureTimeoutTime = capIfWaitedOn(failureTimeoutTime, startTime, Baritone.settings().failureTimeoutMS.value);
                }
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                this.numNodesConsidered = numNodes;
                this.numMovementsConsidered = numMovementsConsidered;
                if (!quiet) {
                    logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                }
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            // standing on the rim of a pillar next door (nothing under this cell): only a Tungsten jump goes from there, the
            // grid moves think there's a floor
            boolean rim = calcContext.allowTungstenJumps && (baritone.pathing.tungsten.TungstenJumps.rimNode(calcContext, currentNode.x, currentNode.y, currentNode.z)
                    || baritone.pathing.tungsten.TungstenJumps.waterCell(calcContext, currentNode.x, currentNode.y, currentNode.z)
                    && !MovementHelper.canWalkOn(calcContext, currentNode.x, currentNode.y - 1, currentNode.z));
            if (calcContext.allowTungstenJumps) {
                // which way the jump in came, for the jumps that carry its speed on (TungstenJumps.Jump.carry)
                PathNode prev = currentNode.previous;
                calcContext.tungsten().arriving(prev == null ? (numNodes == 1 ? calcContext.startArrival : -1)
                        : baritone.pathing.tungsten.TungstenJumps.carrySector(calcContext, prev.x, prev.y, prev.z, currentNode.x, currentNode.y, currentNode.z));
            }
            long missesBefore = baritone.pathing.movement.movements.PendingTables.misses();
            for (Moves moves : allMoves) {
                if (rim && !moves.tungsten) {
                    continue;
                }
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset > maxY || currentNode.y + moves.yOffset < minY) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s calculated implausible cost %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            actionCost));
                }
                // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at x z %s %s instead of %s %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.x),
                            SettingsUtil.maybeCensor(res.z),
                            SettingsUtil.maybeCensor(newX),
                            SettingsUtil.maybeCensor(newZ)));
                }
                if (baritone.api.utils.IPlayerContext.smallFloor(calcContext.get(res.x, res.y, res.z).getBlock())) {
                    // in the cell of a candle, a pot, a head: standing on it counts as the cell over it (playerFeet), so a
                    // node in it is one the bot can never be seen to reach (a candle had it stuck on a "move down" for good)
                    continue;
                }
                if (res.y < avoidBelowY && res.y < startY
                        || baritone.behavior.FallFloor.deadly(calcContext.get(res.x, res.y - 1, res.z).getBlock(), res.x, res.y, res.z)) {
                    continue; // down on the floor a parkour level resets you from (Settings.avoidBelowY, FallFloor)
                }
                boolean offFloor = trailFloors != null && baritone.pathing.tungsten.TungstenJumps.offTrailFloor(calcContext, res.x, res.y, res.z);
                if (offFloor && (guide == null || guide.along(res.x, res.y, res.z) < 0)) {
                    if (++offFloorSkips <= 6 && System.getenv("SOPRANO_FLOOR_LOG") != null) {
                        System.out.println("[floors] off: " + res.x + " " + res.y + " " + res.z + " on " + calcContext.get(res.x, res.y - 1, res.z).getBlock() + " in " + calcContext.get(res.x, res.y, res.z).getBlock() + " from " + currentNode.x + " " + currentNode.y + " " + currentNode.z + " " + moves);
                    }
                    // somewhere the trail never went, on something it never stood on: on a course where everything but
                    // the course puts you back, not somewhere to go at all (any price for it gets lost in a part path's
                    // cost coefficients, and the dam's floor is the easy way to the end). if it's what the course is
                    // after all, the fall floor learning is what finds out the other way round
                    continue;
                }
                if (offFloor) {
                    // on a remembered trail, landing on something it never stood on (a course's glass, a pillar of the wrong
                    // wood) is likely what puts you back: only if there's no way over what it did stand on. (where it went
                    // itself is fine, whatever it is: the start's floor, a block it only stood on once)
                    actionCost += OFF_TRAIL_FLOOR;
                }
                if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at y %s instead of %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.y),
                            SettingsUtil.maybeCensor(currentNode.y + moves.yOffset)));
                }
                // a move a swimmer just got pinned on, don't hand it right back
                actionCost += calcContext.swimStalls.penalty(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z);
                // or one the executor just gave up on, see FailedMoves
                actionCost += calcContext.failedMoves.penalty(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z);
                if (moves.tungsten) {
                    // or a jump that's kept missing in the game, sessions ago or now (RouteMemory)
                    actionCost += baritone.behavior.RouteMemory.penalty(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z);
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                if (isFavoring) {
                    // see issue #18
                    // favoring only cares where you end up, not where you came from
                    // so look it up once per node instead of 22 times
                    if (neighbor.favor == 0) {
                        neighbor.favor = favoring.calculate(hashCode);
                    }
                    actionCost *= neighbor.favor;
                }
                // and don't plan to stand in a nether portal unless that's the goal, see PortalAvoidance. after the favoring so
                // a backtracking discount doesn't shrink it, it's a flat 80 ticks of standing there
                actionCost += PortalAvoidance.penalty(goal, calcContext, res.x, res.y, res.z);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    boolean high = neighbor.y >= partialFloorY;
                    if (failing && guide != null && guide.along(neighbor.x, neighbor.y, neighbor.z) >= Math.min(guide.length(), ROUTE_PROGRESS)) {
                        failing = false; // well along a remembered way: that's a part path worth having (bestSoFar takes it)
                    }
                    // (and never the end of a part path on something the trail never stood on: with the cost coefficients
                    // that pick a part path, the price of the glass all but goes, and a dam's floor runs right to the end)
                    for (int i = 0; i < COEFFICIENTS.length && !offFloor; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost * INVERSE_COEFFICIENTS[i];
                        if (high && bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                        if (bestLowHeuristicSoFar[i] - heuristic > minimumImprovement) {
                            bestLowHeuristicSoFar[i] = heuristic;
                            bestLowSoFar[i] = neighbor;
                        }
                    }
                }
            }
            if (baritone.pathing.movement.movements.PendingTables.misses() != missesBefore) {
                currentNode.pending = true;
            }
        }
        this.numNodesConsidered = numNodes;
        this.numMovementsConsidered = numMovementsConsidered;
        // the open set running dry is the only way out of this loop that proves anything. a timeout, a cancel, or a peek past
        // the loaded chunks all leave world we never looked at
        this.exhausted = openSet.isEmpty() && numEmptyChunk == 0 && !cancelRequested;
        if (cancelRequested) {
            return Optional.empty();
        }
        if (!quiet) {
            System.out.println(numMovementsConsidered + " movements considered" + (trailFloors != null ? ", " + offFloorSkips + " landings off the trail's floors (" + trailFloors.size() + " kinds" + (guide == null ? ", no guide" : "") + ")" : ""));
            System.out.println("Open set size: " + openSet.size());
            System.out.println("PathNode map size: " + mapSize());
            System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
        }
        Optional<IPath> result = bestSoFar(!quiet, numNodes);
        if (result.isPresent() && !quiet) {
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }
}
