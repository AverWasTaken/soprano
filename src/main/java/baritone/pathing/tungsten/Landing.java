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
public record Landing(int x, int y, int z, boolean grab) {

    public Landing(int x, int y, int z) {
        this(x, y, z, false);
    }

    // a ladder or vine to catch instead of a block to stand on: the feet in that cell, hanging on (see JumpPolicy's GRIP)
    public static Landing grab(int x, int y, int z) {
        return new Landing(x, y, z, true);
    }

    // holding on, in the ladder's column or one next to it (a ladder two wide: whichever half we caught, the GRIP phase
    // shuffles us over to the right one, sideways along a ladder is fine)
    public boolean gripping(SimState s) {
        return s.climbing && Math.abs(Mth.floor(s.x) - x) + Math.abs(Mth.floor(s.z) - z) <= 1;
    }

    // where a jump at this landing is finished: standing still on it, or hanging on the ladder in its cell
    public boolean reached(SimState s, double still) {
        if (grab) {
            return s.climbing && Mth.floor(s.x) == x && Mth.floor(s.z) == z && feetY(s.y) == y;
        }
        return s.onGround && holds(s.x, s.y, s.z) && s.horizontalSpeed() < still;
    }

    // how far inside the cell the middle of the box has to come down. the box is 0.6 wide so the floor would hold us well past
    // the edge, but the feet are counted by the middle, and a touchdown right on the line is one gust of lag from the wrong cell
    public static final double MARGIN = 0.06;

    public double centerX() {
        return x + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }

    public static int feetY(double y) {
        return Mth.floor(y + 0.1251);
    }

    public boolean holds(double px, double py, double pz) {
        return Mth.floor(px) == x && Mth.floor(pz) == z && feetY(py) == y;
    }

    // holds, with MARGIN to spare on both horizontal axes
    public boolean holdsWell(double px, double py, double pz) {
        if (!holds(px, py, pz)) {
            return false;
        }
        double fx = px - x, fz = pz - z;
        return fx >= MARGIN && fx <= 1 - MARGIN && fz >= MARGIN && fz <= 1 - MARGIN;
    }

    public double horizontalDistance(double px, double pz) {
        double dx = centerX() - px, dz = centerZ() - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
