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

package baritone.utils;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * How many times each chunk has changed (loaded, unloaded, a block in it set) since we came into the world, and how many
 * times any chunk has been loaded or unloaded. An answer worked out from the blocks of some chunks is still good while
 * their numbers are the same: a few lookups, instead of hashing every block again to find out (NeoSearches did that for
 * every jump A* asked about, most of the planner's time), or copying the chunk cache every tick (BlockStateInterface).
 */
public final class ChunkGens {

    private static final Long2LongOpenHashMap GENS = new Long2LongOpenHashMap();
    // bumped on every load and unload, and on a new world
    private static volatile long loads;
    // a new world: every number before it means nothing
    private static volatile long epoch;

    private ChunkGens() {
    }

    private static long key(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    public static void loaded(int cx, int cz) {
        synchronized (GENS) {
            GENS.addTo(key(cx, cz), 1);
        }
        loads++;
    }

    public static void blockChanged(int cx, int cz) {
        synchronized (GENS) {
            GENS.addTo(key(cx, cz), 1);
        }
    }

    public static void newWorld() {
        synchronized (GENS) {
            GENS.clear();
        }
        epoch++;
        loads++;
    }

    public static long loads() {
        return loads;
    }

    /**
     * a number that changes whenever a block in the box (block coordinates, inclusive) might have
     */
    public static long of(int minX, int minZ, int maxX, int maxZ) {
        long h = epoch * 0x9E3779B97F4A7C15L;
        synchronized (GENS) {
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    h = h * 31 + GENS.get(key(cx, cz)) * 1_000_003L + cx * 7919L + cz;
                }
            }
        }
        return h;
    }
}
