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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BuildAreaTest {

    private static final BetterBlockPos A = new BetterBlockPos(0, 64, 0);
    private static final BetterBlockPos FAR = new BetterBlockPos(40, 64, 0);
    private static final List<BetterBlockPos> NONE = Collections.emptyList();

    private static List<BetterBlockPos> cells(BetterBlockPos... p) {
        return new ArrayList<>(Arrays.asList(p));
    }

    @Test
    public void picksLowestThenNearest() {
        BuildArea area = new BuildArea();
        BetterBlockPos player = new BetterBlockPos(0, 66, 0);
        BetterBlockPos highNear = new BetterBlockPos(0, 66, 1);
        BetterBlockPos lowFar = new BetterBlockPos(10, 63, 0);
        BetterBlockPos lowNear = new BetterBlockPos(3, 63, 0);
        assertTrue(area.choose(cells(highNear, lowFar, lowNear), NONE, player, 0).isEmpty());
        assertEquals(lowNear, area.anchor());
    }

    @Test
    public void keepsTheAnchorWhileItHasWork() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        // something lower turned up somewhere else, we finish here first
        BetterBlockPos lower = new BetterBlockPos(20, 60, 0);
        BetterBlockPos stillHere = new BetterBlockPos(2, 64, 2);
        area.choose(cells(lower, stillHere), NONE, A, 1);
        assertEquals(A, area.anchor());
        assertTrue(area.contains(stillHere.x, stillHere.y, stillHere.z));
        assertFalse(area.contains(lower.x, lower.y, lower.z));
    }

    @Test
    public void noAnchorMeansEverythingIsIn() {
        BuildArea area = new BuildArea();
        assertNull(area.anchor());
        assertTrue(area.contains(1000, 3, -1000));
    }

    @Test
    public void doneAreaMovesOnWithoutABan() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        BetterBlockPos next = new BetterBlockPos(BuildArea.RADIUS + 1, 64, 0); // right next door, inside a ban
        area.choose(cells(next, FAR), NONE, A, 1);
        assertEquals(next, area.anchor());
    }

    @Test
    public void allSkippedAreaGetsBanned() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        BetterBlockPos nextDoor = new BetterBlockPos(BuildArea.RADIUS, 64, 1); // outside the area, inside its ban
        area.choose(cells(nextDoor, FAR), cells(A), A, 1);
        assertEquals(FAR, area.anchor());
    }

    @Test
    public void clockOnlyRunsInside() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        BetterBlockPos outside = new BetterBlockPos(BuildArea.RADIUS + 5, 64, 0);
        long t = 1;
        for (int i = 0; i < BuildArea.STALE_TICKS * 3; i++) {
            assertFalse(area.clock(outside, t++, 0)); // walking there
        }
        BetterBlockPos edge = new BetterBlockPos(BuildArea.RADIUS + 4, 64, 0); // in reach of the far edge
        for (int i = 0; i < BuildArea.STALE_TICKS / 2; i++) {
            assertFalse(area.clock(edge, t++, 0));
        }
        for (int i = 0; i < 50; i++) {
            assertFalse(area.clock(outside, t++, 0)); // frozen, not reset
        }
        for (int i = 0; i < BuildArea.STALE_TICKS / 2 - 1; i++) {
            assertFalse(area.clock(A, t++, 0));
        }
        assertTrue(area.clock(A, t, 0));
        assertNull(area.anchor());
    }

    @Test
    public void aFixResetsTheClock() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        long t = 1;
        for (int i = 0; i < BuildArea.STALE_TICKS - 10; i++) {
            assertFalse(area.clock(A, t++, 0));
        }
        for (int i = 0; i < BuildArea.STALE_TICKS - 1; i++) {
            assertFalse(area.clock(A, t++, 1));
        }
        assertTrue(area.clock(A, t, 1));
    }

    @Test
    public void timeoutBansTheAreaAndItsNeighbours() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        long t = timeOut(area, 1);
        BetterBlockPos banned = new BetterBlockPos(BuildArea.RADIUS, 64, 0); // closer than radius+1
        BetterBlockPos fine = new BetterBlockPos(BuildArea.RADIUS + 1, 64, 0);
        assertTrue(area.choose(cells(A, banned, fine), NONE, A, t).isEmpty());
        assertEquals(fine, area.anchor());
    }

    @Test
    public void banIsIgnoredWhenNothingElseQualifies() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        area.release(1, true); // a failed path, not a timeout, so nothing gets skipped
        BetterBlockPos alsoBanned = new BetterBlockPos(2, 64, 0);
        assertTrue(area.choose(cells(alsoBanned, A), NONE, A, 2).isEmpty());
        assertEquals(A, area.anchor());
    }

    @Test
    public void banRunsOut() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        area.release(0, true);
        area.choose(cells(A, FAR), NONE, A, BuildArea.BAN_TICKS - 1);
        assertEquals(FAR, area.anchor());
        area.release(BuildArea.BAN_TICKS - 1, false);
        area.choose(cells(A, FAR), NONE, A, BuildArea.BAN_TICKS);
        assertEquals(A, area.anchor());
    }

    @Test
    public void lastAreaTimingOutGetsSkipped() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        long t = timeOut(area, 1);
        BetterBlockPos inside = new BetterBlockPos(1, 64, 1);
        // outside the radius but inside the ban, so it's the only thing left and it doesn't get skipped
        BetterBlockPos rim = new BetterBlockPos(BuildArea.RADIUS, 64, 1);
        List<BetterBlockPos> giveUp = area.choose(cells(A, inside, rim), NONE, A, t);
        assertEquals(cells(A, inside), giveUp);
        assertEquals(rim, area.anchor());
    }

    @Test
    public void onlyTheFirstPickAfterATimeoutCanSkip() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        long t = timeOut(area, 1);
        assertTrue(area.choose(cells(A, FAR), NONE, A, t).isEmpty()); // somewhere else to go, no skipping
        assertEquals(FAR, area.anchor());
        area.release(t, false);
        assertTrue(area.choose(cells(A), NONE, A, t + 1).isEmpty()); // banned, but this wasn't a timeout
        assertEquals(A, area.anchor());
    }

    @Test
    public void resetForgetsBans() {
        BuildArea area = new BuildArea();
        area.choose(cells(A), NONE, A, 0);
        area.release(0, true);
        area.reset();
        area.choose(cells(A, FAR), NONE, A, 1);
        assertEquals(A, area.anchor());
    }

    private static long timeOut(BuildArea area, long t) {
        while (!area.clock(A, t, 0)) {
            t++;
        }
        return t + 1;
    }
}
