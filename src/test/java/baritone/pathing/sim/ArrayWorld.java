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

package baritone.pathing.sim;

// a few blocks in a box (public so the executor-side tests in other packages can stand on it), everything outside it is air. no minecraft registries needed, which is the point
public final class ArrayWorld implements CollisionProvider {

    // ICE is the old stand-in for "something the sim doesn't do" (not plain). the rest are the real materials, numbers off
    // the 26.3 blocks: packed ice friction 0.98, slime friction 0.8 and a full bounce, soul sand slows to 0.4
    public static final byte AIR = 0, STONE = 1, SLAB = 2, ICE = 3, PACKED_ICE = 4, SLIME = 5, SOUL_SAND = 6;
    // a ladder on the west side of its cell (hung on the block to the west, ladder[facing=east]) and a vine, which has
    // no box at all
    public static final byte LADDER_ON_WEST = 7, VINE = 8;
    // a cobblestone wall and an oak fence: a post a block and a half tall, joined to the same next door the way the game does
    public static final byte WALL = 9, FENCE = 10;

    private final byte[] blocks;
    private final int sx, sy, sz;

    public ArrayWorld(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.blocks = new byte[sx * sy * sz];
    }

    public void set(int x, int y, int z, byte type) {
        blocks[(x * sy + y) * sz + z] = type;
    }

    // fills a cuboid, corners inclusive
    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, byte type) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(x, y, z, type);
                }
            }
        }
    }

    private byte at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
            return AIR;
        }
        return blocks[(x * sy + y) * sz + z];
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        switch (at(x, y, z)) {
            case STONE:
            case ICE:
            case PACKED_ICE:
            case SLIME:
                out.add(x, y, z, x + 1, y + 1, z + 1);
                break;
            case SLAB:
                out.add(x, y, z, x + 1, y + 0.5, z + 1);
                break;
            case SOUL_SAND:
                out.add(x, y, z, x + 1, y + 0.875, z + 1);
                break;
            case LADDER_ON_WEST:
                out.add(x, y, z, x + 0.1875, y + 1, z + 1);
                break;
            case WALL:
                post(x, y, z, 0.25, 0.3125, WALL, out);
                break;
            case FENCE:
                post(x, y, z, 0.375, 0.375, FENCE, out);
                break;
            default:
                break;
        }
    }

    private void post(int x, int y, int z, double post, double arm, byte kind, BoxList out) {
        out.add(x + post, y, z + post, x + 1 - post, y + 1.5, z + 1 - post);
        if (at(x, y, z - 1) == kind) {
            out.add(x + arm, y, z, x + 1 - arm, y + 1.5, z + 0.5);
        }
        if (at(x, y, z + 1) == kind) {
            out.add(x + arm, y, z + 0.5, x + 1 - arm, y + 1.5, z + 1);
        }
        if (at(x - 1, y, z) == kind) {
            out.add(x, y, z + arm, x + 0.5, y + 1.5, z + 1 - arm);
        }
        if (at(x + 1, y, z) == kind) {
            out.add(x + 0.5, y, z + arm, x + 1, y + 1.5, z + 1 - arm);
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        return at(x, y, z) != ICE;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        byte b = at(x, y, z);
        return b == LADDER_ON_WEST || b == VINE;
    }

    @Override
    public float friction(int x, int y, int z) {
        byte b = at(x, y, z);
        return b == PACKED_ICE ? 0.98F : b == SLIME ? 0.8F : 0.6F;
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        return at(x, y, z) == SOUL_SAND ? 0.4F : 1.0F;
    }

    @Override
    public float bounce(int x, int y, int z) {
        return at(x, y, z) == SLIME ? 1.0F : 0.0F;
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        return at(x, y, z) == SLIME;
    }
}
