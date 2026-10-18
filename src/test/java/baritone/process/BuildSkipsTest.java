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

import it.unimi.dsi.fastutil.longs.LongList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuildSkipsTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;

    @Test
    public void skipsAfterTheLimitAndNotBefore() {
        BuildSkips skips = new BuildSkips();
        for (int t = 0; t < BuildSkips.STUCK_TICKS - 1; t++) {
            assertTrue(skips.work(A, t, BuildSkips.STUCK_TICKS, false).isEmpty());
        }
        assertFalse(skips.isSkipped(A, 100));
        LongList skipped = skips.work(A, BuildSkips.STUCK_TICKS, BuildSkips.STUCK_TICKS, false);
        assertEquals(1, skipped.size());
        assertEquals(A, skipped.getLong(0));
        assertTrue(skips.isSkipped(A, BuildSkips.STUCK_TICKS + 1));
    }

    @Test
    public void countsTotalTicksNotAStreak() {
        // walking off and coming back used to start the count over, now it's the total
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.STUCK_TICKS / 2; i++) {
            skips.work(A, t++, BuildSkips.STUCK_TICKS, false);
        }
        t += 50; // pathing somewhere else for a bit, well under FORGET_TICKS
        boolean got = false;
        for (int i = 0; i < BuildSkips.STUCK_TICKS / 2; i++) {
            got |= skips.work(A, t++, BuildSkips.STUCK_TICKS, false).contains(A);
        }
        assertTrue(got);
    }

    @Test
    public void forgetsAfterALongBreak() {
        BuildSkips skips = new BuildSkips();
        for (int t = 0; t < BuildSkips.STUCK_TICKS - 1; t++) {
            skips.work(A, t, BuildSkips.STUCK_TICKS, false);
        }
        long later = BuildSkips.STUCK_TICKS + BuildSkips.FORGET_TICKS + 10;
        assertTrue(skips.work(A, later, BuildSkips.STUCK_TICKS, false).isEmpty());
        assertFalse(skips.isSkipped(A, later));
    }

    @Test
    public void skipExpires() {
        BuildSkips skips = new BuildSkips();
        for (int t = 0; t < BuildSkips.STUCK_TICKS; t++) {
            skips.work(A, t, BuildSkips.STUCK_TICKS, false);
        }
        long at = BuildSkips.STUCK_TICKS - 1; // the tick it got skipped on
        assertTrue(skips.isSkipped(A, at + BuildSkips.SKIP_TICKS - 1));
        assertFalse(skips.isSkipped(A, at + BuildSkips.SKIP_TICKS));
        assertFalse(skips.anySkipped(at + BuildSkips.SKIP_TICKS));
    }

    @Test
    public void fixedClearsTheCount() {
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.STUCK_TICKS - 1; i++) {
            skips.work(A, t++, BuildSkips.STUCK_TICKS, false);
        }
        assertTrue(skips.watched().contains(A));
        skips.fixed(A);
        assertFalse(skips.watched().contains(A));
        assertTrue(skips.work(A, t, BuildSkips.STUCK_TICKS, false).isEmpty());
    }

    @Test
    public void windowCatchesHoppingBetweenTargets() {
        // three blocks taking turns never reach STUCK_TICKS each, but nothing got done in the whole window
        BuildSkips skips = new BuildSkips();
        long[] targets = {A, B, C};
        LongList last = null;
        int t;
        for (t = 0; t < BuildSkips.WINDOW_TICKS; t++) {
            LongList got = skips.work(targets[t % 3], t, BuildSkips.STUCK_TICKS + 1, false);
            if (!got.isEmpty()) {
                last = got;
                break;
            }
        }
        assertEquals(BuildSkips.WINDOW_TICKS - 1, t);
        assertEquals(3, last.size());
        assertTrue(skips.isSkipped(A, t + 1) && skips.isSkipped(B, t + 1) && skips.isSkipped(C, t + 1));
    }

    @Test
    public void fixingSomethingResetsTheWindow() {
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.WINDOW_TICKS - 1; i++) {
            skips.work(i % 2 == 0 ? A : B, t++, 100, false);
        }
        skips.fixed(C);
        assertTrue(skips.work(A, t, 100, false).isEmpty());
    }

    @Test
    public void slowBreakStretchesTheWindow() {
        // obsidian with a bad pick takes ages, the window must not give up on it before its own limit does
        BuildSkips skips = new BuildSkips();
        int limit = BuildSkips.WINDOW_TICKS * 3;
        for (int t = 0; t < limit - 1; t++) {
            assertTrue(skips.work(A, t, limit, false).isEmpty());
        }
        assertTrue(skips.work(A, limit - 1, limit, false).contains(A));
    }

    @Test
    public void clearForgetsSkips() {
        // what the builder does when the skips are the only goals left
        BuildSkips skips = new BuildSkips();
        for (int t = 0; t <= BuildSkips.STUCK_TICKS; t++) {
            skips.work(A, t, BuildSkips.STUCK_TICKS, false);
        }
        assertTrue(skips.anySkipped(BuildSkips.STUCK_TICKS + 1));
        skips.clear();
        assertFalse(skips.anySkipped(BuildSkips.STUCK_TICKS + 1));
        assertFalse(skips.isSkipped(A, BuildSkips.STUCK_TICKS + 1));
    }

    @Test
    public void oneGoodLookIsNotProof() {
        // a ghost block from client prediction, put back by the server a few ticks later
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.STUCK_TICKS - 5; i++) {
            skips.work(A, t++, BuildSkips.STUCK_TICKS, false);
        }
        skips.looksFixed(A, t++);
        skips.looksFixed(A, t++);
        skips.looksWrong(A);
        // the count carried on, five more and it's skipped
        boolean got = false;
        for (int i = 0; i < 5; i++) {
            got |= skips.work(A, t++, BuildSkips.STUCK_TICKS, false).contains(A);
        }
        assertTrue(got);
    }

    @Test
    public void staysFixedLongEnoughToCount() {
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.STUCK_TICKS - 1; i++) {
            skips.work(A, t++, BuildSkips.STUCK_TICKS, false);
        }
        for (int i = 0; i < BuildSkips.CONFIRM_TICKS; i++) {
            skips.looksFixed(A, t + i);
        }
        assertTrue(skips.watched().contains(A));
        skips.looksFixed(A, t + BuildSkips.CONFIRM_TICKS);
        assertFalse(skips.watched().contains(A));
    }

    @Test
    public void watchedClickCountsAsProgress() {
        // the click went into B while we were counting A, B getting done still resets the window
        BuildSkips skips = new BuildSkips();
        long t = 0;
        for (int i = 0; i < BuildSkips.WINDOW_TICKS - 1; i++) {
            skips.work(i % 2 == 0 ? A : C, t++, 100, false);
        }
        skips.watch(B, t);
        assertTrue(skips.watched().contains(B));
        for (int i = 0; i <= BuildSkips.CONFIRM_TICKS; i++) {
            skips.looksFixed(B, t + i);
        }
        assertTrue(skips.work(A, t + BuildSkips.CONFIRM_TICKS, 100, false).isEmpty());
    }

    @Test
    public void notConfirmedOneTickEarly() {
        BuildSkips skips = new BuildSkips();
        skips.work(A, 0, BuildSkips.STUCK_TICKS, false);
        for (int t = 1; t < 1 + BuildSkips.CONFIRM_TICKS; t++) {
            skips.looksFixed(A, t);
        }
        assertTrue(skips.watched().contains(A));
        skips.looksFixed(A, 1 + BuildSkips.CONFIRM_TICKS);
        assertFalse(skips.watched().contains(A));
    }

    @Test
    public void walkingOffRestartsTheConfirm() {
        // a look from before we left proves nothing about now
        BuildSkips skips = new BuildSkips();
        skips.work(A, 0, BuildSkips.STUCK_TICKS, false);
        skips.looksFixed(A, 1);
        skips.looksFixed(A, 1 + BuildSkips.CONFIRM_TICKS + 5);
        assertTrue(skips.watched().contains(A));
    }

    @Test
    public void breakThenPlaceAreSeparateJobs() {
        // obsidian takes ages to break, the stone going in after it shouldn't inherit that
        BuildSkips skips = new BuildSkips();
        int breakLimit = 400;
        long t = 0;
        for (int i = 0; i < 200; i++) {
            assertTrue(skips.work(A, t++, breakLimit, true).isEmpty());
        }
        assertTrue(skips.work(A, t, BuildSkips.STUCK_TICKS, false).isEmpty());
    }

    @Test
    public void breakGhostsStillAddUp() {
        // protected block: broken on our screen, put back by the server, broken again. the break count keeps going
        BuildSkips skips = new BuildSkips();
        long t = 0;
        boolean got = false;
        for (int round = 0; round < 10 && !got; round++) {
            for (int i = 0; i < 8; i++) {
                got |= skips.work(A, t++, BuildSkips.STUCK_TICKS, true).contains(A);
            }
            skips.work(A, t++, BuildSkips.STUCK_TICKS, false); // a tick where it looked like air wanting a block
            skips.looksFixed(A, t);
            skips.looksWrong(A);
        }
        assertTrue(got);
    }
}
