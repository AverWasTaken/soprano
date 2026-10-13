package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
