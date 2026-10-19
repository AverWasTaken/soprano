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

package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderStallTest {

    private static final BetterBlockPos CELL = new BetterBlockPos(10, 70, 10);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // placed against the wall to the west, the same level allowed (something above the cell)
    private static BuilderProcess.GoalAdjacent adjacent(boolean solid) {
        return new BuilderProcess.GoalAdjacent(CELL, CELL.west(), true, solid);
    }

    @Test
    public void aSolidCellDoesntTakeFeetRightUnderIt() {
        assertFalse(adjacent(true).isInGoal(CELL.below()));
        // a torch doesn't care where our head is
        assertTrue(adjacent(false).isInGoal(CELL.below()));
    }

    @Test
    public void theRestOfAdjacentStillCounts() {
        BuilderProcess.GoalAdjacent goal = adjacent(true);
        assertFalse(goal.isInGoal(CELL));
        assertFalse(goal.isInGoal(CELL.west())); // the block we click on
        assertTrue(goal.isInGoal(CELL.east()));
        assertTrue(goal.isInGoal(CELL.north()));
        assertTrue(goal.isInGoal(CELL.east().below()));
        assertTrue(goal.isInGoal(CELL.above()));
        assertFalse(goal.isInGoal(CELL.below(2)));
    }

    @Test
    public void solidIsPartOfWhichGoalItIs() {
        // a flip between the two would cancel the path like any other goal change
        assertFalse(adjacent(true).equals(adjacent(false)));
        assertEquals(adjacent(true), adjacent(true));
        assertEquals(adjacent(true).hashCode(), adjacent(true).hashCode());
    }

    @Test
    public void nearIsWithinTheTrimDistance() {
        BetterBlockPos player = new BetterBlockPos(0, 64, 0);
        BetterBlockPos close = new BetterBlockPos(10, 64, 10); // 200, right on the edge
        BetterBlockPos far = new BetterBlockPos(11, 64, 10);
        List<BetterBlockPos> near = BuilderProcess.nearCells(List.of(close, far, player), player, BuilderProcess.NEAR_DIST_SQ);
        assertTrue(near.contains(close));
        assertTrue(near.contains(player));
        assertFalse(near.contains(far));
    }

    @Test
    public void theFarFallbackIsTheNearestFewPastTheEdge() {
        BlockPos player = new BlockPos(0, 64, 0);
        List<BetterBlockPos> all = new ArrayList<>();
        all.add(new BetterBlockPos(1, 64, 0)); // near, not a fallback
        for (int x = 100; x > 15; x--) {
            all.add(new BetterBlockPos(x, 64, 0));
        }
        List<BetterBlockPos> far = BuilderProcess.nearestFar(all, player, BuilderProcess.NEAR_DIST_SQ, 5);
        assertEquals(5, far.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(16 + i, far.get(i).x); // nearest first
        }
        // fewer far ones than k is all of them
        assertEquals(1, BuilderProcess.nearestFar(List.of(new BetterBlockPos(50, 64, 0), new BetterBlockPos(1, 64, 0)), player, BuilderProcess.NEAR_DIST_SQ, 48).size());
    }

    @Test
    public void theWatchdogGivesUpWhatThePathWasHeadedFor() {
        BetterBlockPos a = new BetterBlockPos(0, 64, 0);
        BetterBlockPos b = new BetterBlockPos(20, 64, 0);
        List<Pair<BetterBlockPos, Goal>> owners = List.of(new Pair<>(a, new GoalBlock(a.above())), new Pair<>(b, new GoalBlock(b.above())));
        BetterBlockPos feet = new BetterBlockPos(5, 64, 0);
        assertEquals(List.of(b), BuilderProcess.watchdogOwners(owners, b.above(), feet, 8));
        // no path, or it ended somewhere none of them wanted: the nearest by heuristic
        assertEquals(List.of(a), BuilderProcess.watchdogOwners(owners, null, feet, 1));
        assertEquals(List.of(a), BuilderProcess.watchdogOwners(owners, new BetterBlockPos(99, 64, 99), feet, 1));
    }

    @Test
    public void thePlannerTakesTheGoallessOnesBeforeTheStaleOnes() {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        BetterBlockPos feet = new BetterBlockPos(0, 64, 0);
        BuilderProcess.CellPlan stale = new BuilderProcess.CellPlan(stair, List.of(new PlacementPlanner.Feet(new BetterBlockPos(3, 64, 3), null)));
        BuilderProcess.CellPlan parked = new BuilderProcess.CellPlan(stair, List.of(new PlacementPlanner.Feet(feet, null)));
        BuilderProcess.CellPlan nowhere = new BuilderProcess.CellPlan(stair, List.of());
        assertEquals(0, BuilderProcess.planOrder(parked, stair, feet));
        assertEquals(1, BuilderProcess.planOrder(null, stair, feet));
        assertEquals(1, BuilderProcess.planOrder(nowhere, stair, feet));
        // planned for something else, it's got nothing to stand on for this one
        assertEquals(1, BuilderProcess.planOrder(stale, Blocks.STONE_STAIRS.defaultBlockState(), feet));
        assertEquals(2, BuilderProcess.planOrder(stale, stair, feet));
    }
}
