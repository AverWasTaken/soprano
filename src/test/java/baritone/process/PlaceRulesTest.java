/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaceRulesTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static boolean same(BlockState a, BlockState b) {
        return BuilderProcess.sameBlockstate(a, b, false, List.of());
    }

    @Test
    public void stairShapeIsTheNeighboursCall() {
        BlockState straight = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState corner = straight.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT);
        assertTrue(same(corner, straight));
        // which way it faces and which half it's on are still ours to get right
        assertFalse(same(straight.setValue(StairBlock.FACING, Direction.EAST), straight));
        assertFalse(same(straight.setValue(StairBlock.HALF, Half.TOP), straight));
    }

    @Test
    public void fenceAndWallConnectionsDontCount() {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        assertTrue(same(fence, fence.setValue(CrossCollisionBlock.SOUTH, true).setValue(CrossCollisionBlock.EAST, true)));
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        assertTrue(same(pane, pane.setValue(CrossCollisionBlock.NORTH, true)));
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        assertTrue(same(wall, wall.setValue(WallBlock.NORTH, WallSide.TALL).setValue(WallBlock.UP, false)));
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        assertTrue(same(gate, gate.setValue(FenceGateBlock.IN_WALL, true)));
        assertFalse(same(gate, gate.setValue(FenceGateBlock.FACING, Direction.WEST)));
        // waterlogged isn't one of them
        assertFalse(same(fence, fence.setValue(BlockStateProperties.WATERLOGGED, true)));
        assertTrue(same(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true)));
    }

    @Test
    public void vineFacesStayStrict() {
        // same property objects as the fence's, but somebody picked these
        assertTrue(VineBlock.NORTH == CrossCollisionBlock.NORTH);
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true);
        assertFalse(same(vine, vine.setValue(VineBlock.NORTH, false).setValue(VineBlock.SOUTH, true)));
        assertFalse(PlaceRules.derived(Blocks.VINE, VineBlock.NORTH));
        assertTrue(PlaceRules.derived(Blocks.OAK_FENCE, CrossCollisionBlock.NORTH));
    }

    @Test
    public void aimProofIsAFullCubeWithNothingToAim() {
        assertTrue(PlaceRules.aimProof(Blocks.OAK_PLANKS.defaultBlockState()));
        assertTrue(PlaceRules.aimProof(Blocks.COBBLESTONE.defaultBlockState()));
        // vertical is the default and a side click still lays it down
        assertFalse(PlaceRules.aimProof(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_FENCE.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.FURNACE.defaultBlockState()));
        assertFalse(PlaceRules.aimProof(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
    }
}
