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

package baritone.pathing.movement.movements;

import baritone.pathing.movement.movements.MovementFall.FallMode;
import org.junit.Test;

import static org.junit.Assert.*;

public class FallCoverTest {

    // a fall from y 70 down to y 40, walking off the +x side of the block at 10,70,10
    private static boolean in(double px, double py, double pz, double mx, double mz) {
        return FallCover.inColumn(10, 70, 10, 11, 40, 10, px, py, pz, mx, mz);
    }

    @Test
    public void theFallItselfIsInTheColumn() {
        // right off the ledge, halfway down in the dest column, and just above the floor
        assertTrue(in(10.9, 69.5, 10.5, 0.1, 0));
        assertTrue(in(11.5, 55, 10.5, 0.05, 0));
        assertTrue(in(11.5, 40.2, 10.5, 0, 0));
        // sprinting off the edge still drifts, that's ours
        assertTrue(in(11.2, 60, 10.5, 0.28, 0));
    }

    @Test
    public void knockbackIsNotTheFall() {
        // shoved hard enough to be a hit, even though we're still over the column
        assertFalse(in(11.5, 55, 10.5, 0.4, 0.3));
        // or knocked clear of both cells
        assertFalse(in(13.5, 55, 10.5, 0.1, 0));
        assertFalse(in(11.5, 55, 12.5, 0, 0.1));
        assertFalse(in(8.5, 55, 10.5, 0, 0));
    }

    @Test
    public void heightHasToMakeSense() {
        // below the floor we planned to land on, or way above the ledge: not the same fall
        assertFalse(in(11.5, 38, 10.5, 0, 0));
        assertFalse(in(10.5, 75, 10.5, 0, 0));
        // a jump off the edge is
        assertTrue(in(10.9, 71, 10.5, 0.1, 0));
    }

    @Test
    public void diagonalFallsGetBothCells() {
        // src 10,10 dest 11,11: the box is the union, the corner we cut across is fine
        assertTrue(FallCover.inColumn(10, 70, 10, 11, 40, 11, 11.0, 60, 11.0, 0.1, 0.1));
        assertFalse(FallCover.inColumn(10, 70, 10, 11, 40, 11, 9.0, 60, 9.0, 0.05, 0.05));
    }

    @Test
    public void safeFallsAndClutchesAreBaritones() {
        for (boolean hotbar : new boolean[]{false, true}) {
            for (boolean alto : new boolean[]{false, true}) {
                assertTrue(FallCover.handles(FallMode.NONE, false, hotbar, alto));
                assertTrue(FallCover.handles(FallMode.CLUTCH, false, hotbar, alto));
            }
        }
    }

    @Test
    public void bucketIsBaritonesWhenItCanClickIt() {
        // alto doing buckets (the usual setup) still defers to a hotbar bucket
        assertTrue(FallCover.handles(FallMode.BUCKET, false, true, true));
        // the bucket that lives in the inventory is alto's slot handler's problem
        assertFalse(FallCover.handles(FallMode.BUCKET, false, false, true));
        // and with alto not doing buckets there's nobody else to do it
        assertTrue(FallCover.handles(FallMode.BUCKET, false, false, false));
    }

    @Test
    public void nobodyHasAFallThatHurtsOrGaveUp() {
        assertFalse(FallCover.handles(FallMode.HURT, false, true, true));
        // clutch ran out of options with nothing to land with: whatever alto has (hay, a bucket) can try
        assertFalse(FallCover.handles(FallMode.NONE, true, false, true));
    }

    @Test
    public void waterPickupIsBaritonesOnlyAtItsDest() {
        assertTrue(FallCover.pickupIsBaritones(true, true, true));
        // the water went in somewhere else, or the bucket isn't where it clicks from, so the chain still has to do it
        assertFalse(FallCover.pickupIsBaritones(false, true, true));
        assertFalse(FallCover.pickupIsBaritones(true, true, false));
        // not water yet means the placement is still in flight
        assertFalse(FallCover.pickupIsBaritones(true, false, true));
    }
}
