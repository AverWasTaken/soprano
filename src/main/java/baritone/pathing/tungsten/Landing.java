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

import net.minecraft.util.Mth;

// the cell a jump has to end up standing in, counted the way the rest of Baritone counts feet (IPlayerContext.playerFeet:
// the floor of y + 0.1251, so a floor a little under a full block still puts the feet in the cell above it)
// low: the block to land on is a bottom slab, half a block down in the cell under the feet. Baritone still counts the feet in
// the cell over the slab (IPlayerContext.playerFeet bumps a slab cell up one), so that's y, but the feet really rest half a
// block lower, in the slab's own cell
// a stair is low too, on its lower step: the feet can come down there, half a block into the stair's own cell, and settling
// walks them up onto the middle of it (on the high step, where the cell is right again). half: the whole top is the low
// one (a slab), so that's the height to aim for
// ax az: where in the cell to come down, when that isn't its middle: the floor is only along one side of it (the top of a
// ladder, a head on a wall), so the middle is thin air
// water: a water block to come down into (and swim out of the top of, the next jump), not a floor
public record Landing(int x, int y, int z, boolean grab, boolean low, boolean half, double ax, double az, boolean water) {

    public Landing(int x, int y, int z, boolean grab, boolean low, boolean half, double ax, double az) {
        this(x, y, z, grab, low, half, ax, az, false);
    }

    public Landing(int x, int y, int z, boolean grab, boolean low, boolean half) {
        this(x, y, z, grab, low, half, 0.5, 0.5);
    }

    // into the water in this cell (the top of a column of it, or a lone block of it)
    public static Landing swim(int x, int y, int z) {
        return swim(x, y, z, false);
    }

    // deep: the top of a column of water at least two high (low says so, for a water landing): getting the top of us into
    // its lower block is enough, it holds us. a lone block has to be got into properly, or we sink out of its bottom
    public static Landing swim(int x, int y, int z, boolean deep) {
        return new Landing(x, y, z, false, deep, false, 0.5, 0.5, true);
    }

    public Landing(int x, int y, int z) {
        this(x, y, z, false, false, false);
    }

    public Landing(int x, int y, int z, boolean grab) {
        this(x, y, z, grab, false, false);
    }

    // the same landing, coming down at x + ax, z + az instead of the middle
    public Landing aimed(double ax, double az) {
        return new Landing(x, y, z, grab, low, half, ax, az, water);
    }

    // a landing on a bottom slab
    public static Landing slab(int x, int y, int z) {
        return new Landing(x, y, z, false, true, true);
    }

    // a landing on a stair (or anything else with a step half way down)
    public static Landing step(int x, int y, int z) {
        return new Landing(x, y, z, false, true, false);
    }

    // how high the feet are standing on it
    public double floor() {
        return half ? y - 0.5 : y;
    }

    // where the rest of Baritone will say we are when we're done: in the cell. on a slab that's the slab's cell too (it bumps
    // that up one), on a stair's low step it isn't, so a stair landing isn't finished until we're up on its high step
    public boolean countsAsHere(double py) {
        int fy = feetY(py);
        return fy == y || half && fy == y - 1;
    }

    // feet at this height count as being down on the landing (the slab's or the stair's own cell too, for a low one)
    public boolean feetOk(double py) {
        int fy = feetY(py);
        return fy == y || low && fy == y - 1;
    }

    // a ladder or vine to catch instead of a block to stand on: the feet in that cell, hanging on (see JumpPolicy's GRIP)
    public static Landing grab(int x, int y, int z) {
        return new Landing(x, y, z, true, false, false);
    }

    // holding on, in the ladder's column or one next to it (a ladder two wide: whichever half we caught, the GRIP phase
    // shuffles us over to the right one, sideways along a ladder is fine)
    public boolean gripping(SimState s) {
        return s.climbing && Math.abs(Mth.floor(s.x) - x) + Math.abs(Mth.floor(s.z) - z) <= 1;
    }

    // where a jump at this landing is finished: standing still on it, or hanging on the ladder in its cell
    public boolean reached(SimState s, double still) {
        if (water) {
            // in the water in its column: the top of us in it is enough (it holds us, and the swim up out of it is the next
            // jump's), from the feet two blocks under the cell, coming up into a column from the side, to in the cell itself
            int fy = feetY(s.y);
            return s.inWater && Mth.floor(s.x) == x && Mth.floor(s.z) == z && fy <= y && fy >= y - (low ? 2 : 1);
        }
        if (grab) {
            return s.climbing && Mth.floor(s.x) == x && Mth.floor(s.z) == z && feetY(s.y) == y;
        }
        return s.onGround && holds(s.x, s.y, s.z) && s.horizontalSpeed() < still;
    }

    // how far inside the cell the middle of the box has to come down. the box is 0.6 wide so the floor would hold us well past
    // the edge, but the feet are counted by the middle, and a touchdown right on the line is one gust of lag from the wrong cell
    public static final double MARGIN = 0.06;

    public double centerX() {
        return x + ax;
    }

    public double centerZ() {
        return z + az;
    }

    public static int feetY(double y) {
        return Mth.floor(y + 0.1251);
    }

    public boolean holds(double px, double py, double pz) {
        return Mth.floor(px) == x && Mth.floor(pz) == z && countsAsHere(py);
    }

    // holds, with MARGIN to spare on both horizontal axes
    public boolean holdsWell(double px, double py, double pz) {
        if (!holds(px, py, pz)) {
            return false;
        }
        double fx = px - x, fz = pz - z;
        if (ax != 0.5 || az != 0.5) {
            // a floor along one side of the cell, or a rim: there, not just anywhere in the cell (the next jump goes from there)
            return Math.abs(fx - ax) <= AIM_NEAR && Math.abs(fz - az) <= AIM_NEAR;
        }
        return fx >= MARGIN && fx <= 1 - MARGIN && fz >= MARGIN && fz <= 1 - MARGIN;
    }

    // how near the aim point an aimed landing has to settle
    public static final double AIM_NEAR = 0.15;

    public double horizontalDistance(double px, double pz) {
        double dx = centerX() - px, dz = centerZ() - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
