/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.effortless;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagLoader;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// what the builder gets told about each cell, for each mode. the builder asks in local coords, these jobs sit at 0,0,0
// so local and world are the same
public class JobSchematicTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        // the fixture only loads item tags. a made up #logs is enough to see a tag entry work
        BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(BlockTags.LOGS,
                List.of(Blocks.OAK_LOG.builtInRegistryHolder(), Blocks.BIRCH_LOG.builtInRegistryHolder())))).apply();
    }

    private static final List<BlockPos> BOX = List.of(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState dirt() {
        return Blocks.DIRT.defaultBlockState();
    }

    private static BlockState cobble() {
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    private static ShapeSpec cuboid() {
        return ShapeSpec.of(ShapeKind.CUBOID);
    }

    @Test
    public void breakOnlyWhatTheFilterSays() {
        EffortlessJob job = EffortlessJob.breaking(cuboid(), BOX, BlockFilter.ALL.withOnly(List.of("minecraft:stone")));
        JobSchematic s = new JobSchematic(job);
        assertTrue(s.inSchematic(1, 1, 1, stone()));
        assertFalse(s.inSchematic(0, 1, 1, dirt()));
        // air in a break area is already what it should be: in, and done
        assertTrue(s.inSchematic(2, 1, 1, air()));
        assertEquals(air(), s.desiredState(1, 1, 1, stone(), List.of()));
        // broken: still in, and air is what it wants, so the builder sees it done instead of losing track of it
        assertTrue(s.inSchematic(1, 1, 1, air()));
        assertEquals(EffortlessJob.Outcome.BREAK, job.outcome(0, 0, 0, stone()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, job.outcome(0, 0, 0, dirt()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, job.outcome(9, 9, 9, stone()));
    }

    @Test
    public void tagsAndExcludes() {
        BlockFilter logs = BlockFilter.ALL.withOnly(List.of("#minecraft:logs")).withExcept(List.of("minecraft:birch_log"));
        assertTrue(logs.matches(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(logs.matches(Blocks.BIRCH_LOG.defaultBlockState()));
        assertFalse(logs.matches(stone()));
        assertNotNull(BlockFilter.problem("minecraft:not_a_block_at_all"));
        assertNull(BlockFilter.problem("stone"));
    }

    @Test
    public void containersAndFluidsAreOptional() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        assertTrue(BlockFilter.ALL.matches(chest));
        assertFalse(BlockFilter.ALL.withSkipContainers(true).matches(chest));
        assertFalse(BlockFilter.ALL.matches(water));
        assertTrue(BlockFilter.ALL.withKeepFluids(false).matches(water));
    }

    @Test
    public void fillEmptyLeavesBlocksAlone() {
        EffortlessJob job = EffortlessJob.place(cuboid(), BOX, EffortlessJob.FillMode.FILL_EMPTY, null, Palette.of(stone()));
        JobSchematic s = new JobSchematic(job);
        assertTrue(s.inSchematic(0, 0, 0, air()));
        assertFalse(s.inSchematic(1, 0, 0, dirt()));
        // grass and water get placed over
        assertTrue(s.inSchematic(2, 0, 0, Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(stone(), s.desiredState(0, 0, 0, air(), List.of()));
    }

    @Test
    public void aDecidedCellStaysDecided() {
        EffortlessJob job = EffortlessJob.place(cuboid(), BOX, EffortlessJob.FillMode.FILL_EMPTY, null, Palette.of(stone()));
        JobSchematic s = new JobSchematic(job);
        assertTrue(s.inSchematic(0, 0, 0, air()));
        // placed: still in, and now right, so the builder counts it done instead of forgetting it
        assertTrue(s.inSchematic(0, 0, 0, stone()));
        s.reset();
        assertTrue(s.inSchematic(0, 0, 0, stone()));
        assertFalse(s.inSchematic(1, 1, 1, dirt()));
    }

    @Test
    public void replaceKeepsItsCellsThroughTheBreak() {
        BlockFilter dirtOnly = BlockFilter.ALL.withOnly(List.of("minecraft:dirt"));
        EffortlessJob job = EffortlessJob.place(cuboid(), BOX, EffortlessJob.FillMode.REPLACE, dirtOnly, Palette.of(cobble()));
        JobSchematic s = new JobSchematic(job);
        assertTrue(s.inSchematic(0, 0, 0, dirt()));
        // the builder dug the dirt out first, the cell is air now and still has to get its cobble
        assertTrue(s.inSchematic(0, 0, 0, air()));
        assertFalse(s.inSchematic(1, 0, 0, stone()));
        assertEquals(EffortlessJob.Outcome.REPLACE, job.outcome(2, 2, 2, dirt()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, job.outcome(2, 2, 2, stone()));
    }

    @Test
    public void overwriteTakesEverything() {
        EffortlessJob job = EffortlessJob.place(cuboid(), BOX, EffortlessJob.FillMode.OVERWRITE, null, Palette.of(stone()));
        assertEquals(EffortlessJob.Outcome.REPLACE, job.outcome(0, 0, 0, dirt()));
        assertEquals(EffortlessJob.Outcome.PLACE, job.outcome(0, 0, 0, air()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, job.outcome(0, 0, 0, stone()));
    }

    @Test
    public void laterJobInAGroupWins() {
        EffortlessJob fill = EffortlessJob.place(cuboid(), BOX, EffortlessJob.FillMode.OVERWRITE, null, Palette.of(stone()));
        EffortlessJob hole = EffortlessJob.breaking(ShapeSpec.of(ShapeKind.SINGLE), List.of(new BlockPos(1, 1, 1)), BlockFilter.ALL);
        EffortlessJob far = EffortlessJob.place(ShapeSpec.of(ShapeKind.SINGLE), List.of(new BlockPos(6, 0, 0)),
                EffortlessJob.FillMode.FILL_EMPTY, null, Palette.of(dirt()));
        JobSchematic s = new JobSchematic(List.of(fill, hole, far));
        assertEquals(new BlockPos(0, 0, 0), s.origin());
        assertEquals(7, s.widthX());
        assertEquals(air(), s.desiredState(1, 1, 1, stone(), List.of()));
        assertEquals(stone(), s.desiredState(0, 0, 0, air(), List.of()));
        assertEquals(dirt(), s.desiredState(6, 0, 0, air(), List.of()));
        // between the two boxes is nobody's
        assertFalse(s.inSchematic(4, 0, 0, air()));
    }

    @Test
    public void untouchedBlocksAreKept() {
        EffortlessJob job = EffortlessJob.breaking(cuboid(), BOX, BlockFilter.ALL.withOnly(List.of("minecraft:stone")));
        var keep = EffortlessManager.keepUntouched(new JobSchematic(job));
        // the dirt under "break only stone" stays, the stone goes, air and cells outside the job aren't its business
        assertTrue(keep.keep(0, 0, 0, dirt()));
        assertFalse(keep.keep(0, 0, 0, stone()));
        assertFalse(keep.keep(0, 0, 0, air()));
        assertFalse(keep.keep(9, 9, 9, dirt()));
    }
}
