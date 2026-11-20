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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

// the randomizer has to be random looking and completely repeatable, both at once
public class PaletteTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState cobble() {
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    @Test
    public void oneBlockIsAlwaysThatBlock() {
        Palette p = Palette.of(stone());
        assertEquals(stone(), p.pick(1, 2, 3));
        assertEquals(stone(), p.pick(-100, 64, 7));
        assertTrue(p.isSingle());
    }

    @Test
    public void sameCellSamePickWhateverTheOrder() {
        Palette p = new Palette(List.of(new Palette.Entry(stone(), 1, false), new Palette.Entry(cobble(), 1, false)), 42);
        BlockState first = p.pick(10, 20, 30);
        for (int i = 0; i < 50; i++) {
            p.pick(i, i, i);
        }
        assertEquals(first, p.pick(10, 20, 30));
        assertEquals(first, new Palette(p.entries(), 42).pick(10, 20, 30));
    }

    @Test
    public void weightsComeOutRoughlyAsAsked() {
        Palette p = new Palette(List.of(new Palette.Entry(stone(), 3, false), new Palette.Entry(cobble(), 1, false)), 7);
        int stones = 0;
        int total = 0;
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                total++;
                if (p.pick(x, 0, z) == stone()) {
                    stones++;
                }
            }
        }
        double share = (double) stones / total;
        assertEquals(0.75, share, 0.03);
    }

    @Test
    public void rerollChangesThePattern() {
        Palette p = new Palette(List.of(new Palette.Entry(stone(), 1, false), new Palette.Entry(cobble(), 1, false)), 1);
        Palette q = p.withSeed(2);
        int differ = 0;
        for (int x = 0; x < 64; x++) {
            if (p.pick(x, 0, 0) != q.pick(x, 0, 0)) {
                differ++;
            }
        }
        assertTrue(differ > 10);
    }

    @Test
    public void randomRotationOnlyTurnsTheBlock() {
        Set<Direction.Axis> seen = new HashSet<>();
        Palette p = new Palette(List.of(new Palette.Entry(Blocks.OAK_LOG.defaultBlockState(), 1, true)), 3);
        for (int x = 0; x < 64; x++) {
            BlockState s = p.pick(x, 0, 0);
            assertEquals(Blocks.OAK_LOG, s.getBlock());
            seen.add(s.getValue(BlockStateProperties.AXIS));
        }
        assertEquals(3, seen.size());
    }

    @Test
    public void stairsTurnEveryWayAndFlip() {
        List<BlockState> turns = Palette.turns(Blocks.OAK_STAIRS.defaultBlockState());
        assertEquals(8, turns.size());
        // the shape and the water stay what the entry said
        for (BlockState s : turns) {
            assertEquals(Blocks.OAK_STAIRS.defaultBlockState().getValue(BlockStateProperties.STAIRS_SHAPE),
                    s.getValue(BlockStateProperties.STAIRS_SHAPE));
        }
        assertEquals(1, Palette.turns(stone()).size());
    }

    @Test
    public void countAddsUpToTheCells() {
        Palette p = new Palette(List.of(new Palette.Entry(stone(), 1, false), new Palette.Entry(cobble(), 1, false)), 9);
        AreaMask m = ShapeGeometry.build(ShapeSpec.of(ShapeKind.FLOOR), List.of(new BlockPos(0, 0, 0), new BlockPos(9, 0, 9)));
        Map<BlockState, Integer> bill = p.count(m, null);
        assertEquals(100, bill.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(2, bill.size());
    }
}
