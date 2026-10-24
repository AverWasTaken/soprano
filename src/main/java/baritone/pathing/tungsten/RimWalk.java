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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walking round something on the level: along the rim of a block with a wall or a fence on it, round the corner of a pillar,
 * round the outside of a walled platform. The player's box is 0.6 wide and only needs a sliver of floor under it, so the strip
 * of a block outside a wall post is somewhere to walk, but Baritone's grid can't stand there (the wall is in the cell), and a
 * search over keys takes seconds to find a walk. This finds it in a millisecond or two: where the box fits and has floor
 * under it, on a 0.05 grid; the shortest way through that keeping off the edges; and then the sim walks it, steering at a
 * point a little way along, and the pilot flies it with the aim wobbling to make sure it holds up.
 */
public final class RimWalk {

    // Math.hypot is careful about overflow and slow for it (a quarter of the background searches' time went there): these
    // are distances of a few blocks
    static double hyp(double a, double b) {
        return Math.sqrt(a * a + b * b);
    }

    private RimWalk() {}

    static final double RES = 0.05;
    // how far out of the straight line's box the walk may go
    static final double SPREAD = 2.0;
    // the box kept this far off anything in the way, and this much of it over the floor, at least: room for a wobble
    static final double[] MARGINS = {0.08, 0.04};
    static final double[] FOOTING = {0.12, 0.07};
    static final double HALF = 0.3, HEIGHT = 1.8;
    // a plain walk round a corner: a few seconds at most
    static final int MAX_TICKS = 160;
    static final double[] LOOK_AHEADS = {0.55, 0.3};
    static final boolean DEBUG = System.getenv("SOPRANO_RIMWALK_DEBUG") != null;
    private static String lastWhy = "";

    // answers by src, dest and the blocks round them: a planner asks the same pair over and over
    private static final Map<String, Object[]> MEMO = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 2000;
        }
    };
    // keyed by the blocks round it too, so it can be kept a while: the same corner comes up search after search
    private static final long MEMO_MS = 60_000;
    // a walk is steered all the way, a wobble gets put right as it goes: fewer flights than a jump needs
    static final int FLIGHTS = 3;

    public static TungstenSearch.Plan plan(CollisionProvider world, BetterBlockPos src, double floorY, Landing land) {
        if (land.grab() || Math.abs(land.y() - src.y) > 1) {
            return null;
        }
        Area area = new Area(world, src, floorY, land);
        String key = src.x + "," + src.y + "," + src.z + ">" + land.x() + "," + land.y() + "," + land.z() + "@" + floorY + "#" + area.hash;
        long now = System.currentTimeMillis();
        synchronized (MEMO) {
            Object[] known = MEMO.get(key);
            if (known != null && now - (Long) known[0] < MEMO_MS) {
                return (TungstenSearch.Plan) known[1];
            }
        }
        TungstenSearch.Plan plan = null;
        for (int i = 0; i < MARGINS.length && plan == null; i++) {
            Grid grid = new Grid(area, MARGINS[i], FOOTING[i]);
            double[] at = TungstenJumps.standAt(world, src.x, src.y, src.z);
            int start = grid.at(src.x + at[0], src.z + at[1]);
            if (start < 0 || !grid.ok[start]) {
                if (DEBUG) {
                    System.out.println("[rim walk] margin " + MARGINS[i] + ": the start doesn't fit");
                }
                continue;
            }
            // all the way on foot, if the landing's on the same floor
            if (land.y() == src.y) {
                int goal = grid.landingCell(land);
                List<double[]> path = goal < 0 ? null : grid.route(start, goal);
                if (DEBUG) {
                    System.out.println("[rim walk] margin " + MARGINS[i] + ": " + (goal < 0 ? "nowhere free on the landing" : path == null ? "no way through" : path.size() + " points"));
                }
                if (path != null) {
                    plan = drive(world, src, floorY, land, path, null);
                }
            }
            // or along the rim to somewhere the jump's clear from, and the rule's jump from there
            if (plan == null) {
                plan = walkThenJump(world, src, floorY, land, grid, start);
            }
        }
        synchronized (MEMO) {
            MEMO.put(key, new Object[]{now, plan});
        }
        return plan;
    }

    // sprinting it first (quicker), then walking it (tighter round the corners); each steering at a point further along,
    // then nearer. ending with the settle on the landing, or (jump != null) with a stop and then the rule's jump
    private static TungstenSearch.Plan drive(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, List<double[]> path,
                                             Landing jumpTo) {
        for (boolean sprint : new boolean[]{true, false}) {
            for (double ahead : LOOK_AHEADS) {
                long w0 = baritone.pathing.sim.PlayerSim.ticks();
                TungstenSearch.Plan p = walk(world, src, floorY, land, path, sprint, ahead, jumpTo);
                long w1 = baritone.pathing.sim.PlayerSim.ticks();
                boolean flies = p != null && NeoSearches.fliesEveryTime(world, src, floorY, jumpTo != null ? jumpTo : land, p, FLIGHTS);
                if (TungstenJumps.PROFILE) {
                    PROFILE[0] += w1 - w0;
                    PROFILE[1] += baritone.pathing.sim.PlayerSim.ticks() - w1;
                    PROFILE[jumpTo != null ? 2 : 3]++;
                }
                if (DEBUG) {
                    System.out.println("[rim walk]   " + (sprint ? "sprint" : "walk") + " ahead " + ahead + (jumpTo != null ? " then jump" : "") + ": "
                            + (p == null ? "sim fails: " + lastWhy : p.ticks() + " ticks, flights " + flies));
                }
                if (flies) {
                    return p;
                }
            }
        }
        return null;
    }

    // how many places along the rim to try jumping from
    static final int TAKEOFFS = 2;
    // walk ticks, flight ticks, walk-then-jump drives, walk-only drives (TUNGSTEN_PROFILE)
    public static final long[] PROFILE = new long[4];

    private static TungstenSearch.Plan walkThenJump(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, Grid grid, int start) {
        // everywhere reachable on foot from the start
        boolean[] reach = grid.reachable(start);
        // nearest the landing first, a few, not all in the same spot, with nothing between them and it
        List<int[]> spots = new ArrayList<>();
        Integer[] order = new Integer[grid.ok.length];
        int n = 0;
        for (int c = 0; c < grid.ok.length; c++) {
            if (reach[c] && grid.room[c] >= 2) {
                order[n++] = c;
            }
        }
        Integer[] cells = Arrays.copyOf(order, n);
        Arrays.sort(cells, (a, b) -> Double.compare(grid.distance(a, land), grid.distance(b, land)));
        for (int c : cells) {
            if (spots.size() >= TAKEOFFS) {
                break;
            }
            double x = grid.x(c), z = grid.z(c);
            boolean near = false;
            for (int[] s : spots) {
                if (hyp(grid.x(s[0]) - x, grid.z(s[0]) - z) < 0.35) {
                    near = true;
                    break;
                }
            }
            if (near || JumpPolicy.blocked(world, x, floorY, z, land.centerX(), land.y(), land.centerZ())) {
                continue;
            }
            spots.add(new int[]{c});
        }
        for (int[] s : spots) {
            List<double[]> path = s[0] == start ? List.<double[]>of(new double[]{grid.x(start), grid.z(start)}) : grid.route(start, s[0]);
            if (path == null) {
                continue;
            }
            if (DEBUG) {
                System.out.printf("[rim walk] jump from %.2f %.2f (%d points)%n", grid.x(s[0]), grid.z(s[0]), path.size());
            }
            // the walk's own "landing" is just where the floor is, it's never settled on
            TungstenSearch.Plan p = drive(world, src, floorY, new Landing(src.x, src.y, src.z), path, land);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    // the boxes round src and the landing, split into floor (tops at floorY) and in the way, and a hash of them all
    static final class Area {
        final double x0, z0, x1, z1, floorY;
        final BoxList solid = new BoxList(), floor = new BoxList();
        final long hash;

        Area(CollisionProvider world, BetterBlockPos src, double floorY, Landing land) {
            double ax = src.x + 0.5, az = src.z + 0.5, bx = land.centerX(), bz = land.centerZ();
            this.floorY = floorY;
            x0 = Math.min(ax, bx) - SPREAD;
            z0 = Math.min(az, bz) - SPREAD;
            x1 = Math.max(ax, bx) + SPREAD;
            z1 = Math.max(az, bz) + SPREAD;
            BoxList cell = new BoxList();
            long h = 1469598103934665603L;
            int fy = Mth.floor(floorY - 1.0E-3);
            for (int x = Mth.floor(x0 - 1); x <= Mth.floor(x1 + 1); x++) {
                for (int z = Mth.floor(z0 - 1); z <= Mth.floor(z1 + 1); z++) {
                    for (int y = fy; y <= Mth.floor(floorY + HEIGHT); y++) {
                        cell.clear();
                        world.boxes(x, y, z, cell);
                        for (int b = 0; b < cell.size(); b++) {
                            double minY = cell.get(b, 1), maxY = cell.get(b, 4);
                            for (int c = 0; c < 6; c++) {
                                h = (h ^ Double.doubleToLongBits(cell.get(b, c))) * 1099511628211L;
                            }
                            if (Math.abs(maxY - floorY) < 1.0E-4) {
                                floor.add(cell.get(b, 0), minY, cell.get(b, 2), cell.get(b, 3), maxY, cell.get(b, 5));
                            } else if (maxY > floorY + 1.0E-3 && minY < floorY + HEIGHT) {
                                solid.add(cell.get(b, 0), minY, cell.get(b, 2), cell.get(b, 3), maxY, cell.get(b, 5));
                            }
                        }
                        // what the floor's made of matters to a walk too (ice, slime, soul sand)
                        h = (h ^ (Float.floatToIntBits(world.friction(x, y, z)) * 31L + Float.floatToIntBits(world.speedFactor(x, y, z)))) * 1099511628211L;
                    }
                }
            }
            hash = h;
        }

        // the same over a box already gathered (all), for a floor at floorY: Ledges has several floors in one place
        Area(BoxList all, double x0, double z0, double x1, double z1, double floorY) {
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.floorY = floorY;
            for (int b = 0; b < all.size(); b++) {
                double minY = all.get(b, 1), maxY = all.get(b, 4);
                if (all.get(b, 3) < x0 - 1 || all.get(b, 0) > x1 + 1 || all.get(b, 5) < z0 - 1 || all.get(b, 2) > z1 + 1) {
                    continue;
                }
                if (Math.abs(maxY - floorY) < 1.0E-4) {
                    floor.add(all.get(b, 0), minY, all.get(b, 2), all.get(b, 3), maxY, all.get(b, 5));
                } else if (maxY > floorY + 1.0E-3 && minY < floorY + HEIGHT) {
                    solid.add(all.get(b, 0), minY, all.get(b, 2), all.get(b, 3), maxY, all.get(b, 5));
                }
            }
            hash = 0;
        }
    }

    // boxes filed by the block columns they cover
    static final class Columns {
        final BoxList boxes;
        final int cx0, cz0, ncx, ncz;
        final int[][] lists;

        Columns(BoxList boxes, double x0, double z0, double x1, double z1) {
            this.boxes = boxes;
            cx0 = Mth.floor(x0);
            cz0 = Mth.floor(z0);
            ncx = Mth.floor(x1) - cx0 + 1;
            ncz = Mth.floor(z1) - cz0 + 1;
            int[] counts = new int[ncx * ncz];
            int[][] lists = null;
            for (int pass = 0; pass < 2; pass++) {
                for (int b = 0; b < boxes.size(); b++) {
                    int ax = Math.max(0, Mth.floor(boxes.get(b, 0)) - cx0), bx = Math.min(ncx - 1, Mth.floor(boxes.get(b, 3) - 1.0E-9) - cx0);
                    int az = Math.max(0, Mth.floor(boxes.get(b, 2)) - cz0), bz = Math.min(ncz - 1, Mth.floor(boxes.get(b, 5) - 1.0E-9) - cz0);
                    for (int i = ax; i <= bx; i++) {
                        for (int k = az; k <= bz; k++) {
                            int c = i * ncz + k;
                            if (pass == 0) {
                                counts[c]++;
                            } else {
                                lists[c][--counts[c]] = b;
                            }
                        }
                    }
                }
                if (pass == 0) {
                    lists = new int[ncx * ncz][];
                    for (int c = 0; c < counts.length; c++) {
                        lists[c] = new int[counts[c]];
                    }
                }
            }
            this.lists = lists;
        }

        // any box overlapping the square half either side of (x, z), in plan
        boolean any(double x, double z, double half) {
            int ax = Math.max(0, Mth.floor(x - half) - cx0), bx = Math.min(ncx - 1, Mth.floor(x + half) - cx0);
            int az = Math.max(0, Mth.floor(z - half) - cz0), bz = Math.min(ncz - 1, Mth.floor(z + half) - cz0);
            for (int i = ax; i <= bx; i++) {
                for (int k = az; k <= bz; k++) {
                    for (int b : lists[i * ncz + k]) {
                        if (boxes.get(b, 0) < x + half && boxes.get(b, 3) > x - half && boxes.get(b, 2) < z + half && boxes.get(b, 5) > z - half) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    // where the box fits (MARGIN clear of anything in the way) and has FOOTING of floor under it, on a RES grid over the area,
    // and how far each such spot is from one that isn't
    static final class Grid {
        final double x0, z0;
        final int nx, nz;
        final boolean[] ok;
        final int[] room;

        Grid(Area area, double margin, double footing) {
            x0 = area.x0;
            z0 = area.z0;
            nx = (int) Math.round((area.x1 - x0) / RES) + 1;
            nz = (int) Math.round((area.z1 - z0) / RES) + 1;
            ok = new boolean[nx * nz];
            // the boxes by block column, so a spot only looks at the few next to it (a big area has thousands)
            Columns solid = new Columns(area.solid, x0 - 1, z0 - 1, area.x1 + 1, area.z1 + 1);
            Columns floor = new Columns(area.floor, x0 - 1, z0 - 1, area.x1 + 1, area.z1 + 1);
            for (int i = 0; i < nx; i++) {
                double x = x0 + i * RES;
                for (int k = 0; k < nz; k++) {
                    double z = z0 + k * RES;
                    ok[i * nz + k] = !solid.any(x, z, HALF + margin) && floor.any(x, z, HALF - footing);
                }
            }
            room = new int[nx * nz];
            Arrays.fill(room, Integer.MAX_VALUE);
            // breadth first out from every spot that isn't free: each spot goes in the queue once, so a flat array does
            int[] q = new int[ok.length];
            int head = 0, tail = 0;
            for (int c = 0; c < ok.length; c++) {
                if (!ok[c]) {
                    room[c] = 0;
                    q[tail++] = c;
                }
            }
            while (head < tail) {
                int c = q[head++], i = c / nz, k = c % nz;
                for (int d = 0; d < 4; d++) {
                    int ii = i + (d == 0 ? 1 : d == 1 ? -1 : 0), kk = k + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (ii < 0 || kk < 0 || ii >= nx || kk >= nz) {
                        continue;
                    }
                    int m = ii * nz + kk;
                    if (room[m] > room[c] + 1) {
                        room[m] = room[c] + 1;
                        q[tail++] = m;
                    }
                }
            }
        }

        int at(double x, double z) {
            int i = (int) Math.round((x - x0) / RES), k = (int) Math.round((z - z0) / RES);
            return i < 0 || k < 0 || i >= nx || k >= nz ? -1 : i * nz + k;
        }

        double x(int c) {
            return x0 + (c / nz) * RES;
        }

        double z(int c) {
            return z0 + (c % nz) * RES;
        }

        double distance(int c, Landing land) {
            return hyp(x(c) - land.centerX(), z(c) - land.centerZ());
        }

        // the free spot nearest the middle of the landing, well inside its cell, or -1
        int landingCell(Landing land) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int c = 0; c < ok.length; c++) {
                double x = x(c), z = z(c);
                if (!ok[c] || x < land.x() + 0.2 || x > land.x() + 0.8 || z < land.z() + 0.2 || z > land.z() + 0.8) {
                    continue;
                }
                double d = distance(c, land);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            return best;
        }

        boolean[] reachable(int start) {
            boolean[] seen = new boolean[ok.length];
            int[] q = new int[ok.length];
            int head = 0, tail = 0;
            seen[start] = true;
            q[tail++] = start;
            while (head < tail) {
                int c = q[head++], i = c / nz, k = c % nz;
                for (int di = -1; di <= 1; di++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int ii = i + di, kk = k + dk;
                        if (ii < 0 || kk < 0 || ii >= nx || kk >= nz) {
                            continue;
                        }
                        int m = ii * nz + kk;
                        if (ok[m] && !seen[m]) {
                            seen[m] = true;
                            q[tail++] = m;
                        }
                    }
                }
            }
            return seen;
        }

        // A*, eight ways, a step near an edge costing more. points RES apart from start to goal, or null
        List<double[]> route(int start, int goal) {
            double[] g = new double[nx * nz];
            Arrays.fill(g, Double.MAX_VALUE);
            int[] from = new int[nx * nz];
            int gi = goal / nz, gk = goal % nz;
            g[start] = 0;
            IntHeap open = new IntHeap();
            open.add(0, start);
            int comfortable = (int) Math.ceil(0.25 / RES);
            boolean found = false;
            while (!open.isEmpty()) {
                double topKey = open.peekKey();
                int c = open.poll();
                if (c == goal) {
                    found = true;
                    break;
                }
                if (topKey > g[c] + hyp(c / nz - gi, c % nz - gk) + 1.0E-9) {
                    continue;
                }
                int i = c / nz, k = c % nz;
                for (int di = -1; di <= 1; di++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int ii = i + di, kk = k + dk;
                        if ((di == 0 && dk == 0) || ii < 0 || kk < 0 || ii >= nx || kk >= nz || !ok[ii * nz + kk]) {
                            continue;
                        }
                        int m = ii * nz + kk;
                        double step = (di != 0 && dk != 0 ? 1.4142 : 1) * (1 + 2.0 * Math.max(0, comfortable - room[m]) / comfortable);
                        if (g[c] + step < g[m]) {
                            g[m] = g[c] + step;
                            from[m] = c;
                            open.add(g[m] + hyp(ii - gi, kk - gk), m);
                        }
                    }
                }
            }
            if (!found) {
                return null;
            }
            List<double[]> path = new ArrayList<>();
            for (int c = goal; ; c = from[c]) {
                path.add(new double[]{x(c), z(c)});
                if (c == start) {
                    break;
                }
            }
            java.util.Collections.reverse(path);
            return path;
        }
    }

    static boolean fits(BoxList solid, double x, double z, double half) {
        for (int b = 0; b < solid.size(); b++) {
            if (solid.get(b, 0) < x + half && solid.get(b, 3) > x - half && solid.get(b, 2) < z + half && solid.get(b, 5) > z - half) {
                return false;
            }
        }
        return true;
    }

    static boolean footed(BoxList floor, double x, double z, double half) {
        for (int b = 0; b < floor.size(); b++) {
            if (floor.get(b, 0) < x + half && floor.get(b, 3) > x - half && floor.get(b, 2) < z + half && floor.get(b, 5) > z - half) {
                return true;
            }
        }
        return false;
    }

    // the sim walking the path: W held, facing a point LOOK_AHEAD along it; on the landing, JumpPolicy's settle
    static TungstenSearch.Plan walk(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, List<double[]> path, boolean sprint,
                                    double lookAhead) {
        return walk(world, src, floorY, land, path, sprint, lookAhead, null);
    }

    // with jumpTo: walk to the end of the path, stop there, and then the rule's jump at jumpTo from a standstill
    static TungstenSearch.Plan walk(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, List<double[]> path, boolean sprint,
                                    double lookAhead, Landing jumpTo) {
        long before = PlayerSim.ticks();
        PlayerSim sim = NeoSearches.standing(world, src, floorY);
        PlayerSim probe = sim.copy();
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        states.add(SimState.of(sim));
        int at = 0, ahead = (int) Math.round(lookAhead / RES);
        double[] end = path.get(path.size() - 1);
        for (int t = 0; t < MAX_TICKS; t++) {
            if (jumpTo != null && at >= path.size() - 1 - ahead) {
                // the last bit: walk up to the spot, brake, stand still, jump
                double ex = end[0] - sim.x, ez = end[1] - sim.z, d = Math.sqrt(ex * ex + ez * ez);
                double speed = Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz);
                if (d < 0.25 && speed < JumpPolicy.STILL) {
                    JumpPolicy.Rollout jump = JumpPolicy.forgiving(sim, jumpTo);
                    if (jump == null) {
                        lastWhy = "no jump from the spot";
                        return null;
                    }
                    keys.addAll(jump.keys());
                    states.addAll(jump.states().subList(1, jump.states().size()));
                    return new TungstenSearch.Plan(keys, states, 0, PlayerSim.ticks() - before);
                }
                PlayerSim.Controls c;
                if (d > 0.12 && speed < 0.12) {
                    c = Keys.push(Keys.yaw(ex, ez), false);
                } else if (speed > 0.03) {
                    // W facing back against the way we're sliding: the quickest stop there is
                    c = Keys.push(Keys.yaw(-sim.vx, -sim.vz), false);
                } else {
                    c = Keys.release(Keys.yaw(ex, ez));
                }
                if (!Keys.step(sim, c) || sim.horizontalCollision || !sim.onGround || Math.abs(sim.y - floorY) > 1.0E-6) {
                    lastWhy = String.format("stopping, tick %d at %.3f %.3f %.3f%s%s", t, sim.x, sim.y, sim.z, sim.horizontalCollision ? " bumped" : "", sim.onGround ? "" : " off the floor");
                    return null;
                }
                keys.add(c);
                states.add(SimState.of(sim));
                continue;
            }
            if (jumpTo == null && at >= path.size() - 1 - ahead / 2 && JumpPolicy.onTheBlock(SimState.of(sim), land)) {
                List<PlayerSim.Controls> settle = TungstenSearch.settle(sim, probe, land);
                if (settle == null) {
                    lastWhy = "no settle";
                    return null;
                }
                for (PlayerSim.Controls c : settle) {
                    Keys.step(sim, c);
                    keys.add(c);
                    states.add(SimState.of(sim));
                }
                return keys.isEmpty() ? null : new TungstenSearch.Plan(keys, states, 0, PlayerSim.ticks() - before);
            }
            // the nearest point of the path from here on (not back: a path that doubles back is the point)
            double bestD = Double.MAX_VALUE;
            int bestI = at;
            for (int i = at; i < Math.min(path.size(), at + 40); i++) {
                double dx = path.get(i)[0] - sim.x, dz = path.get(i)[1] - sim.z, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    bestI = i;
                }
            }
            at = bestI;
            double[] aim = path.get(Math.min(path.size() - 1, at + ahead));
            // a sharp turn coming up: no sprint into it
            boolean run = sprint && turnAhead(path, at) < 35;
            PlayerSim.Controls c = Keys.push(Keys.yaw(aim[0] - sim.x, aim[1] - sim.z), run);
            if (!Keys.step(sim, c) || sim.horizontalCollision || !sim.onGround || Math.abs(sim.y - floorY) > 1.0E-6) {
                lastWhy = String.format("tick %d at %.3f %.3f %.3f%s%s", t, sim.x, sim.y, sim.z, sim.horizontalCollision ? " bumped" : "", sim.onGround ? "" : " off the floor");
                return null;
            }
            keys.add(c);
            states.add(SimState.of(sim));
        }
        lastWhy = "out of time";
        return null;
    }

    // the most the path turns in the next block and a half, in degrees
    private static double turnAhead(List<double[]> path, int at) {
        int step = (int) Math.round(0.5 / RES);
        double most = 0;
        for (int i = at; i + 2 * step < path.size() && i < at + 3 * step; i += step) {
            double[] a = path.get(i), b = path.get(i + step), c = path.get(i + 2 * step);
            double h1 = Math.atan2(b[1] - a[1], b[0] - a[0]), h2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
            double d = h2 - h1;
            while (d > Math.PI) {
                d -= 2 * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            most = Math.max(most, Math.abs(Math.toDegrees(d)));
        }
        return most;
    }
}
