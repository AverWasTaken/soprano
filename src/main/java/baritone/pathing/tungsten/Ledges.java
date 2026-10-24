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

package baritone.pathing.tungsten;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parkour that goes by way of places Baritone's grid has no node for: the strip of a block outside a wall on it, the top of a
 * wall post, the rim round a walled platform. A CubeCraft favourite: hop up onto the rim, walk round it, run along it and
 * jump off the end.
 *
 * <p>Every floor height round src and the landing gets a {@link RimWalk.Grid} (where the box fits and has floor under it), and
 * each grid's connected patches are "ledges": anywhere on one can be walked to from anywhere else on it. Between ledges, and
 * from a ledge to the landing, jumps are tried in the sim: from a standstill, a short run along a clear lane, the jump, steering
 * at a point on the other ledge, and a stop there. The cheapest chain of walks and jumps from src to the landing is then played
 * out in full and flown with the aim wobbling, like any searched plan.
 */
public final class Ledges {

    // Math.hypot is careful about overflow and slow for it (a quarter of the background searches' time went there): these
    // are distances of a few blocks
    static double hyp(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    private Ledges() {}

    static final double SPREAD = 2.0;
    static final double MARGIN = 0.06, FOOTING = 0.08;
    // how far a jump can go between ledges, and how high up
    static final double MAX_JUMP = 4.6, MAX_UP = 1.25, MAX_DOWN = 4.0;
    static final double[] RUN_UPS = {1.4, 0.7, 0.0}, WALK_RUN_UPS = {0.3, 0.0};
    // landing spots tried on each ledge, and ledges at most
    static final int TARGETS = 20, MAX_LEDGES = 400;
    static final int FLIGHTS = 8;
    static final boolean DEBUG = System.getenv("SOPRANO_LEDGE_DEBUG") != null;
    private static String why = "";

    // one floor height's grid, and which ledge each free cell belongs to
    private static final class Floor {
        final double h;
        final RimWalk.Area area;
        final RimWalk.Grid grid;
        final int[] ledge;

        Floor(double h, RimWalk.Area area, RimWalk.Grid grid, int[] ledge) {
            this.h = h;
            this.area = area;
            this.grid = grid;
            this.ledge = ledge;
        }
    }

    private record Ledge(int id, Floor floor, int[] cells) {}

    // everything worked out about the place, for the jumps to say where they ended up
    private record Place(List<Floor> floors, List<Ledge> ledges) {
        // the floor at height h, or null
        Floor floorAt(double h) {
            for (Floor f : floors) {
                if (Math.abs(f.h - h) < 1.0E-4) {
                    return f;
                }
            }
            return null;
        }

        // somewhere free to run at x z, on a floor within a step of h (a run up can go up and down the halves of a stair)
        boolean runnable(double h, double x, double z) {
            for (Floor f : floors) {
                if (Math.abs(f.h - h) <= 0.6) {
                    int c = f.grid.at(x, z);
                    if (c >= 0 && f.grid.ok[c]) {
                        return true;
                    }
                }
            }
            return false;
        }

        // floor under the box at x z, at y or up to a step below it
        boolean footedNear(double y, double x, double z) {
            for (Floor f : floors) {
                if (f.h <= y + 1.0E-4 && f.h >= y - 0.6 && RimWalk.footed(f.area.floor, x, z, RimWalk.HALF - 0.02)) {
                    return true;
                }
            }
            return false;
        }

        double lowest() {
            double low = Double.MAX_VALUE;
            for (Floor f : floors) {
                low = Math.min(low, f.h);
            }
            return low;
        }

        // the ledge the player stands on at x y z, or null
        Ledge at(double x, double y, double z) {
            for (Floor f : floors) {
                if (Math.abs(f.h - y) < 1.0E-4) {
                    // the nearest free cell within a few hundredths: the stop can leave it a touch closer to an edge or a
                    // wall than the grid's margins allow
                    Ledge best = null;
                    double bd = Double.MAX_VALUE;
                    for (double ox = -0.15; ox <= 0.151; ox += RimWalk.RES) {
                        for (double oz = -0.15; oz <= 0.151; oz += RimWalk.RES) {
                            int c = f.grid.at(x + ox, z + oz);
                            double d = ox * ox + oz * oz;
                            if (c >= 0 && f.ledge[c] >= 0 && d < bd) {
                                bd = d;
                                best = ledges.get(f.ledge[c]);
                            }
                        }
                    }
                    if (best != null) {
                        return best;
                    }
                }
            }
            return null;
        }
    }

    // a jump worked out from a standstill at (rx, rz): the run along u, the jump at p, steering at t, and where it stops
    private record Edge(Ledge from, Ledge to, double rx, double rz, double px, double pz, double tx, double tz, double vx, double vz, int viaTicks, boolean sprint, int ticks,
                        double ex, double ez) {}

    private static final class Run {
        final List<PlayerSim.Controls> keys = new ArrayList<>();
        final List<SimState> states = new ArrayList<>();

        void step(PlayerSim sim, PlayerSim.Controls c) {
            keys.add(c);
            states.add(SimState.of(sim));
        }
    }

    // how far round src a place reaches (every landing a ledge chain from src is asked about is within it)
    public static final double REACH = 9.0;

    // the ledges round a src, worked out once and kept for every landing asked about from it: the jumps between ledges
    // don't depend on the landing, so they're kept too (known)
    private record Built(long hash, Place place, java.util.Map<Long, List<Edge>> known) {}

    private static final java.util.Map<String, Built> BUILT = new java.util.LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Built> e) {
            return size() > 8; // a few MB each (a grid per floor height), the ones round the player are all that get asked again
        }
    };

    private static Built built(CollisionProvider world, BetterBlockPos src, double floorY) {
        double ax = src.x + 0.5, az = src.z + 0.5;
        double x0 = ax - REACH, z0 = az - REACH, x1 = ax + REACH, z1 = az + REACH;
        double lo = floorY - MAX_DOWN, hi = floorY + MAX_UP + 1.25;
        // every box round here, once
        BoxList all = new BoxList(), cell = new BoxList();
        Set<Long> tops = new HashSet<>();
        long hash = 1469598103934665603L;
        for (int x = Mth.floor(x0 - 1); x <= Mth.floor(x1 + 1); x++) {
            for (int z = Mth.floor(z0 - 1); z <= Mth.floor(z1 + 1); z++) {
                for (int y = Mth.floor(lo) - 1; y <= Mth.floor(hi + 2); y++) {
                    cell.clear();
                    world.boxes(x, y, z, cell);
                    for (int b = 0; b < cell.size(); b++) {
                        all.add(cell.get(b, 0), cell.get(b, 1), cell.get(b, 2), cell.get(b, 3), cell.get(b, 4), cell.get(b, 5));
                        for (int c = 0; c < 6; c++) {
                            hash = (hash ^ Double.doubleToLongBits(cell.get(b, c))) * 1099511628211L;
                        }
                        double top = cell.get(b, 4);
                        if (top >= lo - 1.0E-6 && top <= hi + 1.0E-6) {
                            tops.add(Math.round(top * 10000));
                        }
                    }
                    hash = (hash ^ (Float.floatToIntBits(world.friction(x, y, z)) * 31L + Float.floatToIntBits(world.bounce(x, y, z)))) * 1099511628211L;
                }
            }
        }
        String key = src.x + "," + src.y + "," + src.z + "@" + floorY;
        synchronized (BUILT) {
            Built b = BUILT.get(key);
            if (b != null && b.hash() == hash) {
                return b;
            }
        }
        // the floors and their ledges
        List<Ledge> ledges = new ArrayList<>();
        List<Floor> floors = new ArrayList<>();
        int[] queue = new int[0];
        for (long t : tops) {
            double h = t / 10000.0;
            RimWalk.Area area = new RimWalk.Area(all, x0, z0, x1, z1, h);
            if (area.floor.size() == 0) {
                continue;
            }
            RimWalk.Grid grid = new RimWalk.Grid(area, MARGIN, FOOTING);
            int[] label = new int[grid.ok.length];
            Arrays.fill(label, -1);
            Floor floor = new Floor(h, area, grid, label);
            floors.add(floor);
            for (int c = 0; c < grid.ok.length; c++) {
                if (!grid.ok[c] || label[c] >= 0) {
                    continue;
                }
                // breadth first over the free spots joined to this one: the queue is the ledge's cells, in the order found
                int[] q = queue.length < grid.ok.length ? (queue = new int[grid.ok.length]) : queue;
                int head = 0, tail = 0;
                int id = ledges.size();
                label[c] = id;
                q[tail++] = c;
                while (head < tail) {
                    int at = q[head++];
                    int i = at / grid.nz, k = at % grid.nz;
                    for (int di = -1; di <= 1; di++) {
                        for (int dk = -1; dk <= 1; dk++) {
                            int ii = i + di, kk = k + dk;
                            if (ii < 0 || kk < 0 || ii >= grid.nx || kk >= grid.nz) {
                                continue;
                            }
                            int m = ii * grid.nz + kk;
                            if (grid.ok[m] && label[m] < 0) {
                                label[m] = id;
                                q[tail++] = m;
                            }
                        }
                    }
                }
                ledges.add(new Ledge(id, floor, Arrays.copyOf(q, tail)));
            }
        }
        Built b = new Built(hash, new Place(floors, ledges), new java.util.concurrent.ConcurrentHashMap<>());
        synchronized (BUILT) {
            BUILT.put(key, b);
        }
        return b;
    }

    public static TungstenSearch.Plan plan(CollisionProvider world, BetterBlockPos src, double floorY, Landing land) {
        if (land.grab() || Math.abs(land.centerX() - src.x - 0.5) > REACH - SPREAD || Math.abs(land.centerZ() - src.z - 0.5) > REACH - SPREAD) {
            return null;
        }
        double[] stand = TungstenJumps.standAt(world, src.x, src.y, src.z);
        double ax = src.x + stand[0], az = src.z + stand[1];
        double landFloor = landFloor(world, land);
        if (Double.isNaN(landFloor)) {
            return null;
        }
        Built built = built(world, src, floorY);
        Place place = built.place();
        List<Floor> floors = place.floors();
        List<Ledge> ledges = place.ledges();
        if (ledges.size() > MAX_LEDGES) {
            return null;
        }
        Ledge start = place.at(ax, floorY, az);
        if (start == null) {
            return null;
        }
        Ledge landLedge = place.at(land.centerX(), landFloor, land.centerZ());
        if (DEBUG) {
            for (Ledge l : ledges) {
                if (l.cells().length < 4) {
                    continue;
                }
                RimWalk.Grid g = l.floor().grid;
                double mnx = 1e9, mxx = -1e9, mnz = 1e9, mxz = -1e9;
                for (int c : l.cells()) {
                    mnx = Math.min(mnx, g.x(c));
                    mxx = Math.max(mxx, g.x(c));
                    mnz = Math.min(mnz, g.z(c));
                    mxz = Math.max(mxz, g.z(c));
                }
                System.out.printf("[ledges]   ledge %d%s at %.3f: %d cells, x %.2f..%.2f z %.2f..%.2f%n", l.id(), l == start ? " (start)" : "",
                        l.floor().h, l.cells().length, mnx, mxx, mnz, mxz);
            }
        }
        // a jump at a time: the cheapest chain of walks and jumps from where we are (Dijkstra over the ledges), its first jump
        // played out, and again from wherever that one really stopped (onto the low step of a stair it may run on up it)
        Set<Edge> refused = new HashSet<>();
        java.util.Map<Long, List<Edge>> known = built.known();
        // the landing's own edges (onto it from each ledge) aren't for keeping with the place
        java.util.Map<Long, List<Edge>> toLanding = new java.util.HashMap<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            long before = PlayerSim.ticks();
            PlayerSim sim = NeoSearches.standing(world, src, floorY);
            Run run = new Run();
            run.states.add(SimState.of(sim));
            Ledge at = start;
            List<Edge> played = new ArrayList<>();
            boolean arrived = false;
            for (int hop = 0; hop < 10 && !arrived; hop++) {
                List<Edge> chain = route(world, place, ledges, at, new double[]{sim.x, sim.z}, land, landFloor, landLedge, refused, known, toLanding);
                if (chain == null) {
                    break;
                }
                Edge e = chain.get(0);
                SimState saved = SimState.of(sim);
                int keys = run.keys.size();
                Ledge[] ended = new Ledge[1];
                if (!playEdge(world, place, sim, e, land, landFloor, run, ended)) {
                    refused.add(e);
                    saved.write(sim);
                    run.keys.subList(keys, run.keys.size()).clear();
                    run.states.subList(keys + 1, run.states.size()).clear();
                    continue;
                }
                played.add(e);
                if (e.to() == null) {
                    arrived = true;
                } else {
                    at = ended[0];
                }
            }
            if (!arrived) {
                if (DEBUG) {
                    System.out.println("[ledges] " + src + " -> " + land + ": no chain (" + ledges.size() + " ledges on " + floors.size() + " floors)");
                }
                return null;
            }
            TungstenSearch.Plan plan = new TungstenSearch.Plan(run.keys, run.states, 0, PlayerSim.ticks() - before);
            boolean flies = NeoSearches.fliesEveryTime(world, src, floorY, land, plan, FLIGHTS);
            if (DEBUG) {
                System.out.println("[ledges] " + src + " -> " + land + ": " + played.size() + " jumps, " + plan.ticks() + " ticks, flights " + flies);
            }
            if (flies) {
                return plan;
            }
            refused.addAll(played);
        }
        return null;
    }

    // Dijkstra over ledges from ledge `from`, standing at pose: each ledge entered at a spot (where the jump onto it stopped),
    // the walk to the next run up counted from there. the chain of edges to the landing, or null
    private static List<Edge> route(CollisionProvider world, Place place, List<Ledge> ledges, Ledge from, double[] start, Landing land,
                                    double landFloor, Ledge landLedge, Set<Edge> refused, java.util.Map<Long, List<Edge>> known,
                                    java.util.Map<Long, List<Edge>> toLanding) {
        int n = ledges.size();
        double[] cost = new double[n];
        double[][] pose = new double[n][];
        Edge[] via = new Edge[n];
        boolean[] done = new boolean[n];
        Arrays.fill(cost, Double.MAX_VALUE);
        cost[from.id()] = 0;
        pose[from.id()] = start;
        double best = Double.MAX_VALUE;
        Ledge bestLast = null;
        Edge bestFinal = null;
        while (true) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && cost[i] < Double.MAX_VALUE && (u < 0 || cost[i] < cost[u])) {
                    u = i;
                }
            }
            if (u < 0 || cost[u] >= best) {
                break;
            }
            done[u] = true;
            Ledge a = ledges.get(u);
            if (DEBUG) {
                System.out.printf("[ledges]   from ledge %d (cost %.1f):%n", u, cost[u]);
            }
            // the landing on this ledge: walk to it
            if (landLedge == a) {
                double c = cost[u] + walkCost(a, pose[u], land.centerX(), land.centerZ());
                Edge walk = new Edge(a, null, land.centerX(), land.centerZ(), Double.NaN, Double.NaN, land.centerX(), land.centerZ(), Double.NaN, Double.NaN, 0, false, 0,
                        land.centerX(), land.centerZ());
                if (c < best && !refused.contains(walk)) {
                    best = c;
                    bestLast = a;
                    bestFinal = walk;
                }
            }
            // onto the landing from this ledge
            for (Edge e : toLanding.computeIfAbsent((long) a.id(), k -> edges(world, place, a, null, land, landFloor))) {
                if (refused.contains(e)) {
                    continue;
                }
                double c = cost[u] + walkCost(a, pose[u], e.rx(), e.rz()) + e.ticks();
                if (c < best) {
                    best = c;
                    bestLast = a;
                    bestFinal = e;
                }
            }
            // or on to another ledge: aimed at spots on each one near enough, and straight shots off this one's edges
            List<Edge> onwards = new ArrayList<>(shotsFrom(world, place, a, land, landFloor, known));
            for (Ledge b : ledges) {
                if (b != a && near(a, b)) {
                    onwards.addAll(known.computeIfAbsent(a.id() * 1000L + b.id(), k -> edges(world, place, a, b, land, landFloor)));
                }
            }
            for (Edge e : onwards) {
                if (refused.contains(e)) {
                    continue;
                }
                double c = cost[u] + walkCost(a, pose[u], e.rx(), e.rz()) + e.ticks();
                int to = e.to().id();
                if (!done[to] && c < cost[to]) {
                    cost[to] = c;
                    pose[to] = new double[]{e.ex(), e.ez()};
                    via[to] = e;
                }
            }
        }
        if (bestFinal == null) {
            return null;
        }
        List<Edge> chain = new ArrayList<>();
        chain.add(bestFinal);
        for (Ledge l = bestLast; via[l.id()] != null && l != from; l = via[l.id()].from()) {
            chain.add(0, via[l.id()]);
        }
        return chain;
    }

    // the floor height in the landing's cell (the top of whatever's under the middle of it)
    private static double landFloor(CollisionProvider world, Landing land) {
        BoxList boxes = BoxList.temp();
        // (two cells down too: a fence or a wall stands half a block into the cell over it, with a pot or a head on it)
        world.boxes(land.x(), land.y() - 2, land.z(), boxes);
        world.boxes(land.x(), land.y() - 1, land.z(), boxes);
        world.boxes(land.x(), land.y(), land.z(), boxes);
        double top = Double.NaN;
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i, 0) <= land.centerX() && boxes.get(i, 3) >= land.centerX() && boxes.get(i, 2) <= land.centerZ() && boxes.get(i, 5) >= land.centerZ()) {
                double t = boxes.get(i, 4);
                if (t <= land.y() + 0.5 && (Double.isNaN(top) || t > top)) {
                    top = t;
                }
            }
        }
        return top;
    }

    private static boolean near(Ledge a, Ledge b) {
        double dy = b.floor().h - a.floor().h;
        if (dy > MAX_UP || dy < -MAX_DOWN) {
            return false;
        }
        // any two cells within a jump (checked on a coarse sample)
        RimWalk.Grid ga = a.floor().grid, gb = b.floor().grid;
        int stepA = Math.max(1, a.cells().length / 60), stepB = Math.max(1, b.cells().length / 60);
        for (int i = 0; i < a.cells().length; i += stepA) {
            int ca = a.cells()[i];
            for (int j = 0; j < b.cells().length; j += stepB) {
                int cb = b.cells()[j];
                if (hyp(ga.x(ca) - gb.x(cb), ga.z(ca) - gb.z(cb)) <= MAX_JUMP) {
                    return true;
                }
            }
        }
        return false;
    }

    // a rough walk time on the ledge from one spot to another: a straight line at a walk
    private static double walkCost(Ledge a, double[] from, double x, double z) {
        return hyp(x - from[0], z - from[1]) / 0.2;
    }

    // the jumps from ledge a to ledge b (or to the landing, b == null), worked out from standing at a run up spot
    private static List<Edge> edges(CollisionProvider world, Place place, Ledge a, Ledge b, Landing land, double landFloor) {
        List<Edge> out = new ArrayList<>();
        RimWalk.Grid ga = a.floor().grid;
        // where to come down: the landing's middle, or the best few spots on b
        List<double[]> targets = new ArrayList<>();
        if (b == null) {
            double dy = landFloor - a.floor().h;
            if (dy > MAX_UP || dy < -MAX_DOWN) {
                return out;
            }
            targets.add(new double[]{land.centerX(), land.centerZ()});
        } else {
            RimWalk.Grid gb = b.floor().grid;
            // scored by how near the landing they are and how short a jump from a, a few spread out
            int step = Math.max(1, b.cells().length / 400);
            List<double[]> scored = new ArrayList<>();
            for (int i = 0; i < b.cells().length; i += step) {
                int c = b.cells()[i];
                if (gb.room[c] < 1) {
                    continue;
                }
                double x = gb.x(c), z = gb.z(c), d = nearest(a, x, z);
                if (d > MAX_JUMP) {
                    continue;
                }
                scored.add(new double[]{x, z, d - 0.05 * Math.min(gb.room[c], 4)});
            }
            scored.sort((p, q) -> Double.compare(p[2], q[2]));
            for (double[] sc : scored) {
                boolean close = false;
                // onto a ledge right next to this one, a little up or down (a stair's low step, a slab, off the end of a block onto
        // one below): walked onto, no jump
        if (b != null && b.floor().h - a.floor().h <= 0.6 && b.floor().h - a.floor().h >= -1.6) {
            for (double[] t : targets) {
                int p = -1;
                double pd = Double.MAX_VALUE;
                for (int c : a.cells()) {
                    double d = hyp(ga.x(c) - t[0], ga.z(c) - t[1]);
                    if (d < pd) {
                        pd = d;
                        p = c;
                    }
                }
                if (p < 0 || pd > 1.2) {
                    continue;
                }
                PlayerSim sim = rest(world, ga.x(p), a.floor().h, ga.z(p));
                Run run = new Run();
                Ledge[] ended = new Ledge[1];
                if (jump(sim, place, a, ga.x(p), ga.z(p), 1, 0, t[0], t[1], Double.NaN, Double.NaN, -1, b, land, landFloor, false, run, ended)) {
                    out.add(new Edge(a, ended[0], ga.x(p), ga.z(p), ga.x(p), ga.z(p), t[0], t[1], Double.NaN, Double.NaN, -1, false, run.keys.size(), sim.x, sim.z));
                    if (DEBUG) {
                        System.out.printf("[ledges]       step %.2f %.2f -> %.2f %.2f onto ledge %d%n", ga.x(p), ga.z(p), t[0], t[1], ended[0].id());
                    }
                    return out;
                }
            }
        }
        for (double[] t : targets) {
                    if (hyp(t[0] - sc[0], t[1] - sc[1]) < 0.4) {
                        close = true;
                        break;
                    }
                }
                if (!close) {
                    targets.add(new double[]{sc[0], sc[1]});
                }
                if (targets.size() >= TARGETS) {
                    break;
                }
            }
        }
        for (double[] t : targets) {
            // the takeoff: the spot on a nearest the target with a bit of room, and one a bit further back (a hop up onto
            // a ledge right above needs to leave from a little way off, not from underneath)
            List<Integer> takeoffs = new ArrayList<>();
            List<int[]> byDistance = new ArrayList<>();
            for (int c : a.cells()) {
                if (ga.room[c] < 1) {
                    continue;
                }
                double d = hyp(ga.x(c) - t[0], ga.z(c) - t[1]);
                if (d >= 0.45 && d <= MAX_JUMP) {
                    byDistance.add(new int[]{c, (int) Math.round(d * 1000)});
                }
            }
            byDistance.sort((p, q) -> Integer.compare(p[1], q[1]));
            // the nearest, the nearest a block or more back (a hop up onto a ledge right above wants a little room), and the
            // nearest couple with a clear run up behind them, straight at the target: a long jump off a narrow strip goes off
            // the end of the strip, run along its length
            int withRun = 0;
            double nearestD = byDistance.isEmpty() ? 0 : byDistance.get(0)[1] / 1000.0;
            // and from a bit further back each time: off a narrow top round a post, the jump goes from well behind it
            double[] backs = {nearestD + 0.35, nearestD + 0.7, Math.max(1.0, nearestD + 1.0)};
            boolean[] backTaken = new boolean[backs.length];
            for (int[] cd : byDistance) {
                int c = cd[0];
                double d = cd[1] / 1000.0;
                boolean take = takeoffs.isEmpty();
                for (int i = 0; i < backs.length; i++) {
                    if (!backTaken[i] && d >= backs[i]) {
                        backTaken[i] = true;
                        take = true;
                    }
                }
                if (withRun < 2) {
                    double ux = (t[0] - ga.x(c)) / d, uz = (t[1] - ga.z(c)) / d;
                    if (lane(ga, ga.x(c) - ux * 1.4, ga.z(c) - uz * 1.4, ga.x(c), ga.z(c))) {
                        withRun++;
                        take = true;
                    }
                }
                if (take && !takeoffs.contains(c)) {
                    takeoffs.add(c);
                }
                if (takeoffs.size() >= 6 || withRun >= 2 && backTaken[backs.length - 1]) {
                    break;
                }
            }
            // and the takeoffs furthest to either side of the line (a post right in front of the middle of a narrow top:
            // round it from the side of the top on the same side)
            if (!byDistance.isEmpty()) {
                int c0 = byDistance.get(0)[0];
                double lx = t[0] - ga.x(c0), lz = t[1] - ga.z(c0), ll = hyp(lx, lz);
                if (ll > 1.0E-6) {
                    double nx = -lz / ll, nz = lx / ll;
                    for (int w = 0; w < 4; w++) {
                        int sgn = w % 2 == 0 ? 1 : -1;
                        // near the front, and further back
                        double from = w < 2 ? 0 : 0.4, to = w < 2 ? 0.4 : 1.0;
                        int best = -1;
                        double bestSide = -Double.MAX_VALUE;
                        for (int[] cd : byDistance) {
                            double dd = cd[1] / 1000.0 - nearestD;
                            if (dd > to) {
                                break;
                            }
                            if (dd < from) {
                                continue;
                            }
                            double side = sgn * ((ga.x(cd[0]) - ga.x(c0)) * nx + (ga.z(cd[0]) - ga.z(c0)) * nz);
                            if (side > bestSide) {
                                bestSide = side;
                                best = cd[0];
                            }
                        }
                        if (best >= 0 && !takeoffs.contains(best)) {
                            takeoffs.add(best);
                        }
                    }
                }
            }
            boolean found = false;
            if (DEBUG) {
                StringBuilder sb = new StringBuilder(String.format("[ledges]       target %.2f %.2f takeoffs:", t[0], t[1]));
                for (int p : takeoffs) {
                    sb.append(String.format(" %.2f,%.2f", ga.x(p), ga.z(p)));
                }
                System.out.println(sb);
            }
            for (int p : takeoffs) {
                double px = ga.x(p), pz = ga.z(p), pd = hyp(t[0] - px, t[1] - pz);
                // straight at it, or (something in the way) by way of a point off to one side halfway: round a post
                List<double[]> vias = new ArrayList<>();
                vias.add(new double[]{Double.NaN, Double.NaN, 0});
                if (JumpPolicy.blocked(world, px, a.floor().h, pz, t[0], b == null ? landFloor : b.floor().h, t[1])) {
                    double nx = -(t[1] - pz) / pd, nz = (t[0] - px) / pd, mx = (px + t[0]) / 2, mz = (pz + t[1]) / 2;
                    for (double off : new double[]{0.3, -0.3, 0.45, -0.45, 0.7, -0.7, 1.0, -1.0}) {
                        for (int vt : new int[]{0, 3, 5}) {
                            vias.add(new double[]{mx + nx * off, mz + nz * off, vt});
                        }
                    }
                }
                for (double[] v : vias) {
                    double ax = Double.isNaN(v[0]) ? t[0] : v[0], az = Double.isNaN(v[0]) ? t[1] : v[1], al = hyp(ax - px, az - pz);
                    double ux = (ax - px) / al, uz = (az - pz) / al;
                    // a short hop at a walk (bumping into things on the way is fine without sprint, the game does that the
                    // same whichever way the camera looks), a long one at a sprint
                    boolean[] styles = pd < 2.0 ? new boolean[]{false, true} : new boolean[]{true};
                    for (boolean sprint : styles) {
                        for (int k = 0; k < 2 * (sprint ? RUN_UPS : WALK_RUN_UPS).length; k++) {
                            double r = (sprint ? RUN_UPS : WALK_RUN_UPS)[k / 2];
                            // jump at the spot, or run on to the very edge of the floor first (off a narrow top, the last
                            // few hundredths of a block of footing are where the jump goes from)
                            boolean late = k % 2 == 1;
                            double jx = late ? px + ux * 3 : px, jz = late ? pz + uz * 3 : pz;
                            double rx = px - ux * r, rz = pz - uz * r;
                            if (!lane(place, a.floor().h, rx, rz, px, pz)) {
                                continue;
                            }
                            // (the run up starts on this ledge, where the walk there can take us; it may cross a stair's halves after)
                        double fh = nearest(a, rx, rz) <= 0.4 ? runFloor(place, a.floor().h, rx, rz) : Double.NaN;
                            if (Double.isNaN(fh)) {
                                continue;
                            }
                            PlayerSim sim = rest(world, rx, fh, rz);
                            Run run = new Run();
                            Ledge[] ended = new Ledge[1];
                            if (!jump(sim, place, a, jx, jz, ux, uz, t[0], t[1], v[0], v[1], (int) v[2], b, land, landFloor, sprint, run, ended)) {
                                if (DEBUG) {
                                    System.out.printf("[ledges]       %.2f %.2f -> %.2f %.2f%s (%s, run %.1f): %s%n", px, pz, t[0], t[1],
                                            Double.isNaN(v[0]) ? "" : String.format(" via %.2f %.2f", v[0], v[1]), sprint ? "sprint" : "walk", r, why);
                                }
                                continue;
                            }
                            // and from a little either side of the run up spot along the lane (the walk there never stops
                            // exactly on it): a jump that only works from the one spot isn't one to take
                            boolean holds = true;
                            for (double off : new double[]{-0.08, 0.08}) {
                                double qx = rx - ux * off, qz = rz - uz * off;
                                if (!lane(place, a.floor().h, qx, qz, px, pz) && off > 0) {
                                    continue;
                                }
                                double qh = runFloor(place, a.floor().h, qx, qz);
                                if (Double.isNaN(qh)) {
                                    holds = false;
                                    break;
                                }
                                if (!jump(rest(world, qx, qh, qz), place, a, jx, jz, ux, uz, t[0], t[1], v[0], v[1], (int) v[2], b, land, landFloor,
                                        sprint, new Run(), new Ledge[1])) {
                                    holds = false;
                                    break;
                                }
                            }
                            if (!holds) {
                                if (DEBUG) {
                                    System.out.printf("[ledges]       %.2f %.2f -> %.2f %.2f (%s, run %.1f): only from the one spot%n", px, pz, t[0], t[1], sprint ? "sprint" : "walk", r);
                                }
                                continue;
                            }
                            out.add(new Edge(a, ended[0], rx, rz, jx, jz, t[0], t[1], v[0], v[1], (int) v[2], sprint, run.keys.size(), sim.x, sim.z));
                            found = true;
                            break;
                        }
                        if (found) {
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        return out;
    }

    static final int SHOT_YAWS = 32;

    // straight jumps off the edges of a: from spots near its rim, every SHOT_YAWS directions with a clear run up behind,
    // sprint, jump at the spot, keep going straight, and see where it comes down
    private static List<Edge> shotsFrom(CollisionProvider world, Place place, Ledge a, Landing land, double landFloor, java.util.Map<Long, List<Edge>> known) {
        return known.computeIfAbsent(a.id() * 1000L + 998, k -> {
            List<Edge> out = new ArrayList<>();
            RimWalk.Grid ga = a.floor().grid;
            // spots near the edge, a quarter of a block apart
            List<Integer> spots = new ArrayList<>();
            for (int c : a.cells()) {
                if (ga.room[c] > 3) {
                    continue;
                }
                boolean close = false;
                for (int o : spots) {
                    if (hyp(ga.x(o) - ga.x(c), ga.z(o) - ga.z(c)) < 0.15) {
                        close = true;
                        break;
                    }
                }
                if (!close) {
                    spots.add(c);
                }
                if (spots.size() > 200) {
                    break;
                }
            }
            Set<Integer> reached = new HashSet<>();
            if (DEBUG && System.getenv("SOPRANO_LEDGE_SHOTS") != null) {
                StringBuilder sb = new StringBuilder("[ledges]     shot spots on " + a.id() + ":");
                for (int p : spots) {
                    sb.append(String.format(" %.2f,%.2f", ga.x(p), ga.z(p)));
                }
                System.out.println(sb);
            }
            for (int p : spots) {
                double px = ga.x(p), pz = ga.z(p);
                for (int y = 0; y < SHOT_YAWS; y++) {
                    double ang = 2 * Math.PI * y / SHOT_YAWS, ux = Math.cos(ang), uz = Math.sin(ang);
                    // only off the edge: the floor runs out a little way along u
                    int ahead = ga.at(px + ux * 0.4, pz + uz * 0.4);
                    if (ahead >= 0 && a.floor().ledge[ahead] == a.id()) {
                        continue;
                    }
                    for (double r : new double[]{1.4, 0.7}) {
                        double rx = px - ux * r, rz = pz - uz * r;
                        if (!lane(place, a.floor().h, rx, rz, px, pz)) {
                            continue;
                        }
                        double tx = px + ux * 8, tz = pz + uz * 8;
                        // the jump goes where the floor runs out, not at the spot: as far along as it'll go
                        double jx = px + ux * 3, jz = pz + uz * 3;
                        // (the run up starts on this ledge, where the walk there can take us; it may cross a stair's halves after)
                        double fh = nearest(a, rx, rz) <= 0.4 ? runFloor(place, a.floor().h, rx, rz) : Double.NaN;
                        if (Double.isNaN(fh)) {
                            continue;
                        }
                        PlayerSim sim = rest(world, rx, fh, rz);
                        Run run = new Run();
                        Ledge[] ended = new Ledge[1];
                        if (!jump(sim, place, a, jx, jz, ux, uz, tx, tz, a, land, landFloor, true, run, ended)) {
                            if (DEBUG && System.getenv("SOPRANO_LEDGE_SHOTS") != null) {
                                System.out.printf("[ledges]       shot %.2f %.2f yaw %d run %.1f: %s%n", px, pz, y * 360 / SHOT_YAWS, r, why);
                            }
                            continue;
                        }
                        boolean holds = true;
                        for (double off : new double[]{-0.08, 0.08}) {
                            double qx = rx - ux * off, qz = rz - uz * off;
                            if (!lane(place, a.floor().h, qx, qz, px, pz) && off > 0) {
                                continue;
                            }
                            double qh = runFloor(place, a.floor().h, qx, qz);
                            if (Double.isNaN(qh)) {
                                holds = false;
                                break;
                            }
                            Ledge[] other = new Ledge[1];
                            if (!jump(rest(world, qx, qh, qz), place, a, jx, jz, ux, uz, tx, tz, a, land, landFloor, true, new Run(), other)) {
                                holds = false;
                                break;
                            }
                        }
                        if (holds) {
                            reached.add(ended[0].id());
                            out.add(new Edge(a, ended[0], rx, rz, jx, jz, tx, tz, Double.NaN, Double.NaN, 0, true, run.keys.size(), sim.x, sim.z));
                            if (DEBUG) {
                                System.out.printf("[ledges]     shot to ledge %d: run up %.2f %.2f, jump %.2f %.2f, %d ticks, stops %.2f %.2f%n",
                                        ended[0].id(), rx, rz, px, pz, run.keys.size(), sim.x, sim.z);
                            }
                            break;
                        }
                    }
                }
            }
            return out;
        });
    }

    private static double nearest(Ledge a, double x, double z) {
        RimWalk.Grid g = a.floor().grid;
        double best = Double.MAX_VALUE;
        int step = Math.max(1, a.cells().length / 200);
        for (int i = 0; i < a.cells().length; i += step) {
            int c = a.cells()[i];
            best = Math.min(best, hyp(g.x(c) - x, g.z(c) - z));
        }
        return best;
    }

    // every point from (rx, rz) to (px, pz) free on some floor within a step of h
    private static boolean lane(Place place, double h, double rx, double rz, double px, double pz) {
        double len = hyp(px - rx, pz - rz);
        int steps = Math.max(1, (int) Math.ceil(len / RimWalk.RES));
        // (where a stair's halves meet, neither half's grid has the few hundredths of the step itself: a short gap is fine)
        int gap = 0;
        for (int i = 0; i <= steps; i++) {
            boolean ok = place.runnable(h, rx + (px - rx) * i / steps, rz + (pz - rz) * i / steps);
            if ((i == 0 || i == steps) && !ok) {
                return false;
            }
            gap = ok ? 0 : gap + 1;
            if (gap * RimWalk.RES > 0.3) {
                return false;
            }
        }
        return true;
    }

    // the floor a run up starting at x z stands on: the highest free one within a step of h
    private static double runFloor(Place place, double h, double x, double z) {
        double best = Double.NaN;
        for (Floor f : place.floors()) {
            if (Math.abs(f.h - h) <= 0.6) {
                int c = f.grid.at(x, z);
                if (c >= 0 && f.grid.ok[c] && (Double.isNaN(best) || f.h > best)) {
                    best = f.h;
                }
            }
        }
        return best;
    }

    // every point from (rx, rz) to (px, pz) free on the grid
    private static boolean lane(RimWalk.Grid g, double rx, double rz, double px, double pz) {
        double len = hyp(px - rx, pz - rz);
        int steps = Math.max(1, (int) Math.ceil(len / RimWalk.RES));
        for (int i = 0; i <= steps; i++) {
            int c = g.at(rx + (px - rx) * i / steps, rz + (pz - rz) * i / steps);
            if (c < 0 || !g.ok[c]) {
                return false;
            }
        }
        return true;
    }

    static PlayerSim rest(CollisionProvider world, double x, double y, double z) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = x;
        sim.y = y;
        sim.z = z;
        sim.vy = -0.0784;
        sim.onGround = true;
        return sim;
    }

    // from a standstill: sprint along u, jump at p (or where the floor runs out first), steer at t in the air, and come down on
    // b (stopped there after) or on the landing (settled there after). sim ends where it stopped
    private static boolean jump(PlayerSim sim, Place place, Ledge a, double px, double pz, double ux, double uz, double tx, double tz, Ledge b,
                                Landing land, double landFloor, boolean sprint, Run run, Ledge[] ended) {
        return jump(sim, place, a, px, pz, ux, uz, tx, tz, Double.NaN, Double.NaN, 0, b, land, landFloor, sprint, run, ended);
    }

    // with a via (vx, vz not NaN): the run and the first of the flight aimed at it, round whatever's between the takeoff and
    // the target, and only then at the target
    private static boolean jump(PlayerSim sim, Place place, Ledge a, double px, double pz, double ux, double uz, double tx, double tz,
                                double vx, double vz, int viaTicks, Ledge b, Landing land, double landFloor, boolean sprint, Run run, Ledge[] ended) {
        boolean pastVia = Double.isNaN(vx);
        // viaTicks -1: a step, walked onto the next ledge with no jump
        boolean step = viaTicks < 0;
        int offGround = 0;
        float lane = Keys.yaw(ux, uz);
        boolean jumped = false;
        int air = 0;
        BoxList floor = a.floor().area.floor;
        for (int t = 0; t < 80; t++) {
            PlayerSim.Controls c;
            if (!jumped && step) {
                c = new PlayerSim.Controls(1, 0, false, false, Keys.yaw(tx - sim.x, tz - sim.z));
            } else if (!jumped) {
                double ahead = (sim.x - px) * ux + (sim.z - pz) * uz;
                // the floor under the box a tick from now (whichever floor it's on: a run up can cross a stair's halves)
                // (a step down onto a stair's low half isn't the edge: anything within a step below still holds us up)
                boolean edge = !place.footedNear(sim.y, sim.x + sim.vx * 1.1, sim.z + sim.vz * 1.1);
                // (only off the ground: a tick of dropping down a stair's step on the way is no time to press it)
                boolean press = sim.onGround && (ahead >= -0.03 || edge);
                c = new PlayerSim.Controls(1, 0, press, sprint, lane);
            } else {
                if (!pastVia) {
                    // aimed at the via for the first viaTicks in the air (0: until it's reached), then at the target
                    double wx = tx - vx, wz = tz - vz, wl = hyp(wx, wz);
                    pastVia = viaTicks > 0 ? air >= viaTicks
                            : hyp(sim.x - vx, sim.z - vz) < 0.35 || wl > 1.0E-6 && ((sim.x - vx) * wx + (sim.z - vz) * wz) / wl > -0.05;
                }
                c = new PlayerSim.Controls(1, 0, false, sprint, pastVia ? Keys.yaw(tx - sim.x, tz - sim.z) : Keys.yaw(vx - sim.x, vz - sim.z));
            }
            double ox = sim.x, oy = sim.y, oz = sim.z;
            if (!Keys.step(sim, c)) {
                why = "sim";
                return false;
            }
            run.step(sim, c);
            if (sim.horizontalCollision && sprint) {
                why = !DEBUG ? "bumped" : String.format("bumped at %.2f %.2f %.2f%s (tick %d, from %.2f %.2f %.2f, yaw %.0f)", sim.x, sim.y, sim.z, jumped ? " in the air" : "",
                        t, ox, oy, oz, c.moveYaw());
                return false;
            }
            if (!jumped && step) {
                if (!sim.onGround) {
                    jumped = true; // off the edge: dropping onto the one below
                    air = 2;
                    continue;
                }
                if (Math.abs(sim.y - a.floor().h) < 1.0E-4) {
                    if (t > 40) {
                        why = "never got off";
                        return false;
                    }
                    continue;
                }
                // up (or down) a step without leaving the ground: on the next one
                jumped = true;
                air = 2;
            } else if (!jumped) {
                if (c.jump() && sim.vy > 0.2) {
                    jumped = true; // off
                    continue;
                }
                // (stepping up or down a stair's halves on the run up is fine, the ground's still there a tick later)
                if (!sim.onGround && (sim.vy > 0 || ++offGround > 6 || sim.y < a.floor().h - 0.75)) {
                    why = "ran off";
                    return false; // ran off the end before the jump
                }
                if (sim.onGround) {
                    offGround = 0;
                }
                continue;
            }
            if (!sim.onGround) {
                air++;
                if (sim.y < Math.min(a.floor().h, b == null ? landFloor : place.lowest()) - 1.5) {
                    why = !DEBUG ? "fell" : String.format("fell at %.2f %.2f", sim.x, sim.z);
                    return false;
                }
                continue;
            }
            if (air < 2) {
                continue; // still taking off
            }
            // down: where?
            if (b == null) {
                SimState s = SimState.of(sim);
                if (!JumpPolicy.onTheBlock(s, land) && !land.holds(sim.x, sim.y, sim.z)) {
                    why = !DEBUG ? "came down" : String.format("came down at %.2f %.2f %.2f", sim.x, sim.y, sim.z);
                    return false;
                }
                PlayerSim probe = sim.copy();
                List<PlayerSim.Controls> settle = TungstenSearch.settle(sim, probe, land);
                if (settle == null) {
                    why = "no settle";
                    return false;
                }
                for (PlayerSim.Controls k : settle) {
                    Keys.step(sim, k);
                    run.step(sim, k);
                }
                return land.reached(SimState.of(sim), JumpPolicy.STILL * 4) || land.holds(sim.x, sim.y, sim.z) && sim.onGround;
            }
            // down somewhere: stop, and see which ledge that is (onto the low step of a stair, the run carries on up it)
            if (!stop(sim, run)) {
                why = !DEBUG ? "didn't stop" : String.format("didn't stop after coming down at %.2f %.2f %.2f", sim.x, sim.y, sim.z);
                return false;
            }
            ended[0] = place.at(sim.x, sim.y, sim.z);
            if (ended[0] == null || ended[0] == a) {
                why = !DEBUG ? "stopped" : String.format("stopped at %.2f %.2f %.2f, not on a ledge", sim.x, sim.y, sim.z);
                return false;
            }
            return true;
        }
        return false;
    }

    // brake to a standstill where we are, staying on the ground
    private static boolean stop(PlayerSim sim, Run run) {
        for (int t = 0; t < 25; t++) {
            double speed = Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz);
            if (speed < JumpPolicy.STILL) {
                return true;
            }
            PlayerSim.Controls c = speed > 0.03 ? Keys.push(Keys.yaw(-sim.vx, -sim.vz), false) : Keys.release(Keys.yaw(sim.vx, sim.vz));
            if (!Keys.step(sim, c) || !sim.onGround) {
                return false;
            }
            run.step(sim, c);
        }
        return false;
    }

    // walk on the ledge from where sim is to (x, z) and stop there
    private static boolean walkTo(PlayerSim sim, Ledge a, double x, double z, Run run) {
        RimWalk.Grid g = a.floor().grid;
        int from = g.at(sim.x, sim.z), to = g.at(x, z);
        if (to >= 0 && (!g.ok[to] || a.floor().ledge[to] != a.id())) {
            // a run up that starts just off this ledge (on the next half of a stair): to the nearest of it, then on foot
            // the last bit, up or down the step
            int near = nearestFree(g, a, x, z, 0.45);
            if (near < 0) {
                why = "run up too far off the ledge";
                return false;
            }
            return walkTo(sim, a, g.x(near), g.z(near), run) && nudge(sim, x, z, a.floor().h, run);
        }
        if (from < 0 || to < 0) {
            why = "off the grid";
            return false;
        }
        if (hyp(sim.x - x, sim.z - z) < 0.08) {
            return true;
        }
        if (!g.ok[from]) {
            // stopped just off the free cells (a brush of the margin): from the nearest free one
            from = nearestFree(g, a, sim.x, sim.z);
            if (from < 0) {
                why = "nowhere free near the start";
                return false;
            }
        }
        List<double[]> path = g.route(from, to);
        if (path == null) {
            why = "no route on the ledge";
            return false;
        }
        double h = a.floor().h;
        int at = 0, ahead = (int) Math.round(0.3 / RimWalk.RES);
        for (int t = 0; t < 300; t++) {
            double ex = x - sim.x, ez = z - sim.z, d = Math.sqrt(ex * ex + ez * ez);
            double speed = Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz);
            if (d < 0.12 && speed < JumpPolicy.STILL) {
                return true;
            }
            PlayerSim.Controls c;
            if (at >= path.size() - 1 - ahead || d < 0.5) {
                // the last bit: ease up to it. a walk slides about its own speed times two once let go of
                double slide = speed * 2.2;
                if (d > slide + 0.05) {
                    c = Keys.push(Keys.yaw(ex, ez), false);
                } else if (speed > 0.06) {
                    c = Keys.push(Keys.yaw(-sim.vx, -sim.vz), false);
                } else {
                    c = Keys.release(Keys.yaw(ex, ez));
                }
            } else {
                double bestD = Double.MAX_VALUE;
                int bestI = at;
                for (int i = at; i < Math.min(path.size(), at + 40); i++) {
                    double dx = path.get(i)[0] - sim.x, dz = path.get(i)[1] - sim.z, dd = dx * dx + dz * dz;
                    if (dd < bestD) {
                        bestD = dd;
                        bestI = i;
                    }
                }
                at = bestI;
                double[] aim = path.get(Math.min(path.size() - 1, at + ahead));
                c = Keys.push(Keys.yaw(aim[0] - sim.x, aim[1] - sim.z), false);
            }
            if (!Keys.step(sim, c) || !sim.onGround || Math.abs(sim.y - h) > 1.0E-4 || sim.horizontalCollision) {
                why = !DEBUG ? "walk tick" : String.format("walk tick %d at %.2f %.2f %.2f%s%s", t, sim.x, sim.y, sim.z, sim.onGround ? "" : " off the ground", sim.horizontalCollision ? " bumped" : "");
                return false;
            }
            run.step(sim, c);
        }
        return false;
    }

    // the last few tenths to (x, z) on foot, up or down a step on the way, and stop
    private static boolean nudge(PlayerSim sim, double x, double z, double h, Run run) {
        for (int t = 0; t < 60; t++) {
            double ex = x - sim.x, ez = z - sim.z, d = Math.sqrt(ex * ex + ez * ez);
            double speed = Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz);
            if (d < 0.12 && speed < JumpPolicy.STILL && sim.onGround) {
                return true;
            }
            PlayerSim.Controls c = d > speed * 2.2 + 0.05 ? Keys.push(Keys.yaw(ex, ez), false)
                    : speed > 0.06 ? Keys.push(Keys.yaw(-sim.vx, -sim.vz), false) : Keys.release(Keys.yaw(ex, ez));
            if (!Keys.step(sim, c) || Math.abs(sim.y - h) > 0.65) {
                why = "nudge";
                return false;
            }
            run.step(sim, c);
        }
        why = "nudge never got there";
        return false;
    }

    private static int nearestFree(RimWalk.Grid g, Ledge a, double x, double z, double within) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int c : a.cells()) {
            double d = hyp(g.x(c) - x, g.z(c) - z);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return bd <= within ? best : -1;
    }

    private static int nearestFree(RimWalk.Grid g, Ledge a, double x, double z) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int c : a.cells()) {
            double d = hyp(g.x(c) - x, g.z(c) - z);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return bd < 0.2 ? best : -1;
    }

    // one edge played out from where sim stands: the walk to its run up, then the jump (again, from where the walk really
    // stopped), or the walk and settle onto the landing
    private static boolean playEdge(CollisionProvider world, Place place, PlayerSim sim, Edge e, Landing land, double landFloor, Run run,
                                    Ledge[] ended) {
        if (!walkTo(sim, e.from(), e.rx(), e.rz(), run)) {
            if (DEBUG) {
                System.out.printf("[ledges]   walk to %.2f %.2f on %.2f fails from %.2f %.2f (%s)%n", e.rx(), e.rz(), e.from().floor().h, sim.x, sim.z, why);
            }
            return false;
        }
        if (Double.isNaN(e.px())) {
            PlayerSim probe = sim.copy();
            List<PlayerSim.Controls> settle = TungstenSearch.settle(sim, probe, land);
            if (settle == null) {
                return false;
            }
            for (PlayerSim.Controls k : settle) {
                Keys.step(sim, k);
                run.step(sim, k);
            }
            return true;
        }
        double ax = Double.isNaN(e.vx()) ? e.tx() : e.vx(), az = Double.isNaN(e.vx()) ? e.tz() : e.vz();
        double al = hyp(ax - e.px(), az - e.pz());
        if (!jump(sim, place, e.from(), e.px(), e.pz(), (ax - e.px()) / al, (az - e.pz()) / al, e.tx(), e.tz(), e.vx(), e.vz(), e.viaTicks(), e.to(), land,
                landFloor, e.sprint(), run, ended)) {
            if (DEBUG) {
                System.out.printf("[ledges]   jump from %.2f %.2f to %.2f %.2f fails (%s)%n", e.px(), e.pz(), e.tx(), e.tz(), why);
            }
            return false;
        }
        return true;
    }
}
