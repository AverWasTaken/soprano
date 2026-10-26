package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// the "where is the lava and how do we get there" half of the lava portal, with no minecraft in it so it can be tested.
// LPortalTask feeds it the scanned lava seeds and what the lake surveys found, it says walk, wait or give up (to the cast).
// every lake in the loaded chunks gets a verdict before the first walk, so a walk goes to a lake that passed and not to the
// cheapest seed
final class LavaHunt {
    // only lakes this far above and below us count. a lake 50 blocks down is a cave lake and those always fail the floor rule
    static final int BAND = 24;
    // the band never sinks below the surface. starting the portal from a cave (an item pickup at y 2, say) would centre it
    // on cave lava 20 blocks down, and the walk there is cliff rims over the pool
    static final int SURFACE_Y = 60;
    static final int PER_CHUNK = 3;
    // the far lakes have to make the list too, the scan keeps the nearest ones
    static final int SEED_MAX = 200;
    // a seed with no site found this close to it clears every seed in the same lake
    static final int SITE_RADIUS = 24;
    // a good lake this close is built on from where we stand, a farther one is walked to first
    static final int PICK_REACH = 40;
    // nobody walks 75 s to a lake. it's not reachable
    static final int WALK_TICKS = 20 * 75;
    // a walk that is not this much closer than its best in this long is stuck. the goal being re-issued does not count
    static final int PROGRESS_BLOCKS = 3;
    static final int PROGRESS_TICKS = 20 * 15;
    // a lake we could not read all of is looked at again after this
    static final int UNKNOWN_TICKS = 20 * 10;
    // going up or down costs more than going sideways, so a lake at our level wins over one under our feet
    static final int DY_COST = 4;

    enum Move {WALK, WAIT, GIVE_UP}

    // at most PER_CHUNK hits per chunk, counted inside the scanner's filter (it stops a chunk at max, a count after the
    // fact would be too late). the filter runs one thread per chunk but computeIfAbsent can race, so the map is concurrent
    static final class ChunkCap {
        private final Map<Long, int[]> counts = new ConcurrentHashMap<>();

        private static long key(int x, int z) {
            return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
        }

        boolean full(int x, int z) {
            int[] c = counts.get(key(x, z));
            return c != null && c[0] >= PER_CHUNK;
        }

        boolean take(int x, int z) {
            return ++counts.computeIfAbsent(key(x, z), k -> new int[1])[0] <= PER_CHUNK;
        }
    }

    static int bandCentre(int py) {
        return Math.max(py, SURFACE_Y);
    }

    static double cost(P seed, int px, int py, int pz) {
        return Math.hypot(seed.x() - px, seed.z() - pz) + DY_COST * Math.abs(seed.y() - py);
    }

    static List<P> byCost(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>(new HashSet<>(seeds));
        out.sort(Comparator.comparingDouble((P p) -> cost(p, px, py, pz)).thenComparingInt(P::x).thenComparingInt(P::y).thenComparingInt(P::z));
        return out;
    }

    static P mid(Layout l) {
        return l.cell(1, 0, 0);
    }

    private record Entry(Lake lake, int tick) {
    }

    // the lakes we gave up on. the phase hands the list in, so a retry of the phase doesn't walk to the same ones again
    private final List<P> rejected;
    private final List<Entry> lakes = new ArrayList<>();
    private P target;
    private int walkStart;
    private double bestDist;
    private int progressTick;

    LavaHunt() {
        this(new ArrayList<>());
    }

    LavaHunt(List<P> rejected) {
        this.rejected = rejected;
    }

    P target() {
        return target;
    }

    // every seed we may still look at, cheapest first
    List<P> usable(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>();
        for (P p : byCost(seeds, px, py, pz)) {
            if (!nearRejected(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // the cheapest seed whose lake has no verdict yet, or null when every lake has one
    P nextToSurvey(List<P> seeds, int px, int py, int pz, int tick) {
        lakes.removeIf(e -> e.lake().unknown() && tick - e.tick() > UNKNOWN_TICKS);
        for (P p : usable(seeds, px, py, pz)) {
            if (!covered(p)) {
                return p;
            }
        }
        return null;
    }

    private boolean covered(P seed) {
        for (Entry e : lakes) {
            if (e.lake().cells().contains(seed)) {
                return true;
            }
        }
        return false;
    }

    void surveyed(Lake lake, int tick) {
        lakes.add(new Entry(lake, tick));
    }

    // a verdict that went stale between the survey and the pick. the lake is looked at again
    void drop(Layout site) {
        lakes.removeIf(e -> site.equals(e.lake().site()));
        target = null;
    }

    // terrain says good, the bag decides if we can build it right now. a lake we can't afford is skipped, never rejected
    private boolean usableLake(Lake l, int blocks) {
        return l.good() && !nearRejected(mid(l.site())) && blocks >= 8 + l.fills();
    }

    // the good lake to build on from here: close enough that the build clock isn't a walk, cheapest work first. while other lakes
    // are still being looked at only a perfect one is taken at once
    Layout pick(int px, int py, int pz, int blocks, boolean perfectOnly) {
        Layout best = null;
        int bestCost = 0;
        double bestFlat = 0;
        for (Entry e : lakes) {
            Lake l = e.lake();
            if (!usableLake(l, blocks)) {
                continue;
            }
            P m = mid(l.site());
            // the lake we walked to is the one lake that isn't made to wait for the others
            if (perfectOnly && l.cost() != 0 && !m.equals(target)) {
                continue;
            }
            double flat = Math.hypot(m.x() - px, m.z() - pz);
            if (flat > PICK_REACH || Math.abs(m.y() - py) > BAND) {
                continue;
            }
            if (best == null || l.cost() < bestCost || (l.cost() == bestCost && flat < bestFlat)) {
                best = l.site();
                bestCost = l.cost();
                bestFlat = flat;
            }
        }
        return best;
    }

    // a site we picked turned out bad (lava showed up while clearing it, the clearing ran out of time, the walk went nowhere).
    // its lake is not visited again, by this task or by the retry after it
    void siteFailed(P around) {
        rejected.add(around);
        lakes.removeIf(e -> dist(e.lake().anchor(), around) <= SITE_RADIUS || e.lake().cells().contains(around)
                || (e.lake().good() && dist(mid(e.lake().site()), around) <= SITE_RADIUS));
        target = null;
    }

    private boolean nearRejected(P p) {
        for (P r : rejected) {
            if (dist(p, r) <= SITE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static double dist(P a, P b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean stillGood(int blocks) {
        for (Entry e : lakes) {
            if (usableLake(e.lake(), blocks) && mid(e.lake().site()).equals(target)) {
                return true;
            }
        }
        return false;
    }

    // what to do this tick, with nothing near enough to build on. scanned = a seed scan has finished, so an empty list means
    // there is really no lava in the loaded chunks and not that we haven't looked yet. giving up (to the cast) needs that, a
    // verdict on every lake, and none of them good
    Move decide(List<P> seeds, boolean scanned, boolean surveying, int px, int py, int pz, int tick, int blocks) {
        if (!scanned) {
            return Move.WAIT;
        }
        if (target != null && !stillGood(blocks)) {
            target = null;
        }
        if (target == null) {
            // the first walk waits for every verdict, a walk already going doesn't stop for a new lake
            // surveying: the lake in the middle of a survey may have slid out of the newest seed list, it still has no verdict
            if (surveying || nextToSurvey(seeds, px, py, pz, tick) != null) {
                return Move.WAIT;
            }
            P best = null;
            double bestCost = 0;
            for (Entry e : lakes) {
                if (!usableLake(e.lake(), blocks)) {
                    continue;
                }
                P m = mid(e.lake().site());
                double c = cost(m, px, py, pz) + e.lake().cost();
                if (best == null || c < bestCost) {
                    best = m;
                    bestCost = c;
                }
            }
            if (best == null) {
                return Move.GIVE_UP;
            }
            target = best;
            walkStart = tick;
            bestDist = dist(target, new P(px, py, pz));
            progressTick = tick;
        }
        double d = dist(target, new P(px, py, pz));
        if (d < bestDist - PROGRESS_BLOCKS) {
            bestDist = d;
            progressTick = tick;
        }
        // the cap is on the whole walk: always a bit closer is still a walk nobody waits 75 s for
        if (tick - progressTick > PROGRESS_TICKS || tick - walkStart > WALK_TICKS) {
            siteFailed(target);
            return Move.WAIT;
        }
        return Move.WALK;
    }
}
