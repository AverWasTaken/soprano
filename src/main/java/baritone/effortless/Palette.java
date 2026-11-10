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

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// what goes in each cell of a place job. one block is a palette of one. more than one is the randomizer: the pick is a
// hash of the cell and the seed, never Random, so the preview, the material bill and the builder all get the same
// block (and rotation) for the same cell, and a recount after a gather doesn't reshuffle the floor
public final class Palette {

    // weight is relative, 3 and 1 is 75% / 25%. randomRotation turns the block a random valid way per cell
    public record Entry(BlockState state, int weight, boolean randomRotation) {
        public Entry {
            if (state == null) {
                throw new IllegalArgumentException("no block");
            }
            weight = Math.max(1, weight);
        }
    }

    // the properties that are "which way it faces" and nothing else. a slab's type or a door's hinge change what the
    // block is, those stay as the entry has them
    private static final List<Property<?>> ROTATIONS = List.of(
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.HALF
    );

    private final List<Entry> entries;
    private final long seed;
    private final int totalWeight;
    // per entry, every state it may be turned to. only filled for entries with randomRotation
    private final List<List<BlockState>> turns;

    public Palette(List<Entry> entries, long seed) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a palette needs a block");
        }
        this.entries = List.copyOf(entries);
        this.seed = seed;
        int total = 0;
        List<List<BlockState>> t = new ArrayList<>(entries.size());
        for (Entry e : this.entries) {
            total += e.weight();
            t.add(e.randomRotation() ? turns(e.state()) : List.of(e.state()));
        }
        this.totalWeight = total;
        this.turns = t;
    }

    public static Palette of(BlockState state) {
        return new Palette(List.of(new Entry(state, 1, false)), 0);
    }

    public List<Entry> entries() {
        return entries;
    }

    public long seed() {
        return seed;
    }

    public boolean isSingle() {
        return entries.size() == 1 && !entries.get(0).randomRotation();
    }

    public Palette withSeed(long newSeed) {
        return new Palette(entries, newSeed);
    }

    // world coords
    public BlockState pick(int x, int y, int z) {
        if (entries.size() == 1 && turns.get(0).size() == 1) {
            return entries.get(0).state();
        }
        long h = mix(x, y, z, seed);
        int i = entryFor(h);
        List<BlockState> options = turns.get(i);
        if (options.size() == 1) {
            return options.get(0);
        }
        // a second, independent draw for the turn, so "which block" and "which way" don't move together
        long h2 = mix(x, y, z, seed ^ 0x9E3779B97F4A7C15L);
        return options.get((int) Math.floorMod(h2, (long) options.size()));
    }

    private int entryFor(long h) {
        int roll = (int) Math.floorMod(h, (long) totalWeight);
        for (int i = 0; i < entries.size(); i++) {
            roll -= entries.get(i).weight();
            if (roll < 0) {
                return i;
            }
        }
        return entries.size() - 1;
    }

    // how many of each block state the cells take. the bill, and the preview's counts
    public Map<BlockState, Integer> count(AreaMask cells, CellTest include) {
        Map<BlockState, Integer> out = new LinkedHashMap<>();
        cells.forEach((x, y, z) -> {
            if (include == null || include.test(x, y, z)) {
                out.merge(pick(x, y, z), 1, Integer::sum);
            }
        });
        return out;
    }

    public interface CellTest {
        boolean test(int x, int y, int z);
    }

    // whether random rotation would do anything for this block (stone has nothing to turn)
    public static boolean canTurn(BlockState s) {
        return turns(s).size() > 1;
    }

    // every state of this block that differs from it only in the rotation properties it has
    static List<BlockState> turns(BlockState base) {
        List<BlockState> out = new ArrayList<>();
        out.add(base);
        for (Property<?> p : ROTATIONS) {
            if (!base.hasProperty(p)) {
                continue;
            }
            List<BlockState> next = new ArrayList<>();
            for (BlockState s : out) {
                addAll(next, s, p);
            }
            out = next;
        }
        return List.copyOf(out);
    }

    private static <T extends Comparable<T>> void addAll(List<BlockState> into, BlockState s, Property<T> p) {
        for (T value : p.getPossibleValues()) {
            into.add(s.setValue(p, value));
        }
    }

    // splitmix64 over the cell. cheap, and neighbouring cells don't come out looking like stripes
    static long mix(int x, int y, int z, long seed) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = splitmix(h);
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h = splitmix(h);
        h ^= z * 0x165667B19E3779F9L;
        return splitmix(h);
    }

    private static long splitmix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Palette p && p.seed == seed && p.entries.equals(entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode() * 31 + Long.hashCode(seed);
    }
}
