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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// putting dug terrain back: holes get filled, cells something else took are left alone
public class RestoreSchematicTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    @Test
    public void fillsTheHolesItWasGiven() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(10, 64, 10), Blocks.STONE.defaultBlockState());
        plan.put(new BlockPos(12, 65, 10), Blocks.COBBLESTONE.defaultBlockState());
        RestoreSchematic s = new RestoreSchematic(plan);
        assertEquals(new BlockPos(10, 64, 10), s.origin());
        assertEquals(3, s.widthX());
        assertEquals(2, s.heightY());
        assertEquals(1, s.lengthZ());
        assertTrue(s.inSchematic(0, 0, 0, air()));
        assertEquals(Blocks.STONE.defaultBlockState(), s.desiredState(0, 0, 0, air(), List.of()));
        assertTrue(s.inSchematic(2, 1, 0, Blocks.WATER.defaultBlockState()));
        // between the two is nothing of ours
        assertFalse(s.inSchematic(1, 0, 0, air()));
    }

    @Test
    public void aCellSomebodyFilledIsLeftAlone() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        RestoreSchematic s = new RestoreSchematic(plan);
        assertFalse(s.inSchematic(0, 0, 0, Blocks.OAK_PLANKS.defaultBlockState()));
        // decided once: the planks being broken later doesn't make it ours
        assertFalse(s.inSchematic(0, 0, 0, air()));
        s.reset();
        assertTrue(s.inSchematic(0, 0, 0, air()));
        // filled: still ours, and done
        assertTrue(s.inSchematic(0, 0, 0, Blocks.STONE.defaultBlockState()));
    }
}
