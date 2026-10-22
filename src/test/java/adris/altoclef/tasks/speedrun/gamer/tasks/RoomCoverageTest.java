package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.util.baritone.GoalColumnDown;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoomCoverageTest {
    @Test
    public void aChunkCountsWhenWeAreNearItsMiddleAtTheRightLevel() {
        // chunk (2, -1) has its middle at (40, -8)
        assertTrue(RoomCoverage.covers(40, 30, -8, 2, -1, 30));
        assertTrue(RoomCoverage.covers(40 + 23, 30 + 12, -8, 2, -1, 30));
        assertFalse(RoomCoverage.covers(40 + 25, 30, -8, 2, -1, 30));
        // a tunnel two levels above or below does not see the room
        assertFalse(RoomCoverage.covers(40, 30 + 13, -8, 2, -1, 30));
        assertFalse(RoomCoverage.covers(40, 30 - 13, -8, 2, -1, 30));
        // no working level yet = nothing counts
        assertFalse(RoomCoverage.covers(40, 30, -8, 2, -1, Double.NaN));
    }

    @Test
    public void followsTheNearestUnvisitedChunkWithSeenBricks() {
        List<int[]> bricks = new ArrayList<>();
        bricks.add(new int[]{8, 8});      // chunk 0,0
        bricks.add(new int[]{40, 8});     // chunk 2,0
        bricks.add(new int[]{-100, -100}); // chunk -7,-7 (-100 / 16 floors to -7)
        Set<String> visited = new HashSet<>();
        Optional<int[]> next = RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7);
        assertArrayEquals(new int[]{0, 0}, next.get());
        visited.add("0,0");
        assertArrayEquals(new int[]{2, 0}, RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).get());
        visited.add("2,0");
        assertArrayEquals(new int[]{-7, -7}, RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).get());
        visited.add("-7,-7");
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(bricks, visited, 0, 0, 0, 0, 7).isEmpty());
    }

    @Test
    public void bricksOutsideTheSearchSquareAreIgnored() {
        List<int[]> bricks = List.of(new int[]{16 * 20, 0});
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(bricks, Set.of(), 0, 0, 0, 0, 7).isEmpty());
        assertTrue(RoomCoverage.nearestUnvisitedBrickChunk(List.of(), Set.of(), 0, 0, 0, 0, 7).isEmpty());
    }

    @Test
    public void digEndsOnBricksNotOnDepth() {
        // surface, mid, and one block above the floor: still digging until bricks show
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING, RoomCoverage.digVerdict(false, 70, -54));
        assertEquals(RoomCoverage.DigVerdict.KEEP_DIGGING, RoomCoverage.digVerdict(false, 9, -54));
        assertEquals(RoomCoverage.DigVerdict.BRICKS, RoomCoverage.digVerdict(true, 40, -54));
        assertEquals(RoomCoverage.DigVerdict.BRICKS, RoomCoverage.digVerdict(true, -54, -54));
    }

    @Test
    public void reachingTheFloorWithoutBricksFallsBack() {
        assertEquals(RoomCoverage.DigVerdict.FLOOR, RoomCoverage.digVerdict(false, -52, -54));
        assertEquals(RoomCoverage.DigVerdict.FLOOR, RoomCoverage.digVerdict(false, -60, -54));
    }

    @Test
    public void workYIsTheBrickLevelNotTheDigEnd() {
        assertEquals(31, RoomCoverage.workY(OptionalInt.of(31), -54), 0);
        assertEquals(-54, RoomCoverage.workY(OptionalInt.empty(), -54), 0);
    }

    @Test
    public void aTimedOutChunkIsSkippedNotCovered() {
        Set<String> visited = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        assertTrue(RoomCoverage.giveUp(visited, skipped, "3,4"));
        assertTrue(skipped.contains("3,4"));
        assertFalse(visited.contains("3,4"));
        // one markCovered did get is not skipped
        visited.add("5,5");
        assertFalse(RoomCoverage.giveUp(visited, skipped, "5,5"));
        assertFalse(skipped.contains("5,5"));
    }

    @Test
    public void brickFilterRunsBeforeTheCap() {
        BlockPos origin = new BlockPos(0, 60, 0);
        List<BlockPos> tracked = new ArrayList<>();
        // a village on the surface right next to us, more than the cap of it
        for (int i = 1; i <= 300; i++) {
            tracked.add(new BlockPos(i % 10, 70, i / 10));
        }
        // and the real stronghold bricks, further away and lower
        tracked.add(new BlockPos(60, 20, 0));
        tracked.add(new BlockPos(1000, 20, 0));
        List<BlockPos> kept = RoomCoverage.brickCandidates(tracked, 56, true, 0, 0, 140, Double.NaN);
        assertEquals(1, kept.size());
        assertEquals(60, kept.get(0).getX());
        assertEquals(60, StrongholdScan.nearest(kept, origin, StrongholdScan.MAX_SCAN).get(0).getX());
        // without a start nothing is cut by distance
        assertEquals(2, RoomCoverage.brickCandidates(tracked, 56, false, 0, 0, 140, Double.NaN).size());
        // bricks a level away from us are not "we are inside"
        assertEquals(1, RoomCoverage.brickCandidates(tracked, 56, true, 0, 0, 140, 30).size());
        assertEquals(0, RoomCoverage.brickCandidates(tracked, 56, false, 0, 0, 140, 60).size());
    }

    @Test
    public void digGoalIsPinnedToTheStartColumn() {
        GoalColumnDown goal = new GoalColumnDown(996, 1684, -54);
        assertTrue(goal.isInGoal(996, -54, 1684));
        assertTrue(goal.isInGoal(996, -60, 1684));
        assertFalse(goal.isInGoal(996, -53, 1684));
        // the same depth in another column is not it
        assertFalse(goal.isInGoal(1040, -54, 1684));
        assertTrue(goal.heuristic(1040, 9, 1684) > goal.heuristic(996, 9, 1684));
        assertEquals(0, goal.heuristic(996, -54, 1684), 0);
    }

    @Test
    public void nearestSortsByDistanceAndCaps() {
        BlockPos origin = new BlockPos(0, 0, 0);
        List<BlockPos> many = new ArrayList<>();
        for (int i = 300; i >= 1; i--) {
            many.add(new BlockPos(i, 0, 0));
        }
        List<BlockPos> near = StrongholdScan.nearest(many, origin, 200);
        assertEquals(200, near.size());
        assertEquals(1, near.get(0).getX());
        assertEquals(200, near.get(199).getX());
        // the input is untouched
        assertEquals(300, many.get(0).getX());
        // fewer than the cap just comes back sorted
        assertEquals(3, StrongholdScan.nearest(List.of(new BlockPos(9, 0, 0), new BlockPos(3, 0, 0)), origin, 200).get(0).getX());
    }
}
