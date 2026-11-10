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

import baritone.effortless.ShapeSpec.Axis;
import baritone.effortless.ShapeSpec.Fill;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

// every shape counted by hand once, so a change to the maths shows up as a number that moved
public class ShapeGeometryTest {

    private static BlockPos p(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static AreaMask build(ShapeSpec spec, BlockPos... points) {
        return ShapeGeometry.build(spec, List.of(points));
    }

    private static ShapeSpec spec(ShapeKind kind) {
        return ShapeSpec.of(kind);
    }

    @Test
    public void single() {
        AreaMask m = build(spec(ShapeKind.SINGLE), p(3, 4, 5), p(9, 9, 9));
        assertEquals(1, m.count());
        assertTrue(m.contains(3, 4, 5));
    }

    @Test
    public void lineLockedToAnAxisIgnoresTheOtherCoords() {
        AreaMask m = build(spec(ShapeKind.LINE).withAxis(Axis.X), p(0, 0, 0), p(5, 3, 9));
        assertEquals(6, m.count());
        assertTrue(m.contains(5, 0, 0));
        assertFalse(m.contains(5, 3, 9));
    }

    @Test
    public void thickLineGrowsAcrossNotAlong() {
        AreaMask m = build(spec(ShapeKind.LINE).withAxis(Axis.X).withThickness(3), p(0, 0, 0), p(5, 0, 0));
        assertEquals(6 * 9, m.count());
        assertEquals(6, m.sizeX);
    }

    @Test
    public void freeDiagonalLine() {
        AreaMask m = build(spec(ShapeKind.DIAGONAL_LINE), p(0, 0, 0), p(3, 3, 3));
        assertEquals(4, m.count());
        assertTrue(m.contains(2, 2, 2));
    }

    @Test
    public void floorStaysAtTheFirstClick() {
        AreaMask m = build(spec(ShapeKind.FLOOR), p(0, 0, 0), p(4, 7, 4));
        assertEquals(25, m.count());
        assertEquals(1, m.sizeY);
        assertEquals(16, build(spec(ShapeKind.FLOOR).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 7, 4)).count());
    }

    @Test
    public void straightWall() {
        assertEquals(15, build(spec(ShapeKind.WALL).withAxis(Axis.X), p(0, 0, 0), p(4, 2, 0)).count());
        assertEquals(30, build(spec(ShapeKind.WALL).withAxis(Axis.X).withThickness(2), p(0, 0, 0), p(4, 2, 0)).count());
        // 5 wide, 3 high: top and bottom rows plus the two end cells of the middle row
        assertEquals(12, build(spec(ShapeKind.WALL).withAxis(Axis.X).withFill(Fill.FRAME), p(0, 0, 0), p(4, 2, 0)).count());
    }

    @Test
    public void diagonalWallRunsAtAnAngle() {
        AreaMask m = build(spec(ShapeKind.DIAGONAL_WALL), p(0, 0, 0), p(3, 1, 3));
        assertEquals(8, m.count());
        assertTrue(m.contains(2, 1, 2));
        assertFalse(m.contains(3, 0, 0));
    }

    @Test
    public void straightWallSnapsToTheLongerSide() {
        // aimed a bit off the X axis: the wall still runs straight along X, at the first click's z
        AreaMask m = build(spec(ShapeKind.WALL), p(0, 0, 0), p(6, 0, 2), p(6, 3, 2));
        assertEquals(7 * 4, m.count());
        assertEquals(1, m.sizeZ);
        assertTrue(m.contains(6, 3, 0));
    }

    @Test
    public void wallThirdClickIsTheHeight() {
        AreaMask m = build(spec(ShapeKind.WALL), p(0, 0, 0), p(0, 0, 4), p(9, 2, 9));
        assertEquals(5 * 3, m.count());
        assertEquals(1, m.sizeX);
        assertEquals(3, m.sizeY);
    }

    @Test
    public void autoLineFollowsTheLongestDirection() {
        AreaMask up = build(spec(ShapeKind.LINE), p(0, 0, 0), p(1, 6, 2));
        assertEquals(7, up.count());
        assertEquals(1, up.sizeX);
        assertTrue(up.contains(0, 6, 0));
        AreaMask along = build(spec(ShapeKind.LINE), p(0, 0, 0), p(2, 1, -5));
        assertEquals(6, along.count());
        assertTrue(along.contains(0, 0, -5));
    }

    @Test
    public void diagonalLineGoesCornerToCorner() {
        AreaMask m = build(spec(ShapeKind.DIAGONAL_LINE), p(0, 0, 0), p(3, 3, 3));
        assertEquals(4, m.count());
        assertTrue(m.contains(2, 2, 2));
    }

    @Test
    public void cuboidFills() {
        BlockPos a = p(0, 0, 0);
        BlockPos b = p(2, 2, 2);
        assertEquals(27, build(spec(ShapeKind.CUBOID), a, b).count());
        assertEquals(26, build(spec(ShapeKind.CUBOID).withFill(Fill.HOLLOW), a, b).count());
        assertEquals(24, build(spec(ShapeKind.CUBOID).withFill(Fill.WALLS), a, b).count());
        assertEquals(20, build(spec(ShapeKind.CUBOID).withFill(Fill.FRAME), a, b).count());
    }

    @Test
    public void cuboidThirdClickIsTheHeight() {
        AreaMask m = build(spec(ShapeKind.CUBOID), p(0, 0, 0), p(2, 0, 2), p(5, 4, 5));
        assertEquals(45, m.count());
        assertEquals(3, m.sizeX);
        assertEquals(5, m.sizeY);
    }

    @Test
    public void circle() {
        AreaMask m = build(spec(ShapeKind.CIRCLE), p(0, 0, 0), p(2, 0, 0));
        assertEquals(21, m.count());
        assertEquals(1, m.sizeY);
        AreaMask upright = build(spec(ShapeKind.CIRCLE).withAxis(Axis.Z), p(0, 0, 0), p(2, 0, 0));
        assertEquals(21, upright.count());
        assertEquals(1, upright.sizeZ);
    }

    @Test
    public void cylinder() {
        BlockPos c = p(0, 0, 0);
        BlockPos rim = p(2, 4, 0);
        assertEquals(105, build(spec(ShapeKind.CYLINDER), c, rim).count());
        assertEquals(60, build(spec(ShapeKind.CYLINDER).withFill(Fill.WALLS), c, rim).count());
        assertEquals(78, build(spec(ShapeKind.CYLINDER).withFill(Fill.HOLLOW), c, rim).count());
    }

    @Test
    public void sphere() {
        BlockPos c = p(0, 0, 0);
        BlockPos rim = p(2, 0, 0);
        assertEquals(81, build(spec(ShapeKind.SPHERE), c, rim).count());
        assertEquals(51, build(spec(ShapeKind.SPHERE).withHalf(true), c, rim).count());
        assertEquals(62, build(spec(ShapeKind.SPHERE).withFill(Fill.HOLLOW), c, rim).count());
    }

    @Test
    public void stairs() {
        assertEquals(5, build(spec(ShapeKind.STAIRS).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 4, 0)).count());
        assertEquals(15, build(spec(ShapeKind.STAIRS), p(0, 0, 0), p(4, 4, 0)).count());
        assertEquals(45, build(spec(ShapeKind.STAIRS), p(0, 0, 0), p(4, 4, 2)).count());
    }

    @Test
    public void steepStairsHaveNoGaps() {
        AreaMask m = build(spec(ShapeKind.STAIRS).withFill(Fill.HOLLOW), p(0, 0, 0), p(2, 6, 0));
        for (int y = 0; y <= 6; y++) {
            int at = y;
            boolean any = false;
            for (int x = 0; x <= 2; x++) {
                any |= m.contains(x, at, 0);
            }
            assertTrue("gap at y " + y, any);
        }
    }

    @Test
    public void pyramid() {
        assertEquals(35, build(spec(ShapeKind.PYRAMID), p(0, 0, 0), p(4, 0, 4)).count());
        assertEquals(25, build(spec(ShapeKind.PYRAMID).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 0, 4)).count());
    }

    @Test
    public void roofRidgeRunsAlongTheLongSide() {
        AreaMask m = build(spec(ShapeKind.ROOF), p(0, 0, 0), p(6, 0, 4));
        assertEquals(63, m.count());
        assertEquals(3, m.sizeY);
        assertTrue(m.contains(0, 2, 2));
        assertFalse(m.contains(0, 2, 1));
        assertEquals(43, build(spec(ShapeKind.ROOF).withFill(Fill.HOLLOW), p(0, 0, 0), p(6, 0, 4)).count());
    }

    @Test
    public void cornersInAnyOrder() {
        assertEquals(build(spec(ShapeKind.CUBOID), p(0, 0, 0), p(3, 2, 1)).count(),
                build(spec(ShapeKind.CUBOID), p(3, 2, 1), p(0, 0, 0)).count());
    }

    @Test(expected = ShapeGeometry.TooBig.class)
    public void hugeBoxIsRefused() {
        build(spec(ShapeKind.CUBOID), p(0, 0, 0), p(1000, 300, 1000));
    }

    @Test
    public void unionCoversBoth() {
        AreaMask a = build(spec(ShapeKind.CUBOID), p(0, 0, 0), p(1, 1, 1));
        AreaMask b = build(spec(ShapeKind.CUBOID), p(5, 0, 0), p(5, 0, 0));
        AreaMask u = AreaMask.union(List.of(a, b));
        assertEquals(9, u.count());
        assertTrue(u.contains(5, 0, 0));
        assertFalse(u.contains(3, 0, 0));
    }
}
