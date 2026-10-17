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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AimLogTest {

    @Test
    public void wrapsTheShortWayRound() {
        assertEquals(20, AimLog.wrappedDelta(-170, 170), 1e-4);
        assertEquals(-20, AimLog.wrappedDelta(170, -170), 1e-4);
        assertEquals(-180, AimLog.wrappedDelta(0, 180), 1e-4);
        assertEquals(10, AimLog.wrappedDelta(730, 0), 1e-4);
        assertEquals(-10, AimLog.wrappedDelta(-730, 0), 1e-4);
        assertEquals(0, AimLog.wrappedDelta(45, 45), 1e-4);
    }

    @Test
    public void onlyBigTurnsCount() {
        assertFalse(AimLog.isBig(AimLog.wrappedDelta(-170, 170)));
        assertFalse(AimLog.isBig(30));
        assertTrue(AimLog.isBig(30.5f));
        assertTrue(AimLog.isBig(-179));
    }

    @Test
    public void rateLimitsAndCountsWhatItSwallowed() {
        AimLog log = new AimLog();
        assertEquals(0, log.admit(100));
        for (int t = 101; t < 100 + AimLog.MIN_GAP_TICKS; t++) {
            assertEquals(-1, log.admit(t));
        }
        assertEquals(AimLog.MIN_GAP_TICKS - 1, log.admit(100 + AimLog.MIN_GAP_TICKS));
        assertEquals(0, log.admit(1000));
    }

    @Test
    public void aTickCountThatStartedOverDoesNotMuteIt() {
        AimLog log = new AimLog();
        assertEquals(0, log.admit(72000));
        // new dimension, new player, tickCount from zero
        assertEquals(0, log.admit(2));
        assertEquals(-1, log.admit(3));
    }
}
