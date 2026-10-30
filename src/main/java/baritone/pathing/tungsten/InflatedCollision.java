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

// the world with everything the body could brush past fattened sideways by a margin: a plan the search finds in here clears
// every wall by at least that much in the real one, which is the room a wobbly aim gets. what we stand on goes the other
// way, thinner (fattening a floor would let the plan stand further out over an edge than the real box can): a cell at feet
// height or lower with nothing over it is a floor, everything else at body height is something to keep clear of
public final class InflatedCollision implements CollisionProvider {

    private final CollisionProvider world;
    private final double margin, floorMargin;
    // feet cells of the lower and the higher end of the jump
    private final int minFeetY, maxFeetY;
    private final BoxList scratch = new BoxList(), above = new BoxList(), built = new BoxList();
    // each cell's fattened boxes, worked out the first time the search asks (a search asks about the same few hundred
    // cells millions of times). an instance is one search's, on one thread
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> cells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final double[] EMPTY = new double[0];
    // and when the world is a snapshot (a background search's always is), straight by its cell index instead
    private final SnapshotCollision snap;
    private final double[][] dense;

    public InflatedCollision(CollisionProvider world, double margin, int minFeetY, int maxFeetY) {
        this(world, margin, margin, minFeetY, maxFeetY);
    }

    public InflatedCollision(CollisionProvider world, double margin, double floorMargin, int minFeetY, int maxFeetY) {
        this.world = world;
        this.margin = margin;
        this.floorMargin = floorMargin;
        this.minFeetY = minFeetY;
        this.maxFeetY = maxFeetY;
        this.snap = world instanceof SnapshotCollision sc ? sc : null;
        this.dense = snap == null ? null : new double[snap.cellCount()][];
    }

    @Override
    public double[] boxArray(int x, int y, int z) {
        if (snap != null) {
            int i = snap.cellIndex(x, y, z);
            if (i < 0) {
                return EMPTY;
            }
            double[] a = dense[i];
            if (a == null) {
                built.clear();
                compute(x, y, z, built);
                dense[i] = a = toArray(built);
            }
            return a;
        }
        long k = net.minecraft.core.BlockPos.asLong(x, y, z);
        double[] a = cells.get(k);
        if (a == null) {
            built.clear();
            compute(x, y, z, built);
            a = toArray(built);
            if (cells.size() > 200_000) {
                cells.clear();
            }
            cells.put(k, a);
        }
        return a;
    }

    private static double[] toArray(BoxList b) {
        if (b.size() == 0) {
            return EMPTY;
        }
        double[] a = new double[b.size() * 6];
        for (int i = 0; i < b.size(); i++) {
            for (int c = 0; c < 6; c++) {
                a[i * 6 + c] = b.get(i, c);
            }
        }
        return a;
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        double[] a = boxArray(x, y, z);
        for (int o = 0; o < a.length; o += 6) {
            out.add(a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4], a[o + 5]);
        }
    }

    private void compute(int x, int y, int z, BoxList out) {
        scratch.clear();
        world.boxes(x, y, z, scratch);
        if (scratch.size() == 0) {
            return;
        }
        boolean floor = y < minFeetY;
        if (!floor && y < maxFeetY) {
            above.clear();
            world.boxes(x, y + 1, z, above);
            floor = above.size() == 0;
        }
        if (floor) {
            // and what we stand on thinned by as much: a takeoff off the very corner of a block, or a touchdown on its
            // last sliver, is a takeoff a wobble turns into a walk off. only a floor's top layer (the one the feet touch)
            above.clear();
            world.boxes(x, y + 1, z, above);
            double m = above.size() == 0 ? floorMargin : 0;
            for (int i = 0; i < scratch.size(); i++) {
                double minX = scratch.get(i, 0) + m, maxX = scratch.get(i, 3) - m, minZ = scratch.get(i, 2) + m, maxZ = scratch.get(i, 5) - m;
                if (minX < maxX && minZ < maxZ) {
                    out.add(minX, scratch.get(i, 1), minZ, maxX, scratch.get(i, 4), maxZ);
                }
            }
            return;
        }
        double m = margin;
        for (int i = 0; i < scratch.size(); i++) {
            out.add(scratch.get(i, 0) - m, scratch.get(i, 1), scratch.get(i, 2) - m, scratch.get(i, 3) + m, scratch.get(i, 4), scratch.get(i, 5) + m);
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        return world.plain(x, y, z);
    }

    @Override
    public float friction(int x, int y, int z) {
        return world.friction(x, y, z);
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        return world.speedFactor(x, y, z);
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        return world.jumpFactor(x, y, z);
    }

    @Override
    public float bounce(int x, int y, int z) {
        return world.bounce(x, y, z);
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        return world.slimeStep(x, y, z);
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        return world.climbable(x, y, z);
    }

    @Override
    public float water(int x, int y, int z) {
        return world.water(x, y, z);
    }

    @Override
    public int supportKind(int x, int y, int z) {
        return world.supportKind(x, y, z);
    }
}
