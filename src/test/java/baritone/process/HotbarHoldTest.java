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
package baritone.process;

import org.junit.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HotbarHoldTest {

    @Test
    public void holdsForAWhile() {
        HotbarHold hold = new HotbarHold();
        assertFalse(hold.held(3, 0));
        hold.fetched(3, 10);
        assertTrue(hold.held(3, 10));
        assertTrue(hold.held(3, 10 + HotbarHold.HOLD_TICKS - 1));
        assertFalse(hold.held(3, 10 + HotbarHold.HOLD_TICKS));
        assertFalse(hold.held(4, 10));
        hold.clear();
        assertFalse(hold.held(3, 11));
    }

    @Test
    public void tiersLetGoOneThingAtATime() {
        HotbarHold hold = new HotbarHold();
        hold.fetched(2, 0);
        IntPredicate useful = i -> i == 5;
        List<IntPredicate> tiers = hold.tiers(useful, 1);
        assertEquals(3, tiers.size());
        // everything
        assertTrue(tiers.get(0).test(2));
        assertTrue(tiers.get(0).test(5));
        assertTrue(tiers.get(0).test(HotbarHold.THROWAWAY_SLOT));
        assertFalse(tiers.get(0).test(3));
        // the hold goes first
        assertFalse(tiers.get(1).test(2));
        assertTrue(tiers.get(1).test(HotbarHold.THROWAWAY_SLOT));
        assertTrue(tiers.get(1).test(5));
        // then slot 7, useful never does
        assertFalse(tiers.get(2).test(HotbarHold.THROWAWAY_SLOT));
        assertTrue(tiers.get(2).test(5));
    }
}
