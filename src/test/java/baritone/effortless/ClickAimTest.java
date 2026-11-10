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

// the corners after the first follow your look along the shape's plane
public class ClickAimTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    @Test
    public void floorCornerFollowsTheLookAcrossTheLayer() {
        // standing at 0,65.6,0 looking down at 45 degrees towards +x: the layer at 64.5 is 1.1 below, so 1.1 out
        Optional<BlockPos> p = ClickAim.next(ShapeSpec.of(ShapeKind.FLOOR), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, -1, 0);
        assertEquals(Optional.of(new BlockPos(1, 64, 0)), p);
        // far: a shallow look lands 30 blocks off, still on the layer
        Optional<BlockPos> far = ClickAim.next(ShapeSpec.of(ShapeKind.CUBOID), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, -1.1 / 30, 0);
        assertEquals(64, far.orElseThrow().getY());
        assertEquals(30, far.orElseThrow().getX());
    }

    @Test
    public void lookingUpNeverReachesTheFloor() {
        assertTrue(ClickAim.next(ShapeSpec.of(ShapeKind.FLOOR), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, 0.5, 0).isEmpty());
    }

    @Test
    public void wallSecondCornerIsOnTheGround() {
        // where the wall ends: across the first corner's layer, like a floor's corner
        BlockPos a = new BlockPos(0, 64, 5);
        Optional<BlockPos> p = ClickAim.next(ShapeSpec.of(ShapeKind.WALL), List.of(a), 0.5, 65.6, 0.5, 6, -1.1, 4.5);
        assertEquals(64, p.orElseThrow().getY());
        assertEquals(6, p.orElseThrow().getX());
    }

    @Test
    public void wallThirdCornerIsTheHeight() {
        BlockPos a = new BlockPos(0, 64, 5);
        BlockPos b = new BlockPos(6, 64, 5);
        BlockPos p = ClickAim.next(ShapeSpec.of(ShapeKind.DIAGONAL_WALL), List.of(a, b), 6.5, 65.6, 0.5, 0, 0.5, 1).orElseThrow();
        assertEquals(6, p.getX());
        assertTrue(p.getY() > 64);
    }

    @Test
    public void autoLineTakesTheAxisYouLookAlong() {
        // standing beside the column and looking up it: a vertical line
        BlockPos up = ClickAim.next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 2.5, 65.6, 0.5, -0.2, 1, 0).orElseThrow();
        assertEquals(0, up.getX());
        assertTrue(up.getY() > 66);
        // looking out along the ground in +x: an X line
        BlockPos along = ClickAim.next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, -0.15, 0).orElseThrow();
        assertEquals(64, along.getY());
        assertTrue(along.getX() > 3);
    }

    @Test
    public void heightClickStaysAboveTheSecondCorner() {
        BlockPos b = new BlockPos(6, 64, 0);
        // the third click: looking up at the column over 6,_,0 from the side
        BlockPos p = ClickAim.next(ShapeSpec.of(ShapeKind.CUBOID), List.of(ORIGIN, b), 0.5, 65.6, 0.5, 1, 0.5, 0).orElseThrow();
        assertEquals(6, p.getX());
        assertEquals(0, p.getZ());
        assertEquals(68, p.getY());
    }

    @Test
    public void lockedLineSnapsToItsAxis() {
        ShapeSpec line = ShapeSpec.of(ShapeKind.LINE).withAxis(ShapeSpec.Axis.Y);
        // looking at a point 10 up the column over the origin, from 4 blocks off
        BlockPos p = ClickAim.next(line, List.of(ORIGIN), 4.5, 65.6, 0.5, -4, 8.9, 0).orElseThrow();
        assertEquals(0, p.getX());
        assertEquals(0, p.getZ());
        assertEquals(74, p.getY());
    }

    @Test
    public void diagonalLineHasNoPlane() {
        assertTrue(ClickAim.next(ShapeSpec.of(ShapeKind.DIAGONAL_LINE), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, 0, 0).isEmpty());
    }

    @Test
    public void standingOnTheCornerLookingUpIsAVerticalLine() {
        // the eye is on the corner's own column: look up, the line goes up
        BlockPos p = ClickAim.next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 0.5, 65.6, 0.5, 0.05, 1, 0.12).orElseThrow();
        assertEquals(0, p.getX());
        assertEquals(0, p.getZ());
        assertTrue(p.getY() > 68);
    }
}
