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

import java.util.BitSet;

// the cells a shape covers, in world coords. a box plus one bit per cell inside it. made once per click, then the
// preview, the count and the builder all ask the same bits, so they can't disagree about which cells are in
public final class AreaMask {

    public final int minX;
    public final int minY;
    public final int minZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final BitSet bits;
    private final int count;

    private AreaMask(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BitSet bits) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.bits = bits;
        this.count = bits.cardinality();
    }

    public int maxX() {
        return minX + sizeX - 1;
    }

    public int maxY() {
        return minY + sizeY - 1;
    }

    public int maxZ() {
        return minZ + sizeZ - 1;
    }

    // how many cells are in, not the box volume
    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    // world coords
    public boolean contains(int x, int y, int z) {
        return containsLocal(x - minX, y - minY, z - minZ);
    }

    // coords inside the box, 0 based. what an ISchematic gets asked
    public boolean containsLocal(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return false;
        }
        return bits.get(index(x, y, z));
    }

    // calls back with world coords for every cell that's in, x then z then y, so it walks layer by layer
    public void forEach(CellConsumer consumer) {
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            int x = i % sizeX;
            int rest = i / sizeX;
            int z = rest % sizeZ;
            int y = rest / sizeZ;
            consumer.accept(minX + x, minY + y, minZ + z);
        }
    }

    private int index(int x, int y, int z) {
        return x + sizeX * (z + sizeZ * y);
    }

    public interface CellConsumer {
        void accept(int x, int y, int z);
    }

    // the union of several masks (a merged group). later ones don't override anything here, a cell is just in or not
    public static AreaMask union(Iterable<AreaMask> masks) {
        Builder b = null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (AreaMask m : masks) {
            x0 = Math.min(x0, m.minX);
            y0 = Math.min(y0, m.minY);
            z0 = Math.min(z0, m.minZ);
            x1 = Math.max(x1, m.maxX());
            y1 = Math.max(y1, m.maxY());
            z1 = Math.max(z1, m.maxZ());
        }
        if (x0 == Integer.MAX_VALUE) {
            return new Builder(0, 0, 0, 0, 0, 0).build();
        }
        b = new Builder(x0, y0, z0, x1, y1, z1);
        for (AreaMask m : masks) {
            Builder target = b;
            m.forEach(target::add);
        }
        return b.build();
    }

    // collects cells inside a fixed box. anything outside the box is dropped, so shape code can be sloppy at the edges
    public static final class Builder {

        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BitSet bits;

        // corners in any order, both inclusive
        public Builder(int x0, int y0, int z0, int x1, int y1, int z1) {
            this.minX = Math.min(x0, x1);
            this.minY = Math.min(y0, y1);
            this.minZ = Math.min(z0, z1);
            this.sizeX = Math.abs(x1 - x0) + 1;
            this.sizeY = Math.abs(y1 - y0) + 1;
            this.sizeZ = Math.abs(z1 - z0) + 1;
            long volume = (long) sizeX * sizeY * sizeZ;
            if (volume > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("that box is way too big");
            }
            this.bits = new BitSet((int) volume);
        }

        public static long volume(int x0, int y0, int z0, int x1, int y1, int z1) {
            return (long) (Math.abs(x1 - x0) + 1) * (Math.abs(y1 - y0) + 1) * (Math.abs(z1 - z0) + 1);
        }

        public void add(int x, int y, int z) {
            int lx = x - minX;
            int ly = y - minY;
            int lz = z - minZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
                return;
            }
            bits.set(lx + sizeX * (lz + sizeZ * ly));
        }

        public boolean has(int x, int y, int z) {
            int lx = x - minX;
            int ly = y - minY;
            int lz = z - minZ;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) {
                return false;
            }
            return bits.get(lx + sizeX * (lz + sizeZ * ly));
        }

        // the result shrunk to the cells that are actually in, so a sphere's box isn't padded with empty corners
        public AreaMask build() {
            if (bits.isEmpty()) {
                return new AreaMask(minX, minY, minZ, 0, 0, 0, new BitSet());
            }
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                int x = i % sizeX;
                int rest = i / sizeX;
                int z = rest % sizeZ;
                int y = rest / sizeZ;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                z0 = Math.min(z0, z);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
                z1 = Math.max(z1, z);
            }
            int nx = x1 - x0 + 1;
            int ny = y1 - y0 + 1;
            int nz = z1 - z0 + 1;
            if (nx == sizeX && ny == sizeY && nz == sizeZ) {
                return new AreaMask(minX, minY, minZ, sizeX, sizeY, sizeZ, bits);
            }
            BitSet out = new BitSet(nx * ny * nz);
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                int x = i % sizeX;
                int rest = i / sizeX;
                int z = rest % sizeZ;
                int y = rest / sizeZ;
                out.set((x - x0) + nx * ((z - z0) + nz * (y - y0)));
            }
            return new AreaMask(minX + x0, minY + y0, minZ + z0, nx, ny, nz, out);
        }
    }
}
