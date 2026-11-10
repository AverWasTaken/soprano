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

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

// the corners after the first follow your look, the way Effortless Structure traces them. the numbers are worked by
// hand from the planes through the start block's face that's towards the eye
public class ClickAimTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    private static Optional<BlockPos> next(ShapeSpec spec, List<BlockPos> pts, double ex, double ey, double ez,
                                           double dx, double dy, double dz) {
        return ClickAim.next(spec, pts, ex, ey, ez, dx, dy, dz);
    }

    @Test
    public void floorCornerFollowsTheLookAcrossTheLayer() {
        // the top face of the start block is at 65: 0.6 below the eye, so a 45 degree look lands 0.6 out
        assertEquals(Optional.of(new BlockPos(1, 64, 0)),
                next(ShapeSpec.of(ShapeKind.FLOOR), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, -1, 0));
        // a shallow look lands far off, still on the layer
        BlockPos far = next(ShapeSpec.of(ShapeKind.FLOOR), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, -1.1 / 30, 0).orElseThrow();
        assertEquals(64, far.getY());
        assertEquals(16, far.getX());
    }

    @Test
    public void lookingUpNeverReachesTheFloor() {
        assertTrue(next(ShapeSpec.of(ShapeKind.FLOOR), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, 0.5, 0).isEmpty());
    }

    @Test
    public void wallTakesTheSideYouFace() {
        // facing +z at a wall through z=5, looking a bit up and to the side
        BlockPos a = new BlockPos(0, 64, 5);
        BlockPos p = next(ShapeSpec.of(ShapeKind.WALL), List.of(a), 0.5, 65.6, 0.5, 2, 0.9, 4.5).orElseThrow();
        assertEquals(new BlockPos(2, 66, 5), p);
    }

    @Test
    public void bothFacingsTakeTheNearestPlane() {
        // up and to the side of the start, looking back down towards it: the floor is crossed first
        BlockPos p = next(ShapeSpec.of(ShapeKind.CUBE), List.of(ORIGIN), 6.5, 70.6, 0.5, -3, -6.1, 0).orElseThrow();
        assertEquals(new BlockPos(3, 64, 0), p);
        // vertical only can't take the floor
        BlockPos w = next(ShapeSpec.of(ShapeKind.CUBE).withFacing(ShapeSpec.Facing.VERTICAL), List.of(ORIGIN),
                6.5, 70.6, 0.5, -3, -6.1, 0).orElse(null);
        assertTrue(w == null || w.getX() == 0 || w.getZ() == 0);
        assertNotEquals(new BlockPos(3, 64, 0), w);
    }

    @Test
    public void lineTakesTheAxisYouLookAlong() {
        // beside the column, looking up it: a vertical line
        BlockPos up = next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 2.5, 65.6, 0.5, -0.2, 1, 0).orElseThrow();
        assertEquals(new BlockPos(0, 73, 0), up);
        // behind and to the side, looking along +x past it: an x line
        BlockPos along = next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 0.5, 65.6, -3.5, 6, -1.1, 4).orElseThrow();
        assertEquals(new BlockPos(5, 64, 0), along);
    }

    @Test
    public void lockedLineStaysOnItsAxis() {
        ShapeSpec line = ShapeSpec.of(ShapeKind.LINE).withDirection(ShapeSpec.Direction.Y);
        BlockPos p = next(line, List.of(ORIGIN), 4.5, 65.6, 0.5, -4, 8.9, 0).orElseThrow();
        assertEquals(new BlockPos(0, 73, 0), p);
    }

    @Test
    public void thirdClickRunsOutOfThePlane() {
        // a floor face from the origin to 6,_,3: the third click goes straight up from its far corner
        BlockPos b = new BlockPos(6, 64, 3);
        BlockPos p = next(ShapeSpec.of(ShapeKind.CUBE), List.of(ORIGIN, b), 0.5, 65.6, 0.5, 5.5, 3, 3).orElseThrow();
        assertEquals(new BlockPos(6, 68, 3), p);
    }

    @Test
    public void diagonalWallsAreRaisedStraightUp() {
        BlockPos a = new BlockPos(0, 64, 5);
        BlockPos b = new BlockPos(6, 64, 5);
        BlockPos p = next(ShapeSpec.of(ShapeKind.DIAGONAL_WALL), List.of(a, b), 6.5, 65.6, 0.5, 0, 0.5, 1).orElseThrow();
        assertEquals(6, p.getX());
        assertEquals(5, p.getZ());
        assertEquals(67, p.getY());
    }

    @Test
    public void diagonalLineEndsUnderTheCrosshair() {
        assertTrue(next(ShapeSpec.of(ShapeKind.DIAGONAL_LINE), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, 0, 0).isEmpty());
    }

    @Test
    public void equalLengthsMakeASquare() {
        assertEquals(new BlockPos(5, 64, 5), ClickAim.uniform(ORIGIN, new BlockPos(5, 64, 2), true));
        assertEquals(new BlockPos(-4, 64, 4), ClickAim.uniform(ORIGIN, new BlockPos(-4, 64, 1), true));
        assertEquals(new BlockPos(5, 64, 2), ClickAim.uniform(ORIGIN, new BlockPos(5, 64, 2), false));
    }
}
