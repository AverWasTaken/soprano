package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.Baritone;
import baritone.process.BuilderProcess;
import baritone.utils.BlockPlaceHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildScaffoldTest {

    // set in boot, same reason as SkipItemsTest: Blocks before the bootstrap takes the registries down
    private static BlockState AIR;
    private static BlockState COBBLE;
    private static BlockState PLANKS;
    private static BlockState STONE;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        AIR = Blocks.AIR.defaultBlockState();
        COBBLE = Blocks.COBBLESTONE.defaultBlockState();
        PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
    }

    // a 3x3x3 box at the origin. planks everywhere except the middle column, which wants air, and (2,2,2), which the
    // file has cut out
    private static BuildScaffold scaffold() {
        return new BuildScaffold(BlockPos.ZERO, new BlockPos(2, 2, 2), (x, y, z) -> {
            if (x < 0 || y < 0 || z < 0 || x > 2 || y > 2 || z > 2 || (x == 2 && y == 2 && z == 2)) {
                return null;
            }
            return x == 1 && z == 1 ? AIR : PLANKS;
        }, (current, desired) -> BuilderProcess.valid(current, desired, false));
    }

    @Test
    public void onlyTheThrowawayListCounts() {
        assertTrue(BlockPlaceHelper.throwaway(Items.COBBLESTONE));
        assertFalse(BlockPlaceHelper.throwaway(Items.OAK_STAIRS));
        // not a block at all
        assertFalse(BlockPlaceHelper.throwaway(Items.STICK));
    }

    @Test
    public void aThrowawayWhereTheFileWantsSomethingElseIsOurs() {
        BuildScaffold s = scaffold();
        assertTrue(s.offer(new BlockPos(0, 1, 0), COBBLE));
        assertTrue(s.holds(new BlockPos(0, 1, 0), COBBLE));
        // a pillar in a cell that should stay empty is ours too
        assertTrue(s.offer(new BlockPos(1, 0, 1), COBBLE));
    }

    @Test
    public void aThrowawayThatIsTheRightBlockIsJustTheBuild() {
        BuildScaffold s = new BuildScaffold(BlockPos.ZERO, BlockPos.ZERO, (x, y, z) -> COBBLE,
            (current, desired) -> BuilderProcess.valid(current, desired, false));
        assertFalse(s.offer(BlockPos.ZERO, COBBLE));
        assertTrue(s.isEmpty());
    }

    @Test
    public void aSubstituteTheBuilderTakesIsNotOurs() {
        Map<Block, List<Block>> before = Baritone.settings().buildValidSubstitutes.value;
        Map<Block, List<Block>> subs = new HashMap<>();
        subs.put(Blocks.OAK_PLANKS, List.of(Blocks.COBBLESTONE));
        Baritone.settings().buildValidSubstitutes.value = subs;
        try {
            BuildScaffold s = scaffold();
            assertFalse(s.offer(new BlockPos(0, 1, 0), COBBLE));
            // the substitute is for planks only, stone is still wrong there
            assertTrue(s.offer(new BlockPos(0, 2, 0), STONE));
        } finally {
            Baritone.settings().buildValidSubstitutes.value = before;
        }
    }

    @Test
    public void outsideTheBoxCountsUpToTheMargin() {
        BuildScaffold s = scaffold();
        int m = BuildScaffold.MARGIN;
        assertTrue(s.offer(new BlockPos(-m, 0, 0), COBBLE));
        assertTrue(s.offer(new BlockPos(2 + m, 2 + m, 2 + m), COBBLE));
        assertFalse(s.offer(new BlockPos(-m - 1, 0, 0), COBBLE));
        assertFalse(s.offer(new BlockPos(0, 2 + m + 1, 0), COBBLE));
        assertFalse(s.offer(new BlockPos(0, 0, -m - 1), COBBLE));
        assertEquals(2, s.size());
    }

    @Test
    public void airIsNeverOurs() {
        assertFalse(scaffold().offer(new BlockPos(-1, 0, 0), AIR));
    }

    @Test
    public void holdsOnlyWhileTheSameBlockIsThere() {
        BuildScaffold s = scaffold();
        s.offer(new BlockPos(-1, 0, 0), COBBLE);
        assertFalse(s.holds(new BlockPos(-1, 0, 0), AIR));
        assertFalse(s.holds(new BlockPos(-1, 0, 0), STONE));
        assertFalse(s.holds(new BlockPos(-2, 0, 0), COBBLE));
        s.forget(new BlockPos(-1, 0, 0));
        assertFalse(s.holds(new BlockPos(-1, 0, 0), COBBLE));
    }

    @Test
    public void dirtGoingGrassyOrLeavesSettlingIsStillOurs() {
        BuildScaffold s = scaffold();
        s.offer(new BlockPos(-1, 0, 0), Blocks.DIRT.defaultBlockState());
        assertTrue(s.holds(new BlockPos(-1, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState()));
        // same block, other state
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        s.offer(new BlockPos(-2, 0, 0), leaves.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 7));
        assertTrue(s.holds(new BlockPos(-2, 0, 0), leaves.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1)));
        // but grass that was never dirt isn't
        s.offer(new BlockPos(-3, 0, 0), COBBLE);
        assertFalse(s.holds(new BlockPos(-3, 0, 0), Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(s.contains(new BlockPos(-3, 0, 0)));
        assertFalse(s.contains(new BlockPos(-4, 0, 0)));
    }

    @Test
    public void breaksTheStraysNearestColumnFirstTopDown() {
        BuildScaffold s = scaffold();
        // a pillar outside, right next to us
        s.offer(new BlockPos(-1, 0, 0), COBBLE);
        s.offer(new BlockPos(-1, 2, 0), COBBLE);
        s.offer(new BlockPos(-1, 1, 0), COBBLE);
        // one in the cell that wants air, a bit further
        s.offer(new BlockPos(1, 0, 1), COBBLE);
        // one in the cut out cell
        s.offer(new BlockPos(2, 2, 2), COBBLE);
        // where the file wants planks: the builder's job, not on the list
        s.offer(new BlockPos(0, 1, 0), COBBLE);
        List<BlockPos> order = s.toBreak(new BlockPos(-2, 5, 0));
        assertEquals(List.of(new BlockPos(-1, 2, 0), new BlockPos(-1, 1, 0), new BlockPos(-1, 0, 0),
            new BlockPos(1, 0, 1), new BlockPos(2, 2, 2)), order);
    }

    @Test
    public void releaseHandsTheCellsInsideTheFileToTheBuilder() {
        BuildScaffold s = scaffold();
        s.offer(new BlockPos(-1, 0, 0), COBBLE);
        s.offer(new BlockPos(0, 1, 0), COBBLE);
        s.offer(new BlockPos(1, 0, 1), COBBLE);
        s.offer(new BlockPos(2, 2, 2), COBBLE);
        // the planks cell and the air cell go to the builder, outside and cut out stay ours
        assertEquals(2, s.release());
        assertFalse(s.holds(new BlockPos(0, 1, 0), COBBLE));
        assertFalse(s.holds(new BlockPos(1, 0, 1), COBBLE));
        assertTrue(s.holds(new BlockPos(-1, 0, 0), COBBLE));
        assertTrue(s.holds(new BlockPos(2, 2, 2), COBBLE));
    }
}
