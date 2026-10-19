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

import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TorchMemoryTest {

    private static final long CELL = 42L;
    private static final long OTHER = 7L;

    @Test
    public void oneTorchInFlightThenTheCooldown() {
        TorchMemory memory = new TorchMemory();
        assertFalse(memory.coolingDown(0));
        memory.clicked(CELL, 100);
        assertTrue(memory.coolingDown(100));
        // still waiting on the verdict
        assertTrue(memory.coolingDown(100 + TorchMemory.PLACE_COOLDOWN + 50));
        memory.verdict(true, 100 + TorchMemory.CHECK_DELAY);
        assertTrue(memory.coolingDown(100 + TorchMemory.PLACE_COOLDOWN - 1));
        assertFalse(memory.coolingDown(100 + TorchMemory.PLACE_COOLDOWN));
    }

    @Test
    public void verdictWaitsForTheServer() {
        TorchMemory memory = new TorchMemory();
        assertFalse(memory.due(0).isPresent());
        memory.clicked(CELL, 10);
        assertFalse(memory.due(10 + TorchMemory.CHECK_DELAY - 1).isPresent());
        OptionalLong due = memory.due(10 + TorchMemory.CHECK_DELAY);
        assertTrue(due.isPresent());
        assertEquals(CELL, due.getAsLong());
        memory.verdict(false, 10 + TorchMemory.CHECK_DELAY);
        assertFalse(memory.due(10 + TorchMemory.CHECK_DELAY).isPresent());
    }

    @Test
    public void triedCellsRestThenComeBack() {
        TorchMemory memory = new TorchMemory();
        memory.clicked(CELL, 0);
        assertTrue(memory.skip(CELL, TorchMemory.RETRY_TICKS - 1));
        assertFalse(memory.skip(OTHER, 1));
        assertFalse(memory.skip(CELL, TorchMemory.RETRY_TICKS));
    }

    @Test
    public void threeMissesBlacklistTheCell() {
        TorchMemory memory = new TorchMemory();
        long now = 0;
        long listed = 0;
        for (int i = 0; i < TorchMemory.MAX_MISSES; i++) {
            assertFalse(memory.skip(CELL, now));
            memory.clicked(CELL, now);
            now += TorchMemory.CHECK_DELAY;
            memory.verdict(false, now);
            listed = now;
            now += TorchMemory.RETRY_TICKS;
            memory.prune(now);
        }
        assertTrue(memory.skip(CELL, now));
        assertTrue(memory.skip(CELL, listed + TorchMemory.BLACKLIST_TICKS - 1));
        memory.prune(listed + TorchMemory.BLACKLIST_TICKS);
        assertFalse(memory.skip(CELL, listed + TorchMemory.BLACKLIST_TICKS));
    }

    @Test
    public void hitsDontCountAsMisses() {
        TorchMemory memory = new TorchMemory();
        long now = 0;
        for (int i = 0; i < TorchMemory.MAX_MISSES + 2; i++) {
            memory.clicked(CELL, now);
            now += TorchMemory.CHECK_DELAY;
            memory.verdict(true, now);
            now += TorchMemory.RETRY_TICKS;
        }
        assertFalse(memory.skip(CELL, now));
    }

    @Test
    public void hitsDontWipeMissesEither() {
        // a torch the server takes back late reads as a hit, it must not reset the count
        TorchMemory memory = new TorchMemory();
        long now = 0;
        boolean[] verdicts = {false, true, false, true, false};
        for (boolean placed : verdicts) {
            memory.clicked(CELL, now);
            now += TorchMemory.CHECK_DELAY;
            memory.verdict(placed, now);
            now += TorchMemory.RETRY_TICKS;
        }
        assertTrue(memory.skip(CELL, now));
    }

    @Test
    public void oldMissesFade() {
        TorchMemory memory = new TorchMemory();
        long now = 0;
        for (int i = 0; i < TorchMemory.MAX_MISSES - 1; i++) {
            memory.clicked(CELL, now);
            now += TorchMemory.CHECK_DELAY;
            memory.verdict(false, now);
        }
        now += TorchMemory.BLACKLIST_TICKS;
        memory.prune(now);
        memory.clicked(CELL, now);
        now += TorchMemory.CHECK_DELAY;
        memory.verdict(false, now);
        assertFalse(memory.skip(CELL, now + TorchMemory.RETRY_TICKS));
    }

    @Test
    public void aimingTooLongShortListsTheCell() {
        TorchMemory memory = new TorchMemory();
        for (int t = 0; t < TorchMemory.AIM_TIMEOUT; t++) {
            assertFalse(memory.aimTimedOut(CELL, t));
        }
        assertTrue(memory.aimTimedOut(CELL, TorchMemory.AIM_TIMEOUT));
        assertTrue(memory.skip(CELL, TorchMemory.AIM_TIMEOUT + TorchMemory.AIM_BLACKLIST_TICKS - 1));
        assertFalse(memory.skip(CELL, TorchMemory.AIM_TIMEOUT + TorchMemory.AIM_BLACKLIST_TICKS));
    }

    @Test
    public void aimClockStartsOverAfterAGap() {
        TorchMemory memory = new TorchMemory();
        for (int t = 0; t < TorchMemory.AIM_TIMEOUT - 1; t++) {
            memory.aimTimedOut(CELL, t);
        }
        // somebody else had the wheel for a few ticks
        long back = TorchMemory.AIM_TIMEOUT + 5;
        assertFalse(memory.aimTimedOut(CELL, back));
        // a new cell is a new clock too
        assertFalse(memory.aimTimedOut(OTHER, back + 1));
        memory.stopAiming();
        assertFalse(memory.aimTimedOut(OTHER, back + 2));
    }

    @Test
    public void emptyScansBackOff() {
        TorchMemory memory = new TorchMemory();
        assertTrue(memory.scanDue(0));
        memory.foundNothing(10);
        assertFalse(memory.scanDue(10 + TorchMemory.EMPTY_SCAN_BACKOFF - 1));
        assertTrue(memory.scanDue(10 + TorchMemory.EMPTY_SCAN_BACKOFF));
    }

    @Test
    public void resetForgetsEverything() {
        TorchMemory memory = new TorchMemory();
        memory.clicked(CELL, 1000);
        memory.aimTimedOut(OTHER, 1000);
        memory.foundNothing(1000);
        memory.reset();
        // a new player counts from 0, nothing from the old clock may linger
        assertFalse(memory.coolingDown(0));
        assertFalse(memory.skip(CELL, 0));
        assertFalse(memory.due(5000).isPresent());
        assertTrue(memory.scanDue(0));
    }
}
