package adris.altoclef.tasks.construction;

import adris.altoclef.util.baritone.GoalReachBlock;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DestroyBlockVinesTest {

    private static final BlockPos LOG = new BlockPos(0, 64, 0);

    // just enough world to throw a ray through
    private static final class MapGetter implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState vineOn(Direction attached) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.PROPERTY_BY_DIRECTION.get(attached), true);
    }

    private static MapGetter trunk() {
        MapGetter w = new MapGetter();
        for (int y = 60; y <= 72; y++) {
            w.set(new BlockPos(0, y, 0), Blocks.JUNGLE_LOG.defaultBlockState());
        }
        return w;
    }

    // eyes of someone standing on the ground three blocks east of the trunk
    private static final Vec3 EYE = new Vec3(3.5, 63 + 1.62, 0.5);

    @Test
    public void plainLogGetsTheOldGoal() {
        Goal goal = DestroyBlockTask.pickGoal(trunk(), LOG, false);
        assertTrue(goal instanceof GoalNear);
    }

    @Test
    public void logWithVinesOnItGetsTheReachGoal() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, false) instanceof GoalReachBlock);
        // vines a block off to the side count too, they're what the planner climbs into
        MapGetter above = trunk();
        above.set(LOG.above().south(), vineOn(Direction.NORTH));
        assertTrue(DestroyBlockTask.pickGoal(above, LOG.above(), false) instanceof GoalReachBlock);
    }

    @Test
    public void closingInFallsBackToTouching() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, true) instanceof GoalNear);
    }

    @Test
    public void snowOnTopStillMeansStandOnIt() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        w.set(LOG.above(), Blocks.SNOW.defaultBlockState());
        assertTrue(DestroyBlockTask.pickGoal(w, LOG, false) instanceof GoalBlock);
    }

    @Test
    public void aVineInFrontOfTheLogIsTheOneToBreak() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertEquals(LOG.east(), DestroyBlockTask.vineInTheWay(w, EYE, LOG, 4.5));
    }

    @Test
    public void nothingInTheWayMeansNothingToBreak() {
        assertNull(DestroyBlockTask.vineInTheWay(trunk(), EYE, LOG, 4.5));
    }

    @Test
    public void aVineOutOfReachIsLeftAlone() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        assertNull(DestroyBlockTask.vineInTheWay(w, EYE, LOG, 2.0));
    }

    @Test
    public void onlyVinesAreOursToBreak() {
        // leaves in the way (or any other block) aren't something to clear on the way to a log
        MapGetter w = trunk();
        w.set(LOG.east(), Blocks.JUNGLE_LEAVES.defaultBlockState());
        assertNull(DestroyBlockTask.vineInTheWay(w, EYE, LOG, 4.5));
    }

    @Test
    public void ofTwoVinesTheNearerFirst() {
        MapGetter w = trunk();
        w.set(LOG.east(), vineOn(Direction.WEST));
        w.set(LOG.east(2), vineOn(Direction.WEST));
        assertEquals(LOG.east(2), DestroyBlockTask.vineInTheWay(w, EYE, LOG, 4.5));
    }
}
