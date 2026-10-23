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

package baritone.pathing.tungsten;

import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.CollisionProvider;

import java.util.Arrays;

// a box of the world copied out cell by cell, for a search that runs on another thread: the planner's block cache isn't for
// sharing, and what's being searched is what the blocks were when the planner asked. outside the box it's air the sim may
// not go into (the search keeps well inside it anyway)
public final class SnapshotCollision implements CollisionProvider {

    private final int x0, y0, z0, sx, sy, sz;
    // per cell: where its boxes start in `boxes` (6 doubles each) and how many
    private final int[] start, count;
    private final double[] boxes;
    // the same, a cell at a time (see boxArray), made the first time a cell is asked for that way
    private final double[][] cellBoxes;
    private static final double[] NO_BOXES = new double[0];
    private final float[] friction, speed, jump, bounce;
    private final byte[] flags, support;
    private static final byte PLAIN = 1, SLIME_STEP = 2, CLIMBABLE = 4;
    // a hash of everything copied, to tell whether the blocks have changed since
    public final long hash;

    public SnapshotCollision(CollisionProvider world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.x0 = minX;
        this.y0 = minY;
        this.z0 = minZ;
        this.sx = maxX - minX + 1;
        this.sy = maxY - minY + 1;
        this.sz = maxZ - minZ + 1;
        int n = sx * sy * sz;
        start = new int[n];
        count = new int[n];
        friction = new float[n];
        speed = new float[n];
        jump = new float[n];
        bounce = new float[n];
        flags = new byte[n];
        support = new byte[n];
        BoxList all = new BoxList(), cell = new BoxList();
        long h = 1469598103934665603L;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int i = index(x, y, z);
                    cell.clear();
                    world.boxes(x, y, z, cell);
                    start[i] = all.size();
                    count[i] = cell.size();
                    for (int b = 0; b < cell.size(); b++) {
                        all.add(cell.get(b, 0), cell.get(b, 1), cell.get(b, 2), cell.get(b, 3), cell.get(b, 4), cell.get(b, 5));
                        for (int c = 0; c < 6; c++) {
                            h = (h ^ Double.doubleToLongBits(cell.get(b, c))) * 1099511628211L;
                        }
                    }
                    friction[i] = world.friction(x, y, z);
                    speed[i] = world.speedFactor(x, y, z);
                    jump[i] = world.jumpFactor(x, y, z);
                    bounce[i] = world.bounce(x, y, z);
                    support[i] = (byte) world.supportKind(x, y, z);
                    flags[i] = (byte) ((world.plain(x, y, z) ? PLAIN : 0) | (world.slimeStep(x, y, z) ? SLIME_STEP : 0)
                            | (world.climbable(x, y, z) ? CLIMBABLE : 0));
                    h = (h ^ (flags[i] * 31L + Float.floatToIntBits(friction[i]) + Float.floatToIntBits(bounce[i]) * 7L + support[i])) * 1099511628211L;
                }
            }
        }
        boxes = Arrays.copyOf(all.raw(), all.size() * 6);
        cellBoxes = new double[n][];
        hash = h;
    }

    private int index(int x, int y, int z) {
        return ((x - x0) * sy + (y - y0)) * sz + (z - z0);
    }

    private int at(int x, int y, int z) {
        if (x < x0 || y < y0 || z < z0 || x >= x0 + sx || y >= y0 + sy || z >= z0 + sz) {
            return -1;
        }
        return index(x, y, z);
    }

    @Override
    public double[] boxArray(int x, int y, int z) {
        int i = at(x, y, z);
        if (i < 0) {
            return NO_BOXES;
        }
        double[] known = cellBoxes[i];
        if (known == null) {
            // a race between two threads here only makes the same array twice
            known = count[i] == 0 ? NO_BOXES : Arrays.copyOfRange(boxes, start[i] * 6, (start[i] + count[i]) * 6);
            cellBoxes[i] = known;
        }
        return known;
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        int i = at(x, y, z);
        if (i < 0) {
            return;
        }
        for (int b = start[i], e = start[i] + count[i]; b < e; b++) {
            int o = b * 6;
            out.add(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]);
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & PLAIN) != 0;
    }

    @Override
    public float friction(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0.6F : friction[i];
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1F : speed[i];
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1F : jump[i];
    }

    @Override
    public float bounce(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0F : bounce[i];
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & SLIME_STEP) != 0;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & CLIMBABLE) != 0;
    }

    @Override
    public int supportKind(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0 : support[i];
    }
}
