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

package baritone.pathing.path;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HighWaterTest {

    @Test
    public void bouncingBetweenTwoMovementsRunsOut() {
        HighWater water = new HighWater();
        // 0 and 1 trading places forever, each costing 10 with a 100 tick timeout
        int ticks = 0;
        boolean stuck = false;
        while (!stuck && ticks < 1000) {
            stuck = water.tick(ticks % 2, 10, 100);
            ticks++;
        }
        assertTrue(stuck);
        assertEquals(1, water.high());
        // the high was set on the second tick, and 110 is the budget
        assertEquals(112, ticks);
    }

    @Test
    public void gettingFurtherStartsOver() {
        HighWater water = new HighWater();
        for (int i = 0; i < 100; i++) {
            assertFalse(water.tick(0, 10, 100));
        }
        // a new high, the clock starts again and takes the new movement's cost
        for (int i = 0; i < 150; i++) {
            assertFalse(water.tick(1, 50, 100));
        }
        assertTrue(water.tick(1, 50, 100));
    }

    @Test
    public void goingBackDoesntStartOver() {
        HighWater water = new HighWater();
        for (int i = 0; i < 100; i++) {
            water.tick(3, 10, 100);
        }
        // a resync back to 2 is not progress, the cost there doesn't matter either
        for (int i = 0; i < 10; i++) {
            assertFalse(water.tick(2, 1000, 100));
        }
        assertTrue(water.tick(2, 1000, 100));
    }

    @Test
    public void shiftAndForget() {
        HighWater water = new HighWater();
        water.tick(5, 10, 100);
        water.shift(3);
        assertEquals(2, water.high());
        water.forget();
        assertEquals(-1, water.high());
        assertFalse(water.tick(0, 10, 100));
        assertEquals(0, water.high());
    }
}
