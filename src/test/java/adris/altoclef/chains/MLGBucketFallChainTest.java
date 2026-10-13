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
}
