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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TorchRulesTest {

    @Test
    public void darkNeedsBothLights() {
        assertTrue(TorchRules.dark(0, 0, 7));
        assertTrue(TorchRules.dark(7, 7, 7));
        assertFalse(TorchRules.dark(8, 0, 7));
        // the surface at night: no block light, full raw sky light. no torch
        assertFalse(TorchRules.dark(0, 15, 7));
        assertFalse(TorchRules.dark(0, 8, 7));
    }

    @Test
    public void levelIsClamped() {
        assertEquals(0, TorchRules.clampLevel(-3));
        assertEquals(15, TorchRules.clampLevel(99));
        assertEquals(7, TorchRules.clampLevel(7));
        assertTrue(TorchRules.dark(0, 0, -5));
        assertFalse(TorchRules.dark(1, 0, -5));
        assertTrue(TorchRules.dark(15, 15, 40));
    }

    @Test
    public void radiusStopsAtReach() {
        assertEquals(4, TorchRules.radius(4, 4.5));
        assertEquals(5, TorchRules.radius(10, 4.5));
        assertEquals(3, TorchRules.radius(3, 4.5));
        assertEquals(0, TorchRules.radius(-2, 4.5));
    }
}
