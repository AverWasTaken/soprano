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
        ShapeSpec s = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CYLINDER), "x hollow middle equal facing=vertical").spec();
        assertEquals(ShapeSpec.Direction.X, s.direction());
        assertEquals(ShapeSpec.Fill.HOLLOW, s.fill());
        assertTrue(s.centered());
        assertTrue(s.equal());
        assertEquals(ShapeSpec.Facing.VERTICAL, s.facing());
        assertEquals(ShapeSpec.Fill.SKELETON, EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "fill=edges").spec().fill());
        assertTrue(EffortlessOptions.parse(ShapeSpec.of(ShapeKind.SLOPE_FLOOR), "long").spec().longEdge());
        assertEquals(ShapeSpec.Direction.FLOOR, EffortlessOptions.parse(ShapeSpec.of(ShapeKind.LINE), "direction=floor").spec().direction());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonsenseIsRefused() {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "sideways");
    }

    @Test
    public void placeUsesTheHandByDefault() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "")
                .placeJob(BOX, held(Blocks.OAK_PLANKS), Optional.empty());
        assertEquals(EffortlessJob.Mode.PLACE, job.mode);
        assertEquals(EffortlessJob.FillMode.FILL_EMPTY, job.fillMode);
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), job.palette.pick(0, 0, 0));
    }

    @Test(expected = EffortlessOptions.Bad.class)
    public void placeWithEmptyHandsSaysSo() throws Exception {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "").placeJob(BOX, Optional.empty(), Optional.empty());
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
    public void replaceTakesExistingBlocksOnly() throws Exception {
        // no only=: every block that's there, air stays air
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "mode=replace")
                .placeJob(BOX, held(Blocks.COBBLESTONE), held(Blocks.DIRT));
        assertEquals(EffortlessJob.FillMode.REPLACE, job.fillMode);
        assertEquals(EffortlessJob.Outcome.REPLACE, job.outcome(0, 0, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, job.outcome(0, 0, 0, Blocks.AIR.defaultBlockState()));
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), job.palette.pick(0, 0, 0));
    }

    @Test
    public void offhandPicksWhichExistingBlocks() throws Exception {
        // only the offhand's: the dirt goes, the stone stays
        EffortlessJob only = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "mode=replace only=offhand")
                .placeJob(BOX, held(Blocks.COBBLESTONE), held(Blocks.DIRT));
        assertEquals(EffortlessJob.Outcome.REPLACE, only.outcome(0, 0, 0, Blocks.DIRT.defaultBlockState()));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, only.outcome(0, 0, 0, Blocks.STONE.defaultBlockState()));
        // all but the offhand's, air too: the logs in the offhand are kept, the rest filled over
        EffortlessJob but = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "mode=overwrite except=offhand")
                .placeJob(BOX, held(Blocks.COBBLESTONE), held(Blocks.OAK_LOG));
        assertEquals(EffortlessJob.Outcome.UNTOUCHED, but.outcome(0, 0, 0, Blocks.OAK_LOG.defaultBlockState()));
        assertEquals(EffortlessJob.Outcome.REPLACE, but.outcome(0, 0, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(EffortlessJob.Outcome.PLACE, but.outcome(0, 0, 0, Blocks.AIR.defaultBlockState()));
    }

    @Test
    public void breakFilters() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "only=stone,granite skipcontainers")
                .breakJob(BOX, Optional.empty(), Optional.empty());
        assertTrue(job.filter.matches(Blocks.GRANITE.defaultBlockState()));
        assertFalse(job.filter.matches(Blocks.DIRT.defaultBlockState()));
        assertTrue(job.filter.skipContainers());
        assertTrue(job.filter.keepFluids());

        EffortlessJob notHand = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "nothand fluids")
                .breakJob(BOX, held(Blocks.STONE), Optional.empty());
        assertFalse(notHand.filter.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(notHand.filter.matches(Blocks.DIRT.defaultBlockState()));
        assertTrue(notHand.filter.matches(Blocks.WATER.defaultBlockState()));

        EffortlessJob notOff = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "notoffhand")
                .breakJob(BOX, Optional.empty(), held(Blocks.OAK_LOG));
        assertFalse(notOff.filter.matches(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(notOff.filter.matches(Blocks.STONE.defaultBlockState()));
    }

    @Test(expected = EffortlessOptions.Bad.class)
    public void unknownBlockInAFilter() throws Exception {
        EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "only=stoen").breakJob(BOX, Optional.empty(), Optional.empty());
    }

    @Test
    public void seedSticks() throws Exception {
        EffortlessJob job = EffortlessOptions.parse(ShapeSpec.of(ShapeKind.CUBE), "block=stone,dirt seed=77")
                .placeJob(BOX, Optional.empty(), Optional.empty());
        assertEquals(77, job.palette.seed());
    }
}
