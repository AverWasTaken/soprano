package adris.altoclef.util.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

// the tasks make a fresh goal every time they ask, and the path a handover parked only comes back for an equal one
public class GoalEqualityTest {
    @Test
    public void reachBlockIsEqualByTheBlock() {
        GoalReachBlock a = new GoalReachBlock(new BlockPos(1, 64, -3));
        GoalReachBlock b = new GoalReachBlock(new BlockPos(1, 64, -3));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new GoalReachBlock(new BlockPos(1, 65, -3)));
    }

    @Test
    public void blockSideIsEqualByBlockSideAndBuffer() {
        GoalBlockSide a = new GoalBlockSide(new BlockPos(4, 70, 4), Direction.NORTH);
        GoalBlockSide b = new GoalBlockSide(new BlockPos(4, 70, 4), Direction.NORTH, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new GoalBlockSide(new BlockPos(4, 70, 4), Direction.SOUTH));
        assertNotEquals(a, new GoalBlockSide(new BlockPos(4, 70, 4), Direction.NORTH, 2));
    }

    @Test
    public void chunkIsEqualByTheChunk() {
        assertEquals(new GoalChunk(new ChunkPos(2, -7)), new GoalChunk(new ChunkPos(2, -7)));
        assertEquals(new GoalChunk(new ChunkPos(2, -7)).hashCode(), new GoalChunk(new ChunkPos(2, -7)).hashCode());
        assertNotEquals(new GoalChunk(new ChunkPos(2, -7)), new GoalChunk(new ChunkPos(3, -7)));
    }
}
