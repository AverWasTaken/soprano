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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FailedMovesTest {

    private static final BetterBlockPos SRC = new BetterBlockPos(-2, 74, -6);
    private static final BetterBlockPos DEST = new BetterBlockPos(-3, 74, -6);
    private static final BetterBlockPos OTHER = new BetterBlockPos(-2, 74, -5);
    private static final Object OVERWORLD = new Object();
    private static final Object NETHER = new Object();

    private static double penalty(FailedMoves.Snapshot s, BetterBlockPos from, BetterBlockPos to) {
        return s.penalty(from.x, from.y, from.z, to.x, to.y, to.z);
    }

    @Test
    public void nothingRecordedCostsNothing() {
        FailedMoves fails = new FailedMoves();
        FailedMoves.Snapshot s = fails.snapshot(OVERWORLD, 0);
        assertSame(FailedMoves.Snapshot.NONE, s);
        assertEquals(0, penalty(s, SRC, DEST), 0);
    }

    @Test
    public void failingAgainCostsMoreEachTime() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        assertEquals(FailedMoves.FIRST, penalty(fails.snapshot(OVERWORLD, 0), SRC, DEST), 0);
        fails.record(SRC, DEST, OVERWORLD, 100);
        assertEquals(FailedMoves.SECOND, penalty(fails.snapshot(OVERWORLD, 100), SRC, DEST), 0);
        fails.record(SRC, DEST, OVERWORLD, 200);
        assertEquals(FailedMoves.THIRD, penalty(fails.snapshot(OVERWORLD, 200), SRC, DEST), 0);
        // and it stops climbing there, it's a penalty not a wall
        fails.record(SRC, DEST, OVERWORLD, 300);
        assertEquals(FailedMoves.THIRD, penalty(fails.snapshot(OVERWORLD, 300), SRC, DEST), 0);
    }

    @Test
    public void thirtySecondsAfterTheLastFailureItsForgotten() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        fails.record(SRC, DEST, OVERWORLD, 20_000);
        assertEquals(FailedMoves.SECOND, penalty(fails.snapshot(OVERWORLD, 49_999), SRC, DEST), 0);
        assertSame(FailedMoves.Snapshot.NONE, fails.snapshot(OVERWORLD, 50_000));
        // a fresh failure after that is a first failure again
        fails.record(SRC, DEST, OVERWORLD, 60_000);
        assertEquals(FailedMoves.FIRST, penalty(fails.snapshot(OVERWORLD, 60_000), SRC, DEST), 0);
    }

    @Test
    public void anExpiredEntryNobodySnapshottedStillStartsOver() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        fails.record(SRC, DEST, OVERWORLD, 0);
        fails.record(SRC, DEST, OVERWORLD, 40_000);
        assertEquals(FailedMoves.FIRST, penalty(fails.snapshot(OVERWORLD, 40_000), SRC, DEST), 0);
    }

    @Test
    public void onlyThatMoveAndOnlyThatWay() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        FailedMoves.Snapshot s = fails.snapshot(OVERWORLD, 0);
        assertEquals(0, penalty(s, SRC, OTHER), 0);
        assertEquals(0, penalty(s, DEST, SRC), 0);
        assertEquals(0, penalty(s, SRC.above(), DEST.above()), 0);
    }

    @Test
    public void eachMoveCountsOnItsOwn() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        fails.record(SRC, DEST, OVERWORLD, 0);
        fails.record(SRC, OTHER, OVERWORLD, 0);
        FailedMoves.Snapshot s = fails.snapshot(OVERWORLD, 0);
        assertEquals(FailedMoves.SECOND, penalty(s, SRC, DEST), 0);
        assertEquals(FailedMoves.FIRST, penalty(s, SRC, OTHER), 0);
    }

    @Test
    public void changingDimensionForgetsEverything() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        assertSame(FailedMoves.Snapshot.NONE, fails.snapshot(NETHER, 0));
        assertSame(FailedMoves.Snapshot.NONE, fails.snapshot(OVERWORLD, 0));
    }

    @Test
    public void aSnapshotDoesntChangeUnderTheSearch() {
        FailedMoves fails = new FailedMoves();
        fails.record(SRC, DEST, OVERWORLD, 0);
        FailedMoves.Snapshot before = fails.snapshot(OVERWORLD, 0);
        fails.record(SRC, DEST, OVERWORLD, 0);
        assertEquals(FailedMoves.FIRST, penalty(before, SRC, DEST), 0);
    }
}
