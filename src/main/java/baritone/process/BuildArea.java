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

import java.util.ArrayList;
import java.util.List;

// every place and break drops the path, and the next goal is every wrong cell in the build, so the next A* went to
// whatever was nearest that tick. break here, place over there, back again. this is the bit that remembers which
// corner we were doing: goals only come from around the anchor until it's done or clearly not happening.
// no minecraft in here besides the pos class, same as BuildSkips
final class BuildArea {

    static final int RADIUS = 4;
    // "inside" is anywhere we could be working on it from, a cell on the edge is still in reach from 4.5 further out
    static final double REACH = 4.5;
    // no confirmed fix and no cell crossed off for this long while standing in it means it isn't working. a fix shows
    // up CONFIRM_TICKS after the click, so a normal build gets one every couple of seconds
    static final int STALE_TICKS = 200;
    static final int BAN_TICKS = 600;

    private BetterBlockPos anchor;
    private int staleTicks;
    private int lastFixes = Integer.MIN_VALUE;
    // wrong cells left around the anchor last time choose looked. a fix only counts after standing next to it for
    // CONFIRM_TICKS, place one and walk on and it never does, so a shrinking area counts as getting somewhere too
    private int lastLeft = Integer.MAX_VALUE;
    private final List<long[]> bans = new ArrayList<>(); // {x, y, z, until}
    // the anchor a timeout or a failed path just let go of, until the next pick decides whether it was the last place left
    private BetterBlockPos gaveUp;

    BetterBlockPos anchor() {
        return anchor;
    }

    // no anchor means no preference, everything is in
    boolean contains(int x, int y, int z) {
        return anchor == null || distSq(anchor, x, y, z) <= RADIUS * RADIUS;
    }

    // no anchor yet, or the anchor still has some of open around it. a no means let the caller look harder first
    boolean hasWork(List<BetterBlockPos> open) {
        return anchor == null || anyWithin(open, anchor);
    }

    boolean clock(BetterBlockPos player, long now, int fixes) {
        return clock(player, now, fixes, STALE_TICKS);
    }

    // once per builder tick. true if it just gave up on the area for taking too long. limit can only make it longer,
    // an obsidian break with a bad pick takes more than 200 ticks all by itself
    boolean clock(BetterBlockPos player, long now, int fixes, int limit) {
        if (fixes != lastFixes) {
            lastFixes = fixes;
            staleTicks = 0;
        }
        if (anchor == null) {
            return false;
        }
        double inside = RADIUS + REACH;
        if (distSq(anchor, player.x, player.y, player.z) > inside * inside) {
            return false; // still walking there, that's not the area's fault
        }
        if (++staleTicks < Math.max(STALE_TICKS, limit)) {
            return false;
        }
        giveUp(now);
        return true;
    }

    // the path there failed or it ran out of time, either way go somewhere else for a while. if there is nowhere else
    // the next choose skips it, see there
    void giveUp(long now) {
        gaveUp = anchor;
        release(now, true);
    }

    void release(long now, boolean ban) {
        if (anchor != null && ban) {
            bans.add(new long[]{anchor.x, anchor.y, anchor.z, now + BAN_TICKS});
        }
        anchor = null;
        staleTicks = 0;
    }

    void reset() {
        anchor = null;
        staleTicks = 0;
        lastFixes = Integer.MIN_VALUE;
        lastLeft = Integer.MAX_VALUE;
        bans.clear();
        gaveUp = null;
    }

    // open: wrong cells that would get a goal. skipped: the ones that would, if they weren't skipped.
    // keeps the anchor if it still has work, else picks a new one. returns cells to give up on, usually none: see below
    List<BetterBlockPos> choose(List<BetterBlockPos> open, List<BetterBlockPos> skipped, BetterBlockPos player, long now) {
        bans.removeIf(b -> b[3] <= now);
        if (anchor != null && !anyWithin(open, anchor)) {
            // done, or everything left in it got skipped. only the second one is worth staying away from
            release(now, anyWithin(skipped, anchor));
        }
        List<BetterBlockPos> giveUp = new ArrayList<>();
        if (anchor != null) {
            int left = countWithin(open, anchor) + countWithin(skipped, anchor);
            if (left < lastLeft) {
                staleTicks = 0;
            }
            lastLeft = left;
            return giveUp;
        }
        List<BetterBlockPos> pool = open;
        if (gaveUp != null && pick(open, player, true) == null) {
            // we just gave up on it and there's nowhere else to go, so the next pick is straight back into it. cells
            // that get a goal but never get offered for a click (or never get a path) would go round like that forever,
            // so they get skipped the normal way and the anySkipped retries and the pause deal with them like any
            // other stuck block
            pool = new ArrayList<>();
            for (BetterBlockPos p : open) {
                (distSq(gaveUp, p.x, p.y, p.z) <= RADIUS * RADIUS ? giveUp : pool).add(p);
            }
        }
        gaveUp = null;
        BetterBlockPos next = pick(pool, player, true);
        if (next == null) {
            next = pick(pool, player, false); // everything left is near a ban, a banned area beats no area
        }
        anchor = next;
        staleTicks = 0;
        lastLeft = next == null ? Integer.MAX_VALUE : countWithin(pool, next) + countWithin(skipped, next);
        return giveUp;
    }

    // lowest first then nearest, the same bias GoalPlace and GoalAdjacent put in the heuristic
    private BetterBlockPos pick(List<BetterBlockPos> cells, BetterBlockPos player, boolean respectBans) {
        BetterBlockPos best = null;
        double bestDist = 0;
        for (BetterBlockPos p : cells) {
            if (respectBans && banned(p)) {
                continue;
            }
            double d = distSq(player, p.x, p.y, p.z);
            if (best == null || p.y < best.y || (p.y == best.y && d < bestDist)) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    private boolean banned(BetterBlockPos p) {
        double min = RADIUS + 1;
        for (long[] b : bans) {
            double dx = p.x - b[0];
            double dy = p.y - b[1];
            double dz = p.z - b[2];
            if (dx * dx + dy * dy + dz * dz < min * min) {
                return true;
            }
        }
        return false;
    }

    private static int countWithin(List<BetterBlockPos> cells, BetterBlockPos center) {
        int n = 0;
        for (BetterBlockPos p : cells) {
            if (distSq(center, p.x, p.y, p.z) <= RADIUS * RADIUS) {
                n++;
            }
        }
        return n;
    }

    private static boolean anyWithin(List<BetterBlockPos> cells, BetterBlockPos center) {
        for (BetterBlockPos p : cells) {
            if (distSq(center, p.x, p.y, p.z) <= RADIUS * RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static double distSq(BetterBlockPos a, int x, int y, int z) {
        double dx = a.x - x;
        double dy = a.y - y;
        double dz = a.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
