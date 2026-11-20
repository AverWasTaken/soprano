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

import baritone.effortless.ShapeSpec.Fill;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

// every shape counted once, the way Effortless Structure builds it, so a change to the maths shows up as a number
// that moved
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
    public void everyShapeTakesOneTwoOrThreeClicks() {
        for (ShapeKind k : ShapeKind.values()) {
            int n = k.clicks(spec(k));
            assertTrue(k + " takes " + n, n >= 1 && n <= 3);
        }
        assertEquals(3, ShapeKind.CUBE.clicks);
        assertEquals(2, ShapeKind.WALL.clicks);
    }

    @Test
    public void single() {
        AreaMask m = build(spec(ShapeKind.SINGLE), p(3, 4, 5));
        assertEquals(1, m.count());
        assertTrue(m.contains(3, 4, 5));
    }

    @Test
    public void line() {
        AreaMask m = build(spec(ShapeKind.LINE), p(0, 0, 0), p(5, 0, 0));
        assertEquals(6, m.count());
    }

    @Test
    public void diagonalLineGoesCornerToCorner() {
        AreaMask m = build(spec(ShapeKind.DIAGONAL_LINE), p(0, 0, 0), p(3, 3, 3));
        assertEquals(4, m.count());
        assertTrue(m.contains(2, 2, 2));
    }

    @Test
    public void wallAndFloor() {
        assertEquals(15, build(spec(ShapeKind.WALL), p(0, 0, 0), p(4, 2, 0)).count());
        // 5 wide, 3 high: top and bottom rows plus the two end cells of the middle row
        assertEquals(12, build(spec(ShapeKind.WALL).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 2, 0)).count());
        assertEquals(25, build(spec(ShapeKind.FLOOR), p(0, 0, 0), p(4, 0, 4)).count());
        assertEquals(16, build(spec(ShapeKind.FLOOR).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 0, 4)).count());
    }

    @Test
    public void cubeIsAFaceThenPulledOut() {
        // after two clicks: the face
        assertEquals(9, build(spec(ShapeKind.CUBE), p(0, 0, 0), p(2, 0, 2)).count());
        BlockPos a = p(0, 0, 0), b = p(2, 0, 2), c = p(2, 2, 2);
        assertEquals(27, build(spec(ShapeKind.CUBE), a, b, c).count());
        assertEquals(26, build(spec(ShapeKind.CUBE).withFill(Fill.HOLLOW), a, b, c).count());
        assertEquals(20, build(spec(ShapeKind.CUBE).withFill(Fill.SKELETON), a, b, c).count());
    }

    @Test
    public void circleFromACornerAndFromTheMiddle() {
        // corner: fits between the clicks, a 5x5 box
        AreaMask corner = build(spec(ShapeKind.CIRCLE), p(0, 0, 0), p(4, 0, 4));
        assertEquals(1, corner.sizeY);
        assertEquals(5, corner.sizeX);
        assertTrue(corner.contains(2, 0, 2));
        assertFalse(corner.contains(0, 0, 0));
        // middle: the first click is the centre, the same circle around it
        AreaMask middle = build(spec(ShapeKind.CIRCLE).withCentered(true), p(2, 0, 2), p(4, 0, 4));
        assertEquals(corner.count(), middle.count());
        assertTrue(middle.contains(0, 0, 2));
        AreaMask ring = build(spec(ShapeKind.CIRCLE).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 0, 4));
        assertTrue(ring.count() < corner.count());
        assertFalse(ring.contains(2, 0, 2));
        // upright: on the plane the clicks share
        AreaMask wall = build(spec(ShapeKind.CIRCLE), p(0, 0, 0), p(4, 4, 0));
        assertEquals(1, wall.sizeZ);
        assertEquals(corner.count(), wall.count());
    }

    @Test
    public void cylinderStacksTheCircle() {
        AreaMask disc = build(spec(ShapeKind.CIRCLE), p(0, 0, 0), p(4, 0, 4));
        AreaMask can = build(spec(ShapeKind.CYLINDER), p(0, 0, 0), p(4, 0, 4), p(4, 3, 4));
        assertEquals(disc.count() * 4, can.count());
    }

    @Test
    public void sphereFitsTheBox() {
        AreaMask ball = build(spec(ShapeKind.SPHERE), p(0, 0, 0), p(4, 0, 4), p(4, 4, 4));
        assertEquals(5, ball.sizeY);
        assertTrue(ball.contains(2, 2, 2));
        assertFalse(ball.contains(0, 0, 0));
        AreaMask shell = build(spec(ShapeKind.SPHERE).withFill(Fill.HOLLOW), p(0, 0, 0), p(4, 0, 4), p(4, 4, 4));
        assertFalse(shell.contains(2, 2, 2));
        assertTrue(shell.count() < ball.count());
    }

    @Test
    public void domeIsTheTopHalf() {
        AreaMask dome = build(spec(ShapeKind.DOME).withCentered(true), p(0, 0, 0), p(3, 0, 0), p(3, 3, 0));
        assertEquals(4, dome.sizeY);
        assertEquals(0, dome.minY);
        assertTrue(dome.contains(0, 3, 0));
        assertTrue(dome.contains(-3, 0, 0));
    }

    @Test
    public void diagonalWallRaisesTheDiagonal() {
        AreaMask m = build(spec(ShapeKind.DIAGONAL_WALL), p(0, 0, 0), p(3, 0, 3), p(3, 2, 3));
        assertEquals(4 * 3, m.count());
        assertTrue(m.contains(2, 2, 2));
        assertFalse(m.contains(3, 0, 0));
    }

    @Test
    public void slopeFloorClimbsTheLongWayByDefault() {
        // 5 along x, 3 along z: the short edges stay level, the slope climbs along x
        AreaMask m = build(spec(ShapeKind.SLOPE_FLOOR), p(0, 0, 0), p(4, 0, 2), p(4, 4, 2));
        assertEquals(5 * 3, m.count());
        assertTrue(m.contains(0, 0, 1));
        assertTrue(m.contains(4, 4, 1));
        // the long edge raised instead: it climbs along z
        AreaMask l = build(spec(ShapeKind.SLOPE_FLOOR).withLongEdge(true), p(0, 0, 0), p(4, 0, 2), p(4, 2, 2));
        assertTrue(l.contains(4, 2, 2));
        assertTrue(l.contains(4, 0, 0));
    }

    @Test
    public void pyramidShrinksAWholeBlockALayer() {
        AreaMask m = build(spec(ShapeKind.PYRAMID), p(0, 0, 0), p(4, 0, 4), p(4, 2, 4));
        // Effortless Structure's steps: 5x5, 3x3, the point
        assertEquals(25 + 9 + 1, m.count());
        assertTrue(m.contains(2, 2, 2));
    }

    @Test
    public void coneIsRoundLayers() {
        AreaMask m = build(spec(ShapeKind.CONE), p(0, 0, 0), p(4, 0, 4), p(4, 2, 4));
        assertTrue(m.contains(2, 2, 2));
        assertFalse(m.contains(0, 0, 0));
        assertTrue(m.count() < 35);
    }

    @Test
    public void cornersInAnyOrder() {
        assertEquals(build(spec(ShapeKind.CUBE), p(0, 0, 0), p(3, 0, 1), p(3, 2, 1)).count(),
                build(spec(ShapeKind.CUBE), p(3, 2, 1), p(0, 2, 0), p(0, 0, 0)).count());
    }

    @Test(expected = ShapeGeometry.TooBig.class)
    public void hugeBoxIsRefused() {
        build(spec(ShapeKind.CUBE), p(0, 0, 0), p(1000, 0, 1000), p(1000, 300, 1000));
    }

    @Test
    public void unionCoversBoth() {
        AreaMask a = build(spec(ShapeKind.CUBE), p(0, 0, 0), p(1, 0, 1), p(1, 1, 1));
        AreaMask b = build(spec(ShapeKind.SINGLE), p(5, 0, 0));
        AreaMask u = AreaMask.union(List.of(a, b));
        assertEquals(9, u.count());
        assertTrue(u.contains(5, 0, 0));
        assertFalse(u.contains(3, 0, 0));
    }
}
