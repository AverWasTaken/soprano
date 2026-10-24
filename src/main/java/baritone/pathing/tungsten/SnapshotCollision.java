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
//
// kept small: a long #goto can have a couple of hundred of these waiting in the queue. the boxes are one flat array, and
// what each cell is like otherwise (friction, bounce, ...) is a short into a table of the few kinds there are
public final class SnapshotCollision implements CollisionProvider {

    private final int x0, y0, z0, sx, sy, sz;
    // cell i's boxes are boxes[start[i] * 6 .. start[i + 1] * 6)
    private final int[] start;
    private final double[] boxes;
    private final short[] kind;
    private final float[] friction, speed, jump, bounce, water;
    private final byte[] flags, support;
    private static final byte PLAIN = 1, SLIME_STEP = 2, CLIMBABLE = 4;
    // the same boxes, a cell at a time (see boxArray), made the first time the search asks that way
    private volatile double[][] cellBoxes;
    private static final double[] NO_BOXES = new double[0];
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
        start = new int[n + 1];
        kind = new short[n];
        BoxList all = new BoxList(), cell = new BoxList();
        java.util.Map<Kind, Integer> kinds = new java.util.HashMap<>();
        java.util.List<Kind> table = new java.util.ArrayList<>();
        long h = 1469598103934665603L;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int i = index(x, y, z);
                    cell.clear();
                    world.boxes(x, y, z, cell);
                    start[i] = all.size();
                    for (int b = 0; b < cell.size(); b++) {
                        all.add(cell.get(b, 0), cell.get(b, 1), cell.get(b, 2), cell.get(b, 3), cell.get(b, 4), cell.get(b, 5));
                    }
                    Kind k = Kind.of(world, x, y, z);
                    h = mix(h, cell, k);
                    Integer id = kinds.get(k);
                    if (id == null) {
                        id = table.size();
                        kinds.put(k, id);
                        table.add(k);
                    }
                    kind[i] = (short) (int) id;
                }
            }
        }
        start[n] = all.size();
        boxes = Arrays.copyOf(all.raw(), all.size() * 6);
        int m = table.size();
        friction = new float[m];
        speed = new float[m];
        jump = new float[m];
        bounce = new float[m];
        water = new float[m];
        flags = new byte[m];
        support = new byte[m];
        for (int k = 0; k < m; k++) {
            Kind t = table.get(k);
            friction[k] = t.friction;
            speed[k] = t.speed;
            jump[k] = t.jump;
            bounce[k] = t.bounce;
            water[k] = t.water;
            flags[k] = t.flags;
            support[k] = t.support;
        }
        hash = h;
    }

    // the hash a snapshot of this box would have, without making one (the planner asks whether the blocks have changed a
    // lot more often than it needs a snapshot)
    public static long hashOf(CollisionProvider world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BoxList cell = new BoxList();
        long h = 1469598103934665603L;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cell.clear();
                    world.boxes(x, y, z, cell);
                    h = mix(h, cell, Kind.of(world, x, y, z));
                }
            }
        }
        return h;
    }

    private static long mix(long h, BoxList cell, Kind k) {
        for (int b = 0; b < cell.size(); b++) {
            for (int c = 0; c < 6; c++) {
                h = (h ^ Double.doubleToLongBits(cell.get(b, c))) * 1099511628211L;
            }
        }
        return (h ^ (k.flags * 31L + Float.floatToIntBits(k.friction) + Float.floatToIntBits(k.bounce) * 7L + k.support
                + Float.floatToIntBits(k.water) * 13L)) * 1099511628211L;
    }

    // everything about a cell but its boxes
    private record Kind(float friction, float speed, float jump, float bounce, byte flags, byte support, float water) {
        static Kind of(CollisionProvider world, int x, int y, int z) {
            return new Kind(world.friction(x, y, z), world.speedFactor(x, y, z), world.jumpFactor(x, y, z), world.bounce(x, y, z),
                    (byte) ((world.plain(x, y, z) ? PLAIN : 0) | (world.slimeStep(x, y, z) ? SLIME_STEP : 0) | (world.climbable(x, y, z) ? CLIMBABLE : 0)),
                    (byte) world.supportKind(x, y, z), world.water(x, y, z));
        }
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
        double[][] cells = cellBoxes;
        if (cells == null) {
            cellBoxes = cells = new double[kind.length][]; // (two threads racing here each make one, either does)
        }
        double[] known = cells[i];
        if (known == null) {
            known = start[i + 1] == start[i] ? NO_BOXES : Arrays.copyOfRange(boxes, start[i] * 6, start[i + 1] * 6);
            cells[i] = known;
        }
        return known;
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        int i = at(x, y, z);
        if (i < 0) {
            return;
        }
        for (int b = start[i], e = start[i + 1]; b < e; b++) {
            int o = b * 6;
            out.add(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]);
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[kind[i]] & PLAIN) != 0;
    }

    @Override
    public float friction(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0.6F : friction[kind[i]];
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1F : speed[kind[i]];
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1F : jump[kind[i]];
    }

    @Override
    public float bounce(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0F : bounce[kind[i]];
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[kind[i]] & SLIME_STEP) != 0;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[kind[i]] & CLIMBABLE) != 0;
    }

    @Override
    public int supportKind(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0 : support[kind[i]];
    }

    @Override
    public float water(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? -1F : water[kind[i]];
    }
}
