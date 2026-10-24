package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// the "where is the lava and how do we get there" half of the lava portal, with no minecraft in it so it can be tested.
// LPortalTask feeds it the scanned lava seeds and the finder's passes, it says walk, wait or look around
final class LavaHunt {
    // pass A only looks this far above and below us, a lake 50 blocks down is somebody else's problem until we ran out of
    // lakes up here
    static final int BAND = 24;
    static final int PER_CHUNK = 3;
    static final int SEED_MAX = 60;
    // arriving is a 3d check: a horizontal one would call a seed 60 blocks under the surface "arrived"
    static final int ARRIVE = 8;
    // a seed with no site found this close to it clears every seed in the same lake
    static final int SITE_RADIUS = 24;
    static final int LEGS_FOR_DEEP = 2;
    // nobody walks 75 s to a lake. it's not reachable
    static final int WALK_TICKS = 20 * 75;
    // except one in a pass B (deep) lake, that is a dig down staircase and those are slow
    static final int WALK_TICKS_DEEP = 20 * 180;
    // going up or down costs more than going sideways, so a lake at our level wins over one under our feet
    static final int DY_COST = 4;

    enum Move {WALK, WAIT, EXPLORE}

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

    static double cost(P seed, int px, int py, int pz) {
        return Math.hypot(seed.x() - px, seed.z() - pz) + DY_COST * Math.abs(seed.y() - py);
    }

    static boolean arrived(P seed, int px, int py, int pz) {
        long dx = seed.x() - px;
        long dy = seed.y() - py;
        long dz = seed.z() - pz;
        return dx * dx + dy * dy + dz * dz <= (long) ARRIVE * ARRIVE;
    }

    static List<P> byCost(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>(new HashSet<>(seeds));
        out.sort(Comparator.comparingDouble((P p) -> cost(p, px, py, pz)).thenComparingInt(P::x).thenComparingInt(P::y).thenComparingInt(P::z));
        return out;
    }

    private final Set<P> noSite = new HashSet<>();
    // the targets we gave up on. a later scan can bring other cells of the same lake (the nearest 60 move with us), so
    // the seeds are dropped by distance to these and not by exact cell
    private final List<P> rejected = new ArrayList<>();
    private P target;
    private int walkStart;
    private int walkLimit = WALK_TICKS;
    // the finder pass that has to start before it counts as a look at the target. -1 = not waiting on one
    private int needPass = -1;
    private int passNo;
    private int emptyLegs;
    private List<P> seen = List.of();

    P target() {
        return target;
    }

    Set<P> noSite() {
        return noSite;
    }

    int emptyLegs() {
        return emptyLegs;
    }

    // pass B (the deep one) is only worth its scan once the band had nothing and wandering didn't turn anything up either
    boolean deepAllowed() {
        return emptyLegs >= LEGS_FOR_DEEP;
    }

    // every seed we may still walk to, cheapest first
    List<P> usable(List<P> seeds, int px, int py, int pz) {
        List<P> out = new ArrayList<>();
        for (P p : byCost(seeds, px, py, pz)) {
            if (!noSite.contains(p) && !nearRejected(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // a wander leg ended. it only counts when pass A had finished and came back with nothing
    void legDone(boolean passAFinished, boolean nothingUsable) {
        if (passAFinished && nothingUsable) {
            emptyLegs++;
        }
    }

    // the finder started a pass
    void passStarted() {
        passNo++;
    }

    // the finder went through a whole pass and found no site anywhere. if one of those started after we got to the target,
    // the lake it sits on is checked and none of its neighbours need a visit either
    void passDone(int number) {
        if (target == null || needPass < 0 || number < needPass) {
            return;
        }
        markNoSite(target);
    }

    int pass() {
        return passNo;
    }

    // a site we picked turned out bad (lava showed up while clearing it, the clearing ran out of time). same as a pass
    // that found nothing there
    void siteFailed(P around) {
        markNoSite(around);
    }

    private void markNoSite(P around) {
        for (P p : seen) {
            if (dist(p, around) <= SITE_RADIUS) {
                noSite.add(p);
            }
        }
        noSite.add(around);
        rejected.add(around);
        target = null;
        needPass = -1;
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

    // what to do this tick. seeds = everything the scans know about, noSite is filtered in here
    Move decide(List<P> seeds, int px, int py, int pz, int tick) {
        seen = seeds;
        List<P> usable = usable(seeds, px, py, pz);
        if (usable.isEmpty()) {
            target = null;
            needPass = -1;
            return Move.EXPLORE;
        }
        if (target == null || noSite.contains(target)) {
            target = usable.get(0);
            walkStart = tick;
            walkLimit = Math.abs(target.y() - py) > BAND ? WALK_TICKS_DEEP : WALK_TICKS;
            needPass = -1;
        }
        if (arrived(target, px, py, pz)) {
            if (needPass < 0) {
                needPass = passNo + 1;
            }
            return Move.WAIT;
        }
        needPass = -1;
        if (tick - walkStart > walkLimit) {
            markNoSite(target);
            return Move.WAIT;
        }
        return Move.WALK;
    }
}
