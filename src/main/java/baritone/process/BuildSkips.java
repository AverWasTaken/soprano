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

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

// the builder stops the path for anything it can reach, and a block it can reach but never gets done used to
// stop the path forever. this counts the ticks spent standing still for each block and gives up on it for a
// while once that gets silly. positions are BetterBlockPos.serializeToLong, time is the builder's own tick count,
// no minecraft in here so the tests don't need a world
final class BuildSkips {
    // see fixes()
    private int fixes;

    // placing something that works takes a handful of ticks: turn, sneak, click
    static final int STUCK_TICKS = 40;
    // twitching between three blocks that each get 30 ticks never trips STUCK_TICKS on its own, so this catches that
    static final int WINDOW_TICKS = 120;
    static final int SKIP_TICKS = 600;
    // a block nobody touched for this long starts from zero again
    static final int FORGET_TICKS = 600;
    // the client draws a placed or broken block before the server says yes, and a protected cell gets put back a
    // round trip later. one good look would reset the count on every click and the skip would never come.
    // two seconds is a lot of ping, even for 2b2t on a bad day
    static final int CONFIRM_TICKS = 40;
    // standing in a goal with nothing to click is a "not from here", not a "not ever". short, so the next anchor or the
    // next spot to stand gets a go at it soon
    static final int SOFT_SKIP_TICKS = 200;

    // pos -> ticks spent on it. breaking and placing count apart: a slow obsidian break followed by placing
    // the stone that goes there is two jobs, not one stuck one
    private final Long2LongOpenHashMap placeWorked = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap breakWorked = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap lastWorked = new Long2LongOpenHashMap(); // every pos we're keeping an eye on
    private final Long2LongOpenHashMap skipUntil = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap fixedSince = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap fixedLastSeen = new Long2LongOpenHashMap();
    private final LongOpenHashSet window = new LongOpenHashSet();
    // soft skips live apart from skipUntil: no counts get dropped, the window doesn't hear about it, and the
    // "only skipped left" retry can let them go without burning one of its tries
    private final Long2LongOpenHashMap softUntil = new Long2LongOpenHashMap();
    // pos -> tick of its last soft skip. outlives softUntil, that's the point: twice in a row is a real skip
    private final Long2LongOpenHashMap softLast = new Long2LongOpenHashMap();
    private int windowTicks;
    private int windowLimit = WINDOW_TICKS;
    private long lastTick = Long.MIN_VALUE;

    // one tick of standing still for pos. limit is how many ticks pos is allowed (a slow break gets more).
    // returns whatever got skipped because of this tick, usually nothing
    LongList work(long pos, long now, int limit, boolean breaking) {
        return work(pos, now, limit, breaking, p -> false);
    }

    // right: cells that are correct right now. the window takes everything it saw, done ones too, and a skip of a right
    // block is just noise. they get left out of the skip instead of skipped and let go after: skip() forgets the
    // counts, and a protected cell that looks right for a round trip mustn't get a fresh 40 out of it. not a fix
    // either, that still has to hold for CONFIRM_TICKS (looksFixed)
    LongList work(long pos, long now, int limit, boolean breaking, LongPredicate right) {
        if (lastTick != Long.MIN_VALUE && now - lastTick > FORGET_TICKS) {
            resetWindow();
        }
        lastTick = now;
        forgetOld(now);
        Long2LongOpenHashMap worked = breaking ? breakWorked : placeWorked;
        long count = worked.get(pos) + 1;
        worked.put(pos, count);
        lastWorked.put(pos, now);
        window.add(pos);
        windowLimit = Math.max(windowLimit, limit);
        windowTicks++;
        LongList skipped = new LongArrayList();
        if (count >= limit && !right.test(pos)) {
            skip(pos, now);
            skipped.add(pos);
        }
        if (windowTicks >= windowLimit) {
            // a whole window and not one block got done, so none of them are working
            for (long p : window) {
                if (!skipped.contains(p) && !isSkipped(p, now) && !right.test(p)) {
                    skip(p, now);
                    skipped.add(p);
                }
            }
            resetWindow();
        }
        return skipped;
    }

    // nothing to click on it from where we stand. true if it was soft skipped recently already and got a real skip
    // instead, a cell that keeps leaving us staring isn't a bad spot, it's a bad cell
    boolean softSkip(long pos, long now) {
        forgetOldSoft(now);
        boolean again = softLast.containsKey(pos);
        softLast.put(pos, now);
        if (again) {
            softUntil.remove(pos);
            skip(pos, now);
            return true;
        }
        softUntil.put(pos, now + SOFT_SKIP_TICKS);
        return false;
    }

    // pos is correct now, whatever we were doing works
    void fixed(long pos) {
        fixes++;
        forget(pos);
        skipUntil.remove(pos);
        resetWindow();
    }

    // pos looks correct this tick. it only counts once it has stayed that way for CONFIRM_TICKS
    void looksFixed(long pos, long now) {
        // we only look on ticks spent in reach, so a gap means we walked off and have no idea what it did meanwhile
        if (!fixedSince.containsKey(pos) || now - fixedLastSeen.get(pos) > 2) {
            fixedSince.put(pos, now);
        } else if (now - fixedSince.get(pos) >= CONFIRM_TICKS) {
            fixed(pos);
            return;
        }
        fixedLastSeen.put(pos, now);
    }

    // it was a ghost, the count carries on from where it was
    void looksWrong(long pos) {
        fixedSince.remove(pos);
        fixedLastSeen.remove(pos);
    }

    // a cell a click went into, so it gets checked like the ones we're stuck on. costs it no ticks
    void watch(long pos, long now) {
        lastWorked.put(pos, now);
    }

    // the positions that might turn out to be fixed, for the builder to check. a copy, fixed() edits the map
    LongList watched() {
        return new LongArrayList(lastWorked.keySet());
    }

    boolean isSkipped(long pos, long now) {
        return skippedIn(skipUntil, pos, now) || skippedIn(softUntil, pos, now);
    }

    private static boolean skippedIn(Long2LongOpenHashMap until, long pos, long now) {
        if (until.isEmpty()) {
            return false; // the scans ask this for every block in range, every tick
        }
        if (!until.containsKey(pos)) {
            return false;
        }
        if (until.get(pos) > now) {
            return true;
        }
        until.remove(pos);
        return false;
    }

    // cells fixed so far, never cleared. the builder compares it across its retries to see if they get anywhere
    int fixes() {
        return fixes;
    }

    // the real skips only, see anySoftSkipped
    boolean anySkipped(long now) {
        return anyLeft(skipUntil, now);
    }

    boolean anySoftSkipped(long now) {
        return anyLeft(softUntil, now);
    }

    private static boolean anyLeft(Long2LongOpenHashMap until, long now) {
        for (LongIterator it = until.values().iterator(); it.hasNext(); ) {
            if (it.nextLong() <= now) {
                it.remove();
            }
        }
        return !until.isEmpty();
    }

    // the history stays, a cell let go of here and stared at again still gets the real skip
    void clearSoftSkips() {
        softUntil.clear();
    }

    void clear() {
        softUntil.clear();
        softLast.clear();
        placeWorked.clear();
        breakWorked.clear();
        lastWorked.clear();
        skipUntil.clear();
        fixedSince.clear();
        fixedLastSeen.clear();
        resetWindow();
        lastTick = Long.MIN_VALUE;
    }

    private void skip(long pos, long now) {
        skipUntil.put(pos, now + SKIP_TICKS);
        // coming back to it later gets the full 40 again
        forget(pos);
    }

    private void forget(long pos) {
        placeWorked.remove(pos);
        breakWorked.remove(pos);
        lastWorked.remove(pos);
        fixedSince.remove(pos);
        fixedLastSeen.remove(pos);
    }

    private void resetWindow() {
        window.clear();
        windowTicks = 0;
        windowLimit = WINDOW_TICKS;
    }

    private void forgetOld(long now) {
        forgetOldSoft(now);
        if (lastWorked.isEmpty()) {
            return;
        }
        for (ObjectIterator<Long2LongMap.Entry> it = lastWorked.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (now - e.getLongValue() > FORGET_TICKS) {
                long pos = e.getLongKey();
                it.remove();
                forget(pos);
            }
        }
    }

    private void forgetOldSoft(long now) {
        if (softLast.isEmpty()) {
            return;
        }
        for (LongIterator it = softLast.values().iterator(); it.hasNext(); ) {
            if (now - it.nextLong() > FORGET_TICKS) {
                it.remove();
            }
        }
    }
}
