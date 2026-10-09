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

package baritone.behavior;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathKeepTest {
    @Test
    public void theSameGoalRightAwayWithUsOnThePathGetsItBack() {
        assertTrue(PathKeep.restore(true, true, 0, true, true, true));
        assertTrue(PathKeep.restore(true, true, PathKeep.MAX_AGE_TICKS, true, true, true));
    }

    @Test
    public void halfASecondIsTheMost() {
        assertEquals(10, PathKeep.MAX_AGE_TICKS);
        assertFalse(PathKeep.restore(true, true, PathKeep.MAX_AGE_TICKS + 1, true, true, true));
        // a park from the future is a broken clock, not a fresh path
        assertFalse(PathKeep.restore(true, true, -1, true, true, true));
    }

    @Test
    public void aDifferentGoalNeverGetsAnOldPath() {
        assertFalse(PathKeep.restore(true, false, 0, true, true, true));
    }

    @Test
    public void offThePathMeansKnockbackOrATeleport() {
        assertFalse(PathKeep.restore(true, true, 0, false, true, true));
    }

    @Test
    public void anotherDimensionOrAnotherLifeIsAnotherPath() {
        assertFalse(PathKeep.restore(true, true, 0, true, false, true));
        assertFalse(PathKeep.restore(true, true, 0, true, true, false));
    }

    @Test
    public void theSettingTurnsItOff() {
        assertFalse(PathKeep.restore(false, true, 0, true, true, true));
    }
}
