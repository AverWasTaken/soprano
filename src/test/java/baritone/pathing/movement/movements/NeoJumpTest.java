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

import org.junit.Test;

import static org.junit.Assert.*;

public class NeoJumpTest {

    // which neos a run up can actually make. if the sim changes and this table changes, go look at why before updating
    // it, these were all worked out by hand first
    @Test
    public void standingStarts() {
        assertNotNull(NeoJump.staging(2, 0b1, 1, true));
        assertNotNull(NeoJump.staging(3, 0b01, 1, true));
        assertNotNull(NeoJump.staging(3, 0b10, 1, true));
        assertNotNull(NeoJump.staging(2, 0b1, 3, true));
        assertNotNull(NeoJump.staging(3, 0b01, 2, false));
        // two thick (the classic 3b neo) needs a full speed run along the very edge, and one block of runway isn't
        // enough to get up to speed
        assertNull(NeoJump.staging(3, 0b11, 1, true));
        assertNotNull(NeoJump.staging(3, 0b11, 2, true));
        assertNotNull(NeoJump.staging(3, 0b11, 2, false));
        // not neos at all
        assertNull(NeoJump.staging(3, 0, 1, true));
        assertNull(NeoJump.staging(4, 0b1, 1, true));
        assertNull(NeoJump.staging(2, 0b1, 0, true));
    }

    @Test
    public void stagingSpotFlies() {
        NeoJump.Staging st = NeoJump.staging(2, 0b1, 1, true);
        assertNotNull(st);
        // behind the takeoff block, off to the open side, and from a standstill it has to run first
        assertTrue(st.u() > -1.5 && st.u() < -0.5);
        assertTrue(st.v() > 0 && st.v() < 0.8);
        assertTrue(st.runup() > 0);
    }

    @Test
    public void threeBlockNeoLands() {
        // what MovementNeo will actually do, start to finish, has to clear by at least what A* asked for
        NeoJump.Staging st = NeoJump.staging(3, 0b11, 2, true);
        NeoJump neo = new NeoJump(3, 0b11, 2, true, NeoJump.SPRINT_GROUND);
        assertTrue(neo.rollout(st.u(), st.v(), st.runTarget(), st.takeoff()) >= NeoJump.PLAN_MARGIN);
    }
}
