package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// one lake looked at from where we stand, a few layouts a tick, with no minecraft in it so it can be tested. the verdict is
// about the terrain only: the bag is asked at choose time, a lake that is fine but not affordable right now is not a bad lake
final class LakeSurvey {
    // the lake around a seed, read live, because the scan only keeps a few cells of each chunk
    static final int NEAR_SEED = 6;
    // cells of lake taken per seed, a lake is a lot of layouts
    static final int LAKE_GROW = 40;
    // the cap is per lake, so a far lake gets the same share a near one does
    static final int SOURCES = 160;

    // what we know about the lake. site = the cheapest layout that survived, null = nothing here. unknown = no site, and part
    // of the box was not loaded, so that is not a no
    record Lake(P anchor, Set<P> cells, Layout site, int cost, int fills, boolean unknown) {
        boolean good() {
            return site != null;
        }
    }

    private final P anchor;
    private final Set<P> cells;
    private final List<Layout> layouts;
    private int at;
    private Layout best;
    private int bestCost;
    private int bestFills;
    private boolean skipped;

    private LakeSurvey(P anchor, Set<P> cells, List<Layout> layouts) {
        this.anchor = anchor;
        this.cells = cells;
        this.layouts = layouts;
    }

    // the grid at the seed's height plus the lake grown from it, the same cells the walk's arrival used to read
    static LakeSurvey of(Terrain t, P seed, int px, int pz) {
        Set<P> cells = new LinkedHashSet<>();
        for (int dx = -NEAR_SEED; dx <= NEAR_SEED; dx++) {
            for (int dz = -NEAR_SEED; dz <= NEAR_SEED; dz++) {
                cells.add(new P(seed.x() + dx, seed.y(), seed.z() + dz));
            }
        }
        grow(t, seed, cells);
        List<P> sources = new ArrayList<>();
        for (P p : cells) {
            if (t.at(p.x(), p.y(), p.z()) == Mat.LAVA && t.at(p.x(), p.y() + 1, p.z()) == Mat.AIR) {
                sources.add(p);
                if (sources.size() >= SOURCES) {
                    break;
                }
            }
        }
        return new LakeSurvey(seed, cells, LPortalPlan.candidates(t, sources, px, pz));
    }

    private static void grow(Terrain t, P seed, Set<P> found) {
        ArrayDeque<P> queue = new ArrayDeque<>();
        int taken = 0;
        queue.add(seed);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty() && taken < LAKE_GROW) {
            P at = queue.poll();
            taken++;
            for (int[] d : dirs) {
                P next = new P(at.x() + d[0], at.y(), at.z() + d[1]);
                if (!found.contains(next) && t.at(next.x(), next.y(), next.z()) == Mat.LAVA) {
                    found.add(next);
                    queue.add(next);
                }
            }
        }
    }

    // up to n layouts. unloaded reads as rock, so a half loaded box would look fine and be nothing of the sort: those are skipped
    // and the lake stays unknown instead of bad
    boolean step(int n, Terrain t, Predicate<Layout> loaded) {
        for (int i = 0; i < n && at < layouts.size(); i++) {
            Layout l = layouts.get(at++);
            if (!loaded.test(l)) {
                skipped = true;
                continue;
            }
            LPortalPlan.Survey s = LPortalPlan.survey(l, t);
            if (!s.ok()) {
                continue;
            }
            int cost = s.mines() + 2 * s.fills();
            if (best == null || cost < bestCost) {
                best = l;
                bestCost = cost;
                bestFills = s.fills();
            }
        }
        return at >= layouts.size();
    }

    Lake lake() {
        return new Lake(anchor, cells, best, bestCost, bestFills, best == null && skipped);
    }
}
