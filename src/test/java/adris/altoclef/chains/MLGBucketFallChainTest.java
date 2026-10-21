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

package adris.altoclef.chains;

import org.junit.Test;

import static org.junit.Assert.*;

public class MLGBucketFallChainTest {

    @Test
    public void noClutchItemAndNothingDeadlyMeansNothingToTakeOverFor() {
        // this is the one that used to cancel a planned fall for nothing
        assertFalse(MLGBucketFallChain.claimsFall(true, false, false));
    }

    @Test
    public void aFallWithSomethingToPlaceIsStillTheChains() {
        assertTrue(MLGBucketFallChain.claimsFall(true, true, false));
        assertTrue(MLGBucketFallChain.claimsFall(true, true, true));
    }

    @Test
    public void aDeadlyFallIsTheChainsEvenWithAnEmptyHand() {
        // nothing to place, but the steering toward lava or water can still save us
        assertTrue(MLGBucketFallChain.claimsFall(true, false, true));
    }

    @Test
    public void notFallingIsNotFalling() {
        for (boolean item : new boolean[]{false, true}) {
            for (boolean deadly : new boolean[]{false, true}) {
                assertFalse(MLGBucketFallChain.claimsFall(false, item, deadly));
            }
        }
    }

    // a cell that's held water on our side, landed a while ago
    private static MLGBucketFallChain.CellEnd seen(boolean stillSource, boolean gained, double distance) {
        return MLGBucketFallChain.cellEnd(stillSource, true, true, gained, distance);
    }

    @Test
    public void ourSourceGoneWithABucketGainedIsThePickup() {
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, seen(false, true, 1));
        // gone is gone, however far off we are
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, seen(false, true, 10));
        // and that's not waiting on the settle time either
        assertEquals(MLGBucketFallChain.CellEnd.PICKED_UP, MLGBucketFallChain.cellEnd(false, true, false, true, 1));
    }

    @Test
    public void goneWithoutABucketIsJustGone() {
        // flowed off a ledge, a block on it, someone else's bucket
        assertEquals(MLGBucketFallChain.CellEnd.GONE, seen(false, false, 1));
    }

    @Test
    public void aSourceInReachStaysOnTheList() {
        // a tick we couldn't click (no aim yet, somebody else had the wheel) is not the end of it
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, false, 0.5));
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, seen(true, true, MLGBucketFallChain.CLEANUP_REACH));
    }

    @Test
    public void walkingOffLeavesIt() {
        assertEquals(MLGBucketFallChain.CellEnd.TOO_FAR, seen(true, false, MLGBucketFallChain.CLEANUP_REACH + 0.01));
        assertEquals(MLGBucketFallChain.CellEnd.TOO_FAR, MLGBucketFallChain.cellEnd(false, false, false, false, MLGBucketFallChain.CLEANUP_REACH + 0.01));
    }

    @Test
    public void waterThatHasntShownUpYetGetsARoundTrip() {
        // a waterlogged block fills on the server only, so right after landing ours still says dry
        assertEquals(MLGBucketFallChain.CellEnd.KEEP, MLGBucketFallChain.cellEnd(false, false, false, false, 1));
        // once the round trip is over and it never came, the server said no
        assertEquals(MLGBucketFallChain.CellEnd.GONE, MLGBucketFallChain.cellEnd(false, false, true, false, 1));
        assertEquals(MLGBucketFallChain.CellEnd.GONE, MLGBucketFallChain.cellEnd(false, false, true, true, 1));
    }
}
