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

package baritone.behavior.look;

import org.junit.Test;

import static org.junit.Assert.*;

public class CombatAimMathTest {

    private static final double[] EYE = {0, 1.62, 0};

    // a zombie sized box with its feet at (x, y, z)
    private static double[] zombie(double x, double y, double z) {
        return new double[]{x - 0.3, y, z - 0.3, x + 0.3, y + 1.95, z + 0.3};
    }

    private static double[] dirTo(double[] from, double[] to) {
        float[] rot = CombatAimMath.rotationTo(from, to);
        return CombatAimMath.direction(rot[0], rot[1]);
    }

    @Test
    public void theInnerBoxIsTheMiddleHalfAndTheChestToTheChin() {
        double[] inner = CombatAimMath.innerBox(zombie(0, 0, 3));
        assertEquals(-0.15, inner[0], 1e-9);
        assertEquals(0.15, inner[3], 1e-9);
        assertEquals(1.95 * 0.35, inner[1], 1e-9);
        assertEquals(1.95 * 0.85, inner[4], 1e-9);
        double[] pref = CombatAimMath.preferredPoint(zombie(0, 0, 3), EYE[1]);
        assertTrue(CombatAimMath.inside(pref, inner));
    }

    @Test
    public void aMobAboveUsIsAimedLowerDownItsBody() {
        // level: 70% up. three up: the bottom of the inner box, the nearest bit we'd still call a body
        assertEquals(1.95 * CombatAimMath.PREFERRED_HEIGHT, CombatAimMath.preferredPoint(zombie(0, 0, 3), EYE[1])[1], 1e-9);
        double[] above = zombie(0, 3, 2);
        assertEquals(3 + 1.95 * CombatAimMath.LOW, CombatAimMath.preferredPoint(above, EYE[1])[1], 1e-9);
        // halfway: our eye height, if that's inside the band
        double[] half = zombie(0, 0.5, 2);
        assertEquals(EYE[1], CombatAimMath.preferredPoint(half, EYE[1])[1], 1e-9);
    }

    @Test
    public void rotationAndDirectionAgreeWithMinecraft() {
        // yaw 0 looks down +z, yaw 90 down -x, positive pitch is down
        assertArrayEquals(new double[]{0, 0, 1}, CombatAimMath.direction(0, 0), 1e-9);
        assertArrayEquals(new double[]{-1, 0, 0}, CombatAimMath.direction(90, 0), 1e-9);
        assertEquals(-1, CombatAimMath.direction(0, 90)[1], 1e-9);
        float[] rot = CombatAimMath.rotationTo(new double[]{0, 0, 0}, new double[]{-1, 0, 0});
        assertEquals(90, CombatAimMath.wrap(rot[0]), 1e-4);
        assertEquals(0, rot[1], 1e-4);
        // and they invert each other
        double[] p = {1.3, 2.2, -4.1};
        double[] d = dirTo(EYE, p);
        double len = Math.sqrt(Math.pow(p[0] - EYE[0], 2) + Math.pow(p[1] - EYE[1], 2) + Math.pow(p[2] - EYE[2], 2));
        for (int i = 0; i < 3; i++) {
            assertEquals(p[i], EYE[i] + d[i] * len, 1e-4);
        }
    }

    @Test
    public void aCrosshairAlreadyOnThePreferredPointStaysPut() {
        double[] box = zombie(0.4, 0, 3);
        double[] pref = CombatAimMath.preferredPoint(box, EYE[1]);
        double[] dir = dirTo(EYE, pref);
        double[] point = CombatAimMath.aimPoint(EYE, dir, CombatAimMath.innerBox(box), pref);
        // the point is on the ray we already look down (its depth is the box middle's, not the preferred point's), so the
        // rotation it asks for is the one we have
        float[] now = CombatAimMath.rotationTo(EYE, pref);
        float[] want = CombatAimMath.rotationTo(EYE, point);
        assertEquals(now[0], want[0], 1e-4);
        assertEquals(now[1], want[1], 1e-4);
    }

    @Test
    public void insideTheInnerBoxOnlyTheDriftMoves() {
        double[] box = zombie(0, 0, 3);
        double[] inner = CombatAimMath.innerBox(box);
        double[] pref = CombatAimMath.preferredPoint(box, EYE[1]);
        // looking at a spot inside the inner box but off the preferred point
        double[] spot = {0.1, 0.9, 3};
        assertTrue(CombatAimMath.inside(spot, inner));
        double[] point = CombatAimMath.aimPoint(EYE, dirTo(EYE, spot), inner, pref);
        // the ray point is at the depth of the box centre, a hair off `spot`, so measure against that
        double[] dir = dirTo(EYE, spot);
        double t = (0 - EYE[0]) * dir[0] + ((inner[1] + inner[4]) / 2 - EYE[1]) * dir[1] + (3 - EYE[2]) * dir[2];
        for (int i = 0; i < 3; i++) {
            double ray = EYE[i] + dir[i] * t;
            assertEquals(ray + (pref[i] - ray) * CombatAimMath.DRIFT, point[i], 1e-9);
        }
    }

    @Test
    public void lookingAwayClampsOntoTheNearSideOfTheInnerBox() {
        double[] box = zombie(0, 0, 3);
        double[] inner = CombatAimMath.innerBox(box);
        double[] pref = CombatAimMath.preferredPoint(box, EYE[1]);
        // looking way off to the right: the point lands inside the inner box (give or take the drift toward the middle)
        double[] point = CombatAimMath.aimPoint(EYE, CombatAimMath.direction(-60, 0), inner, pref);
        assertTrue(point[0] >= inner[0] && point[0] <= inner[3]);
        assertTrue(point[1] >= inner[1] && point[1] <= inner[4]);
        // and looking straight behind us still gives a point on the box, not behind us
        double[] behind = CombatAimMath.aimPoint(EYE, CombatAimMath.direction(180, 0), inner, pref);
        assertTrue(behind[2] > 2.5);
    }

    @Test
    public void aPreferredPointOutsideTheInnerBoxIsAimedAtNearlyStraight() {
        double[] box = zombie(0, 0, 3);
        double[] inner = CombatAimMath.innerBox(box);
        // the top of its head, the only bit visible over a block
        double[] top = {0, 1.9, 3};
        double[] p = CombatAimMath.aimPoint(EYE, CombatAimMath.direction(0, 0), inner, top);
        // wherever we were looking, a tenth of a block from the head toward the middle, still on the box and off its edge
        assertEquals(top[1] - CombatAimMath.OUTSIDE_PULL, p[1], 1e-9);
        assertEquals(0, p[0], 1e-9);
        assertTrue(p[1] < box[4] && p[1] > inner[4]);
        assertArrayEquals(p, CombatAimMath.aimPoint(EYE, CombatAimMath.direction(70, 20), inner, top), 1e-9);
    }

    @Test
    public void wrapGoesTheShortWayRound() {
        assertEquals(2, CombatAimMath.wrap(-179 - 179), 1e-4);
        assertEquals(-10, CombatAimMath.wrap(350), 1e-4);
        assertEquals(-180, CombatAimMath.wrap(180), 1e-4);
        assertEquals(0, CombatAimMath.wrap(720), 1e-4);
    }

    @Test
    public void mousePixelsAreWholeAndScaleWithSensitivity() {
        // (0.2)^3 * 8 * 0.15 at the bottom of the slider, (0.8)^3 * 8 * 0.15 at the top
        assertEquals(0.0096, CombatAimMath.mouseQuantum(0), 1e-5);
        assertEquals(0.6144, CombatAimMath.mouseQuantum(1), 1e-4);
        float q = CombatAimMath.mouseQuantum(0.5);
        float moved = CombatAimMath.quantize(10, 13.37f, 0.5) - 10;
        assertEquals(Math.round(moved / q), moved / q, 1e-3);
        assertTrue(Math.abs(moved - 3.37f) <= q / 2 + 1e-4);
    }

    // ---- ray against box

    private static final double[] UNIT = {-0.5, 0, 2, 0.5, 2, 3};

    @Test
    public void aRayStraightAtTheBoxEntersAtItsNearFace() {
        assertEquals(2, CombatAimMath.rayEntry(new double[]{0, 1, 0}, new double[]{0, 0, 1}, UNIT), 1e-9);
    }

    @Test
    public void aRayFromInsideTheBoxEntersAtZero() {
        assertEquals(0, CombatAimMath.rayEntry(new double[]{0, 1, 2.5}, new double[]{0, 0, 1}, UNIT), 1e-9);
        assertEquals(0, CombatAimMath.rayEntry(new double[]{0, 1, 2.5}, new double[]{0, 0, -1}, UNIT), 1e-9);
    }

    @Test
    public void grazingTheEdgeStillCounts() {
        // along the top edge of the box
        assertEquals(2, CombatAimMath.rayEntry(new double[]{0, 2, 0}, new double[]{0, 0, 1}, UNIT), 1e-9);
        // and a diagonal that just clips the corner
        double s = Math.sqrt(0.5);
        double t = CombatAimMath.rayEntry(new double[]{-1.5, 1, 1}, new double[]{s, 0, s}, UNIT);
        assertFalse(Double.isNaN(t));
        // a hair outside the edge is a miss
        assertTrue(Double.isNaN(CombatAimMath.rayEntry(new double[]{0, 2.001, 0}, new double[]{0, 0, 1}, UNIT)));
    }

    @Test
    public void aBoxBehindUsOrBesideTheRayIsAMiss() {
        assertTrue(Double.isNaN(CombatAimMath.rayEntry(new double[]{0, 1, 0}, new double[]{0, 0, -1}, UNIT)));
        assertTrue(Double.isNaN(CombatAimMath.rayEntry(new double[]{0, 1, 0}, CombatAimMath.direction(-45, 0), UNIT)));
    }

    @Test
    public void beyondReachIsAnEntryPastTheReach() {
        double[] far = {-0.5, 0, 5, 0.5, 2, 6};
        double t = CombatAimMath.rayEntry(new double[]{0, 1, 0}, new double[]{0, 0, 1}, far);
        assertEquals(5, t, 1e-9);
        assertTrue(t > 3);
    }

    @Test
    public void lookingAtTheInnerBoxIsAlwaysAHitOnTheWholeBox() {
        // the point of the inner box: anywhere the aim settles is a hit, with margin for the mob to shuffle
        double[] box = zombie(0.7, -0.5, 2.6);
        double[] inner = CombatAimMath.innerBox(box);
        for (double fx = 0; fx <= 1; fx += 0.25) {
            for (double fy = 0; fy <= 1; fy += 0.25) {
                double[] p = {inner[0] + (inner[3] - inner[0]) * fx, inner[1] + (inner[4] - inner[1]) * fy, (inner[2] + inner[5]) / 2};
                double t = CombatAimMath.rayEntry(EYE, dirTo(EYE, p), box);
                assertFalse(Double.isNaN(t));
                assertTrue(t <= 3);
            }
        }
    }
}
