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

package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwimEscapeTest {

    @Test
    public void oneFailingExitIsJustAnEdge() {
        SwimEscape e = new SwimEscape();
        for (int i = 0; i < 50; i++) {
            assertFalse(e.tick(1));
        }
    }

    @Test
    public void twoExitsStandsUpForTwentyTicksThenLetsGo() {
        SwimEscape e = new SwimEscape();
        assertTrue(e.tick(2));
        assertTrue(e.began());
        for (int i = 1; i < SwimEscape.STAND_TICKS; i++) {
            assertTrue(e.tick(2));
            assertFalse(e.began());
        }
        // once per movement, then the normal swim gets another try
        assertFalse(e.tick(2));
        assertFalse(e.tick(3));
    }

    @Test
    public void triggerCanWaitForTheSecondExit() {
        SwimEscape e = new SwimEscape();
        assertFalse(e.tick(1));
        assertTrue(e.tick(2));
    }
}
