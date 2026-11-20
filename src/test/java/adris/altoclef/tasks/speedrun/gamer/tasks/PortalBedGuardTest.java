package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the never-break rule on the portal bed: both halves, nothing else, and nothing once there is no portal bed
public class PortalBedGuardTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // a red bed with its foot at 10 64 10, head one block east
    private static Map<BlockPos, BlockState> world() {
        Map<BlockPos, BlockState> w = new HashMap<>();
        BlockState foot = Block.byItem(Items.BED.red()).defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
        w.put(new BlockPos(10, 64, 10), foot);
        w.put(new BlockPos(11, 64, 10), foot.setValue(BedBlock.PART, BedPart.HEAD));
        w.put(new BlockPos(10, 64, 11), Blocks.DIRT.defaultBlockState());
        return w;
    }

    private static RunState withBed(int x, int y, int z) {
        RunState s = new RunState();
        s.portalBed = new RunState.Pos(x, y, z);
        return s;
    }

    private static boolean guarded(RunState s, Map<BlockPos, BlockState> w, int x, int y, int z) {
        return PortalBedStep.guarded(s, new BlockPos(x, y, z), p -> w.getOrDefault(p, Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void bothHalvesOfThePortalBedAreKept() {
        RunState s = withBed(10, 64, 10);
        Map<BlockPos, BlockState> w = world();
        assertTrue(guarded(s, w, 10, 64, 10));
        assertTrue(guarded(s, w, 11, 64, 10));
    }

    @Test
    public void theHeadAsTheRememberedCellKeepsTheFootToo() {
        RunState s = withBed(11, 64, 10);
        Map<BlockPos, BlockState> w = world();
        assertTrue(guarded(s, w, 11, 64, 10));
        assertTrue(guarded(s, w, 10, 64, 10));
    }

    @Test
    public void dirtNextToItAndBlocksAboveAndBelowStayBreakable() {
        RunState s = withBed(10, 64, 10);
        Map<BlockPos, BlockState> w = world();
        assertFalse(guarded(s, w, 10, 64, 11));
        assertFalse(guarded(s, w, 10, 63, 10));
        assertFalse(guarded(s, w, 10, 65, 10));
        assertFalse(guarded(s, w, 12, 64, 10));
    }

    @Test
    public void aNightBedPlacedAgainstItIsNotThePortalBed() {
        RunState s = withBed(10, 64, 10);
        Map<BlockPos, BlockState> w = world();
        // a second bed with its foot right next to the portal bed's foot, pointing north away from it
        BlockState other = Block.byItem(Items.BED.white()).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
        w.put(new BlockPos(10, 64, 9), other);
        w.put(new BlockPos(10, 64, 8), other.setValue(BedBlock.PART, BedPart.HEAD));
        assertFalse(guarded(s, w, 10, 64, 9));
        assertFalse(guarded(s, w, 10, 64, 8));
        assertTrue(guarded(s, w, 10, 64, 10));
    }

    @Test
    public void noPortalBedOrNoBedThereGuardsNothing() {
        Map<BlockPos, BlockState> w = world();
        assertFalse(guarded(new RunState(), w, 10, 64, 10));
        assertFalse(guarded(null, w, 10, 64, 10));
        // the bed is gone: the cell holds whatever got put there since, and that's breakable
        RunState s = withBed(10, 64, 10);
        w.put(new BlockPos(10, 64, 10), Blocks.COBBLESTONE.defaultBlockState());
        w.remove(new BlockPos(11, 64, 10));
        assertFalse(guarded(s, w, 10, 64, 10));
        assertFalse(guarded(s, w, 11, 64, 10));
    }
}
