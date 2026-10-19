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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// the bits of the planner that don't need a world. the facing and half themselves come from vanilla's own
// getStateForPlacement, which wants a real Level, so that part only gets checked in game
public class PlacementPlannerTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    @Test
    public void preferredFaceGoesFirstAndNothingGoesMissing() {
        Direction[] faces = PlacementPlanner.facesFirst(Direction.WEST);
        assertEquals(Direction.WEST, faces[0]);
        assertEquals(6, faces.length);
        assertEquals(6, java.util.EnumSet.copyOf(List.of(faces)).size());
        assertEquals(Direction.values().length, PlacementPlanner.facesFirst(null).length);
    }

    @Test
    public void sideFaceSamplesStayClearOfTheMiddle() {
        // a stair's half on a side click is the hit's y against 0.5, so the samples can't sit on it
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (var m : PlacementPlanner.aabbSideMultipliers(side)) {
                assertTrue(Math.abs(m.y - 0.5) >= 0.25);
            }
        }
    }

    @Test
    public void aStairStackSuppliesEveryFacing() {
        // approxPlaceable has whatever way we faced when it was asked, the planner turns us around later
        assertTrue(BuilderProcess.canSupply(stairs(Direction.NORTH), stairs(Direction.SOUTH)));
        assertTrue(BuilderProcess.canSupply(stairs(Direction.NORTH), stairs(Direction.EAST).setValue(StairBlock.HALF, Half.TOP)));
        assertTrue(BuilderProcess.containsItem(List.of(Blocks.STONE.defaultBlockState(), stairs(Direction.WEST)), stairs(Direction.SOUTH)));
        assertFalse(BuilderProcess.canSupply(Blocks.STONE.defaultBlockState(), stairs(Direction.SOUTH)));
        assertFalse(BuilderProcess.canSupply(Blocks.SPRUCE_STAIRS.defaultBlockState(), stairs(Direction.SOUTH)));
        // air is no item, two of them aren't a match
        assertFalse(BuilderProcess.sameItem(Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertTrue(BuilderProcess.canSupply(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        // a torch stack is the wall torch's stack too, the planner gets the wall one out of the item
        assertTrue(BuilderProcess.canSupply(Blocks.TORCH.defaultBlockState(), Blocks.WALL_TORCH.defaultBlockState()));
        assertTrue(BuilderProcess.canSupply(Blocks.OAK_SIGN.defaultBlockState(), Blocks.OAK_WALL_SIGN.defaultBlockState()));
        assertFalse(BuilderProcess.canSupply(Blocks.TORCH.defaultBlockState(), Blocks.SOUL_WALL_TORCH.defaultBlockState()));
        // kelp plant has no item, so kelp doesn't count for it
        assertFalse(BuilderProcess.sameItem(Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState()));
    }

    @Test
    public void plannedGoalsGoLowestCellFirst() {
        BlockPos feet = new BlockPos(0, 64, 0);
        BuilderProcess.GoalPlanned low = new BuilderProcess.GoalPlanned(feet, 64);
        BuilderProcess.GoalPlanned high = new BuilderProcess.GoalPlanned(feet, 65);
        assertTrue(low.isInGoal(0, 64, 0));
        assertFalse(low.isInGoal(1, 64, 0));
        assertTrue(low.heuristic(5, 64, 5) < high.heuristic(5, 64, 5));
        assertNotEquals(low, high);
        assertEquals(low, new BuilderProcess.GoalPlanned(feet, 64));
    }
}
