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

import baritone.api.schematic.ISchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the terrain a job's paths dug through outside its area, put back: each cell and the block it gets. a cell something
// else has filled since (a block somebody placed, sand that fell in) is left as it is, only holes and things you place
// straight over (water that ran in, grass) get filled
public final class RestoreSchematic implements ISchematic {

    private final Map<Long, BlockState> cells;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    // decided once per cell like JobSchematic: a hole stays "ours to fill" while the builder works on it
    private final Map<Long, Boolean> decided = new java.util.concurrent.ConcurrentHashMap<>();

    public RestoreSchematic(Map<BlockPos, BlockState> what) {
        if (what.isEmpty()) {
            throw new IllegalArgumentException("nothing to put back");
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : what.keySet()) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        minX = x0;
        minY = y0;
        minZ = z0;
        sizeX = x1 - x0 + 1;
        sizeY = y1 - y0 + 1;
        sizeZ = z1 - z0 + 1;
        cells = new HashMap<>();
        what.forEach((p, s) -> cells.put(BlockPos.asLong(p.getX(), p.getY(), p.getZ()), s));
    }

    public BlockPos origin() {
        return new BlockPos(minX, minY, minZ);
    }

    public int size() {
        return cells.size();
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        long key = BlockPos.asLong(minX + x, minY + y, minZ + z);
        BlockState want = cells.get(key);
        if (want == null) {
            return false;
        }
        return decided.computeIfAbsent(key, k -> current == want || current.isAir() || current.canBeReplaced());
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        BlockState want = cells.get(BlockPos.asLong(minX + x, minY + y, minZ + z));
        return want == null ? current : want;
    }

    @Override
    public void reset() {
        decided.clear();
    }

    @Override
    public int widthX() {
        return sizeX;
    }

    @Override
    public int heightY() {
        return sizeY;
    }

    @Override
    public int lengthZ() {
        return sizeZ;
    }
}
