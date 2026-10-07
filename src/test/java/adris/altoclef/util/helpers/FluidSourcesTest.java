package adris.altoclef.util.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

// fake worlds are just a map of position to fluid state, which is all the climb ever asks for
public class FluidSourcesTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final Map<BlockPos, FluidState> world = new HashMap<>();

    private FluidState at(BlockPos pos) {
        return world.getOrDefault(pos, Fluids.EMPTY.defaultFluidState());
    }

    private static FluidState source() {
        return Fluids.WATER.getSource(false);
    }

    private static FluidState flowing(int amount) {
        return Fluids.FLOWING_WATER.getFlowing(amount, false);
    }

    private static FluidState falling() {
        return Fluids.FLOWING_WATER.getFlowing(8, true);
    }

    private Optional<BlockPos> climb(BlockPos start, int moves) {
        return FluidSources.climbToSource(this::at, start, moves);
    }

    @Test
    public void bareSourceIsExposed() {
        assertTrue(FluidSources.exposedSource(source(), Fluids.EMPTY.defaultFluidState()));
    }

    @Test
    public void buriedSourceIsNot() {
        assertFalse(FluidSources.exposedSource(source(), source()));
        // flowing water on top counts too, you still can't reach in from above
        assertFalse(FluidSources.exposedSource(source(), flowing(7)));
        assertFalse(FluidSources.exposedSource(source(), falling()));
    }

    @Test
    public void flowingWaterIsNeverASource() {
        assertFalse(FluidSources.exposedSource(flowing(7), Fluids.EMPTY.defaultFluidState()));
        assertFalse(FluidSources.exposedSource(falling(), Fluids.EMPTY.defaultFluidState()));
    }

    @Test
    public void otherFluidOnTopDoesNotBury() {
        // lava over water is somebody else's problem, the water is still right there
        assertTrue(FluidSources.exposedSource(source(), Fluids.LAVA.getSource(false)));
        assertTrue(FluidSources.exposedSource(Fluids.LAVA.getSource(false), source()));
    }

    @Test
    public void emptyIsNeverASource() {
        assertFalse(FluidSources.exposedSource(Fluids.EMPTY.defaultFluidState(), Fluids.EMPTY.defaultFluidState()));
    }

    @Test
    public void climbsAFallingColumnToItsSource() {
        // source on a ledge at y=20, spills over the edge and falls 5 blocks
        world.put(new BlockPos(0, 20, 0), source());
        world.put(new BlockPos(1, 20, 0), flowing(7));
        for (int y = 19; y >= 15; y--) {
            world.put(new BlockPos(1, y, 0), falling());
        }
        assertEquals(Optional.of(new BlockPos(0, 20, 0)), climb(new BlockPos(1, 15, 0), 16));
    }

    @Test
    public void startingAtTheSourceIsThatSource() {
        world.put(new BlockPos(0, 20, 0), source());
        assertEquals(Optional.of(new BlockPos(0, 20, 0)), climb(new BlockPos(0, 20, 0), 16));
    }

    @Test
    public void followsTheTallestNeighbourAlongAStream() {
        // flows away from the source along x, levels 8 (source) down to 3
        world.put(new BlockPos(0, 0, 0), source());
        for (int i = 1; i <= 5; i++) {
            world.put(new BlockPos(i, 0, 0), flowing(8 - i));
        }
        assertEquals(Optional.of(new BlockPos(0, 0, 0)), climb(new BlockPos(5, 0, 0), 16));
    }

    @Test
    public void givesUpAfterTheCap() {
        world.put(new BlockPos(0, 30, 0), source());
        for (int y = 0; y < 30; y++) {
            world.put(new BlockPos(0, y, 0), falling());
        }
        // 30 blocks up is not happening in 16 moves
        assertTrue(climb(new BlockPos(0, 0, 0), 16).isEmpty());
        // but the same column is fine from close enough
        assertEquals(Optional.of(new BlockPos(0, 30, 0)), climb(new BlockPos(0, 20, 0), 16));
    }

    @Test
    public void streamWithNoFeederIsADeadEnd() {
        // a puddle of flowing water with nothing taller around it (the source got picked up)
        world.put(new BlockPos(0, 0, 0), flowing(4));
        world.put(new BlockPos(1, 0, 0), flowing(3));
        assertTrue(climb(new BlockPos(1, 0, 0), 16).isEmpty());
    }

    @Test
    public void startingOutsideTheFluidIsNothing() {
        assertTrue(climb(new BlockPos(0, 0, 0), 16).isEmpty());
    }

    @Test
    public void wrongFluidOnTheWayUpStopsTheClimb() {
        world.put(new BlockPos(0, 0, 0), falling());
        world.put(new BlockPos(0, 1, 0), Fluids.LAVA.getSource(false));
        // lava above water is not water above water, so we are at the top of the water and it is a dead end
        assertTrue(climb(new BlockPos(0, 0, 0), 16).isEmpty());
    }
}
