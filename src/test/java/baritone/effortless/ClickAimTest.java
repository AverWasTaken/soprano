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
    public void wallCornerGoesUpTheWall() {
        // facing +z at a wall along X through the first corner, 5 blocks ahead, looking up a bit
        ShapeSpec wall = ShapeSpec.of(ShapeKind.WALL).withAxis(ShapeSpec.Axis.X);
        BlockPos a = new BlockPos(0, 64, 5);
        Optional<BlockPos> p = ClickAim.next(wall, List.of(a), 3.5, 65.6, 0.5, 0, 0.5, 1);
        assertEquals(Optional.of(new BlockPos(3, 68, 5)), p);
    }

    @Test
    public void freeWallTakesThePlaneYouFace() {
        ShapeSpec wall = ShapeSpec.of(ShapeKind.WALL);
        BlockPos a = new BlockPos(0, 64, 5);
        // mostly facing +z, so the wall runs along X through z=5
        BlockPos p = ClickAim.next(wall, List.of(a), 3.5, 65.6, 0.5, 0.2, 0.3, 1).orElseThrow();
        assertEquals(5, p.getZ());
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
    public void freeLineHasNoPlane() {
        assertTrue(ClickAim.next(ShapeSpec.of(ShapeKind.LINE), List.of(ORIGIN), 0.5, 65.6, 0.5, 1, 0, 0).isEmpty());
    }
}
