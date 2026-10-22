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

package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// in the nether the usual way to die is not the lava, it's the walk along the cliff above it: an arrow or a fireball knocks us
// two blocks sideways and the drop is thirty blocks into the sea. so a node with a drop within two blocks of it pays a little,
// enough to take the inland route when there is one and not enough to turn down a ridge when that's all there is
public final class CliffAvoidance {

    // about a block and a bit of walking, per node. it adds up along a ridge, which is the point, but the heuristic knows
    // nothing about it so every extra tick of this is more nodes the search has to eat before it gives up on the cheap way
    public static final double NODE_COST = ActionCosts.WALK_ONE_BLOCK_COST * 1.25;
    // fortress bridges are all there is to walk on, and the wither skeletons up there are why we'd be near the edge anyway
    public static final double FORTRESS_NODE_COST = NODE_COST / 3;
    // how far sideways a knockback is worth worrying about
    static final int REACH = 2;
    // a floor this close is a short fall, further than this is the sea
    static final int DEPTH = 8;

    private CliffAvoidance() {}

    // per search, not per context: contexts live across segments, and a column that was in an unloaded chunk last time (or
    // that we have bridged or mined since) has a different answer now. the scan is the cost, and every node asks about the
    // same columns as its neighbours do
    public static final class Memo {
        private final Long2ByteOpenHashMap columns = new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap nodes = new Long2ByteOpenHashMap();

        public Memo() {
            columns.defaultReturnValue((byte) -1);
            nodes.defaultReturnValue((byte) -1);
        }
    }

    // the extra cost of ending a movement with our feet at (x, y, z). a no-op outside the nether, see CalculationContext.cliffEdges
    public static double penalty(CalculationContext context, Memo memo, int x, int y, int z) {
        if (!context.cliffEdges || !nearADrop(context.bsi, memo, x, y, z)) {
            return 0;
        }
        return onFortressBricks(context.get(x, y - 1, z)) ? FORTRESS_NODE_COST : NODE_COST;
    }

    static boolean onFortressBricks(BlockState floor) {
        return floor.is(Blocks.NETHER_BRICKS) || floor.is(Blocks.NETHER_BRICK_SLAB) || floor.is(Blocks.NETHER_BRICK_STAIRS);
    }

    // any other column within REACH of (x, z) that we could stand in at this height and that has nothing to land on. our own
    // column is left out on purpose: a bridge step's floor is about to be placed and a node on a ridge has its floor already,
    // so either way it's the neighbours that say whether the edge is near, and a bridge out over the void sees the void
    static boolean nearADrop(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.nodes.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean found = false;
        search:
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if ((dx != 0 || dz != 0) && dropColumn(bsi, memo, x + dx, y, z + dz)) {
                    found = true;
                    break search;
                }
            }
        }
        memo.nodes.put(key, (byte) (found ? 1 : 0));
        return found;
    }

    private static boolean dropColumn(BlockStateInterface bsi, Memo memo, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = memo.columns.get(key);
        if (known >= 0) {
            return known == 1;
        }
        boolean drop = scanColumn(bsi, x, y, z);
        memo.columns.put(key, (byte) (drop ? 1 : 0));
        return drop;
    }

    private static boolean scanColumn(BlockStateInterface bsi, int x, int y, int z) {
        // the bsi reads air for chunks we don't have, which would look like the biggest cliff ever
        if (!bsi.isLoaded(x, z) || !MovementHelper.canWalkThrough(bsi, x, y, z)) {
            return false;
        }
        for (int d = 1; d <= DEPTH; d++) {
            BlockState below = bsi.get0(x, y - d, z);
            if (MovementHelper.isLava(below)) {
                return true;
            }
            // a solid floor, or water to land in
            if (MovementHelper.isWater(below) || !MovementHelper.canWalkThrough(bsi, x, y - d, z, below)) {
                return false;
            }
        }
        return true;
    }
}
