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
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

// what "#ef place ..." and "#ef break ..." turn into, with a made up hand and offhand
public class EffortlessOptionsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final List<BlockPos> BOX = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));

    private static Optional<BlockState> held(net.minecraft.world.level.block.Block b) {
        return Optional.of(b.defaultBlockState());
    }

    @Test
    public void shapeWords() {
        ShapeSpec s = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CYLINDER), "x hollow t=3 half").spec();
        assertEquals(ShapeSpec.Axis.X, s.axis());
        assertEquals(ShapeSpec.Fill.HOLLOW, s.fill());
        assertEquals(3, s.thickness());
        assertTrue(s.half());
        assertEquals(ShapeSpec.Fill.FRAME, EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "fill=edges").spec().fill());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonsenseIsRefused() {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "sideways");
    }

    @Test
    public void placeUsesTheHandByDefault() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "")
                .placeJob(BOX, held(Blocks.OAK_PLANKS), Optional.empty());
        assertEquals(EffortlessJob.Mode.PLACE, job.mode);
        assertEquals(EffortlessJob.FillMode.FILL_EMPTY, job.fillMode);
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), job.palette.pick(0, 0, 0));
    }

    @Test(expected = EffortlessOptions.Bad.class)
    public void placeWithEmptyHandsSaysSo() throws Exception {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "").placeJob(BOX, Optional.empty(), Optional.empty());
    }

    @Test
    public void paletteWithWeightsAndRotation() throws Exception {
        Palette p = EffortlessOptions.palette("stone_bricks*6,cracked_stone_bricks*3,oak_log@r", Optional.empty(), Optional.empty());
        assertEquals(3, p.entries().size());
        assertEquals(6, p.entries().get(0).weight());
        assertTrue(p.entries().get(2).randomRotation());
        assertEquals(Blocks.OAK_LOG, p.entries().get(2).state().getBlock());
    }

    @Test(expected = EffortlessOptions.Bad.class)
    public void airIsNotABlockToPlace() throws Exception {
        EffortlessOptions.palette("air", Optional.empty(), Optional.empty());
    }

    @Test
    public void replaceSwapsHandForOffhand() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "mode=replace")
                .placeJob(BOX, held(Blocks.DIRT), held(Blocks.COBBLESTONE));
        assertEquals(EffortlessJob.FillMode.REPLACE, job.fillMode);
        assertTrue(job.filter.matches(Blocks.DIRT.defaultBlockState()));
        assertFalse(job.filter.matches(Blocks.STONE.defaultBlockState()));
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), job.palette.pick(0, 0, 0));
    }

    @Test
    public void breakFilters() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "only=stone,granite skipcontainers")
                .breakJob(BOX, Optional.empty(), Optional.empty());
        assertTrue(job.filter.matches(Blocks.GRANITE.defaultBlockState()));
        assertFalse(job.filter.matches(Blocks.DIRT.defaultBlockState()));
        assertTrue(job.filter.skipContainers());
        assertTrue(job.filter.keepFluids());

        EffortlessJob notHand = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "nothand fluids")
                .breakJob(BOX, held(Blocks.STONE), Optional.empty());
        assertFalse(notHand.filter.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(notHand.filter.matches(Blocks.DIRT.defaultBlockState()));
        assertTrue(notHand.filter.matches(Blocks.WATER.defaultBlockState()));
    }

    @Test(expected = EffortlessOptions.Bad.class)
    public void unknownBlockInAFilter() throws Exception {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "only=stoen").breakJob(BOX, Optional.empty(), Optional.empty());
    }

    @Test
    public void seedSticks() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBOID), "block=stone,dirt seed=77")
                .placeJob(BOX, Optional.empty(), Optional.empty());
        assertEquals(77, job.palette.seed());
    }
}
