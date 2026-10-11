package adris.altoclef.tasks.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WaterBreakGuardTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static boolean soaked(int ticks, double air, boolean instant, boolean landSpot) {
        return WaterBreakGuard.mayBreak(false, true, instant, ticks, air, landSpot);
    }

    @Test
    public void dryGroundBreaksAndTheAirDoesNot() {
        assertTrue(WaterBreakGuard.mayBreak(true, false, false, 0, 1, false));
        assertFalse(WaterBreakGuard.mayBreak(false, false, false, 0, 1, false));
    }

    @Test
    public void soakedWithSomewhereDryWaits() {
        assertFalse(soaked(0, 1, false, true));
        assertFalse(soaked(WaterBreakGuard.GIVE_UP_TICKS - 1, 1, false, true));
    }

    @Test
    public void softThingsBreakAnyway() {
        assertTrue(soaked(0, 1, true, true));
    }

    @Test
    public void patienceRunsOut() {
        assertTrue(soaked(WaterBreakGuard.GIVE_UP_TICKS, 1, false, true));
    }

    @Test
    public void lowAirBreaksAnyway() {
        assertFalse(soaked(0, WaterBreakGuard.LOW_AIR_FRACTION + 0.01, false, true));
        assertTrue(soaked(0, WaterBreakGuard.LOW_AIR_FRACTION - 0.01, false, true));
    }

    @Test
    public void noDryPlaceMeansBreakIt() {
        assertTrue(soaked(0, 1, false, false));
    }

    // enough world to ask what's standable
    private static final class MapGetter implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
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
            return getBlockState(pos).getFluidState();
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

    private static final BlockPos LOG = new BlockPos(0, 64, 0);

    // a lake with the log on a one block island in it, shore to the east from x=3
    private static MapGetter lake() {
        MapGetter w = new MapGetter();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 62, z, Blocks.SAND.defaultBlockState());
                w.set(x, 63, z, x >= 3 ? Blocks.SAND.defaultBlockState() : Blocks.WATER.defaultBlockState());
                w.set(x, 64, z, x >= 3 ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
                w.set(x, 65, z, x >= 3 ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        w.set(0, 64, 0, Blocks.OAK_LOG.defaultBlockState());
        return w;
    }

    @Test
    public void landSpotsAreOnTheShoreAndInReach() {
        List<BlockPos> spots = DestroyBlockTask.landSpots(lake(), LOG, 4.5, new BlockPos(0, 64, 1));
        assertFalse("the shore is three blocks away, there should be spots", spots.isEmpty());
        for (BlockPos p : spots) {
            assertTrue("not on the shore: " + p, p.getX() >= 3);
            assertEquals(64, p.getY());
        }
    }

    @Test
    public void aLakeWithNoShoreInRangeHasNoSpots() {
        MapGetter w = lake();
        for (int x = 3; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 63, z, Blocks.WATER.defaultBlockState());
            }
        }
        assertTrue(DestroyBlockTask.landSpots(w, LOG, 4.5, new BlockPos(0, 64, 1)).isEmpty());
    }

    @Test
    public void aWallInTheWayIsNotASpot() {
        MapGetter w = lake();
        // a stone screen between the shore and the log, both eye and foot height
        for (int z = -6; z <= 6; z++) {
            for (int y = 64; y <= 66; y++) {
                w.set(2, y, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertTrue(DestroyBlockTask.landSpots(w, LOG, 4.5, new BlockPos(0, 64, 1)).isEmpty());
    }

    @Test
    public void closestFirstAndCapped() {
        MapGetter w = lake();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                w.set(x, 63, z, Blocks.SAND.defaultBlockState());
                w.set(x, 64, z, Blocks.AIR.defaultBlockState());
                w.set(x, 65, z, Blocks.AIR.defaultBlockState());
            }
        }
        w.set(0, 64, 0, Blocks.OAK_LOG.defaultBlockState());
        BlockPos from = new BlockPos(2, 64, 0);
        List<BlockPos> spots = DestroyBlockTask.landSpots(w, LOG, 4.5, from);
        assertEquals(24, spots.size());
        for (int i = 1; i < spots.size(); i++) {
            assertTrue(spots.get(i - 1).distSqr(from) <= spots.get(i).distSqr(from));
        }
        // never on top of the log or the square above it
        for (BlockPos p : spots) {
            assertFalse(p.getX() == 0 && p.getZ() == 0 && p.getY() >= 64);
        }
    }
}
