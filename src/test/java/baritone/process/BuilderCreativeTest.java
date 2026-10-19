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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BuilderCreativeTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void blocksComeFromTheirOwnItem() {
        assertEquals(Items.STONE, BuilderProcess.creativeItem(Blocks.STONE.defaultBlockState()));
        assertEquals(Items.OAK_STAIRS, BuilderProcess.creativeItem(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)));
        assertEquals(Items.BEDROCK, BuilderProcess.creativeItem(Blocks.BEDROCK.defaultBlockState()));
    }

    @Test
    public void nothingTheBuilderCantClickInGetsHandedOut() {
        // the place branch only does block items, and a torch item places a standing torch
        assertNull(BuilderProcess.creativeItem(Blocks.WATER.defaultBlockState()));
        assertNull(BuilderProcess.creativeItem(Blocks.LAVA.defaultBlockState()));
        assertNull(BuilderProcess.creativeItem(Blocks.FIRE.defaultBlockState()));
        assertNull(BuilderProcess.creativeItem(Blocks.WALL_TORCH.defaultBlockState()));
        assertNull(BuilderProcess.creativeItem(Blocks.AIR.defaultBlockState()));
    }
}
